package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.impl.AbstractRecipeLogic;
import gregtech.api.metatileentity.MTETrait;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.logic.OCResult;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuses the overclocked and parallel-multiplied recipe produced by sub-tick overclocking.
 * <p>
 * Once a recipe overclocks below one tick, {@code duration} is clamped to 1, so the recipe finishes
 * every tick and {@code setupAndConsumeRecipeInputs} reruns the whole preparation pipeline every
 * tick: {@code calculateOverclock} -> {@code subTickOC} (recipe copy + {@code doParallelRecipes})
 * -> power and output space checks. {@code doParallelRecipes} alone costs 30 to 100 microseconds
 * per call at 256 to 1024 parallels, which is what makes 1-tick-overclocked machines lag.
 * <p>
 * The cached recipe is reused only while nothing that shaped it changed: the same source recipe,
 * the same max/overclock voltage, the same EU/speed discount, the inputs still covering the cached
 * parallel amount, the outputs still fitting, enough power, and at most
 * {@code gtlitecore$CACHE_TICKS} ticks of age, so a machine that gains inputs still grows its
 * parallelism and anything not covered by these checks is picked up within a second. Every check
 * the caller performs is reproduced before the cache is served.
 */
@Mixin(value = AbstractRecipeLogic.class, remap = false)
public abstract class MixinAbstractRecipeLogicSubTickCache
{

    /** How long a prepared recipe stays reusable, in ticks. */
    @Unique
    private static final int gtlitecore$CACHE_TICKS = 20;

    /** Kept mutable so the benchmark can A/B a single server run; production always runs enabled. */
    @Unique
    private static boolean gtlitecore$cacheEnabled =
            !Boolean.parseBoolean(System.getProperty("gtlitecore.subTickCacheDisable", "false"));

    @Shadow
    @Final
    private OCResult ocResult;

    @Shadow
    private double euDiscount;

    @Shadow
    private double speedBonus;

    @Shadow
    public abstract long getMaxVoltage();

    @Shadow
    public abstract long getMaximumOverclockVoltage();

    @Shadow
    protected abstract IItemHandlerModifiable getOutputInventory();

    @Shadow
    protected abstract IMultipleTankHandler getOutputTank();

    @Shadow
    protected abstract boolean checkOutputSpaceItems(@NotNull Recipe recipe,
                                                     @NotNull IItemHandlerModifiable exportInventory);

    @Shadow
    protected abstract boolean checkOutputSpaceFluids(@NotNull Recipe recipe,
                                                      @NotNull IMultipleTankHandler exportFluids);

    @Shadow
    protected abstract boolean hasEnoughPower(long eut, int duration);

    @Unique
    private @Nullable Recipe gtlitecore$source;

    @Unique
    private @Nullable Recipe gtlitecore$prepared;

    @Unique
    private long gtlitecore$maxVoltage;

    @Unique
    private long gtlitecore$maxOverclockVoltage;

    @Unique
    private double gtlitecore$euDiscount;

    @Unique
    private double gtlitecore$speedBonus;

    @Unique
    private long gtlitecore$preparedEUt;

    @Unique
    private int gtlitecore$preparedDuration;

    @Unique
    private int gtlitecore$preparedParallel;

    @Unique
    private long gtlitecore$preparedParallelEUt;

    @Unique
    private long gtlitecore$preparedAt;

    @Unique
    private boolean gtlitecore$servedFromCache;

    @Inject(method = "setupAndConsumeRecipeInputs(Lgregtech/api/recipes/Recipe;Lnet/minecraftforge/items/IItemHandlerModifiable;Lgregtech/api/capability/IMultipleTankHandler;)Lgregtech/api/recipes/Recipe;",
            at = @At("HEAD"),
            cancellable = true)
    private void gtlitecore$reusePreparedRecipe(Recipe recipe, IItemHandlerModifiable importInventory,
                                                IMultipleTankHandler importFluids,
                                                CallbackInfoReturnable<Recipe> callbackInfo)
    {
        this.gtlitecore$servedFromCache = false;
        // The method reassigns its own `recipe` parameter, so remember what it was called with.
        this.gtlitecore$source = recipe;
        if (!gtlitecore$cacheEnabled) return;

        Recipe cached = this.gtlitecore$prepared;
        if (cached == null) return;
        if (!gtlitecore$cacheMatches(recipe)) return;

        long now = gtlitecore$metaTileEntity().getOffsetTimer();
        if (now - this.gtlitecore$preparedAt >= gtlitecore$CACHE_TICKS)
        {
            gtlitecore$clear();
            return;
        }

        // Reproduce every check the caller would perform before consuming the inputs.
        if (!cached.matches(false, importInventory, importFluids)) return;
        if (!hasEnoughPower(this.gtlitecore$preparedEUt, this.gtlitecore$preparedDuration)) return;
        if (!checkOutputSpaceItems(cached, getOutputInventory())) return;
        if (!checkOutputSpaceFluids(cached, getOutputTank())) return;
        if (!cached.matches(true, importInventory, importFluids)) return;

        this.ocResult.init(this.gtlitecore$preparedEUt, this.gtlitecore$preparedDuration,
                this.gtlitecore$preparedParallel, this.gtlitecore$preparedParallelEUt);
        gtlitecore$metaTileEntity().addNotifiedInput(importInventory);
        this.gtlitecore$servedFromCache = true;
        callbackInfo.setReturnValue(cached);
    }

    @Inject(method = "setupAndConsumeRecipeInputs(Lgregtech/api/recipes/Recipe;Lnet/minecraftforge/items/IItemHandlerModifiable;Lgregtech/api/capability/IMultipleTankHandler;)Lgregtech/api/recipes/Recipe;",
            at = @At("RETURN"))
    private void gtlitecore$cachePreparedRecipe(Recipe recipe, IItemHandlerModifiable importInventory,
                                                IMultipleTankHandler importFluids,
                                                CallbackInfoReturnable<Recipe> callbackInfo)
    {
        if (!gtlitecore$cacheEnabled || this.gtlitecore$servedFromCache) return;

        Recipe result = callbackInfo.getReturnValue();
        Recipe source = this.gtlitecore$source;
        // subTickOC only kicks in when overclocking produced extra parallels; everything else is
        // cheap enough that caching it would just add bookkeeping.
        if (result == null || source == null || result == source || this.ocResult.parallel() <= 1)
        {
            gtlitecore$clear();
            return;
        }

        this.gtlitecore$prepared = result;
        this.gtlitecore$maxVoltage = getMaxVoltage();
        this.gtlitecore$maxOverclockVoltage = getMaximumOverclockVoltage();
        this.gtlitecore$euDiscount = this.euDiscount;
        this.gtlitecore$speedBonus = this.speedBonus;
        this.gtlitecore$preparedEUt = this.ocResult.eut();
        this.gtlitecore$preparedDuration = this.ocResult.duration();
        this.gtlitecore$preparedParallel = this.ocResult.parallel();
        this.gtlitecore$preparedParallelEUt = this.ocResult.parallelEUt();
        this.gtlitecore$preparedAt = gtlitecore$metaTileEntity().getOffsetTimer();
    }

    @Inject(method = "invalidate", at = @At("HEAD"))
    private void gtlitecore$clearOnInvalidate(CallbackInfo callbackInfo)
    {
        gtlitecore$clear();
    }

    @Unique
    private boolean gtlitecore$cacheMatches(Recipe recipe)
    {
        if (this.gtlitecore$source != recipe) return false;
        return this.gtlitecore$maxVoltage == getMaxVoltage()
                && this.gtlitecore$maxOverclockVoltage == getMaximumOverclockVoltage()
                && this.gtlitecore$euDiscount == this.euDiscount
                && this.gtlitecore$speedBonus == this.speedBonus;
    }

    @Unique
    private MetaTileEntity gtlitecore$metaTileEntity()
    {
        return ((MTETrait) (Object) this).getMetaTileEntity();
    }

    @Unique
    private void gtlitecore$clear()
    {
        this.gtlitecore$source = null;
        this.gtlitecore$prepared = null;
    }
}
