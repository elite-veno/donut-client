package dev.donut.client.schematic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * What the builder can make of each block: the properties placing it decides, how many more of its item still go in,
 * and the clicks that set what placing can't, such as a repeater's delay. Everything else about a state, such as a
 * wire's connections or whether something is powered, the game works out by itself once the blocks around it are in.
 */
public final class BlockRules {
    public enum Kind {
        /** Placed with its item. */
        PLACE,
        /** A water or lava source, poured from a bucket. */
        FLUID,
        /**
         * Comes with another block: a door's or tall flower's top half, a bed's head, a piston's head, and water or lava
         * flowing from its source.
         */
        AUTO,
        /** No item places it, as with fire, portals and filled cauldrons. */
        UNPLACEABLE
    }

    /** Settings the player's look and the face clicked decide when the block goes down. */
    private static final Set<Class<?>> PLACED_BY_CLICK = Set.of(Direction.class, Direction.Axis.class, Half.class,
            DoubleBlockHalf.class, AttachFace.class, DoorHingeSide.class, BellAttachType.class, FrontAndTop.class, BedPart.class);
    /** Blocks that take more of their own item in the same spot. */
    private static final List<IntegerProperty> COUNTS = List.of(BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS, BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT);
    private static final ClassValue<Boolean> INTERACTIVE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != BlockBehaviour.class && c != Block.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals("useWithoutItem") || method.getName().equals("useItemOn")) return true;
                }
            }
            return false;
        }
    };

    /** Stems of growing plants, placed as the plant's tip; the tip turns into stem when the next one goes on it. */
    private static final Map<Block, Block> HEADS = Map.of(Blocks.KELP_PLANT, Blocks.KELP, Blocks.TWISTING_VINES_PLANT,
            Blocks.TWISTING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.CAVE_VINES_PLANT, Blocks.CAVE_VINES);

    private BlockRules() { }

    public static Kind kind(BlockState target) {
        Block block = target.getBlock();
        if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) return Kind.AUTO;
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return Kind.AUTO;
        if (target.hasProperty(BlockStateProperties.BED_PART) && target.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return Kind.AUTO;
        if (block instanceof LiquidBlock) {
            if (!target.getFluidState().isSource()) return Kind.AUTO;
            return item(target) == null ? Kind.UNPLACEABLE : Kind.FLUID;
        }
        // A bubble column is water poured over soul sand or magma: it rises by itself once the water is there.
        if (block instanceof BubbleColumnBlock) return Kind.FLUID;
        if (item(target) == null) return Kind.UNPLACEABLE;
        return Kind.PLACE;
    }

    /** The item that places the block: a bucket for water and lava, the plant's tip for a stem; null when none does. */
    public static Item item(BlockState target) {
        if (target.getBlock() instanceof BubbleColumnBlock) return Items.WATER_BUCKET;
        if (target.getBlock() instanceof LiquidBlock) {
            Fluid fluid = target.getFluidState().getType();
            return fluid.isSame(Fluids.WATER) ? Items.WATER_BUCKET : fluid.isSame(Fluids.LAVA) ? Items.LAVA_BUCKET : null;
        }
        Item item = placedAs(target).getBlock().asItem();
        return item instanceof BlockItem ? item : null;
    }

    /** What placing makes first: the tip of a growing plant for its stem, otherwise the target itself. */
    public static BlockState placedAs(BlockState target) {
        Block head = HEADS.get(target.getBlock());
        return head == null ? target : head.defaultBlockState();
    }

    /** The same block, counting a growing plant's tip and stem as one, since the plant turns one into the other. */
    public static boolean sameBlock(Block a, Block b) {
        return a == b || HEADS.getOrDefault(a, a) == HEADS.getOrDefault(b, b);
    }

    /** The other half of a double chest, or null for any other block. */
    public static BlockPos chestPartner(BlockPos pos, BlockState target) {
        if (!(target.getBlock() instanceof ChestBlock) || target.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        return pos.relative(ChestBlock.getConnectedDirection(target));
    }

    /**
     * Whether a block just placed, or about to be, starts the target: the same block, with every setting placing
     * decides the same. A slab of a double slab, the first of several candles and the first half of a double chest
     * count.
     */
    public static boolean placementMatches(BlockState placed, BlockState target) {
        return placementMatches(placed, target, false);
    }

    /**
     * As {@link #placementMatches(BlockState, BlockState)}; with {@code partnerReady}, the other half of a double chest
     * stands there already, and the chest must join it rather than stand alone.
     */
    public static boolean placementMatches(BlockState placed, BlockState target, boolean partnerReady) {
        if (!sameBlock(placed.getBlock(), target.getBlock())) return false;
        // Glow lichen and the like grow on the faces they were clicked onto: one on a face the target leaves bare is wrong.
        if (target.getBlock() instanceof MultifaceBlock) {
            for (Direction side : Direction.values()) {
                if (on(placed, side) && !on(target, side)) return false;
            }
        }
        for (Property<?> property : target.getProperties()) {
            if (!placed.hasProperty(property)) continue;
            Object want = target.getValue(property), got = placed.getValue(property);
            Class<?> type = property.getValueClass();
            if (type == SlabType.class) {
                if (want != SlabType.DOUBLE && got != want) return false;
            } else if (type == ChestType.class) {
                // A lone chest must stay alone; a half of a pair may stand alone until its other half goes in.
                if (got != want && (want == ChestType.SINGLE || partnerReady || got != ChestType.SINGLE)) return false;
            } else if (type == RailShape.class) {
                // Rails turn to their neighbors by themselves; only the direction they are laid in is chosen.
                Direction.Axis axis = railAxis((RailShape) want), placedAxis = railAxis((RailShape) got);
                if (axis != null && placedAxis != null && axis != placedAxis) return false;
            } else if (PLACED_BY_CLICK.contains(type) || property == BlockStateProperties.ROTATION_16
                    || property == BlockStateProperties.HANGING) {
                if (!want.equals(got)) return false;
            }
        }
        return true;
    }

    private static Direction.Axis railAxis(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> Direction.Axis.Z;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> Direction.Axis.X;
            default -> null;
        };
    }

    /** The world's block is the target as far as the builder can make it. */
    public static boolean satisfied(BlockState current, BlockState target) {
        return placedFully(current, target) && !needsWater(current, target);
    }

    /**
     * As {@link #satisfied}, but for the water a waterlogged target holds: placed, topped up and clicked as it should be.
     */
    public static boolean placedFully(BlockState current, BlockState target) {
        return placementMatches(current, target) && missing(current, target) == 0 && adjustClicks(current, target) == 0;
    }

    /**
     * Whether the target stands full of water, as a chest, trapdoor, slab or leaves can: placed dry like any block, then
     * filled from a bucket. A block full of water runs it out as a source does, and many farms feed their item streams
     * from one, so leaving it dry is no small difference.
     */
    public static boolean soaked(BlockState target) {
        return target.hasProperty(BlockStateProperties.WATERLOGGED) && target.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** Whether the world's block is the soaked target's block, still dry. */
    public static boolean needsWater(BlockState current, BlockState target) {
        return soaked(target) && current.getBlock() == target.getBlock() && current.hasProperty(BlockStateProperties.WATERLOGGED)
                && !current.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** Blocks that drop something other than themselves, or nothing, when broken without silk touch. */
    private static final Set<Block> DROPS_OTHER = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRASS_BLOCK, Blocks.PODZOL,
            Blocks.MYCELIUM, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.BOOKSHELF, Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.CLAY,
            Blocks.MELON, Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.GLASS_PANE, Blocks.BUDDING_AMETHYST);

    /**
     * Whether breaking the block gives back its own item, as far as can be told without the server's loot tables: not
     * glass, panes, ice, leaves or ores, nor stone, grass or the like, which drop something else.
     */
    public static boolean dropsItself(BlockState state) {
        Block block = state.getBlock();
        return !(block instanceof HalfTransparentBlock || block instanceof StainedGlassPaneBlock || block instanceof LeavesBlock
                || block instanceof DropExperienceBlock || block instanceof RedStoneOreBlock || DROPS_OTHER.contains(block));
    }

    /**
     * Whether breaking the block leaves nothing behind but what it drops: not ice, which turns into water over a block,
     * nor a block with a silverfish in it.
     */
    public static boolean breaksClean(BlockState state) {
        Block block = state.getBlock();
        return !(block instanceof IceBlock || block instanceof InfestedBlock);
    }

    /**
     * Whether a bucket emptied onto the placed block, standing, fills it with water, as with slabs, stairs, leaves or bars.
     * Clicked, a block with a use, a chest or a wooden trapdoor, is used instead; and crouched, the bucket pours in front
     * of what it is aimed at rather than into it. Such a block is filled as a player fills one: placed into water.
     */
    public static boolean fillsInPlace(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof TrapDoorBlock) return !openByHand(state);
        return !interactive(block);
    }

    /** Whether a glow lichen or the like grows on {@code side} of its spot. */
    private static boolean on(BlockState state, Direction side) {
        return state.hasProperty(MultifaceBlock.getFaceProperty(side)) && state.getValue(MultifaceBlock.getFaceProperty(side));
    }

    /** How many faces of its spot a glow lichen or the like grows on. */
    private static int faces(BlockState state) {
        int faces = 0;
        for (Direction side : Direction.values()) if (on(state, side)) faces++;
        return faces;
    }

    /**
     * How many more of the item still go in the same spot: candles, sea pickles, snow layers, the second slab, a glow
     * lichen's other faces.
     */
    public static int missing(BlockState current, BlockState target) {
        if (current.getBlock() != target.getBlock()) return 0;
        int missing = 0;
        if (target.getBlock() instanceof MultifaceBlock) {
            for (Direction side : Direction.values()) if (on(target, side) && !on(current, side)) missing++;
        }
        for (IntegerProperty count : COUNTS) {
            if (target.hasProperty(count)) missing += Math.max(0, target.getValue(count) - current.getValue(count));
        }
        if (target.hasProperty(BlockStateProperties.SLAB_TYPE) && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                && current.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) missing++;
        return missing;
    }

    /** How many of the block's item it still takes to make the target out of what is there now. */
    public static int itemsNeeded(BlockState current, BlockState target) {
        if (current.getBlock() == target.getBlock()) return missing(current, target);
        for (IntegerProperty count : COUNTS) if (target.hasProperty(count)) return target.getValue(count);
        if (target.getBlock() instanceof MultifaceBlock) return Math.max(1, faces(target));
        boolean doubleSlab = target.hasProperty(BlockStateProperties.SLAB_TYPE)
                && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
        return doubleSlab ? 2 : 1;
    }

    /**
     * Right clicks on the placed block that set what placing can't: a repeater's delay, a comparator's mode, a note
     * block's note, an inverted daylight detector, a lever that is on, an open door, trapdoor or gate, and a lone wire's
     * dot or cross.
     */
    public static int adjustClicks(BlockState current, BlockState target) {
        Block block = target.getBlock();
        if (current.getBlock() != block) return 0;
        if (target.hasProperty(BlockStateProperties.DELAY)) {
            return Math.floorMod(target.getValue(BlockStateProperties.DELAY) - current.getValue(BlockStateProperties.DELAY), 4);
        }
        if (target.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
            return target.getValue(BlockStateProperties.MODE_COMPARATOR) == current.getValue(BlockStateProperties.MODE_COMPARATOR) ? 0 : 1;
        }
        if (block instanceof NoteBlock) {
            return Math.floorMod(target.getValue(BlockStateProperties.NOTE) - current.getValue(BlockStateProperties.NOTE), 25);
        }
        if (block instanceof DaylightDetectorBlock) {
            return target.getValue(BlockStateProperties.INVERTED) == current.getValue(BlockStateProperties.INVERTED) ? 0 : 1;
        }
        if (block instanceof LeverBlock) {
            return target.getValue(BlockStateProperties.POWERED) == current.getValue(BlockStateProperties.POWERED) ? 0 : 1;
        }
        if (openByHand(target)) {
            // A powered door is held open by its signal; a click would only fight it.
            boolean powered = target.hasProperty(BlockStateProperties.POWERED) && target.getValue(BlockStateProperties.POWERED);
            return powered || target.getValue(BlockStateProperties.OPEN) == current.getValue(BlockStateProperties.OPEN) ? 0 : 1;
        }
        if (block instanceof RedStoneWireBlock) {
            // A lone wire is drawn as a cross; a click turns it into a dot, and back.
            return dot(target) && cross(current) || cross(target) && dot(current) ? 1 : 0;
        }
        return 0;
    }

    private static boolean openByHand(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.OPEN)) return false;
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) return door.type().canOpenByHand();
        if (block instanceof TrapDoorBlock) return block != Blocks.IRON_TRAPDOOR;
        return block instanceof FenceGateBlock;
    }

    private static boolean dot(BlockState wire) { return sides(wire, RedstoneSide.NONE); }

    private static boolean cross(BlockState wire) { return sides(wire, RedstoneSide.SIDE); }

    private static boolean sides(BlockState wire, RedstoneSide side) {
        return wire.getValue(BlockStateProperties.NORTH_REDSTONE) == side && wire.getValue(BlockStateProperties.EAST_REDSTONE) == side
                && wire.getValue(BlockStateProperties.SOUTH_REDSTONE) == side && wire.getValue(BlockStateProperties.WEST_REDSTONE) == side;
    }

    /**
     * When a block goes in within its layer: 0 for plain blocks, 1 for redstone parts, 2 for what moves or fires on an
     * update (pistons, observers, dispensers, droppers, crafters and TNT), so a half-built layer triggers as little as
     * possible; then 3 for water and lava, poured once the layer holds them in, and 4 for kelp and seagrass, which need
     * the water.
     */
    public static int group(BlockState state) {
        Block block = state.getBlock();
        // Water and lava go in once their layer's blocks hold them in, and what grows in water after them.
        if (block instanceof LiquidBlock || block instanceof BubbleColumnBlock) return 3;
        if (state.getFluidState().isSource() && !state.hasProperty(BlockStateProperties.WATERLOGGED)) return 4;
        if (block instanceof PistonBaseBlock || block instanceof ObserverBlock || block instanceof DispenserBlock
                || block instanceof CrafterBlock || block instanceof TntBlock) return 2;
        if (state.isSignalSource() || block instanceof RedStoneWireBlock || block instanceof DiodeBlock
                || block instanceof RedstoneTorchBlock || block instanceof LeverBlock || block instanceof ButtonBlock
                || block instanceof BasePressurePlateBlock || block instanceof RedstoneLampBlock || block instanceof BaseRailBlock
                || block instanceof DaylightDetectorBlock || block instanceof TargetBlock || block instanceof TripWireHookBlock
                || block instanceof TripWireBlock || block instanceof SculkSensorBlock || block instanceof HopperBlock
                || block instanceof NoteBlock || block instanceof PoweredBlock || block instanceof LightningRodBlock
                || block instanceof CopperBulbBlock) return 1;
        return 0;
    }

    /**
     * Whether the block is water or lava, or something that grows in water such as kelp. These go in once every other
     * block of the build is in: water running through a half-built machine pushes the player about, and fills the
     * spaces they would stand in to place the rest.
     */
    public static boolean wet(BlockState state) {
        return state.getBlock() instanceof LiquidBlock || state.getFluidState().isSource() && !state.hasProperty(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Whether the world's block is the water or lava the target pours: a source of it, as the liquid itself or in
     * something wet that is full of it, such as kelp, seagrass or a bubble column. Kelp grows up into the water over it,
     * and water over soul sand turns into a bubble column.
     */
    public static boolean holdsFluid(BlockState current, BlockState target) {
        FluidState fluid = current.getFluidState();
        return fluid.isSource() && fluid.getType().isSame(target.getFluidState().getType()) && wet(current);
    }

    /**
     * Whether right-clicking the block can use it rather than place against it, as with chests, doors, levers and
     * repeaters. The builder sneaks to place against these, which keeps them as they are.
     */
    public static boolean interactive(Block block) {
        return INTERACTIVE.get(block.getClass());
    }
}
