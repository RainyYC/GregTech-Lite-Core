package gregtechlite.gtlitecore.api.recipe.duck;

/**
 * Duck interface that carries the amount of parallel operations a {@code Recipe} was multiplied by, so chanced
 * outputs can be drawn once per entry instead of being expanded into {@code parallel} separate entries.
 */
public interface IChancedParallel
{
    int gtlite$getChancedParallel();

    void gtlite$setChancedParallel(int value);
}
