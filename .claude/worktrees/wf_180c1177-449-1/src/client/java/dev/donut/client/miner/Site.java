package dev.donut.client.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** What the miner needs to know about the world: what each block is, and what the eye sees. */
public interface Site {
    /** Nothing to break and nothing to bump into: air, cave air, grass and the like. Never set with {@link #FLUID}. */
    int CLEAR = 1;
    /** Has a collision box, so it can be stood on. */
    int SOLID = 2;
    /** Wants breaking: a block a tool takes away. */
    int DIG = 4;
    /** Never broken: bedrock, barriers, and blocks with contents such as chests and spawners. */
    int HARD = 8;
    /** Water or lava, or a block holding water. */
    int FLUID = 16;
    int LAVA = 32;
    /** Falls once what is under it goes: sand, gravel, concrete powder, pointed dripstone. */
    int FALLS = 64;
    /** Not loaded, so unknown. */
    int UNLOADED = 128;

    int flags(int x, int y, int z);

    default int flags(BlockPos pos) { return flags(pos.getX(), pos.getY(), pos.getZ()); }

    default boolean is(BlockPos pos, int flag) { return (flags(pos) & flag) != 0; }

    /** Whether a look from {@code eye} through {@code point} meets {@code pos} on {@code face} before anything else. */
    boolean sees(Vec3 eye, Vec3 point, BlockPos pos, Direction face);
}
