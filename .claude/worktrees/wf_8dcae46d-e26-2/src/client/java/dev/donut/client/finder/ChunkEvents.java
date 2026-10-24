package dev.donut.client.finder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Finder");
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean failed;

    private ChunkEvents() { }

    public static void register(Listener listener) { LISTENERS.add(listener); }

    public static void unregister(Listener listener) { LISTENERS.remove(listener); }

    // These run inside packet handling, where an exception disconnects the player; a finder bug must not.
    public static void blockChanged(ClientLevel level, BlockPos pos, BlockState previous, BlockState current) {
        for (Listener listener : LISTENERS) {
            try {
                listener.blockChanged(level, pos, previous, current);
            } catch (RuntimeException e) {
                failed(e);
            }
        }
    }

    public static void serverBlock(ClientLevel level, BlockPos pos, BlockState state) {
        for (Listener listener : LISTENERS) {
            try {
                listener.serverBlock(level, pos, state);
            } catch (RuntimeException e) {
                failed(e);
            }
        }
    }

    public static void chunkLoaded(ClientLevel level, ChunkPos pos) {
        for (Listener listener : LISTENERS) {
            try {
                listener.chunkLoaded(level, pos);
            } catch (RuntimeException e) {
                failed(e);
            }
        }
    }

    private static void failed(RuntimeException e) {
        if (failed) return;
        failed = true;
        LOGGER.error("A finder failed on a block or chunk update; later failures are not logged", e);
    }
}
