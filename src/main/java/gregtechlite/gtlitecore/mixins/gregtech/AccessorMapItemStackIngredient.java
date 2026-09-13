package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.recipes.map.MapItemStackIngredient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 配方审计工具需要读取 trie key 的原始字段来渲染稳定 token。
 * 这些字段是 protected，且子类 {@code MapItemStackNBTIngredient} 继承它们，所以一个 accessor 覆盖两者。
 * <p>
 * 对应 {@code gregtechlite.gtlitecore.loader.recipe.audit.RecipeAuditTokens.keyToken}。
 */
@Mixin(value = MapItemStackIngredient.class, remap = false)
public interface AccessorMapItemStackIngredient
{
    @Accessor("stack")
    ItemStack auditStack();

    @Accessor("meta")
    int auditMeta();

    @Accessor("tag")
    NBTTagCompound auditTag();
}
