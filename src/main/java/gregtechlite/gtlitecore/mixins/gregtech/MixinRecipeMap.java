package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtechlite.gtlitecore.loader.recipe.audit.RecipeAuditRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录 {@code RecipeMap.compileRecipe} 的插入结果。
 * <p>
 * 失败时 {@code RecipeMap.addRecipe} 只是 {@code return false}，调用方
 * {@code RecipeBuilder.buildAndRegister} 又把返回值丢掉，所以<b>这一类</b>被拒配方在 trie 与
 * {@code recipeByCategory} 里都不留痕，只能在这里观测。见 spec 1 节。
 * <p>
 * <b>但不要写成"被拒配方在任何可枚举结构里都不存在"</b>——spec §9.4 已判定并纠正过这个结论。
 * 存在第二类"<b>幽灵配方</b>"：{@code recurseIngredientTreeAdd} 在叶子槽挂的是 {@code Branch}
 * 而非 {@code Recipe} 时会打冲突日志、{@code compileRecipe} 却返回 {@code true}，
 * 于是它进了 {@code recipeByCategory} 但没有 trie 路径。本 hook 按返回值记录，看不到这一类
 * （<b>漏的只有 {@code RecipeAuditRecorder.rejectedFor()} 这一边</b>）；而 Task 8 的导出走
 * {@code RecipeMap.getRecipesByCategory()}（{@code RecipeMap.java:1424}），
 * 所以幽灵配方会带着 {@code inTree=false} 出现在导出的 {@code recipes} 清单里，{@code recipes} 那边是齐的。
 * <p>
 * <b>反面陷阱（导出必须用 {@code getRecipesByCategory()} 的理由之一）</b>：谁若把导出改成走
 * {@code getRecipeList()}（{@code RecipeMap.java:1239-1242}，读 trie），幽灵就会
 * <b>同时从 {@code recipes} 与 {@code rejected} 里消失、彻底不可见</b>。
 *
 * @author Magic_Sweepy
 */
@Mixin(value = RecipeMap.class, remap = false)
public abstract class MixinRecipeMap
{

    @Inject(method = "compileRecipe", at = @At("RETURN"))
    private void auditRecordCompileResult(Recipe recipe, CallbackInfoReturnable<Boolean> cir)
    {
        // (Object) 中转是 mixin 里取目标实例的常规写法：MixinRecipeMap 与 RecipeMap 在 Java 层
        // 没有继承关系，能转型是因为 mixin 在运行期把本类合进了 RecipeMap。本仓库既有同款用法，
        // 见 MixinMetaTileEntity#(MetaTileEntity) (Object) this。
        //
        // 不改成 extends RecipeMap 的写法：那会改变 mixin 的父类契约（要求父类本身是作用在
        // 同一目标上的 mixin，或就是目标类的超类），收益只是省掉一次转型，风险却更大。
        //
        // @At("RETURN") 覆盖 compileRecipe 的全部出口（recipe == null / 插入成功 / 插入失败），
        // 返回值直接就是"这次插入有没有成功"。
        //
        // recipe == null 的出口也返回 false，但那不是一条配方；记进来只会让下游渲染 token 时
        // NPE，所以在最靠近来源的地方挡掉。
        if (recipe == null) return;
        RecipeAuditRecorder.record((RecipeMap<?>) (Object) this, recipe, cir.getReturnValue());
    }

}
