from __future__ import annotations

import re
import unittest
from unittest import mock

from recipeaudit import scanjob
from recipeaudit.criterion import AlphaEnumerationOverflow, ScanDeadlineExceeded
from recipeaudit.report import (SCOPE_ALL, SCOPE_PER_CIRCUIT, SCOPES, ScanResult,
                                _candidate_source_sets, _group_label, _source_group,
                                render_markdown, render_summary, scan, scope_note)
from recipeaudit.tests import fixtures


class TestScan(unittest.TestCase):

    def test_scan_finds_the_grid_conflict(self):
        export = fixtures.grid_without_recipe_2()
        result = scan(export)
        conflicts = [f for f in result.findings if not f[1] == []]
        self.assertTrue(conflicts)

    def test_scan_reports_ghosts(self):
        export = fixtures.grid_all_four()
        patched_recipes = tuple(
            r.__class__(**{**r.__dict__, "in_tree": False}) if r.id == 3 else r
            for r in export.recipes
        )
        patched = export.__class__(**{**export.__dict__, "recipes": patched_recipes})
        result = scan(patched)
        self.assertIn(3, result.ghosts)

    def test_scan_reports_dead_inputs(self):
        export = fixtures.grid_all_four()
        first = export.recipes[0]
        dead_input = type(first.inputs[0])(
            kind="ore",
            amount=1,
            consumable=True,
            representative="ore:nonexistent",
            slot_candidates=(),
        )
        patched_recipe = first.__class__(**{**first.__dict__, "inputs": (dead_input,) + first.inputs[1:]})
        patched_recipes = (patched_recipe,) + export.recipes[1:]
        patched = export.__class__(**{**export.__dict__, "recipes": patched_recipes})
        result = scan(patched)
        self.assertIn(0, result.dead_inputs)

    def test_cycle_conflict_reached_by_a_disjoint_pair(self):
        """守住覆盖判据：暴露五元环冲突的那一对不共享任何 kind。"""
        result = scan(fixtures.five_cycle())
        self.assertIn((0, 3), {source_ids for source_ids, _ in result.findings})

    def test_long_cover_is_enumerated_without_a_k_ceiling(self):
        """极小覆盖的规模没有人为上限：需要 3 条共同参与的 S 必须被枚举到。

        这是用户构造的"任意长度冲突"的 3 元版本——若有人给枚举加回 `--max-union 3`
        之类的常数天花板，这条测试仍会过（3 恰好等于天花板），所以另断言
        `max_source_set_size >= 3` 是**实测**出来的而不是配置的。
        """
        result = scan(fixtures.needs_three_sources())
        self.assertIn((0, 1, 2), {source_ids for source_ids, _ in result.findings})
        self.assertGreaterEqual(result.max_source_set_size, 3)

    def test_time_budget_reports_actual_progress(self):
        result = scan(fixtures.five_cycle(), time_budget=0.0)
        self.assertTrue(result.truncated)
        self.assertLess(result.coverage, 1.0)
        self.assertIn("未扫完", result.coverage_note)
        self.assertEqual(result.completed, 0)

    def test_coverage_note_reports_measured_set_size(self):
        result = scan(fixtures.five_cycle())
        self.assertIn("max_source_set_size=", result.coverage_note)

    def test_alpha_overflow_is_recorded_not_fatal(self):
        """α 枚举超预算只能让**那一个 S** 记为未判定，不能中止整轮扫描。

        `minimal_alphas` 用 `AlphaEnumerationOverflow` 大声失败（不静默截断），
        而 `scan` 若不捕获，一次超预算就带 traceback 中止、前面扫过的结果全丢。
        这条既断言不抛，也断言该 S 落在 `undecided` 而不是被当成"无冲突"。
        """
        export = fixtures.alpha_explosion()
        result = scan(export, time_budget=60.0)
        self.assertTrue(result.undecided)
        # 【为什么绑到实际数值】原写法 `assertIn("未能判定", result.coverage_note)` 是**恒真**的：
        # coverage_note 的模板本身就写着"…，其中 {n} 组未能判定；"，无论 n 是几都含这个子串，
        # 于是它什么都守不住（哪怕 undecided 被清空、扫描被改坏，这条照样绿）。
        # 绑到 len(result.undecided) 之后，它才真的在守"实际记下的组数被如实报出来"。
        self.assertIn(f"{len(result.undecided)} 组未能判定", result.coverage_note)


    def test_time_budget_hit_inside_analyse_is_recorded_as_its_own_kind(self):
        """时间超预算必须记进 `timed_out`，**不能**混进 `undecided`（那是 α 枚举的通道）。

        这里把 `analyse` 换成"撞上截止时刻"的替身，测的是 `scan` 的**分流与记账**：
        两种未判定来源在结果里必须分得开（报告也分两行印），且都得如实标成"未扫完"。
        """
        export = fixtures.five_cycle()
        with mock.patch("recipeaudit.scanjob.analyse",
                        side_effect=ScanDeadlineExceeded("cut mid-S")):
            result = scan(export, time_budget=60.0)
        self.assertEqual(len(result.timed_out), 1)
        self.assertEqual(result.undecided, [])
        self.assertTrue(result.truncated)
        self.assertEqual(result.completed, 0)
        self.assertIn("时间预算耗尽", result.coverage_note)
        self.assertIn("未扫完", result.coverage_note)

    def test_time_budget_hit_inside_reach_is_recorded_too(self):
        """`reach` 是预算真正的漏斗（指数级 trie 搜索），它超时同样记 `timed_out`。"""
        export = fixtures.five_cycle()
        with mock.patch("recipeaudit.scanjob.reach",
                        side_effect=ScanDeadlineExceeded("cut during reach")):
            result = scan(export, time_budget=60.0)
        self.assertEqual(len(result.timed_out), 1)
        self.assertEqual(result.undecided, [])
        self.assertTrue(result.truncated)

    def test_time_budget_hit_inside_analyse_discards_partial_findings(self):
        """只判了一半的 S，其**部分** findings 一律丢弃——半个答案比没有答案更危险。"""
        export = fixtures.grid_without_recipe_2()

        def fake(export, source_ids, reachable_ids=None, deadline=None, alpha_sink=None):
            raise ScanDeadlineExceeded("cut after recording a partial finding")

        with mock.patch("recipeaudit.scanjob.analyse", side_effect=fake):
            result = scan(export, time_budget=60.0)
        self.assertEqual(result.findings, [])
        self.assertEqual(len(result.timed_out), 1)
        # 那个 S 的 S 值要如实出现在报告里（否则读者不知道是哪一组没判）。
        text = render_markdown(export, result)
        self.assertIn("- **未能判定的 S（时间预算在判定途中耗尽", text)


class TestRenderMarkdown(unittest.TestCase):

    def test_markdown_mentions_coverage_and_never_says_no_conflict(self):
        export = fixtures.grid_without_recipe_2()
        text = render_markdown(export, scan(export))
        self.assertIn("扫过的范围内", text)
        self.assertNotIn("无冲突", text)

    def test_markdown_lists_both_sections(self):
        export = fixtures.grid_without_recipe_2()
        text = render_markdown(export, scan(export))
        self.assertIn("## 冲突", text)
        self.assertIn("## 注册异常", text)

    def test_markdown_reuses_the_alphas_computed_by_scan(self):
        """渲染**不得**再为见证重算 α——那是 29.1 MB 报告多跑 34 分钟的全部原因。

        把 `minimal_alphas` 换成"一被调用就炸"的替身：复用缓存时它根本不会被碰到，
        而印出来的 α 数值仍必须与重算一致。
        """
        export = fixtures.grid_without_recipe_2()
        result = scan(export)
        self.assertTrue(result.witness_alphas)
        with mock.patch("recipeaudit.report.minimal_alphas",
                        side_effect=AssertionError("render must not recompute alphas")):
            text = render_markdown(export, result)
        self.assertIn("在 α=(1, 1) 处无法补全", text)

    def test_markdown_falls_back_to_recomputing_without_the_cache(self):
        """缓存缺失不能静默印错：退回重算，数值与 scan 缓存的那份一致。"""
        export = fixtures.grid_without_recipe_2()
        result = scan(export)
        hand_built = ScanResult(**{**result.__dict__, "witness_alphas": {}})
        text = render_markdown(export, hand_built)
        self.assertIn("在 α=(1, 1) 处无法补全", text)

    def test_markdown_survives_a_recompute_that_overflows(self):
        """重算兜不住时退到"某个极小 α"措辞——**报告照样完整**，且不撒谎。

        `render_markdown` 是公开 API，手工拼的 ScanResult 也能喂进来；那时裸抛就等于
        整份报告一个字节都写不出来（spec 9.9 要避免的形态）。
        """
        export = fixtures.grid_without_recipe_2()
        result = ScanResult(**{**scan(export).__dict__, "witness_alphas": {}})
        with mock.patch("recipeaudit.report.minimal_alphas",
                        side_effect=AlphaEnumerationOverflow("boom")):
            text = render_markdown(export, result)
        self.assertIn("在某个极小 α 处无法补全", text)
        self.assertIn("## 注册异常", text)


class TestScanScope(unittest.TestCase):
    """spec 4.5：收窄是精确的限制，且默认开启。"""

    def test_per_circuit_drops_cross_group_sources(self):
        export = fixtures.two_circuits_share_a_part()
        every = _candidate_source_sets(export, scope=SCOPE_ALL)
        per_circuit = _candidate_source_sets(export, scope=SCOPE_PER_CIRCUIT)
        # R4 的极小覆盖必须跨**两种**电路配置取源（R0 出 电路1 与 p，R1 出 q）
        self.assertIn((0, 1), every)
        self.assertNotIn((0, 1), per_circuit)
        # 【元组一律是升序的】`_minimal_covers` 内部 `tuple(sorted(...))`，降序写法恒真、是空转断言。
        self.assertNotIn((1, 3), per_circuit)

    def test_per_circuit_keeps_a_cover_from_its_own_group(self):
        export = fixtures.two_circuits_share_a_part()
        per_circuit = _candidate_source_sets(export, scope=SCOPE_PER_CIRCUIT)
        # R3 的极小覆盖 {R0, R2} 全在 组1 ∪ 组0 内，收窄后必须还在
        self.assertIn((0, 2), per_circuit)

    def test_per_circuit_keeps_a_cover_mixing_one_circuit_with_none(self):
        export = fixtures.two_circuits_share_a_part()
        by_id = export.recipe_by_id()
        # 先守住这个用例**真的**在考那一支：target R5 无电路，两条源一条带电路、一条不带
        self.assertIsNone(by_id[5].circuit)
        self.assertEqual(by_id[6].circuit, 1)
        self.assertIsNone(by_id[7].circuit)
        per_circuit = _candidate_source_sets(export, scope=SCOPE_PER_CIRCUIT)
        # 布局 {电路1, s, t} 只有一种电路配置 -> 在口径内。把无电路 target 的源域收成 组(0)
        # 会把这一类整类丢掉（真实装配机导出实测 2,908 个 S）。
        self.assertIn((6, 7), per_circuit)

    def test_unknown_scope_is_rejected(self):
        with self.assertRaises(ValueError):
            _candidate_source_sets(fixtures.grid_all_four(), scope="per-circuity")

    def test_default_scope_is_per_circuit(self):
        self.assertEqual(scan(fixtures.grid_all_four(), time_budget=5.0).scope,
                         SCOPE_PER_CIRCUIT)

    def test_scan_records_the_scope_it_ran_with(self):
        self.assertEqual(scan(fixtures.grid_all_four(), time_budget=5.0,
                              scope=SCOPE_ALL).scope, SCOPE_ALL)

    def test_scope_note_wording(self):
        self.assertIn("按电路分组", scope_note(SCOPE_PER_CIRCUIT))
        self.assertIn("全量", scope_note(SCOPE_ALL))
        # 口径说明同样受 spec 11 的措辞纪律约束：不得出现"无冲突"三字连排
        for scope in SCOPES:
            self.assertNotIn("无冲突", scope_note(scope))


class TestParallelScan(unittest.TestCase):
    """并行只许改速度，不许改结果——所以对拍的是**产物**，不是"跑通了"。"""

    def test_one_worker_does_not_build_a_pool(self):
        with mock.patch("recipeaudit.scanjob.ProcessPoolExecutor") as pool:
            scan(fixtures.grid_without_recipe_2(), time_budget=5.0, workers=1)
        pool.assert_not_called()

    def test_parallel_matches_serial_exactly(self):
        export = fixtures.doubled_recipe_order()
        serial = scan(export, time_budget=5.0, workers=1)
        parallel = scan(export, time_budget=5.0, workers=2)
        # 空 findings 会让下面每一条都空转 —— 先守住"本用例真的报了冲突"
        self.assertTrue(serial.findings, "doubled_recipe_order 必须报出至少一条见证")
        self.assertEqual(serial.findings, parallel.findings)
        self.assertEqual(serial.completed, parallel.completed)
        self.assertEqual(serial.undecided, parallel.undecided)
        self.assertEqual(serial.timed_out, parallel.timed_out)
        self.assertEqual(serial.unreachable_self, parallel.unreachable_self)
        self.assertEqual(serial.witness_alphas, parallel.witness_alphas)
        # 【为什么这里要规范化两项】`render_markdown` 把 `coverage_note` 整行印进产物，而那一行里
        # 有两项**按定义**就不同：挂钟用时（跑一次一个数）与进程数（本轮刻意印给读者的标签，
        # 见 Step 4）。所以对拍前只抹掉这两项，其余逐字相同——findings 的全部细节、见证 α、
        # 注册异常一条都没放过："并行只改速度"的落点仍然在 `render_markdown` 上，
        # 只是不许把"用时/进程数"这种标签算成产物差异。
        volatile = re.compile(r"用时 \d+\.\d+s|；进程数 \d+")
        self.assertEqual(volatile.sub("", render_markdown(export, serial)),
                         volatile.sub("", render_markdown(export, parallel)))

    def test_worker_batch_honours_an_expired_deadline(self):
        # 不建池、直接调 worker 端：截止时刻已是过去 -> 一条都不许开始。
        # 【id 取 (0,) 与 (2,)】`grid_without_recipe_2()` 的 id 是 **0 / 2 / 3**（它去掉了
        # 原例的 1 号配方），写 `(1,)` 会让 `decide_one` 在 `by_id[1]` 上抛 KeyError；
        # 超时那条分支因为一条都不开始才看不出问题，但 id 本身是错的。
        scanjob._worker_init(fixtures.grid_without_recipe_2(), SCOPE_PER_CIRCUIT, -1.0)
        self.assertEqual([o.kind for o in scanjob._worker_run([(0,), (2,)])],
                         ["skipped", "skipped"])

    def test_worker_batch_decides_when_the_deadline_is_ahead(self):
        scanjob._worker_init(fixtures.grid_without_recipe_2(), SCOPE_PER_CIRCUIT, 60.0)
        kinds = [o.kind for o in scanjob._worker_run([(0,), (2,)])]
        self.assertNotIn("skipped", kinds)
        self.assertNotIn("timeout", kinds)

    def test_coverage_note_names_the_process_count_only_when_parallel(self):
        export = fixtures.grid_all_four()
        self.assertNotIn("进程数", scan(export, time_budget=5.0, workers=1).coverage_note)
        self.assertIn("进程数 2", scan(export, time_budget=5.0, workers=2).coverage_note)

    def test_parallel_actually_uses_a_pool(self):
        """【守住"并行真的在并行"】只对拍产物是**不敏感**的：把 `workers <= 1` 改成恒真
        （永久串行）后产物逐字不变，上面那几条照样全绿——Task 13 的评审实测过这件事。
        所以这里直接盯"池被建了、且 `max_workers` 就是 `workers`"。
        """
        export = fixtures.doubled_recipe_order()
        real = scanjob.ProcessPoolExecutor
        seen: list[int] = []

        class Spy(real):                    # 真子类：池照常工作，只在构造时记一笔
            def __init__(self, *args, **kwargs):
                seen.append(kwargs.get("max_workers"))
                super().__init__(*args, **kwargs)

        with mock.patch.object(scanjob, "ProcessPoolExecutor", Spy):
            # 【两个不同的 workers 值】只测一个值的话，把 `max_workers=workers` 硬编码成
            # `max_workers=2` 仍然是绿的（Task 13 复评实测过这个盲区）。
            for workers in (2, 3):
                seen.clear()
                scan(export, time_budget=30.0, workers=workers)
                self.assertEqual(seen, [workers])

    def test_progress_counts_decided_sets_not_indices(self):
        """进度回调的第一参数必须是**已判定的组数**，不是回填下标。

        并行下两者会分叉：实测 CLI 曾同时印出"99.7%"（下标）与"完成 51.3%"（真实完成）。
        这里把上报间隔压到 1——**不这么做这条测试就是空转**：fixture 的计划只有十几组，
        默认的 `_PROGRESS_EVERY = 100` 一次都不会触发。断言它单调不减，且最后一个值
        与 `result.completed` 同口径。
        """
        export = fixtures.five_cycle()
        seen: list[int] = []
        with mock.patch.object(scanjob, "_PROGRESS_EVERY", 1):
            result = scan(export, time_budget=30.0, workers=2,
                          progress=lambda done, total, elapsed: seen.append(done))
        self.assertTrue(seen, "本用例必须真的产生过进度回调，否则是空转")
        self.assertEqual(seen, sorted(seen), "进度必须单调不减")
        self.assertEqual(seen[-1], result.completed)

    def test_progress_never_counts_unrun_sets(self):
        """`decided` **只许数 `ok` / `overflow`**，数了 `skipped` 就是撒谎。

        真实装配机数据上实测过这个症状：预算耗尽后大批 `skipped` 被算进进度，
        进度条显示 **99.9%** 而摘要里 `completed` 只有 **7.5%**。
        这里把"0 判定 -> 0 进度"钉死：预算为 0 时一条都没被真正判定，
        所以进度回调**一次都不该触发**。

        【`_PROGRESS_EVERY` 必须 patch 成 1】不 patch 的话本用例就是空转：
        fixture 的计划只有十几组，坏实现最多把 `decided` 抬到十几，跨不过默认的 100。
        """
        seen: list[int] = []
        with mock.patch.object(scanjob, "_PROGRESS_EVERY", 1):
            result = scan(fixtures.five_cycle(), time_budget=0.0, workers=1,
                          progress=lambda done, total, elapsed: seen.append(done))
        self.assertEqual(result.completed, 0)
        self.assertEqual(seen, [], "没有任何一条被判定，进度就不该动")


class TestScopeInReport(unittest.TestCase):
    def test_markdown_states_the_scope(self):
        export = fixtures.grid_all_four()
        self.assertIn("口径：全量", render_markdown(export, scan(export, time_budget=5.0,
                                                             scope=SCOPE_ALL)))
        self.assertIn("按电路分组", render_markdown(export, scan(export, time_budget=5.0)))

    def test_markdown_groups_the_detail_by_circuit(self):
        # 【fixture 不能用 grid_all_four】它是**无冲突**的那张网格（findings 恒为空），
        # 而组标题只印在有 findings 的分支里 —— 拿它测分组是**构造性不可能通过**的空转。
        # grid_without_recipe_2 才是既有渲染测试用的那张冲突网格，且同样全无电路 -> 唯一的组是 0。
        export = fixtures.grid_without_recipe_2()
        result = scan(export, time_budget=5.0)
        # 空 findings 会让下面那条断言空转 —— 先守住"本用例真的报了冲突"
        self.assertTrue(result.findings, "grid_without_recipe_2 必须报出至少一条冲突")
        text = render_markdown(export, result)
        self.assertIn("**无电路（", text)

    def test_source_group_labels(self):
        export = fixtures.grid_all_four()
        self.assertEqual(_source_group(export, (0,)), 0)
        self.assertEqual(_group_label(0), "无电路")
        export = fixtures.two_circuits_share_a_part()
        self.assertEqual(_source_group(export, (0, 3)), 1)
        self.assertEqual(_source_group(export, (0, 1)), "跨电路")
        self.assertEqual(_group_label(1), "电路 1")

    def test_witness_summary_is_deduplicated(self):
        """摘要里每条见证**恰好出现一次**。

        【fixture 必须让"去重"这件事可被证伪】`doubled_recipe_order` 在这里是**空转**的：
        它只有 2 条见证、每条只被 **1 组 S** 见证，于是"把去重拆掉"（不去重、见证按 S 逐条印）
        实测仍然全绿——Task 12 评审验过。`two_circuits_share_a_part` 才有区分度：
        实测 12 组 S / 6 条见证 / `#0` 单独被 **7 组 S** 见证，拆掉去重必然变红。
        """
        export = fixtures.two_circuits_share_a_part()
        result = scan(export, time_budget=5.0)
        witnesses = {wid for _S, findings in result.findings for wid, _i in findings}
        # 空 findings 会让下面三条断言全部空转 —— 先守住"本用例真的报了冲突"
        self.assertTrue(witnesses, "本 fixture 必须报出至少一条见证")
        # 再守住"至少有一条见证被 >1 组 S 见证"，否则去重仍不可证伪
        counts: dict[int, int] = {}
        for _S, findings in result.findings:
            for wid, _i in findings:
                counts[wid] = counts.get(wid, 0) + 1
        self.assertGreater(max(counts.values()), 1, "本 fixture 必须有被多组 S 见证的见证配方")
        text = render_markdown(export, result)
        self.assertIn("## 冲突配方摘要（去重）", text)
        self.assertIn(f"涉及 **{len(witnesses)}** 条见证配方", text)
        for wid in witnesses:
            self.assertEqual(text.count(f"| [{wid}] |"), 1)

    def test_summary_table_escapes_pipes_in_descriptions(self):
        export = fixtures.doubled_recipe_order()
        text = render_summary(export, scan(export, time_budget=5.0))
        rows = [line for line in text.splitlines() if line.startswith("| [")]
        # 【前置断言不能省】没有它，某天 fixture 不再产见证时下面的循环一次都不执行、
        # 本条静默变绿（Task 12 评审 M-5）。兄弟用例 `test_witness_summary_is_deduplicated`
        # 一直带着这条防线，这里漏了。
        self.assertTrue(rows, "本用例必须真的产出一张见证表，否则循环是空转")
        for line in rows:
            # 四列 = 五个 `|`；描述里的 `|` 必须转义，否则表格列会被切断
            self.assertEqual(line.count("|") - line.count("\\|"), 5)

    def test_summary_report_omits_the_detail_and_says_so(self):
        export = fixtures.doubled_recipe_order()
        text = render_summary(export, scan(export, time_budget=5.0))
        self.assertIn("## 冲突配方摘要（去重）", text)
        self.assertIn("## 注册异常", text)
        self.assertNotIn("### S = ", text)
        self.assertIn("--report detail", text)

    def test_summary_report_states_the_scope(self):
        """两份产物都**必须**印口径——这是本任务的唯一验收条件（Task 11 评审 M-3）。

        【为什么摘要版要单独一条】摘要版是 CLI 的**默认**产物；而删掉 `render_summary`
        里那一行后其余测试**全绿**（Task 12 评审实测）——那时这条要求就只剩口头约定，
        而它正是防止读者把"单电路口径"读成"任何输入都不会冲突"的唯一防线。
        """
        export = fixtures.grid_without_recipe_2()
        self.assertIn("按电路分组", render_summary(export, scan(export, time_budget=5.0)))
        self.assertIn("口径：全量",
                      render_summary(export, scan(export, time_budget=5.0, scope=SCOPE_ALL)))

    def test_registry_section_explains_the_known_exception(self):
        """`unreachable_self` 那一行不许写成"出现即 bug"。

        真实装配机数据上它印着 `[2318]`，而同一份报告的**上一行**就把 2318 列为
        「空展开输入」的合法例外（空矿辞，spec 9.1）——报告不能用自己的上一行否证自己。
        所以要印**判读方法**，且旧措辞不得回来。
        """
        export = fixtures.grid_without_recipe_2()
        text = render_markdown(export, scan(export, time_budget=5.0))
        self.assertIn("已知的合法例外", text)
        self.assertNotIn("出现即 bug", text)

    def test_circuit_groups_sort_through_mixed_keys(self):
        """分组排序键混合了 `int` 与 `str`，**必须**是真全序。

        最终审查实测：把 `sorted(grouped, key=lambda key: (isinstance(key, str), key))`
        换成裸 `sorted(grouped)` 后 **96 条全绿**——而它在 `--scope all` 下的真实混键上会 `TypeError`。
        本用例用 `scope=all` 在 `two_circuits_share_a_part` 上真的产出三种键
        （实测 `{1: 11, 0: 1, "跨电路": 4}`），所以裸 `sorted` 在这里会当场抛。
        """
        export = fixtures.two_circuits_share_a_part()
        result = scan(export, time_budget=30.0, scope=SCOPE_ALL)
        groups = {_source_group(export, source_ids) for source_ids, _ in result.findings}
        self.assertTrue(any(isinstance(g, str) for g in groups), "本用例必须产出「跨电路」键")
        self.assertTrue(any(isinstance(g, int) for g in groups), "本用例必须产出 int 键")
        # 渲染本身就会走那条排序 → 不抛即是通过的一半，标题齐全是通过的另一半
        text = render_markdown(export, result)
        self.assertIn("跨电路", text)
        self.assertIn("无电路（", text)


if __name__ == "__main__":
    unittest.main()
