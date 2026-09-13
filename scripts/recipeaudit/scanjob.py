"""把"对一个 S 的判定"从报告层拆出来，并给它一个进程池。

【为什么是进程不是线程】判定是纯 Python 的 CPU 密集计算，默认构建（GIL 开启）下线程拿不到并行。
Windows 的 start method 是 spawn，因此有两条不能违反的规矩：
  · worker 函数必须是**模块级可 pickle 的**（不能是闭包 / lambda）；
  · 调用方（`recipeaudit/__main__.py`）必须有 `if __name__ == "__main__":` 守卫（已有）。
另有一条只在 Windows 上成立的代价：每个 worker 各自反序列化一份 export（内存 × 进程数）——
Linux 的 fork 能靠 COW 省掉，但本项目的验证环境是 Windows。
"""
from __future__ import annotations

import time
from concurrent.futures import FIRST_COMPLETED, Future, ProcessPoolExecutor, wait
from dataclasses import dataclass, field

from recipeaudit.criterion import (AlphaEnumerationOverflow, analyse, slot_candidates_for)
from recipeaudit.model import Export
from recipeaudit.reach import ScanDeadlineExceeded, build_trie, reach

__all__ = ["ScanOutcome", "decide_one", "run_plan"]

# 每批几条 S。**必须小**：单条 S 的代价差异极大（大 |S| 的 α 枚举比小 |S| 贵几个数量级），
# 批一大，"最贵的那条"就把整批拖住，负载不均会把并行的收益吃掉。
_CHUNK = 8

# 每判定多少组报一次进度。**做成模块常量是为了能被测试调小**：fixture 的计划只有十几组，
# 写死 100 的话进度回调一次都不会触发，那条测试就成了空转（本项目对"空转断言"是一票否决的）。
_PROGRESS_EVERY = 100

# 每个 worker 允许在飞的批数。太小会饿着 worker（结果回来之前它空转），
# 太大则超时后要取消的队列很长、且异常发生前的无用功更多。
_INFLIGHT_BATCHES_PER_WORKER = 2


@dataclass(frozen=True)
class ScanOutcome:
    """单个 S 的判定结果。`kind` 的四个取值就是 `report.scan` 的全部记账口径。"""

    kind: str                      # "ok" | "overflow" | "timeout" | "skipped"
    findings: tuple = ()
    unreachable_self: tuple = ()
    witness_alphas: dict = field(default_factory=dict)


def decide_one(export: Export, handle, source_ids, deadline: float) -> ScanOutcome:
    """判定单个 S。**串行与并行共用这一份**，所以两条路径的行为不可能分叉。

    【`unreachable_self` 为什么在这里算】它依赖本次 `reach` 的结果，而本函数是唯一算 `reach`
    的地方；留在 `report.scan` 里就得把 `reach` 再跑一遍（那是这个函数里最贵的一步）。
    """
    by_id = export.recipe_by_id()
    sources = [by_id[rid] for rid in source_ids]
    try:
        reachable_ids = reach(handle, slot_candidates_for(export, sources), deadline=deadline)
        sink: dict = {}
        findings = analyse(export, list(source_ids), reachable_ids=reachable_ids,
                           deadline=deadline, alpha_sink=sink)
    except ScanDeadlineExceeded:
        # 时间预算在判定途中耗尽。**部分结果一律丢弃**（spec 9.9）：只判了一半的 S
        # 印出冲突清单，读者会以为它被完整判过。
        return ScanOutcome(kind="timeout")
    except AlphaEnumerationOverflow:
        # α 枚举超预算：与超时同属"没判定"，但来源不同，故 kind 单列。
        return ScanOutcome(kind="overflow")
    return ScanOutcome(
        kind="ok",
        findings=tuple(findings),
        unreachable_self=tuple(rid for rid in source_ids if rid not in reachable_ids),
        witness_alphas=sink,
    )


# worker 进程内的状态。只在 `_worker_init` 里写一次，之后只读。
_WORKER: tuple | None = None


def _worker_init(export: Export, scope: str, budget_seconds: float) -> None:
    """每个 worker 进程一次：自己的 export、自己的 trie、自己的截止时刻。

    【截止时刻必须各进程自己换算】父进程传的是**剩余预算（相对秒数）**，不是绝对时刻：
    `time.monotonic()` 的 epoch 是否跨进程可比**不是 Python 的保证**（Windows 上恰好可比，
    但别把平台的巧合写进契约）。每批判定前都用本进程的 `time.monotonic()` 比一次。
    """
    global _WORKER
    _WORKER = (export, build_trie(export.trie), time.monotonic() + budget_seconds)


def _worker_run(batch: list[tuple[int, ...]]) -> list[ScanOutcome]:
    """跑一批 S，**逐条**返回结果：批内某条超时不影响同批其它条的结果。

    【为什么不按条提交 / 为什么不抛异常】按条提交时每条的 pickle 与调度开销不再可忽略；
    按批提交若把异常直接抛出去，一次超时会**连带丢掉同批已完成**的那些 S——那正是
    "半个结果不能当结果用"要避免的静默少报。所以异常在 `decide_one` 里就被收敛成 kind。
    """
    assert _WORKER is not None, "worker 未初始化：必须经由 _worker_init 启动"
    export, handle, deadline = _WORKER
    out: list[ScanOutcome] = []
    for source_ids in batch:
        if time.monotonic() >= deadline:
            # 预算已尽 -> **不再开始**新的 S：记为 skipped（既不算"完成"，也不算"超时判定"）。
            out.append(ScanOutcome(kind="skipped"))
            continue
        out.append(decide_one(export, handle, source_ids, deadline))
    return out


def run_plan(export: Export, planned: list[tuple[int, ...]], handle, deadline: float,
             scope: str, workers: int,
             progress=None) -> list[ScanOutcome]:
    """按 `planned` 的**下标**回填结果，返回等长数组。

    【为什么按下标而不是按完成顺序】报告要可复现：`findings` 的顺序、`witness_alphas` 的
    合并顺序都会影响渲染。并行只许改速度，所以结果一律回到它原来那一格。
    """
    total = len(planned)
    outcomes: list[ScanOutcome | None] = [None] * total
    started = time.monotonic()
    # 【进度报的是"判定了多少组"，**不是**"走到哪个下标"】`note` 的下标是**回填位置**，
    # 并行下按完成顺序乱序出现：实测 CLI 曾同时印出"99.7%"与"完成 51.3%"——那个 99.7% 是下标。
    # 所以这里独立计数，且只数**会计入 `completed` 的那两类**（`ok` / `overflow`），
    # 于是最后一次打印的百分数与 `result.coverage` 口径一致。
    # 用 `decided - last_reported >= step` 而不是 `decided % step == 0`：并行下 `decided`
    # 一次可能跨过整百（98 -> 105），取模会**整批漏报**，差值比较不会。
    decided = 0
    last_reported = 0
    report_step = _PROGRESS_EVERY

    def note(index: int, outcome: ScanOutcome) -> None:
        nonlocal decided, last_reported
        outcomes[index] = outcome
        if outcome.kind in ("ok", "overflow"):
            decided += 1
        if progress is not None and decided - last_reported >= report_step:
            last_reported = decided
            progress(decided, total, time.monotonic() - started)

    if workers <= 1:
        for index, source_ids in enumerate(planned):
            # 【组间看钟】真正的界在 `decide_one` 内部（它把 deadline 传给了 reach 与 analyse），
            # 这里只是省下一次进入判定的开销。
            if time.monotonic() >= deadline:
                note(index, ScanOutcome(kind="skipped"))
                continue
            note(index, decide_one(export, handle, source_ids, deadline))
    else:
        budget = max(0.0, deadline - time.monotonic())
        with ProcessPoolExecutor(max_workers=workers, initializer=_worker_init,
                                 initargs=(export, scope, budget)) as pool:
            inflight: dict[Future, int] = {}
            cursor = 0
            window = max(1, workers * _INFLIGHT_BATCHES_PER_WORKER)

            def fill() -> None:
                nonlocal cursor
                while cursor < total and len(inflight) < window and time.monotonic() < deadline:
                    batch = planned[cursor:cursor + _CHUNK]
                    inflight[pool.submit(_worker_run, batch)] = cursor
                    cursor += len(batch)

            fill()
            while inflight:
                done, _pending = wait(list(inflight), return_when=FIRST_COMPLETED)
                for future in done:
                    start = inflight.pop(future)
                    # worker 里的异常在这里**大声抛出**（与串行路径一致：意外异常不吞）
                    for offset, outcome in enumerate(future.result()):
                        note(start + offset, outcome)
                fill()

    # 没派出去的一律记为 skipped：**不是"完成"**，覆盖率会如实低下来（spec 9.1 的纪律）。
    return [outcome if outcome is not None else ScanOutcome(kind="skipped")
            for outcome in outcomes]
