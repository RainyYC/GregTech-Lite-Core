package gregtechlite.gtlitecore.core.debug

import gregtech.api.capability.IDataAccessHatch
import gregtech.api.capability.IEnergyContainer
import gregtech.api.metatileentity.MetaTileEntity
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity
import gregtech.api.metatileentity.multiblock.AbilityInstances
import gregtech.api.metatileentity.multiblock.IMultiblockPart
import gregtech.api.metatileentity.multiblock.MultiblockAbility
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase
import gregtech.api.recipes.Recipe
import gregtech.api.recipes.ingredients.GTRecipeFluidInput
import gregtech.api.recipes.properties.impl.ResearchProperty
import gregtech.api.capability.impl.EnergyContainerList
import gregtech.api.capability.impl.AbstractRecipeLogic
import gregtech.api.capability.impl.MultiblockRecipeLogic
import gregtech.api.capability.impl.FluidTankList
import gregtech.api.capability.IMultipleTankHandler
import gregtech.api.capability.impl.ItemHandlerList
import gregtech.api.metatileentity.IVoidable
import gregtech.api.recipes.RecipeBuilder
import gregtech.api.recipes.logic.ParallelLogic
import gregtech.api.GTValues
import gregtech.api.pattern.BlockPattern
import gregtech.api.pattern.FactoryBlockPattern
import gregtech.client.renderer.ICubeRenderer
import gregtech.client.renderer.texture.Textures
import gregtech.api.util.GTTransferUtils
import gregtech.common.ConfigHolder
import gregtechlite.gtlitecore.api.capability.handler.QuantumStorageHandler
import gregtechlite.gtlitecore.common.metatileentity.multiblock.MultiblockSpaceElevator
import gregtech.api.metatileentity.multiblock.RecipeMapMultiblockController
import gregtech.api.recipes.RecipeMap
import gregtech.api.recipes.RecipeMaps
import gregtech.api.recipes.map.Branch
import gregtech.api.recipes.map.Either
import gregtechlite.gtlitecore.api.capability.logic.AdvancedAssemblyLineRecipeLogic
import gregtechlite.gtlitecore.common.metatileentity.multiblock.advanced.MultiblockAdvancedAssemblyLine
import net.minecraft.init.Blocks
import net.minecraft.item.Item
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.item.ItemStack
import gregtech.api.util.ItemStackHashStrategy
import gregtech.api.util.RelativeDirection
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.FMLCommonHandler
import net.minecraftforge.fml.common.event.FMLServerStartedEvent
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.FluidTankInfo
import net.minecraftforge.fluids.IFluidTank
import net.minecraftforge.items.IItemHandlerModifiable
import org.apache.logging.log4j.LogManager
import java.math.BigInteger
import java.util.Locale

/**
 * Throwaway dev-only benchmark for verifying the idle-load hypothesis of the
 * Advanced Assembly Line. Enabled with `-Dgtlitec.bench=true`; shuts the server
 * down by itself when finished.
 */
object AALIdleBench
{
    private val logger = LogManager.getLogger("GTLite-AALBench")

    fun onServerStarted(event: FMLServerStartedEvent)
    {
        if (!enabled()) return
        if (System.getProperty("gtlitec.bench.done", "false") == "true") return
        System.setProperty("gtlitec.bench.done", "true")

        logger.info("BENCH-START")
        Thread({
            try
            {
                runAll()
            }
            catch (t: Throwable)
            {
                logger.error("BENCH-FAILED", t)
            }
            finally
            {
                logger.info("BENCH-END")
                val server = FMLCommonHandler.instance().getMinecraftServerInstance()
                if (server != null)
                    server.addScheduledTask { server.initiateShutdown() }
            }
        }, "gtlite-aal-bench").apply { isDaemon = true }.start()
    }

    private fun enabled(): Boolean =
        System.getProperty("gtlitec.bench", "false") == "true" ||
        System.getenv("GTLITE_BENCH") == "true"

    private fun runAll()
    {
        val map = RecipeMaps.ASSEMBLY_LINE_RECIPES

        // A) Cost of the RecipeMap#getRecipeList getter itself.
        repeat(400) { map.recipeList }
        report("A/getRecipeList", sample(1000) { map.recipeList })
        logger.info("BENCH A/recipes size={} {}", map.recipeList.size, countTrie(map))

        // B) Per-tick cost of a synthetic idle machine (no structure needed).
        val controller = MultiblockAdvancedAssemblyLine(ResourceLocation("gtlitecore", "aal_bench"))
        val energyField = RecipeMapMultiblockController::class.java.getDeclaredField("energyContainer")
        energyField.isAccessible = true
        energyField.set(controller, EnergyContainerList(emptyList<IEnergyContainer>()))
        val logic = controller.recipeMapWorkable as AdvancedAssemblyLineRecipeLogic

        report("B/idle-pipeline.updateWorkable", sample(600, 100) { logic.updateWorkable() })

        // C) Same machine, working disabled -> expected ~0.
        logic.setWorkingEnabled(false)
        report("C/working-disabled.updateWorkable", sample(600, 100) { logic.updateWorkable() })
        logic.setWorkingEnabled(true)

        // D) Non-pipeline mode (GT default search path) for comparison.
        controller.isPipelineMode = false
        try
        {
            report("D/non-pipeline.updateWorkable", sample(300, 50) { logic.updateWorkable() })
        }
        catch (t: Throwable)
        {
            logger.warn("BENCH D/non-pipeline aborted: {}", t.toString())
        }
        controller.isPipelineMode = true

        // H) With materials present: scan, skip and last-recipe fast path.
        benchWithMaterials()

        // S) Space Elevator pattern rebuild + QuantumStorageHandler hot paths.
        benchElevatorAndStorage()

        // O) One-tick overclock (sub-tick parallel) per-tick cost.
        benchOneTickOverclock()

        // C) GTLite color channel search: per-search rebuild cost.
        benchChannelRebuild()

        // R) Sub-tick prepared-recipe cache A/B on the real prepare path.
        benchSubTickPrepare()

        // E) How many AL recipes actually pass the "bus count" gate with an empty machine?
        logger.info("BENCH E/empty-machine-visible={}", countVisibleWithNoBuses(map))

        // F) Trie traversal only (no dedupe/sort/list copy) for decomposition.
        val lookupField = RecipeMap::class.java.getDeclaredField("lookup").apply { isAccessible = true }
        val root = lookupField.get(map) as Branch
        repeat(200) { root.getRecipes(true).count() }
        report("F/trie-traversal-only", sample(500) { root.getRecipes(true).count() })

        // G) Scan of an already-built snapshot: the loop body itself, without list reconstruction.
        val snapshot = map.recipeList
        report("G/scan-prebuilt-list", sample(600, 50) {
            for (recipe in snapshot)
                if (recipe.inputs.isEmpty()) continue
        })
    }

    /**
     * Walks the ingredient trie of [map] and counts branch nodes and recipe leaves.
     */
    private fun countTrie(map: RecipeMap<*>): String
    {
        val lookupField = RecipeMap::class.java.getDeclaredField("lookup").apply { isAccessible = true }
        val root = lookupField.get(map) as Branch
        val nodeField = Branch::class.java.getDeclaredField("nodes").apply { isAccessible = true }
        val specialField = Branch::class.java.getDeclaredField("specialNodes").apply { isAccessible = true }

        var branches = 0
        var leaves = 0
        val stack = ArrayDeque<Branch>()
        stack.addLast(root)

        while (stack.isNotEmpty())
        {
            val branch = stack.removeLast()
            for (field in arrayOf(nodeField, specialField))
            {
                val entries = (field.get(branch) as Map<*, *>?) ?: continue
                for (value in entries.values)
                {
                    val either = value as Either<*, *>
                    if (either.left().isPresent)
                    {
                        leaves++
                    }
                    else
                    {
                        branches++
                        stack.addLast(either.right().get() as Branch)
                    }
                }
            }
        }
        return "trieBranches=$branches trieLeaves=$leaves"
    }

    /**
     * Mimics the early gate of [AdvancedAssemblyLineRecipeLogic.tryLockRecipe] on a machine
     * with no import bus at all, which is what the synthetic benchmark actually measures.
     */
    private fun countVisibleWithNoBuses(map: RecipeMap<*>): Int
    {
        var visible = 0
        for (recipe in map.recipeList)
        {
            if (recipe.inputs.isEmpty() || 0 < recipe.inputs.size) continue
            visible++
        }
        return visible
    }

    // region sub-tick prepare cache A/B

    private fun benchSubTickPrepare()
    {
        val controller = BenchMulti(ResourceLocation("gtlitecore", "bench_multi"))
        val energyField = RecipeMapMultiblockController::class.java.getDeclaredField("energyContainer")
        energyField.isAccessible = true
        energyField.set(controller, EnergyContainerList(listOf(FakeEnergy(GTValues.V[GTValues.UXV]))))
        val outputField = RecipeMapMultiblockController::class.java.getDeclaredField("outputInventory")
        outputField.isAccessible = true
        outputField.set(controller, VoidBus())
        val outputFluidField = RecipeMapMultiblockController::class.java.getDeclaredField("outputFluidInventory")
        outputFluidField.isAccessible = true
        outputFluidField.set(controller, FluidTankList(true, emptyList()))

        val logic = controller.recipeMapWorkable
        val recipe = RecipeMaps.MACERATOR_RECIPES.recipeList.firstOrNull {
            it.inputs.size == 1 && it.outputs.isNotEmpty() && it.fluidInputs.isEmpty()
        } ?: run {
            logger.warn("BENCH R skipped: no usable recipe")
            return
        }
        val template = recipe.inputs[0].inputStacks.firstOrNull { !it.isEmpty } ?: return
        val amountPerRun = recipe.inputs[0].amount
        val bus = BufferBus(template, 1 shl 22)
        val tanks = FluidTankList(true, emptyList())

        val method = AbstractRecipeLogic::class.java.getDeclaredMethod("setupAndConsumeRecipeInputs",
                Recipe::class.java, IItemHandlerModifiable::class.java, IMultipleTankHandler::class.java)
        method.isAccessible = true
        val switch = AbstractRecipeLogic::class.java.getDeclaredField("gtlitecore\$cacheEnabled")
        switch.isAccessible = true

        bus.refill()
        val prepared = method.invoke(logic, recipe, bus, tanks) as Recipe?
        logger.info("BENCH R/prepared sourceEUt={} duration={} preparedEUt={} outputs={}",
                recipe.eUt, prepared?.duration, prepared?.eUt, prepared?.allItemOutputs?.size)

        val run = { label: String, enabled: Boolean ->
            switch.setBoolean(null, enabled)
            bus.refill()
            method.invoke(logic, recipe, bus, tanks)
            repeat(200) { bus.refill(); method.invoke(logic, recipe, bus, tanks) }
            report(label, sample(400) { bus.refill(); method.invoke(logic, recipe, bus, tanks) })
        }

        run("R/prepare-off-1", false)
        run("R/prepare-on-1", true)
        run("R/prepare-off-2", false)
        run("R/prepare-on-2", true)

        verifySubTickCache(logic, method, switch, bus, tanks, recipe, amountPerRun, energyField, controller)
        switch.setBoolean(null, true)
    }

    private fun verifySubTickCache(logic: MultiblockRecipeLogic,
                                   method: java.lang.reflect.Method,
                                   switch: java.lang.reflect.Field,
                                   bus: BufferBus,
                                   tanks: FluidTankList,
                                   recipe: Recipe,
                                   amountPerRun: Int,
                                   energyField: java.lang.reflect.Field,
                                   controller: BenchMulti)
    {
        val baseEUt = recipe.eUt

        fun prepareOnce(): Pair<Recipe?, Long>
        {
            val before = bus.stacks[0].count
            val prepared = method.invoke(logic, recipe, bus, tanks) as Recipe?
            return prepared to (before - bus.stacks[0].count).toLong()
        }

        // 1) A cache hit must consume exactly what recomputation would.
        switch.setBoolean(null, false)
        bus.setCount(1 shl 20)
        prepareOnce()
        val (offRecipe, offConsumed) = prepareOnce()

        switch.setBoolean(null, true)
        bus.setCount(1 shl 20)
        prepareOnce()
        val (onRecipe, onConsumed) = prepareOnce()

        val consumptionMatches = offConsumed == onConsumed && onConsumed > 0
        val recipeMatches = offRecipe?.eUt == onRecipe?.eUt && offRecipe?.duration == onRecipe?.duration

        // 2) When inputs drop below the cached parallel amount the cache must fall back and the
        //    recomputed recipe must use fewer parallels.
        switch.setBoolean(null, true)
        bus.setCount(1 shl 20)
        prepareOnce()
        val cachedParallel = (onRecipe?.eUt ?: 0L) / baseEUt
        val smallParallel = 4L
        bus.setCount((smallParallel * amountPerRun).toInt())
        val (shrunkRecipe, shrunkConsumed) = prepareOnce()
        val shrankCorrectly = shrunkRecipe != null
                && shrunkRecipe.eUt < (onRecipe?.eUt ?: Long.MAX_VALUE)
                && shrunkConsumed <= smallParallel * amountPerRun
                && shrunkConsumed > 0

        // 3) A different energy container must invalidate the cache immediately, not after the TTL.
        switch.setBoolean(null, true)
        bus.setCount(1 shl 20)
        prepareOnce()
        energyField.set(controller, EnergyContainerList(listOf(FakeEnergy(GTValues.V[GTValues.IV]))))
        val (loweredRecipe, _) = prepareOnce()
        val voltageGuardWorks = loweredRecipe != null && loweredRecipe.eUt < (onRecipe?.eUt ?: Long.MAX_VALUE)
        energyField.set(controller, EnergyContainerList(listOf(FakeEnergy(GTValues.V[GTValues.UXV]))))

        logger.info("BENCH VERIFY-subtick consumeMatch={} (off={} on={}) sameRecipe={} shrank={} ({} <- {} parallels, consumed={}) voltageGuard={}",
                consumptionMatches, offConsumed, onConsumed, recipeMatches, shrankCorrectly,
                cachedParallel, smallParallel, shrunkConsumed, voltageGuardWorks)
    }

    /** Minimal multiblock only used by the benchmark to drive a real MultiblockRecipeLogic. */
    private class BenchMulti(id: ResourceLocation) : RecipeMapMultiblockController(id, RecipeMaps.MACERATOR_RECIPES)
    {
        override fun createMetaTileEntity(te: IGregTechTileEntity): MetaTileEntity = BenchMulti(metaTileEntityId)
        override fun updateFormedValid() {}
        override fun createStructurePattern(): BlockPattern = FactoryBlockPattern.start()
                .aisle("S")
                .where('S', selfPredicate())
                .build()
        override fun getBaseTexture(sourcePart: IMultiblockPart?): ICubeRenderer = Textures.SOLID_STEEL_CASING
        override fun getFrontOverlay(): ICubeRenderer = Textures.SOLID_STEEL_CASING
    }

    // endregion

    // region color channel search probes

    /**
     * Rebuild cost of the per-channel caches that
     * MixinMultiblockRecipeLogic#trySearchNewRecipeByChannel() redoes on every recipe search.
     */
    private fun benchChannelRebuild()
    {
        val map = RecipeMaps.MACERATOR_RECIPES
        val recipe = map.recipeList.firstOrNull { it.inputs.size == 1 } ?: run {
            logger.warn("BENCH C skipped: no single-input recipe")
            return
        }
        val template = recipe.inputs[0].inputStacks.firstOrNull { !it.isEmpty } ?: run {
            logger.warn("BENCH C skipped: recipe has no concrete input")
            return
        }

        for (slotsPerBus in intArrayOf(16, 64))
        {
            val buses = List(4) { FakeBusOf(slotsPerBus).apply { setStackInSlot(0, template.copy()) } }
            val tanks = List(2) { FakeTank() }
            val prefix = "C/bus${slotsPerBus}x4"

            report("$prefix/ItemHandlerList", sample(200, 20) { ItemHandlerList(buses) })
            report("$prefix/FluidTankList", sample(500, 50) { FluidTankList(true, tanks) })

            val items = ItemHandlerList(buses)
            val fluids = FluidTankList(true, tanks)
            report("$prefix/previousRecipe.matches", sample(500, 50) {
                recipe.matches(false, items, fluids)
            })
            report("$prefix/AbilityInstances+cast", sample(2000, 200) {
                val instances = AbilityInstances(MultiblockAbility.IMPORT_ITEMS)
                instances.add(buses[0])
                instances.cast<Any>()
            })
        }

        // The per-part scan itself: 40 parts, each with an abilities list to inspect.
        val partAbilities = List(40) { listOf(MultiblockAbility.IMPORT_ITEMS) }
        report("C/part-scan-40", sample(500, 50) {
            var itemCount = 0
            for (abilities in partAbilities)
                if (abilities.contains(MultiblockAbility.IMPORT_ITEMS)) itemCount++
        })
    }

    private class FakeBusOf(private val slotCount: Int) : IItemHandlerModifiable
    {
        private val stacks = Array(slotCount) { ItemStack.EMPTY }
        override fun getSlots() = stacks.size
        override fun getStackInSlot(slot: Int) = stacks[slot]
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean) = stack
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean) = ItemStack.EMPTY
        override fun getSlotLimit(slot: Int) = Int.MAX_VALUE
        override fun setStackInSlot(slot: Int, stack: ItemStack) { stacks[slot] = stack }
    }

    // endregion

    // region one-tick overclock probes

    private fun benchOneTickOverclock()
    {
        val map = RecipeMaps.MACERATOR_RECIPES
        val input = ItemStack(Item.getItemFromBlock(Blocks.COBBLESTONE), 1, 0)
        val output = ItemStack(Item.getItemFromBlock(Blocks.SAND), 1, 0)
        val recipe = map.recipeBuilder()
                .inputs(input)
                .outputs(output)
                .duration(1)
                .EUt(30L)
                .build().result ?: run {
            logger.warn("BENCH O skipped: could not build a recipe")
            return
        }

        val inputBus = BufferBus(input, 1 shl 20)
        val outputBus = VoidBus()
        val inputFluids = FluidTankList(true, emptyList())
        val outputFluids = FluidTankList(true, emptyList())
        val voidable = object : IVoidable
        {
            override fun canVoidRecipeItemOutputs() = false
            override fun canVoidRecipeFluidOutputs() = false
        }
        val maxVoltage = Long.MAX_VALUE / 8

        // Per-tick parallel search, the core of a 1-tick overclocked recipe.
        for (parallel in intArrayOf(1, 16, 64, 256, 1024))
        {
            report("O/doParallel-$parallel", sample(30, 3) {
                ParallelLogic.doParallelRecipes(recipe, map, inputBus, inputFluids, outputBus, outputFluids,
                        parallel, maxVoltage, voidable)
            })
        }

        // The rest of one tick: recipe copy, output space check, input consumption.
        // (RecipeBuilder(recipe, map) cannot be used here: its fluent methods cast `this` to the
        // map's concrete builder type, which throws for a base RecipeBuilder instance.)
        report("O/recipeCopy", sample(50, 5) { recipe.copy() })

        val builder = ParallelLogic.doParallelRecipes(recipe, map, inputBus, inputFluids, outputBus,
                outputFluids, 1024, maxVoltage, voidable)
        val multiplied = builder?.build()?.result
        if (multiplied == null)
        {
            logger.warn("BENCH O: 1024 parallel did not produce a recipe")
            return
        }
        logger.info("BENCH O/multiplied parallel={} outputsListSize={} eut={} duration={}",
                builder.parallel, multiplied.allItemOutputs.size, multiplied.eUt, multiplied.duration)
        report("O/outputSpaceCheck-1024", sample(50, 5) {
            GTTransferUtils.addItemsToItemHandler(outputBus, true, multiplied.allItemOutputs)
        })
        report("O/consumeInputs-1024", sample(50, 5) {
            inputBus.refill()
            multiplied.matches(true, inputBus, inputFluids)
        })
    }

    /** Input handler holding a large amount of one stack, refilled between samples. */
    private class BufferBus(private val template: ItemStack, private val count: Int) : IItemHandlerModifiable
    {
        val stacks = Array(4) { ItemStack.EMPTY }
        init { refill() }
        fun refill() { setCount(count) }
        fun setCount(newCount: Int)
        {
            stacks[0] = if (newCount <= 0) ItemStack.EMPTY
            else template.copy().also { it.count = newCount }
        }
        override fun getSlots() = stacks.size
        override fun getStackInSlot(slot: Int) = stacks[slot]
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean) = stack
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack
        {
            val stack = stacks[slot]
            if (stack.isEmpty) return ItemStack.EMPTY
            val taken = minOf(amount, stack.count)
            if (!simulate)
            {
                if (taken >= stack.count) stacks[slot] = ItemStack.EMPTY
                else stack.shrink(taken)
            }
            return stack.copy().also { it.count = taken }
        }
        override fun getSlotLimit(slot: Int) = Int.MAX_VALUE
        override fun setStackInSlot(slot: Int, stack: ItemStack) { stacks[slot] = stack }
    }

    /** Output handler that accepts everything, so only the simulation cost is measured. */
    private class VoidBus : IItemHandlerModifiable
    {
        override fun getSlots() = 4
        override fun getStackInSlot(slot: Int) = ItemStack.EMPTY
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean) = ItemStack.EMPTY
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean) = ItemStack.EMPTY
        override fun getSlotLimit(slot: Int) = Int.MAX_VALUE
        override fun setStackInSlot(slot: Int, stack: ItemStack) {}
    }

    // endregion

    // region elevator / storage probes

    private fun benchElevatorAndStorage()
    {
        // S1: the elevator rebuilds its 52k position pattern every 5s while unformed.
        val elevator = MultiblockSpaceElevator(ResourceLocation("gtlitecore", "bench_space_elevator"))
        report("S1/elevator-pattern-build-normal", sample(30, 3) { elevator.reinitializeStructurePattern() })

        val extendedField = MultiblockSpaceElevator::class.java.getDeclaredField("isExtended")
        extendedField.isAccessible = true
        extendedField.setBoolean(elevator, true)
        report("S2/elevator-pattern-build-extended", sample(20, 2) { elevator.reinitializeStructurePattern() })
        benchOffsetMath()

        // Q: QuantumStorageHandler hot paths at a realistic number of distinct entries.
        for (distinct in intArrayOf(200, 500, 1000))
        {
            val handler = newItemStorage(distinct)
            report("Q-serialize/" + distinct, sample(30, 3) { handler.serialize() })
            report("Q-totalStored/" + distinct, sample(30, 3) { handler.totalStored() })
            report("Q-insertExisting/" + distinct, sample(200, 20) {
                handler.insert(ItemStack(Item.getItemFromBlock(Blocks.STONE), 1, distinct - 1), BigInteger.ONE)
            })
            report("Q-exportScan/" + distinct, sample(30, 3) { exportScan(handler) })
            report("Q-currentAmount/" + distinct, sample(2000, 200) {
                handler.currentAmount(ItemStack(Item.getItemFromBlock(Blocks.STONE), 1, distinct - 1))
            })
        }
        verifyStorage()
    }

    /**
     * Research data does not change the item/fluid fingerprint, so the lookup must be invalidated
     * by GT's notified handler lists: inserting a data orb has to re-lock in the same tick.
     */
    private fun verifyResearchNotification(voltage: Long)
    {
        if (!ConfigHolder.machines.enableResearch)
        {
            logger.warn("BENCH VERIFY-research skipped: research is disabled")
            return
        }

        val research = RecipeMaps.ASSEMBLY_LINE_RECIPES.recipeList.firstOrNull { recipe ->
            recipe.hasProperty(ResearchProperty.getInstance())
                    && recipe.inputs.isNotEmpty() && recipe.inputs.size <= 8
                    && recipe.fluidInputs.size <= 4
                    && recipe.inputs.all { it.inputStacks.isNotEmpty() }
                    && recipe.fluidInputs.all { it is GTRecipeFluidInput }
        } ?: run {
            logger.warn("BENCH VERIFY-research skipped: no research recipe")
            return
        }

        val hatch = FakeDataHatch(false)
        val controller = newController("aal_bench_research", voltage, hatch)
        val buses = List(research.inputs.size) { FakeBus() }
        val tanks = List(research.fluidInputs.size) { FakeTank() }
        injectBuses(controller, buses)
        injectTanks(controller, tanks)
        loadInputs(research, buses, tanks)
        val logic = controller.recipeMapWorkable as AdvancedAssemblyLineRecipeLogic

        logic.updateWorkable()
        val blockedWhileUnavailable = lockedRecipe(logic) == null

        hatch.available = true
        logic.updateWorkable()
        val blockedWithoutNotification = lockedRecipe(logic) == null

        controller.addNotifiedInput(buses[0])
        logic.updateWorkable()
        val lockedAfterNotification = lockedRecipe(logic) === research

        logger.info("BENCH VERIFY-research blockedWhileUnavailable={} blockedWithoutNotify={} lockedAfterNotify={}",
                blockedWhileUnavailable, blockedWithoutNotification, lockedAfterNotification)
    }

    private fun verifyStorage()
    {
        val handler = QuantumStorageHandler<ItemStack>(64, BigInteger.TEN.pow(6),
            strategy = ItemStackHashStrategy.comparingAllButCount(),
            writeType = { tag, stack ->
                val nbt = NBTTagCompound()
                stack.writeToNBT(nbt)
                tag.setTag("item", nbt)
            },
            readType = { tag -> ItemStack(tag.getCompoundTag("item")).also { it.setCount(1) } })

        val stone0 = ItemStack(Item.getItemFromBlock(Blocks.STONE), 1, 0)
        val stone0Big = ItemStack(Item.getItemFromBlock(Blocks.STONE), 64, 0)
        val stone1 = ItemStack(Item.getItemFromBlock(Blocks.STONE), 1, 1)

        handler.insert(stone0, BigInteger.TEN)
        val merged = handler.insert(stone0Big, BigInteger.TEN).toLong()      // same type, "+10"
        handler.insert(stone1, BigInteger.TEN)                               // different damage -> new entry
        val afterInsert = handler.distinctSlots() == 2 && handler.totalStored().toLong() == 30L

        val removed = handler.extract(stone0Big, BigInteger.valueOf(4L)).toLong()
        val afterExtract = removed == 4L && handler.currentAmount(stone0).toLong() == 16L
                && handler.totalStored().toLong() == 26L

        val tag = handler.serialize()
        handler.deserialize(tag)
        val afterRoundTrip = handler.distinctSlots() == 2 && handler.totalStored().toLong() == 26L
                && handler.currentAmount(stone0).toLong() == 16L

        val shrunk = QuantumStorageHandler<ItemStack>(64, BigInteger.valueOf(20L),
            strategy = ItemStackHashStrategy.comparingAllButCount(),
            writeType = { tag, stack ->
                val nbt = NBTTagCompound()
                stack.writeToNBT(nbt)
                tag.setTag("item", nbt)
            },
            readType = { tag -> ItemStack(tag.getCompoundTag("item")).also { it.setCount(1) } })
        shrunk.deserialize(tag)
        val rebuildRemoved = shrunk.rebuild(1, BigInteger.TEN)
        val afterRebuild = rebuildRemoved && shrunk.distinctSlots() == 1

        logger.info("BENCH VERIFY insert={} (merged={}) extract={} roundTrip={} rebuild={}",
                afterInsert, merged == 10L, afterExtract, afterRoundTrip, afterRebuild)
    }

    /** Non-world part of one full structure match: relative offset math + a BlockPos per position. */
    private fun benchOffsetMath()
    {
        val dirs = arrayOf(RelativeDirection.RIGHT, RelativeDirection.DOWN, RelativeDirection.FRONT)
        var sink = 0L
        report("S3/pattern-position-math-52675", sample(30, 3) {
            for (c in 0 until 35)
                for (b in 0 until 43)
                    for (a in 0 until 35)
                        sink += RelativeDirection.setActualRelativeOffset(a, b, c, EnumFacing.NORTH,
                                EnumFacing.UP, false, dirs).toLong()
        })
        logger.info("BENCH S3/sink={}", sink)
    }

    private fun newItemStorage(distinct: Int): QuantumStorageHandler<ItemStack>
    {
        val handler = QuantumStorageHandler<ItemStack>(4096, BigInteger.TEN.pow(12),
            strategy = ItemStackHashStrategy.comparingAllButCount(),
            writeType = { tag, stack ->
                val nbt = NBTTagCompound()
                stack.writeToNBT(nbt)
                tag.setTag("item", nbt)
            },
            readType = { tag -> ItemStack(tag.getCompoundTag("item")).also { it.setCount(1) } })
        for (i in 0 until distinct)
        {
            val stack = ItemStack(Item.getItemFromBlock(Blocks.STONE), 1, i)
            handler.insert(stack, BigInteger.valueOf(1000))
        }
        return handler
    }

    /** What MultiblockQuantumChest#exportItems does every second, minus the actual insertion. */
    private fun exportScan(handler: QuantumStorageHandler<ItemStack>): Int
    {
        var moved = 0
        // Mirrors the patched export loop: one snapshot, no per-entry re-lookup.
        for ((stack, available) in handler.entries())
            if (available.signum() > 0) moved += stack.count
        return moved
    }

    // endregion

    // region with-materials scenarios

    private fun benchWithMaterials()
    {
        val map = RecipeMaps.ASSEMBLY_LINE_RECIPES
        val voltage = Long.MAX_VALUE / 8

        // H1: imports present but matching nothing -> forces the full scan path.
        val h1 = newController("aal_bench_h1", voltage)
        val buses1 = List(8) { FakeBus() }
        buses1[0].stacks[0] = ItemStack(Item.getItemFromBlock(Blocks.STONE), 1)
        injectBuses(h1, buses1)
        val logic1 = h1.recipeMapWorkable as AdvancedAssemblyLineRecipeLogic

        var toggle = 0
        report("H1a/full-scan-no-match", sample(100, 5) {
            toggle++
            buses1[0].stacks[0].count = 1 + (toggle % 2)   // 改签名，强制重扫
            logic1.updateWorkable()
        })
        report("H1b/skip-unchanged-inputs", sample(600, 100) { logic1.updateWorkable() })

        // H2/H3: recipes that can actually be locked (fake buses + fake tanks).
        val recipes = pickRecipes(map, voltage, 2)
        if (recipes.isEmpty())
        {
            logger.warn("BENCH H2/H3 skipped: no usable recipe")
            return
        }
        val a = recipes[0]
        val b = if (recipes.size > 1) recipes[1] else a

        val h2 = newController("aal_bench_h2", voltage)
        val busCount = maxOf(a.inputs.size, b.inputs.size)
        val tankCount = maxOf(a.fluidInputs.size, b.fluidInputs.size)
        val buses2 = List(busCount) { FakeBus() }
        val tanks2 = List(tankCount) { FakeTank() }
        injectBuses(h2, buses2)
        injectTanks(h2, tanks2)
        loadInputs(a, buses2, tanks2)
        val logic2 = h2.recipeMapWorkable as AdvancedAssemblyLineRecipeLogic

        val start = System.nanoTime()
        logic2.updateWorkable()
        logger.info("BENCH H2a/first-lock={}us items={} fluids={} locked={}",
                us((System.nanoTime() - start).toDouble()), a.inputs.size, a.fluidInputs.size,
                lockedRecipe(logic2) === a)

        // Isolate the three costs: overclock math, last-recipe fast path, full scan.
        val tryLock: java.lang.reflect.Method = AdvancedAssemblyLineRecipeLogic::class.java
                .getDeclaredMethod("tryLockRecipe").apply { isAccessible = true }
        val applyRecipe: java.lang.reflect.Method = AdvancedAssemblyLineRecipeLogic::class.java
                .getDeclaredMethod("applyRecipe", Recipe::class.java).apply { isAccessible = true }

        report("H2b/applyRecipe-only", sample(600, 50) { applyRecipe.invoke(logic2, a) })
        report("H2c/last-recipe-fast-path", sample(600, 50) { tryLock.invoke(logic2) })

        // H3: switch the job while no pipeline is in flight; the memo must not block the scan.
        loadInputs(a, buses2, tanks2)
        tryLock.invoke(logic2)
        var switched = false
        val switchCost = sample(200, 10) {
            loadInputs(b, buses2, tanks2)
            tryLock.invoke(logic2)
            switched = lockedRecipe(logic2) === b
            loadInputs(a, buses2, tanks2)
            tryLock.invoke(logic2)
        }
        report("H3/switch-recipe", switchCost)
        logger.info("BENCH H3/switched-to-next-recipe={} (a===b: {})", switched, a === b)

        verifyResearchNotification(voltage)

        // H4: no match at all -> first call scans, repeats are skipped by the fingerprint memo.
        val h4 = newController("aal_bench_h4", voltage)
        val buses4 = List(8) { FakeBus() }
        buses4[0].stacks[0] = ItemStack(Item.getItemFromBlock(Blocks.STONE), 1)
        injectBuses(h4, buses4)
        val logic4 = h4.recipeMapWorkable as AdvancedAssemblyLineRecipeLogic
        var toggle4 = 0
        report("H4a/forced-rescan", sample(100, 5) {
            toggle4++
            buses4[0].stacks[0].count = 1 + (toggle4 % 2)
            tryLock.invoke(logic4)
        })
        report("H4b/memo-skip", sample(600, 100) { tryLock.invoke(logic4) })
    }

    private fun pickRecipes(map: RecipeMap<*>, voltage: Long, count: Int): List<Recipe>
    {
        val picked = ArrayList<Recipe>(count)
        var total = 0
        var inputSize = 0
        var fluidSize = 0
        var research = 0
        var stacks = 0
        var fluidType = 0
        for (recipe in map.recipeList)
        {
            if (picked.size >= count) break
            total++
            if (recipe.inputs.size !in 1..8) continue
            inputSize++
            if (recipe.fluidInputs.size > 4) continue
            fluidSize++
            if (recipe.eUt > voltage) continue
            if (recipe.hasProperty(ResearchProperty.getInstance())) research++
            if (recipe.inputs.any { it.inputStacks.isEmpty() }) { stacks++; continue }
            if (recipe.fluidInputs.any { it !is GTRecipeFluidInput }) { fluidType++; continue }
            if (picked.any { it == recipe }) continue
            // skip recipes whose item signature collides with an already picked one
            val sameInputs = picked.any { p ->
                p.inputs.size == recipe.inputs.size &&
                p.inputs.indices.all { p.inputs[it].inputStacks.size == recipe.inputs[it].inputStacks.size }
            }
            if (sameInputs) continue
            picked.add(recipe)
        }
        logger.info("BENCH picker: total={} inputSize={} fluidSize={} research={} emptyStacks={} fluidType={} picked={}",
                total, inputSize, fluidSize, research, stacks, fluidType, picked.size)
        return picked
    }

    private fun loadInputs(recipe: Recipe, buses: List<FakeBus>, tanks: List<FakeTank>)
    {
        for (bus in buses) bus.stacks[0] = ItemStack.EMPTY
        for (tank in tanks) tank.set(null)
        for (i in recipe.inputs.indices)
        {
            val stack = recipe.inputs[i].inputStacks.first().copy()
            stack.count = maxOf(recipe.inputs[i].amount, 1)
            buses[i].stacks[0] = stack
        }
        for (i in recipe.fluidInputs.indices)
        {
            val fluid = (recipe.fluidInputs[i] as GTRecipeFluidInput).inputFluidStack
            tanks[i].set(FluidStack(fluid.fluid, fluid.amount))
        }
    }

    private fun lockedRecipe(logic: AdvancedAssemblyLineRecipeLogic): Recipe?
    {
        val field = AdvancedAssemblyLineRecipeLogic::class.java.getDeclaredField("currentRecipe")
        field.isAccessible = true
        return field.get(logic) as Recipe?
    }

    private fun injectTanks(controller: MultiblockAdvancedAssemblyLine, tanks: List<IFluidTank>)
    {
        val field = MultiblockControllerBase::class.java.getDeclaredField("multiblockAbilities")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val abilities = field.get(controller)
                as MutableMap<MultiblockAbility<*>, AbilityInstances>
        val instances = AbilityInstances(MultiblockAbility.IMPORT_FLUIDS)
        for (tank in tanks) instances.add(tank)
        abilities[MultiblockAbility.IMPORT_FLUIDS] = instances
    }

    private fun newController(id: String, voltage: Long,
                              hatch: FakeDataHatch = FakeDataHatch()): MultiblockAdvancedAssemblyLine
    {
        val controller = MultiblockAdvancedAssemblyLine(ResourceLocation("gtlitecore", id))
        val energyField = RecipeMapMultiblockController::class.java.getDeclaredField("energyContainer")
        energyField.isAccessible = true
        energyField.set(controller, EnergyContainerList(listOf(FakeEnergy(voltage))))
        val abilitiesField = MultiblockControllerBase::class.java.getDeclaredField("multiblockAbilities")
        abilitiesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val abilities = abilitiesField.get(controller)
                as MutableMap<MultiblockAbility<*>, AbilityInstances>
        val hatches = AbilityInstances(MultiblockAbility.DATA_ACCESS_HATCH)
        hatches.add(hatch)
        abilities[MultiblockAbility.DATA_ACCESS_HATCH] = hatches
        return controller
    }

    private fun injectBuses(controller: MultiblockAdvancedAssemblyLine,
                            buses: List<IItemHandlerModifiable>)
    {
        val field = MultiblockControllerBase::class.java.getDeclaredField("multiblockAbilities")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val abilities = field.get(controller)
                as MutableMap<MultiblockAbility<*>, AbilityInstances>
        val instances = AbilityInstances(MultiblockAbility.IMPORT_ITEMS)
        for (bus in buses) instances.add(bus)
        abilities[MultiblockAbility.IMPORT_ITEMS] = instances
    }

    /** Minimal item handler; writes are ignored so the inventory stays stable while measuring. */
    private class FakeBus : IItemHandlerModifiable
    {
        val stacks = Array(4) { ItemStack.EMPTY }
        override fun getSlots() = stacks.size
        override fun getStackInSlot(slot: Int) = stacks[slot]
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean) = stack
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean) = ItemStack.EMPTY
        override fun getSlotLimit(slot: Int) = 64
        override fun setStackInSlot(slot: Int, stack: ItemStack) {}
    }

    private class FakeDataHatch(var available: Boolean = true) : IDataAccessHatch
    {
        override fun isRecipeAvailable(recipe: Recipe, seen: MutableCollection<IDataAccessHatch>) = available
        override fun isCreative() = false
    }

    private class FakeTank : IFluidTank
    {
        private var fluid: FluidStack? = null
        fun set(stack: FluidStack?) { fluid = stack }
        override fun getFluid() = fluid
        override fun getFluidAmount() = fluid?.amount ?: 0
        override fun getCapacity() = Int.MAX_VALUE
        override fun getInfo() = FluidTankInfo(fluid, getCapacity())
        override fun fill(resource: FluidStack?, doFill: Boolean) = 0
        override fun drain(maxDrain: Int, doDrain: Boolean): FluidStack?
        {
            val current = fluid ?: return null
            val drained = minOf(maxDrain, current.amount)
            if (doDrain && drained > 0) fluid = if (drained >= current.amount) null
            else FluidStack(current.fluid, current.amount - drained)
            return FluidStack(current.fluid, drained)
        }
    }

    private class FakeEnergy(private val voltage: Long) : IEnergyContainer
    {
        override fun acceptEnergyFromNetwork(side: EnumFacing?, voltage: Long, amperage: Long) = 0L
        override fun inputsEnergy(side: EnumFacing?) = false
        override fun changeEnergy(differenceAmount: Long) = 0L
        override fun getEnergyStored() = Long.MAX_VALUE / 8
        override fun getEnergyCapacity() = Long.MAX_VALUE / 8
        override fun getInputAmperage() = 2L
        override fun getInputVoltage() = voltage
    }

    // endregion

    private fun sample(times: Int, warmup: Int = 0, body: () -> Unit): LongArray
    {
        repeat(warmup) { body() }
        val samples = LongArray(times)
        for (i in 0 until times)
        {
            val start = System.nanoTime()
            body()
            samples[i] = System.nanoTime() - start
        }
        samples.sort()
        return samples
    }

    private fun report(name: String, samples: LongArray)
    {
        var sum = 0L
        for (value in samples) sum += value
        val mean = sum.toDouble() / samples.size
        val p50 = samples[samples.size / 2].toDouble()
        val p95 = samples[(samples.size * 95) / 100].toDouble()
        val max = samples.last().toDouble()
        logger.info("BENCH {} n={} mean={}us p50={}us p95={}us max={}us",
                name, samples.size, us(mean), us(p50), us(p95), us(max))
    }

    private fun us(nanos: Double): String = String.format(Locale.ROOT, "%.2f", nanos / 1000.0)
}
