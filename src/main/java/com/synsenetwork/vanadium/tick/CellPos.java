package com.synsenetwork.vanadium.tick;

/** The grid coordinate of a {@link Cell}, in cell units (not chunks or blocks). */
public record CellPos(int x, int z) {

    /** The cell that owns the chunk at (chunkX, chunkZ). */
    public static CellPos fromChunk(int chunkX, int chunkZ, int cellSize) {
        return new CellPos(Math.floorDiv(chunkX, cellSize), Math.floorDiv(chunkZ, cellSize));
    }

    /**
     * The checkerboard class of this cell, 0..3. Two cells with the same color are never
     * adjacent (not even diagonally), so they may tick at the same time safely.
     */
    public int color() {
        return Math.floorMod(x, 2) + 2 * Math.floorMod(z, 2);
    }
}
