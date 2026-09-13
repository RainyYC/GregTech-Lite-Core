package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.map.Branch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 配方审计工具需要遍历整棵 trie，而它的根 {@code lookup} 是 {@code RecipeMap} 的 private 字段。
 * <p>
 * {@code Branch}（{@code getNodes()} / {@code getSpecialNodes()} / {@code getRecipes(boolean)}）、
 * {@code Either}（{@code left()} / {@code right()}）与 {@code AbstractMapIngredient} 的遍历 API
 * 都是 public，所以<b>只需要这一个 accessor</b> 就能完整走一遍 trie——多余的 accessor 只会多一份
 * 与 GT 内部结构耦合的契约。
 * <p>
 * 对应 {@code gregtechlite.gtlitecore.loader.recipe.audit.RecipeMapExporter.walk}。
 */
@Mixin(value = RecipeMap.class, remap = false)
public interface AccessorRecipeMap
{

    @Accessor("lookup")
    Branch auditLookup();

}
