package com.synsenetwork.vanadium.mixin.server.world;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import java.util.Collections;

import it.unimi.dsi.fastutil.longs.LongSet;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(DistanceManager.class)
public abstract class ChunkTicketManagerMixin {

    @Shadow
    @Final
    @Mutable
    Set<ChunkHolder> chunksToUpdateFutures = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Shadow
    @Final
    @Mutable
    LongSet ticketsToRelease = new ConcurrentLongLinkedOpenHashSet();
}
