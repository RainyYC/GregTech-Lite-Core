"""模组侧 RecipeMap 的 trie 重建与可达性搜索，逐句复刻 GTCEu 的实现。

对应两个 Java 方法：

- 建树：`RecipeMap.recurseIngredientTreeAdd`（`GregTech/.../RecipeMap.java:920-1010`）
- 搜索：`RecipeMap.recurseIngredientTreeFindRecipeCollisions`（`:739-825`）

模组侧只导出数据、不判定，所以这里必须**忠实重演**：结构错一点、搜索少探一支，
审计结果就会静默漏报，且不会报错。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from recipeaudit.model import Trie, TriePath, _is_special_token

__all__ = ["build_trie", "reach", "ScanDeadlineExceeded"]


class ScanDeadlineExceeded(RuntimeError):
    """扫描的**截止时刻**（`deadline`）已到，当前这一步判不完，于是大声失败。

    定义在**依赖链最底层**的模块（`model` → `reach` → `criterion` → `report`）里，
    因为要抛它的热循环分散在两层：`reach` 的 trie 搜索，以及 `criterion` 里的三处
    （`analyse` 的见证循环、`minimal_alphas` 的 α 枚举、`minimal_alphas` 末尾那个
    O(候选²) 的极小化过滤）。放在 `criterion` 里会让 `reach` 反向依赖 `criterion` 成环，
    所以由这里持有、`criterion` 再导出。

    **必须与 `AlphaEnumerationOverflow` 分开，不能复用它的类型**：两者都让一个 S 变成
    "未判定"，但来源不同——一个是**枚举规模**（节点预算），一个是**时间**（墙钟）。
    复用类型会把"时间到了"混进"α 枚举炸了"的统计里，报告就再也分不清这两种未判定
    （spec §9.9 要求未判定如实、可区分地记）。

    与 `AlphaEnumerationOverflow` 同理：**大声失败，绝不静默截断**。截断 = 那一步的结果
    被当成完整结果用，可能少报冲突。`deadline` 为 `None` 时这些循环永不抛它，
    也就是说**只加了超时通道、没有改任何判定**。
    """


# 时间预算的检查间隔（以搜索节点计）。逐节点调 `time.monotonic()` 会把热循环拖慢
# 一个量级；这个间隔下额外开销可忽略，而超时最多多花这么多个节点的计算。
_DEADLINE_CHECK_EVERY = 4096


@dataclass
class _Branch:
    """对应 `RecipeMap.Branch`：**每个分支都同时持有 `nodes` 与 `specialNodes` 两张子表**。

    两张子表都挂在同一个分支上（而不是按首节点给整条路径选一张），因为
    `determineRootNodes`（`:1064`）是按**每个 key 自身**分流的，一条链可以层 0 在
    `specialNodes`、层 1 在 `nodes`。

    叶子的值存配方 id（`int`）；分支存 `_Branch`。这对应 Java 的
    `Either<Recipe, Branch>`（left=配方、right=分支）。
    """

    nodes: dict[str, object] = field(default_factory=dict)
    special_nodes: dict[str, object] = field(default_factory=dict)


def _child_map(branch: _Branch, is_special: bool) -> dict[str, object]:
    """`RecipeMap.determineRootNodes`（`:1064`）：按 key 自身属于哪个 map 选子表。"""
    return branch.special_nodes if is_special else branch.nodes


def _insert(root: _Branch, path: TriePath) -> None:
    """按 `path.special[k]` **逐节点**选子表，插入一条 trie 路径。

    不要按首节点给整条路径选 map：那会把混用路径挂到错的一侧，而层 1 的 regular
    候选是去 `nodes` 查的，于是这条路径永远不可达、Reach 静默漏项。
    """
    if path.recipe is None:
        return
    branch = root
    last = len(path.nodes) - 1
    for index, token in enumerate(path.nodes):
        target = _child_map(branch, path.special[index])
        if index == last:
            # Java 在末尾 key 已有值时不覆盖（`:946-1003`：返回既有的 `v`），
            # 即先插入的路径胜出。
            target.setdefault(token, path.recipe)
            return
        nxt = target.get(token)
        if nxt is None:
            nxt = _Branch()
            target[token] = nxt
        elif not isinstance(nxt, _Branch):
            # 该 key 上已经是叶子，没法再往下挂分支：Java 里 `r.right()` 为空、
            # 整条配方放弃（`:1004-1034`）。合法导出不会出现（模组侧也建不出这种
            # trie，会在 `rejected` 里），这里同样放弃而不是硬改结构。
            return
        branch = nxt


def build_trie(trie: Trie) -> _Branch:
    """把导出数据重建成 trie，返回根分支（对调用方是不透明句柄）。"""
    root = _Branch()
    for path in trie.paths:
        _insert(root, path)
    return root


def _lookup(branch: _Branch, token: str) -> object | None:
    """在**当前分支**下查一个槽位候选 token，返回配方 id、子分支或 None。

    按候选 token 自身的前缀分流（对应 `ingredient.isSpecialIngredient()`），
    与导出侧 `model._is_special_token` 用的是同一套前缀，避免两处真值。
    """
    return _child_map(branch, _is_special_token(token)).get(token)


def reach(handle: _Branch,
          slot_candidates: list[tuple[str, ...]],
          deadline: float | None = None) -> set[int]:
    """复刻 `recurseIngredientTreeFindRecipeCollisions`（`:739-825`）。

    对每个槽位给一组候选 token，返回所有可达配方的 id 集合：命中叶子即收进结果集，
    **不校验数量、也不校验电压**（`:776-781`）。

    入参契约：`slot_candidates` 的**每一项对应 Java 布局（`ingredients`）里的一个槽位**，
    且**物品侧必须已经按 `(item, meta, tag)` 去重**。原因是 Java 的布局维度 `L` 不是"槽位数"，
    而是"**不同的**非空输入栈个数"：`prepareRecipeFind`（`:519-537`）在造布局前先过
    `uniqueItems`（`:567-591`），把 (item, meta, NBT) 完全相同的栈折叠成一条。若调用方把两个
    完全相同的栈当成两个槽位传进来，Python 会认为存在一个把两层映射到这两个槽位的单射、判为
    **可达**；而 Java 只有 1 个栈、`P(R) <= L` 不成立、判为**不可达**——这是静默多报，所以
    去重必须由调用方保证，本函数不做（也不该做，那会掩盖调用方违反契约）。

    流体侧则相反：`slot_candidates` 里流体重复项是否折叠是**有意的建模选择**，见 spec §9.6
    （流体的物理含义是每种流体一个储罐，故按 `distinct(fluid, tag)` 去重，代价是漏判"把同一种
    流体声明成两条流体输入"的配方）。也就是说流体这一侧**不要求**与 Java 的
    `buildFromFluidStacks`（`:1107-1112`，不去重）逐字一致。

    `deadline` 是**绝对**的单调时钟时刻（`time.monotonic()` 口径）；到点抛
    `ScanDeadlineExceeded`，**不返回半个可达集**（半个集合会让下游把"没探到的分支"
    当成"不存在"，那是静默漏报）。默认 `None` = 不限时，行为与 Java 逐字一致。

    【为什么这里也要看钟】这个搜索在布局槽位多时是**指数级**的：同一状态
    （branch, index, count, skip）会被反复重走，槽位一多就爆炸。实测装配机数据上
    `--time-budget 600` 有单次调用跑出分钟级——`report.scan` 的组间检查、乃至
    `analyse` 的两个循环都够不着它（它在 `analyse` 之前调用）。只加检查、不改判定：
    `deadline is None` 时**零行为差异、开销可忽略**（不是"零开销"——下面的 `visited += 1`
    是无条件的，每次调用都照跑；多出来的只是每节点一次 `deadline is not None` 判断）。
    """
    found: set[int] = set()
    slots = len(slot_candidates)
    if slots == 0:
        return found

    # 搜索节点计数器。见 `_DEADLINE_CHECK_EVERY`：每这么多个节点才看一次钟。
    visited = 0

    def recurse(branch: _Branch, index: int, count: int, skip: int) -> None:
        nonlocal visited
        visited += 1
        if deadline is not None and visited % _DEADLINE_CHECK_EVERY == 0 \
                and time.monotonic() >= deadline:
            raise ScanDeadlineExceeded(
                f"deadline hit while searching reachability over {slots} slots "
                f"after {visited} nodes"
            )
        # 每层入口 count == slots 即剪枝（`:659`）
        if count == slots:
            return
        # 遍历当前层的候选
        for token in slot_candidates[index]:
            hit = _lookup(branch, token)
            if hit is None:
                continue
            if isinstance(hit, int):
                # 命中叶子：无条件收进结果集
                found.add(hit)
            else:
                dive(hit, index, count, skip)

    def dive(branch: _Branch, current: int, count: int, skip: int) -> None:
        # 环形前进（`:699-714` / `:812-825`）：从 current 的下一个槽绕一整圈，
        # `skip` 位图保证不重复用槽。注意这里对**每个**未用槽都递归一次，
        # 所以层 k 落到哪个槽并不要求与层 k-1 相邻。
        i = (current + 1) % slots
        while i != current:
            if not (skip >> i) & 1:
                # Java 原版这里还有 `Recipe r = recurse(...); if (r != null) return r;` 的
                # 早退（`:772-780`），此处省略。依据：被调用的
                # `recurseIngredientTreeFindRecipeCollisions` **恒 `return null`**
                # （剪枝分支 `:764` 与收尾 `:784` 都是 null），故 `r` 永远为 null、
                # 早退从不触发，省略它在结果上与 Java 不可区分。
                # 对照 Java 时不要误以为这里漏写了。
                recurse(branch, i, count + 1, skip | (1 << i))
            i = (i + 1) % slots

    # 顶层枚举起点 i，把它记进 skip 位图（`:634-639`）
    for start in range(slots):
        recurse(handle, start, 0, 1 << start)
    return found
