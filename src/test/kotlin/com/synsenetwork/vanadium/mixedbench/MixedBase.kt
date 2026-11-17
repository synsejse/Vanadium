package com.synsenetwork.vanadium.mixedbench

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FarmlandBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.RedstoneLampBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap

/**
 * One player base on generated terrain: a 32×32 stone platform (above the terrain and trees) with a hopper-fed furnace array, two
 * circulating hopper rings, a repeater clock driving lamps and pistons, a hydrated wheat farm, an
 * animal pen and villagers with beds and workstations. Everything is built through ordinary block
 * updates before any measurement, at the same place for the same seed and base index.
 */
internal class MixedBase private constructor(
    private val level: ServerLevel,
    val index: Int,
    /** Platform centre; its y is the platform's walking surface. */
    val centre: BlockPos,
) {
    private val outputChests = ArrayList<BlockPos>()
    private lateinit var clockLamp: BlockPos
    private var lampLit = false

    /** Times the slow clock's first lamp changed state; proves the redstone kept running. */
    var redstoneToggles = 0L
        private set

    /** Items currently in the furnace output chests (cumulative smelting; the inputs last for hours). */
    fun smelted(): Int = outputChests.sumOf { pos ->
        val container = level.getBlockEntity(pos) as? Container ?: return@sumOf 0
        (0 until container.containerSize).sumOf { container.getItem(it).count }
    }

    /** Samples the clock lamp; called once per tick on the server thread, only while its chunk is loaded. */
    fun sample() {
        if (level.chunkSource.getChunkNow(clockLamp.x shr 4, clockLamp.z shr 4) == null) return
        val lit = level.getBlockState(clockLamp).getValue(RedstoneLampBlock.LIT)
        if (lit != lampLit) {
            lampLit = lit
            redstoneToggles++
        }
    }

    private fun at(dx: Int, dy: Int, dz: Int): BlockPos = centre.offset(dx, dy, dz)

    private fun set(pos: BlockPos, state: BlockState) {
        check(level.setBlock(pos, state, Block.UPDATE_ALL) || level.getBlockState(pos) == state) { "could not place $state at $pos" }
    }

    private fun fill(container: BlockPos, vararg stacks: Pair<Item, Int>) {
        val inventory = level.getBlockEntity(container) as Container
        var slot = 0
        for ((item, count) in stacks) repeat(count) { inventory.setItem(slot++, ItemStack(item, item.defaultMaxStackSize)) }
    }

    private fun build() {
        // Platform floor above the highest terrain, tree or water in the footprint: nothing natural is cut.
        for (dx in -16..15) for (dz in -16..15) level.setBlock(at(dx, -1, dz), Blocks.STONE.defaultBlockState(), Walker.QUIET)
        furnaces()
        hopperRing(10, -14, 5)
        hopperRing(10, -6, 3)
        clock()
        farm()
        pen()
        village()
    }

    /** Twelve furnaces: input chest → hopper → furnace top, fuel chest → hopper → furnace side, furnace → hopper → output chest. */
    private fun furnaces() {
        for (k in 0 until 12) {
            val x = -14 + 2 * k
            set(at(x, 0, -12), Blocks.CHEST.defaultBlockState())
            set(at(x, 1, -12), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN))
            set(at(x, 2, -12), Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH))
            set(at(x, 3, -12), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN))
            set(at(x, 4, -12), Blocks.CHEST.defaultBlockState())
            set(at(x, 2, -13), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.SOUTH))
            set(at(x, 3, -13), Blocks.CHEST.defaultBlockState())
            fill(at(x, 4, -12), Items.RAW_IRON to 9, Items.COBBLESTONE to 9, Items.SAND to 9)
            fill(at(x, 3, -13), Items.COAL to 27)
            outputChests.add(at(x, 0, -12))
        }
    }

    /** Hoppers around the perimeter of a side×side square, each feeding the next, so items circulate forever. */
    private fun hopperRing(x0: Int, z0: Int, side: Int) {
        val ring = ArrayList<Pair<Int, Int>>()
        for (i in 0 until side - 1) ring.add(i to 0)
        for (j in 0 until side - 1) ring.add(side - 1 to j)
        for (i in side - 1 downTo 1) ring.add(i to side - 1)
        for (j in side - 1 downTo 1) ring.add(0 to j)
        ring.forEachIndexed { n, (i, j) ->
            val (ni, nj) = ring[(n + 1) % ring.size]
            val facing = Direction.getApproximateNearest((ni - i).toDouble(), 0.0, (nj - j).toDouble())
            val pos = at(x0 + i, 0, z0 + j)
            set(pos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing))
            (level.getBlockEntity(pos) as Container).setItem(0, ItemStack(Items.COBBLESTONE, 16))
        }
    }

    /**
     * A 3×3 repeater ring (four delay-4 repeaters, dust corners) carries one pulse: on 8 ticks, off 24.
     * A dust line from its south-east corner lights ten lamps under it and fires ten upward pistons beside them.
     */
    private fun clock() {
        val x0 = -4
        val z0 = -6
        val wire = Blocks.REDSTONE_WIRE.defaultBlockState()
        val corners = listOf(at(x0, 0, z0), at(x0 + 2, 0, z0), at(x0 + 2, 0, z0 + 2), at(x0, 0, z0 + 2))
        corners.forEach { set(it, wire) }
        // A repeater takes input from its FACING side and outputs on the opposite side; the pulse runs clockwise.
        val repeaters = listOf(
            at(x0 + 1, 0, z0) to Direction.WEST,
            at(x0 + 2, 0, z0 + 1) to Direction.NORTH,
            at(x0 + 1, 0, z0 + 2) to Direction.EAST,
            at(x0, 0, z0 + 1) to Direction.SOUTH,
        )
        for ((pos, facing) in repeaters) {
            set(pos, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(RepeaterBlock.DELAY, 4))
        }
        val line = (1..10).map { at(x0 + 2 + it, 0, z0 + 2) }
        for (pos in line) {
            set(pos.below(), Blocks.REDSTONE_LAMP.defaultBlockState())
            set(pos.below().south(), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP))
            set(pos, wire)
        }
        // Placement does not choose dust connections; recompute them now that every neighbour exists.
        for (pos in corners + line) set(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos))
        clockLamp = line.first().below()
        // Inject the pulse: the first repeater turns off after its delay and the next one turns on.
        val (start, _) = repeaters.first()
        set(start, level.getBlockState(start).setValue(BlockStateProperties.POWERED, true))
        level.scheduleTick(start, Blocks.REPEATER, 8)
    }

    /** 9×9 hydrated farmland around one water source, sown with wheat at mixed ages. */
    private fun farm() {
        for (dx in -14..-6) for (dz in -6..2) {
            val pos = at(dx, -1, dz)
            // The platform may float above the terrain: keep the water source from falling through.
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Walker.QUIET)
            if (dx == -10 && dz == -2) {
                set(pos, Blocks.WATER.defaultBlockState())
            } else {
                set(pos, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7))
                set(pos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, (dx * 7 + dz * 3).mod(8)))
            }
        }
    }

    /** A fenced pen with cows, sheep, pigs and chickens (chickens lay eggs as item entities). */
    private fun pen() {
        val fence = ArrayList<BlockPos>()
        for (dx in 2..13) for (dz in 4..13) if (dx == 2 || dx == 13 || dz == 4 || dz == 13) fence.add(at(dx, 0, dz))
        fence.forEach { set(it, Blocks.OAK_FENCE.defaultBlockState()) }
        fence.forEach { set(it, Block.updateFromNeighbourShapes(level.getBlockState(it), level, it)) }
        val animals = listOf(EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CHICKEN)
        animals.forEachIndexed { a, type -> repeat(4) { n -> spawn(type, at(4 + 2 * n, 0, 6 + 2 * a)) } }
    }

    /** Four villagers with beds and job sites (farmer, fisherman, butcher, librarian) beside the farm, and a bell. */
    private fun village() {
        val sites = listOf(Blocks.COMPOSTER, Blocks.BARREL, Blocks.SMOKER, Blocks.LECTERN)
        sites.forEachIndexed { n, site ->
            val x = -14 + 3 * n
            val foot = at(x, 0, 8)
            set(foot, Blocks.BED.red.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.BED_PART, BedPart.FOOT))
            set(foot.south(), Blocks.BED.red.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.BED_PART, BedPart.HEAD))
            set(at(x, 0, 5), site.defaultBlockState())
            spawn(EntityTypes.VILLAGER, at(x + 1, 0, 6))
        }
        set(at(-8, 0, 12), Blocks.BELL.defaultBlockState())
    }

    private fun spawn(type: EntityType<*>, pos: BlockPos) {
        checkNotNull(type.spawn(level, pos, EntitySpawnReason.SPAWN_ITEM_USE)) { "could not spawn $type at $pos" }
    }

    companion object {
        /** Bases sit 384 blocks apart in rows of four, far enough that their player areas only meet through explorers. */
        fun centreXz(index: Int): Pair<Int, Int> = (index % 4) * 384 - 576 to (index / 4) * 384 - 192

        fun build(level: ServerLevel, index: Int): MixedBase {
            val (cx, cz) = centreXz(index)
            var top = level.seaLevel
            // Server-thread chunk loads (generation by C2ME); the bases exist before any measurement.
            for (x in cx - 16..cx + 15) for (z in cz - 16..cz + 15) top = maxOf(top, surface(level, x, z))
            return MixedBase(level, index, BlockPos(cx, top + 1, cz)).also { it.build() }
        }

        /** First free height above the column's highest motion-blocking block, including leaves and fluids. */
        fun surface(level: ServerLevel, x: Int, z: Int): Int =
            level.getChunk(x shr 4, z shr 4).getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1
    }
}
