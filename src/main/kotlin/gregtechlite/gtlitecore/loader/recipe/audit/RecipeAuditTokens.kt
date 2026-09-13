package gregtechlite.gtlitecore.loader.recipe.audit

import gregtech.api.recipes.Recipe
import gregtech.api.recipes.ingredients.GTRecipeInput
import gregtech.api.recipes.ingredients.IntCircuitIngredient
import gregtech.api.recipes.map.MapFluidIngredient
import gregtech.api.recipes.map.MapItemStackIngredient
import gregtech.api.recipes.map.MapItemStackNBTIngredient
import gregtech.api.recipes.map.MapOreDictIngredient
import gregtech.api.recipes.map.MapOreDictNBTIngredient
import gregtechlite.gtlitecore.mixins.gregtech.AccessorMapItemStackIngredient
import gregtechlite.gtlitecore.mixins.gregtech.AccessorMapOreDictIngredient
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagList
import net.minecraft.nbt.NBTTagString
import net.minecraftforge.fluids.Fluid
import net.minecraftforge.oredict.OreDictionary

/**
 * 把配方/trie key 渲染成稳定、无歧义的 token，供离线 Python 侧直接比较。
 *
 * token 语法（必须与 spec 逐字一致）：
 * ```
 * item:<registryName>:<meta>:<tag|none>
 * itemnbt:<registryName>:<meta>:<tag|none>
 * ore:<oreName>
 * orenbt:<oreName>:<tag|none>
 * fluid:<fluidName>:<tag|none>
 * ```
 * `<tag>` 一律用 [canonicalNbt]（按 key 排序的规范串），**不得用任何 hashCode**——
 * `NBTTagCompound.hashCode` 是单个 int（`id ^ tagMap.hashCode`），碰撞会让 Python 把
 * 两个不同输出判成相等，那是静默假阴性（而"输出是否相等"是判据的唯一依据）。
 * 同理 `Item` / `Material` 的 identity hash 跨 JVM 启动会变，任何 token 里都不能出现。
 *
 * ### 为什么 `<tag>` 不能用 `NBTTagCompound.toString()`
 *
 * 它是**非规范**序列化，跨运行会变，会让两次导出的 id / 输出比对整体错位：
 *
 * 1. `NBTTagCompound.tagMap` 是 `Maps.newHashMap()`（`NBTTagCompound.java:28`），而
 *    `toString()`（同文件 `:488-500`）按 `keySet()` 迭代 —— 内容相同、key 插入顺序不同的
 *    两个 compound 会渲染成不同的串（同桶碰撞时 HashMap 的链表顺序就是插入顺序）。
 * 2. 更糟：`toString()` 在 `LOGGER.isDebugEnabled()` 时**改成排序输出** —— 也就是说
 *    **同一份数据在开了 DEBUG 日志的运行里 token 会变**，token 稳定性取决于运行时的日志级别，
 *    这显然不是内容派生的量。
 *
 * [canonicalNbt] 就是把 DEBUG 分支给出的那个形式固化下来：对 key 排序后逐个渲染，
 * 递归处理嵌套的 `NBTTagCompound` / `NBTTagList`（`NBTTagList` 是有序的，顺序即语义，
 * 不能排序），标量用各 `NBTBase` 自身的 `toString()`（标量没有顺序问题）。
 *
 * 这些 token 是**跨语言契约**：[SPECIAL_PREFIXES] 与 `scripts/recipeaudit/model.py` 的
 * `_SPECIAL_PREFIXES` 是同一个常量的两份拷贝，见 [selfCheck]。
 */
internal object RecipeAuditTokens
{

    private const val NONE_TAG = "none"

    private const val ITEM_PREFIX = "item"
    private const val ITEM_NBT_PREFIX = "itemnbt"
    private const val ORE_PREFIX = "ore"
    private const val ORE_NBT_PREFIX = "orenbt"
    private const val FLUID_PREFIX = "fluid"

    /**
     * 输入段里"声明身份"那半段的标记前缀。**刻意不与上面任何 trie token 前缀相同**：
     * 候选集里的 token 永远不会以 `decl:` 开头，于是"声明身份"与"展开结果"在段内不会互相冒充。
     */
    private const val DECLARED_PREFIX = "decl"

    /** 输入展开为空（[canonicalCandidateTokens] 返回 `null`）时的占位。 */
    private const val EMPTY_EXPANSION = "empty"

    /**
     * [nbtMatcherToken] 在输入**没有** NBT matcher 时的占位。
     * 刻意与 [NONE_TAG] 分开：`none` 是 Python 逐字依赖的 `<tag>` 字面量，这里的取值只进指纹、
     * 不出现在任何 token 语法里，混用会让"改 `none`"的后果变得不可分辨。
     */
    private const val NO_NBT_MATCHER = "nomatch"

    /**
     * [stableClassName] 把 lambda 类名归一化成的后缀。
     * 例：`gregtech.api.recipes.ingredients.nbtmatch.NBTMatcher$$Lambda$2389/1033839220`
     * → `gregtech.api.recipes.ingredients.nbtmatch.NBTMatcher$$Lambda`。
     */
    private const val LAMBDA_CLASS_SUFFIX = "\$\$Lambda"

    /**
     * 属于 trie `specialNodes` 的 token 前缀（含末尾冒号）。
     *
     * 这是**跨语言重复的常量**：Python 侧逐字拷贝在 `scripts/recipeaudit/model.py` 的
     * `_SPECIAL_PREFIXES = ("itemnbt:", "orenbt:")`，由 `model._is_special_token` 使用，
     * 并在 `model._parse_trie_path` 里与导出器声明的 `nodeMaps` 做交叉校验——
     * 两边对不上，Python 会在**载入期**直接抛 `ValueError`，整轮审计一条都跑不了。
     * 改动这里必须同步改 Python 侧。见 [selfCheck]。
     */
    private val SPECIAL_PREFIXES = listOf("$ITEM_NBT_PREFIX:", "$ORE_NBT_PREFIX:")

    /**
     * NBT key 的"简单值"判据，与 `NBTTagCompound.SIMPLE_VALUE`（`[A-Za-z0-9._+-]+`）逐字相同。
     *
     * **必须声明在 `init` 块之前**：Kotlin 按源码顺序初始化属性与 init 块，而 `selfCheck` 会走到
     * [canonicalNbt] → [escapeNbtKey]。声明在 init 之后的话，自检时这个字段还是 null，
     * 直接 NPE（`ExceptionInInitializerError`）——本对象就会在首次使用时炸掉。
     */
    private val SIMPLE_NBT_KEY = Regex("[A-Za-z0-9._+-]+")

    /**
     * **跨语言字面量契约**：本文件渲染出的这些字面量被 Python 侧逐字解析，改动本文件里任何一处
     * 前缀常量或 [NONE_TAG] 都必须同步改 `scripts/recipeaudit/model.py`。
     *
     * 每一项三元组为 `(实际值, 硬编码字面量, 说明)`。**右值是字面量，不是本文件里某个常量的另一种
     * 写法** —— 这是本表存在的全部意义：上一版这里写的是
     * `check(itemToken(...).endsWith(":$NONE_TAG"))`，右边就是 `NONE_TAG` 本身，而 `itemToken`
     * 的尾部恒为 `":${canonicalNbt(tag)}"`、`canonicalNbt(null) == NONE_TAG` —— 那条断言**只可能因
     * 本函数自相矛盾而失败**，把 `NONE_TAG` 改成别的值它照样通过，而 Python 逐字依赖的正是那个
     * 字面量 `none`。本仓库已经五次栽在这类空转/恒真断言上，所以这里一律对着**字面量**断言。
     *
     * 左值尽量取**渲染函数的实际产出**（`itemToken` / `oreToken` / `canonicalNbt` 真调用），
     * 而不是常量本身；只有 fluid 例外（见下）。
     *
     * **必须声明在 `init` 块之前**：Kotlin 按源码顺序初始化属性，而 `selfCheck` 会读本表，
     * 声明在 init 之后的话自检时它还是 null（`ExceptionInInitializerError`）。
     */
    private val CROSS_LANGUAGE_LITERALS: List<Triple<String, String, String>> = listOf(
        Triple(
            itemToken(ItemStack.EMPTY, 0, null, nbtVariant = false).substringBefore(':'),
            "item", "`item:` —— 非 NBT 精确栈 token 前缀"
        ),
        Triple(
            itemToken(ItemStack.EMPTY, 0, null, nbtVariant = true).substringBefore(':'),
            "itemnbt", "`itemnbt:` —— NBT 精确栈 token 前缀（属 trie specialNodes）"
        ),
        Triple(
            oreToken(0, null, nbtVariant = false).substringBefore(':'),
            "ore", "`ore:` —— 非 NBT 矿辞 token 前缀"
        ),
        Triple(
            oreToken(0, null, nbtVariant = true).substringBefore(':'),
            "orenbt", "`orenbt:` —— NBT 矿辞 token 前缀（属 trie specialNodes）"
        ),
        Triple(
            FLUID_PREFIX,
            "fluid", "`fluid:` —— 流体 token 前缀（自检里无法安全构造 Fluid 实例，只能断言常量）"
        ),
        Triple(
            canonicalNbt(null),
            "none", "`none` —— `<tag>` 为空时的字面量，Python 逐字依赖"
        ),
        Triple(
            SPECIAL_PREFIXES[0],
            "itemnbt:", "Python `model._SPECIAL_PREFIXES[0]`（模型侧逐字拷贝）"
        ),
        Triple(
            SPECIAL_PREFIXES[1],
            "orenbt:", "Python `model._SPECIAL_PREFIXES[1]`（模型侧逐字拷贝）"
        )
    )

    /**
     * 初始化自检：把"跨语言常量对不上"从"运行时在 Python 侧崩"提前到"首次使用本对象时立刻发现"。
     *
     * 自检失败一律抛异常。本对象是开发工具的一部分，**大声失败优于静默**：
     * 渲染出一个 Python 侧不认识的前缀，只会让 Python 在载入期报错或者（更糟）按错误的 map 查 trie。
     */
    init
    {
        selfCheck()
    }

    fun itemToken(stack: ItemStack, meta: Int, tag: NBTTagCompound?, nbtVariant: Boolean): String
    {
        val prefix = if (nbtVariant) ITEM_NBT_PREFIX else ITEM_PREFIX
        // stack.item 可以是 null（ItemStack.EMPTY 就是 new ItemStack((Item) null)），
        // 这里退化成 "unknown" 而不是 NPE——token 渲染器必须是**全函数**，
        // 否则一条畸形配方就能让整次导出崩掉。
        val name = stack.item?.registryName?.toString() ?: "unknown"
        return "$prefix:$name:$meta:${canonicalNbt(tag)}"
    }

    fun oreToken(oreId: Int, tag: NBTTagCompound?, nbtVariant: Boolean): String
    {
        val prefix = if (nbtVariant) ORE_NBT_PREFIX else ORE_PREFIX
        val name = OreDictionary.getOreName(oreId) // 未知 id 返回 "Unknown"，不抛异常
        return "$prefix:$name:${canonicalNbt(tag)}"
    }

    fun fluidToken(fluid: Fluid, tag: NBTTagCompound?): String
        = "$FLUID_PREFIX:${fluid.name}:${canonicalNbt(tag)}"

    /**
     * 规范 NBT 渲染：把 `NBTTagCompound.toString()` 的 **DEBUG 分支**（按 key 排序）固化下来。
     *
     * `tag` 为 `null` 时返回 [NONE_TAG]（即 `none`）—— 这是 Python 侧逐字依赖的既有约定，
     * 三处调用点因此不再各写一遍 `?: NONE_TAG`。
     *
     * **不能用 `NBTTagCompound.toString()`**（原因见类 KDoc 两条）：它按 `tagMap.keySet()` 迭代
     * （HashMap，同桶碰撞时顺序即插入顺序），且在 DEBUG 日志开启时切换成排序输出 ——
     * 于是"同一份数据"在不同运行里会渲染成不同串，跨次导出的 id 与输出比对整体错位。
     * **更不能用 hashCode**：那是单个 int，碰撞会让 Python 把两个不同输出判成相等，
     * 而"输出是否相等"是判据的唯一依据，这是最隐蔽的假阴性。
     *
     * 渲染规则（与 `toString()` 的 DEBUG 分支同形）：
     * - `NBTTagCompound` → `{k1:v1,k2:v2}`，key 用 [escapeNbtKey] 转义后按字典序排列；
     * - `NBTTagList` → `[v1,v2]`，**顺序原样保留**（列表有序，顺序即语义）；
     * - 其余（标量：byte/short/int/long/float/double/string/byte[]/int[]/long[]）直接用
     *   `NBTBase.toString()` —— 标量没有顺序问题，且它们的形式已是内容派生的。
     */
    private fun canonicalNbt(tag: NBTTagCompound?): String
        = if (tag == null) NONE_TAG else buildString { appendCanonicalNbt(this, tag) }

    private fun appendCanonicalNbt(builder: StringBuilder, tag: NBTBase)
    {
        when (tag)
        {
            is NBTTagCompound ->
            {
                builder.append('{')
                var first = true
                // 排序是关键：`tagMap` 是 HashMap，直接迭代 keySet 会受插入顺序与桶碰撞影响。
                // （MCP stable_39 下这个 getter 名为 `getKeySet()`，Kotlin 合成为属性 `keySet`。）
                for (key in tag.keySet.sorted())
                {
                    if (!first) builder.append(',')
                    first = false
                    builder.append(escapeNbtKey(key)).append(':')
                    appendCanonicalNbt(builder, tag.getTag(key))
                }
                builder.append('}')
            }

            is NBTTagList ->
            {
                builder.append('[')
                for (index in 0 until tag.tagCount())
                {
                    if (index != 0) builder.append(',')
                    appendCanonicalNbt(builder, tag.get(index))
                }
                builder.append(']')
            }

            else -> builder.append(tag.toString())
        }
    }

    /**
     * key 转义，复刻 `NBTTagCompound.handleEscape`：只有匹配 `[A-Za-z0-9._+-]+` 的 key 才原样输出，
     * 其余走 [NBTTagString.quoteAndEscape]。不转义不难看，但会让 `{a:b:c}` 这类 key 产生歧义，
     * 破坏 token 的逐字可比性。
     */
    private fun escapeNbtKey(key: String): String
        = if (SIMPLE_NBT_KEY.matches(key)) key else NBTTagString.quoteAndEscape(key)

    /**
     * trie key → token。NBT 变体的判定**直接用 `AbstractMapIngredient.isSpecialIngredient()`**，
     * 不用类名字符串：这个谓词正是 `RecipeMap.determineRootNodes` 把 key 分进 `nodes` 还是
     * `specialNodes` 的依据，所以前缀与导出器记录的 `nodeMaps` 必然一致，Python 的交叉校验
     * 也就必然通过。类名匹配（如 `simpleName.contains("NBT")`）只是当前实现下的巧合，
     * 加一个名字里不带 NBT 的子类就会静默错位。
     */
    fun keyToken(key: Any): String = when (key)
    {
        is MapItemStackIngredient ->
        {
            val access = key as AccessorMapItemStackIngredient
            itemToken(access.auditStack(), access.auditMeta(), access.auditTag(),
                      nbtVariant = key.isSpecialIngredient())
        }

        is MapOreDictIngredient ->
        {
            val access = key as AccessorMapOreDictIngredient
            // 这里传 null 是**有依据的**而不是偷懒：trie 里的 orenbt key 全部来自
            // `RecipeMap.buildFromRecipeItems`（`new MapOreDictNBTIngredient(ore, matcher, condition)`），
            // 走的是三参构造，字段 `nbtTagCompound` 恒为 null；区分它们的 matcher/condition
            // 不是内容派生的字符串，也无法渲染成稳定 token。带 tag 的两参构造只出现在
            // `buildFromItemStacks` 的**查询侧**，而查询侧的 key 不走本函数。
            oreToken(access.auditOre(), null, nbtVariant = key.isSpecialIngredient())
        }

        is MapFluidIngredient -> fluidToken(key.fluid, key.tag)

        else -> throw IllegalArgumentException("unknown trie key type: ${key.javaClass.name}")
    }

    /**
     * 槽位候选 token 列表：**第一项恒为精确栈的 `item:` token**，其余候选（各矿辞 id 的 `ore:` /
     * `orenbt:`，以及 NBT 变体 `itemnbt:`）按 **token 字典序**排在它后面。
     *
     * ### 为什么第一项必须是精确栈 token
     *
     * 导出器把 `first()` 当作该槽位的 representative（spec 9.5），representative 又参与 Python 侧
     * `criterion.slot_candidates_for` 的槽位去重，所以它必须是**内容派生、跨运行不变**的逐字记号。
     * 精确栈的 `item:` / `itemnbt:` 只由 registryName / meta / 规范 NBT 决定，满足这一点；
     * 矿辞候选的**排列**则不然（见下）。因此 [itemToken] 的精确栈判定恒定占首位，**不参与排序**。
     *
     * ### 为什么其余候选择排序：为了让导出物跨运行可 diff，而不是为了对齐 Java
     *
     * 本函数**不再**保证与 `RecipeMap.buildFromItemStacks`（查询侧）的候选次序逐条对应——
     * 那个次序本身就**不稳定**，不能当契约：
     *
     * 1. `OreDictionary.getOreIDs(stack)` 的顺序 = `HashSet<Integer>` 的迭代顺序
     *    （`OreDictionary.java:502-531`，`set.toArray(...)`），只由**矿辞数字 id 的数值**决定；
     * 2. 矿辞 id 在首次注册时按 `idToName.size()` 自增发放 —— 谁先注册谁拿小 id；
     * 3. 首次注册顺序来自 `MetaBlocks.registerOreDict()` 遍历 `COMPRESSED`
     *    （`MetaBlocks.java:679-695`），而它是 `Object2ObjectOpenHashMap<Material, BlockCompressed>`：
     *    FastUtil 开放寻址表按 **key 的 hash** 定序；
     * 4. `Material` 没有覆写 `hashCode()`（`Material.java:69`），于是退化成 identity hash，
     *    **每次 JVM 启动都不同**。
     *
     * 因果链：`COMPRESSED` 迭代顺序逐次启动不同 → `registerOre("block…")` 的首次注册顺序不同 →
     * 数字 ore id 置换 → `getOreIDs()` 顺序置换 → 本函数的候选排列置换 → [stableKey]
     * （spec §7.2 的稳定 id）整串不同。即：**同一条配方在两次运行里得到不同的指纹**，
     * 跨运行/跨导出的 id 与输出比对整体错位，而"输出是否相等"是判据的唯一依据。
     *
     * 排序后本函数的产出完全由**内容**决定（token 只含 registryName / meta / 规范化 NBT），
     * 同一份数据两次导出逐字节一致，可以直接 diff。
     *
     * ### 顺序不承载语义：Python 侧把候选当集合用
     *
     * - `reach`（`reach.py:117`）对每个候选**逐个**做 trie 查找并取并集——与排列无关；
     * - `criterion.slot_candidates_for`（`criterion.py:200-224`）按 `representative` 去重，
     *   候选列表本身只在查 trie 时被遍历。
     *
     * 所以重排不改变槽位集合、不改变 `Reach`、不改变冲突结论；它只影响跨运行/跨导出的可比性。
     * 同理，排序**不违反** `first() == representative` 的约定：第一项仍是精确栈的 `item:` token。
     *
     * 关于 map 级的 `hasOreDictedInputs` / `hasNBTMatcherInputs` 开关：本函数不加判断地给出
     * 全量候选。这是安全的超集——那两个开关只会 0→1 且不再复位，而 trie 里存在 `ore:` /
     * `orenbt:` / `itemnbt:` 节点本身就蕴含当时开关已开，故 Java 对同样的 stack 也会给出这些候选。
     * 反过来若漏给候选，Python 的可达性就会静默少报。
     */
    fun slotTokens(stack: ItemStack): List<String>
    {
        val meta = stack.metadata
        val tag = stack.tagCompound
        val candidates = mutableListOf<String>()
        for (oreId in OreDictionary.getOreIDs(stack))
        {
            candidates.add(oreToken(oreId, null, nbtVariant = false))
            candidates.add(oreToken(oreId, tag, nbtVariant = true))
        }
        candidates.add(itemToken(stack, meta, tag, nbtVariant = true))
        // 精确栈 token 钉在首位（representative 依赖它）；其余候选按 token 字典序，
        // 使产出只由内容决定。排序是对**渲染后的 token** 做的，所以矿辞的先后等价于
        // 按 ore 名字典序，与不稳定的数字 ore id 无关。
        return listOf(itemToken(stack, meta, tag, nbtVariant = false)) + candidates.sorted()
    }

    /**
     * 槽位的**规范代表** token：把 `input.getInputStacks()` 的每一项映射成它的**精确** token
     * （即 `slotTokens(stack).first()`，不带 NBT 变体的那一个），取其中**字典序最小**者；
     * 展开为空则返回 `null`。
     *
     * ### 为什么必须"规范地选"，而不能取 `[0]`
     *
     * `getInputStacks()` 的内部是 `OreDictionary.getOres(name)`
     * （`GTRecipeOreInput.getInputStacks`，`GregTech/src/main/java/gregtech/api/recipes/ingredients/GTRecipeOreInput.java:102-113`），
     * 其元素顺序**不跨启动稳定**：矿辞注册顺序的源头之一 `MetaBlocks.registerOreDict()` 遍历的是
     * `MetaBlocks.COMPRESSED` —— 一个以 `Material` 为 key 的 FastUtil map，而 `Material` 没有覆写
     * `hashCode()`（退化成 identity hash），逐次 JVM 启动都不同。实测：`craftingLensGlass` 的 16 个
     * 候选来自 `MetaItems.GLASS_LENSES` 这个 `HashMap`，第 0 个在两次运行里分别是
     * `meta_item_1:825`(Lime) 与 `meta_item_1:833`(Red)。
     *
     * 取 `[0]` 会让三样东西一起漂，而它们都必须稳定：
     *
     * 1. [stableKey]（spec §7.2 的稳定 id）—— 指纹漂 = 两次导出整体错位；
     * 2. 导出侧的 `representative`（spec 5.1）—— 它是 Python 侧 `criterion.slot_candidates_for`
     *    的槽位去重键，一漂就会改变 Python 手里的**槽位集合**，进而可能改变 `Reach` 与**冲突结论**；
     * 3. `kinds`（覆盖剪枝）—— 同样由代表派生。
     *
     * 因此栈的选取**必须**被 [stableKey] 与导出侧（Task 8）**共用同一份实现**，不允许各写一遍：
     * 各写一遍就意味着两处可以独立漂移，而上面第 2 条的后果是静默的错误结论。本文件里那个共用点
     * 就是 [canonicalStack]，本函数与 [stableKey] 用的 [canonicalCandidateTokens] 都从它出发。
     *
     * 取字典序最小是**内容派生**的：候选集合由内容决定（见 [slotTokens] 对 `getOreIDs` 不稳定性的
     * 论证），而 `min` 与候选排列无关，所以代表跨运行不变。
     *
     * **注意**：本函数是给导出侧（Task 8）填 `representative` / `kinds` 用的；[stableKey] 的输入段
     * **不再**用它，改用 [inputSegment]，即「**声明身份** + [canonicalCandidateTokens] 的完整候选集」，
     * 原因见那个函数的 KDoc。
     */
    fun canonicalRepresentativeToken(input: GTRecipeInput): String?
        = canonicalStack(input)?.let { slotTokens(it).first() }

    /**
     * 槽位的**完整候选指纹**：取该槽位的规范栈（见 [canonicalStack]），返回它的**全部**候选
     * token 排序后的列表（`slotTokens(stack).sorted()`）；展开为空则返回 `null`。
     *
     * 这是 [stableKey] 输入段的输入，与 [canonicalRepresentativeToken] **共用同一个栈选取**，
     * 只是把"只用那个栈的最小 token"扩成"用那个栈的全部候选"。
     *
     * ### 为什么指纹必须用**完整候选集**，不能只用代表 token
     *
     * [canonicalRepresentativeToken] 取的是展开里精确 token 的字典序最小者。两条配方的输入若是
     * **不同的矿辞名**、但展开**共享同一个最小元素**（例如两个矿辞都含同一枚铁锭），则它们的代表
     * token 完全相同。若 [stableKey] 只吃代表 token，这两条配方就会得到**同一个 stableKey**，
     * 而导出器（Task 8）用 `distinctBy { stableKey(it) }` 去重 —— 于是**其中一条被静默丢掉**，
     * 导出里没有它的 id，Python 侧完全不知道少了一条，判据因此可能给出错误的"无冲突"结论。
     *
     * 这类静默错误在本仓库已经出现过四次（`Recipe.hashCode`、矿辞候选顺序、头栈选法、输出顺序），
     * 根因同是 `Material` / `Item` 未覆写 `hashCode` 导致的"身份不是内容"。所以这里**刻意加细**：
     * 指纹从"一个代表 token"扩成"整条候选串"。
     *
     * 加细不会带来假阴性：候选集是由**内容**（展开出来的栈及其矿辞 id）派生的，不是身份。
     *
     * ### 候选集只是**必要的一半**：它换不来比代表 token 更细的区分
     *
     * 候选集来自**规范栈自身**（`slotTokens(stack)` 只吃 `stack`），而规范栈的身份
     * `(item, meta, tag)` 恰好就是 [canonicalRepresentativeToken] 编码的那三样东西。于是
     * `wide` 是 `narrow` 的**纯函数**、两者诱导**同一个划分**：
     *
     * ```
     * narrow(input) = min{ slotTokens(stack).first() }     // 代表 token
     * wide(input)   = slotTokens(stackOf(narrow)).sorted() // 本函数
     * wide.first() == narrow   // `item:` 恒为候选集里的最小前缀，见 [slotTokens]
     * ```
     *
     * 119 张表（108081 个槽位）上的实测：`narrowDistinctSigs == wideDistinctSigs == 11251`、
     * `narrowColliding == wideColliding == 4938`、`wideNotDerivedFromRep == 0`。
     * **用规范栈的候选集，在数学上不可能比用代表 token 更细；加细的区分度实测为 0。**
     *
     * 真正会撞的是这类：`GTRecipeItemInput(精确栈 meta_dust:285)` 与一条
     * `GTRecipeOreInput(ore="dustMolybdenumDisilicide"，展开恰为 [meta_dust:285])` ——
     * **声明形式不同、内容不同**，却渲染出同一串候选。要分开它们，指纹必须带上
     * **输入自己声明的身份**（`input.isOreDict` / `input.oreDict` 的矿辞名 / 具体实现类），
     * 而不是规范栈的候选集。那件事由 [declaredIdentityToken] 与 [inputSegment] 负责；
     * 本函数保持"候选集由内容派生且排序"这一职责不变。
     *
     * ### 为什么必须排序
     *
     * 顺序**不是内容**：`getInputStacks()` 的顺序由矿辞注册顺序决定，而那条链上含 `Material`
     * 的 identity hash（见 [slotTokens] 的 KDoc），逐次 JVM 启动都不同。排序后同一份数据两次导出
     * 逐字节一致；不排序则指纹会整串漂移，跨次比对全是噪声。
     *
     * Python 侧同样把候选当集合用（`reach.py` 逐个查 trie 取并集、`criterion.slot_candidates_for`
     * 按 `representative` 去重），所以排序不改变任何结论。
     */
    fun canonicalCandidateTokens(input: GTRecipeInput): List<String>?
        = canonicalStack(input)?.let { slotTokens(it).sorted() }

    /**
     * 输入**声明的身份**：这条输入是**以什么形式被声明**进配方的，而不是它展开成了什么。
     *
     * - 矿辞输入（`isOreDict == true`，即 `GTRecipeOreInput`）→ `decl:ore:<矿辞名>`；
     * - 其余输入 → `decl:stack:<实现类名>`（`input.javaClass.name`）。
     *
     * ### 为什么必须带**矿辞名**，绝不能用 `input.oreDict` 这个 int id
     *
     * 数字 ore id 由 `OreDictionary` 按**首次注册顺序**自增发放（`idToName.size()`），而首次注册
     * 顺序的源头含 `Material` 的 identity hash（完整因果链见 [slotTokens] 的 KDoc），**逐次 JVM
     * 启动都不同**。实测：同一批 12839 个矿辞名里，**1600 个**在两次启动之间拿到了不同的 id。
     * 把 id 写进指纹，等于把 [stableKey] 重新变成一个逐次启动漂移的量 —— 那正是本文件前面
     * 反复修掉的病（跨次导出的 id 与输出比对整体错位）。**名字是内容，id 是注册顺序的产物。**
     *
     * ### 为什么非矿辞输入要带**具体实现类**，而不是只带一个"非矿辞"标记
     *
     * `GTRecipeItemInput`、`IntCircuitIngredient`、`FluidCellInput`（`GTRecipeItemInput` 的子类）
     * 都 `isOreDict == false`，且展开可能恰好相同。但**声明形式不同 ⇒ trie 里是不同的 key 类型、
     * 不同的节点前缀，两条都能注册成功**。指纹若把它们并成一个，[inputSegment] 的 KDoc 里描述的
     * 静默合并路径就会重新打开。
     *
     * 类名是**运行期常量**（不含任何 identity hash / 对象地址），跨启动逐字不变，所以带上它
     * 不会引入不稳定性。**这里不需要 [stableClassName] 归一化**：`GTRecipeInput` 是抽象类，
     * lambda 无法实现它，所以实现类名一定是具名类或匿名类（`Foo$1`），两者都跨启动稳定。
     * 与之相对，[nbtMatcherToken] 那边是**函数式接口**、实现全是 lambda，就**必须**归一化
     * （原因见那个函数的 KDoc）。
     */
    fun declaredIdentityToken(input: GTRecipeInput): String
        = if (input.isOreDict) "$DECLARED_PREFIX:$ORE_PREFIX:${OreDictionary.getOreName(input.oreDict)}"
          else "$DECLARED_PREFIX:$ITEM_PREFIX:${input.javaClass.name}"

    /**
     * 输入**声明的全部栈**的精确 token，排序后拼接 —— 即 `input.inputStacks` 里每一个栈的
     * `slotTokens(stack).first()`，按 token 字典序排列。
     *
     * ### 为什么指纹需要它：同一声明类、同一声明身份，差别只在**声明了几个栈**
     *
     * `GTRecipeItemInput` 内部持有 `ItemStack[] inputStacks`（`GTRecipeItemInput.java:19`），
     * 构造时把 meta 为 `GTValues.W` 的通配栈按 `getSubItems` 展开成**一串**子项（`:44-58`）。
     * 于是 `GTRecipeItemInput(通配 meta)` 与 `GTRecipeItemInput(单个 concrete:0)` 是**两条不同的
     * 声明**：trie 侧 `RecipeMap.buildFromRecipeItems`（`RecipeMap.java:1159-1178`）走
     * `MapItemStackIngredient.from(r)`，其中 `from` **逐个声明栈**产出一个 key
     * （`MapItemStackIngredient.java:30-36`）——前者在 trie 里是 16 条 key 的路径，后者只有 1 条。
     * 两者都能注册、都留在 trie 里，所以指纹相同就会被导出器的 `distinctBy { stableKey(it) }`
     * 静默合并（因果链见 [inputSegment] 的 KDoc）。
     *
     * ### 为什么 [canonicalCandidateTokens] 够不到它
     *
     * 那个函数只从 [canonicalStack] 选出的**唯一一个**栈渲染候选（两侧都可能选到 `concrete:0`），
     * 声明里多出来的 `concrete:1..15` 落在规范栈之外、指纹里没有任何字段承载。实测（本文件
     * 修此之前）：11 个残余碰撞族**全部**是这个形态，逐族都是"同一个 `GTRecipeItemInput`、
     * 同一份声明身份，只有 `inputStacks` 的项数不同"（`coal:0` vs `[coal:0,coal:1]`、
     * `concrete:0` vs `[concrete:0..15]`、`wool:0` vs `[wool:0..15]` 等）。
     *
     * ### 为什么排序、为什么不是身份
     *
     * 排序理由同 [canonicalCandidateTokens]：`GTRecipeOreInput.getInputStacks()` 的顺序来自
     * 矿辞注册顺序（源头含 `Material` 的 identity hash），逐次 JVM 启动都不同；排序后同一份数据
     * 两次导出逐字节一致。而栈的**存在与否**是内容：它由构造参数（以及通配 meta 的
     * `getSubItems` 展开结果）决定，不是注册顺序的产物。
     */
    fun declaredStackTokens(input: GTRecipeInput): List<String>
        = input.inputStacks?.map { slotTokens(it).first() }?.sorted() ?: emptyList()

    /**
     * 输入的 **NBT matcher** 身份：没有 matcher 时 [NO_NBT_MATCHER]，否则是 matcher 的**具体类名**。
     *
     * ### 为什么必须在指纹里：它决定 key 进 `nodes` 还是 `specialNodes`
     *
     * `GTRecipeInput.hasNBTMatchingCondition()` 是个布尔（`GTRecipeInput.java:135-137`，
     * 即 `nbtMatcher != null`），而 `RecipeMap.buildFromRecipeItems`（`RecipeMap.java:1140-1180`）
     * 正是按它分派：
     *
     * - 带 matcher → `MapItemStackNBTIngredient` / `MapOreDictNBTIngredient`（走 `specialNodes`，
     *   前缀 `itemnbt:` / `orenbt:`）；
     * - 不带 → `MapItemStackIngredient` / `MapOreDictIngredient`（走 `nodes`）。
     *
     * **这正是本文件整套前缀模型在编码的那条区分**，所以它必须进指纹：两条输入声明形式相同、
     * 展开相同、只差 matcher 时，它们在 trie 里是**两条不同的路径、两条都能注册**，指纹漏掉它就
     * 会走 [inputSegment] 描述的静默合并路径。实测（加上本字段之前）：放宽到"NBT matcher 也算
     * 内容"的口径下，残余碰撞族从 11 涨到 **208**，多出的 197 族**全部**只由 matcher/condition
     * 区分 —— 这批就是本字段要关掉的对象。
     *
     * ### 为什么取**类名**而不是只取布尔
     *
     * 类名能分开"同一布尔、不同 matcher 实现"的输入，而布尔只有两位信息量；且类名同时**编码**了
     * 那个布尔（[NO_NBT_MATCHER] ⇔ 布尔为 false），所以不必再单列一位。
     *
     * ### 但类名**必须**先归一化：`NBTMatcher` 的实现是 lambda，其类名**不是**稳定常量
     *
     * 这一点与 [declaredIdentityToken] 里的实现类名**不同**，不能照搬：
     * `GTRecipeInput` 是**抽象类**、不可能被 lambda 实现，所以那里的类名一定是稳定的具名类
     * （匿名子类的 `Foo$1` 也跨启动稳定）。而 `NBTMatcher` 是**函数式接口**，GTCEu 的
     * `NBTMatcher.ANY` / `LESS_THAN` / `GREATER_THAN` …（`NBTMatcher.java:29+`）**全部是 lambda**，
     * 于是 `javaClass.name` 形如
     * `gregtech.api.recipes.ingredients.nbtmatch.NBTMatcher$$Lambda$2389/1033839220`
     * —— `$$Lambda$<序号>/<hash>` 两段都**逐次 JVM 启动不同**（序号取决于 lambda 的引导顺序、
     * 后半段来自 identity hash）。直接写进指纹就等于把 [stableKey] 重新变成漂移量，
     * 正是本文件反复修掉的病。本文件实测确认了这一点：未归一化时探针报出
     * `MATCHERS|...NBTMatcher$$Lambda$2389/1033839220=657`，两次运行的 `keys.txt` 逐字节不同。
     *
     * 所以这里走 [stableClassName] 把 lambda 尾部归一化掉。
     *
     * ### 归一化之后仍然表达不了的：**已知残余，而不是"不存在差异"**
     *
     * 1. **同一宿主类里的不同 lambda**：GTCEu 的 matcher 全是 `NBTMatcher` 里的 lambda
     *    （`NBTMatcher.java:29+` 的 `ANY` / `LESS_THAN` / `GREATER_THAN` …），归一化后都渲染成
     *    `...NBTMatcher$$Lambda`，彼此不可分，本字段因此**关不掉**这一族。
     *
     *    **trie 层分得开它们** —— 不要拿"渲染分不开"去推"trie 也没分开"，那是偷换概念。
     *    `MapItemStackNBTIngredient.equals` 走 `GTRecipeInput.equalIgnoreAmount`，而
     *    `equalIgnoreAmount` 把 `Objects.equals(this.nbtMatcher, other.nbtMatcher)` 与
     *    `Objects.equals(this.nbtCondition, other.nbtCondition)` 当作 key 的一部分
     *    （`GTRecipeItemInput.java:213-228`、`GTRecipeOreInput.java:159-169`）。matcher 是 lambda、
     *    没有覆写 `equals`，于是**身份即相等性**：`ANY` 与 `NOT_PRESENT_OR_HAS_KEY` 是**两条不同的
     *    key、两条都能注册**，而它们渲染出的 token 一字不差。所以这一族是**真实的可见性差异**，
     *    是本指纹的**已知残余**。
     *
     *    收不掉的原因不是"不存在差异"，而只是**拿不到稳定量**写进指纹：`NBTMatcher` 的实现全是
     *    lambda（无 `Serializable`，`toString()` / 类名都含 identity hash，见上面 §归一化）。
     *
     *    **对导出介质的后果**：两条 key 渲染出的 `nodes` 串一字不差，导出照实写出两条
     *    完全相同的路径；Python 侧 `reach._insert` 的 `setdefault` 会**静默丢掉后来者**，
     *    于是那条配方 `in_tree=true` 却在 Python 侧不可达。载入期已加 `_check_unique_paths`
     *    把它变成大声失败（`load_export`），**不要**把那条校验当成可以省掉的防御。
     * 2. **`NBTCondition` 的比较数据**：它带的是 `tagType` / `nbtKey` / `value` 这些**内容数据**，
     *    而 GT 自己就拿 `Objects.equals(nbtCondition)` 当 key 的一部分
     *    （`NBTCondition.java:22-24,37-41` —— 这三个字段就是它 `hashCode` / `equals` 的全部依据）。
     *    所以"它不是内容派生的字符串"这个说法**站不住**：它是内容，只是本函数没有带、也拿不到
     *    跨启动稳定的渲染形式。与上一条同理，这一族只能**接受为已知残余**。
     */
    fun nbtMatcherToken(input: GTRecipeInput): String
        = input.nbtMatcher?.javaClass?.name?.let(::stableClassName) ?: NO_NBT_MATCHER

    /**
     * 把**含运行期身份**的类名归一化成跨启动稳定的形式。
     *
     * lambda 类名 `Host$$Lambda$<序号>/<hash>` 里的两段都逐次启动不同（见 [nbtMatcherToken]），
     * 归一化成 `Host$$Lambda`。`/` 在合法类名里不可能出现（二进制名用 `.`），所以按它先切一刀是安全的；
     * 具名类与匿名类（`Foo$1`）**原样返回**，不受影响。
     */
    private fun stableClassName(name: String): String
    {
        var stable = name
        val slash = stable.indexOf('/')
        if (slash >= 0) stable = stable.substring(0, slash)
        val lambda = stable.indexOf("\$\$Lambda\$")
        if (lambda >= 0) stable = stable.substring(0, lambda) + LAMBDA_CLASS_SUFFIX
        return stable
    }

    /**
     * [stableKey] **输入段的唯一渲染点**：
     * `<声明身份>:<NBT matcher>:<完整候选集|empty>:<声明栈集合|empty>`，**不含**数量
     * （数量由 [stableKey] 拼在段首）。
     *
     * 四段的分工（段的字面形式见上面的代码块）、以及四段都**必须**在指纹里的理由：
     *
     * - **声明身份**（[declaredIdentityToken]）——以什么形式声明：矿辞名，或输入的
     *   具体实现类。**没有它，指纹就区分不了声明形式**：`GTRecipeItemInput(精确栈 S)` 与
     *   「展开恰为 `[S]`」的 `GTRecipeOreInput` 会渲染成同一个串（实测 4938 个槽位族）。
     * - **NBT matcher**（[nbtMatcherToken]）——是否带 matcher（带哪个实现）：它决定 key
     *   进 `nodes` 还是 `specialNodes`，见 [nbtMatcherToken] 的 KDoc。**没有它，指纹就区分不了
     *   "同一个类的两条输入，一条走 `MapItemStackIngredient`、一条走 `MapItemStackNBTIngredient`"**，
     *   实测 197 族。
     * - **完整候选集**（[canonicalCandidateTokens]，按 token 字典序排序）——规范栈**展开成了什么**。
     * - **声明栈集合**（[declaredStackTokens]，按 token 字典序排序）——这条输入**声明了哪些栈**。
     *   与上一段是两件事：`GTRecipeItemInput` 可以声明一串栈（通配 meta 展开），trie 里相应的
     *   就是同样多条 key 路径。**没有它，指纹就区分不了"声明 `[concrete:0..15]`"与"声明
     *   `[concrete:0]`"**（两侧的规范栈都是 `concrete:0`、候选集也相同），实测 11 族。
     *
     * 四段都**不能省**：后两段是**不同的两件事**（一个答"展开成了什么"、一个答"声明了哪些栈"），
     * 只取前者会让上面那 11 族撞在一起（见 [declaredStackTokens] 的 KDoc），只取后者则会丢掉
     * 矿辞展开出来的东西。去掉任一段，对应的那一族都会重新被导出器的 `distinctBy { stableKey(it) }`
     * 静默合并。
     *
     * ### 为什么声明形式必须进指纹：它会让一整条配方**对分析不可见**
     *
     * 声明形式不同的两条输入是 trie 里**两条不同的路径**（`MapItemStackIngredient` 的 `item:` /
     * `itemnbt:` vs `MapOreDictIngredient` 的 `ore:` / `orenbt:`），两条都能注册成功、都留在 trie 里。
     * 指纹若相同，导出器（Task 8）的
     * `distinctBy { RecipeAuditTokens.stableKey(it) }` 会**静默合并**它们，其中一条从 `recipes`
     * 列表里消失、拿不到 id。**但它仍然在 trie 里**：`walk` 用
     * `idOf[either.left().get()] ?: -1` 渲染路径，于是它的路径变成 `recipe: -1`；而 Python 侧
     * `Export.recipe_by_id()` 只含**有 id** 的配方，`reach` / `criterion` 都按 id 取配方
     * ——这条配方既当不了源、也当不了目标，**对分析完全不可见**。判据于是在"少了一条配方"的
     * 输入上给出"无冲突"：**静默假阴性**，正是本仓库已出现四次的那一类错误
     * （`Recipe.hashCode`、矿辞候选顺序、头栈选法、输出顺序），根因同是 `Material` / `Item`
     * 未覆写 `hashCode` 造成的"身份不是内容"。
     *
     * **为什么用矿辞名而不是 ore id**：见 [declaredIdentityToken] —— ore id 按注册顺序发放，
     * 实测 12839 个名字里 1600 个跨启动 id 不同，写 id 就等于把指纹重新变成漂移量。
     *
     * 展开为空（[canonicalCandidateTokens] 返回 `null`）时渲染成 [EMPTY_EXPANSION]，但**声明身份
     * 与声明栈集合仍然在**：两条"展开为空"的输入（实测 2 个槽位）因此不再一律渲染成同一个 `empty`。
     */
    fun inputSegment(input: GTRecipeInput): String
        = buildString {
            append(declaredIdentityToken(input)).append(':')
            append(nbtMatcherToken(input)).append(':')
            append(canonicalCandidateTokens(input)?.joinToString("|") ?: EMPTY_EXPANSION).append(':')
            append(declaredStackTokens(input).joinToString("|").ifEmpty { EMPTY_EXPANSION })
        }

    /**
     * 槽位的**规范栈**：把 `input.getInputStacks()` 的每一项映射成精确 token
     * （`slotTokens(stack).first()`，见 [slotTokens] 对"第一项恒为精确 token"的保证），
     * 取其中**字典序最小**的那一项对应的栈；展开为空则返回 `null`。
     *
     * 取最小是**内容派生**的：候选集合由内容决定，而 `min` 与候选排列无关，所以选出的栈跨运行不变。
     */
    private fun canonicalStack(input: GTRecipeInput): ItemStack?
    {
        val stacks = input.inputStacks ?: return null
        var bestStack: ItemStack? = null
        var bestToken: String? = null
        for (stack in stacks)
        {
            val exact = slotTokens(stack).first()
            if (bestToken == null || exact < bestToken)
            {
                bestStack = stack
                bestToken = exact
            }
        }
        return bestStack
    }

    /**
     * 内容派生的稳定指纹，用于 spec 7.2 的稳定 id。
     *
     * **绝对不要用 `GTRecipeInput.hashCode()` / `ItemStack.hashCode()`**：它们最终引用
     * `Item.hashCode()` 这个 identity hash（`Item` 未覆写 `hashCode`），跨 JVM 启动会变，
     * 会让两次导出的 id 全错位，跨次对比全是噪声。
     * 这里全部走 [itemToken] / [fluidToken]，只含 registryName、meta 与 NBT 的**规范**渲染
     * （[canonicalNbt]），都是内容派生的。
     *
     * ### 本指纹的全部用途只问"内容"，而**顺序不属于内容**
     *
     * 用途有二：spec §7.2 的稳定 id，以及导出器（Task 8）的 `distinctBy { stableKey(it) }` 去重。
     * 两者问的都是"这两条配方的内容是否相同"，不关心同一个多重集以什么次序被枚举出来。
     *
     * 所以本函数对**每一段都与顺序无关**地渲染，方式是**把段排序后拼接**：
     * 多重集相同 ⇒ 指纹相同。**这是全函数性质，不是对个别段的特例**：
     *
     * - 六个数据段**全部排序后** `,` 拼接：物品输入、流体输入、物品输出、流体输出、
     *   钱斯物品输出、钱斯流体输出（外加 `eUt|duration|` 这个固定头）；
     * - 段**内**的顺序同样被规范化到底：候选集排序（[canonicalCandidateTokens]）、
     *   声明栈集合排序（[declaredStackTokens]）、规范栈取字典序最小（[canonicalStack]）；
     * - 排序键：**输入两段**是**整段渲染出的串本身**（数量在该串内），**四个输出段**是
     *   `(token, 数量…)` 元组；两者都是**全序**，所以"数量 ↔ token"的关联不丢：
     *   相同的段渲染成相同的键时它们本就不可区分，互换位置不改变排序结果。
     *
     * 反过来说：**上一版这里是不成立的**——输入段与流体输入段当时按 `recipe.inputs` /
     * `recipe.fluidInputs` 的**列表顺序**直接拼接，段序会漂（只要同一批输入以不同次序被声明，
     * 指纹就不同），而 KDoc 却宣称"对每一段都与顺序无关"。现在这句话是真的，且有回归对照：
     * 探针把这两段换成**逐次运行不同**的次序（`sortedBy { System.identityHashCode(it) }`）后重跑，
     * `keys.txt` 立刻 18813 行 differ（报告"灵敏度对照"的 **C2**）—— 段序一漂，导出物就抓得住。
     *
     * **这条对照的结论边界**（必须写清，免得又变成"声称有防线、实则没有"）：C2 证明的是
     * "**段序一漂，`keys.txt` 抓得住**"，依据是两条相互独立的证据 ——（a）**隔离性**：C2 只有
     * `keys.txt` 动（18813 行），`tokens/pairs/collide` 一字不动，扰动没有溢出到别处；（b）
     * `classify.py` 复核出 **`content: 0`**，4624 组差异**全部**是"同一批 token 的排列变了"，
     * 没有一个是 token 被换掉。但它**不能**证明"一个**确定的**错误段序会被抓到"：那种形态的对照
     * （**C1'**：只把这两段退回**声明顺序**）是**确定性**的 —— 两次运行跑的是同一份代码，结果逐字节
     * 相同，给出的是 **IDENTICAL**。确定性扰动本来就不会在"两次运行之间"产生差异；C1' 因此不能
     * 用来证明 I1 的修复是必要的，I1 的证据只由 C2 提供。
     *
     * 下面三个不稳定源都**不来自渲染**，而是来自枚举次序，都在本函数里被规范化掉：
     *
     * 1. **输入展开的次序**（`getOres` 的 HashMap 序）：`GTRecipeOreInput.getInputStacks()` 缓存
     *    `OreDictionary.getOres(name)`，其顺序由矿辞注册顺序决定，而注册顺序的源头含 `Material`
     *    identity hash（`MetaItems.GLASS_LENSES` 等 HashMap）——逐次启动不同。由 [canonicalStack]
     *    取"精确 token 的字典序最小者"对应的那个栈、再对它的候选集**排序**消除，
     *    见 [canonicalCandidateTokens] 的 KDoc。
     * 2. **保底输出的声明顺序**（`finalizeOutputs` 的等量 hash 序）：`recipe.outputs` 的顺序由配方
     *    生成方决定；GTCEu 的回收配方 `RecyclingRecipes.finalizeOutputs` 用
     *    `sorted(comparingLong(-amount))` 排一个 `HashMap` 的 entrySet —— **材料量相同的一对输出**
     *    （如电缆回收出的两份 2x 粉）顺序即由 hash 定，而 `MaterialStack` 无 `hashCode`，
     *    逐次启动不同（实测 macerator 58 组）。
     * 3. **输入段的声明顺序**：`recipe.inputs` / `recipe.fluidInputs` 的次序由配方生成方决定，
     *    同一批输入以不同次序声明会得到不同段序 —— 对"内容是否相同"这个问题是噪声。
     *
     * 三处都用同一个手法消掉：**排序已渲染出的段串**。
     *
     * ### 输入段 = **声明身份** + **NBT matcher** + **完整候选集** + **声明栈集合**
     *
     * 每一段是 `amount` + `x` + [inputSegment]。四段的完整分工见 [inputSegment] 的 KDoc；
     * 要点是**四段各管一件不同的、都必须进指纹的事**：
     *
     * - **声明身份**（[declaredIdentityToken]）：这条输入是**以什么形式声明的** —— 矿辞名，
     *   或输入的具体实现类。非矿辞输入带的是它的具体类别（而不是一个笼统的"非矿辞"标记），
     *   因为 `GTRecipeItemInput` / `IntCircuitIngredient` / `FluidCellInput` 都 `isOreDict == false`
     *   却仍是 trie 里不同的 key 类型。这一段把 4938 个槽位族分开。
     * - **NBT matcher**（[nbtMatcherToken]）：决定 key 进 `nodes` 还是 `specialNodes`。
     *   这一段把"只由 matcher 区分"的 197 族分开。
     * - **完整候选集**（[canonicalCandidateTokens]，按 token 字典序排序）：这条输入**展开成了什么**。
     * - **声明栈集合**（[declaredStackTokens]，按 token 字典序排序）：这条输入**声明了哪些栈**
     *   （`GTRecipeItemInput` 的通配 meta 会展开成一串栈，trie 里是同样多条路径）。
     *   这一段把"只由声明栈项数区分"的 11 族分开。
     *
     * **四段为什么都不能省**：它们是 trie 里**不同的可见性**（见 [inputSegment] 的 KDoc 里那条
     * "静默合并 → `recipe: -1` → 对 Python 侧不可见"的因果链）。去掉任一段，对应的那一族就会
     * 重新被导出器的 `distinctBy { stableKey(it) }` 静默合并。
     *
     * **为什么用矿辞名而不是 `input.oreDict`（int id）**：ore id 按注册顺序发放、跨启动会变
     * （实测 12839 个名字里 1600 个 id 不同），写 id 就等于把 [stableKey] 重新变成漂移量。
     * 详见 [declaredIdentityToken]。
     *
     * 为什么**没有**退化成"每输入一个代表 token"（本文件曾经如此）：代表 token 是展开里精确 token
     * 的字典序最小者，两条输入**矿辞名不同**、但展开**共享同一个最小元素**时代表 token 会相同，
     * 同样会走上面那条静默合并路径。候选集是**必要的一段**，但它单独换不来更细的区分
     * （`wide` 是 `narrow` 的纯函数，实测区分度增量为 0，见 [canonicalCandidateTokens]）；
     * 真正把 4938 个槽位族分开的是**声明身份**那一段。
     *
     * 这些只影响**跨运行**比对与导出侧的合并：单次运行内自洽，Python 侧的 `Reach` 与冲突结论
     * 不受影响（候选与输出在那边都当集合/多重集用）。
     */
    fun stableKey(recipe: Recipe): String = buildString {
        // `Recipe.getEUT()` 在 Kotlin 里被合成为属性 `eUt`（与 RecipeBuilder.EUT 同名不同形），
        // 原本的 Java 风格调用 recipe.getEUT() 会 Unresolved reference——Kotlin 不暴露被
        // 合成为属性的 getter。仓库里其它地方（如 PseudoGroupRecipeMapBase.kt:37）也是写 recipe.eUt。
        append(recipe.eUt).append('|').append(recipe.duration).append('|')
        // 输入段**排序后再拼接**：`recipe.inputs` 的**声明顺序不是内容**（理由与下面输出段同）。
        // 排序键是**整段已渲染出的串**（含数量），所以"数量与 token 的关联"不会因排序而丢失：
        // 相同的段渲染成相同的串，互换位置不改变排序结果。
        val itemInputSegments = recipe.inputs.map { "${it.amount}x${inputSegment(it)}" }
        for (segment in itemInputSegments.sorted())
        {
            append(segment).append(',')
        }
        append('|')
        // 流体输入段同理：排序键是整段渲染出的串（含 `amount`）。
        val fluidInputSegments = recipe.fluidInputs.map {
            val stack = it.inputFluidStack
            "${it.amount}x${fluidToken(stack.fluid, stack.tag)}"
        }
        for (segment in fluidInputSegments.sorted())
        {
            append(segment).append(',')
        }
        // 输出排序后再拼接：`recipe.outputs` 的**声明顺序不是内容**（见 KDoc 第 2 条）。
        // 主键是 token、次键是数量 —— 既满足"按 token 排序"，又是一个全序：token 相同时
        // 仍然确定，不会退化成"保持原声明顺序"（那样等量同名的两条仍会跨运行互换）。
        append('|')
        val itemOutputs = recipe.outputs.map { it.count to itemToken(it, it.metadata, it.tagCompound, false) }
        for ((count, token) in itemOutputs.sortedWith(compareBy({ it.second }, { it.first })))
        {
            append(count).append('x').append(token).append(',')
        }
        append('|')
        val fluidOutputs = recipe.fluidOutputs.map { it.amount to fluidToken(it.fluid, it.tag) }
        for ((amount, token) in fluidOutputs.sortedWith(compareBy({ it.second }, { it.first })))
        {
            append(amount).append('x').append(token).append(',')
        }
        // 【相对 brief 的加固】钱斯输出也是内容，必须计入指纹。
        //
        // 漏掉它们的后果不是"指纹不够唯一"这么轻：导出器（Task 8）用
        // `distinctBy { stableKey(it) }` 给配方去重，两条**只在钱斯输出上不同**的配方
        // 会被合并成一条，被丢掉的那条在导出里没有自己的 id，Python 拿到的是张冠李戴的映射——
        // 又是一次静默错误结论。Python 侧不建模钱斯输出（它只看保底输出），所以把两条渲染成
        // 不同 id 对它无害：它们的保底输出向量相同，判据仍会判为"无冲突"。
        //
        // 同样排序：钱斯输出的**声明顺序也不是内容**。
        append('|')
        val chancedItems = recipe.chancedOutputs.chancedEntries.map {
            Triple(
                itemToken(it.ingredient, it.ingredient.metadata, it.ingredient.tagCompound, false),
                it.chance,
                it.chanceBoost
            )
        }
        for ((token, chance, boost) in
            chancedItems.sortedWith(compareBy({ it.first }, { it.second }, { it.third })))
        {
            append(chance).append('/').append(boost).append('x').append(token).append(',')
        }
        append('|')
        val chancedFluids = recipe.chancedFluidOutputs.chancedEntries.map {
            Triple(fluidToken(it.ingredient.fluid, it.ingredient.tag), it.chance, it.chanceBoost)
        }
        for ((token, chance, boost) in
            chancedFluids.sortedWith(compareBy({ it.first }, { it.second }, { it.third })))
        {
            append(chance).append('/').append(boost).append('x').append(token).append(',')
        }
    }

    fun isCircuit(input: GTRecipeInput): Boolean = input is IntCircuitIngredient

    /**
     * 初始化自检，由 `init` 块调用（首次使用本对象时执行，早于任何一次导出）。
     *
     * 断言五件事：
     * 1. 非 special 前缀落不进 Python 的 `_is_special_token` 判定（否则一个 `item:` 节点会被
     *    Python 当成属于 `specialNodes`，交叉校验必崩）；
     * 2. 渲染函数**实际产出**的前缀与上面这条一致——走真实的 [keyToken] 分派（含
     *    `isSpecialIngredient()` 判定与 accessor 调用），而不是另写一份断言用的表达式；
     * 3. `<tag>` 段是规范渲染且与插入顺序无关——这是 canonicalNbt 的回归防线；
     * 4. **lambda 类名归一化**（[stableClassName]）——这是 [nbtMatcherToken] 的回归防线；
     * 5. **跨语言字面量**（前缀 + `none`）与 Python 侧逐字一致——[CROSS_LANGUAGE_LITERALS]，
     *    每一项对着硬编码字面量断言，不是对着本文件里的表达式断言。
     *
     * 第 3、4、5 条都是**真会失败**的断言：第 3 条用了一个 key 插入顺序与排序形式不同的
     * compound，第 4、5 条的对侧是写死的字面量。**这里刻意不写"`SPECIAL_PREFIXES` 与 Python 的
     * `_SPECIAL_PREFIXES` 相等"那种自己跟自己比的断言**：本文件里只能把 `SPECIAL_PREFIXES` 与
     * "同一个表达式的另一种写法"相比，是恒真式，永远抓不到真正的不一致。第 4 条把它换成"与写死的
     * 字面量相等"，才真正抓得住。真正的跨语言兜底仍在 Python 侧：`scripts/recipeaudit/model.py`
     * 的 `_parse_trie_path` 会把 token 前缀与导出器声明的 `nodeMaps` 交叉校验，不一致就在
     * **载入期抛 `ValueError`**——响亮失败，整轮审计跑不起来，而不是静默给出错误结论。
     * 本地自检的职责只有一件：防本侧回归。
     */
    private fun selfCheck()
    {
        // 1. 非 special 前缀不得被 Python 的 startswith(_SPECIAL_PREFIXES) 命中
        for (prefix in listOf("$ITEM_PREFIX:", "$ORE_PREFIX:", "$FLUID_PREFIX:"))
        {
            check(SPECIAL_PREFIXES.none { prefix.startsWith(it) }) {
                "前缀 $prefix 被 SPECIAL_PREFIXES=$SPECIAL_PREFIXES 命中：" +
                    "Python 会把这些节点查进 specialNodes，交叉校验必崩"
            }
        }

        // 2. 渲染函数实际产出的前缀。这里构造的是**真实的 GT key 实例**，
        //    所以同时验证了 accessor mixin 已生效、字段名没写错。
        val noTag: NBTTagCompound? = null
        val plainItem = MapItemStackIngredient(ItemStack.EMPTY, 0, noTag)
        val nbtItem = MapItemStackNBTIngredient(ItemStack.EMPTY, 0, noTag)
        val plainOre = MapOreDictIngredient(0)
        val nbtOre = MapOreDictNBTIngredient(0, noTag)

        for (key in listOf(plainItem, nbtItem, plainOre, nbtOre))
        {
            val token = keyToken(key)
            val isSpecial = SPECIAL_PREFIXES.any { token.startsWith(it) }
            check(isSpecial == key.isSpecialIngredient()) {
                "token $token 的前缀与 isSpecialIngredient()=${key.isSpecialIngredient()} 不一致：" +
                    "Python 会按前缀选 map，而 trie 是按 isSpecialIngredient() 分节点的，两者必须同源"
            }
        }

        // 3. <tag> 段必须是**规范**渲染（与 DEBUG 下 toString() 同形、与 key 插入顺序无关），
        //    既不是 hashCode，也不是原始的 NBTTagCompound.toString()。
        //
        //    这条断言**真的会失败**（不是恒真式）：`ordered` 的 key 按 "zz","aa" 插入，而
        //    "zz"/"aa" 的 hashCode 落在 HashMap 同一个桶里，所以 `toString()` 会按插入顺序
        //    输出 `{zz:"2",aa:"1"}` —— 与下面断言的排序形式不同。谁把 canonicalNbt 换回
        //    `tag.toString()`，这里立刻挂。
        val ordered = NBTTagCompound().apply { setString("zz", "2"); setString("aa", "1") }
        check(ordered.toString() != "{aa:\"1\",zz:\"2\"}") {
            "构造用例失效：ordered 的 toString() 恰好也等于排序形式，下面的断言就抓不到回退了"
        }
        check(canonicalNbt(ordered) == "{aa:\"1\",zz:\"2\"}") {
            "canonicalNbt 必须按 key 字典序渲染键值对，实际得到 ${canonicalNbt(ordered)}"
        }

        // 嵌套 compound 递归排序；list 有序，顺序即语义，必须原样保留
        val nested = NBTTagCompound().apply {
            setTag("z", NBTTagCompound().apply { setString("b", "2"); setString("a", "1") })
            setTag("a", NBTTagList().apply { appendTag(NBTTagString("x")); appendTag(NBTTagString("y")) })
        }
        check(canonicalNbt(nested) == "{a:[\"x\",\"y\"],z:{a:\"1\",b:\"2\"}}") {
            "嵌套 compound 必须排序、list 必须保序，实际得到 ${canonicalNbt(nested)}"
        }

        // 4. **lambda 类名归一化**：这是 [nbtMatcherToken] 的回归防线，且**真的会失败**——
        //    右边是写死的期望值，不是 [stableClassName] 里的另一种写法。谁把归一化删掉，
        //    lambda 类名里的 `$$Lambda$<序号>/<hash>` 就进了指纹，[stableKey] 逐次启动漂移
        //    （本文件实测过：两次运行 keys.txt 逐字节不同）。
        for ((raw, expected, description) in listOf(
            // JDK 8 的 lambda 类名
            Triple("a.b.C\$\$Lambda\$2389/1033839220", "a.b.C\$\$Lambda", "lambda 类名（含序号/hash）"),
            Triple("a.b.C\$\$Lambda\$17/0x0000000800c01200", "a.b.C\$\$Lambda", "lambda 类名（hex 形态）"),
            // 具名类与匿名类必须原样穿过，不能被误伤
            Triple("gregtech.api.recipes.ingredients.GTRecipeItemInput",
                   "gregtech.api.recipes.ingredients.GTRecipeItemInput", "具名类原样保留"),
            Triple("a.b.C\$1", "a.b.C\$1", "匿名类原样保留")
        ))
        {
            check(stableClassName(raw) == expected) {
                "$description：stableClassName(`$raw`) 期望 `$expected`，实际 `${stableClassName(raw)}`"
            }
        }

        // 5. **跨语言字面量**：逐项对着**硬编码字面量**断言（表见 [CROSS_LANGUAGE_LITERALS]）。
        //
        //    这里是上一版那条空转断言的替代品。上一版写的是
        //        check(itemToken(ItemStack.EMPTY, 0, noTag, nbtVariant = false).endsWith(":$NONE_TAG"))
        //    期望值 `":$NONE_TAG"` 里就是 `NONE_TAG` 常量本身，而 `itemToken` 的尾部恒为
        //    `":${canonicalNbt(tag)}"`、`canonicalNbt(null) == NONE_TAG` —— 那条断言**只可能因本
        //    函数自相矛盾而失败**，把 `NONE_TAG` 改成 `"null"` 它照样通过。而 Python 逐字依赖的
        //    正是那个字面量 `none`：改掉它，Python 侧 `model._parse_trie_path` 与输出比对会整体
        //    错位，本侧却一声不吭。**这是本仓库第五次出现空转断言**，所以下面不写任何"期望值 =
        //    本文件里的某个表达式"，一律写死字面量。
        for ((actual, literal, description) in CROSS_LANGUAGE_LITERALS)
        {
            check(actual == literal) {
                "$description：渲染出的字面量是 `$actual`，但 Python 侧逐字依赖 `$literal`。" +
                    "改这里就必须同步改 scripts/recipeaudit/model.py（`_SPECIAL_PREFIXES` 与 token 解析）"
            }
        }
    }

}
