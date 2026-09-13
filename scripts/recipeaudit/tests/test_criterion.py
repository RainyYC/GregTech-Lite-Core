from __future__ import annotations

import time
import unittest
from unittest import mock

from recipeaudit.criterion import (
    AlphaEnumerationOverflow,
    ScanDeadlineExceeded,
    analyse,
    consumes,
    minimal_alphas,
    output_vector,
    slot_candidates_for,
)
from recipeaudit.reach import build_trie, reach
from recipeaudit.tests import fixtures


class TestConsumes(unittest.TestCase):

    def test_excludes_non_consumable(self):
        export = fixtures.grid_all_four()
        recipe = export.recipes[0]
        recipe_inputs = tuple(recipe.inputs) + (
            type(recipe.inputs[0])(
                kind="circuit",
                amount=1,
                consumable=False,
                representative="item:circuit:0:none",
                slot_candidates=("item:circuit:0:none",),
            ),
        )
        patched = recipe.__class__(**{**recipe.__dict__, "inputs": recipe_inputs})
        self.assertEqual(consumes(patched), (("item:a:0:none", 1), ("item:c:0:none", 1)))

    def test_merges_same_token(self):
        export = fixtures.doubled_recipe_order()
        self.assertEqual(consumes(export.recipes[1]), (("item:a:0:none", 1), ("item:b:0:none", 2)))


class TestKinds(unittest.TestCase):
    """kinds 与 consumes 的区别是刻意的，别把两者混用。"""

    def test_kinds_includes_non_consumable(self):
        from recipeaudit.criterion import kinds

        export = fixtures.grid_all_four()
        recipe = export.recipes[0]
        circuit = type(recipe.inputs[0])(
            kind="circuit",
            amount=1,
            consumable=False,
            representative="item:circuit:0:none",
            slot_candidates=("item:circuit:0:none",),
        )
        patched = recipe.__class__(**{**recipe.__dict__, "inputs": recipe.inputs + (circuit,)})
        # 电路占一个槽位，所以必须出现在 kinds 里（kinds 描述"有哪些槽位"）……
        self.assertIn("item:circuit:0:none", kinds(patched))
        # ……但不会被吃掉，所以必须从 consumes 里消失。
        self.assertNotIn("item:circuit:0:none", dict(consumes(patched)))


class TestOutputVector(unittest.TestCase):

    def test_items_and_fluids_are_separated_by_prefix(self):
        export = fixtures.grid_all_four()
        self.assertEqual(output_vector(export.recipes[0]), (("out:item:A:0:none", 1),))


class TestMinimalAlphas(unittest.TestCase):

    def test_single_source_scales_up_to_cover(self):
        export = fixtures.doubled_recipe_order()
        alphas = minimal_alphas([export.recipes[0]], export.recipes[1])
        self.assertEqual(alphas, [(2,)])

    def test_single_source_not_executable_ever(self):
        export = fixtures.grid_all_four()
        # 目标需要 c，而源只产 a/b 的消费 -> 永远不可执行
        self.assertEqual(minimal_alphas([export.recipes[0]], export.recipes[2]), [])

    def test_two_sources_minimal_pair(self):
        export = fixtures.grid_all_four()
        # 源 0 = a+c，源 3 = b+d；目标 2 = b+c -> 极小 (1,1)
        self.assertEqual(minimal_alphas([export.recipes[0], export.recipes[3]], export.recipes[2]), [(1, 1)])


class TestAnalyseGrid(unittest.TestCase):
    """spec 4.4 表：判据必须给出正确结论，且命中正确的 α。"""

    def test_all_four_is_harmless(self):
        export = fixtures.grid_all_four()
        self.assertEqual(analyse(export, [0, 3]), [])

    def test_without_recipe_2_is_a_conflict_binding_at_alpha_1_1(self):
        export = fixtures.grid_without_recipe_2()
        findings = analyse(export, [0, 3])
        self.assertEqual(len(findings), 1)
        witness_id, alpha_index = findings[0]
        self.assertEqual(witness_id, 2)
        alpha = minimal_alphas([export.recipe_by_id()[0], export.recipe_by_id()[3]],
                               export.recipe_by_id()[2])[alpha_index]
        self.assertEqual(alpha, (1, 1))

    def test_amplified_recipe_2_is_a_conflict(self):
        """**断言整个见证集 `[1, 2]`**——brief 原写的是 `[1]`，那一版与 spec §4.2 的判据矛盾。

        依据（权威句）：
        - spec §4.2：「后一条等价于：∃ x ∈ ℚ≥0^{Reach}, x_{R_c} ≥ 1，使
          Σ_M x_M·consumes(M) = Σi αi·consumes(Ri)、Σ_M x_M·output(M) = Σi αi·output(Ri)」
          ——两条都是**等式**，不是不等式。判据的集合形式 `Σα·v_i ∈ K_c`（v 把消费与产出拼在一起）
          同样是精确相等。
        - spec §5 Step 4 与 §5.4、以及 `_lp_feasible_at_alpha` 的 docstring：廉价短路与 LP
          **等价、不是近似**。而那个等价只在等式形式下成立（总产出恰为 O(S) 时 x_{R_c} ≥ 1 才逼出
          output(R_c) ≤ O(S)）；若把任一侧放宽成不等式，短路就退化成会误报的近似。

        为什么 id 2（`b+c→B`）在这份 fixture 里也是见证：`grid_all_four` 里它之所以无害，
        靠的是平衡计划 `{a+d→A, b+c→B}` ——消耗恰好 `{a,b,c,d}`、产出恰好 `{A,B}`，与 S 同净交换。
        把 `a+d→A` 增产成 `a+d→2A` 后这条计划被破坏（产出变 `2A+B`），而含 `b+c→B` 的计划
        再无解：产出必须恰好 `{A:1,B:1}`，故 x₁ 与 x₂ 只能取 0（`2A > A`），于是 `x₁+x₂=1`
        与 `x₁+2x₂=0` 矛盾（x₂=−1）。这正是 spec §4.1 说的"额外配方会对配方执行产生影响"。
        """
        export = fixtures.grid_amplified_recipe_2()
        findings = analyse(export, [0, 3])
        self.assertEqual([f[0] for f in findings], [1, 2])
        # id 2 的见证是**解过 LP 才判的**（`B ⊆ O(S)`，短路拦不掉），id 1 才是短路判的。
        self.assertIsNone(findings[0][1])
        self.assertIsNotNone(findings[1][1])

    def test_amplified_recipe_2_is_caught_by_the_cheap_shortcut(self):
        """`2A ⊄ O(S)={A,B}`，所以应该在廉价短路处就判掉，alpha_index 记 None。

        这条守住"短路没被误删成近似优化"——它与 LP 等价（x_{R_c} ≥ 1 强制产出该输出）。
        """
        export = fixtures.grid_amplified_recipe_2()
        findings = analyse(export, [0, 3])
        self.assertIsNone(findings[0][1])

    def test_same_output_case_still_goes_through_the_lp(self):
        """`2b+a→A` 的输出 A ⊆ O(S)={A}，短路拦不掉，必须真的解 LP。"""
        export = fixtures.doubled_recipe_order()
        findings = analyse(export, [0])
        self.assertIsNotNone(findings[0][1])

    def test_doubled_recipe_order_binds_at_alpha_2(self):
        export = fixtures.doubled_recipe_order()
        findings = analyse(export, [0])
        self.assertEqual(len(findings), 1)
        witness_id, alpha_index = findings[0]
        self.assertEqual(witness_id, 1)
        alpha = minimal_alphas([export.recipe_by_id()[0]], export.recipe_by_id()[1])[alpha_index]
        self.assertEqual(alpha, (2,))

    def test_proportional_double_is_harmless(self):
        """成比例放大必须判无害（spec 4.4 第 5 行）。

        这条同时是**短路前提**的回归防线：短路若漏掉"α=1 可执行"那个前提，
        就会拿 `2A` 与 α=1 的 `A` 一比、把这个无害场景误报成冲突。
        """
        export = fixtures.proportional_double()
        source = export.recipe_by_id()[0]
        target = export.recipe_by_id()[1]

        # 自检一（防测试空转）：目标必须**真的在 Reach 里**。两条同路径时后者会被
        # trie 静默丢弃（spec §3.4 表第 1 行），目标不可达 -> 下面的断言会空转通过。
        handle = build_trie(export.trie)
        self.assertIn(target.id, reach(handle, slot_candidates_for(export, [source])))

        # 自检二：α=1 对目标【不可执行】（目标要 2 份 a、2 份 b，而源只给 1 份各），
        # 所以短路的"α=1 可执行"前提不成立、必须落到 LP。α 的极小值是 (2,)。
        self.assertEqual(minimal_alphas([source], target), [(2,)])

        self.assertEqual(analyse(export, [0]), [])


class TestExecutabilityPrecondition(unittest.TestCase):
    """spec §4.2 的前提：R_c 必须在**该供给下可执行**，才谈得上施加约束。

    守的是 `analyse` 里"**先**枚举极小 α、**再**走廉价短路"的顺序。
    把短路排到前面，就会对"可达但不可执行"的目标报出假阳性——本类即那条回归防线。
    """

    def test_reachable_but_not_executable_target_is_harmless(self):
        from recipeaudit.criterion import slot_candidates_for
        from recipeaudit.reach import build_trie, reach

        export = fixtures.reachable_but_not_executable()
        source = export.recipe_by_id()[0]
        target = export.recipe_by_id()[1]

        # 自检一（防测试空转）：目标**确实在 Reach 里**，所以它真的会被 analyse 遍历到。
        reachable = reach(build_trie(export.trie), slot_candidates_for(export, [source]))
        self.assertIn(target.id, reachable)
        # 自检二：它确实**不可执行**——consumes 的 token 与源的供给不重合，极小 α 恒为空。
        self.assertEqual(minimal_alphas([source], target), [])
        # 自检三：廉价短路本来**会**命中（产出 B ⊄ O(S)={A}）——旧顺序正是从这里报出假阳性。
        expected: dict[str, int] = {}
        for token, count in output_vector(source):
            expected[token] = expected.get(token, 0) + count
        self.assertTrue(any(count > expected.get(token, 0) for token, count in output_vector(target)))

        # 结论：不可执行的 R_c 不施加约束 -> 无害，不得报见证。
        self.assertEqual(analyse(export, [0]), [])


class TestAlphaNodeBudget(unittest.TestCase):
    """α 枚举必须被内部预算兜住，且**大声失败**而不是静默截断。

    静默截断会漏掉极小 α、进而漏判冲突（静默**少报**）——那比让一条配方对把扫描打断更糟。
    非回归的另一半由本模块其余用例承担：预算在正常规模上不得误伤。
    """

    def test_runaway_enumeration_raises_instead_of_hanging(self):
        from recipeaudit.criterion import AlphaEnumerationOverflow

        export = fixtures.alpha_explosion()
        # 顺序刻意打乱：预算是枚举规模的性质，不该依赖源的排列。
        sources = [export.recipe_by_id()[2], export.recipe_by_id()[0], export.recipe_by_id()[1]]
        target = export.recipe_by_id()[3]
        with self.assertRaises(AlphaEnumerationOverflow):
            minimal_alphas(sources, target)


class TestAlphaReduction(unittest.TestCase):
    """spec 10.4：非极小 α 的结论必须与极小 α 一致。"""

    def test_non_minimal_alphas_agree_on_harmless_case(self):
        from recipeaudit.criterion import _lp_feasible_at_alpha

        export = fixtures.proportional_double()
        source = export.recipe_by_id()[0]
        target = export.recipe_by_id()[1]
        reachable = [source, target]
        for alpha in [(2,), (4,), (6,)]:
            ok, _ = _lp_feasible_at_alpha(reachable, [source], target, alpha)
            self.assertTrue(ok, f"alpha={alpha} 应可行")

    def test_non_minimal_alphas_agree_on_conflict_case(self):
        from recipeaudit.criterion import _lp_feasible_at_alpha

        export = fixtures.doubled_recipe_order()
        source = export.recipe_by_id()[0]
        target = export.recipe_by_id()[1]
        reachable = [source, target]
        for alpha in [(2,), (4,)]:
            ok, _ = _lp_feasible_at_alpha(reachable, [source], target, alpha)
            self.assertFalse(ok, f"alpha={alpha} 应不可行")


class TestDeadline(unittest.TestCase):
    """`deadline` 必须是**真的界**，且不改变任何判定结果。

    "真的界"指：光在 `report.scan` 的组间检查挡不住单组内部的长判定（实测 `--time-budget 600`
    跑到 2486.7s），所以 `analyse` 自己的目标循环与 `minimal_alphas` 的枚举循环都要看钟。
    超时的形态必须**是 `ScanDeadlineExceeded`**，不能复用 `AlphaEnumerationOverflow`——
    两者在报告里要分开记（规模 vs 墙钟）。
    """

    def test_expired_deadline_stops_analyse_instead_of_running_on(self):
        export = fixtures.alpha_explosion()
        # 这个 S 正常要跑很久（α 枚举规模 ≈ 10⁹）。截止时刻已过 -> 立刻抛，不往下判。
        with self.assertRaises(ScanDeadlineExceeded) as caught:
            analyse(export, [0, 1, 2], deadline=time.monotonic() - 1.0)
        # 是"时间到了"，不是"枚举炸了"：类型必须分开，报告才能分开记。
        self.assertNotIsInstance(caught.exception, AlphaEnumerationOverflow)

    def test_expired_deadline_stops_the_alpha_enumeration_itself(self):
        export = fixtures.alpha_explosion()
        sources = [export.recipe_by_id()[rid] for rid in (0, 1, 2)]
        target = export.recipe_by_id()[3]
        with self.assertRaises(ScanDeadlineExceeded) as caught:
            minimal_alphas(sources, target, deadline=time.monotonic() - 1.0)
        self.assertNotIsInstance(caught.exception, AlphaEnumerationOverflow)

    def test_expired_deadline_stops_the_minimality_filter_itself(self):
        """抛点必须能落在**极小化过滤**那一层——它才是实测的漏斗。

        上面两条用的都是 `alpha_explosion`，而它在 `walk` 里就先抛了，够不到过滤层。
        本用例改用"`walk` 很快返回、但候选数多"的 fixture（`many_alpha_candidates`：
        源各供一种 token，候选有 2^(count-1) 个、而 DFS 只有 O(3^count) 个节点），并把
        `recipeaudit.criterion` 的 `_DEADLINE_CHECK_EVERY` 调到**远大于本 fixture 的
        搜索节点数**：于是 `walk` 的 `visited % _DEADLINE_CHECK_EVERY == 0` 一次都不成立、
        它一轮都不看钟，"`walk` 先抛"这条歧义路径被排除；随后**过滤层**（修后每轮看钟）
        撞上过期的截止时刻。

        【常量是**按值**导入的】`criterion` 从 `reach` 拿到的 `_DEADLINE_CHECK_EVERY`
        只是本模块自己的一个 int：patch `recipeaudit.reach` 的那一份**影响不到**这里，
        所以这里 patch 的是 `recipeaudit.criterion` 自己的那一份。
        """
        count = 9
        export = fixtures.many_alpha_candidates(count)
        sources = [export.recipe_by_id()[rid] for rid in range(count)]
        target = export.recipe_by_id()[count]
        with mock.patch("recipeaudit.criterion._DEADLINE_CHECK_EVERY", 10 ** 9):
            with self.assertRaises(ScanDeadlineExceeded) as caught:
                minimal_alphas(sources, target, deadline=time.monotonic() - 1.0)
        # 是"时间到了"，不是"枚举炸了"：类型必须分开，报告才能分开记。
        self.assertNotIsInstance(caught.exception, AlphaEnumerationOverflow)
        # 【把抛点钉死在过滤层】枚举层与过滤层的措辞不同，按措辞断言，免得这条用例
        # 退化成"其实还是在 walk 里抛的"却照样通过（那样它就白写了）。
        # 候选数是 2^(count-1) 而不是 2^count：最后一个维度被 `walk` 的早停剪枝截住了。
        self.assertIn(f"reducing {2 ** (count - 1)} candidate alphas", str(caught.exception))

    def test_the_filter_checks_the_clock_every_round_not_every_nth(self):
        """`_DEADLINE_CHECK_EVERY` 那套**抽样**对本层不成立：它每轮都要看一次钟。

        守的是"本层不按间隔抽样"这条契约：抽样只适用于每次迭代很便宜的循环，而本层每轮要花
        O(|kept|) 次 `dominates`——按间隔抽样会在相邻两次看钟之间留出几千上万次比较，
        最坏十几秒的越界就是这么来的（修前单次调用卡 995.2s 的也是这一层）。

        做法：把表调成"第一次看钟未过期、第二次（index=1）就过期"，并把
        `_DEADLINE_CHECK_EVERY` 调得远大于候选数。若本层退回按 `index % interval` 抽样，
        第二次根本不会看钟，这条断言就会失败——所以它是这条契约的回归防线，不是空转。
        """
        export = fixtures.many_alpha_candidates(3)
        sources = [export.recipe_by_id()[rid] for rid in range(3)]
        target = export.recipe_by_id()[3]
        now = time.monotonic()
        rounds = {"n": 0}

        def clock() -> float:
            rounds["n"] += 1
            return now - 1.0 if rounds["n"] == 1 else now + 1.0

        # `criterion.time` 只在本模块里被换掉，不去动全局的 time 模块。
        with mock.patch("recipeaudit.criterion.time") as fake_time, \
                mock.patch("recipeaudit.criterion._DEADLINE_CHECK_EVERY", 10 ** 9):
            fake_time.monotonic = clock
            with self.assertRaises(ScanDeadlineExceeded) as caught:
                minimal_alphas(sources, target, deadline=now)
        self.assertIn("(after 1)", str(caught.exception))

    def test_a_future_deadline_does_not_change_the_verdict(self):
        """不超时时，带 deadline 与不带必须**逐字**同结论（否则预算就成了判据的一部分）。"""
        for export, source_ids in (
            (fixtures.grid_without_recipe_2(), [0, 3]),
            (fixtures.grid_all_four(), [0, 3]),
            (fixtures.doubled_recipe_order(), [0]),
        ):
            self.assertEqual(analyse(export, source_ids, deadline=time.monotonic() + 60.0),
                             analyse(export, source_ids))

    def test_alpha_sink_records_the_witness_alphas(self):
        """见证命中的 α 要顺手记进 sink——`render_markdown` 靠它免掉重算。"""
        export = fixtures.doubled_recipe_order()
        sink: dict[tuple[tuple[int, ...], int], tuple[tuple[int, ...], ...]] = {}
        findings = analyse(export, [0], alpha_sink=sink)
        self.assertEqual(findings, [(1, 0)])
        # 键必须带 S：同一个见证可以是别的 S 的见证，而不同 S 下的极小 α 不同。
        self.assertEqual(sink[((0,), 1)], ((2,),))

    def test_alpha_sink_is_not_consulted(self):
        """sink 是**只写缓存**：里面塞垃圾也不得影响判定结果。"""
        export = fixtures.grid_without_recipe_2()
        poisoned: dict[tuple[tuple[int, ...], int], tuple[tuple[int, ...], ...]] = {
            ((0, 3), 2): ((99, 99),),
        }
        self.assertEqual(analyse(export, [0, 3], alpha_sink=poisoned),
                         [(2, 0)])


if __name__ == "__main__":
    unittest.main()
