package gregtechlite.gtlitecore.api.recipe

import gregtech.api.GTValues
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Helpers that keep chanced outputs affordable when a recipe is multiplied by a very large parallel count.
 *
 * GT multiplies chanced outputs by expanding every entry once per parallel operation and then rolling every
 * expanded entry, which costs `O(parallel * entries)` in both allocation and randomness. The output inventory
 * can only absorb a bounded amount of items per cycle though, so only `min(successes, cap)` is observable and
 * the successes can be drawn from the underlying Bernoulli process directly, one geometric jump at a time.
 */
object ChancedOutputSampler
{
    /** GT rolls chances with a 1/10000 granularity (`ChancedOutputLogic#getMaxChancedValue`). */
    private const val MAX_CHANCE = 10_000

    /** Items beyond this cannot be observed through an output inventory, so the draw stops here. */
    const val MAX_EMIT_PER_ENTRY = 4096

    /** Headroom used by the saturation shortcut, in standard deviations of the binomial distribution. */
    private const val SIGMA_CUTOFF = 12.0

    /** Measured cost of one plain chance roll, used to pick the cheaper sampler. */
    private const val ROLL_NANOS_PER_TRIAL = 2.5

    /** Measured cost of one geometric jump (a logarithm), used to pick the cheaper sampler. */
    private const val JUMP_NANOS_PER_SUCCESS = 96.0

    /**
     * Draws the number of successes of `trials` independent rolls at `chance` (1/10000 granularity), stopping
     * once `cap` successes have been reached. The distribution is exactly `min(Binomial(trials, p), cap)`:
     * every iteration advances directly to the next success, so the loop runs `O(successes)` times instead of
     * `O(trials)` times.
     */
    fun cappedSuccesses(trials: Int, chance: Int, cap: Int = MAX_EMIT_PER_ENTRY): Int
    {
        if (trials <= 0 || cap <= 0 || chance <= 0) return 0
        if (chance >= MAX_CHANCE) return min(trials, cap)

        // `passesChance` accepts on `nextInt(10000) <= chance`, so the effective probability is (chance + 1) / 10000.
        val p = (chance + 1).toDouble() / MAX_CHANCE

        // When the expected amount is far above the cap the draw would always be saturated; the probability of a
        // shortfall is below 1e-33, which is far outside anything a player could ever observe.
        if (isSaturated(trials, p, cap)) return cap

        // Pick the cheaper sampler: rolling every trial costs ~2.5 ns per trial, while a geometric jump costs
        // ~96 ns per success. For anything but very low chances rolling is faster.
        val expected = trials.toDouble() * p
        if (trials * ROLL_NANOS_PER_TRIAL <= expected * JUMP_NANOS_PER_SUCCESS) return rollSuccesses(trials, chance, cap)

        val logFailure = ln(1.0 - p)
        var consumed = 0.0
        var successes = 0
        while (successes < cap)
        {
            val u = GTValues.RNG.nextDouble()
            // Geometric wait: floor(ln(U) / ln(1 - p)) + 1, so the mean is exactly 1 / p.
            consumed += if (u <= 0.0) 1.0 else floor(ln(1.0 - u) / logFailure) + 1.0
            if (consumed > trials) break
            successes++
        }
        return successes
    }

    private fun rollSuccesses(trials: Int, chance: Int, cap: Int): Int
    {
        var successes = 0
        var remaining = trials
        while (remaining-- > 0)
        {
            if (GTValues.RNG.nextInt(MAX_CHANCE) <= chance)
            {
                if (++successes >= cap) return cap
            }
        }
        return successes
    }

    /** Saturated `count * multiplier`, so a huge parallel count cannot overflow into a negative amount. */
    fun saturatedCount(count: Int, multiplier: Int): Int
    {
        val total = count.toLong() * multiplier.toLong()
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Saturated `count * multiplier` for fluid amounts (also int-backed). */
    fun saturatedAmount(count: Int, multiplier: Int): Int = saturatedCount(count, multiplier)

    private fun isSaturated(trials: Int, p: Double, cap: Int): Boolean
    {
        val expected = trials.toDouble() * p
        val sigma = sqrt(expected * (1.0 - p))
        return expected - SIGMA_CUTOFF * sigma > cap
    }
}
