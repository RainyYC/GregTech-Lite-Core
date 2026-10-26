package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeBuilder;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.chance.output.ChancedOutputLogic;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.util.ValidationResult;
import gregtechlite.gtlitecore.api.recipe.ChancedOutputSampler;
import gregtechlite.gtlitecore.api.recipe.duck.IChancedParallel;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = RecipeBuilder.class, remap = false)
public abstract class MixinRecipeBuilder
{
    @Shadow
    protected long EUt;

    @Shadow
    @Final
    protected List<ChancedItemOutput> chancedOutputs;

    @Shadow
    @Final
    protected List<ChancedFluidOutput> chancedFluidOutputs;

    /**
     * Amount of parallel operations the built recipe is multiplied by. Chanced outputs are kept once per entry
     * instead of once per operation, and this amount is carried over to the built {@link Recipe}.
     */
    @Unique
    private int gtlite$chancedParallel = 1;

    /**
     * Minimum {@code EUt} request bound is not be implemented in CEu but only throw loggers. We added a minimum
     * bound of the {@code EUt} properties in {@link RecipeBuilder}.
     * <p>
     * If a recipe requested {@code EUt} value is <tt>0</tt>, then added it with <tt>1</tt> to resolve bound
     * problem of {@link RecipeBuilder}.
     *
     * @author Magic_Sweepy
     */
    @Inject(method = "build",
            at = @At("HEAD"))
    private void requestEUtRange(CallbackInfoReturnable<ValidationResult<Recipe>> cir)
    {
        if (EUt == 0) EUt += 1;
    }

    /**
     * Attaches the parallel amount carried by this builder to the built recipe.
     *
     * @author Magic_Sweepy
     */
    @Inject(method = "build",
            at = @At("TAIL"))
    private void gtlite$attachChancedParallel(CallbackInfoReturnable<ValidationResult<Recipe>> cir)
    {
        Recipe recipe = cir.getReturnValue().getResult();
        if (recipe != null && gtlite$chancedParallel > 1)
        {
            ((IChancedParallel) recipe).gtlite$setChancedParallel(gtlite$chancedParallel);
        }
    }

    /**
     * Inherits the parallel amount when a builder is created from an already multiplied recipe, so repeated
     * multiplications (parallel search followed by sub-tick overclocking) keep their product.
     *
     * @author Magic_Sweepy
     */
    @Inject(method = "<init>(Lgregtech/api/recipes/Recipe;Lgregtech/api/recipes/RecipeMap;)V",
            at = @At("TAIL"))
    private void gtlite$inheritChancedParallel(Recipe recipe, RecipeMap<?> recipeMap, CallbackInfo ci)
    {
        if (recipe != null)
        {
            this.gtlite$chancedParallel = ((IChancedParallel) recipe).gtlite$getChancedParallel();
        }
    }

    /**
     * Keeps a single copy of every chanced output instead of one copy per parallel operation.
     * <p>
     * The amount of produced items is drawn when the recipe result is computed, see
     * {@code MixinRecipe#gtlite$sampledItemOutputs}. Non-{@code OR} chance logics keep the original expansion
     * because their result depends on the order and amount of the expanded entries.
     *
     * @author Magic_Sweepy
     */
    @Inject(method = "chancedOutputsMultiply",
            at = @At("HEAD"),
            cancellable = true)
    private void gtlite$multiplyChancedOutputs(Recipe source, int numberOfOperations, CallbackInfo ci)
    {
        if (numberOfOperations <= 1) return;
        if (source.getChancedOutputs().getChancedOutputLogic() != ChancedOutputLogic.OR
                || source.getChancedFluidOutputs().getChancedOutputLogic() != ChancedOutputLogic.OR)
        {
            return;
        }

        for (ChancedItemOutput entry : source.getChancedOutputs().getChancedEntries())
        {
            this.chancedOutputs.add(new ChancedItemOutput(entry.getIngredient().copy(),
                    entry.getChance(), entry.getChanceBoost()));
        }
        for (ChancedFluidOutput entry : source.getChancedFluidOutputs().getChancedEntries())
        {
            this.chancedFluidOutputs.add(new ChancedFluidOutput(entry.getIngredient().copy(),
                    entry.getChance(), entry.getChanceBoost()));
        }

        this.gtlite$chancedParallel = gtlite$saturatingMultiply(this.gtlite$chancedParallel, numberOfOperations);
        ci.cancel();
    }

    /**
     * Saturates the multiplied item and fluid amounts, so a huge parallel count cannot overflow them into
     * negative counts.
     *
     * @author Magic_Sweepy
     */
    @Redirect(method = "append",
            at = @At(value = "INVOKE",
                    target = "Lgregtech/api/recipes/RecipeBuilder;multiplyInputsAndOutputs(Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Lgregtech/api/recipes/Recipe;I)V"))
    private void gtlite$saturatingMultiplyInputsAndOutputs(List<GTRecipeInput> newRecipeInputs,
                                                           List<GTRecipeInput> newFluidInputs,
                                                           List<ItemStack> outputItems,
                                                           List<FluidStack> outputFluids,
                                                           Recipe recipe,
                                                           int numberOfOperations)
    {
        for (GTRecipeInput input : recipe.getInputs())
        {
            if (input.isNonConsumable())
            {
                newRecipeInputs.add(input);
            }
            else
            {
                newRecipeInputs.add(input.copyWithAmount(
                        ChancedOutputSampler.INSTANCE.saturatedCount(input.getAmount(), numberOfOperations)));
            }
        }
        for (GTRecipeInput input : recipe.getFluidInputs())
        {
            if (input.isNonConsumable())
            {
                newFluidInputs.add(input);
            }
            else
            {
                newFluidInputs.add(input.copyWithAmount(
                        ChancedOutputSampler.INSTANCE.saturatedCount(input.getAmount(), numberOfOperations)));
            }
        }
        for (ItemStack stack : recipe.getOutputs())
        {
            ItemStack copy = stack.copy();
            copy.setCount(ChancedOutputSampler.INSTANCE.saturatedCount(stack.getCount(), numberOfOperations));
            outputItems.add(copy);
        }
        for (FluidStack stack : recipe.getFluidOutputs())
        {
            FluidStack copy = stack.copy();
            copy.amount = ChancedOutputSampler.INSTANCE.saturatedAmount(stack.amount, numberOfOperations);
            outputFluids.add(copy);
        }
    }

    @Unique
    private static int gtlite$saturatingMultiply(int value, int multiplier)
    {
        long result = (long) value * multiplier;
        return (int) Math.min(Integer.MAX_VALUE, result);
    }
}
