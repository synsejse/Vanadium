package com.synsenetwork.vanadium.client;

import com.synsenetwork.vanadium.debug.DebugFramePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.util.Util;

public class VanadiumClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(DebugFramePayload.ID, (payload, context) ->
                context.client().execute(() -> DebugFrameHolder.set(payload, Util.getMeasuringTimeMs())));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(CellDebugRenderer::render);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> CellDebugRenderer.renderHud(drawContext));
    }
}
