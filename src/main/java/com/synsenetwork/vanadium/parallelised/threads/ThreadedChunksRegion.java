package com.synsenetwork.vanadium.parallelised.threads;

import java.util.concurrent.*;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import com.synsenetwork.vanadium.Vanadium;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.synsenetwork.vanadium.parallelised.threads.VanadiumThreads.*;

public class ThreadedChunksRegion implements ConfigData {
    private String name;
    public int x1, z1, x2, z2;
    public boolean multiThreadChunkTick = false;
    public boolean multiThreadEntityTick = false;
    public boolean multiThreadBlockEntityTick = false;
    public String worldId;

    @ConfigEntry.Gui.Excluded
    private transient Executor singleThreadExecutor;

    @ConfigEntry.Gui.Excluded
    private transient Executor serialExecutor;

    public void setAssignedCpuCore(int assignedCpuCore) {
        this.assignedCpuCore = assignedCpuCore;
    }

    @ConfigEntry.Gui.Excluded
    private transient int assignedCpuCore = -1;

    @ConfigEntry.Gui.Excluded
    private transient String source;

    @ConfigEntry.Gui.Excluded
    private transient Phaser chunkTickPhaser = new Phaser(1);

    @ConfigEntry.Gui.Excluded
    private transient Phaser entityTickPhaser = new Phaser(1);

    @ConfigEntry.Gui.Excluded
    private transient Phaser blockEntityTickPhaser = new Phaser(1);

    @ConfigEntry.Gui.Excluded
    private static final Logger LOGGER = LogManager.getLogger(ThreadedChunksRegion.class);


    public ThreadedChunksRegion() {
        // Default constructor required for serialization
        this.source = "config"; // Default source
    }

    public ThreadedChunksRegion(String name, String worldId, int x1, int z1, int x2, int z2) {
        this(name, worldId, x1, z1, x2, z2, "config");
    }

    public ThreadedChunksRegion(String name, String worldId, int x1, int z1, int x2, int z2, String source) {
        this.name = name;
        this.worldId = worldId;
        this.x1 = Math.min(x1, x2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.z2 = Math.max(z1, z2);
        this.source = source;
    }

    public void initializePhaser() {
        // Initialize phaser with 1 party (the main thread)
        this.chunkTickPhaser = new Phaser(1);
        this.entityTickPhaser = new Phaser(1);
        this.blockEntityTickPhaser = new Phaser(1);
    }


    public Executor getSingleThreadExecutor() {
        if (singleThreadExecutor == null) {
            String poolType = System.getProperty("VANADIUM_SINGLE_POOL_TYPE", "platform").toLowerCase();
            switch (poolType) {
                case "virtual":
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedVirtualThreadFactory("Region-" + name + "-VirtualThread-"));
                    break;
                case "platform":
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformThreadFactory("Region-" + name + "-PlatformThread-"));
                    break;
                case "affinity":
//                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformAffinityThreadFactoryForRegion(this));
                    singleThreadExecutor = getAffinitySerialExecutor();
                    break;
                default:
                    Vanadium.LOGGER.warn("Invalid VANADIUM_SINGLE_POOL_TYPE: {}. Using default 'platform'.", poolType);
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformThreadFactory("Region-" + name + "-PlatformThread-"));
            }
        }
        return singleThreadExecutor;
    }

    public Executor getAffinitySerialExecutor() {
        if (serialExecutor == null) {
            serialExecutor = new SerialExecutor(GlobalAffinityThreadPool.getAffinityWorldAndRegionPool());
        }
        return serialExecutor;
    }

    public boolean contains(String worldId, int x, int z) {
        return this.worldId.equals(worldId) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    public Executor getChunkTickExecutor() {
        return multiThreadChunkTick ?
                GlobalAffinityThreadPool.getAffinitySharedPool() :
                getSingleThreadExecutor();
    }

    public Executor getEntityTickExecutor() {
        return multiThreadEntityTick ?
                GlobalAffinityThreadPool.getAffinitySharedPool() :
                getSingleThreadExecutor();
    }

    public Executor getBlockEntityTickExecutor() {
        return multiThreadBlockEntityTick ?
                GlobalAffinityThreadPool.getAffinitySharedPool() :
                getSingleThreadExecutor();
    }

    public boolean isMultiThreadChunkTick() {
        return multiThreadChunkTick;
    }

    public void setMultiThreadChunkTick(boolean value) {
        this.multiThreadChunkTick = value;
    }

    public boolean isMultiThreadEntityTick() {
        return multiThreadEntityTick;
    }

    public void setMultiThreadEntityTick(boolean value) {
        this.multiThreadEntityTick = value;
    }

    public boolean isMultiThreadBlockEntityTick() {
        return multiThreadBlockEntityTick;
    }

    public void setMultiThreadBlockEntityTick(boolean value) {
        this.multiThreadBlockEntityTick = value;
    }

    public void shutdownExecutors() {
        if (singleThreadExecutor != null) {
            if (assignedCpuCore != -1) {
                LOGGER.debug("Region {} releasing core {}", name, assignedCpuCore);
                CPUCoreManager.releaseCore(assignedCpuCore, "REGION");
                assignedCpuCore = -1;
            }
            singleThreadExecutor = null;
        }
    }

    // Getters remain the same
    public String getName() {
        return name;
    }

    public String getWorldId() {
        return worldId;
    }

    public int getX1() {
        return x1;
    }

    public int getZ1() {
        return z1;
    }

    public int getX2() {
        return x2;
    }

    public int getZ2() {
        return z2;
    }

    public String getSource() {
        return source != null ? source : "config"; // Fallback to "config" if null
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void postChunkTick() {
        // Wait for chunk tick phaser
        chunkTickPhaser.arriveAndAwaitAdvance();
    }

    public void postEntityTick() {
        // Ensure chunk stage has completed
        chunkTickPhaser.awaitAdvance(0);
        // Wait for entity tick phaser
        entityTickPhaser.arriveAndAwaitAdvance();
    }

    public void postBlockEntityTick() {
        // Ensure entity stage has completed
        entityTickPhaser.awaitAdvance(0);
        // Wait for block entity tick phaser
        blockEntityTickPhaser.arriveAndAwaitAdvance();
    }

    public long getArea() {
        // Calculate area of the region in chunks
        long width = Math.abs((long) x2 - x1) + 1;
        long height = Math.abs((long) z2 - z1) + 1;
        return width * height;
    }

    public Phaser getChunkTickPhaser() {
        return chunkTickPhaser;
    }

    public Phaser getEntityTickPhaser() {
        return entityTickPhaser;
    }

    public Phaser getBlockEntityTickPhaser() {
        return blockEntityTickPhaser;
    }
}