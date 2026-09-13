package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.recipes.map.MapOreDictIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 同上。子类 {@code MapOreDictNBTIngredient} 继承 {@code ore} 字段。
 * <p>
 * 对应 {@code gregtechlite.gtlitecore.loader.recipe.audit.RecipeAuditTokens.keyToken}。
 */
@Mixin(value = MapOreDictIngredient.class, remap = false)
public interface AccessorMapOreDictIngredient
{
    @Accessor("ore")
    int auditOre();
}
