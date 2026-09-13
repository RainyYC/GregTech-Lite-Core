package gregtechlite.gtlitecore.loader.recipe.audit

import gregtech.api.recipes.RecipeMap
import gregtech.api.recipes.RecipeMaps.ASSEMBLER_RECIPES
import gregtech.api.recipes.RecipeMaps.CIRCUIT_ASSEMBLER_RECIPES
import gregtechlite.gtlitecore.api.recipe.GTLiteRecipeMaps.ALLOY_BLAST_RECIPES
import gregtechlite.gtlitecore.api.recipe.GTLiteRecipeMaps.CIRCUIT_ASSEMBLY_LINE_RECIPES
import gregtechlite.gtlitecore.api.recipe.GTLiteRecipeMaps.COMPONENT_ASSEMBLY_LINE_RECIPES

/**
 * map 名 → RecipeMap 的查找表。要加新表在这里加一行即可。
 *
 * 这一层只做**名字到对象的解析**，不做任何导出或判定：命令侧拿到的必须是与游戏里
 * 真正在用的那**同一个 [RecipeMap] 实例**（`RecipeMaps.ASSEMBLER_RECIPES` 是 static final，
 * `GTLiteRecipeMaps.*` 是 object 的 `@JvmField val`，都只有一份），否则导出的 trie
 * 与运行期实际匹配的 trie 会是两张表，Python 侧的所有结论都建立在错的输入上。
 *
 * 名字里用下划线而不是 [RecipeMap.getUnlocalizedName] 的点号形态（`gt.recipe.assembler`）：
 * 这个名字会进产物文件名（`recipeaudit-<name>-recipes.json`），点号不便于当文件名，
 * 而且 `component_assembly_line` 本来就是 GT 侧那个 builder 的注册名。
 */
internal object RecipeAuditMaps
{

    // @formatter:off

    private val ASSEMBLY_FAMILY = listOf(
        "assembler"               to ASSEMBLER_RECIPES,
        "circuit_assembler"       to CIRCUIT_ASSEMBLER_RECIPES,
        "component_assembly_line" to COMPONENT_ASSEMBLY_LINE_RECIPES,
        "circuit_assembly_line"   to CIRCUIT_ASSEMBLY_LINE_RECIPES,
        // 合金冶炼炉（`recipemap.alloy_blast_smelter.name=合金冶炼炉`，GTLite 自有表）。
        // 它不属于"装配族"，但这一层只做**名字到对象的解析**、不关心表族，所以并进同一张表即可。
        "alloy_blast_smelter"     to ALLOY_BLAST_RECIPES,
    )

    // @formatter:on

    /**
     * 命令补全 / 报错里列出的可用名字（**含** `all`）。报错必须列全，否则使用者只能瞎猜。
     */
    val names: List<String> get() = ASSEMBLY_FAMILY.map { it.first } + "all"

    /**
     * `null` 表示**没有这个名字**（命令侧据此给"未知配方表 + 可用列表"，而不是静默导出一张空表）。
     * `all` 返回整族四条。
     */
    fun byName(name: String): List<Pair<String, RecipeMap<*>>>? = when (name)
    {
        "all" -> ASSEMBLY_FAMILY
        else  -> ASSEMBLY_FAMILY.filter { it.first == name }.takeIf { it.isNotEmpty() }
    }
}
