"""构造 spec 4.4 表里五个用例的导出结构。

token 约定用单字母，便于阅读：
  item:a:0:none 记作 A，依此类推。所有配方每条输入数量均为 1，除非显式指定。
"""
from __future__ import annotations

from dataclasses import replace

from recipeaudit.model import Export, Outputs, Recipe, RecipeInput, Trie, TriePath


def _tok(name: str) -> str:
    return f"item:{name}:0:none"


def _inp(name: str, amount: int = 1, consumable: bool = True) -> RecipeInput:
    return RecipeInput(
        kind="item",
        amount=amount,
        consumable=consumable,
        representative=_tok(name),
        slot_candidates=(_tok(name),),
    )


def _inp_candidates(representative: str, candidates: tuple[str, ...], amount: int = 1) -> RecipeInput:
    """representative 与候选 token **分开指定**的输入（矿辞槽位的形态）。

    普通 `_inp` 里两者相同（representative 一定是自己的候选），所以那个 helper 造不出
    "可达但 consumes 取不到"的结构；本 helper 专供这一角用。
    """
    return RecipeInput(
        kind="item",
        amount=amount,
        consumable=True,
        representative=representative,
        slot_candidates=candidates,
    )


def _recipe(rid: int, inputs, outputs) -> Recipe:
    items = tuple((_tok(name), count) for name, count in outputs)
    return Recipe(
        id=rid,
        circuit=None,
        eut=30,
        duration=100,
        inputs=tuple(inputs),
        fluid_inputs=(),
        outputs=Outputs(items=items, fluids=()),
        in_tree=True,
        in_category=True,
    )


def _circuit_inp(config: int) -> RecipeInput:
    """电路槽位：**非消耗**，token 带配置号，**两个候选**（真实形态见 spec 4.5 的核对表：
    `item:…:{Configuration:N}` 与其 `itemnbt:` 变体，两个都带配置号）。

    本 fixture 保留的是**判据依赖**的那条性质：token 带配置号（匹配只可能是同配置）、
    **没有通配入口**（不存在"任意配置都吃"的输入形态）、且恰好**两个候选**。这三条合起来
    才是"布局里只含配置 g 的电路时，配置 g' 的配方不可能匹配"的依据，也是 4.5 收窄成立的根据。

    【本 fixture **不**覆盖的一维】第二个候选带 `itemnbt:` 前缀，按 `model._is_special_token`
    它属于 trie 的 `specialNodes`；而 `_export` 只按 `representative`（`item:` 前缀）置
    `special=(False, …)`，所有路径都插进 `nodes`。所以走 `reach` 时 `specialNodes` 那张子表
    始终是空的——两个候选在这里只是"同配置的两种写法"，**特殊节点匹配那一维不被本 fixture 覆盖**。
    """
    tok = f"item:circuit:0:{{Configuration:{config}}}"
    twin = f"itemnbt:circuit:0:{{Configuration:{config}}}"
    return RecipeInput(kind="circuit", amount=1, consumable=False,
                       representative=tok, slot_candidates=(tok, twin))


def _circuit_recipe(rid: int, circuit: int, inputs, outputs) -> Recipe:
    return replace(_recipe(rid, inputs, outputs), circuit=circuit)


def two_circuits_share_a_part() -> Export:
    """跨电路组来源的最小结构（spec 4.5）。

    组1：R0=[电路1, p]、R3=[电路1, r, p]、R4=[电路1, q, p]、R6=[电路1, s]
    组2：R1=[电路2, q]
    组0：R2=[r]、R7=[t]、R5=[s, t]（**无电路的 target**）

    · 目标 **R4**（kinds `{电路1, q, p}`）的极小覆盖是 **{R0, R1}**：R0 出 `{电路1, p}`、
      R1 出 `{q}`，两条都不可少 -> `all` 口径下会被查询，`per-circuit` 下
      （R4 自带电路1 -> 布局必含电路1 -> 源只能落在 组1 ∪ 组0）**没有任何覆盖**，整条被丢弃。
    · 目标 **R3**（kinds `{电路1, r, p}`）的极小覆盖是 {R0, R2}，两条都在 组1 ∪ 组0 内
      -> 两个口径下都必须在。这一支守住"收窄没有把同组覆盖一起砍掉"。
    · 目标 **R5**（kinds `{s, t}`，**无电路**）的唯一极小覆盖是 **{R6, R7}**——一条**带电路**的
      源配一条**无电路**的源。布局 `{电路1, s, t}` 仍只有一种电路配置，**在口径内**
      （这就是"有电路 vs 无电路"那一类的另一半：额外的配方自己无电路、而源里有电路）。
      若把"无电路的 target"的源域收成 组(0)，这一类会被整类丢掉——真实装配机导出上实测 2,908 个 S。

    **路径必须两两不成前缀**（3.4 / `recurseIngredientTreeAdd`）：若同时存在 `[电路1, p]`
    与 `[电路1, p, …]`，后者会被前者的叶子挡掉而注册失败（`alpha_explosion` 的 docstring 有同款说明）。
    所以组1 各条的第二层刻意各异（p / r / q / s），R4 的 `p` 放在**末位**。
    """
    return _export([
        _circuit_recipe(0, 1, [_circuit_inp(1), _inp("p")], [("X", 1)]),
        _circuit_recipe(1, 2, [_circuit_inp(2), _inp("q")], [("Y", 1)]),
        _recipe(2, [_inp("r")], [("W", 1)]),
        _circuit_recipe(3, 1, [_circuit_inp(1), _inp("r"), _inp("p")], [("V", 1)]),
        _circuit_recipe(4, 1, [_circuit_inp(1), _inp("q"), _inp("p")], [("U", 1)]),
        _recipe(5, [_inp("s"), _inp("t")], [("Z", 1)]),
        _circuit_recipe(6, 1, [_circuit_inp(1), _inp("s")], [("S1", 1)]),
        _recipe(7, [_inp("t")], [("S2", 1)]),
    ])


def _export(recipes: list[Recipe]) -> Export:
    paths = tuple(
        TriePath(
            nodes=tuple(i.representative for i in r.inputs),
            # fixture 里的原料全是普通物品，没有 NBT 匹配输入 -> 全 regular。
            special=(False,) * len(r.inputs),
            recipe=r.id,
        )
        for r in recipes
    )
    return Export(
        name="fixture",
        recipes=tuple(recipes),
        trie=Trie(paths=paths),
        has_ore_dict=False,
        has_nbt_matcher=False,
        rejected=(),
    )


def grid_all_four() -> Export:
    """原例：1=a+c->A, 2=a+d->A, 3=b+c->B, 4=b+d->B。"""
    return _export([
        _recipe(0, [_inp("a"), _inp("c")], [("A", 1)]),
        _recipe(1, [_inp("a"), _inp("d")], [("A", 1)]),
        _recipe(2, [_inp("b"), _inp("c")], [("B", 1)]),
        _recipe(3, [_inp("b"), _inp("d")], [("B", 1)]),
    ])


def grid_without_recipe_2() -> Export:
    """反例：去掉 a+d->A。"""
    return _export([
        _recipe(0, [_inp("a"), _inp("c")], [("A", 1)]),
        _recipe(2, [_inp("b"), _inp("c")], [("B", 1)]),
        _recipe(3, [_inp("b"), _inp("d")], [("B", 1)]),
    ])


def grid_amplified_recipe_2() -> Export:
    """增产：把 a+d->A 改成 a+d->2A。"""
    return _export([
        _recipe(0, [_inp("a"), _inp("c")], [("A", 1)]),
        _recipe(1, [_inp("a"), _inp("d")], [("A", 2)]),
        _recipe(2, [_inp("b"), _inp("c")], [("B", 1)]),
        _recipe(3, [_inp("b"), _inp("d")], [("B", 1)]),
    ])


def doubled_recipe_order() -> Export:
    """a+b->A 与 2b+a->A：声明顺序不同（[b,a] vs [a,b]），两条都注册成功，输出相同。

    2b+a 的声明顺序是 b 在前，所以路径是 [b, a]；而 a+b 的路径是 [a, b]。
    （顺序本身对可达性无影响——这里是两条**不同的路径**，故两条都能注册成功、不撞叶子。）
    """
    return _export([
        _recipe(0, [_inp("a"), _inp("b")], [("A", 1)]),
        _recipe(1, [_inp("b", 2), _inp("a")], [("A", 1)]),
    ])


def proportional_double() -> Export:
    """a+b->A 与 2b+2a->2A：成比例放大，应判为无害（spec 4.4 第 5 行）。

    **第二条必须把声明顺序写成 [b, a]**（照 `doubled_recipe_order` 的做法）：
    若写成 [a, b]，两条在 trie 里就是【同一条路径】，后者会被 GT 静默丢弃
    （spec §3.4 表第 1 行）→ 目标根本不可达 → `analyse` 返回空**不是因为它判无害，
    而是因为它压根没遍历它**。那样这条用例就成了空转，
    从未真正验证过 spec §4.4 第 5 行，也守不住 `analyse` 里那个短路的"α=1 可执行"前提。
    """
    return _export([
        _recipe(0, [_inp("a"), _inp("b")], [("A", 1)]),
        _recipe(1, [_inp("b", 2), _inp("a", 2)], [("A", 2)]),
    ])


def five_cycle() -> Export:
    """五元环 a+b, b+c, c+d, d+e, e+a（spec 5.2 的反例结构）。

    取 S = {0, 3}（即 a+b 与 d+e）时，布局 {a,b,d,e} 覆盖了 R4=[e,a] 的 kind 集，
    其两层能分别匹配到 `e`、`a` 两个不同的槽位 → 真冲突。
    **这两条配方不共享任何 kind**——"共享 kind 优先"的启发式会把这一对排到末尾、
    预算一到就永远轮不到。本用例存在的意义就是守住"覆盖判据"不被改回那个启发式。
    """
    return _export([
        _recipe(0, [_inp("a"), _inp("b")], [("A", 1)]),
        _recipe(1, [_inp("b"), _inp("c")], [("B", 1)]),
        _recipe(2, [_inp("c"), _inp("d")], [("C", 1)]),
        _recipe(3, [_inp("d"), _inp("e")], [("D", 1)]),
        _recipe(4, [_inp("e"), _inp("a")], [("E", 1)]),
    ])


def needs_three_sources() -> Export:
    """k=3 才暴露的那种结构（spec 9.1）。

    R0=[x,a]、R1=[b]、R2=[c] 各只含 {a,b,c} 中的部分；R3=[a,b,c] → U。
    任意两条的 kind 并集都覆盖不全 {a,b,c}（0∪1 缺 c、0∪2 缺 b、1∪2 缺 a），
    所以 S={0,1,2} 只能由三元组枚举产生。
    布局是槽位集合 {x,a,b,c}，R3 的路径 [a,b,c] 的三层分别匹配 a、b、c 三个不同槽位，
    而 x 空着不用即可——可达性是与顺序无关的二分图匹配（spec 3.3 推论二），多出的槽位无害。

    注意本用例**不**断言"二元找不到任何冲突"：pair {0,3} 会因覆盖到 R1、R2 而被查询，
    也确实会报出一处冲突。它只断言 **{0,1,2} 这个 S 被枚举到**——
    即极小覆盖枚举的深度确实能超过 2，没有常数天花板。
    """
    return _export([
        _recipe(0, [_inp("x"), _inp("a")], [("P", 1)]),
        _recipe(1, [_inp("b")], [("Q", 1)]),
        _recipe(2, [_inp("c")], [("T", 1)]),
        _recipe(3, [_inp("a"), _inp("b"), _inp("c")], [("U", 1)]),
    ])


def reachable_but_not_executable() -> Export:
    """矿辞槽位造成的"可达但**不可执行**"：spec §4.2 的 R_c 前提不成立，必须判无害。

    R0 的输入槽是矿辞：representative 记矿辞 token `oredict:oreA`，候选里含具体物品
    `item:oreA:0:none`（矿辞槽位的常规形态：任何写出该矿辞的栈都填得进这个槽）。
    R1 的 representative 恰是那个**候选**，于是两条判据在此分叉：

    - **可达**：`slot_candidates_for` 给出的布局槽位是 `{oredict:oreA, item:oreA:0:none}`，
      R1 的 trie 路径 `[item:oreA:0:none]` 命中候选 -> R1 ∈ Reach。
    - **不可执行**：`consumes(R0) = {oredict:oreA}` 与 `consumes(R1) = {item:oreA:0:none}`
      **无交集**，`minimal_alphas` 在任意 α 下都取不到 target 需要的 token -> 恒 `[]`。
      按 §4.2，不可执行的 R_c 不施加任何约束，故 R1 必须判**无害**。

    而 R1 的产出 B ⊄ O(S)={A}，**廉价短路本来会命中** —— 这正是旧顺序（短路在 α 枚举之前）
    会报出的那类假阳性。本 fixture 就是为守住"先判可执行、再走短路"而存在的。
    """
    ore = "oredict:oreA"
    return _export([
        _recipe(0, [_inp_candidates(ore, (ore, _tok("oreA")))], [("A", 1)]),
        _recipe(1, [_inp("oreA")], [("B", 1)]),
    ])


def many_alpha_candidates(count: int = 9) -> Export:
    """极小 α 的**候选**很多、而 `walk` 很快返回的结构。

    `count` 条源各只供一种 token（`t0`..`t{count-1}` 之外还各带一个互不相同的 `z_i`，
    只为让每条路径的**首节点**不与目标的路径撞车、目标真的能进 trie），目标把这 `count`
    种各要 1 份。每条源的 cap 都是 `ceil(1/1) + 1 = 2`，可行组合是 `{1,2}^count`——但
    `walk` 的早停剪枝（"前缀已可行即记录并停止加值"）只对**最后一个**维度生效，所以
    `minimal_alphas` 的 `candidates` 是 `2^(count-1)` 个（`count=9` 时 256 个）。

    存在的意义（见 `test_criterion.TestDeadline`）：`alpha_explosion` 在 `walk` 里就先超时，
    够不到 `minimal_alphas` 末尾那层极小化过滤；而修前实测真正把 600s 预算吃穿 6 分钟的
    正是那一层（`walk` 早已返回，它还在两两比较）。本 fixture 让 `walk` 的节点数只是
    O(3^count)、很快返回，同时把过滤层的候选规模抬到 2^count。
    """
    sources = [
        _recipe(index, [_inp(f"z{index}"), _inp(f"t{index}")], [(f"T{index}", 1)])
        for index in range(count)
    ]
    target = _recipe(count, [_inp(f"t{index}") for index in range(count)], [("U", 1)])
    return _export(sources + [target])


def alpha_explosion() -> Export:
    """α 枚举的规模爆炸：目标每样要 1000 份，而每条源各只供 1 份。

    R0/R1/R2 各只供 1 份（分别供 `a`、`b`、`c`），R3 是目标、三个 token 各要 1000 份。
    `_alpha_bound` 给的是**比值**上界 ceil(1000/1)=1000 -> 每个 cap 都是 1001，
    三个分量相乘 ≈ 10⁹ 个 DFS 节点，远超 `_ALPHA_NODE_BUDGET`（2×10⁶）。

    早停剪枝在这里救不了场：前缀要凑够 1000 份才可行，凑够之前每一层都得分叉展开。
    这不是构造出来的病态输入——真实数据里同一个 token 上"某配方用 1 mB、另一条用 1000 mB"
    就是合法形态（装配机配方的流体量是 1000 mB 级），比值上界因此天然可以很大。
    所以 `minimal_alphas` 必须有内部预算，且**大声失败而不是静默截断**。

    **S 的规模是 3，且这个 3 是覆盖判据【真的会枚举到】的 3。** 每条源的 kinds 与目标的
    kinds 只交一位（R0→a、R1→b、R2→c），所以 {R0,R1,R2} 是 `kinds(R3)={a,b,c}` 的**极小**
    覆盖——少一条就铺不满，多一条就非极小。这是 `report.scan` 能真的撞上 α 预算的前提：
    旧版把三条源都造成"共享 `a`"的形状，于是 `kinds(R3)={a,b}` 被 R1 一条就盖住了、
    `{R0,R1,R2}` 不是任何目标的极小覆盖，scan 根本不会去查这个三元组。

    **trie 路径**：R0/R1/R2 的首节点刻意取 `x`/`y`/`w`（都**不在** R3 的原料里）。
    否则 R3 的路径 `[a,b,c]` 的第一个 token 会撞上某条单元路径留下的叶子，而
    `recurseIngredientTreeAdd` 在"该 key 上已挂着别的配方的叶子"时 `return false`、
    整条配方放弃注册（`reach._insert` 逐句复刻了这条）——R3 就不在 trie 里、`Reach` 里也没有它，
    于是 `analyse` / `scan` 路径上这条 fixture 成了**空转**（直调 `minimal_alphas` 不受影响）。
    """
    return _export([
        _recipe(0, [_inp("x"), _inp("a", 1)], [("P", 1)]),
        _recipe(1, [_inp("y"), _inp("b", 1)], [("Q", 1)]),
        _recipe(2, [_inp("w"), _inp("c", 1)], [("R", 1)]),
        _recipe(3, [_inp("a", 1000), _inp("b", 1000), _inp("c", 1000)], [("T", 1)]),
    ])
