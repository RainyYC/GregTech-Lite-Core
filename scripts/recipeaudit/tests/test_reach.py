from __future__ import annotations

import time
import unittest
from unittest import mock

from recipeaudit.model import Trie, TriePath
from recipeaudit.reach import ScanDeadlineExceeded, build_trie, reach

A = "item:a:0:none"
B = "item:b:0:none"
C = "item:c:0:none"
D = "item:d:0:none"

ORE_A = "ore:oreA"


def _trie(*paths: tuple[tuple[str, ...], int]) -> Trie:
    """默认所有节点都是 regular（`special` 全 False）。"""
    return Trie(
        paths=tuple(
            TriePath(nodes=nodes, special=(False,) * len(nodes), recipe=rid)
            for nodes, rid in paths
        ),
    )


class TestReachDeadline(unittest.TestCase):
    """`reach` 必须能被截止时刻打断，且**不返回半个可达集**。

    它是预算真正的漏斗：槽位多时这个搜索是指数级的，实测单次调用能跑出分钟级，
    而它在 `analyse` 之前调用——只圈住 `analyse` 的话预算照样被冲掉。
    """

    def test_expired_deadline_raises_instead_of_returning_a_partial_set(self):
        trie = _trie(((A, B), 0), ((B, A), 1))
        handle = build_trie(trie)
        # 检查间隔调到 1，让这个只有几个节点的 fixture 也能撞到钟（间隔本身只是开销优化，
        # 与"能不能超时"无关；真实数据上按默认 4096 个节点检查一次）。
        with mock.patch("recipeaudit.reach._DEADLINE_CHECK_EVERY", 1):
            with self.assertRaises(ScanDeadlineExceeded):
                reach(handle, [(A,), (B,)], deadline=time.monotonic() - 1.0)

    def test_a_future_deadline_does_not_change_the_reachable_set(self):
        trie = _trie(((A, B), 0), ((B, A), 1))
        handle = build_trie(trie)
        slots = [(A,), (B,), ("item:x:0:none",)]
        self.assertEqual(reach(handle, slots, deadline=time.monotonic() + 60.0),
                         reach(handle, slots))


class TestReach(unittest.TestCase):

    def test_single_slot_single_recipe(self):
        trie = _trie(((A,), 0))
        self.assertEqual(reach(build_trie(trie), [(A,)]), {0})

    def test_single_slot_does_not_match_other_token(self):
        trie = _trie(((A,), 0))
        self.assertEqual(reach(build_trie(trie), [(B,)]), set())

    def test_two_slots_in_declared_order(self):
        trie = _trie(((A, B), 0))
        self.assertEqual(reach(build_trie(trie), [(A,), (B,)]), {0})

    def test_two_slots_reversed_order_still_found_by_rotation(self):
        # 从 i=1 起：层 0 -> 槽 1(B)，层 1 -> 槽 0(A)
        trie = _trie(((B, A), 0))
        self.assertEqual(reach(build_trie(trie), [(A,), (B,)]), {0})

    def test_recipe_with_more_levels_than_slots_is_unreachable(self):
        trie = _trie(((A, B, C), 0))
        self.assertEqual(reach(build_trie(trie), [(A,), (B,)]), set())

    def test_recipe_with_fewer_levels_than_slots_is_reachable(self):
        # P(R)=1 <= L=2
        trie = _trie(((A,), 0))
        self.assertEqual(reach(build_trie(trie), [(A,), (B,)]), {0})

    def test_added_slot_does_not_revoke_reachability(self):
        # 路径 [B, A] 在布局 [A, B] 上由 i=1 起可达（层 0->槽 1、层 1->槽 0）。
        #
        # 这里**改写了 brief 的第二条断言**（原为 set()，即 spec 5.2 的"三个起点全落空"）。
        # 那句推论把 dive 读成了"只走相邻槽"；但 dive 是绕整圈枚举每个未用槽的
        # （RecipeMap.java:812-825），而 recurse...Collisions 恒返回 null（:784），
        # 所以 `if (r != null) return r;` 从不短路。于是 L=3 时 i=1 起：层 1 先试槽 2
        # （extra，落空），再绕回槽 0（A，命中）——仍然可达。
        # 论证见 .superpowers/sdd/task-3-report.md。
        trie = _trie(((B, A), 0))
        self.assertEqual(reach(build_trie(trie), [(A,), (B,)]), {0})
        self.assertEqual(reach(build_trie(trie), [(A,), (B,), ("item:x:0:none",)]), {0})

    def test_non_consecutive_slot_assignment_is_reached(self):
        # 层 0->槽 0、层 1->槽 2：跳过空槽 1。连续对齐 (0,1)/(1,2)/(2,0) 全部落空，
        # 但 dive 会对每个未用槽各递归一次，所以可达性其实是"路径各层与互不相交的槽位
        # 一一配对"，与槽位顺序无关（详见报告）。
        trie = _trie(((A, B), 0))
        self.assertEqual(reach(build_trie(trie), [(A,), (), (B,)]), {0})

    def test_multiple_recipes_all_collected(self):
        trie = _trie(((A, B), 0), ((B, A), 1))
        self.assertEqual(reach(build_trie(trie), [(A,), (B,)]), {0, 1})

    def test_slot_candidate_alternative_matches(self):
        # 槽位候选含矿辞备选，能命中矿辞 key
        trie = _trie(((ORE_A, B), 0))
        self.assertEqual(reach(build_trie(trie), [(A, ORE_A), (B,)]), {0})

    def test_special_path_only_reachable_from_special_candidate(self):
        trie = Trie(paths=(TriePath(nodes=("itemnbt:a:0:none",), special=(True,), recipe=7),))
        self.assertEqual(reach(build_trie(trie), [("itemnbt:a:0:none",)]), {7})
        self.assertEqual(reach(build_trie(trie), [("item:a:0:none",)]), set())

    def test_mixed_path_crossing_both_maps_is_inserted_correctly(self):
        """层 0 在 specialNodes、层 1 在 nodes —— 混用路径必须按【逐节点】map 建。

        若按首节点给整条路径选 map，叶子会被挂到 specialNodes 那侧，而层 1 的 regular
        候选是去 nodes 查的，于是这条路径永远不可达、Reach 静默漏项。
        """
        trie = Trie(paths=(TriePath(
            nodes=("itemnbt:a:0:none", "item:b:0:none"),
            special=(True, False),
            recipe=9,
        ),))
        self.assertEqual(
            reach(build_trie(trie), [("itemnbt:a:0:none",), ("item:b:0:none",)]),
            {9},
        )


if __name__ == "__main__":
    unittest.main()
