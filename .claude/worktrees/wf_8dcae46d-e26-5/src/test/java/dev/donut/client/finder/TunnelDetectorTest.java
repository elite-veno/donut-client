package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TunnelDetectorTest {
    /** Solid rock with dug-out cells; outside its bounds nothing is known, like an unloaded chunk. */
    private static class Rock implements TunnelDetector.Grid {
        final int size;
        final Set<Long> air = new HashSet<>();

        Rock(int size) { this.size = size; }

        void dig(int x, int y, int z) { air.add(key(x, y, z)); }

        boolean inside(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < size && y < size && z < size; }

        @Override public boolean open(int x, int y, int z) { return inside(x, y, z) && air.contains(key(x, y, z)); }

        @Override public boolean solid(int x, int y, int z) { return inside(x, y, z) && !air.contains(key(x, y, z)); }

        static long key(int x, int y, int z) { return ((long) x << 40) | ((long) y << 20) | z; }

        List<TunnelDetector.Segment> scan(int minLength) {
            TunnelDetector detector = new TunnelDetector(this, minLength);
            List<TunnelDetector.Segment> found = new ArrayList<>();
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) detector.scanCell(x, y, z, true, true, true, found);
                }
            }
            return found;
        }
    }

    @Test
    void straightTunnelIsFoundOnceFromItsFirstBlock() {
        Rock rock = new Rock(40);
        for (int x = 5; x < 17; x++) { rock.dig(x, 10, 20); rock.dig(x, 11, 20); }

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertEquals(List.of(new TunnelDetector.Segment(TunnelDetector.Kind.TUNNEL, 5, 10, 20, 1, 0, 0, 12)), found);
    }

    @Test
    void tunnelsAlongZAreFoundToo() {
        Rock rock = new Rock(40);
        for (int z = 3; z < 30; z++) { rock.dig(8, 4, z); rock.dig(8, 5, z); }

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertEquals(1, found.size());
        assertEquals(TunnelDetector.Kind.TUNNEL, found.getFirst().kind());
        assertEquals(1, found.getFirst().dz());
        assertEquals(27, found.getFirst().length());
    }

    @Test
    void shortTunnelsAreIgnored() {
        Rock rock = new Rock(40);
        for (int x = 5; x < 14; x++) { rock.dig(x, 10, 20); rock.dig(x, 11, 20); }

        assertTrue(rock.scan(10).isEmpty());
        assertEquals(1, rock.scan(9).size());
    }

    @Test
    void wideCorridorsAndOpenCavesAreNotTunnels() {
        Rock rock = new Rock(40);
        // Two blocks wide, like a mineshaft or a cave passage.
        for (int x = 2; x < 30; x++) {
            for (int y = 10; y < 12; y++) { rock.dig(x, y, 20); rock.dig(x, y, 21); }
        }
        // A large open cave.
        for (int x = 2; x < 30; x++) {
            for (int y = 20; y < 26; y++) {
                for (int z = 2; z < 10; z++) rock.dig(x, y, z);
            }
        }

        assertTrue(rock.scan(8).isEmpty());
    }

    @Test
    void verticalShaftIsAHole() {
        Rock rock = new Rock(40);
        for (int y = 3; y < 20; y++) rock.dig(10, y, 10);

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertEquals(List.of(new TunnelDetector.Segment(TunnelDetector.Kind.HOLE, 10, 3, 10, 0, 1, 0, 17)), found);
    }

    @Test
    void staircaseIsFoundStepByStep() {
        Rock rock = new Rock(40);
        // Walking down along +x: each column is three blocks tall, one lower than the last.
        int steps = 9;
        for (int i = 0; i < steps; i++) {
            for (int dy = 0; dy < 3; dy++) rock.dig(5 + i, 25 - i + dy, 12);
        }

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertEquals(List.of(new TunnelDetector.Segment(TunnelDetector.Kind.STAIRS, 5, 25, 12, 1, -1, 0, steps)), found);
    }

    @Test
    void passagesStopAtUnknownBlocks() {
        Rock rock = new Rock(20);
        // The tunnel leaves the known area at x = 19, so only its known part counts.
        for (int x = 8; x < 20; x++) { rock.dig(x, 10, 10); rock.dig(x, 11, 10); }

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertEquals(1, found.size());
        assertEquals(12, found.getFirst().length());
    }

    @Test
    void cellHeightsMatchEachKind() {
        assertEquals(2, new TunnelDetector.Segment(TunnelDetector.Kind.TUNNEL, 0, 0, 0, 1, 0, 0, 1).cellHeight());
        assertEquals(1, new TunnelDetector.Segment(TunnelDetector.Kind.HOLE, 0, 0, 0, 0, 1, 0, 1).cellHeight());
        assertEquals(3, new TunnelDetector.Segment(TunnelDetector.Kind.STAIRS, 0, 0, 0, 1, -1, 0, 1).cellHeight());
    }

    @Test
    void longTunnelsAreNotCutShort() {
        Rock rock = new Rock(230);
        for (int x = 5; x < 205; x++) { rock.dig(x, 10, 20); rock.dig(x, 11, 20); }

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertEquals(List.of(new TunnelDetector.Segment(TunnelDetector.Kind.TUNNEL, 5, 10, 20, 1, 0, 0, 200)), found);
    }

    @Test
    void eachAreaReportsItsOwnPieceMeasuredOverTheWholeTunnel() {
        // Only columns 16 to 31 are scanned, like one chunk; the tunnel starts in the chunk before it.
        Rock rock = new Rock(40) {
            @Override public boolean inside(int x, int z) { return x >= 16 && x < 32; }
        };
        for (int x = 10; x < 30; x++) { rock.dig(x, 10, 20); rock.dig(x, 11, 20); }
        TunnelDetector detector = new TunnelDetector(rock, 18);
        List<TunnelDetector.Segment> found = new ArrayList<>();
        for (int x = 16; x < 32; x++) {
            for (int y = 0; y < 40; y++) {
                for (int z = 0; z < 40; z++) detector.scanCell(x, y, z, true, true, true, found);
            }
        }

        // 14 of its 20 cells lie here; only the whole length reaches 18.
        assertEquals(List.of(new TunnelDetector.Segment(TunnelDetector.Kind.TUNNEL, 16, 10, 20, 1, 0, 0, 14, 20)), found);
    }

    @Test
    void aBranchMineCorridorSurvivesItsSideTunnels() {
        Rock rock = new Rock(40);
        for (int x = 5; x < 25; x++) { rock.dig(x, 10, 20); rock.dig(x, 11, 20); }
        // Side tunnels every four blocks, each opening the corridor's wall at one cell.
        for (int x = 8; x < 23; x += 4) {
            for (int z = 21; z < 26; z++) { rock.dig(x, 10, z); rock.dig(x, 11, z); }
        }

        List<TunnelDetector.Segment> found = rock.scan(10);

        assertTrue(found.contains(new TunnelDetector.Segment(TunnelDetector.Kind.TUNNEL, 5, 10, 20, 1, 0, 0, 20)), found.toString());
    }

    @Test
    void twoWideCorridorsStillDoNotCount() {
        Rock rock = new Rock(40);
        for (int x = 5; x < 25; x++) {
            for (int z = 20; z < 22; z++) { rock.dig(x, 10, z); rock.dig(x, 11, z); }
        }

        assertTrue(rock.scan(10).stream().noneMatch(segment -> segment.kind() == TunnelDetector.Kind.TUNNEL && segment.dx() == 1));
    }
}
