package dev.donut.client.miner;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * What one swing breaks: a normal tool the block hit, an area pickaxe such as DonutSMP's Amethyst Pickaxe a square of
 * blocks around it, flat against the face hit (3x3 for radius 1).
 *
 * <p>Plugins disagree on whether the square follows the face hit or the way the player looks; the two only differ
 * when the face hit is not the one the look points at, so the miner only swings an area pickaxe with a look that
 * agrees with its face ({@link #agrees}).
 */
public final class ToolShape {
    public static final ToolShape SINGLE = new ToolShape(0);
    private static final ToolShape[] AREAS = {SINGLE, new ToolShape(1), new ToolShape(2)};

    public final int radius;

    private ToolShape(int radius) { this.radius = radius; }

    public static ToolShape area(int radius) { return AREAS[Math.clamp(radius, 0, AREAS.length - 1)]; }

    public boolean area() { return radius > 0; }

    /** The blocks a swing at {@code face} of {@code pos} breaks, the block hit first. */
    public List<BlockPos> cells(BlockPos pos, Direction face) {
        if (radius == 0) return List.of(pos);
        List<BlockPos> cells = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        cells.add(pos);
        Direction.Axis axis = face.getAxis();
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (a == 0 && b == 0) continue;
                cells.add(switch (axis) {
                    case Y -> pos.offset(a, 0, b);
                    case Z -> pos.offset(a, b, 0);
                    case X -> pos.offset(0, b, a);
                });
            }
        }
        return cells;
    }

    /**
     * Whether a look agrees with the face it hits about which way it points: looking well down at a top face, well up
     * at a bottom face, or level-ish straight at a side face.
     */
    public static boolean agrees(Direction face, float yaw, float pitch) {
        return switch (face) {
            // Plugins that go by the look split it at 45 degrees; a degree either side keeps clear of rounding.
            case UP -> pitch >= 46;
            case DOWN -> pitch <= -46;
            default -> {
                if (Math.abs(pitch) > 44) yield false;
                // The look's way across the ground, against the face's: they must point at each other, within 40 degrees.
                double lx = -Math.sin(Math.toRadians(yaw)), lz = Math.cos(Math.toRadians(yaw));
                double dot = -(lx * face.getStepX() + lz * face.getStepZ());
                yield dot >= Math.cos(Math.toRadians(40));
            }
        };
    }
}
