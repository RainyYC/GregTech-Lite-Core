package gregtechlite.gtlitecore.loader.recipe.audit

import gregtech.api.recipes.Recipe
import gregtech.api.recipes.RecipeMap
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 承接 [gregtechlite.gtlitecore.mixins.gregtech.MixinRecipeMap] 从 `RecipeMap.compileRecipe`
 * 截获的插入结果。
 *
 * 被拒配方在 trie 和 `recipeByCategory` 里不留痕，`RecipeBuilder.buildAndRegister` 又丢弃了
 * `addRecipe` 的返回值（`RecipeBuilder.java:1097`），只能靠观测那次插入尝试拿到。日志也不算观测点：
 * `RecurseIngredientTreeAdd` 的冲突告警被 `ConfigHolder.misc.debug || GTValues.isDeobfEnvironment()`
 * 门控（`RecipeMap.java:982`），生产环境静默。见 spec 1 节与 9.4。
 *
 * **这个 object 必须是 public，不能是 `internal`**：Kotlin 会给 `internal` 成员的名字
 * 加 `$模块名` 后缀，Java 的 mixin 里 `RecipeAuditRecorder.record(...)` 会链接不到。
 * 它是本仓库里少数几个必须暴露给 Java 的 Kotlin 类型之一，除此之外没有别的公开理由。
 * 同理 [record] / [rejectedFor] 必须带 `@JvmStatic`，否则 Java 侧只能拿到 `INSTANCE` 再转发。
 *
 * ### 这里记的是 `compileRecipe` 的返回值，因此有**两类看不见的丢弃**（实测确认，见 task-7-report）
 *
 * 1. **validation 阶段就死的**：`RecipeMap.addRecipe`（`RecipeMap.java:303-320`）在
 *    `postValidateRecipe` 判出 `SKIP` / `INVALID` 时直接 `return false`，**根本不进
 *    `compileRecipe`**，本 hook 看不到。典型是"输入为空 / 输出为空 / 输入超槽位"。
 * 2. **记了冲突却返回 true 的**：`recurseIngredientTreeAdd`（同文件 `:926-1052`，冲突窗口在
 *    `:946-1022`）在"叶子节点上挂的是 Branch 而非 Recipe"时，会打 `Recipe duplicate or conflict found`
 *    日志、却走 `r.right()` 继续递归并在 `count >= ingredients.size()` 处返回 `true`
 *    （实测：`circuit_assembler` / `component_assembly_line` / `circuit_assembly_line`
 *    都能构造出来）。这类配方（下称"**幽灵配方**"）**进了 `recipeByCategory` 但没有 trie 路径**——
 *    而 `getRecipeList()`（`:1239-1242`）读的是 `lookup.getRecipes(true)`，所以它不在
 *    `getRecipeList()` 里，也不在本 recorder 里。
 *
 *    **但"两边都漏"是错的**（spec §9.4 已判定并纠正，别再照旧稿复述）：Task 8 的导出走
 *    `RecipeMap.getRecipesByCategory()`（`:1424`，返回的正是 `:334` 那个 `recipeByCategory`），
 *    幽灵配方**会带着 `inTree=false` 出现在导出的 `recipes` 清单里**。
 *    也就是说，**漏的只有 [rejectedFor] 这一边**，`recipes` 那边是齐的。
 *
 *    **反面陷阱**（这是导出必须用 `getRecipesByCategory()` 的理由之一）：谁若把导出改成走
 *    `getRecipeList()`，幽灵就会**同时从 `recipes` 与 `rejected` 里消失、彻底不可见**——
 *    既没有 trie 路径、又被本 hook 按 `accepted == true` 放过。
 */
object RecipeAuditRecorder
{

    // 必须带上 map 实例：compileRecipe 是在某一张具体的 RecipeMap 上发生的，
    // 不带 map 的话 `/gtlite recipeaudit all` 会把 4 张表的被拒配方堆在一起，
    // 而且连跑两次命令会翻倍（这个列表从服务器启动起一直累积）。
    private val rejectedRecipes = mutableListOf<Pair<RecipeMap<*>, Recipe>>()

    // 按**实例身份**去重（不是 equals）：同一个配方实例可能被多次 record，
    // 见 [record] 的 KDoc。必须用 IdentityHashMap——`Recipe.equals` 只比输入不比输出
    // （`Recipe.java`），用 equals 会把"输入同、输出不同"的两条**不同**配方误判成同一条，
    // 那正好是被拒列表里最常见的一族（同输入换输出）。用 `===` 语义的集合才既拦重复、又不误杀。
    //
    // 与 rejectedRecipes 一样没有加锁：注册期单线程，运行期唯一的写入者是 GroovyScript reload
    // （见 [record] 的 KDoc），同样跑在服务器线程上。
    private val recordedRecipes: MutableSet<Recipe> =
        Collections.newSetFromMap(IdentityHashMap<Recipe, Boolean>())

    /**
     * 记录一次插入尝试。[accepted] 为 `false` 时（trie 里已有同路径配方、即被拒）写入列表。
     *
     * **调用时机**：以**注册期**为主，但不是只在注册期——`VirtualizedRecipeMap.onReload()`
     * （`VirtualizedRecipeMap.java:32`）的 `restoreFromBackup().forEach(recipeMap::compileRecipe)`
     * 会在 GroovyScript reload 时**在运行期**重新走一遍 `compileRecipe`，而被拒的脚本配方
     * 每次 reload 都会被再拒一次。所以列表**可能随 reload 增长**，因此这里按 [Recipe] 的
     * **实例身份**去重：同一个实例（哪怕被 record 多次）只记一条，不同实例照记不误。
     *
     * [recipe] 非空由调用方保证：`compileRecipe(null)` 同样返回 `false`，但它不是一条配方，
     * 记进来只会让下游渲染 token 时 NPE——那道判空放在 mixin 里（Java 侧本来就没有可空类型信息，
     * 暴露在 Kotlin 签名上没有意义）。
     */
    @JvmStatic
    fun record(map: RecipeMap<*>, recipe: Recipe, accepted: Boolean)
    {
        // 先查再存：`Set.add` 返回 false 即"这个实例已经记过"。
        // 注意去重的键是实例身份（IdentityHashMap），不是 equals——理由见 recordedRecipes 的注释。
        if (!accepted && recordedRecipes.add(recipe)) rejectedRecipes.add(map to recipe)
    }

    /** 取出 [map] 这张表上从服务器启动至今累积的所有被拒配方，保持注册顺序。 */
    @JvmStatic
    fun rejectedFor(map: RecipeMap<*>): List<Recipe> =
        rejectedRecipes.filter { it.first === map }.map { it.second }

}
