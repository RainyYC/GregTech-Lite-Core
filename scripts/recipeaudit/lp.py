from __future__ import annotations

from fractions import Fraction

__all__ = ["lp_feasible"]


def lp_feasible(columns: list[list[Fraction]], rhs: list[Fraction]) -> list[Fraction] | None:
    """求 x >= 0 使 A x = rhs，其中 columns[j] 是 A 的第 j 列。

    列主序：A 是 m x n（m = len(rhs)，n = len(columns)），因此每列长度
    必须恰好等于 len(rhs)；传入转置后的矩阵不会报错但会得到错误答案。
    rhs 为空时返回 []。

    用精确有理数的 Phase-1 单纯形。找到返回 x，无解返回 None。
    转轴规则用 Bland 法则（最小下标进基、最小比值出基并在平局时取最小基下标），
    保证不循环。
    """
    m = len(rhs)
    n = len(columns)
    if m == 0:
        return []

    width = n + m
    # 增广表：m 行约束 + 1 行目标。列 0..n-1 是结构变量，n..n+m-1 是人工变量。
    tab = [[Fraction(0)] * (width + 1) for _ in range(m + 1)]
    for i in range(m):
        for j in range(n):
            tab[i][j] = Fraction(columns[j][i])
        tab[i][width] = Fraction(rhs[i])
        if tab[i][width] < 0:
            # 等式两边同乘 -1 让右端非负。注意只能取反结构变量与右端：
            # 人工变量必须在取反之后再放 +1，否则基变量的列不是单位列，
            # 典范形被破坏（Phase-1 会把不可行误判为可行）。
            for j in range(n):
                tab[i][j] = -tab[i][j]
            tab[i][width] = -tab[i][width]
        tab[i][n + i] = Fraction(1)

    basis = [n + i for i in range(m)]

    # Phase-1 目标：最小化人工变量之和。
    for j in range(n):
        tab[m][j] = Fraction(0)
    for j in range(n, width):
        tab[m][j] = Fraction(1)
    tab[m][width] = Fraction(0)
    # 消去目标行中的人工变量（基变量必须在目标行系数为 0）。
    # 通用做法：按基变量在目标行里的系数（不一定等于 1）整行消去。
    for i in range(m):
        factor = tab[m][n + i]
        if factor == 0:
            continue
        for j in range(width + 1):
            tab[m][j] -= factor * tab[i][j]

    def pivot(row: int, col: int) -> None:
        inv = Fraction(1) / tab[row][col]
        for j in range(width + 1):
            tab[row][j] *= inv
        for i in range(m + 1):
            if i == row:
                continue
            factor = tab[i][col]
            if factor == 0:
                continue
            for j in range(width + 1):
                tab[i][j] -= factor * tab[row][j]
        basis[row] = col

    while True:
        enter = -1
        for j in range(width):
            if tab[m][j] < 0:
                enter = j
                break
        if enter < 0:
            break

        leave = -1
        best = None
        for i in range(m):
            if tab[i][enter] <= 0:
                continue
            ratio = tab[i][width] / tab[i][enter]
            if best is None or ratio < best or (ratio == best and basis[i] < basis[leave]):
                best = ratio
                leave = i
        if leave < 0:
            # 无非负比值 -> 无界。Phase-1 目标有下界 0，不可能无界；
            # 走到这里说明实现有误。
            raise AssertionError("phase-1 objective must be bounded")

        pivot(leave, enter)

    # 最优解处目标值 = -tab[m][width]；Phase-1 目标为 0 才可行。
    if tab[m][width] != 0:
        return None

    x = [Fraction(0)] * n
    for i in range(m):
        if basis[i] < n:
            x[basis[i]] = tab[i][width]
    return x
