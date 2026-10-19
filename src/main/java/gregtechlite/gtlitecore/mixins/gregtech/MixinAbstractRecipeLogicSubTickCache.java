package gregtechlite.gtlitecore.mixins.gregtech;

import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.impl.AbstractRecipeLogic;
import gregtech.api.capability.impl.MultiblockRecipeLogic;
import gregtech.api.recipes.logic.OCParams;
import gregtech.api.recipes.properties.RecipePropertyStorage;
import gregtech.api.metatileentity.MTETrait;
import gregtech.api.metatileentity.multiblock.IMaintenance;
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
 * Reuses the recipe prepared by sub-tick overclocking, which would otherwise be rebuilt on every
 * tick because its duration is clamped to one tick.
 * <p>
 * The cache is keyed on the recipe instance handed to {@code prepareRecipe}, which is the recipe
 * map's own instance and therefore stable while the same job runs. The prepared recipe itself is
 * captured after {@code setupAndConsumeRecipeInputs}, because that method rebuilds the recipe
 * through {@code trimRecipeOutputs} / the EU discount builder / {@code findParallelRecipe} /
 * {@code subTickOC} and its result is what {@code setupRecipe} has to receive.
 */
@Mixin(value = AbstractRecipeLogic.class, remap = false)
public abstract class MixinAbstractRecipeLogicSubTickCache
{

    @Unique
    private static final int gtlitecore$CACHE_TICKS = 20;

    /**
     * Shorter lifetime for logics that customize overclocking: those hooks can read machine state
     * that changes on its own (temperature and the like), and the cache has no way to observe it.
     */
    @Unique
    private static final int gtlitecore$DYNAMIC_CACHE_TICKS = 2;

    /** Per class: does the logic leave GT's overclocking untouched? */
    @Unique
    private static final java.util.Map<Class<?>, Boolean> gtlitecore$STABLE_OVERCLOCK =
            new java.util.concurrent.ConcurrentHashMap<>();


    @Shadow
    @Final
    private OCResult ocResult;

    @Shadow
    private double euDiscount;

    @Shadow
    private double speedBonus;

    @Shadow
    protected boolean isOutputsFull;

    @Shadow
    protected abstract boolean isAllowOverclocking();

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

    @Shadow
    protected abstract void setupRecipe(@NotNull Recipe recipe);

    @Shadow
    public abstract void setParallelRecipesPerformed(int amount);

    @Shadow
    public abstract int getParallelRecipesPerformed();

    /** The recipe of the current prepareRecipe call; only used to key the capture below. */
    @Unique
    private @Nullable Recipe gtlitecore$source;

    /** The recipe the cached prepared recipe was built from, i.e. what a hit must match. */
    @Unique
    private @Nullable Recipe gtlitecore$cachedSource;

    /** True between {@code prepareRecipe} HEAD and RETURN, so only its result is captured. */
    @Unique
    private boolean gtlitecore$inPrepare;

    @Unique
    private @Nullable Recipe gtlitecore$prepared;

    @Unique
    private boolean gtlitecore$allowOverclocking;

    @Unique
    private long gtlitecore$maxVoltage;

    @Unique
    private long gtlitecore$maxOverclockVoltage;

    @Unique
    private long gtlitecore$maintenanceProblems;

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
    private int gtlitecore$preparedParallelPerformed;

    @Unique
    private long gtlitecore$preparedParallelEUt;

    @Unique
    private long gtlitecore$preparedAt;


    @Inject(method = "prepareRecipe(Lgregtech/api/recipes/Recipe;Lnet/minecraftforge/items/IItemHandlerModifiable;Lgregtech/api/capability/IMultipleTankHandler;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void gtlitecore$reusePreparedRecipe(Recipe recipe, IItemHandlerModifiable importInventory,
                                                IMultipleTankHandler importFluids,
                                                CallbackInfoReturnable<Boolean> callbackInfo)
    {
        this.gtlitecore$source = recipe;
        this.gtlitecore$inPrepare = true;

        Recipe cached = this.gtlitecore$prepared;
        if (cached == null) return;
        if (!gtlitecore$cacheMatches(recipe)) return;

        int ttl = gtlitecore$overclockIsStable() ? gtlitecore$CACHE_TICKS
                : gtlitecore$DYNAMIC_CACHE_TICKS;
        if (gtlitecore$metaTileEntity().getOffsetTimer() - this.gtlitecore$preparedAt >= ttl)
        {
            gtlitecore$clear();
            return;
        }

        // Mirror the checks the skipped pipeline performs, so a hit equals recomputation.
        if (!cached.matches(false, importInventory, importFluids)) return;
        if (!hasEnoughPower(this.gtlitecore$preparedEUt, this.gtlitecore$preparedDuration)) return;
        if (!checkOutputSpaceItems(cached, getOutputInventory())) return;
        if (!checkOutputSpaceFluids(cached, getOutputTank())) return;
        if (!cached.matches(true, importInventory, importFluids)) return;

        this.isOutputsFull = false;
        this.ocResult.init(this.gtlitecore$preparedEUt, this.gtlitecore$preparedDuration,
                this.gtlitecore$preparedParallel, this.gtlitecore$preparedParallelEUt);
        setParallelRecipesPerformed(this.gtlitecore$preparedParallelPerformed);
        gtlitecore$metaTileEntity().addNotifiedInput(importInventory);
        setupRecipe(cached);
        callbackInfo.setReturnValue(true);
    }

    @Inject(method = "prepareRecipe(Lgregtech/api/recipes/Recipe;Lnet/minecraftforge/items/IItemHandlerModifiable;Lgregtech/api/capability/IMultipleTankHandler;)Z",
            at = @At("RETURN"))
    private void gtlitecore$endPrepare(Recipe recipe, IItemHandlerModifiable importInventory,
                                       IMultipleTankHandler importFluids,
                                       CallbackInfoReturnable<Boolean> callbackInfo)
    {
        this.gtlitecore$inPrepare = false;
    }

    @Inject(method = "setupAndConsumeRecipeInputs(Lgregtech/api/recipes/Recipe;Lnet/minecraftforge/items/IItemHandlerModifiable;Lgregtech/api/capability/IMultipleTankHandler;)Lgregtech/api/recipes/Recipe;",
            at = @At("RETURN"))
    private void gtlitecore$capturePreparedRecipe(Recipe recipe, IItemHandlerModifiable importInventory,
                                                  IMultipleTankHandler importFluids,
                                                  CallbackInfoReturnable<Recipe> callbackInfo)
    {
        Recipe result = callbackInfo.getReturnValue();
        Recipe source = this.gtlitecore$source;
        // Only the sub-tick path (extra parallels) is expensive enough to cache.
        if (result == null || !this.gtlitecore$inPrepare || source == null
                || result == source || this.ocResult.parallel() <= 1)
        {
            gtlitecore$clear();
            return;
        }

        this.gtlitecore$prepared = result;
        this.gtlitecore$cachedSource = source;
        this.gtlitecore$allowOverclocking = isAllowOverclocking();
        this.gtlitecore$maxVoltage = getMaxVoltage();
        this.gtlitecore$maxOverclockVoltage = getMaximumOverclockVoltage();
        this.gtlitecore$maintenanceProblems = gtlitecore$maintenanceProblems();
        this.gtlitecore$euDiscount = this.euDiscount;
        this.gtlitecore$speedBonus = this.speedBonus;
        this.gtlitecore$preparedEUt = this.ocResult.eut();
        this.gtlitecore$preparedDuration = this.ocResult.duration();
        this.gtlitecore$preparedParallel = this.ocResult.parallel();
        this.gtlitecore$preparedParallelPerformed = getParallelRecipesPerformed();
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
        if (this.gtlitecore$cachedSource != recipe) return false;
        return this.gtlitecore$allowOverclocking == isAllowOverclocking()
                && this.gtlitecore$maxVoltage == getMaxVoltage()
                && this.gtlitecore$maxOverclockVoltage == getMaximumOverclockVoltage()
                && this.gtlitecore$maintenanceProblems == gtlitecore$maintenanceProblems()
                && this.gtlitecore$euDiscount == this.euDiscount
                && this.gtlitecore$speedBonus == this.speedBonus;
    }

    @Unique
    private boolean gtlitecore$overclockIsStable()
    {
        return gtlitecore$STABLE_OVERCLOCK.computeIfAbsent(getClass(), c ->
                !gtlitecore$declaresBelow(c, "modifyOverclockPre", OCParams.class, RecipePropertyStorage.class)
                        && !gtlitecore$declaresBelow(c, "modifyOverclockPost", OCResult.class, RecipePropertyStorage.class)
                        && !gtlitecore$declaresBelow(c, "getNumberOfOCs", long.class)
                        && !gtlitecore$declaresBelow(c, "getOverclockForTier", long.class)
                        && !gtlitecore$declaresBelow(c, "isAllowOverclocking")
                        && !gtlitecore$declaresBelow(c, "subTickOC", OCResult.class, Recipe.class,
                        IItemHandlerModifiable.class, IMultipleTankHandler.class));
    }

    @Unique
    private static boolean gtlitecore$declaresBelow(Class<?> logicClass, String name, Class<?>... params)
    {
        for (Class<?> c = logicClass; c != null; c = c.getSuperclass())
        {
            if (c == AbstractRecipeLogic.class || c == MultiblockRecipeLogic.class) return false;
            try
            {
                c.getDeclaredMethod(name, params);
                return true;
            }
            catch (NoSuchMethodException ignored)
            {
                // keep walking up
            }
        }
        return false;
    }

    @Unique
    private long gtlitecore$maintenanceProblems()
    {
        MetaTileEntity mte = gtlitecore$metaTileEntity();
        if (!(mte instanceof IMaintenance)) return 0L;
        return ((IMaintenance) mte).getNumMaintenanceProblems();
    }

    @Unique
    private MetaTileEntity gtlitecore$metaTileEntity()
    {
        return ((MTETrait) (Object) this).getMetaTileEntity();
    }

    @Unique
    private void gtlitecore$clear()
    {
        this.gtlitecore$prepared = null;
        this.gtlitecore$cachedSource = null;
    }
}
