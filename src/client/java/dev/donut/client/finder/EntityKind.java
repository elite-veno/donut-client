package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;

/** Entities that EntityFinder, and StorageFinder for minecarts, can highlight. */
public enum EntityKind {
    PLAYER, HOSTILE, PASSIVE, ITEM, STORAGE_MINECART;

    public static EntityKind of(Entity entity) {
        if (entity instanceof Player) return entity == Minecraft.getInstance().player ? null : PLAYER;
        // Chest and hopper minecarts, and chest boats and rafts.
        if (entity instanceof ContainerEntity) return STORAGE_MINECART;
        if (entity instanceof ItemEntity) return ITEM;
        if (entity instanceof Enemy) return HOSTILE;
        if (entity instanceof Mob) return PASSIVE;
        return null;
    }

    public FinderSettings settings(ClientConfig config) {
        return this == STORAGE_MINECART ? config.storage : config.entities;
    }

    public boolean included(FinderSettings settings) {
        return switch (this) {
            case PLAYER -> settings.players;
            case HOSTILE -> settings.hostiles;
            case PASSIVE -> settings.passives;
            case ITEM -> settings.items;
            case STORAGE_MINECART -> settings.minecarts;
        };
    }

    public int color(FinderSettings settings) {
        return switch (this) {
            case PLAYER -> settings.color;
            case HOSTILE -> settings.secondaryColor;
            case PASSIVE -> settings.tertiaryColor;
            case ITEM -> settings.itemColor;
            case STORAGE_MINECART -> settings.perTypeColors ? settings.chestColor : settings.color;
        };
    }
}
