from __future__ import annotations

import time
from dataclasses import dataclass, field
from itertools import product
from typing import Callable

from recipeaudit.criterion import (
    AlphaEnumerationOverflow,
    ScanDeadlineExceeded,
    kinds,
    minimal_alphas,
    output_vector,
)
from recipeaudit.model import Export, Recipe
from recipeaudit.reach import build_trie
from recipeaudit.scanjob import run_plan

__all__ = ["SCOPE_ALL", "SCOPE_PER_CIRCUIT", "SCOPES", "ScanResult", "render_markdown",
           "render_summary", "scan", "scope_note"]

# 扫描口径（spec 4.5）。默认 `per-circuit`：只查询"布局里的电路配置不超过一种"的 S。
#
# 【为什么这是精确的限制而不是启发式】电路的槽位候选 token 带配置号、且电路入口没有通配形态
# （实测：2107 条电路入口各恰好 2 个候选、都带 {Configuration:N}；0 条通配；0 条配方带两个
# 电路入口；0 条配方产出电路）。于是"布局里只含配置 g 的电路"直接蕴含"配置 g' 的配方不可能
# 匹配"——`Reach` 本身就是那个过滤，这里不需要另加过滤，只需要把**源域**限到 组(g) ∪ 组(0)。
#
# 【两个口径不能混说】`all` 的结论强度不同（永远只是有界扫描），所以口径随 ScanResult 落盘、
# 由报告印出来，见 scope_note。
SCOPE_PER_CIRCUIT = "per-circuit"
SCOPE_ALL = "all"
SCOPES = (SCOPE_PER_CIRCUIT, SCOPE_ALL)


def scope_note(scope: str) -> str:
    """口径的人类可读说明。CLI 与报告**共用这一份**，免得两处措辞漂移。"""
    if scope == SCOPE_PER_CIRCUIT:
        return ("口径：按电路分组（per-circuit）——只考虑输入里最多一种编程电路的布局；"
                "输入同时含多种编程电路的布局不在本轮范围内（spec 4.5）")
    if scope == SCOPE_ALL:
        return ("口径：全量（all）——任意线性组合的输入，含同时含多种编程电路的布局；"
                "此口径下永远只是有界扫描，覆盖率见下方进度")
    raise ValueError(f"unknown scope {scope!r}; expected one of {SCOPES}")


@dataclass
class ScanResult:
    planned: int = 0
    completed: int = 0
    elapsed_seconds: float = 0.0
    truncated: bool = False
    max_source_set_size: int = 0
    # 本轮口径（spec 4.5）。**必须随结果落盘**：报告要能读出"这是单电路口径"，
    # 否则读者会把结论当成"任何输入都不会冲突"。
    scope: str = SCOPE_PER_CIRCUIT
    # 本轮用了几个进程（1 = 串行）。它只影响速度，不影响任何判定；印在 coverage_note 里，
    # 好让读者分辨"这轮为什么快/慢"。
    workers: int = 1
    findings: list[tuple[tuple[int, ...], list[tuple[int, int | None]]]] = field(default_factory=list)
    ghosts: list[int] = field(default_factory=list)
    dead_inputs: list[int] = field(default_factory=list)
    unreachable_self: list[int] = field(default_factory=list)
    # α 枚举超预算、判不了的 S。**必须与 findings 分开记**，报告里也要单独列出——
    # 它们是"没判定"，不是"没问题"（spec §9.9）。
    undecided: list[tuple[int, ...]] = field(default_factory=list)
    # **时间**预算在判定途中耗尽、判不了的 S。与 `undecided` 同属"没判定"，但来源不同
    # （规模 vs 墙钟），所以**另立一个字段**：合在一起就再也分不清是"枚举炸了"还是
    # "时间到了"，报告也就印不出区别。两处都要单列，绝不能混进"已扫完"。
    timed_out: list[tuple[int, ...]] = field(default_factory=list)
    # scan 期间算出的、每个见证命中的极小 α：键 (S, witness_id) -> minimal_alphas 的结果。
    # 存在的唯一理由是让 `render_markdown` **直接取用**而不是重算——实测 29.1 MB 的报告
    # 为每个见证重算一遍要跑 34 分钟。它是**缓存不是真值**：删掉它只影响渲染速度，
    # 不影响任何判定（`analyse` 只往里写、不读）。
    witness_alphas: dict[tuple[tuple[int, ...], int], tuple[tuple[int, ...], ...]] = \
        field(default_factory=dict)
    coverage_note: str = ""

    @property
    def coverage(self) -> float:
        return self.completed / self.planned if self.planned else 1.0


# 同一掩码下取几个配方当代表。这是扫描唯一的近似来源（另一处是时间预算），调大它换更完备。
_REPS_PER_MASK = 2


def _minimal_covers(by_mask: dict[int, list[int]], full: int) -> set[tuple[int, ...]]:
    """枚举使掩码并集铺满 full 的覆盖组合（**增量极小**，不是全局极小——见下）。

    递归：取当前**最低的未覆盖位**，只从能覆盖它的掩码里分支，并要求每个新成员带来
    至少一位新 kind。任何极小覆盖都能按这个顺序枚举到（起点取覆盖最低位的那一个，
    其后每步取覆盖"当前最低未覆盖位"的那一个），所以**不会漏**。

    **深度天然 ≤ popcount(full) = |kinds(R_c)|**：每加一个成员至少贡献一位新 bit。
    这就是"不需要人为 k 上限"的根据——长度任意大的冲突构造正好顶到这个界。

    【名字里那个"极小"是**增量**意义，不是**全局**意义】这个约束保证的是"每个成员在被
    加入的那一刻都是必要的"，不保证"整组没有多余的成员"：`{0b1, 0b11}` 走"先取 0b1、
    再补 0b11"这条路照样会被枚举出来，尽管 0b1 是多余的（去掉它仍是覆盖）。
    实测（4000 组随机实例，暴力对拍）：
      · 全局极小覆盖**一个都没漏**（0 次）、枚举出的组合**没有一组铺不满 full**（0 次）；
      · 但枚举集比全局极小集**大 97%**（多出来的全是这种"增量必要、全局多余"的组合）。

    **这是刻意的，不要"顺手改成"全局极小。** 理由：每个被枚举的 S 都**确实**覆盖了某个
    `R_c`（`full` 就是 `kinds(R_c)`），所以它满足 5.2 的判据、是**值得查询**的，不是废查询；
    而且多加一条源会改变供给、进而改变 `Reach` 与极小 α，因此超集能查到严格极小集查不到的
    冲突。收窄成全局极小是**减少覆盖面**（预算不变时更弱），不是优化。这里唯一真正的近似
    是 `_REPS_PER_MASK` 的代表采样与 `scan` 的时间预算（spec 9.1）。
    """
    masks = sorted(by_mask)
    found: set[tuple[int, ...]] = set()

    def extend(chosen: list[int], covered: int) -> None:
        if covered == full:
            found.add(tuple(sorted(chosen)))
            return
        missing = full & ~covered
        lowest = missing & -missing
        for mask in masks:
            if not (mask & lowest) or not (mask & ~covered):
                continue
            chosen.append(mask)
            extend(chosen, covered | mask)
            chosen.pop()

    extend([], 0)

    out: set[tuple[int, ...]] = set()
    for cover in found:
        for chosen in product(*[by_mask[mask][:_REPS_PER_MASK] for mask in cover]):
            if len(set(chosen)) != len(chosen):
                continue
            out.add(tuple(sorted(chosen)))
    return out


def _candidate_source_sets(export: Export, scope: str = SCOPE_PER_CIRCUIT) -> list[tuple[int, ...]]:
    """按 spec 5.2 的覆盖判据筛出值得查询的 S，按覆盖到的第三方配方数降序。

    判据：`S` 值得查询 ⟺ 存在 `R_c ∉ S` 使 `kinds(R_c) ⊆ ∪_{R∈S} kinds(R)`。
    不覆盖任何第三方的 `S` **可证明无产出**，直接剪掉。

    **没有 k 上限。** 早先设的 `--max-union 3` 是拍脑袋的天花板：多配方共同参与的冲突
    长度可以任意大——`R1=[x,a1], R2=[a2], …, Rn=[an]` 加 `Rc=[a1..an]`，任意 n−1 条的
    kind 并集都覆盖不全，故需要 n 条共同参与。而极小覆盖的规模天然 ≤ |kinds(R_c)|，
    人为上限只会漏掉长的那一类。

    这个判据也换掉了早先"优先共享 kind"的启发式：那个启发式对**环状/链状结构系统性失效**——
    五元环 `a+b, b+c, c+d, d+e, e+a` 取 `S={a+b, d+e}` 时 kind 并集 `{a,b,d,e}` 覆盖了 `e+a`
    （真冲突），但这两条**不共享任何 kind**，会被排到末尾、预算一到就永远轮不到。

    `scope` 见 spec 4.5：`per-circuit` 只查询**布局里电路配置不超过一种**的 S。落实方式是按
    **源的电路**分组限域，分两支（见下方 `domains` 处的注释）：target 自带配置 g′ 时源域为
    `组(g′) ∪ 组(0)`；target 无电路时对每个 g 各有一份 `组(g) ∪ 组(0)`，另加一份 `组(0)`。
    """
    by_id = export.recipe_by_id()
    kinds_by_id = {rid: kinds(recipe) for rid, recipe in by_id.items()}
    ids = sorted(by_id)

    if scope not in SCOPES:
        raise ValueError(f"unknown scope {scope!r}; expected one of {SCOPES}")
    # 【不许把"需要配置 0 的电路"悄悄当成"不需要电路"】配置号实测从 1 开始；若将来导出里
    # 出现 0，这里**大声失败**：`circuit or 0` 那类写法会把"无电路"与"配置 0"混成一个键，
    # 而那是两件事（前者不占电路物品，后者要一个配置 0 的电路物品在场）。
    # 注意：换成 `circuit if circuit is not None else 0` **并不能**修好它——桶键仍然是 0，
    # 两者照样合组；能守住的只有这里的显式校验。
    if any(by_id[rid].circuit == 0 for rid in ids):
        raise ValueError("export contains circuit == 0: 'no circuit' and 'config 0' are different groups")
    group_of = {rid: (0 if by_id[rid].circuit is None else by_id[rid].circuit) for rid in ids}
    by_group: dict[int, list[int]] = {}
    for rid in ids:
        by_group.setdefault(group_of[rid], []).append(rid)
    # 每个组的源域 = 组(g) ∪ 组(0)，**预先算一次**（原来每个 target 重建一遍集合，纯浪费）。
    #
    # 【`sorted` 不能去掉】这两个域都是**有序**列表，顺序一路传到 `by_mask` 的桶里，
    # 而桶内顺序决定 `_REPS_PER_MASK` 挑哪两个代表、进而决定哪些 S 被枚举出来。
    # 换句话说排序不是装饰，是结果的一部分；"顺手优化掉"会静默改变产出。
    zero_group = by_group.get(0, [])
    nonzero_groups = sorted(g for g in by_group if g)
    domain_by_group = {g: sorted(set(by_group[g]) | set(zero_group)) for g in nonzero_groups}
    domain_by_group[0] = list(zero_group)

    # 单元：全部保留，完备。
    singles: list[tuple[int, ...]] = [(rid,) for rid in ids]

    scored: dict[tuple[int, ...], int] = {}
    for target_id in ids:
        need = kinds_by_id[target_id]
        if not need:
            continue
        bits = {kind: bit for bit, kind in enumerate(sorted(need))}
        full = (1 << len(need)) - 1

        # 【判据范围（spec 4.5）】口径约束的是**布局**的电路，而布局里的电路 token 只能来自源，
        # 所以要按"源的电路"分组，**不是**按 target 的组：
        #   · target 自带配置 g′ -> 布局必含 g′（它自己的电路入口要匹配得上），于是源只能落在
        #     组(g′) ∪ 组(0)；其它 g 的域里它铺不满（它的电路 token 只有组(g′) 的源提供）。
        #   · target 无电路 -> 布局的电路完全由源带进来，故对**每个** g 都有域 组(g) ∪ 组(0)，
        #     外加"源一个电路都不带"的 组(0)。
        # 【第二支不能丢】它正是"额外配方无电路、而源里有电路"的那一半（用户点名的第二类情形）。
        # 曾把无电路 target 的域收成 组(0)，实测在真实装配机导出上丢掉 5,753 个 S
        # （全部是这一类；早先评审把这 5,753 拆成"2,908 缺口 + 2,845 采样残留"，**那个拆分是错的**）。
        #
        # 【别以为 `all` 是 `per-circuit` 的超集】两个计划**互不包含**（真实装配机导出，
        # 本修正后复测：per\all = 42,143、all\per = 218,353）。根因是 `_REPS_PER_MASK` 的代表采样：
        # 域的成员一变，`by_mask` 桶内顺序就变，被挑中的代表配方随之改变，于是枚举出的 S
        # 也变——`all` 多出来的是它自己那批代表造成的组合，并不是"per-circuit 的全部加上一些"。
        if scope == SCOPE_ALL:
            domains = (ids,)
        else:
            own = group_of[target_id]
            domains = ((domain_by_group[own],) if own else
                       (domain_by_group[0],) + tuple(domain_by_group[g] for g in nonzero_groups))

        for domain in domains:
            by_mask: dict[int, list[int]] = {}
            for rid in domain:
                if rid == target_id:
                    continue
                mask = 0
                for kind in kinds_by_id[rid] & need:
                    mask |= 1 << bits[kind]
                if mask:
                    by_mask.setdefault(mask, []).append(rid)

            for source_ids in _minimal_covers(by_mask, full):
                scored[source_ids] = scored.get(source_ids, 0) + 1

    ranked = sorted(scored, key=lambda combo: (-scored[combo], combo))
    # 【去重保序】singles 与 ranked 必然重叠：单元素 S 常常同时是别的配方的极小覆盖
    # （`proportional_double` 实测 planned=4 而 distinct=2，`needs_three_sources` 的 `(3,)` 出现两次）。
    # 直接相接的后果不是"多跑一次"这么轻：① 时间预算被同一个 S 白吃两份；
    # ② `findings` 里会出现两条一模一样的记录，报告里就印出两个相同的 `### S = [...]` 段
    #    （实测 `needs_three_sources`：findings 3 条、distinct 2 条）。
    # 首次出现的名次胜出，所以 ranked 的优先级信息不受影响。
    return list(dict.fromkeys(singles + ranked))


def scan(export: Export,
         time_budget: float = 600.0,
         progress: Callable[[int, int, float], None] | None = None,
         scope: str = SCOPE_PER_CIRCUIT,
         workers: int = 1) -> ScanResult:
    """在时间预算内跑扫描。超时即停，并把**实际进度**写进结果——不假装跑完了。

    【`scope` 的默认值改了行为】不传 `scope` 时结果**比改动前窄**：默认走 spec 4.5 的
    `per-circuit`（只查询布局里电路配置不超过一种的 S，见 `scope_note`）；改动前的全域口径
    现在要显式传 `SCOPE_ALL`。两个口径**结论强度不同**，别把默认口径的结果当成全域结论。

    【"预算内"是真的界，不是"组间粗略"】`time_budget` 换算成一个**绝对截止时刻**，
    一路传到 `analyse` 里：那里的目标循环与 `minimal_alphas` 的枚举循环都会看钟。
    只在下面这个组间检查（循环顶）挡是不够的——实测 `--time-budget 600` 单组内部
    就跑到 2486.7s（4 倍超），因为整个超时都发生在某一次 `analyse` 里面。
    """
    if scope not in SCOPES:
        raise ValueError(f"unknown scope {scope!r}; expected one of {SCOPES}")
    started = time.monotonic()
    deadline = started + time_budget
    result = ScanResult(scope=scope, workers=workers)
    handle = build_trie(export.trie)

    for recipe in export.recipes:
        if recipe.id is None:
            continue
        if recipe.in_category and not recipe.in_tree:
            result.ghosts.append(recipe.id)
        for entry in tuple(recipe.inputs) + tuple(recipe.fluid_inputs):
            if not entry.slot_candidates:
                result.dead_inputs.append(recipe.id)
                break

    planned = _candidate_source_sets(export, scope=scope)
    result.planned = len(planned)
    # 实测事实，不是选定档位：这张表里实际用到过多大的 S。
    result.max_source_set_size = max((len(source_ids) for source_ids in planned), default=0)

    outcomes = run_plan(export, planned, handle, deadline, scope, workers, progress)
    for source_ids, outcome in zip(planned, outcomes):
        if outcome.kind == "overflow":
            # α 枚举超预算 -> "没判定"，与"无冲突"分开记（spec 9.9）。**记下继续扫**。
            result.undecided.append(source_ids)
            result.completed += 1
            continue
        if outcome.kind == "timeout":
            # 时间预算在判定途中耗尽 -> "没判定（时间）"，与上一类分开记。
            #
            # 【这里必须**停**，不是 continue】截止时刻是**全局且单调**的，一条 S 判到超时
            # 就意味着预算已经耗尽，后面的 S 不会有结果；继续记账只会把同一件事重复 N 遍
            # （fake 掉时钟的用例里实测：timed_out 会从 1 条涨到全部计划数）。
            # 停在这里也正好让串行与并行的**记账前缀**对齐：按下标升序消费，第一条 timeout
            # 之前的下标两条路径都判过，之后的一律不计——所以产物不受并行影响。
            # （并行下可能有更后面的下标已被别的 worker 判完，这里一并丢弃：与串行一致，
            # 也符合"半个结果不当结果用"的口径。）
            result.timed_out.append(source_ids)
            # 预算已尽，再扫下一组只会立刻撞钟；如实标"未扫完"。
            result.truncated = True
            break
        if outcome.kind == "skipped":
            # 没轮到的：**不计入 completed**，覆盖率如实低下来。
            result.truncated = True
            continue
        for rid in outcome.unreachable_self:
            if rid not in result.unreachable_self:
                result.unreachable_self.append(rid)
        if outcome.findings:
            result.findings.append((source_ids, list(outcome.findings)))
        result.witness_alphas.update(outcome.witness_alphas)
        result.completed += 1

    result.elapsed_seconds = time.monotonic() - started
    # 【两种"未判定"分开报】α 枚举超预算（规模）与时间预算耗尽（墙钟）都是"没判定"，
    # 但来源不同，必须各印各的：合并成一个数就再也分不出来是什么没判定。
    result.coverage_note = (
        f"max_source_set_size={result.max_source_set_size}（实测）；"
        f"计划 {result.planned} 组，完成 {result.completed} 组"
        f"（{result.coverage * 100:.1f}%），其中 {len(result.undecided)} 组未能判定"
        f"（α 枚举超预算）、{len(result.timed_out)} 组未能判定（时间预算耗尽）；"
        f"用时 {result.elapsed_seconds:.1f}s"
        + ("；**触发时间预算，未扫完**" if result.truncated else "；已扫完")
        + (f"；进程数 {workers}" if workers > 1 else "")
    )
    return result


def _describe(export: Export, recipe: Recipe) -> str:
    inputs = ", ".join(f"{entry.amount}x{entry.representative}" for entry in recipe.inputs)
    fluids = ", ".join(f"{entry.amount}mB {entry.representative}" for entry in recipe.fluid_inputs)
    outputs = ", ".join(f"{count}x{token}" for token, count in recipe.outputs.items)
    out_fluids = ", ".join(f"{amount}mB {token}" for token, amount in recipe.outputs.fluids)
    circuit = "无电路" if recipe.circuit is None else f"电路{recipe.circuit}"
    return f"[{recipe.id}] {circuit} | 输入 {inputs} {fluids} | 输出 {outputs} {out_fluids}"


def _witness_counts(result: ScanResult) -> list[tuple[int, int]]:
    """见证配方 -> 被多少组 S 见证。按次数降序、同次数按 id 升序（确定性输出）。"""
    counts: dict[int, int] = {}
    for _source_ids, findings in result.findings:
        for witness_id, _alpha_index in findings:
            counts[witness_id] = counts.get(witness_id, 0) + 1
    return sorted(counts.items(), key=lambda item: (-item[1], item[0]))


def _source_group(export: Export, source_ids) -> int | str:
    """S 的电路归属：成员里唯一的非零配置 / `0`（全无电路）/ `"跨电路"`（多种）。

    `"跨电路"` 只在 `scope=all` 下出现——默认口径的源域本身就限在同一组内（spec 4.5）。
    """
    by_id = export.recipe_by_id()
    # 【与 `_candidate_source_sets` 同一条规矩】不许用 `circuit or 0`：它把"无电路"与"配置 0"
    # 混成一个键。那里已用显式校验把它变成大声失败，这里只是**别把同一条规矩写松**——
    # 今天靠上游拦截才等价，而 `render_markdown` 是公开 API，手工拼的 Export 能绕过上游。
    nonzero = {(0 if by_id[rid].circuit is None else by_id[rid].circuit) for rid in source_ids} - {0}
    if not nonzero:
        return 0
    if len(nonzero) == 1:
        return next(iter(nonzero))
    return "跨电路"


def _group_label(group: int | str) -> str:
    if group == 0:
        return "无电路"
    if group == "跨电路":
        return "跨电路（仅 all 口径下出现）"
    return f"电路 {group}"


def _render_registry_section(export: Export, result: ScanResult) -> list[str]:
    lines: list[str] = ["## 注册异常", ""]
    lines.append(f"- 幽灵配方（进了分类表但没进 trie）：{result.ghosts or '无'}")
    lines.append(f"- 空展开输入的配方（永远匹配不到）：{result.dead_inputs or '无'}")
    # 【措辞不许断言一件已知不成立的事】真实装配机数据上这一节印出 `[2318]`，而它**同时**列在
    # 上面那行「空展开输入」里——那是空矿辞（`plateFrancium`）的**合法例外**：GT 建 trie 的 key
    # 取自**声明**、导出侧 `slotCandidates` 取自**展开**，展开为空即没有候选，于是"在 trie 里"
    # 却"自己不可达"（spec 9.1 / Task 10 已记）。原措辞"出现即 bug"在这个数据上就是假的，
    # 而且它印在**两份**产物里。所以改成给出**判读方法**，而不是一句会被自己上一行否证的断言。
    lines.append("- S 自身不可达的配方（按 3.3 推论二不应出现）："
                 f"{result.unreachable_self or '无'}"
                 "——非空时逐个核对：若它**同时**列在上面『空展开输入的配方』里，"
                 "属**已知的合法例外**（声明与展开不一致，spec 9.1），不是判定缺陷；否则才是缺陷。")
    # 【语法】f-string 的**字面文本段**不能复用外层引号（复用引号只在 `{}` 表达式段里合法），
    # 所以这里的引号必须用全角。顺带满足上面的措辞约束：属“没判定”而非“没有冲突”。
    lines.append(f"- **未能判定的 S（α 枚举超预算，属“没判定”而非“没有冲突”）：{result.undecided or '无'}**")
    # 【两种未判定必须分两行】时间预算耗尽与 α 枚举超预算是**不同的**未判定来源：
    # 前者说明"这轮没来得及看"，后者说明"这个 S 的规模超出枚举预算"。合并成一行
    # 会让读者无法判断该不该加大预算重跑。措辞同样避开"没有冲突"。
    lines.append(f"- **未能判定的 S（时间预算在判定途中耗尽，同属“没判定”）：{result.timed_out or '无'}**")
    if export.rejected:
        lines.append("")
        lines.append(f"- 注册期被拒（{len(export.rejected)} 条）：")
        for recipe in export.rejected:
            lines.append(f"  - {_describe(export, recipe)}")
    lines.append("")
    return lines


def _render_witness_summary(export: Export, result: ScanResult) -> list[str]:
    by_id = export.recipe_by_id()
    counts = _witness_counts(result)
    lines: list[str] = ["## 冲突配方摘要（去重）", ""]
    if not counts:
        # 【默认产物也要给未判定的指引】detail 版的空分支会把"，但有 N 组未能判定（见下方…）"
        # 并进同一句，就是怕读者把"没报出"读成"没问题"。摘要版是 CLI 的**默认**产物，
        # 那个入口只会更宽，所以照同一口径补上（Task 12 评审 M-1）。
        undecided_total = len(result.undecided) + len(result.timed_out)
        suffix = (f"，但有 {undecided_total} 组未能判定（见下方注册异常一节）"
                  if undecided_total else "")
        lines += [f"（本轮没有报出任何见证配方{suffix}）", ""]
        return lines
    lines.append(f"本轮共 {len(result.findings)} 组 S 报出冲突，涉及 **{len(counts)}** 条见证配方；"
                 f"按被多少组 S 见证降序：")
    lines.append("")
    lines.append("| 见证配方 | 所属电路 | 被多少组 S 见证 | 该配方 |")
    lines.append("|---|---|---|---|")
    for witness_id, count in counts:
        witness = by_id.get(witness_id)
        if witness is None:
            lines.append(f"| {witness_id} | ? | {count} | （不在本轮导出里） |")
            continue
        circuit = "无电路" if witness.circuit is None else f"电路{witness.circuit}"
        # 【`|` 必须转义】`_describe` 本身用 `|` 分隔字段，裸放进表格会把列切断。
        description = _describe(export, witness).replace("|", "\\|")
        lines.append(f"| [{witness_id}] | {circuit} | {count} | {description} |")
    lines.append("")
    return lines


def render_summary(export: Export, result: ScanResult) -> str:
    """**摘要版报告**：口径 + 进度 + 去重后的见证配方 + 注册异常，**不含逐组 S 明细**。

    【为什么需要它】默认口径（4.5）下扫描能**跑完**，于是 findings 是全量的——实测 **55,259** 组 S
    会印成 **1.4×10⁸ B** 量级的明细，没人读得完。摘要版把同样的判定压成一张"哪些配方是意外的"表
    （同一批实测：726 条见证配方）；要逐组证据时用 `render_markdown`（CLI 的 `--report detail`）。

    【不是静默截断】末尾**明确写出**本文件不含明细、明细怎么拿，所以读者不会把
    "没印 S 明细"误当成"没有 S 明细"（spec 9.1 的纪律）。
    """
    lines: list[str] = [f"# 配方冲突报告（摘要）— {export.name}", ""]
    lines.append(f"- {scope_note(result.scope)}")
    lines.append(f"- 覆盖范围：{result.coverage_note}")
    lines.append(f"- 配方数：{len(export.recipes)}")
    lines.append("")
    lines.append("> 本报告只声明**在扫过的范围内未发现冲突**，不构成“冲突不存在”的证明。")
    lines.append("")
    lines += _render_witness_summary(export, result)
    lines += _render_registry_section(export, result)
    lines.append("**本文件不含逐组 S 明细**；需要每条冲突的完整证据（S、各源配方、命中 α）时，"
                 "用 `--report detail` 重新生成。")
    lines.append("")
    return "\n".join(lines)


def render_markdown(export: Export, result: ScanResult) -> str:
    by_id = export.recipe_by_id()
    lines: list[str] = []
    lines.append(f"# 配方冲突报告 — {export.name}")
    lines.append("")
    lines.append(f"- 覆盖范围：{result.coverage_note}")
    # 【口径必须印出来】与"未发现冲突 ≠ 无冲突"同一条纪律（spec 4.5 / 9.14）：
    # 单电路口径的结论不能被读成"任何输入都不会冲突"。
    lines.append(f"- {scope_note(result.scope)}")
    lines.append(f"- 配方数：{len(export.recipes)}")
    lines.append("")
    # 措辞硬约束（spec 5.2 / 11）：报告**任何位置都不得出现“无冲突”这三个字连排**——
    # 那是把这轮有界扫描读成完备结论的入口。所以这里连免责声明也要绕开它，
    # 写成“不构成『冲突不存在』的证明”。
    lines.append("> 本报告只声明**在扫过的范围内未发现冲突**，不构成“冲突不存在”的证明。")
    lines.append("")
    lines += _render_witness_summary(export, result)

    lines.append("## 冲突")
    lines.append("")
    if not result.findings:
        # 【措辞】`undecided` / `timed_out` 非空时不能只印「未发现冲突」——那对只读这一节的
        # 读者是一句无限定的断言，而两者的含义都是"**没判定**"，不是"没问题"（spec §9.9）。
        # 所以把这件事带进同一句（**合计**，具体是哪一种由下方那一节分两行说清），
        # 并指向已经单列了它们的「## 注册异常」一节；那里已有加粗行把 S 逐个列出，
        # 这里只做指引、不重复列表。
        undecided_total = len(result.undecided) + len(result.timed_out)
        suffix = (f"，但有 {undecided_total} 组未能判定（见下方注册异常一节）"
                  if undecided_total else "")
        lines.append(f"在扫过的范围内未发现冲突{suffix}。")
        # 与 findings 分支一样补一个空行，否则正文与下一个 `## 注册异常` 贴在一起。
        lines.append("")
    else:
        # 【分组用加粗行，不新增标题层级】`### S = ` 是既有报告契约（既有测试与外部解析都认它），
        # 换层级会静默改掉契约。
        grouped: dict[int | str, list] = {}
        for entry in result.findings:
            grouped.setdefault(_source_group(export, entry[0]), []).append(entry)
        # 键混合了 int 与 str：先按"是不是字符串"排，保证 int 与 str 永不互相比较。
        for group in sorted(grouped, key=lambda key: (isinstance(key, str), key)):
            entries = grouped[group]
            lines.append(f"**{_group_label(group)}（{len(entries)} 组 S）**")
            lines.append("")
            for source_ids, findings in entries:
                sources = [by_id[rid] for rid in source_ids]
                lines.append(f"### S = {list(source_ids)}")
                lines.append("")
                for recipe in sources:
                    lines.append(f"- 预期：{_describe(export, recipe)}")
                lines.append("")
                for witness_id, alpha_index in findings:
                    witness = by_id[witness_id]
                    if alpha_index is None:
                        detail = "输出超出预期，无需解 LP"
                    else:
                        # 【取用 scan 算过的，不重算】`minimal_alphas` 只为把 α 的可读数值
                        # 印进报告，逐见证重算是纯浪费——实测 29.1 MB 的报告光这一项就要跑
                        # 34 分钟（每个见证一次 DFS，且部分见证的 caps 乘积极大）。
                        # `analyse` 在判出这个见证时已经把同一份结果记进 `witness_alphas`
                        # （键 (S, witness_id)），这里直接取。S 相同、见证相同 -> 结果逐字相同，
                        # 所以这是**纯性能**改动，印出来的 α 与重算完全一致。
                        #
                        # 【防御仍在这里】缓存**可能缺**：`render_markdown` 是公开 API，
                        # 手工拼一个 ScanResult（或老代码留下的结果）就能喂进来。缺了不能静默
                        # 印错，也不能裸抛把整份报告报废（spec 9.9 的形态）——退回到重算，
                        # 重算也要兜住超预算（α 枚举 / 时间，两种都可能）。
                        # 真兜不住的极端情况退到"某个极小 α"措辞，报告照样完整、且不撒谎。
                        cached = result.witness_alphas.get((tuple(source_ids), witness_id))
                        if cached is not None:
                            alphas = cached
                        else:
                            # 【`ScanDeadlineExceeded` 这一支今天是**死代码**，留着是给将来兜底】
                            # 这条重算路径**不传 `deadline`**，所以它永远抛不出超时异常。
                            # 之所以照旧捕获，是因为 `render_markdown` 是公开 API：将来若有人
                            # 给重算加上预算（或换了带预算的实现），这里不能裸抛把整份报告报废。
                            # 别据此以为"渲染也会超时"——渲染今天没有、也不该有截止时刻。
                            try:
                                alphas = minimal_alphas(sources, witness)
                            except (AlphaEnumerationOverflow, ScanDeadlineExceeded):
                                alphas = ()
                        detail = (f"在 α={alphas[alpha_index]} 处无法补全"
                                  if alpha_index < len(alphas) else "在某个极小 α 处无法补全")
                    lines.append(f"- 见证配方 {witness_id}（{detail}）：")
                    lines.append(f"  - {_describe(export, witness)}")
                expected: dict[str, int] = {}
                for recipe in sources:
                    for token, count in output_vector(recipe):
                        expected[token] = expected.get(token, 0) + count
                rendered = ", ".join(f"{count}x{token}" for token, count in sorted(expected.items()))
                lines.append(f"- S 的预期输出合计：{rendered or '无'}")
                lines.append("")

    lines += _render_registry_section(export, result)
    return "\n".join(lines)
