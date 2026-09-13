from __future__ import annotations

import argparse
import os
import sys

from recipeaudit.model import load_export
from recipeaudit.report import (SCOPE_PER_CIRCUIT, SCOPES, render_markdown, render_summary,
                                scan, scope_note)

__all__ = ["main"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="recipeaudit", description="组装机配方冲突探测（离线判定）")
    parser.add_argument("recipes", help="<prefix>-recipes.json 路径")
    parser.add_argument("trie", help="<prefix>-trie.json 路径")
    parser.add_argument("-o", "--out", help="Markdown 报告输出路径；省略则只打印摘要")
    parser.add_argument("--time-budget", type=float, default=600.0,
                        help="扫描时间预算（秒），默认 600。超时即停，并在结果里报告实际进度")
    parser.add_argument("--scope", choices=SCOPES, default=SCOPE_PER_CIRCUIT,
                        help="扫描口径：per-circuit（默认，只考虑输入里最多一种编程电路，"
                             "即 GT /recipecheck 的口径）或 all（任意线性组合，含多电路输入）")
    parser.add_argument("--workers", type=int, default=0,
                        help="并行进程数；0 = 用满 os.cpu_count()，1 = 串行。"
                             "注意每个进程各持一份导出数据（Windows 无 fork，内存 × 进程数）")
    parser.add_argument("--report", choices=("summary", "detail"), default="summary",
                        help="报告形态：summary（默认，口径 + 去重摘要 + 注册异常）"
                             "或 detail（含逐组 S 明细，默认口径下可达 100 MB 量级）")
    args = parser.parse_args(argv)

    workers = args.workers if args.workers > 0 else (os.cpu_count() or 1)

    def report_progress(done: int, total: int, elapsed: float) -> None:
        print(f"  ... {done}/{total} 组（{done / total * 100:.1f}%），已用 {elapsed:.0f}s")

    export = load_export(args.recipes, args.trie)
    result = scan(export, time_budget=args.time_budget, progress=report_progress,
                  scope=args.scope, workers=workers)

    print(f"配方 {len(export.recipes)} 条；{scope_note(result.scope)}")
    print(f"{result.coverage_note}")
    print(f"冲突 {len(result.findings)} 组；幽灵 {len(result.ghosts)} 条；"
          f"空展开输入 {len(result.dead_inputs)} 条；S 自身不可达 {len(result.unreachable_self)} 条；"
          f"注册期被拒 {len(export.rejected)} 条")

    if args.out:
        text = (render_summary(export, result) if args.report == "summary"
                else render_markdown(export, result))
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)
        print(f"报告（{args.report}）已写入 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
