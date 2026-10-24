package dev.donut.client.mixin;

import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The spawn timer arrives with the spawner's block entity data. */
@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccessor {
    @Accessor("spawnDelay")
    int donut$getSpawnDelay();

    /** How close a player has to be for the timer to run; the server sends it with the timer. */
    @Accessor("requiredPlayerRange")
    int donut$getRequiredPlayerRange();
}
