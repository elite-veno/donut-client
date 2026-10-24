package dev.donut.client.mixin;

import dev.donut.client.finder.ChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the finders rescan a chunk as soon as one of its blocks changes. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void donut$blockChanged(BlockPos pos, BlockState previous, BlockState current, int flags, CallbackInfo ci) {
        ChunkEvents.blockChanged((ClientLevel) (Object) this, pos, previous, current);
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void donut$serverBlock(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ChunkEvents.serverBlock((ClientLevel) (Object) this, pos, state);
    }

    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void donut$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
        ChunkEvents.chunkLoaded((ClientLevel) (Object) this, pos);
    }
}
