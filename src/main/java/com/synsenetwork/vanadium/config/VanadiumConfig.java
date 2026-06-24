package com.synsenetwork.vanadium.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;
import com.synsenetwork.vanadium.Vanadium;

@Config(name = "vanadium")
public class VanadiumConfig implements ConfigData {
    // Actual config stuff
    //////////////////////

    // General
    @Comment("Globally disable all toggleable functionality")
    public boolean disabled = false;

    // Parallelism
    @Comment("Thread count config; In standard mode: will never create more threads than there are CPU threads (as that causeses Context switch churning)\n" +
            "Values <=1 are treated as 'all cores'")
    public int paraMax = -1;

    @Comment("""
            Other modes for paraMax
            Override: Standard but without the CoreCount Ceiling (So you can have 64k threads if you want)
            Reduction: Parallelism becomes Math.max(CoreCount-paramax, 2), if paramax is set to be -1, it's treated as 0
            Todo: add more"""
    )
    public ParaMaxMode paraMaxMode = ParaMaxMode.Standard;

    // World
    @Comment("Disable world parallel chunk loading")
    public boolean disableMultiChunk = false;

    // Entity
    @Comment("Disable entity parallelisation")
    public boolean disableEntity = false;

    // TE
    @Comment("Disable block entity parallelisation")
    public boolean disableBlockEntity = false;

    @Comment("Width and height, in chunks, of each automatic parallel cell (default 8). "
            + "Smaller cells = finer parallelism but more overhead. Takes effect next tick.")
    public int cellSize = 8;

    // Misc
    @Comment("Disable environment (plant ticks, etc.) parallelisation")
    public boolean disableEnvironment = false;

    @Comment("Disable parallelised chunk caching; doing this will result in much lower performance with little to no gain")
    public boolean disableChunkProvider = false;

    public enum ParaMaxMode {
        Standard,
        Override,
        Reduction
    }

    // Functions intended for usage
    ///////////////////////////////

    @Override
    public void validatePostLoad() throws ValidationException {
        if (paraMax < -1) {
            throw new ValidationException("paraMax must be >= -1 (got " + paraMax + ").");
        }
    }

    public static int getParallelism() {
        VanadiumConfig config = Vanadium.config;
        return switch (config.paraMaxMode) {
            case Standard -> config.paraMax <= 1 ?
                    Runtime.getRuntime().availableProcessors() :
                    Math.clamp(Runtime.getRuntime().availableProcessors(), 2, config.paraMax);
            case Override -> config.paraMax <= 1 ?
                    Runtime.getRuntime().availableProcessors() :
                    config.paraMax; // guarded above: paraMax is already >= 2 here
            case Reduction -> Math.max(
                    Runtime.getRuntime().availableProcessors() - Math.max(0, config.paraMax),
                    2);
        };
    }
}
