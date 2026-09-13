from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from recipeaudit.model import TriePath, _check_unique_paths, load_export


def _recipes_doc() -> dict:
    return {
        "map": "assembler",
        "unlocalizedName": "gt.recipe.assembler",
        "exportedAt": "2026-09-13T12:00:00Z",
        "slotLimit": 64,
        "hasOreDictedInputs": True,
        "hasNBTMatcherInputs": False,
        "recipeCount": 1,
        "recipes": [
            {
                "id": 0,
                "circuit": 1,
                "eut": 30,
                "duration": 100,
                "circuitDeclaredFirst": True,
                "inputs": [
                    {
                        "kind": "item",
                        "amount": 1,
                        "consumable": True,
                        "representative": "item:minecraft:iron_ingot:0:none",
                        "slotCandidates": [
                            "item:minecraft:iron_ingot:0:none",
                            "ore:ingotIron",
                        ],
                    },
                    {
                        "kind": "circuit",
                        "amount": 1,
                        "consumable": False,
                        "representative": "item:gregtech:meta_item_1:0:none",
                        "slotCandidates": ["item:gregtech:meta_item_1:0:none"],
                    },
                ],
                "fluidInputs": [
                    {
                        "token": "fluid:water:none",
                        "candidates": ["fluid:water:none"],
                        "amount": 1000,
                        "consumable": True,
                    }
                ],
                "outputs": {
                    "items": [{"token": "item:minecraft:stone:0:none", "count": 2}],
                    "fluids": [{"token": "fluid:steam:none", "amount": 500}],
                },
                "inTree": True,
                "inCategory": True,
            }
        ],
    }


def _trie_doc() -> dict:
    return {
        "map": "assembler",
        "pathCount": 1,
        "paths": [
            {
                "nodes": [
                    "item:minecraft:iron_ingot:0:none",
                    "item:gregtech:meta_item_1:0:none",
                    "fluid:water:none",
                ],
                "nodeMaps": ["nodes", "nodes", "nodes"],
                "recipe": 0,
            }
        ],
        "rejected": [
            {
                "reason": "duplicate-or-conflict",
                "recipe": {
                    "id": None,
                    "circuit": None,
                    "eut": 30,
                    "duration": 100,
                    "circuitDeclaredFirst": True,
                    "inputs": [],
                    "fluidInputs": [],
                    "outputs": {"items": [], "fluids": []},
                    "inTree": False,
                    "inCategory": False,
                },
            }
        ],
    }


class TestLoadExport(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.recipes_path = root / "r.json"
        self.trie_path = root / "t.json"
        self.recipes_path.write_text(json.dumps(_recipes_doc()), encoding="utf-8")
        self.trie_path.write_text(json.dumps(_trie_doc()), encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def test_loads_recipes(self):
        export = load_export(str(self.recipes_path), str(self.trie_path))
        self.assertEqual(len(export.recipes), 1)
        recipe = export.recipes[0]
        self.assertEqual(recipe.id, 0)
        self.assertEqual(recipe.circuit, 1)
        self.assertEqual(recipe.eut, 30)
        self.assertEqual(len(recipe.inputs), 2)
        self.assertEqual(recipe.inputs[0].slot_candidates, ("item:minecraft:iron_ingot:0:none", "ore:ingotIron"))
        self.assertFalse(recipe.inputs[1].consumable)
        self.assertEqual(recipe.fluid_inputs[0].amount, 1000)
        self.assertEqual(recipe.outputs.items, (("item:minecraft:stone:0:none", 2),))
        self.assertEqual(recipe.outputs.fluids, (("fluid:steam:none", 500),))
        self.assertTrue(recipe.in_tree)

    def test_loads_trie_paths(self):
        export = load_export(str(self.recipes_path), str(self.trie_path))
        self.assertEqual(len(export.trie.paths), 1)
        self.assertEqual(export.trie.paths[0].recipe, 0)
        self.assertEqual(len(export.trie.paths[0].nodes), 3)
        self.assertEqual(export.trie.paths[0].special, (False, False, False))

    def test_loads_rejected_recipes(self):
        export = load_export(str(self.recipes_path), str(self.trie_path))
        self.assertEqual(len(export.rejected), 1)
        self.assertIsNone(export.rejected[0].id)

    def test_mixed_path_is_accepted_and_keeps_per_node_map(self):
        """混用路径合法：determineRootNodes 按每个 key 自身分流，链可以跨两个 map。

        这条守住"不要把 trie 表示成整条同类的两组路径"——那种模型会在这里抛错，
        或放宽后让下游按首节点静默建错 trie。
        """
        doc = _trie_doc()
        doc["paths"][0]["nodes"] = ["itemnbt:minecraft:iron_ingot:0:none", "fluid:water:none"]
        doc["paths"][0]["nodeMaps"] = ["specialNodes", "nodes"]
        Path(self.trie_path).write_text(json.dumps(doc), encoding="utf-8")
        export = load_export(str(self.recipes_path), str(self.trie_path))
        self.assertEqual(export.trie.paths[0].special, (True, False))
        self.assertEqual(export.trie.paths[0].recipe, 0)

    def test_rejects_length_mismatch_between_nodes_and_node_maps(self):
        doc = _trie_doc()
        doc["paths"][0]["nodeMaps"] = ["nodes"]
        Path(self.trie_path).write_text(json.dumps(doc), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_export(str(self.recipes_path), str(self.trie_path))

    def test_rejects_empty_path(self):
        doc = _trie_doc()
        doc["paths"] = [{"nodes": [], "nodeMaps": [], "recipe": 0}]
        Path(self.trie_path).write_text(json.dumps(doc), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_export(str(self.recipes_path), str(self.trie_path))

    def test_rejects_unknown_node_map(self):
        doc = _trie_doc()
        doc["paths"][0]["nodeMaps"] = ["nodes", "bogus", "nodes"]
        Path(self.trie_path).write_text(json.dumps(doc), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_export(str(self.recipes_path), str(self.trie_path))

    def test_rejects_node_map_disagreeing_with_token_prefix(self):
        """`nodeMaps` 与 token 前缀是同一事实的两种编码；不一致就是导出器渲染错了。"""
        doc = _trie_doc()
        # 首个 token 是 regular（`item:`），却声称住在 specialNodes
        doc["paths"][0]["nodeMaps"] = ["specialNodes", "nodes", "nodes"]
        Path(self.trie_path).write_text(json.dumps(doc), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_export(str(self.recipes_path), str(self.trie_path))


class TestUniquePaths(unittest.TestCase):
    """导出介质会把 trie 层分得开的两条 key 渲染成同一个 `nodes` 串。

    spec §9.12 原先那句话是**错的**：`keyToken` 把 node 侧 NBT key 的 tag 段恒渲染成 `:none`
    （依据见该函数 docstring），而区分两个 nbt key 的是 matcher/condition 的**身份**
    （`equalIgnoreAmount` 里 `Objects.equals(nbtMatcher)`），不是内容派生的字符串。
    于是同一 ore 上两个不同 matcher 的 key 会渲染出**同一个 token**，导出照实写出两条
    `nodes` 一字不差的路径，而 `reach._insert` 的 `setdefault` 会**静默丢掉后来者**——
    那条配方 `in_tree=true` 却在 Python 侧永远不可达。

    今天不触发（全表 matcher 实例数为 1、真实导出 2691 条路径的 `(nodes)` 重复 0 组），
    但**静默少报**正是本项目最不能留的形态，所以载入期就把它变成**大声失败**。
    """

    def test_duplicate_node_sequences_are_rejected(self):
        paths = (TriePath(nodes=("item:a:0:none",), special=(False,), recipe=0),
                 TriePath(nodes=("item:a:0:none",), special=(False,), recipe=1))
        with self.assertRaises(ValueError) as caught:
            _check_unique_paths(paths)
        self.assertIn("1", str(caught.exception))   # 报出冲突的配方，便于定位

    def test_identical_paths_for_the_same_recipe_are_allowed(self):
        # 同一条配方出现两次不构成冲突（去重即可），不得抛
        paths = (TriePath(nodes=("item:a:0:none",), special=(False,), recipe=0),
                 TriePath(nodes=("item:a:0:none",), special=(False,), recipe=0))
        _check_unique_paths(paths)

    def test_distinct_node_sequences_pass(self):
        paths = (TriePath(nodes=("item:a:0:none",), special=(False,), recipe=0),
                 TriePath(nodes=("item:b:0:none",), special=(False,), recipe=1))
        _check_unique_paths(paths)


class TestLoadExportWiring(unittest.TestCase):
    """`load_export` **真的调用**了那条唯一性校验（接线测试）。

    只测 `_check_unique_paths` 本身不够：把 `load_export` 里那一行接线删掉，其余测试全绿
    （Task 14 实测），"载入期拦截"就只剩口头约定。本用例走**真实文件**，把接线本身钉住。

    **独立成类而不用 `TestLoadExport` 的 setUp**：那个类在计划里是 Task 2 的整块交付物，
    往里插方法会让计划的历史代码块与仓库脱节——而计划脱节在本项目里是缺陷。
    """

    def test_load_export_rejects_collapsed_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            recipes = root / "r.json"
            trie = root / "t.json"
            recipes.write_text(json.dumps(_recipes_doc()), encoding="utf-8")
            doc = _trie_doc()
            first = doc["paths"][0]
            # 第二条路径与第一条的 nodes/nodeMaps 一字不差、配方不同 -> 导出介质分不开它们
            doc["paths"].append({"nodes": list(first["nodes"]),
                                 "nodeMaps": list(first["nodeMaps"]),
                                 "recipe": 1})
            trie.write_text(json.dumps(doc), encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                load_export(str(recipes), str(trie))
        self.assertIn("trie path", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
