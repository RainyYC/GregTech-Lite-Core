package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.chance.boost.ChanceBoostFunction;
import gregtech.api.recipes.chance.output.ChancedOutputLogic;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtechlite.gtlitecore.api.recipe.ChancedOutputSampler;
import gregtechlite.gtlitecore.api.recipe.duck.IChancedParallel;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws chanced outputs once per entry instead of once per parallel operation.
 * <p>
 * Recipes multiplied by {@code RecipeBuilder#append} carry the amount of parallel operations in
 * {@link IChancedParallel} and keep a single copy of every chanced entry instead of one copy per operation, so
 * the amount of produced items is drawn from the matching binomial distribution instead of rolling every
 * expanded entry. Entries are emitted as a single stack with the full amount, which is exactly what GT does
 * with a multiplied recipe: the output inventory accepts what fits and the remainder is dropped anyway.
 *
 * @author Magic_Sweepy
 */
@Mixin(value = Recipe.class, remap = false)
public abstract class MixinRecipe implements IChancedParallel
{
    @Unique
    private int gtlite$chancedParallel = 1;

    @Override
    public int gtlite$getChancedParallel()
    {
        return gtlite$chancedParallel;
    }

    @Override
    public void gtlite$setChancedParallel(int value)
    {
        this.gtlite$chancedParallel = value;
    }

    @Inject(method = "copy", at = @At("TAIL"))
    private void gtlite$copyChancedParallel(CallbackInfoReturnable<Recipe> cir)
    {
        Recipe copy = cir.getReturnValue();
        if (copy != null && copy != (Object) this)
        {
            ((IChancedParallel) copy).gtlite$setChancedParallel(gtlite$chancedParallel);
        }
    }

    @Inject(method = "getResultItemOutputs", at = @At("HEAD"), cancellable = true)
    private void gtlite$sampledItemOutputs(int recipeTier, int machineTier, RecipeMap<?> recipeMap,
                                           CallbackInfoReturnable<List<ItemStack>> cir)
    {
        Recipe self = (Recipe) (Object) this;
        int parallel = gtlite$chancedParallel;
        if (parallel <= 1) return;
        // AND/XOR entries are still expanded per operation, keep their original handling.
        if (self.getChancedOutputs().getChancedOutputLogic() != ChancedOutputLogic.OR) return;

        List<ItemStack> outputs = new ArrayList<>(self.getOutputs());
        ChanceBoostFunction boostFunction = recipeMap.getChanceFunction();
        for (ChancedItemOutput entry : self.getChancedOutputs().getChancedEntries())
        {
            int chance = ChancedOutputLogic.getChance(entry, boostFunction, recipeTier, machineTier);
            int successes = ChancedOutputSampler.INSTANCE.cappedSuccesses(parallel, chance,
                    ChancedOutputSampler.MAX_EMIT_PER_ENTRY);
            if (successes <= 0) continue;

            ItemStack stack = entry.getIngredient().copy();
            stack.setCount(ChancedOutputSampler.INSTANCE.saturatedCount(stack.getCount(), successes));
            outputs.add(stack);
        }
        cir.setReturnValue(outputs);
    }

    @Inject(method = "getResultFluidOutputs", at = @At("HEAD"), cancellable = true)
    private void gtlite$sampledFluidOutputs(int recipeTier, int machineTier, RecipeMap<?> recipeMap,
                                            CallbackInfoReturnable<List<FluidStack>> cir)
    {
        Recipe self = (Recipe) (Object) this;
        int parallel = gtlite$chancedParallel;
        if (parallel <= 1) return;
        if (self.getChancedFluidOutputs().getChancedOutputLogic() != ChancedOutputLogic.OR) return;

        List<FluidStack> outputs = new ArrayList<>();
        for (FluidStack stack : self.getFluidOutputs())
        {
            outputs.add(stack.copy());
        }
        ChanceBoostFunction boostFunction = recipeMap.getChanceFunction();
        for (ChancedFluidOutput entry : self.getChancedFluidOutputs().getChancedEntries())
        {
            int chance = ChancedOutputLogic.getChance(entry, boostFunction, recipeTier, machineTier);
            int successes = ChancedOutputSampler.INSTANCE.cappedSuccesses(parallel, chance,
                    ChancedOutputSampler.MAX_EMIT_PER_ENTRY);
            if (successes <= 0) continue;

            FluidStack stack = entry.getIngredient().copy();
            stack.amount = ChancedOutputSampler.INSTANCE.saturatedAmount(stack.amount, successes);
            outputs.add(stack);
        }
        cir.setReturnValue(outputs);
    }
}
