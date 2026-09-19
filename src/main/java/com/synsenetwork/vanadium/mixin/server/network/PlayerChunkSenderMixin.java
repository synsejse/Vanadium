package com.synsenetwork.vanadium.mixin.server.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.chunk.ChunkPacketPreparation;
import com.synsenetwork.vanadium.Vanadium;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    @WrapMethod(method = "sendNextChunks")
    private void sendBatch(ServerPlayer player, Operation<Void> original) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelChunkPackets) {
            original.call(player);
            return;
        }
        try (ChunkPacketPreparation ignored = new ChunkPacketPreparation()) {
            original.call(player);
        }
    }

    @WrapOperation(method = "sendNextChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/PlayerChunkSender;collectChunksToSend(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;"))
    private List<LevelChunk> preparePackets(PlayerChunkSender sender, ChunkMap map, ChunkPos pos,
                                           Operation<List<LevelChunk>> original) {
        List<LevelChunk> chunks = original.call(sender, map, pos);
        ChunkPacketPreparation.prepare(chunks);
        return chunks;
    }
}
