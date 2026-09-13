package gregtechlite.gtlitecore.loader.recipe.audit

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import gregtech.api.recipes.Recipe
import gregtech.api.recipes.RecipeMap
import gregtech.api.recipes.ingredients.IntCircuitIngredient
import gregtech.api.recipes.map.Branch
import gregtechlite.gtlitecore.mixins.gregtech.AccessorRecipeMap
import java.io.File
import java.time.Instant
import java.util.IdentityHashMap
import java.util.stream.Collectors

/**
 * 把一张 [RecipeMap] 的**全部配方**与**它的 trie 结构**导出成两份 JSON，供离线 Python 侧判定。
 *
 * **这里没有任何判定逻辑**——只负责枚举、渲染 token、序列化。任何"顺手判一下"的代码都会成为
 * 第二处真相：Python 侧拿到的就不是"模组的原始状态"，而是"模组 + 导出器预处理过的状态"，
 * 判据的结论会与真实的 GTCEu 语义脱钩，且**是静默的**。
 *
 * 产物两份（spec 7.3）：
 * - `<outDir>/recipeaudit-<name>-recipes.json`：配方清单（含 `id` / 输入 / 输出 / `inTree`）；
 * - `<outDir>/recipeaudit-<name>-trie.json`：trie 的每条根→叶路径（含 `nodeMaps`）与注册期被拒配方。
 *
 * 本文件里**每一处"为什么不能那么写"的注释都不是洁癖**：导出侧的任何一处静默错配（配方被合并、
 * 代表栈漂移、id 与路径对不上），Python 都会拿到错误输入并给出错误结论，而它**不会报错**——
 * 报告看起来完全正常。所以每条约束旁边都写清了违反它的具体后果。
 *
 * ### `inCategory` 字段的**真实含义**，以及它**不能**做什么
 *
 * 这一节讲的是**导出物整体的局限**（同一份判据同时作用于 `recipes` 与 `rejected` 两侧），
 * 不是某个参数的语义——[renderRecipe] 那边只留参数本身。取值一律**按实例身份实算**，
 * 所以下面说的都是"它测得到什么 / 测不到什么"，而不是"某处传了个什么常数"。
 *
 * - **主清单那份是恒真式**：清单本身就来自 `getRecipesByCategory()`
 *   （它返回的正是那个 `recipeByCategory`）。所以 Python 侧的 `inCategory && !inTree`
 *   事实上等价于 `!inTree`——"幽灵判据"里 `inCategory` 那一半今天不承载信息量，
 *   别把它当成独立的一路验证。
 * - **"其实连分类表也没进"的那一类在导出里根本不可见**：`RecipeMap.addRecipe`
 *   （`RecipeMap.java:303-320`）在 `postValidateRecipe` 判出 `SKIP` / `INVALID` 时直接
 *   `return false`，**根本不进 `compileRecipe`**，本工具的记录钩子（[RecipeAuditRecorder]）
 *   看不到它们，而它们也不在 `recipeByCategory` 里，所以既不进 `recipes` 也不进 `rejected`。
 *   这是 spec §9.4 已列的**已知缺口**，不是本文件能补的——本字段只是**如实报告**
 *   "这条不在分类表里"，它测不到"有多少条被更早地丢掉了"。
 * - **它无法检测"清单源被换掉"**：若有人把 `allRecipes` 从 `getRecipesByCategory()` 换成
 *   `getRecipeList()`，这个字段**两侧的取值都不会变**（换来的清单里的每一条当然也都在分类表里；
 *   被拒的那批也照旧不在），于是一个都不报警。**要发现那种改动，看 `paths` 里 `recipe: -1`
 *   的条数**（今天恒为 0）：`getRecipeList()` 走 `getRecipes(true)` 会滤掉 `isHidden()` 的配方，
 *   而那些配方**仍在 trie 的叶子上**，`walk` 按 `idOf[...] ?: -1` 渲染时就会查不到 id。
 *   换句话说，`inCategory` 报的是"这条在不在分类表里"，它**不是**"清单有没有漏"的哨兵——
 *   后者只能由 `recipe: -1` 发现。
 */
internal object RecipeMapExporter
{

    // 布局槽位上限，出自 spec §5.2（"`|kinds(R_c)|` 受该表的输入槽位上限（64，实际远小于此）约束"），
    // 而 64 这个数本身来自 GT 搜索期那个防"一层占两个槽"的 `skip` **位图**：它是 `long`
    // （`RecipeMap.java:635` 的 `1L << i` 播种、`:702` / `:807` 的 `skip & (1L << i)` 判占用），
    // `long` 只有 64 位，故一张表能被搜索到的布局最多 64 个槽。
    //
    // **取错会怎样**：Python 侧的 `skip` 是**任意精度 int**（`reach.py` 的 `skip | (1 << i)`），
    // 与 Java 的 `long` 不同宽，所以这个数是两个实现之间唯一的宽度契约。
    // - 取大（> 64）：Python 会去建一个 Java 建不出来的 65+ 槽布局并判它可达；而 Java 那边
    //   `1L << 64` 的移位量按 64 取模、回绕成 `1L`，与槽 0 撞位——判据于是在 Java 达不到的
    //   可达性上给出结论（**静默多报**）。
    // - 取小：合法布局被截掉，可达判成不可达（静默假阴性）。
    // 目前 Python 侧还没有读这个字段的消费者（全仓只有 `tests/test_model.py` 的 fixture 里
    // 出现过这个键），所以它是 spec 声明的 schema 契约，而不是当下生效的输入——正因如此，
    // 取错不会立刻炸，只会等到有人按它建布局时才发作。
    private const val SLOT_LIMIT = 64

    // **`serializeNulls` 必须开**：Gson 默认的 `JsonWriter` 在遇到 `JsonNull` 成员时会**把整个成员
    // 丢掉**（`JsonWriter.nullValue()` 在 `deferredName != null` 且 `!serializeNulls` 时直接
    // `deferredName = null; return`）。于是 `add("id", JsonNull.INSTANCE)` / `add("circuit", ...)`
    // 写出来的是**字段不存在**，而不是 spec 7.3 里写明的 `"circuit": null`。
    // Python 侧 `_parse_recipe` 恰好用 `raw.get("circuit")` / `raw.get("id")` 读它们，
    // 所以"字段缺席"与"值为 null"在那边不可区分、**不会报错**——正因如此它瞒得过一整轮审计，
    // 只有在别的消费者按 schema 取键时才炸（本次实测就是被一个 `r['circuit']` 的 KeyError 抓到的）。
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    /**
     * 导出 [map] 这张表，写出两份 JSON 并返回它们。
     *
     * @param name 产物文件名里的表名（与 [RecipeMap.getUnlocalizedName] 不同：那是 `gt.recipe.assembler`，
     *             带点号，不适合当文件名）。
     */
    fun export(map: RecipeMap<*>, name: String, outDir: File): Pair<File, File>
    {
        val lookup = (map as AccessorRecipeMap).auditLookup()

        // 配方清单**必须**来自 getRecipesByCategory()（裸 ArrayList，不去重）。
        // **不能**用 getRecipeList()：它用 `ObjectOpenHashSet<Recipe>` 去重，而 `Recipe.equals`
        // 只比输入不比输出（`Recipe.java:351-356`，`hasSameInputs && hasSameFluidInputs`），
        // 于是"输入多重集相同、输出不同"的一对会被合并成一条——而那一对恰好就是判据要看的对象，
        // 工具会因此看不见它存在的理由。
        val allRecipes = map.recipesByCategory.values.flatten()

        // 判 inTree 必须用 lookup.getRecipes(false)，**不能**用 map.recipeList，也不能用 getRecipeList()：
        // 后两者走 getRecipes(true)，会过滤掉 isHidden() 的配方（`Branch.java:36-38` 的
        // `filter(t -> !t.isHidden())`），于是每条隐藏配方都会被误报成"幽灵"。
        //
        // **不能用 `Stream.toList()`**：它是 **Java 16** 才引入的方法，而本仓库的目标是
        // **Java 8**（`build.gradle.kts` 的 `kotlin { jvmToolchain(8) }`），它在这里不可用。
        // 这里显式走 `Collectors.toList()`（Java 8 就有）——**这个写法在两种编译目标下都正确**，
        // 它不依赖 Java 16 的成员方法，也不依赖 Kotlin 的 `kotlin.streams.toList` 扩展函数，
        // 所以不必去争论"到底是编不过还是运行期炸"：换成它，这个争论就不存在了。
        val inTreeRecipes: List<Recipe> = lookup.getRecipes(false).collect(Collectors.toList())

        // 内容派生的稳定 id：**不能用列表下标**——`getRecipeList()` 的末级 tiebreaker 是
        // `Recipe.hashCode`，它经 `hashInputs`（`Recipe.java:369-381`）引用了 `Item.hashCode()`
        // 这个 identity hash，跨 JVM 启动会变（spec 7.2）。
        //
        // 指纹**每条只算一次**（先 map 成 `(指纹, 配方)`，之后只读 `first`）：`stableKey` 要遍历
        // 该配方的全部输入输出、并为每个槽位做 `OreDictionary` 查询，而 `sortedBy` 是**按比较器
        // 逐次调用** selector 的——写成 `sortedBy { stableKey(it) }` 会让整轮排序反复重算指纹
        // （实测 assembler 单表整轮 ≈515 ms）。这是纯浪费，不是故障，
        // 但它的代价随配方数线性增长，没有理由留着。
        //
        // **行为必须完全等价**：`distinctBy` 保留每个指纹**首次出现**的那条（Kotlin 的
        // `distinctBy` 与 `sortedBy` 都是稳定的），先 map 不改变这个语义；排序键仍是同一个字符串，
        // 所以同一组配方会以同一个顺序产出（`distinctBy`/`sortedBy` 作用于 `Pair` 与作用于
        // `Recipe`，在 selector 相同时结果逐条相同）。
        val keyedRecipes = allRecipes.map { RecipeAuditTokens.stableKey(it) to it }
        val ordered = keyedRecipes
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { it.second }
        // 【必须用 IdentityHashMap 当 key】`Recipe.equals` 只比输入不比输出（`Recipe.java:351-356`），
        // 而 `stableKey` 是内容派生的。用普通 HashMap 的话，两条"输入相同、输出不同"的配方会互为 equals、
        // 撞成同一个 id，Python 侧于是拿到张冠李戴的 id 并把它们静默合并——
        // 正是 spec 7.3 里 `getRecipeList()` 那个陷阱的同一形态，只是换了个位置。
        val idOf: MutableMap<Recipe, Int> = IdentityHashMap()
        ordered.forEachIndexed { index, recipe -> idOf[recipe] = index }

        // `inCategory` 一律**按实例身份实算**（`===`），既不写死、也不从"它来自哪个方法"推断。
        //
        // 必须是身份而不是 `contains` / `equals`：`Recipe.equals` 只比输入不比输出
        // （`Recipe.java:351-356`），用 equals 查分类表会把"输入相同、输出不同"的另一条配方
        // 误认成在表里（或反过来漏认）。这与同函数 `inTree` 的口径是**同一条**理由，两侧必须一致。
        //
        // 主清单那份本可以写死 `true`（它自己就是 `getRecipesByCategory()` 摊平的），但"恒真"只由
        // 那行代码的来历保证、不由这里测出；若哪天 `allRecipes` 的来源被换掉，写死的 `true` 不会
        // 报警。实算则两侧走同一份判据，"口径一致"才是可验证的事实。
        //
        // `rejected` 那侧尤其**不能**写死 `false`：同一个 `Recipe` **实例**若先插入成功、之后又被
        // `addRecipe` 而遭拒（本分支的 `dedupe re-inserted script recipes` 就是这类历史），它会
        // **同时**躺在分类表与 `rejected` 里——写死 `false` 就是反向假值，而且同样静默。
        val categoryGroups = map.recipesByCategory.values
        fun inCategoryTable(recipe: Recipe): Boolean =
            categoryGroups.any { group -> group.any { it === recipe } }

        val recipesDoc = JsonObject()
        recipesDoc.addProperty("map", name)
        recipesDoc.addProperty("unlocalizedName", map.unlocalizedName)
        recipesDoc.addProperty("exportedAt", Instant.now().toString())
        recipesDoc.addProperty("slotLimit", SLOT_LIMIT)
        // 这两个开关报的是**导出物自身的性质**（清单里有没有矿辞输入 / 有没有 NBT matcher 输入），
        // 而不是 `RecipeMap` 上那两个"一旦置真就不复位"的 sticky 字段：后者会被**被拒配方**置真，
        // 于是可能出现"trie/清单里根本没有 `ore:` 键，开关却是 true"的错配。
        // Python 侧拿它们当"这张表值不值得按某类分支处理"的提示，报自洽的那个口径才不会误导。
        recipesDoc.addProperty("hasOreDictedInputs", ordered.any { recipe -> recipe.inputs.any { it.isOreDict } })
        recipesDoc.addProperty("hasNBTMatcherInputs",
            ordered.any { recipe -> recipe.inputs.any { it.hasNBTMatchingCondition() } })
        recipesDoc.addProperty("recipeCount", ordered.size)
        // **自报"被指纹合并掉了几条"**（spec 7.2 第四处）。`distinctBy { stableKey }` 是靠指纹去重的，
        // 指纹一旦不够细，就会有两条不同的配方被静默合并、其中一条从导出里消失；
        // 而它残留在 trie 里的路径会变成 `recipe: -1`，被 Python 侧静默过滤——**整条配方对分析不可见**，
        // 判据于是在"少了一条配方"的输入上给出"无冲突"（静默假阴性）。
        // 实测今天这批数据是 0，但"恰好没发作"不是保证，所以把它变成**可观测的数字**：
        // 非 0 就说明指纹粒度不足，要么去修 `stableKey`，要么在报告里显式声明丢了几条。
        recipesDoc.addProperty("mergedByFingerprint", allRecipes.size - ordered.size)
        recipesDoc.add("recipes", JsonArray().apply {
            // 主清单**就是** `getRecipesByCategory()` 摊平的产物，所以这里的 `inCategory` 实测必然为真
            // （实测而非写死，理由见上面 `inCategoryTable` 处的注释）。
            ordered.forEach { recipe ->
                add(renderRecipe(recipe, idOf[recipe], inTreeRecipes, inCategory = inCategoryTable(recipe)))
            }
        })

        val trieDoc = JsonObject()
        trieDoc.addProperty("map", name)
        val pathArray = JsonArray()
        walk(lookup, ArrayDeque(), ArrayDeque(), pathArray, idOf)
        // **写出前按 (recipe id, nodes) 规范排序。** `walk` 遍历的是 `branch.nodes` / `branch.specialNodes`
        // 这两个 FastUtil（`Object2ObjectOpenHashMap`）map，顺序由 key 的 hash 决定，而 key 的 hash 里
        // 含 `Item` 的 identity hash、**跨运行不稳定**。
        // Python 把 paths 当集合用（不影响结论），但顺序不稳会让"导出两次 diff 看配方表有没有变"
        // 这个核心用法失效——而且失效是静默的：diff 出一堆噪声，容易被误判成"配方变了"。
        val sortedPaths = pathArray.map { it.asJsonObject }.sortedWith(
            compareBy(
                { it.get("recipe").asInt },
                { it.getAsJsonArray("nodes").joinToString("|") { node -> node.asString } },
            ),
        )
        trieDoc.addProperty("pathCount", sortedPaths.size)
        trieDoc.add("paths", JsonArray().apply { sortedPaths.forEach { add(it) } })
        // 被拒配方**不在**上面的 `recipes` 里（`compileRecipe` 只在插入成功时才写分类表，
        // `RecipeMap.java:333-340`），所以必须自带完整数据，否则报告只能打印一个指向不了的编号。
        trieDoc.add("rejected", JsonArray().apply {
            RecipeAuditRecorder.rejectedFor(map).forEach { recipe ->
                add(JsonObject().apply {
                    addProperty("reason", "duplicate-or-conflict")
                    // **必须实算**，不许写死 `false`：被拒配方是 `compileRecipe` 返回 false 的那批，
                    // 而分类表只在插入**成功**时才写（`RecipeMap.java:333-340`），所以它们**通常**
                    // 不在里面。但"通常"不是"必然"——实例被重复插入时它会同时在两边（见
                    // `inCategoryTable` 处的注释），写死就把那条的反向假值也一起静默了。
                    add("recipe", renderRecipe(recipe, null, inTreeRecipes, inCategory = inCategoryTable(recipe)))
                })
            }
        })

        val recipesFile = File(outDir, "recipeaudit-$name-recipes.json")
        val trieFile = File(outDir, "recipeaudit-$name-trie.json")
        // 这一句真正解决的只有"调用方还没把目录建出来"：那时 `writeText` 会抛
        // `FileNotFoundException`（响亮失败，不会静默丢数据），所以它省掉的是**运行前手建目录**
        // 这一步，没有别的作用。
        //
        // **它不解决"配对"问题，本函数也不解决**：目录已存在时 `mkdirs()` 是个 no-op，于是
        // "第一份写成功、第二份才失败"照样会在目录里留下"新的 recipes 配旧的 trie"——而这两份
        // 文件按文件名配对、下游无从分辨版本。要真修它得走"先写临时文件、两份都成功再统一
        // rename"，那不在本次范围内（这里刻意不实现，只点明）。
        outDir.mkdirs()
        recipesFile.writeText(gson.toJson(recipesDoc))
        trieFile.writeText(gson.toJson(trieDoc))
        return recipesFile to trieFile
    }

    /**
     * 单条配方 → JSON。**必须全函数**：渲染器里任何一个"这条不该出现所以可以崩"的假设，
     * 代价都是整次导出失败（而不是跳过一条）——配方表里任何一条畸形数据都值得如实导出。
     *
     * @param id     稳定 id；`null` 表示这条不在 `recipes` 清单里（被拒配方）。
     * @param inTree 判"有无 trie 路径"用的配方集合（`lookup.getRecipes(false)`）。
     * @param inCategory 这条配方**是否在 `RecipeMap.recipesByCategory` 里**。**必须由调用方显式传入**，
     *               渲染器不代劳：主清单与 `rejected` 走的是**同一个渲染器**，只有两边都经
     *               **同一份实算**（`===` 查分类表）才谈得上口径一致。这个字段**测不到什么**、
     *               以及它为何**不能当哨兵**，见本 object KDoc 的 `inCategory` 段。
     */
    private fun renderRecipe(recipe: Recipe, id: Int?, inTree: Collection<Recipe>,
                             inCategory: Boolean): JsonObject = JsonObject().apply {
        if (id != null) addProperty("id", id) else add("id", JsonNull.INSTANCE)
        // `Recipe` 没有 getCircuit() / hasCircuit()，电路只能从输入里认。
        val circuit = circuitOf(recipe)
        if (circuit != null) addProperty("circuit", circuit) else add("circuit", JsonNull.INSTANCE)
        // **getter 的名字是 `getEUt()`（小写 t），不是 `getEUT()`** —— 写 `recipe.getEUT()` 会
        // Unresolved reference；Kotlin 把它合成为属性 `eUt`（与 `RecipeBuilder.EUT` 同名不同形），
        // 本仓库既有写法也是 `recipe.eUt`（`RecipeAuditTokens.stableKey`）。
        addProperty("eut", recipe.eUt)
        addProperty("duration", recipe.duration)
        // 声明顺序下电路是否在首位——纯诊断字段，判据不依赖它（可达性与槽位顺序无关，spec 3.3 推论三）。
        addProperty("circuitDeclaredFirst", recipe.inputs.firstOrNull()
            ?.let { RecipeAuditTokens.isCircuit(it) } ?: false)

        // 必须过一遍 uniqueIngredientsList（RecipeMap 的公开静态方法）：它按 equalIgnoreAmount 去重
        // 并把 IntCircuitIngredient 前置到 index 0（`RecipeMap.java:601-619`）——这正是 trie 路径的形态
        // （`fromRecipe` → `buildFromRecipeItems(list, uniqueIngredientsList(r.getInputs()))`，`:1125`）。
        // **目的是让层数与 Python 侧的槽位数对得上**（spec 3.3 的 `P(R) ≤ L`）：
        // 直接遍历 getInputs() 拿到的是【声明顺序】且不去重（RecipeBuilder 只是 inputs.add(...)），
        // 同一原料声明两次会多算一层。
        // （槽位的**顺序**本身无所谓——可达性是二分图匹配，与排列无关。）
        add("inputs", JsonArray().apply {
            RecipeMap.uniqueIngredientsList(recipe.inputs).forEach { input ->
                add(JsonObject().apply {
                    // spec 7.3 的取值域是 item | ore | circuit。Python 侧 `_parse_input` 只做透传、
                    // 判据不读它（`criterion.kinds` 用的是 `representative`），但它是**报告用的种类标签**，
                    // 顺手写成"非电路一律 item"会让矿辞输入在报告里显示成精确栈。
                    addProperty("kind", when
                    {
                        RecipeAuditTokens.isCircuit(input) -> "circuit"
                        input.isOreDict                    -> "ore"
                        else                               -> "item"
                    })
                    addProperty("amount", input.amount)
                    addProperty("consumable", !input.isNonConsumable)
                    // **代表必须规范地选，不能取 `getInputStacks()[0]`**（spec 5.1 细则 3）：
                    // 它内部是 `OreDictionary.getOres(...)`，顺序来自某个以 Material/Item 为 key 的
                    // HashMap 的迭代序、**跨启动不稳定**。取 [0] 会让 representative 漂 →
                    // Python 侧按 representative 做的布局去重跟着漂 → 槽位集合变 →
                    // `Reach` 与冲突结论可能变。**必须与 stableKey 共用同一个函数，不许各写一份。**
                    val canonical = RecipeAuditTokens.canonicalRepresentativeToken(input)
                    val head = input.inputStacks.firstOrNull { stack ->
                        RecipeAuditTokens.slotTokens(stack).first() == canonical
                    }
                    // 展开为空（canonical == null）时写空串：它的候选集本来就是空的，
                    // 写什么都查不到 trie，Python 侧会把它归入"空展开输入"那一类（spec Step 5）。
                    addProperty("representative", canonical ?: "")
                    add("slotCandidates", JsonArray().apply {
                        head?.let { stack -> RecipeAuditTokens.slotTokens(stack).forEach { add(it) } }
                    })
                })
            }
        })

        add("fluidInputs", JsonArray().apply {
            recipe.fluidInputs.forEach { fluid ->
                val stack = fluid.inputFluidStack
                add(JsonObject().apply {
                    addProperty("token", RecipeAuditTokens.fluidToken(stack.fluid, stack.tag))
                    add("candidates", JsonArray().apply { add(RecipeAuditTokens.fluidToken(stack.fluid, stack.tag)) })
                    addProperty("amount", fluid.amount)
                    addProperty("consumable", !fluid.isNonConsumable)
                })
            }
        })

        add("outputs", JsonObject().apply {
            add("items", JsonArray().apply {
                recipe.outputs.forEach { stack ->
                    add(JsonObject().apply {
                        addProperty("token", RecipeAuditTokens.itemToken(stack, stack.metadata, stack.tagCompound, false))
                        addProperty("count", stack.count)
                    })
                }
            })
            add("fluids", JsonArray().apply {
                recipe.fluidOutputs.forEach { stack ->
                    add(JsonObject().apply {
                        addProperty("token", RecipeAuditTokens.fluidToken(stack.fluid, stack.tag))
                        addProperty("amount", stack.amount)
                    })
                }
            })
        })

        // 必须用【引用相等】。Recipe.equals 只比输入不比输出（`Recipe.java:351-356`），
        // 用 equals 会把"输入多重集相同、输出不同"的一对误判成同一条——而那正是
        // `getRecipeList()` 那处陷阱的形态。
        addProperty("inTree", inTree.any { it === recipe })
        // 字段保留是因为 Python 侧读它（`model._parse_recipe` 的 `inCategory`），删掉会让"幽灵"
        // 判据失去一半输入。取值**由调用方按实例身份实算后给出**（`===`，见 `inCategoryTable`），
        // 不再写死：写死 `true` 对 `rejected` 是**假值**（它们通常不在分类表里），写死 `false`
        // 则在"同实例先插入成功、后又被拒"时是**反向假值**——两种都会让拿它做 `rejected` 侧
        // 报表的消费者**静默说错**。
        addProperty("inCategory", inCategory)
    }

    /**
     * 从输入里认电路，取的是**配置号**（0..32），不是栈的 metadata。
     *
     * **不能取 `getInputStacks()[0].metadata`**：`IntCircuitIngredient.getInputStacks()` 返回的是
     * `getIntegratedCircuit(matchingConfigurations)`，而那个栈的**配置号存在 NBT 的 `Configuration`
     * 键里**（`IntCircuitIngredient.setCircuitConfiguration`），metadata 只是集成电路这个物品自身的
     * damage 值。取 metadata 会让**所有**电路的导出值都等于同一个数（实测全表 461），
     * 报告里于是每条都印成"电路461"——一个看起来很正常、其实完全没有区分度的数字。
     * 正确读法是 `IntCircuitIngredient.getCircuitConfiguration(stack)`。
     *
     * 用 `firstNotNullOfOrNull` 而不是 `map { }.first()`：后者在无电路时抛异常，而"无电路"是常态。
     */
    private fun circuitOf(recipe: Recipe): Int? = recipe.inputs
        .firstNotNullOfOrNull { input ->
            // 与 `RecipeAuditTokens.isCircuit` 同一个谓词（`input is IntCircuitIngredient`），
            // 这里写成 `is` 是为了拿到智能转换后的类型去调那个静态读法。
            if (input is IntCircuitIngredient)
                input.inputStacks.firstOrNull()?.let { IntCircuitIngredient.getCircuitConfiguration(it) }
            else null
        }

    /**
     * 走一遍 trie，把每条**根→叶**路径写成一条记录，**逐节点记下它属于哪个 map**。
     *
     * `Branch` / `Either` 的遍历 API 都是 public（`Branch.java` 的 `getNodes()` / `getSpecialNodes()`、
     * `Either.java` 的 `left()` / `right()`），只有 `RecipeMap.lookup` 是 private（`RecipeMap.java:121`），
     * 所以只需要 [AccessorRecipeMap] 一个 accessor。
     *
     * **每一层都要在两个 map 里各走一遍**：`determineRootNodes`（`RecipeMap.java:1064-1067`）是按
     * **每个 key 自身**的 `isSpecialIngredient()` 分流的，一条链可以层 0 在 `specialNodes`、层 1 在
     * `nodes`（`hasNBTMatcherInputs` 为真时确实会产生）。混用路径必须如实导出，否则 Python 侧要么
     * 校验失败整轮崩（`model._parse_trie_path` 的交叉校验）、要么静默建错 trie。
     *
     * 递归期间 `nodes` / `nodeMaps` 是**同长同序**的两个栈：`nodes[i]` 的 map 就是 `nodeMaps[i]`。
     * Python 侧 `_parse_trie_path` 会逐位校验这两列与 token 前缀是否自洽——不一致直接抛
     * `ValueError`（**载入期响亮失败**，而不是静默按其中一个走）。
     *
     * @param idOf 稳定 id 查找表。**必须走它而不是"在清单里找一遍"**：它按实例身份建表
     *             （`IdentityHashMap`），用 equals 找一个"输入相同、输出不同"的配方会找错人。
     *             `?: -1`（写进 JSON 的 `recipe: -1`）只是兜底：trie 里的配方必然在
     *             `getRecipesByCategory()` 里（`compileRecipe` 只在插入成功时才写分类表，
     *             `RecipeMap.java:333-340`），真出现 -1 就说明假设错了（典型是 `stableKey` 把两条
     *             配方合并成了一条——那时 `mergedByFingerprint` 也是非 0，两处信号互相印证）。
     */
    private fun walk(branch: Branch,
                     nodes: ArrayDeque<String>,
                     nodeMaps: ArrayDeque<String>,
                     sink: JsonArray,
                     idOf: Map<Recipe, Int>)
    {
        // `branch.nodes` / `branch.specialNodes` 会解析到 `getNodes()` / `getSpecialNodes()`——
        // 它们是 @NotNull 且**惰性建表**的（null 时 new 一个空的），所以这里可能给原本为 null 的
        // 分支建出空表。空表对 `isEmptyBranch()` / `getRecipes()` / 搜索都不产生行为差异，
        // 而且 `RecipeMap.removeAllRecipes`（`:373-374`）本来就会做同样的事。
        for ((mapName, children) in listOf("nodes" to branch.nodes, "specialNodes" to branch.specialNodes))
        {
            for ((key, either) in children)
            {
                val token = RecipeAuditTokens.keyToken(key)
                if (either.left().isPresent)
                {
                    // 叶子：这条路径到此为止，末端挂着配方。
                    val path = nodes.toMutableList().apply { add(token) }
                    val maps = nodeMaps.toMutableList().apply { add(mapName) }
                    sink.add(JsonObject().apply {
                        add("nodes", JsonArray().apply { path.forEach { add(it) } })
                        add("nodeMaps", JsonArray().apply { maps.forEach { add(it) } })
                        addProperty("recipe", idOf[either.left().get()] ?: -1)
                    })
                }
                else
                {
                    // 中间节点：继续往下走。**不要在 else 里也记一条"半路径"**——
                    // 那种记录在 Python 侧既不是有效路径（末端不是配方），又会让 pathCount 虚高。
                    nodes.addLast(token)
                    nodeMaps.addLast(mapName)
                    walk(either.right().get(), nodes, nodeMaps, sink, idOf)
                    nodes.removeLast()
                    nodeMaps.removeLast()
                }
            }
        }
    }

}
