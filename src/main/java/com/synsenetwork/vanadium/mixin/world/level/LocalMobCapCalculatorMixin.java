package com.synsenetwork.vanadium.mixin.world.level;

import com.synsenetwork.vanadium.collections.ConcurrentLongObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes {@link LocalMobCapCalculator}'s per-chunk player cache and per-player cap map lock-free for parallel
 * spawning. Pre-sizing {@code playersNearChunk} and using {@code get} + {@code putIfAbsent} avoids
 * {@code ConcurrentHashMap.computeIfAbsent} bin locks, table-resize contention, and capturing lambdas when many
 * spawn workers query nearby players concurrently.
 */
@Mixin(LocalMobCapCalculator.class)
public abstract class LocalMobCapCalculatorMixin {
    @Shadow @Final @Mutable private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;
    @Shadow @Final @Mutable private Map<ServerPlayer, LocalMobCapCalculator.MobCounts> playerMobCounts;
    @Shadow @Final private ChunkMap chunkMap;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$installConcurrentMaps(CallbackInfo ci) {
        this.playersNearChunk = new ConcurrentLongObjectMap<>(512);
        this.playerMobCounts = new ConcurrentHashMap<>();
    }

    /**
     * @author Vanadium
     * @reason Lock-free read-through cache lookup without holding a map bin lock across
     *         {@link ChunkMap#getPlayersCloseForSpawning(ChunkPos)} or allocating a capturing lambda.
     */
    @Overwrite
    private List<ServerPlayer> getPlayersNear(ChunkPos pos) {
        long key = pos.pack();
        List<ServerPlayer> cached = this.playersNearChunk.get(key);
        if (cached != null) return cached;
        List<ServerPlayer> computed = this.chunkMap.getPlayersCloseForSpawning(pos);
        List<ServerPlayer> existing = this.playersNearChunk.putIfAbsent(key, computed);
        return existing != null ? existing : computed;
    }
}
