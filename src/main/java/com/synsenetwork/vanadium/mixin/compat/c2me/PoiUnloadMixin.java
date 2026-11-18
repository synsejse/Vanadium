package com.synsenetwork.vanadium.mixin.compat.c2me;

import com.mojang.serialization.Codec;
import com.synsenetwork.vanadium.tick.WorkerPool;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C2ME's POI unloading ({@code chunkSystem.allowPOIUnloading}, on by default) runs when a chunk downgrades below
 * its first status, on the server thread's executor: {@code c2me$unloadPoi} asks the merged
 * {@code c2me$shouldUnloadPoi}, then flushes the column and removes its sections from storage, holding no POI monitor
 * and telling nobody. That executor also runs while the server thread waits inside a parallel wave (serving a
 * worker's chunk load), where villagers on the workers may be claiming a ticket of that column: the claim could land
 * after the flush packed it, on a record about to be dropped. And the distance graph is never told: a village centre
 * in the column would stay a source until a nearby update, then vanish, where vanilla (which never unloads POI) keeps
 * it as long as it is occupied.
 * <p>
 * This adds two vetoes to C2ME's own answer, never overriding a "keep": while the current thread runs a parallel wave
 * (or is a worker), and while any section of the column is a village centre (an occupied village POI, the graph's
 * only kind of source; every vanilla type with tickets is a village type, so this also covers every claimed ticket).
 * A vetoed column simply stays loaded, as in vanilla, until C2ME asks again on a later downgrade of the chunk; the
 * request is not queued or replayed, since nothing here could tell whether that chunk loaded again in between. A
 * column that is removed therefore holds no source, and removing it changes no level in the graph.
 * <p>
 * The method comes from C2ME's chunk-system rewrite: {@code require = 0} for a C2ME without it (then nothing unloads
 * POI), and {@code SynchronisePlugin} fails the transformation if it exists but this did not bind. Priority above
 * C2ME's default, which merges it.
 */
@Mixin(value = PoiManager.class, priority = 1100)
public abstract class PoiUnloadMixin extends SectionStorage<PoiSection, PoiSection.Packed> {
    protected PoiUnloadMixin(SimpleRegionStorage storage, Codec<PoiSection.Packed> codec, Function<PoiSection, PoiSection.Packed> packer,
            BiFunction<PoiSection.Packed, Runnable, PoiSection> unpacker, Function<Runnable, PoiSection> factory, RegistryAccess registryAccess,
            ChunkIOErrorReporter errorReporter, LevelHeightAccessor levelHeightAccessor) {
        super(storage, codec, packer, unpacker, factory, registryAccess, errorReporter, levelHeightAccessor);
    }

    @Shadow
    private boolean isVillageCenter(long sectionPos) {
        throw new AssertionError();
    }

    @Inject(method = "c2me$shouldUnloadPoi", require = 0, cancellable = true, at = @At("RETURN"))
    private void vanadium$keepResident(ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (WorkerPool.isWorkerThread() || WorkerPool.isParallelWaveCaller() || this.vanadium$holdsVillageCenter(pos))) {
            cir.setReturnValue(false);
        }
    }

    /** Reads only installed sections ({@code isVillageCenter} uses the non-loading {@code get}); takes no monitor. */
    @Unique
    private boolean vanadium$holdsVillageCenter(ChunkPos pos) {
        for (int y = this.levelHeightAccessor.getMinSectionY(); y <= this.levelHeightAccessor.getMaxSectionY(); y++) {
            if (this.isVillageCenter(SectionPos.asLong(pos.x(), y, pos.z()))) {
                return true;
            }
        }
        return false;
    }
}
