package dev.donut.client.finder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** Block and chunk changes the client receives, forwarded from {@code ClientLevelMixin} on the client thread. */
public final class ChunkEvents {
    public interface Listener {
        /** Any change to a loaded block: server updates, block predictions and chunk section updates. */
        default void blockChanged(ClientLevel level, BlockPos pos, BlockState previous, BlockState current) { }

        /** The server sent this exact block, even when it matches what the client already had. */
        default void serverBlock(ClientLevel level, BlockPos pos, BlockState state) { }

        /** A chunk arrived, or its data was replaced in place. */
        default void chunkLoaded(ClientLevel level, ChunkPos pos) { }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ChunkEvents() { }

    public static void register(Listener listener) { LISTENERS.add(listener); }

    public static void unregister(Listener listener) { LISTENERS.remove(listener); }

    public static void blockChanged(ClientLevel level, BlockPos pos, BlockState previous, BlockState current) {
        for (Listener listener : LISTENERS) listener.blockChanged(level, pos, previous, current);
    }

    public static void serverBlock(ClientLevel level, BlockPos pos, BlockState state) {
        for (Listener listener : LISTENERS) listener.serverBlock(level, pos, state);
    }

    public static void chunkLoaded(ClientLevel level, ChunkPos pos) {
        for (Listener listener : LISTENERS) listener.chunkLoaded(level, pos);
    }
}
