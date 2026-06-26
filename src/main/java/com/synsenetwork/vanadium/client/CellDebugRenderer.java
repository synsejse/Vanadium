package com.synsenetwork.vanadium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.synsenetwork.vanadium.debug.DebugFramePayload;
import com.synsenetwork.vanadium.tick.CellPos;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

public final class CellDebugRenderer {

    /** Indexed by CellPos.color() (0..3): red, green, blue, yellow. */
    private static final float[][] COLORS = {
            {1.0f, 0.3f, 0.3f},
            {0.3f, 1.0f, 0.3f},
            {0.3f, 0.5f, 1.0f},
            {1.0f, 1.0f, 0.3f},
    };

    // Load classification from tick duration. < MED = LOW (green), [MED, HIGH) = MEDIUM (yellow), >= HIGH = HIGH (red).
    private static final long LOAD_MED_NANOS = 100_000L;    // 100us
    private static final long LOAD_HIGH_NANOS = 1_000_000L; // 1ms
    private static final int COLOR_LOW = 0xFF55FF55;
    private static final int COLOR_MEDIUM = 0xFFFFFF55;
    private static final int COLOR_HIGH = 0xFFFF5555;

    private static final long PULSE_MS = 200;

    // Label text scales with distance: scale = LABEL_REF_DIST / distance, clamped.
    private static final double LABEL_REF_DIST = 12.0;
    private static final float LABEL_MIN_SCALE = 0.4f;
    private static final float LABEL_MAX_SCALE = 2.0f;

    // Labels are drawn in a HUD pass (screen space). The world pass collects their world positions and
    // captures the world->clip transform so the HUD pass can project them. All on the render thread.
    private record Label(double x, double y, double z, double dist, String time, String load, int loadColor) {
    }

    private static final List<Label> LABELS = new ArrayList<>();
    private static final Matrix4f labelMvp = new Matrix4f();
    private static double labelCamX;
    private static double labelCamY;
    private static double labelCamZ;
    private static boolean labelsReady = false;

    private CellDebugRenderer() {
    }

    public static void render(WorldRenderContext context) {
        long now = Util.getMeasuringTimeMs();
        DebugFramePayload frame = DebugFrameHolder.current(now);
        MinecraftClient client = MinecraftClient.getInstance();
        if (frame == null || client.player == null || client.world == null) {
            labelsReady = false;
            return;
        }

        float pulse = pulse(now, DebugFrameHolder.receivedAtMs());

        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();

        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider.Immediate consumers =
                (VertexConsumerProvider.Immediate) context.consumers();
        if (matrices == null || consumers == null) {
            labelsReady = false;
            return;
        }

        // Capture the exact world->clip transform the GPU uses this frame, so the HUD pass can project
        // label world positions to the screen (camera rotation may live in either matrix).
        labelMvp.set(RenderSystem.getProjectionMatrix());
        labelMvp.mul(RenderSystem.getModelViewMatrix());
        labelMvp.mul(matrices.peek().getPositionMatrix());
        labelCamX = cam.x;
        labelCamY = cam.y;
        labelCamZ = cam.z;

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        renderContents(client, matrices, lines, frame, pulse);
        matrices.pop();
        consumers.draw(RenderLayer.getLines());

        collectLabels(client, frame, px, py, pz);
        labelsReady = true;
    }

    /** Highlights the ticked objects, each colored by its owning cell's checkerboard color. */
    private static void renderContents(MinecraftClient client, MatrixStack matrices, VertexConsumer lines,
                                       DebugFramePayload frame, float pulse) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        int cellSize = frame.cellSize();
        float a = Math.min(1.0f, 0.4f + 0.6f * pulse);
        int playerId = client.player.getId();

        // ENTITY: box the live entity by id, falling back to its sampled position if it is gone.
        for (DebugFramePayload.EntityTick e : frame.entities()) {
            if (e.id() == playerId) {
                continue; // don't box ourselves
            }
            Entity entity = world.getEntityById(e.id());
            Box box = entity != null
                    ? entity.getBoundingBox()
                    : new Box(e.x() - 0.4, e.y(), e.z() - 0.4, e.x() + 0.4, e.y() + 1.0, e.z() + 0.4);
            float[] c = cellColor((int) Math.floor(e.x()) >> 4, (int) Math.floor(e.z()) >> 4, cellSize);
            WorldRenderer.drawBox(matrices, lines, box, c[0], c[1], c[2], a);
        }

        // BLOCK_ENTITY: a small marker at each ticked block entity's position.
        for (DebugFramePayload.BlockEntityTick b : frame.blockEntities()) {
            BlockPos p = BlockPos.fromLong(b.pos());
            Box marker = new Box(p).expand(0.05);
            float[] c = cellColor(p.getX() >> 4, p.getZ() >> 4, cellSize);
            WorldRenderer.drawBox(matrices, lines, marker, c[0], c[1], c[2], a);
        }

        // CHUNK: a column from the build floor up to the chunk's highest block.
        double bottom = world.getBottomY();
        for (DebugFramePayload.ChunkTick ct : frame.chunks()) {
            double x0 = ct.chunkX() * 16.0;
            double z0 = ct.chunkZ() * 16.0;
            double top = highestBlock(world, ct.chunkX(), ct.chunkZ());
            Box column = new Box(x0, bottom, z0, x0 + 16, top, z0 + 16);
            float[] c = cellColor(ct.chunkX(), ct.chunkZ(), cellSize);
            WorldRenderer.drawBox(matrices, lines, column, c[0], c[1], c[2], a * 0.5f);
        }
    }

    private static void collectLabels(MinecraftClient client, DebugFramePayload frame, double px, double py, double pz) {
        LABELS.clear();
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        int playerId = client.player.getId();

        for (DebugFramePayload.EntityTick e : frame.entities()) {
            if (e.id() == playerId) {
                continue; // don't label ourselves
            }
            Entity entity = world.getEntityById(e.id());
            double lx = entity != null ? entity.getX() : e.x();
            double ly = (entity != null ? entity.getY() + entity.getHeight() : e.y()) + 0.4;
            double lz = entity != null ? entity.getZ() : e.z();
            addLabel(lx, ly, lz, formatDuration(e.nanos()), e.nanos(), px, py, pz);
        }
        for (DebugFramePayload.BlockEntityTick b : frame.blockEntities()) {
            BlockPos p = BlockPos.fromLong(b.pos());
            addLabel(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, formatDuration(b.nanos()), b.nanos(), px, py, pz);
        }
        for (DebugFramePayload.ChunkTick c : frame.chunks()) {
            double top = highestBlock(world, c.chunkX(), c.chunkZ()) + 0.5;
            addLabel(c.chunkX() * 16 + 8, top, c.chunkZ() * 16 + 8,
                    "chunk " + formatDuration(c.nanos()), c.nanos(), px, py, pz);
        }
    }

    private static void addLabel(double x, double y, double z, String time, long nanos,
                                 double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        LABELS.add(new Label(x, y, z, Math.sqrt(dx * dx + dy * dy + dz * dz),
                time, loadWord(nanos), loadColor(nanos)));
    }

    /** HUD pass: project each label and draw two lines — duration, then a colored load word below it. */
    public static void renderHud(DrawContext context) {
        if (!labelsReady || LABELS.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        for (Label label : LABELS) {
            Vector4f v = new Vector4f(
                    (float) (label.x() - labelCamX),
                    (float) (label.y() - labelCamY),
                    (float) (label.z() - labelCamZ), 1.0f);
            labelMvp.transform(v);
            if (v.w() <= 1.0e-4f) {
                continue; // behind the camera
            }
            float ndcX = v.x() / v.w();
            float ndcY = v.y() / v.w();
            if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f) {
                continue; // off-screen
            }
            float sx = (ndcX * 0.5f + 0.5f) * screenWidth;
            float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenHeight;
            float scale = (float) Math.clamp(LABEL_REF_DIST / Math.max(label.dist(), 0.1), LABEL_MIN_SCALE, LABEL_MAX_SCALE);

            MatrixStack stack = context.getMatrices();
            stack.push();
            stack.translate(sx, sy, 0);
            stack.scale(scale, scale, 1.0f);
            context.drawText(textRenderer, label.time(),
                    -textRenderer.getWidth(label.time()) / 2, 0, 0xFFFFFFFF, true);
            context.drawText(textRenderer, label.load(),
                    -textRenderer.getWidth(label.load()) / 2, textRenderer.fontHeight + 1, label.loadColor(), true);
            stack.pop();
        }
    }

    private static float[] cellColor(int chunkX, int chunkZ, int cellSize) {
        return COLORS[new CellPos(Math.floorDiv(chunkX, cellSize), Math.floorDiv(chunkZ, cellSize)).color()];
    }

    /** Highest surface block in the chunk (max of the WORLD_SURFACE heightmap over its 16x16 columns). */
    private static int highestBlock(ClientWorld world, int chunkX, int chunkZ) {
        Heightmap heightmap = world.getChunk(chunkX, chunkZ).getHeightmap(Heightmap.Type.WORLD_SURFACE);
        int max = world.getBottomY();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                max = Math.max(max, heightmap.get(lx, lz));
            }
        }
        return max;
    }

    private static String loadWord(long nanos) {
        if (nanos >= LOAD_HIGH_NANOS) {
            return "HIGH";
        }
        if (nanos >= LOAD_MED_NANOS) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static int loadColor(long nanos) {
        if (nanos >= LOAD_HIGH_NANOS) {
            return COLOR_HIGH;
        }
        if (nanos >= LOAD_MED_NANOS) {
            return COLOR_MEDIUM;
        }
        return COLOR_LOW;
    }

    private static String formatDuration(long nanos) {
        if (nanos >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
        }
        if (nanos >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.0fus", nanos / 1_000.0);
        }
        return nanos + "ns";
    }

    private static float pulse(long now, long receivedAtMs) {
        long age = now - receivedAtMs;
        if (age >= PULSE_MS) {
            return 0.0f;
        }
        return 1.0f - (float) age / PULSE_MS;
    }
}
