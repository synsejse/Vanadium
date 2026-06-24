package com.synsenetwork.vanadium.debug;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugFramePayloadTest {

    @Test
    void codecRoundTrip() {
        var original = new DebugFramePayload(8,
                List.of(new DebugFramePayload.EntityTick(42, 1.5, 64.0, -2.5, 123456L),
                        new DebugFramePayload.EntityTick(7, -10.0, 70.0, 5.0, 9L)),
                List.of(new DebugFramePayload.BlockEntityTick(1234567890L, 555L)),
                List.of(new DebugFramePayload.ChunkTick(3, -1, 88L)));

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        DebugFramePayload.CODEC.encode(buf, original);
        DebugFramePayload decoded = DebugFramePayload.CODEC.decode(buf);

        assertEquals(original, decoded);
    }

    @Test
    void codecRoundTripEmpty() {
        var original = new DebugFramePayload(16, List.of(), List.of(), List.of());
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        DebugFramePayload.CODEC.encode(buf, original);
        assertEquals(original, DebugFramePayload.CODEC.decode(buf));
    }
}
