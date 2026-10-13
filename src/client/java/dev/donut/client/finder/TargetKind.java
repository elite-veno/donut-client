package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

public enum TargetKind {
    CHEST("Chest"), TRAPPED_CHEST("Trapped chest"), ENDER_CHEST("Ender chest"), SHULKER("Shulker box"),
    DROPPER("Dropper"), SPAWNER("Spawner"), TRIAL_SPAWNER("Trial spawner"), ANCIENT_DEBRIS("Ancient debris"),
    BARREL("Barrel"), HOPPER("Hopper"), DISPENSER("Dispenser"), FURNACE("Furnace");

    private final String label;

    TargetKind(String label) { this.label = label; }

    public String label() { return label; }
    public boolean isSpawner() { return this == SPAWNER || this == TRIAL_SPAWNER; }
    public boolean isDebris() { return this == ANCIENT_DEBRIS; }
    public boolean isStorage() { return !isSpawner() && !isDebris(); }

    public FinderSettings settings(ClientConfig config) {
        return isSpawner() ? config.spawner : isDebris() ? config.debris : config.storage;
    }

    public boolean included(FinderSettings settings) {
        return switch (this) {
            case CHEST -> settings.chests;
            case TRAPPED_CHEST -> settings.trappedChests;
            case ENDER_CHEST -> settings.enderChests;
            case SHULKER -> settings.shulkers;
            case DROPPER -> settings.droppers;
            case BARREL -> settings.barrels;
            case HOPPER -> settings.hoppers;
            case DISPENSER -> settings.dispensers;
            case FURNACE -> settings.furnaces;
            case SPAWNER, ANCIENT_DEBRIS -> true;
            case TRIAL_SPAWNER -> settings.trialSpawners;
        };
    }

    public static TargetKind from(BlockState state) {
        if (state.is(Blocks.CHEST)) return CHEST;
        if (state.is(Blocks.TRAPPED_CHEST)) return TRAPPED_CHEST;
        if (state.is(Blocks.ENDER_CHEST)) return ENDER_CHEST;
        if (state.getBlock() instanceof ShulkerBoxBlock) return SHULKER;
        if (state.is(Blocks.DROPPER)) return DROPPER;
        if (state.is(Blocks.SPAWNER)) return SPAWNER;
        if (state.is(Blocks.TRIAL_SPAWNER)) return TRIAL_SPAWNER;
        if (state.is(Blocks.ANCIENT_DEBRIS)) return ANCIENT_DEBRIS;
        if (state.is(Blocks.BARREL)) return BARREL;
        if (state.is(Blocks.HOPPER)) return HOPPER;
        if (state.is(Blocks.DISPENSER)) return DISPENSER;
        if (state.getBlock() instanceof AbstractFurnaceBlock) return FURNACE;
        return null;
    }
}
