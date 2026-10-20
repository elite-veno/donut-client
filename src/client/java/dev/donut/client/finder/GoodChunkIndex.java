package dev.donut.client.finder;

import dev.donut.client.config.FinderSettings;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * ChunkFinder: reads every loaded chunk for signs of players and picks base spots away from them. The signs are any
 * block only players or structures place ({@link BuiltBlocks}), placed entities such as item frames and boats,
 * passages players dig (TunnelFinder's detector), plants that grew on random ticks ({@link GrowthClocks}), amethyst
 * grown past worldgen (ActivityFinder's test) and players in sight. A spot also needs solid ground under it
 * ({@link Caves}). A chunk's plants and caves are read once, the first time it is scanned, and kept per dimension for
 * the connection: from then on our own presence grows the plants too, and the chunk sent again later would carry it.
 */
final class GoodChunkIndex {
    /** Our own presence is followed this far back, in game ticks: half an hour. */
    private static final int TRAIL_TICKS = 36_000;
    private static final int TRAIL_SEGMENTS = 4096;
    /** First sights kept per dimension; past this, those far from the newest are forgotten. */
    private static final int MAX_SIGHTS = 200_000;
    /** Before the server sends its simulation distance, a wide one: it only makes more of our own time count as ours. */
    private static final int ASSUMED_SIMULATION_DISTANCE = 12;
    /** Spots handed out per pass, best first. */
    private static final int MAX_SPOTS = 16;
    /** How far above or below the blocks that give a structure away its other blocks reach. */
    private static final int STRUCTURE_REACH = 16;
    /** A block this close below the surface sits on it; one deeper than {@link #BURIED} is underground. */
    private static final int ON_SURFACE = 3, BURIED = 6;
    /** What {@link GoodChunk#confidence()} is about: a visit this long, in minutes. */
    static final double CONFIDENCE_MINUTES = 10;
    private static final Predicate<BlockState> CLOCK = state -> state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS)
            || state.is(Blocks.KELP) || state.is(Blocks.VINE);
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private final ChunkIndex<Scan> chunks = new ChunkIndex<>(this::scan, 8, 200);
    private final TunnelIndex tunnels = new TunnelIndex();

    GoodChunkIndex() {
        // Fresh worldgen has 1x1 shafts 11 deep now and then; a player's way down after a teleport is far longer.
        tunnels.configure(16, true, true, true);
    }
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Sight>> sights = new HashMap<>();
    private Long2ObjectOpenHashMap<Sight> firstSight = new Long2ObjectOpenHashMap<>();
    /** The chunks the player stood in, oldest first, each as its packed position and the ticks spent there. */
    private final ArrayDeque<long[]> trail = new ArrayDeque<>();
    private int trailTicks;
    private int simulationDistance = ASSUMED_SIMULATION_DISTANCE;
    private ChunkStats stats = ChunkStats.EMPTY;

    /** Drops the scans; first sights and our trail stay, so turning ChunkFinder back on does not judge our own growth. */
    void clear() {
        chunks.clear();
        tunnels.clear();
        stats = ChunkStats.EMPTY;
    }

    /** A new level: its dimension's first sights, and a trail that starts here. */
    void useLevel(ClientLevel level) {
        clear();
        firstSight = sights.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>());
        trail.clear();
        trailTicks = 0;
    }

    /** Left the server: what we saw there means nothing on the next one. */
    void forget() {
        clear();
        sights.clear();
        firstSight = new Long2ObjectOpenHashMap<>();
        trail.clear();
        trailTicks = 0;
    }

    ChunkStats stats() { return stats; }

    /** Call every tick, whether ChunkFinder is on or not, so time spent nearby before turning it on counts as ours. */
    void follow(ClientLevel level, ChunkPos player) {
        int distance = level.getServerSimulationDistance();
        simulationDistance = distance > 0 ? distance : ASSUMED_SIMULATION_DISTANCE;
        long key = player.pack();
        long[] last = trail.peekLast();
        if (last != null && last[0] == key) last[1]++;
        else trail.addLast(new long[] {key, 1});
        trailTicks++;
        // Only the ticks past the limit go, from the oldest stay: a long one isn't forgotten all at once, nor the one under
        // way when it is the only one.
        while (trailTicks > TRAIL_TICKS) {
            long[] first = trail.peekFirst();
            int over = trailTicks - TRAIL_TICKS;
            if (first[1] > over) {
                first[1] -= over;
                trailTicks -= over;
            } else {
                trailTicks -= (int) first[1];
                trail.removeFirst();
            }
        }
        while (trail.size() > TRAIL_SEGMENTS) trailTicks -= (int) trail.removeFirst()[1];
    }

    void tick(ClientLevel level, ChunkPos center, double range) {
        chunks.tick(level, center, range);
        tunnels.tick(level, center, range);
    }

    /** A built block or amethyst changed here. */
    void markDirty(int chunkX, int chunkZ) { chunks.markDirty(chunkX, chunkZ); }

    /** A block turned open or solid, which can make or end a dug passage. */
    void passageChanged(BlockPos pos) { tunnels.markDirty(pos); }

    /**
     * A chunk arrived: it and its neighbors are read again, their passages too. A neighbor's blocks facing it were read
     * with nothing known beyond them, a build's open face among them.
     */
    void chunkLoaded(ChunkPos pos) {
        chunks.markDirty(pos.x(), pos.z());
        chunks.markDirty(pos.x() - 1, pos.z());
        chunks.markDirty(pos.x() + 1, pos.z());
        chunks.markDirty(pos.x(), pos.z() - 1);
        chunks.markDirty(pos.x(), pos.z() + 1);
        tunnels.markDirty(pos);
    }

    /** Ticks we spent within simulation distance of this chunk, where the server random-ticks it for us. */
    private int exposure(ChunkPos pos) {
        long ticks = 0;
        for (long[] segment : trail) {
            int dx = ChunkPos.getX(segment[0]) - pos.x(), dz = ChunkPos.getZ(segment[0]) - pos.z();
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= simulationDistance) ticks += segment[1];
        }
        return (int) ticks;
    }

    /**
     * Judges every scanned chunk and picks spots around {@code origin}.
     *
     * @param self the player, whose own presence is no sign
     * @param rtpZones random teleport landings in this world, which spots keep the setting's RTP distance from
     */
    void collect(ClientLevel level, Entity self, Vec3 origin, FinderSettings settings, List<RtpZones.Zone> rtpZones,
                 List<GoodChunk> spots, List<ChunkSign> signs) {
        // Live signs first: what is in sight now, by chunk.
        Long2ObjectOpenHashMap<String> live = new Long2ObjectOpenHashMap<>();
        LongSet structures = new LongOpenHashSet();
        for (Player player : level.players()) {
            if (player == self || EntityKind.of(player) != EntityKind.PLAYER) continue;
            live.put(player.chunkPosition().pack(), "Player here: " + player.getName().getString());
        }
        for (Entity entity : level.entitiesForRendering()) {
            // Mineshafts leave chest minecarts.
            String placed = placed(entity);
            if (entity instanceof MinecartChest) structures.add(entity.chunkPosition().pack());
            else if (placed != null) live.putIfAbsent(entity.chunkPosition().pack(), "Built: " + placed);
        }
        List<TunnelDetector.Segment> dug = new ArrayList<>();
        tunnels.collect(origin, Double.MAX_VALUE, dug);
        for (TunnelDetector.Segment segment : dug) {
            String passage = switch (segment.kind()) {
                case TUNNEL -> "Dug tunnel, " + segment.total() + " long";
                case HOLE -> "Dug hole, " + segment.total() + " deep";
                case STAIRS -> "Dug staircase, " + segment.total() + " steps";
            };
            for (int i = 0; i < segment.length(); i++) {
                int x = segment.x() + segment.dx() * i, z = segment.z() + segment.dz() * i;
                live.putIfAbsent(ChunkPos.pack(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)), passage);
            }
        }

        structures(structures);

        double maxCaves = settings.maxCaves;
        List<SpotPicker.Cell> cells = new ArrayList<>();
        int[] counts = new int[3];
        chunks.forEach(scan -> {
            long key = scan.chunk.pack();
            // Its passages not read yet, a player's tunnel or staircase there not known: as a chunk not read.
            if (!tunnels.indexed(scan.chunk.x(), scan.chunk.z())) return;
            boolean structure = structures.contains(key);
            String reason = reason(scan, live.remove(key), structure, settings);
            // Too near a random teleport landing to dig in, though read and clean for the chunks round it: left out before
            // the spots are picked and spaced, so the spacing doesn't keep away spots that do count.
            double x = scan.chunk.getMinBlockX() + 8, z = scan.chunk.getMinBlockZ() + 8;
            boolean far = settings.rtpDistance <= 0 || RtpZones.distance(rtpZones, x, z) >= settings.rtpDistance;
            cells.add(new SpotPicker.Cell(scan.chunk.x(), scan.chunk.z(), reason != null, structure,
                    scan.sight.caves <= maxCaves && far, scan.sight.clocks));
            if (scan.sight.clocks.clocks() > 0) counts[0]++;
            if (reason != null) {
                counts[1]++;
                signs.add(new ChunkSign(scan.chunk, scan.sight.surfaceY, reason, false));
            } else if (structure) {
                counts[2]++;
                signs.add(new ChunkSign(scan.chunk, scan.sight.surfaceY,
                        "Structure" + (scan.built == null ? "" : ": " + scan.built.names()), true));
            }
        });
        // Signs in chunks not read yet still keep spots away.
        for (long key : live.keySet()) cells.add(new SpotPicker.Cell(ChunkPos.getX(key), ChunkPos.getZ(key), true, false, false, null));

        int clearance = (int) Math.round(settings.minClearance);
        ChunkPos center = ChunkPos.containing(BlockPos.containing(origin));
        for (SpotPicker.Spot spot : SpotPicker.pick(cells, clearance, settings.maxUnseen, center.x(), center.z(), MAX_SPOTS)) {
            Scan scan = chunks.get(spot.x(), spot.z());
            spots.add(new GoodChunk(scan.chunk, scan.sight.surfaceY, spot.area().detectChance(CONFIDENCE_MINUTES), spot.clear(),
                    spot.area().clocks(), spot.unseenMinutes(), scan.sight.caves));
        }
        boolean overworld = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD);
        int roomy = spots.isEmpty() ? SpotPicker.roomy(cells, clearance, false) : spots.size();
        int solid = spots.isEmpty() ? SpotPicker.roomy(cells, clearance, true) : spots.size();
        stats = new ChunkStats(overworld, chunks.size(), counts[0], counts[1], counts[2], roomy, solid);
    }

    /**
     * Adds the chunks that are part of a structure: those with blocks that give one away, and chunks of plain built
     * blocks joined to them at the same level, such as a mineshaft's corridors without rails or a village's houses away
     * from its paths. A build on the ground next to a mineshaft's chunk stays someone's build.
     */
    private void structures(LongSet structures) {
        // Plain built chunks by whether all their blocks are underground; a structure's by where what gives it away is.
        Long2BooleanOpenHashMap plain = new Long2BooleanOpenHashMap(), level = new Long2BooleanOpenHashMap();
        for (long key : structures) level.put(key, true);
        chunks.forEach(scan -> {
            if (scan.built == null) return;
            if (scan.built.structure()) level.put(scan.chunk.pack(), scan.built.buried());
            else plain.put(scan.chunk.pack(), scan.built.buried());
        });
        structures.addAll(level.keySet());
        ArrayDeque<Long> queue = new ArrayDeque<>(structures);
        while (!queue.isEmpty()) {
            long key = queue.poll();
            boolean buried = level.get(key);
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long near = ChunkPos.pack(x + dx, z + dz);
                    if (!plain.containsKey(near) || plain.get(near) != buried) continue;
                    plain.remove(near);
                    level.put(near, buried);
                    structures.add(near);
                    queue.add(near);
                }
            }
        }
    }

    /** What a player placed, for entities worldgen never leaves outside structures, or null. */
    private static String placed(Entity entity) {
        if (entity instanceof ItemFrame) return "item frame";
        if (entity instanceof Painting) return "painting";
        if (entity instanceof ArmorStand) return "armor stand";
        if (entity instanceof AbstractBoat) return "boat";
        if (entity instanceof AbstractMinecart) return "minecart";
        return null;
    }

    /**
     * Why spots must keep their clearance from this chunk, or null when nothing in it shows players. A structure's
     * blocks are no sign of players; its plants, and what players left in it, are.
     *
     * @param live a player, placed entity or dug passage in the chunk, or null
     */
    private String reason(Scan scan, String live, boolean structure, FinderSettings settings) {
        if (live != null && live.startsWith("Player here")) return live;
        if (scan.blocks != null && scan.blocks.score() >= BaseEvidence.DEFAULT_MIN_SCORE) return "Player blocks: " + scan.blocks.reasons();
        if (scan.built != null && !structure) return "Built: " + scan.built.names();
        if (scan.built != null && scan.built.apart() != null) return "Built: " + scan.built.apart();
        if (live != null) return live;
        if (scan.amethyst != null) {
            ActivityArea judged = judge(scan);
            if (judged.score() > 0) return "Amethyst grew " + Math.round(judged.score() * 100) + "%";
        }
        Sight sight = scan.sight;
        if (!sight.clocks.foreign(sight.ownTicks)) return null;
        double minutes = sight.clocks.minutes(sight.ownTicks);
        if (minutes < settings.ignoreVisitsUnder) return null;
        return sight.clocks.reason(sight.ownTicks) + ", " + duration(minutes);
    }

    /** ActivityFinder's judgement of this chunk's amethyst against the amethyst chunks around it. */
    private ActivityArea judge(Scan scan) {
        List<ActivityArea> around = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Scan near = chunks.get(scan.chunk.x() + dx, scan.chunk.z() + dz);
                if (near != null && near.amethyst != null) around.add(near.amethyst);
            }
        }
        return ActivityIndex.judge(scan.amethyst, around);
    }

    static String duration(double minutes) {
        if (minutes < 1) return "<1 min";
        if (minutes < 90) return "~" + Math.round(minutes) + " min";
        return "~" + Math.round(minutes / 60) + " h";
    }

    private Scan scan(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        Sight sight = firstSight.get(key);
        if (sight == null) {
            sight = new Sight(clocks(chunk), exposure(chunk.getPos()), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8),
                    Caves.share(chunk));
            if (firstSight.size() >= MAX_SIGHTS) forgetFarFrom(chunk.getPos());
            firstSight.put(key, sight);
        }
        return new Scan(chunk.getPos(), sight, ActivityIndex.scan(chunk), BaseIndex.blocks(chunk), built(chunk));
    }

    /**
     * The chunk's built blocks open to air, and whether any gives a structure away; null without any. Anti-xray plugins
     * scatter fake chests and the like through buried stone, so buried blocks don't count.
     */
    static Built built(LevelChunk chunk) {
        Level level = chunk.getLevel();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), neighbor = new BlockPos.MutableBlockPos();
        Map<Block, Integer> found = new IdentityHashMap<>();
        // Every built block's height and whether it sits on the surface, to tell a build from a structure below it.
        List<long[]> heights = new ArrayList<>();
        int giveawayMin = Integer.MAX_VALUE, giveawayMax = Integer.MIN_VALUE;
        boolean giveawayBuried = true, allBuried = true;
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> BuiltBlocks.built(state) || BuiltBlocks.structure(state))) continue;
            int sectionY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int i = 0; i < 4096; i++) {
                BlockState state = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                boolean giveaway = BuiltBlocks.structure(state);
                if (!giveaway && !BuiltBlocks.built(state)) continue;
                pos.set(minX + (i & 15), sectionY + (i >> 8), minZ + ((i >> 4) & 15));
                if (!BaseIndex.exposed(level, chunk, pos, neighbor)) continue;
                found.merge(state.getBlock(), 1, Integer::sum);
                int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
                allBuried &= pos.getY() < surface - BURIED;
                if (giveaway) {
                    giveawayMin = Math.min(giveawayMin, pos.getY());
                    giveawayMax = Math.max(giveawayMax, pos.getY());
                    giveawayBuried &= pos.getY() < surface - BURIED;
                } else {
                    heights.add(new long[] {pos.getY(), Block.getId(state), pos.getY() >= surface - ON_SURFACE ? 1 : 0});
                }
            }
        }
        if (found.isEmpty()) return null;
        boolean giveaways = giveawayMin != Integer.MAX_VALUE;
        boolean structure = giveaways || allBuried && mineshaftPiece(found.keySet());
        // A structure's own blocks stay within some 16 blocks of what gives it away, and one that is all underground has
        // none on the surface: a crafting table on the ground above a mineshaft is someone's.
        Map<Block, Integer> apart = new IdentityHashMap<>();
        if (giveaways) {
            for (long[] height : heights) {
                boolean far = height[0] < giveawayMin - STRUCTURE_REACH || height[0] > giveawayMax + STRUCTURE_REACH;
                if (far || giveawayBuried && height[2] == 1) apart.merge(Block.stateById((int) height[1]).getBlock(), 1, Integer::sum);
            }
        }
        return new Built(names(found), structure, apart.isEmpty() ? null : names(apart), giveaways ? giveawayBuried : allBuried);
    }

    /** Only what mineshafts are made of, with some of their supports or chains: a corridor without rails or cobwebs. */
    private static boolean mineshaftPiece(java.util.Set<Block> blocks) {
        boolean frame = false;
        for (Block block : blocks) {
            if (!BuiltBlocks.mineshaft(block)) return false;
            frame |= BuiltBlocks.mineshaftFrame(block);
        }
        return frame;
    }

    /** The most common first, such as "12 stone bricks, torch". */
    private static String names(Map<Block, Integer> counts) {
        List<Map.Entry<Block, Integer>> most = new ArrayList<>(counts.entrySet());
        most.sort(Map.Entry.<Block, Integer>comparingByValue().reversed());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Block, Integer> entry : most.subList(0, Math.min(3, most.size()))) {
            parts.add(entry.getValue() == 1 ? BuiltBlocks.name(entry.getKey()) : entry.getValue() + " " + BuiltBlocks.name(entry.getKey()));
        }
        return String.join(", ", parts) + (most.size() > 3 ? ", ..." : "");
    }

    private void forgetFarFrom(ChunkPos pos) {
        firstSight.long2ObjectEntrySet().removeIf(entry -> Math.max(Math.abs(ChunkPos.getX(entry.getLongKey()) - pos.x()),
                Math.abs(ChunkPos.getZ(entry.getLongKey()) - pos.z())) > 128);
    }

    /** The chunk's plants as the server sent them. */
    static GrowthClocks clocks(LevelChunk chunk) {
        GrowthClocks clocks = new GrowthClocks();
        Level level = chunk.getLevel();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // The palette check skips almost every section without reading its blocks.
            if (section.hasOnlyAir() || !section.maybeHas(CLOCK)) continue;
            int sectionY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int i = 0; i < 4096; i++) {
                BlockState state = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                if (!CLOCK.test(state)) continue;
                pos.set(minX + (i & 15), sectionY + (i >> 8), minZ + ((i >> 4) & 15));
                if (state.is(Blocks.SUGAR_CANE)) cane(chunk, pos, state, clocks);
                else if (state.is(Blocks.CACTUS)) cactus(chunk, pos, state, clocks);
                else if (state.is(Blocks.KELP)) kelp(chunk, pos, state, clocks);
                else vine(level, chunk, pos, clocks);
            }
        }
        return clocks;
    }

    /** A column's top ages until it grows to 3 blocks; any age is a random tick's. */
    private static void cane(LevelChunk chunk, BlockPos pos, BlockState state, GrowthClocks clocks) {
        BlockState above = chunk.getBlockState(pos.above());
        if (above.is(Blocks.SUGAR_CANE)) return;
        int height = below(chunk, pos, Blocks.SUGAR_CANE) + 1, age = state.getValue(SugarCaneBlock.AGE);
        if (age > 0) clocks.add(GrowthClocks.Kind.CANE, true, age);
        else if (above.isAir() && height < 3) clocks.add(GrowthClocks.Kind.CANE, false, 0);
        if (above.isAir() && (height == 2 || height == 3)) clocks.caneColumn(height == 2);
    }

    /** A top ages up to 15 whatever the height; one under a flower has stopped. */
    private static void cactus(LevelChunk chunk, BlockPos pos, BlockState state, GrowthClocks clocks) {
        BlockState above = chunk.getBlockState(pos.above());
        if (above.is(Blocks.CACTUS)) return;
        int age = state.getValue(CactusBlock.AGE);
        if (age > 0) clocks.add(GrowthClocks.Kind.CACTUS, true, age);
        else if (above.isAir()) clocks.add(GrowthClocks.Kind.CACTUS, false, 0);
    }

    /** Worldgen leaves the top at age 20 to 23, under water, in a column of at most 11; it grows only into still water. */
    private static void kelp(LevelChunk chunk, BlockPos pos, BlockState state, GrowthClocks clocks) {
        BlockState above = chunk.getBlockState(pos.above());
        int age = state.getValue(KelpBlock.AGE), column = below(chunk, pos, Blocks.KELP_PLANT) + 1;
        if (age >= 24 || above.isAir() || column >= 12) clocks.add(GrowthClocks.Kind.KELP, true, 0);
        else if (age < 25 && above.is(Blocks.WATER) && above.getFluidState().isSource()) clocks.add(GrowthClocks.Kind.KELP, false, 0);
    }

    /**
     * Read from the lowest vine of a column: worldgen hangs at most 4 below the one held by a leaf, so a fifth hanging
     * vine grew. A run of 4 with air below can still show growth.
     */
    private static void vine(Level level, LevelChunk chunk, BlockPos bottom, GrowthClocks clocks) {
        BlockState below = chunk.getBlockState(bottom.below());
        if (below.is(Blocks.VINE)) return;
        int hanging = 0;
        BlockPos.MutableBlockPos pos = bottom.mutable();
        while (hanging < 16) {
            BlockState state = chunk.getBlockState(pos);
            if (!state.is(Blocks.VINE) || held(level, pos, state)) break;
            hanging++;
            pos.move(Direction.UP);
        }
        if (hanging >= 5) clocks.add(GrowthClocks.Kind.VINE, true, 0);
        else if (hanging == 4 && below.isAir()) clocks.add(GrowthClocks.Kind.VINE, false, 0);
    }

    /** Whether a face of this vine rests on a block, rather than hanging from the vine above. */
    private static boolean held(Level level, BlockPos pos, BlockState state) {
        if (state.getValue(VineBlock.UP) && attachable(level, pos, Direction.UP)) return true;
        for (Direction direction : HORIZONTAL) {
            if (state.getValue(VineBlock.getPropertyForFace(direction)) && attachable(level, pos, direction)) return true;
        }
        return false;
    }

    /** An unloaded neighbor reads as air, so it counts as holding the vine: a run is never read longer than it is. */
    private static boolean attachable(Level level, BlockPos pos, Direction direction) {
        BlockPos next = pos.relative(direction);
        return !LoadedChunks.has(level, next) || VineBlock.isAcceptableNeighbour(level, next, direction);
    }

    /** How many blocks of this kind are stacked right below, up to 64. */
    private static int below(LevelChunk chunk, BlockPos pos, Block block) {
        BlockPos.MutableBlockPos next = pos.mutable().move(Direction.DOWN);
        int count = 0;
        while (count < 64 && next.getY() >= chunk.getMinY() && chunk.getBlockState(next).is(block)) {
            count++;
            next.move(Direction.DOWN);
        }
        return count;
    }

    /**
     * A chunk's plants as first read, how long we had been within simulation distance of it by then, the surface in its
     * middle column, and the share of its underground that is open.
     */
    private record Sight(GrowthClocks clocks, int ownTicks, int surfaceY, float caves) { }

    /** @param built the chunk's built blocks, or null */
    private record Scan(ChunkPos chunk, Sight sight, ActivityArea amethyst, BaseArea blocks, Built built) { }

    /**
     * Built blocks open to air in one chunk.
     *
     * @param names the most common first, such as "12 stone bricks, torch"
     * @param structure one of them gives a structure away
     * @param apart in a structure's chunk, the built blocks far above or below what gives it away, which are someone's
     *              build; otherwise null
     * @param buried in a structure's chunk, what gives it away is all underground; otherwise every built block is
     */
    record Built(String names, boolean structure, String apart, boolean buried) { }
}
