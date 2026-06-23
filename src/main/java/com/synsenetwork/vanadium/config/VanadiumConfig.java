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
    @Comment("Disable world parallelisation")
    public boolean disableWorld = false;

    @Comment("Disable world post tick parallelisation")
    public boolean disableWorldPostTick = false;

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

    //Debug
    @Comment("Enable chunk loading timeouts; this will forcibly kill any chunks that fail to load in sufficient time\n"
            + "May allow for loading of damaged/corrupted worlds")
    public boolean enableChunkTimeout = false;

    @Comment("Attempts to re-load timed out chunks; Seems to work")
    public boolean enableTimeoutRegen = false;

    @Comment("Simply returns a new empty chunk instead of a re-generating fully")
    public boolean enableBlankReturn = false;

    @Comment("Amount of workless iterations to wait before declaring a chunk load attempt as timed out\n"
            + "This is in ~100us iterations (plus minus yield time) so timeout >= timeoutCount * 100us")
    public int timeoutCount = 5000;

    @Comment("Maximum time between Vanadium presence alerts in 10ms steps")
    public int logCap = 720000;


    public enum ParaMaxMode {
        Standard,
        Override,
        Reduction
    }

    // Functions intended for usage
    ///////////////////////////////

    @Override
    public void validatePostLoad() throws ValidationException {
        if (paraMax >= -1)
            if (paraMaxMode == ParaMaxMode.Standard || paraMaxMode == ParaMaxMode.Override || paraMaxMode == ParaMaxMode.Reduction)
                if (timeoutCount >= 500 && timeoutCount <= 500000)
                    if (logCap >= 15000)
                        return;
        throw new ValidationException("Failed to validate Vanadium config.");
    }

    public static int getParallelism() {
        VanadiumConfig config = Vanadium.config;
        switch (config.paraMaxMode) {
            case Standard:
                return config.paraMax <= 1 ?
                        Runtime.getRuntime().availableProcessors() :
                        Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), config.paraMax));
            case Override:
                return config.paraMax <= 1 ?
                        Runtime.getRuntime().availableProcessors() :
                        Math.max(2, config.paraMax);
            case Reduction:
                return Math.max(
                        Runtime.getRuntime().availableProcessors() - Math.max(0, config.paraMax),
                        2);
        }
        // Unsure quite how this is "Reachable code" but ok I guess
        return Runtime.getRuntime().availableProcessors();
    }

}
