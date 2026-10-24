package dev.donut.client.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The state an item would place for a click, without placing it: the schematic builder tries clicks and looks until
 * one makes the block it needs. Torches, signs and banners choose between their standing and wall blocks in here.
 */
@Mixin(BlockItem.class)
public interface BlockItemAccess {
    @Invoker("getPlacementState")
    BlockState donut$placementState(BlockPlaceContext context);
}
