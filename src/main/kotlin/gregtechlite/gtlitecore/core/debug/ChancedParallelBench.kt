package gregtechlite.gtlitecore.core.debug

import gregtech.api.recipes.logic.OCParams
import gregtech.api.recipes.logic.OCResult
import gregtech.api.recipes.logic.OverclockingLogic
import gregtechlite.gtlitecore.api.LOGGER
import gregtechlite.gtlitecore.api.recipe.GTLiteRecipeMaps

/**
 * Dev-only bench: how expensive are chanced outputs when a recipe is run with (sub-tick) parallel?
 */
object ChancedParallelBench
{
    fun run()
    {
        val map = GTLiteRecipeMaps.MOB_COLLECTING_RECIPES
        val recipe = map.recipeList.maxByOrNull { it.chancedOutputs.chancedEntries.size }
            ?.takeIf { it.chancedOutputs.chancedEntries.isNotEmpty() }
            ?: run { LOGGER.warn("BENCH chanced: no recipe with chanced outputs"); return }
        val chancedCount = recipe.chancedOutputs.chancedEntries.size
        val sampleStack = recipe.chancedOutputs.chancedEntries[0].ingredient
        LOGGER.info("BENCH chanced: entries={} baseOutputs={} inputs={}",
                chancedCount, recipe.outputs.size, recipe.inputs.size)

        // 1) how large does sub-tick overclocking make the parallel multiplier?
        for (ocAmount in intArrayOf(1, 2, 3, 4, 5, 6, 8))
        {
            val params = OCParams().also { it.initialize(480L, 200, ocAmount) }
            val result = OCResult()
            OverclockingLogic.subTickParallelOC(params, result, Long.MAX_VALUE, 0.25, 4.0)
            LOGGER.info("BENCH subTickOC oc={} -> eut={} duration={} parallel={} parallelEUt={}",
                    ocAmount, result.eut(), result.duration(), result.parallel(), result.parallelEUt())
        }

        // 2) cost of expanding a recipe to N parallel operations (this is what append()/chancedOutputsMultiply does)
        for (parallel in intArrayOf(16, 64, 256, 1024, 4096))
        {
            val entries = parallel * chancedCount
            repeat(100) { buildParallel(map, chancedCount, sampleStack, entries) }
            val started = System.nanoTime()
            val iterations = 300
            repeat(iterations) { buildParallel(map, chancedCount, sampleStack, entries) }
            LOGGER.info("BENCH build parallel={} chancedEntries={} -> {} ns/op",
                    parallel, entries, (System.nanoTime() - started) / iterations)
        }

        // 3) cost of rolling the chanced outputs of such a recipe (once per recipe start)
        for (parallel in intArrayOf(16, 64, 256, 1024, 4096))
        {
            val entries = parallel * chancedCount
            val built = buildParallel(map, chancedCount, sampleStack, entries)
            repeat(200) { built.getResultItemOutputs(3, 3, map) }
            val started = System.nanoTime()
            val iterations = 500
            repeat(iterations) { built.getResultItemOutputs(3, 3, map) }
            LOGGER.info("BENCH roll parallel={} chancedEntries={} -> {} ns/op",
                    parallel, entries, (System.nanoTime() - started) / iterations)
        }
    }

    private fun buildParallel(map: gregtech.api.recipes.RecipeMap<*>,
                              chancedCount: Int,
                              stack: net.minecraft.item.ItemStack,
                              entries: Int) =
            map.recipeBuilder().duration(1).EUt(30).also { builder ->
                repeat(entries) { builder.chancedOutput(stack.copy(), 2500, 0) }
            }.build().getResult()!!
}
