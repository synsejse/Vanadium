package com.synsenetwork.vanadium.mixin.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.chunk.ChunkPacketPreparation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataMixin {
    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;calculateChunkSize(Lnet/minecraft/world/level/chunk/LevelChunk;)I"))
    private int preparedSize(LevelChunk chunk, Operation<Integer> original) {
        byte[] payload = ChunkPacketPreparation.payload(chunk);
        return payload == null ? original.call(chunk) : payload.length;
    }

    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;extractChunkData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void preparedSections(FriendlyByteBuf buffer, LevelChunk chunk, Operation<Void> original) {
        byte[] payload = ChunkPacketPreparation.payload(chunk);
        if (payload == null) original.call(buffer, chunk);
        else buffer.writeBytes(payload);
    }
}
