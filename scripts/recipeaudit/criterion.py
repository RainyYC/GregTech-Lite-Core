from __future__ import annotations

import time
from fractions import Fraction

from recipeaudit.lp import lp_feasible
from recipeaudit.model import Export, Recipe
# `ScanDeadlineExceeded` 与检查间隔都定义在 reach 里（依赖链最底层，`reach` 自己也要抛它），
# 这里只是**再导出**，免得同一个契约有两份定义。
#
# 【注意：常量是**按值**导入的】`_DEADLINE_CHECK_EVERY` 到了这里就是本模块自己的一个 int，
# 运行时改 `recipeaudit.reach` 的那一份**影响不到**这里（反之亦然），所以别指望"改一处两处都变"。
# 两个模块只是**约定同值**：改这个数就得两处一起改，否则 reach 与 criterion 的看钟节奏会分叉。
from recipeaudit.reach import _DEADLINE_CHECK_EVERY, ScanDeadlineExceeded, build_trie, reach

__all__ = [
    "AlphaEnumerationOverflow",
    "ScanDeadlineExceeded",
    "consumes",
    "kinds",
    "output_vector",
    "minimal_alphas",
    "slot_candidates_for",
    "analyse",
]

# α 分量的【健全性护栏】，防的是 `_alpha_bound` 写错导致的**荒谬**上界。
#
# **不要把它当成"可以调大的旋钮"，也不要以为它只会被错误公式触发**：
# 比值上界本身**就可以很大**——同一 token 上某配方用 1 mB、另一条用 1000 mB，
# 正确的上界就是 1000。所以这个值必须远高于现实比值，否则【合法】输入就会抛异常，
# 而它在 `analyse` / `scan` 里是**未捕获**的异常 -> 一条这样的配方对会把整轮扫描打断
# （不是"少报一条"）。真正兜住规模的是枚举里的早停剪枝（前缀已可行即记录并停止加值）
# 与 `scan` 的时间预算，不是这个阈值。
_ALPHA_HARD_CAP = 10 ** 6

# α 枚举的节点预算。早停剪枝只在"前缀 + 余下全取 0 已可行"时生效；当需求分散在多个源、
# 前几个源单独都无法满足时，DFS 会按 ∏(capᵢ+1) 扩张，而 cap 是**比值**——
# 同一 token 上 1 mB vs 1000 mB 这种【合法】输入就能给出 1000，三个这样的分量相乘就到 10⁹。
# 此时 `scan` 的时间预算也救不了场：它只在两次 `analyse` 之间检查，中断不了单次枚举。
_ALPHA_NODE_BUDGET = 2_000_000


class AlphaEnumerationOverflow(RuntimeError):
    """`minimal_alphas` 的枚举超出 `_ALPHA_NODE_BUDGET` 时抛出。

    **必须是异常，不能静默截断**：截断会把超出预算的那些极小 α 整个漏掉，
    于是一个本该报出的冲突被静默判成无害——**静默少报**，比让扫描停摆危险得多。
    所以这里大声失败，把"要继续还是放弃"的决定交还给调用方（`report.scan` 可自行
    捕获本类型并显式记录），而不是让库偷偷替它决定。
    """


# `ScanDeadlineExceeded` 与检查间隔 `_DEADLINE_CHECK_EVERY` 见 `recipeaudit.reach`
# ——定义放那儿是因为 `reach` 自己也要抛它，而 criterion 依赖 reach（反向会成环）。


def kinds(recipe: Recipe) -> frozenset[str]:
    """布局槽位的 kind 集合，用于 spec 5.2 的覆盖剪枝。

    **与 [consumes] 刻意相反：这里必须包含非消耗输入（电路）**——
    电路也占一个槽位，所以它是"哪些槽位存在"的一部分（`kinds` 描述槽位集合，不是消耗）。
    而 [consumes] 是"哪些原料被吃掉"，那里必须排除非消耗输入（spec 4.2）。
    两者混用会让剪枝或判据之一出错。
    """
    return frozenset(
        entry.representative
        for entry in tuple(recipe.inputs) + tuple(recipe.fluid_inputs)
    )


def consumes(recipe: Recipe) -> tuple[tuple[str, int], ...]:
    """只累加 consumable 输入，按 token 合并，按字典序返回。

    非消耗输入（电路等）必须排除：matchesItems 里非消耗输入不会扣减槽位
    （Recipe.java:288-317），算进供给向量会让配比平衡直接算错。
    """
    totals: dict[str, int] = {}
    for entry in tuple(recipe.inputs) + tuple(recipe.fluid_inputs):
        if not entry.consumable:
            continue
        totals[entry.representative] = totals.get(entry.representative, 0) + entry.amount
    return tuple(sorted(totals.items()))


def output_vector(recipe: Recipe) -> tuple[tuple[str, int], ...]:
    totals: dict[str, int] = {}
    for token, count in recipe.outputs.items:
        totals[f"out:{token}"] = totals.get(f"out:{token}", 0) + count
    for token, amount in recipe.outputs.fluids:
        totals[f"out:{token}"] = totals.get(f"out:{token}", 0) + amount
    return tuple(sorted(totals.items()))


def _as_vector(pairs: tuple[tuple[str, int], ...], keys: list[str]) -> list[Fraction]:
    table = dict(pairs)
    return [Fraction(table.get(key, 0)) for key in keys]


def _net(recipe: Recipe) -> tuple[tuple[str, int], ...]:
    """净消耗向量 `consumes(recipe) - output(recipe)`；LP 的每一列就是这个向量。

    consume 侧的 token 形如 `item:x:0:none`、output 侧形如 `out:item:x:0:none`，
    靠前缀不相交，所以合并进同一张表不会撞键，与 `analyse` 的 O(S) 计数表同构。
    """
    totals: dict[str, int] = {}
    for token, amount in consumes(recipe):
        totals[token] = totals.get(token, 0) + amount
    for token, amount in output_vector(recipe):
        totals[token] = totals.get(token, 0) - amount
    return tuple(sorted(totals.items()))


def _union_keys(*pairs_list: tuple[tuple[str, int], ...]) -> list[str]:
    keys: set[str] = set()
    for pairs in pairs_list:
        keys.update(key for key, _ in pairs)
    return sorted(keys)


def _alpha_bound(source_vec: dict[str, int], need_vec: dict[str, int]) -> int:
    """单个 α 分量的上界：**比值**上界，不是绝对数量上界。

    **绝对不能用 `max(need_vec.values())`**：装配机配方的流体量是 1000 mB 级，
    拿它当上界再乘个系数，cap 会到几万；|S|=2 的双重枚举就是 4×10⁹ 次迭代，
    单个见证就跑到天荒地老。真正相关的是**比例** `ceil(need / source)`——
    流体量在分子分母里约掉，所以这个上界通常是个位数。
    """
    bound = 0
    for token, need in need_vec.items():
        have = source_vec.get(token, 0)
        if have > 0:
            bound = max(bound, -(-need // have))
    return bound


def minimal_alphas(sources: list[Recipe],
                   target: Recipe,
                   deadline: float | None = None) -> list[tuple[int, ...]]:
    """枚举"极小可执行 α"：sum_i alpha_i * consumes(source_i) >= consumes(target) 逐分量成立，
    且不被另一个可行 α 逐分量支配。

    用有界 DFS 而不是固定重数的 cartesian 积——**|S| 没有人为上限**
    （极小覆盖的规模天然 ≤ |kinds(R_c)|，见 report._minimal_covers），
    所以不能再假设 |S| ∈ {1, 2}。

    展开节点数超过 `_ALPHA_NODE_BUDGET` 时抛 `AlphaEnumerationOverflow`：**不静默截断**，
    截断会漏掉极小 α 从而漏判冲突，见 `_ALPHA_NODE_BUDGET` 与 `AlphaEnumerationOverflow`
    上的说明。

    `deadline` 是**绝对**的单调时钟时刻（`time.monotonic()` 口径，与 `time_budget` 这种
    相对秒数不是一回事），到点抛 `ScanDeadlineExceeded`。它与上面的节点预算是**互补**的：
    节点预算管"枚举规模"，它管"枚举慢"——同样 2×10⁶ 个节点，在 `admissible` 很贵
    （目标 token 多、源多）时可以跑很久，光靠节点计数封不住墙钟。默认 `None` = 不限时，
    既有调用点行为不变。
    """
    target_vec = dict(consumes(target))
    if not target_vec:
        return []
    source_vecs = [dict(consumes(source)) for source in sources]
    caps = [max(_alpha_bound(vec, target_vec), 1) + 1 for vec in source_vecs]
    if any(cap > _ALPHA_HARD_CAP for cap in caps):
        raise ValueError(
            f"alpha bound {caps} exceeds the hard cap {_ALPHA_HARD_CAP}; "
            "the ratio bound is wrong — do not raise the cap, fix _alpha_bound"
        )

    def admissible(alpha: tuple[int, ...]) -> bool:
        for token, need in target_vec.items():
            got = 0
            for vec, count in zip(source_vecs, alpha):
                got += vec.get(token, 0) * count
            if got < need:
                return False
        return True

    def dominates(left: tuple[int, ...], right: tuple[int, ...]) -> bool:
        return all(a >= b for a, b in zip(left, right)) and left != right

    candidates: list[tuple[int, ...]] = []
    # DFS 的展开节点数。见 `_ALPHA_NODE_BUDGET`：这个计数器是唯一的兜底，
    # `scan` 的时间预算够不着单次枚举内部。
    visited = 0

    def walk(prefix: list[int]) -> None:
        nonlocal visited
        if len(prefix) == len(sources):
            alpha = tuple(prefix)
            if any(alpha) and admissible(alpha):
                candidates.append(alpha)
            return
        for value in range(caps[len(prefix)] + 1):
            visited += 1
            if visited > _ALPHA_NODE_BUDGET:
                raise AlphaEnumerationOverflow(
                    f"alpha enumeration exceeded the node budget {_ALPHA_NODE_BUDGET} "
                    f"for {len(sources)} sources with caps {caps}; "
                    "refusing to truncate silently (that would drop minimal alphas "
                    "and under-report conflicts) — narrow S or bound the ratios upstream"
                )
            # 【时间预算】见 `_DEADLINE_CHECK_EVERY`：每这么多个节点才看一次钟，
            # 免得把热循环拖慢。**只在 `deadline is not None` 时才看**，默认路径零开销。
            if deadline is not None and visited % _DEADLINE_CHECK_EVERY == 0 \
                    and time.monotonic() >= deadline:
                raise ScanDeadlineExceeded(
                    f"deadline hit while enumerating minimal alphas for {len(sources)} "
                    f"sources with caps {caps} after {visited} nodes"
                )
            prefix.append(value)
            # 剪枝：若把余下分量全取 0 就已可行，再往上加只会被支配。
            if admissible(tuple(prefix + [0] * (len(sources) - len(prefix)))):
                candidates.append(tuple(prefix + [0] * (len(sources) - len(prefix))))
                prefix.pop()
                break
            walk(prefix)
            prefix.pop()

    walk([])
    # 保留**极小**元：丢掉那些**压住了**另一个候选的（`c >= other` 且 `c != other`）。
    # 注意这个谓词的方向：`c >= other` 说的是 `other` 比 `c` 小，也就是 `c` **下方**还压着
    # 一个候选——"下面还有更小的可行 α"正说明 `c` 不是极小元，该丢。
    #
    # 参数顺序不能反。`dominates(left, right)` 判的是 `left >= right`（`left` 是**大**的那个）；
    # 把"候选有没有压住别人"写成 `dominates(other, c)` 就读反了，变成保留**极大**元：候选里只要
    # 同时存在 (1,1) 与 (2,1)，(1,1) 会被 (2,1) 抹掉，返回 [(2,1)]——于是判据去查一个并不极小的
    # α，(1,1) 上真正的冲突就漏了（下游 doubled_recipe_order / grid_without_recipe_2 都吃这个）。
    # 下面的增量 skyline 里两处 `dominates` 的方向**刻意相反**，见那里的说明。
    #
    # 【时间预算必须也覆盖这一层】它是 O(|candidates| × |kept|) 次 `dominates`：候选上万时这一层
    # 单跑就能到**十分钟**量级，而 `walk` 早已返回——实测装配机数据上 `--time-budget 600`
    # 超时 6 分钟、栈帧落的就是这里（`walk` 里那个看钟够不着它）。所以这里的循环也要看钟。
    # 同样只在 `deadline is not None` 时看，且**不改变任何输出**：没有超时时这个循环与原来的
    # 两两比较版逐字等价（**集合与顺序都一样**，`candidates` 的顺序即 `kept` 的顺序）。
    #
    # 【为什么值得改成增量 skyline】原来那版对每个候选都要与**全部**候选比一遍，是 O(|candidates|²)。
    # deadline 让"预算真的是界"成立了，却没把"这轮审计本来看得见多少"还回来：实测 600s 预算只跑完
    # 11255/239684 组（4.7%），而让整轮扫描在 606s 停下的就是这个函数。改成增量维护后每轮只与
    # 当前 `kept` 比，而 `kept` 是**极小元集**（通常远小于 `candidates`），同一预算下能多看若干组。
    #
    # 【正确性依据：`dominates` 是**严格偏序**】`all(a >= b) and left != right` 非自反、反对称、传递，
    # 于是**任一被支配的候选必被某个 Pareto 极小元支配**：若 `candidate` 上方压着一个 `x`，就把 `x`
    # 沿"更小"的方向降到某个极小元 `m`，由传递性 `candidate` 同样被 `m` 压住，而 `m` 必定已经躺在
    # `kept` 里（`kept` 始终是"已处理前缀的极小元集"）。反过来，`kept` 里一旦有成员被新候选压住就
    # 当场删掉。所以循环结束时 `kept` 恰好是"与全部候选两两比较"会得到的那个集合
    # （实现时已用 28000 组随机候选逐字对拍：集合与顺序全等，0 组不等）。
    kept: list[tuple[int, ...]] = []
    for index, candidate in enumerate(candidates):
        # 【本层**每轮**都看一次钟，不按 `_DEADLINE_CHECK_EVERY` 抽样】那个间隔是给
        # "每次迭代很便宜"的循环（`reach` / `walk`）标定的，本层不是那种循环：每一轮要花
        # O(|kept|) 次 `dominates`（`kept` 最多与 `candidates` 同量级），于是抽样看钟时
        # **相邻两次看钟之间**最坏就有 (interval-1) × |kept| 次比较。按修前实测
        # "单次 `minimal_alphas` 卡 995.2s"反推 |candidates| ≈ 3×10⁴，默认间隔 4096 下
        # 单个间隔最坏能到 10–30 秒——修后那次只超预算 6s 属于运气好，而这一层恰恰就是
        # 修前那 995s 的来源。
        # 相对代价可以忽略，所以这里不抽样：`time.monotonic()` 约 60ns，而每轮至少还要跑
        # 一次 `dominates` 加生成器/取值开销（≥100ns × |kept|），即 <0.1%。
        # 也就是说**间隔在本层只换来正确性损失**，换不来有意义的性能。
        if deadline is not None and time.monotonic() >= deadline:
            raise ScanDeadlineExceeded(
                f"deadline hit while reducing {len(candidates)} candidate alphas "
                f"to the minimal ones (after {index})"
            )
        # 【增量 skyline：两处 `dominates` 的参数顺序**刻意相反**，别"顺手统一"】
        #   · 丢候选：`candidate` **压住**了某个 `kept`（`kept` 里有比它小的）-> 非极小元；
        #   · 删 kept：某个 `kept` **被** `candidate` 压住（`kept` 里有比它大的）-> 不再极小。
        # 只写成同一个方向就是另一个算法了（把极小元过滤成极大元，或留下本该丢的候选），
        # 实测那样写会与两两比较版大面积不等。
        if any(dominates(candidate, other) for other in kept):
            continue
        kept = [other for other in kept if not dominates(other, candidate)]
        kept.append(candidate)
    return kept


def slot_candidates_for(export: Export, layout: list[Recipe]) -> list[tuple[str, ...]]:
    """布局的槽位集合：按 `representative` 全局去重，保留首次出现。

    **这件事不是可选的**：`reach` 的契约要求入参按 `(item, meta, tag)` 去重——Java 的
    `prepareRecipeFind` 会先过 `uniqueItems`（`RecipeMap.java:567-591`）把完全相同的栈折叠成一条，
    所以布局维度 `L` 是"不同输入栈的个数"。若这里漏了去重，Python 会判出 Java 判不了的可达性，
    属**静默多报**。

    **顺序不重要**（spec 3.3 推论三：可达性是二分图匹配，与排列无关），
    所以这里只做去重，不尝试各成员的路径顺序。

    流体侧的重复项同样按 `representative` 折叠，但那是**有意的建模选择**（不是 Java 的行为）——
    `buildFromFluidStacks` 并不去重。见 spec §9.6。
    """
    seen: dict[str, tuple[str, ...]] = {}
    order: list[str] = []
    for recipe in layout:
        for entry in recipe.inputs:
            if entry.representative not in seen:
                seen[entry.representative] = entry.slot_candidates
                order.append(entry.representative)
        for entry in recipe.fluid_inputs:
            if entry.representative not in seen:
                seen[entry.representative] = entry.slot_candidates
                order.append(entry.representative)
    return [seen[token] for token in order]


def _lp_feasible_at_alpha(
    reachable: list[Recipe],
    sources: list[Recipe],
    target: Recipe,
    alpha: tuple[int, ...],
) -> tuple[bool, str]:
    """在给定 α 下判定：是否存在含 target 的平衡计划。

    要判的是：∃ x >= 0，x_target >= 1，使 **消耗** sum_r x_r * consumes(r) 逐分量等于
    sum_i alpha_i * consumes(s_i)，且 **产出** sum_r x_r * output(r) 等于 sum_i alpha_i * output(s_i)。
    两式相减即得净消耗列形式 `sum_r x_r * net(r) = sum_i alpha_i * net(s_i)`（LP 的每一列 = net(r)）。

    x_target >= 1 用 `x_target = 1 + t`（t >= 0）代换掉：
        (1 + t) * net(target) + sum_{r != target} x_r * net(r) = sum_i alpha_i * net(s_i)
    <=> sum_{r != target} x_r * net(r) + t * net(target) = sum_i alpha_i * net(s_i) - net(target)
    也就是 **target 那一列照旧留在 LP 里**（当自由变量 t 用），代价全部体现在右端减掉一份 net(target)。

    **右端必须减 net(target)，不能加**：写成加号会让右端多出两份 net(target)，
    于是"两支配方对称差"这类真冲突会被抹平成可行解（doubled_recipe_order 在 α=(2,) 上
    就是靠这一项区分的：正确右端 {a:1, A:-1} 无解，错写成 {a:3, b:4, A:-3} 则有解）。

    前置条件：**target 必须在 reachable 里**。`analyse` 的循环只遍历 reachable，故生产路径天然满足；
    但若被误用（target 不在列里），`x_target >= 1` 这个约束会**整个消失**（列不存在就没变量可约束），
    右端却仍然扣掉了一份 net(target)——结果是静默给出错误的"可行"。这里宁可大声报错。
    """
    if all(recipe.id != target.id for recipe in reachable):
        raise ValueError(
            f"target recipe {target.id!r} is not in the column set; "
            "the x_target >= 1 constraint would silently vanish"
        )

    all_keys = _union_keys(
        *[_net(r) for r in reachable],
        *[_net(s) for s in sources],
        _net(target),
    )

    # 右端 = sum_i alpha_i * net(s_i) - net(target)，见 docstring 的代换。
    rhs_table: dict[str, Fraction] = {}

    def _weigh(pairs: tuple[tuple[str, int], ...], weight: Fraction | int) -> None:
        for token, amount in pairs:
            rhs_table[token] = rhs_table.get(token, Fraction(0)) + Fraction(amount) * weight

    _weigh(_net(target), -1)
    for count, source in zip(alpha, sources):
        _weigh(_net(source), count)

    rhs = _as_vector(tuple(sorted(rhs_table.items())), all_keys)
    columns = [_as_vector(_net(recipe), all_keys) for recipe in reachable]

    solution = lp_feasible(columns, rhs)
    if solution is None:
        return False, "no-plan"
    return True, "ok"


def analyse(export: Export,
            source_ids: list[int],
            reachable_ids: set[int] | None = None,
            deadline: float | None = None,
            alpha_sink: dict[tuple[tuple[int, ...], int], tuple[tuple[int, ...], ...]] | None = None
            ) -> list[tuple[int, int | None]]:
    """对给定的 S（用配方 id 列表表示）做冲突判定。

    返回 [(witness_recipe_id, binding_alpha_index)]；空列表 = 在扫过的范围内未发现冲突。
    `reachable_ids` 可由调用方传入以避免重算（report.scan 已经算过一次）。

    `deadline` 是**绝对**的单调时钟时刻（`time.monotonic()` 口径）。到点即抛
    `ScanDeadlineExceeded`，**不再往下判**。这是"时间预算真的是界"的落点：光在 `scan`
    的组间检查（循环顶）挡不住单组内部的长时间判定——实测 `--time-budget 600` 跑到
    2486.7s 就是这么来的。撞上截止时刻的那个 S 由 `scan` 记为**未判定（时间）**，
    与 α 枚举超预算分开记，**绝不当作"无冲突"**。

    `alpha_sink` 是可选的**记录表**：命中某个极小 α 的见证会把该见证的 `minimal_alphas`
    结果记进 `alpha_sink[(S, witness_id)]`（只记见证，不记每个被遍历的目标——那会白占内存）。
    `scan` 传一张进来、`render_markdown` 直接取用，于是报告不用为每个见证**重算一遍**
    （实测那是 29.1 MB 报告多跑 34 分钟的全部原因）。**记不记与判定结果无关**：
    这里只写缓存，不读它。
    """
    by_id = export.recipe_by_id()
    sources = [by_id[rid] for rid in source_ids]
    if reachable_ids is None:
        handle = build_trie(export.trie)
        reachable_ids = reach(handle, slot_candidates_for(export, sources), deadline=deadline)
    reachable = [by_id[rid] for rid in sorted(reachable_ids) if rid in by_id]

    # O(S) 的计数表，用于下面的廉价短路。
    expected: dict[str, int] = {}
    for source in sources:
        for token, count in output_vector(source):
            expected[token] = expected.get(token, 0) + count

    findings: list[tuple[int, int | None]] = []
    for target in reachable:
        # 【时间预算的唯一落点之一】在**每个目标**的开头看一次钟。`scan` 的组间检查
        # 挡不住这里的循环：一个 S 的 reachable 可以很大、每个目标还要解 LP，
        # 单组跑几十分钟是实测事实。注意是"看一次就抛"，不是"提前收工"——
        # 只判了一半的 S 记为未判定，绝不把半个结果当完整结果用。
        if deadline is not None and time.monotonic() >= deadline:
            raise ScanDeadlineExceeded(
                f"deadline hit while analysing S={source_ids} "
                f"({len(reachable)} reachable recipes)"
            )
        if target.id in source_ids:
            continue

        # 【顺序不能反】可执行前提必须先判。spec 4.2 的 R_c 是"在该供给下**可执行**的配方"：
        # 若 consumes 取不到（典型：矿辞槽位的候选 token 与源 representative 不重合），
        # R_c 其实不可执行、根本不施加任何约束。把短路排到前面会对这类目标**多报见证**——
        # 短路只看产出，看不见"前提本就不成立"。α 枚举本身很便宜，短路的目的是省 LP，
        # 挪到它后面不影响省下的开销。
        alphas = minimal_alphas(sources, target, deadline=deadline)
        if not alphas:
            # R_c 在这套供给下永远不可执行 -> 不施加任何约束。
            continue

        # 廉价短路。**带前提：只有 α=1 本身可执行时它才与 LP 等价。**
        #
        # 判据的供给是 Σαᵢ·output(sᵢ)，**随 α 变**；而下面只比较 α=1 处的 O₁(S)=Σoutput(sᵢ)。
        # 少了这个前提，它就是在偷偷假设 α≡1——而 spec §4.4 第 4/5 行专门否掉的正是这件事。
        #
        # 可复现实例（`fixtures.proportional_double`）：`a+b→A`（路径 [a,b]）与 `2b+2a→2A`
        # （路径 [b,a]，两条都注册成功）。取 S={0} 时 `minimal_alphas([s0], t) == [(2,)]`——
        # α=1 处 R_c 不可执行、不施加任何约束；α=2 处平衡成立（x_t=1，其余 0）→ 按 spec §4.4
        # 第 5 行应为**无害**。但漏了前提的短路会拿 `2A` 与 α=1 的 `A` 一比、`2 > 1` → 报出
        # `[(1, None)]`，把无害场景误报成冲突。这是本前提存在的唯一理由。
        #
        # 加了前提就 **sound**（不是启发式）：α=1 ∈ 可行域，且该点 LP 不可行
        #   ⟹ 存在极小 α ≤ (1,…,1)（可行域向上封闭：α 逐分量变大只会让供应更多）
        #   ⟹ 由 spec §4.3 的单调性，该极小 α 处同样不可行
        #   ⟹ 见证必然在下面的 α 循环里被找到。
        # 于是短路退化为**纯优化**：命中的场景结果完全一致，只是省下了那次 LP。
        # 前提本身也只是纯计数器比较（O(1)），真数据下绝大多数见证仍在这里就被拦掉。
        # 注意 alpha_index 记 None，表示"这一步没经过 α"（报告里要能区分）。
        alpha_one_executable = all(
            sum(dict(consumes(source)).get(token, 0) for source in sources) >= need
            for token, need in consumes(target)
        )
        if alpha_one_executable and any(
            count > expected.get(token, 0) for token, count in output_vector(target)
        ):
            findings.append((target.id, None))
            continue

        for index, alpha in enumerate(alphas):
            ok, _ = _lp_feasible_at_alpha(reachable, sources, target, alpha)
            if not ok:
                findings.append((target.id, index))
                if alpha_sink is not None:
                    # 键必须带 S：同一个见证可以是**多个不同 S** 的见证，而每个 S 下的
                    # 极小 α 各不相同，只按 witness_id 索引会串味。
                    alpha_sink[(tuple(source_ids), target.id)] = tuple(alphas)
                break
    return findings
