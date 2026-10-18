package dev.donut.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The ticks left before held attack starts on the next block after one broke: the schematic builder keeps the button
 * down through the turn to the next helper, as a player clearing a row does, only while this is above zero.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccess {
    @Accessor("destroyDelay")
    int donut$destroyDelay();
}
