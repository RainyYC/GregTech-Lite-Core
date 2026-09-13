from __future__ import annotations

import unittest
from fractions import Fraction as F

from recipeaudit.lp import lp_feasible


class TestLpFeasible(unittest.TestCase):

    def test_no_constraints_is_trivially_feasible(self):
        self.assertEqual(lp_feasible([], []), [])

    def test_single_column_solvable(self):
        # 2 * x0 = 4  ->  x0 = 2
        self.assertEqual(lp_feasible([[F(2)]], [F(4)]), [F(2)])

    def test_single_column_negative_rhs_is_infeasible(self):
        # 2 * x0 = -4, x0 >= 0  ->  无解
        self.assertIsNone(lp_feasible([[F(2)]], [F(-4)]))

    def test_identity_picks_zero(self):
        # x0 = 1, x1 = 0  ->  (1, 0) 是最简解
        self.assertEqual(lp_feasible([[F(1), F(0)], [F(0), F(1)]], [F(1), F(0)]), [F(1), F(0)])

    def test_overdetermined_requires_combination(self):
        # 两个变量：(1,1) 与 (1,2)；rhs = (1,0)  需要 x0 + x1 = 1 且 x0 + 2*x1 = 0
        # -> x1 = -1，与 x1 >= 0 矛盾
        self.assertIsNone(lp_feasible([[F(1), F(1)], [F(1), F(2)]], [F(1), F(0)]))

    def test_overdetermined_feasible_combination(self):
        # 同上列，rhs = (2,3)  ->  x0 = 1, x1 = 1
        self.assertEqual(lp_feasible([[F(1), F(1)], [F(1), F(2)]], [F(2), F(3)]), [F(1), F(1)])

    def test_zero_row_with_nonzero_rhs_is_infeasible(self):
        # 0 * x0 = 1  ->  无解
        self.assertIsNone(lp_feasible([[F(0)]], [F(1)]))

    def test_zero_row_with_zero_rhs_is_ignored(self):
        # 一个变量 x0（列向量 (1, 0)，长度必须等于 rhs 的长度）；
        # 第 1 行是 0 * x0 = 0，不影响可行性。
        self.assertEqual(lp_feasible([[F(1), F(0)]], [F(3), F(0)]), [F(3)])

    def test_negative_column_entries(self):
        # x0 - x1 = 0, x0 + x1 = 2  ->  x0 = x1 = 1
        # 列主序：x0 的列 = (1, 1)，x1 的列 = (-1, 1)
        self.assertEqual(lp_feasible([[F(1), F(1)], [F(-1), F(1)]], [F(0), F(2)]), [F(1), F(1)])

    def test_rational_solution(self):
        # 3 * x0 = 1  ->  x0 = 1/3
        self.assertEqual(lp_feasible([[F(3)]], [F(1)]), [F(1, 3)])


if __name__ == "__main__":
    unittest.main()
