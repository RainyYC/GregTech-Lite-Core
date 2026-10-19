package gregtechlite.gtlitecore.core.debug

import gregtech.api.capability.IEnergyContainer
import gregtech.api.capability.impl.EnergyContainerList
import gregtech.api.metatileentity.multiblock.RecipeMapMultiblockController
import gregtech.api.recipes.RecipeMap
import gregtech.api.recipes.RecipeMaps
import gregtech.api.recipes.map.Branch
import gregtech.api.recipes.map.Either
import gregtechlite.gtlitecore.api.capability.logic.AdvancedAssemblyLineRecipeLogic
import gregtechlite.gtlitecore.common.metatileentity.multiblock.advanced.MultiblockAdvancedAssemblyLine
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.FMLCommonHandler
import net.minecraftforge.fml.common.event.FMLServerStartedEvent
import org.apache.logging.log4j.LogManager
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
