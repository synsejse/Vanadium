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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CellDebugRenderer {

    /** Cell checkerboard color (active cells), indexed by CellPos.color() (0..3): red, green, blue, yellow. */
    private static final float[][] COLORS = {
            {1.0f, 0.3f, 0.3f},
            {0.3f, 1.0f, 0.3f},
            {0.3f, 0.5f, 1.0f},
            {1.0f, 1.0f, 0.3f},
    };
    private static final float[] ORANGE = {1.0f, 0.55f, 0.1f}; // partially-active cells
    private static final float[] GRAY = {0.55f, 0.55f, 0.55f}; // inactive cells

    private static final float ACTIVE_ALPHA = 0.55f;
    private static final float INACTIVE_ALPHA = 0.16f;
    private static final float PARTIAL_ALPHA = INACTIVE_ALPHA; // translucent like inactive, just orange
    private static final float CONTENT_ALPHA = 0.85f; // entity / block-entity boxes

    private static final int COLOR_ACTIVE = 0xFF55FF55;  // green
    private static final int COLOR_PARTIAL = 0xFFFF9020; // orange
    // Load thresholds for the stats panel cache/stage coloring.
    private static final int COLOR_LOW = 0xFF55FF55;
    private static final int COLOR_MEDIUM = 0xFFFFFF55;
    private static final int COLOR_HIGH = 0xFFFF5555;

    // Label text scales with distance: scale = LABEL_REF_DIST / distance, clamped. The low floor lets
    // far labels keep shrinking (perspective-like) instead of flattening to one size.
    private static final double LABEL_REF_DIST = 18.0;
    private static final float LABEL_MIN_SCALE = 0.1f;
    private static final float LABEL_MAX_SCALE = 1.8f;

    private enum Activity {INACTIVE, PARTIAL, ACTIVE}

    /** One cell's aggregated activity for this frame. */
    private record CellInfo(int cellX, int cellZ, Activity activity,
                            long chunkNanos, long entityNanos, long blockEntityNanos, double topY) {
    }

    /** One cell's two-line HUD label: a colored status word over an aggregated-times line. */
    private record Label(double x, double y, double z, double dist, String status, int statusColor, String times) {
    }

    private static final List<Label> LABELS = new ArrayList<>();
    private static final Matrix4f labelMvp = new Matrix4f();
    private static double labelCamX;
    private static double labelCamY;
    private static double labelCamZ;
    private static boolean labelsReady = false;
    private static DebugFramePayload.Stats latestStats;
    private static ClientWorld lastWorld;

    // Cell surface heights are expensive (a full heightmap scan per chunk) but terrain rarely moves, so
    // cache them per cell and refresh the whole cache every few seconds (and whenever cellSize changes).
    private static final Map<Long, Integer> CELL_TOP_CACHE = new HashMap<>();
    private static final long CELL_TOP_TTL_MS = 3000;
    private static long cellTopStampMs;
    private static int cellTopCacheSize = -1;

    private CellDebugRenderer() {
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            labelsReady = false;
            lastWorld = null;
            return;
        }
        if (client.world != lastWorld) {
            // world / dimension changed: drop stale frame and the per-world cell-height cache
            lastWorld = client.world;
            DebugFrameHolder.clear();
            CELL_TOP_CACHE.clear();
            labelsReady = false;
            return;
        }
        DebugFramePayload frame = DebugFrameHolder.current(System.currentTimeMillis());
        if (frame == null) {
            labelsReady = false;
            return;
        }

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

        // Capture the exact world->clip transform the GPU uses this frame so the HUD pass can project labels.
        labelMvp.set(RenderSystem.getProjectionMatrix());
        labelMvp.mul(RenderSystem.getModelViewMatrix());
        labelMvp.mul(matrices.peek().getPositionMatrix());
        labelCamX = cam.x;
        labelCamY = cam.y;
        labelCamZ = cam.z;

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        List<CellInfo> cells = buildCells(client, client.world, frame);

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        drawCells(matrices, lines, cells, client.world, frame.cellSize());
        renderEntities(client, matrices, lines, frame);
        renderBlockEntities(matrices, lines, frame);
        matrices.pop();
        consumers.draw(RenderLayer.getLines());

        collectCellLabels(cells, frame.cellSize(), px, py, pz);
        latestStats = frame.stats();
        labelsReady = true;
    }

    /** Aggregates the frame's per-object timings into cells, then classifies every cell in render distance. */
    private static List<CellInfo> buildCells(MinecraftClient client, ClientWorld world, DebugFramePayload frame) {
        int cellSize = frame.cellSize();
        if (cellSize <= 0) {
            return List.of();
        }
        manageCellTopCache(cellSize);

        // cell key -> {tickedChunks, chunkNanos, entityNanos, blockEntityNanos}
        Map<Long, long[]> agg = new HashMap<>();
        for (DebugFramePayload.ChunkTick c : frame.chunks()) {
            long[] a = agg.computeIfAbsent(cellKey(c.chunkX(), c.chunkZ(), cellSize), k -> new long[4]);
            a[0]++;
            a[1] += c.nanos();
        }
        for (DebugFramePayload.EntityTick e : frame.entities()) {
            agg.computeIfAbsent(cellKey((int) Math.floor(e.x()) >> 4, (int) Math.floor(e.z()) >> 4, cellSize),
                    k -> new long[4])[2] += e.nanos();
        }
        for (DebugFramePayload.BlockEntityTick b : frame.blockEntities()) {
            BlockPos p = BlockPos.fromLong(b.pos());
            agg.computeIfAbsent(cellKey(p.getX() >> 4, p.getZ() >> 4, cellSize), k -> new long[4])[3] += b.nanos();
        }

        int viewDist = client.options.getViewDistance().getValue();
        ChunkPos pc = client.player.getChunkPos();
        int minCellX = Math.floorDiv(pc.x - viewDist, cellSize);
        int maxCellX = Math.floorDiv(pc.x + viewDist, cellSize);
        int minCellZ = Math.floorDiv(pc.z - viewDist, cellSize);
        int maxCellZ = Math.floorDiv(pc.z + viewDist, cellSize);
        int totalChunks = cellSize * cellSize;

        List<CellInfo> cells = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long[] a = agg.get(pack(cellX, cellZ));
                int ticked = a == null ? 0 : (int) a[0];
                boolean entitiesTicking = a != null && (a[2] > 0 || a[3] > 0); // entities or block entities
                Activity activity;
                if (ticked >= totalChunks) {
                    activity = Activity.ACTIVE;
                } else if (ticked > 0 || entitiesTicking) {
                    activity = Activity.PARTIAL; // some chunks, or entities/BEs ticking without chunk ticks
                } else {
                    activity = Activity.INACTIVE;
                }
                cells.add(new CellInfo(cellX, cellZ, activity,
                        a == null ? 0 : a[1], a == null ? 0 : a[2], a == null ? 0 : a[3],
                        cellTop(world, cellX, cellZ, cellSize)));
            }
        }
        return cells;
    }

    /** Footprint column per cell: build floor up to the cell surface, colored by activity. */
    private static void drawCells(MatrixStack matrices, VertexConsumer lines, List<CellInfo> cells,
                                  ClientWorld world, int cellSize) {
        double bottom = world.getBottomY();
        for (CellInfo c : cells) {
            float[] color;
            float alpha;
            switch (c.activity()) {
                case ACTIVE -> {
                    color = COLORS[new CellPos(c.cellX(), c.cellZ()).color()];
                    alpha = ACTIVE_ALPHA;
                }
                case PARTIAL -> {
                    color = ORANGE;
                    alpha = PARTIAL_ALPHA;
                }
                default -> {
                    color = GRAY;
                    alpha = INACTIVE_ALPHA;
                }
            }
            double x0 = (double) c.cellX() * cellSize * 16;
            double z0 = (double) c.cellZ() * cellSize * 16;
            Box box = new Box(x0, bottom, z0, x0 + cellSize * 16, c.topY(), z0 + cellSize * 16);
            WorldRenderer.drawBox(matrices, lines, box, color[0], color[1], color[2], alpha);
        }
    }

    /** Boxes the ticked entities (colored by their cell). No per-entity text — that lives on the cell label. */
    private static void renderEntities(MinecraftClient client, MatrixStack matrices, VertexConsumer lines,
                                       DebugFramePayload frame) {
        ClientWorld world = client.world;
        int cellSize = frame.cellSize();
        int playerId = client.player.getId();
        for (DebugFramePayload.EntityTick e : frame.entities()) {
            if (e.id() == playerId) {
                continue;
            }
            Entity entity = world.getEntityById(e.id());
            Box box = entity != null
                    ? entity.getBoundingBox()
                    : new Box(e.x() - 0.4, e.y(), e.z() - 0.4, e.x() + 0.4, e.y() + 1.0, e.z() + 0.4);
            float[] col = cellColor((int) Math.floor(e.x()) >> 4, (int) Math.floor(e.z()) >> 4, cellSize);
            WorldRenderer.drawBox(matrices, lines, box, col[0], col[1], col[2], CONTENT_ALPHA);
        }
    }

    /** Markers at the ticked block entities (colored by their cell). */
    private static void renderBlockEntities(MatrixStack matrices, VertexConsumer lines, DebugFramePayload frame) {
        int cellSize = frame.cellSize();
        for (DebugFramePayload.BlockEntityTick b : frame.blockEntities()) {
            BlockPos p = BlockPos.fromLong(b.pos());
            Box marker = new Box(p).expand(0.05);
            float[] col = cellColor(p.getX() >> 4, p.getZ() >> 4, cellSize);
            WorldRenderer.drawBox(matrices, lines, marker, col[0], col[1], col[2], CONTENT_ALPHA);
        }
    }

    private static void collectCellLabels(List<CellInfo> cells, int cellSize, double px, double py, double pz) {
        LABELS.clear();
        for (CellInfo c : cells) {
            if (c.activity() == Activity.INACTIVE) {
                continue; // the gray box already says "inactive"; no text, to avoid spamming labels
            }
            double x = (c.cellX() * cellSize + cellSize / 2.0) * 16.0;
            double z = (c.cellZ() * cellSize + cellSize / 2.0) * 16.0;
            double y = c.topY() + 1.0;

            String status;
            int statusColor;
            if (c.activity() == Activity.ACTIVE) {
                status = "active";
                statusColor = COLOR_ACTIVE;
            } else {
                status = "partially active";
                statusColor = COLOR_PARTIAL;
            }
            String times = "chunk " + dur(c.chunkNanos()) + "  ent " + dur(c.entityNanos())
                    + "  be " + dur(c.blockEntityNanos());

            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            LABELS.add(new Label(x, y, z, Math.sqrt(dx * dx + dy * dy + dz * dz), status, statusColor, times));
        }
    }

    /** HUD pass: project each cell label, draw the status word over the aggregated-times line. */
    public static void renderHud(DrawContext context) {
        if (!labelsReady) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        if (latestStats != null) {
            drawStatsPanel(context, textRenderer, latestStats);
        }
        if (LABELS.isEmpty()) {
            return;
        }
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
            context.drawText(textRenderer, label.status(),
                    -textRenderer.getWidth(label.status()) / 2, 0, label.statusColor(), true);
            context.drawText(textRenderer, label.times(),
                    -textRenderer.getWidth(label.times()) / 2, textRenderer.fontHeight + 1, 0xFFFFFFFF, true);
            stack.pop();
        }
    }

    private static float[] cellColor(int chunkX, int chunkZ, int cellSize) {
        return COLORS[new CellPos(Math.floorDiv(chunkX, cellSize), Math.floorDiv(chunkZ, cellSize)).color()];
    }

    private static void manageCellTopCache(int cellSize) {
        long now = System.currentTimeMillis();
        if (cellSize != cellTopCacheSize || now - cellTopStampMs > CELL_TOP_TTL_MS) {
            CELL_TOP_CACHE.clear();
            cellTopStampMs = now;
            cellTopCacheSize = cellSize;
        }
    }

    /** Cached cell surface height. Computed once per cell per refresh window; see {@link #manageCellTopCache}. */
    private static double cellTop(ClientWorld world, int cellX, int cellZ, int cellSize) {
        long key = pack(cellX, cellZ);
        Integer cached = CELL_TOP_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int top = computeCellTop(world, cellX, cellZ, cellSize);
        if (top > world.getBottomY()) {
            CELL_TOP_CACHE.put(key, top); // only cache once the chunks are loaded (a real surface was found)
        }
        return top;
    }

    /** The highest surface block across every chunk in the cell (so the column reaches the cell's peak). */
    private static int computeCellTop(ClientWorld world, int cellX, int cellZ, int cellSize) {
        int baseChunkX = cellX * cellSize;
        int baseChunkZ = cellZ * cellSize;
        int max = world.getBottomY();
        for (int dx = 0; dx < cellSize; dx++) {
            for (int dz = 0; dz < cellSize; dz++) {
                max = Math.max(max, highestBlock(world, baseChunkX + dx, baseChunkZ + dz));
            }
        }
        return max;
    }

    /** Highest surface block in one chunk (max of the WORLD_SURFACE heightmap over its 16x16 columns). */
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

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static long cellKey(int chunkX, int chunkZ, int cellSize) {
        return pack(Math.floorDiv(chunkX, cellSize), Math.floorDiv(chunkZ, cellSize));
    }

    private static String dur(long nanos) {
        return nanos > 0 ? formatDuration(nanos) : "—"; // em dash for an idle category
    }

    private static String formatDuration(long nanos) {
        if (nanos >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
        }
        if (nanos >= 1_000L) {
            return String.format(Locale.ROOT, "%.0fus", nanos / 1_000.0);
        }
        return nanos + "ns";
    }

    /** Top-left scheduler dashboard: per-stage times (longest highlighted), sync wait, cache rate, throughput. */
    private static void drawStatsPanel(DrawContext ctx, TextRenderer tr, DebugFramePayload.Stats s) {
        int t = Math.max(1, s.ticks());
        int w = Math.max(1, s.workers());
        double chunkMs = perTickMs(s.chunkNanos(), t);
        double entityMs = perTickMs(s.entityNanos(), t);
        double beMs = perTickMs(s.blockEntityNanos(), t);
        double parallelMs = chunkMs + entityMs + beMs;
        double workMs = perTickMs(s.workNanos(), t);
        double syncMs = Math.max(0.0, parallelMs - workMs / w);
        double eff = parallelMs > 0 ? Math.min(100.0, workMs / w / parallelMs * 100.0) : 0.0;
        long hits = s.cacheHits();
        long lookups = hits + s.cacheMisses();
        double rate = lookups > 0 ? hits * 100.0 / lookups : 100.0;
        double maxStage = Math.max(chunkMs, Math.max(entityMs, beMs));

        List<String> texts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        addLine(texts, colors, String.format(Locale.ROOT, "Vanadium   MSPT %.1f   workers %d   (avg/%dt)",
                s.mspt(), s.workers(), s.ticks()), 0xFFFFFFFF);
        addLine(texts, colors, String.format(Locale.ROOT, "CHUNK    %.2f ms", chunkMs), stageColor(chunkMs, maxStage));
        addLine(texts, colors, String.format(Locale.ROOT, "ENTITY   %.2f ms", entityMs), stageColor(entityMs, maxStage));
        addLine(texts, colors, String.format(Locale.ROOT, "BLOCKENT %.2f ms", beMs), stageColor(beMs, maxStage));
        addLine(texts, colors, String.format(Locale.ROOT, "parallel %.2f ms   sync-wait %.2f ms   %.0f%% eff",
                parallelMs, syncMs, eff), 0xFFB0B0B0);
        addLine(texts, colors, String.format(Locale.ROOT, "cache/t   %s hit   %s miss   %s bounce   %.0f%%",
                compact(s.cacheHits() / t), compact(s.cacheMisses() / t), compact(s.cacheBounces() / t), rate),
                cacheColor(rate));
        addLine(texts, colors, String.format(Locale.ROOT, "ticked/t   cells %d   chunk %d   ent %d   be %d",
                perTick(s.cellsRun(), t), perTick(s.chunksTicked(), t),
                perTick(s.entitiesTicked(), t), perTick(s.blockEntitiesTicked(), t)), 0xFFFFFFFF);

        int pad = 3;
        int lineH = tr.fontHeight + 1;
        int width = 0;
        for (String line : texts) {
            width = Math.max(width, tr.getWidth(line));
        }
        int x = 4;
        int y = 4;
        ctx.fill(x - pad, y - pad, x + width + pad, y + texts.size() * lineH + pad, 0x90000000);
        for (int i = 0; i < texts.size(); i++) {
            ctx.drawText(tr, texts.get(i), x, y + i * lineH, colors.get(i), true);
        }
    }

    private static void addLine(List<String> texts, List<Integer> colors, String text, int color) {
        texts.add(text);
        colors.add(color);
    }

    private static double perTickMs(long nanos, int ticks) {
        return nanos / (double) ticks / 1_000_000.0;
    }

    private static int perTick(int total, int ticks) {
        return Math.round(total / (float) ticks);
    }

    private static int stageColor(double ms, double maxMs) {
        return ms >= maxMs && maxMs > 0 ? COLOR_HIGH : 0xFFD0D0D0;
    }

    private static int cacheColor(double rate) {
        return rate >= 90 ? COLOR_LOW : rate >= 70 ? COLOR_MEDIUM : COLOR_HIGH;
    }

    /** Compact human count: 45123 -> "45k", 1_200_000 -> "1.2M". */
    private static String compact(long n) {
        if (n >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }
        if (n >= 10_000) {
            return (n / 1000) + "k";
        }
        if (n >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        }
        return Long.toString(n);
    }
}
