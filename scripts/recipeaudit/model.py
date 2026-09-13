from __future__ import annotations

import json
from dataclasses import dataclass

__all__ = [
    "RecipeInput",
    "Outputs",
    "Recipe",
    "TriePath",
    "Trie",
    "Export",
    "load_export",
]

# 属于 trie 的 specialNodes 的 token 前缀。
# 全仓库只有 MapItemStackNBTIngredient 与 MapOreDictNBTIngredient 覆写 isSpecialIngredient()，
# 所以这两个前缀是穷尽的（map 包下只有 5 个具体 ingredient 类，已核实）。
_SPECIAL_PREFIXES = ("itemnbt:", "orenbt:")
_VALID_NODE_MAPS = ("nodes", "specialNodes")


def _is_special_token(token: str) -> bool:
    """输入槽位候选 token → 该去哪个 map 查。对应 `RecipeMap.determineRootNodes`。"""
    return token.startswith(_SPECIAL_PREFIXES)


@dataclass(frozen=True)
class RecipeInput:
    kind: str
    amount: int
    consumable: bool
    representative: str
    slot_candidates: tuple[str, ...]


@dataclass(frozen=True)
class Outputs:
    items: tuple[tuple[str, int], ...]
    fluids: tuple[tuple[str, int], ...]


@dataclass(frozen=True)
class Recipe:
    id: int | None
    circuit: int | None
    eut: int
    duration: int
    inputs: tuple[RecipeInput, ...]
    fluid_inputs: tuple[RecipeInput, ...]
    outputs: Outputs
    in_tree: bool
    in_category: bool


@dataclass(frozen=True)
class TriePath:
    nodes: tuple[str, ...]
    special: tuple[bool, ...]
    recipe: int | None


@dataclass(frozen=True)
class Trie:
    paths: tuple[TriePath, ...]


@dataclass(frozen=True)
class Export:
    name: str
    recipes: tuple[Recipe, ...]
    trie: Trie
    has_ore_dict: bool
    has_nbt_matcher: bool
    rejected: tuple[Recipe, ...]

    def recipe_by_id(self) -> dict[int, Recipe]:
        return {r.id: r for r in self.recipes if r.id is not None}


def _parse_input(raw: dict) -> RecipeInput:
    return RecipeInput(
        kind=raw["kind"],
        amount=int(raw["amount"]),
        consumable=bool(raw["consumable"]),
        representative=raw["representative"],
        slot_candidates=tuple(raw["slotCandidates"]),
    )


def _parse_fluid_input(raw: dict) -> RecipeInput:
    return RecipeInput(
        kind="fluid",
        amount=int(raw["amount"]),
        consumable=bool(raw.get("consumable", True)),
        representative=raw["token"],
        slot_candidates=tuple(raw["candidates"]),
    )


def _parse_outputs(raw: dict) -> Outputs:
    return Outputs(
        items=tuple((entry["token"], int(entry["count"])) for entry in raw["items"]),
        fluids=tuple((entry["token"], int(entry["amount"])) for entry in raw["fluids"]),
    )


def _parse_recipe(raw: dict) -> Recipe:
    return Recipe(
        id=raw.get("id"),
        circuit=raw.get("circuit"),
        eut=int(raw["eut"]),
        duration=int(raw["duration"]),
        inputs=tuple(_parse_input(x) for x in raw["inputs"]),
        fluid_inputs=tuple(_parse_fluid_input(x) for x in raw["fluidInputs"]),
        outputs=_parse_outputs(raw["outputs"]),
        in_tree=bool(raw["inTree"]),
        in_category=bool(raw["inCategory"]),
    )


def _parse_trie_path(raw: dict) -> TriePath:
    """逐节点解析所属 map。

    **不做"整条同类"的校验**——混用是合法的（见 Interfaces 段对 `TriePath` 的说明）。
    这里校验的是结构自洽：非空、两列等长、map 名合法，以及
    **`nodeMaps` 与 token 前缀一致**。

    最后那条交叉检查是刻意的：`nodeMaps` 与 token 前缀编码了同一个事实（一个 key 属于哪个 map
    由它的类决定，而类决定前缀），两者不一致就说明导出器的 token 渲染与 trie 结构对不上——
    与其静默按其中一个走，不如在这里报错。
    """
    nodes = tuple(raw["nodes"])
    maps = tuple(raw["nodeMaps"])
    if not nodes:
        raise ValueError("empty trie path")
    if len(nodes) != len(maps):
        raise ValueError(f"nodes/nodeMaps length mismatch: {len(nodes)} vs {len(maps)}")
    unknown = [m for m in maps if m not in _VALID_NODE_MAPS]
    if unknown:
        raise ValueError(f"unknown node map(s): {unknown!r}")
    for token, mapped in zip(nodes, maps):
        if _is_special_token(token) != (mapped == "specialNodes"):
            raise ValueError(
                f"token {token!r} is declared in {mapped!r} but its prefix says otherwise; "
                "the exporter's token rendering disagrees with the trie structure"
            )
    return TriePath(
        nodes=nodes,
        special=tuple(m == "specialNodes" for m in maps),
        recipe=raw["recipe"],
    )


def _check_unique_paths(paths: tuple[TriePath, ...]) -> None:
    """拒绝"两条不同配方共用同一个 `(nodes, nodeMaps)`"的导出。

    【为什么要大声失败】导出介质会把 trie 层**分得开**的两条 key 渲染成同一个 `nodes` 串：
    node 侧 NBT key 的 tag 段恒渲染成 `:none`（`RecipeAuditTokens.keyToken` 的 docstring 有依据），
    而区分两个 nbt key 的是 matcher/condition 的**身份**，不是内容派生的字符串。
    两条这样的路径进 `reach._insert` 时，后一条会被 `setdefault` **静默丢掉**——
    配方 `in_tree=true` 却在 Python 侧永不不可达，且现有任何检测器都看不见（spec §9.12 那句
    "不构成 trie 层可见性差异"是**错的**，已改）。

    实测今天不触发：全表 matcher 实例数为 1，真实导出 2691 条路径的 `(nodes)` 重复 0 组。
    但"静默少报"不能用"今天没触发"来担保，所以载入期就拦。

    同一配方的重复路径**不算冲突**（去重即可），故只在 `recipe` 不同时报错。
    """
    seen: dict[tuple[tuple[str, ...], tuple[bool, ...]], int] = {}
    for path in paths:
        key = (path.nodes, path.special)
        other = seen.get(key)
        if other is None:
            seen[key] = path.recipe
        elif other != path.recipe:
            raise ValueError(
                f"two recipes ({other} and {path.recipe}) share an identical trie path "
                f"{path.nodes!r}; the export cannot distinguish them (see _check_unique_paths) "
                "— the Python side would silently drop one of them"
            )


def load_export(recipes_path: str, trie_path: str) -> Export:
    with open(recipes_path, encoding="utf-8") as handle:
        recipes_doc = json.load(handle)
    with open(trie_path, encoding="utf-8") as handle:
        trie_doc = json.load(handle)

    paths = tuple(_parse_trie_path(raw) for raw in trie_doc["paths"])
    _check_unique_paths(paths)

    return Export(
        name=recipes_doc["map"],
        recipes=tuple(_parse_recipe(x) for x in recipes_doc["recipes"]),
        trie=Trie(paths=paths),
        has_ore_dict=bool(recipes_doc["hasOreDictedInputs"]),
        has_nbt_matcher=bool(recipes_doc["hasNBTMatcherInputs"]),
        rejected=tuple(_parse_recipe(entry["recipe"]) for entry in trie_doc.get("rejected", [])),
    )
