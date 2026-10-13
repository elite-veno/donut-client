package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks DebrisFinder's seed on a live server without anyone at the keyboard. It runs once, only when
 * {@code config/donut-debris-livetest} exists (which it deletes) or the game was started with
 * {@code -Ddonut.debrisLiveTest=true}: once connected it sends {@code /rtp nether}, waits for the Nether to load,
 * and writes what DebrisFinder sees there to the log, and the chunks to donut-dumps, at four random places.
 */
public final class DebrisLiveTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/DebrisLiveTest");
    private static final int SAMPLES = 4;
    private static final int SETTLE_TICKS = 30 * 20;
    private static final int RTP_TIMEOUT_TICKS = 45 * 20;
    private static final int MAX_RTPS = 6;

    private enum State { JOINING, RTP, ARRIVING, SETTLING, DONE }

    private final ClientConfig config;
    private final FinderScanner scanner;
    private State state = State.JOINING;
    private int timer = 15 * 20;
    private int samples;
    private int rtps;
    private Vec3 departure;
    private boolean departedFromNether;
    private float health;

    private DebrisLiveTest(ClientConfig config, FinderScanner scanner) {
        this.config = config;
        this.scanner = scanner;
    }

    public static void registerIfRequested(ClientConfig config, FinderScanner scanner) {
        Path marker = FabricLoader.getInstance().getConfigDir().resolve("donut-debris-livetest");
        boolean requested = Boolean.getBoolean("donut.debrisLiveTest") || Files.exists(marker);
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            LOGGER.warn("[DebrisLiveTest] could not delete {}", marker, e);
        }
        if (!requested) return;
        DebrisLiveTest test = new DebrisLiveTest(config, scanner);
        ClientTickEvents.END_CLIENT_TICK.register(test::tick);
        LOGGER.info("[DebrisLiveTest] armed; waiting for a server");
    }

    private void tick(Minecraft client) {
        if (state == State.DONE || client.player == null || client.level == null || client.getCurrentServer() == null) return;
        if (state != State.JOINING && client.player.getHealth() < health - 3) {
            finish("stopped: the player took damage at " + position(client));
            return;
        }
        health = Math.max(health, client.player.getHealth());
        boolean nether = client.level.dimension() == Level.NETHER;
        // Arrived once in the Nether, and far from where the command was sent if that was the Nether too.
        if (state == State.ARRIVING && nether && (!departedFromNether || client.player.position().distanceTo(departure) > 64)) {
            settle(client);
            return;
        }
        if (--timer > 0) return;
        switch (state) {
            case JOINING -> {
                config.debris.enabled = true;
                health = client.player.getHealth();
                LOGGER.info("[DebrisLiveTest] joined {} at {}", client.getCurrentServer().ip, position(client));
                if (nether) settle(client); else rtp(client);
            }
            case RTP -> rtp(client);
            case ARRIVING -> {
                // Timed out: the server ignored the command or is on cooldown.
                LOGGER.info("[DebrisLiveTest] no teleport after {} s, still at {}", RTP_TIMEOUT_TICKS / 20, position(client));
                rtp(client);
            }
            case SETTLING -> {
                samples++;
                DebrisStats s = scanner.debrisStats();
                LOGGER.info("[DebrisLiveTest] sample {} at {}: {} chunks, bedrock {} of {} match ({} bedrock), debris on seed spots {} of {}, "
                                + "buried {}, exposed {}, faked chunks {}, estimates {} -> {}", samples, position(client), s.scannedChunks(),
                        s.bedrockMatched(), s.bedrockChecked(), s.bedrockSeen(), s.seedMatched(), s.seedChecked(), s.buried(), s.exposed(),
                        s.fakedChunks(), s.estimates(), verdict(s));
                DebrisPredictor.parseSeed(config.debris.seed).ifPresent(seed -> LOGGER.info("[DebrisLiveTest] sample {}: {}", samples,
                        FeatureProbe.run(client.level, client.player.chunkPosition(), 6, seed).describe()));
                try {
                    var file = ChunkDump.write(FabricLoader.getInstance().getGameDir().resolve("donut-dumps"), client.level,
                            client.player.blockPosition(), 8);
                    LOGGER.info("[DebrisLiveTest] sample {}: saved chunks to {}", samples, file);
                } catch (IOException e) {
                    LOGGER.warn("[DebrisLiveTest] sample {}: could not save chunks", samples, e);
                }
                if (samples >= SAMPLES) finish("done"); else rtp(client);
            }
            default -> { }
        }
    }

    private void rtp(Minecraft client) {
        if (rtps++ >= MAX_RTPS) { finish("stopped: no teleport after " + MAX_RTPS + " tries"); return; }
        departure = client.player.position();
        departedFromNether = client.level.dimension() == Level.NETHER;
        LOGGER.info("[DebrisLiveTest] /rtp nether (try {}) from {}", rtps, position(client));
        client.player.connection.sendCommand("rtp nether");
        state = State.ARRIVING;
        timer = RTP_TIMEOUT_TICKS;
    }

    private void settle(Minecraft client) {
        LOGGER.info("[DebrisLiveTest] in the Nether at {}; scanning for {} s", position(client), SETTLE_TICKS / 20);
        state = State.SETTLING;
        timer = SETTLE_TICKS;
    }

    private void finish(String why) {
        state = State.DONE;
        LOGGER.info("[DebrisLiveTest] {}", why);
    }

    private static String verdict(DebrisStats s) {
        if (!s.nether()) return "not in the Nether";
        if (s.bedrockSeen() == 0 && s.bedrockChecked() > 0) return "flat bedrock: the server changed bedrock, no bedrock check";
        if (!s.bedrockReady()) return "not enough bedrock loaded yet";
        if (s.bedrockMismatch()) return "bedrock does NOT match: the seed is not this Nether's";
        if (s.debrisMismatch()) return "bedrock matches but debris does not: the server randomizes ore placement";
        if (s.seedChecked() == 0) return "bedrock matches the seed; no visible debris to check yet";
        return "bedrock and debris match the seed";
    }

    private static String position(Minecraft client) {
        var p = client.player.blockPosition();
        return p.getX() + " " + p.getY() + " " + p.getZ() + " in " + client.level.dimension().identifier().getPath();
    }
}
