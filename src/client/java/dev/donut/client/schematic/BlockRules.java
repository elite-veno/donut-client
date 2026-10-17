package dev.donut.client.schematic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * What the builder can make of each block: the properties placing it decides, how many more of its item still go in,
 * and the clicks that set what placing can't, such as a repeater's delay. Everything else about a state, such as a
 * wire's connections or whether something is powered, the game works out by itself once the blocks around it are in.
 */
public final class BlockRules {
    public enum Kind {
        /** Placed with its item. */
        PLACE,
        /** Comes with another block: a door's or tall flower's top half, a bed's head, a piston's head. */
        AUTO,
        /** No item places it, as with water, fire, portals and filled cauldrons. */
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

    private BlockRules() { }

    public static Kind kind(BlockState target) {
        Block block = target.getBlock();
        if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) return Kind.AUTO;
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return Kind.AUTO;
        if (target.hasProperty(BlockStateProperties.BED_PART) && target.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return Kind.AUTO;
        if (block instanceof LiquidBlock || item(target) == null) return Kind.UNPLACEABLE;
        return Kind.PLACE;
    }

    /** The item that places the block, or null when none does. */
    public static Item item(BlockState target) {
        Item item = target.getBlock().asItem();
        return item instanceof BlockItem ? item : null;
    }

    /**
     * Whether a block just placed, or about to be, starts the target: the same block, with every setting placing
     * decides the same. A slab of a double slab and the first of several candles count.
     */
    public static boolean placementMatches(BlockState placed, BlockState target) {
        if (placed.getBlock() != target.getBlock()) return false;
        for (Property<?> property : target.getProperties()) {
            Object want = target.getValue(property), got = placed.getValue(property);
            Class<?> type = property.getValueClass();
            if (type == SlabType.class) {
                if (want != SlabType.DOUBLE && got != want) return false;
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
        return placementMatches(current, target) && missing(current, target) == 0 && adjustClicks(current, target) == 0;
    }

    /** How many more of the item still go in the same spot: candles, sea pickles, snow layers, the second slab. */
    public static int missing(BlockState current, BlockState target) {
        if (current.getBlock() != target.getBlock()) return 0;
        int missing = 0;
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
     * possible.
     */
    public static int group(BlockState state) {
        Block block = state.getBlock();
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
     * Whether right-clicking the block can use it rather than place against it, as with chests, doors, levers and
     * repeaters. The builder sneaks to place against these, which keeps them as they are.
     */
    public static boolean interactive(Block block) {
        return INTERACTIVE.get(block.getClass());
    }
}
