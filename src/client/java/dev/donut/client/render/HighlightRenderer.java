package dev.donut.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.BaseArea;
import dev.donut.client.finder.ChunkSign;
import dev.donut.client.finder.EntityKind;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.GoodChunk;
import dev.donut.client.finder.LoadedChunks;
import dev.donut.client.finder.TargetKind;
import dev.donut.client.finder.TunnelDetector;
import dev.donut.client.mixin.GameRendererAccess;
import dev.donut.client.schematic.SchematicBuilder;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Batches finder overlays using the 26.2 render pipeline and render-state APIs. */
public final class HighlightRenderer implements AutoCloseable {
    /** Tracers beyond this many nearest targets add clutter rather than direction. */
    private static final int MAX_TRACERS = 256;
    /** Label candidates handed to the HUD per label it shows; it drops those that overlap after placing them. */
    private static final int LABEL_CANDIDATES = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Render");
    private static volatile List<ScreenLabel> labels = List.of();

    private final RenderStateDataKey<Frame> frame =
            RenderStateDataKey.create(() -> "Donut finder highlights");
    private static final RenderPipeline FILL_VISIBLE = pipeline("fill_visible", false, false);
    private static final RenderPipeline LINE_VISIBLE = pipeline("line_visible", true, false);
    /** Tracers always draw in full: they point through walls on purpose. */
    private static final RenderPipeline LINE_THROUGH = pipeline("line_through", true, true);
    /** The parts of a through-walls highlight that something hides: drawn fainter, so what's in view stands out. */
    private static final RenderPipeline FILL_HIDDEN = pipeline("fill_hidden", false, CompareOp.LESS_THAN);
    private static final RenderPipeline LINE_HIDDEN = pipeline("line_hidden", true, CompareOp.LESS_THAN);
    private static final Vector4f HIDDEN_TINT = new Vector4f(1, 1, 1, 0.4f);

    // Corner bits are X=1, Y=2, Z=4. Winding is outward for all six faces.
    private static final int[][] FACES = {
            {0, 4, 6, 2}, {5, 1, 3, 7}, {4, 0, 1, 5},
            {2, 6, 7, 3}, {1, 0, 2, 3}, {4, 5, 7, 6}
    };

    private final ClientConfig config;
    private final FinderScanner scanner;
    /** Highlights that respect walls draw before translucent terrain, so water and glass in front tint them. */
    private StagedVertexBuffer visibleVertices = vertexBuffer("Donut finder highlights");
    private StagedVertexBuffer throughVertices = vertexBuffer("Donut finder highlights through walls");
    private boolean registered;
    private boolean closed;
    private boolean failed;

    public HighlightRenderer(ClientConfig config, FinderScanner scanner) {
        this.config = config;
        this.scanner = scanner;
    }

    /** Labels for the nearest highlights in the last rendered frame, for the HUD. */
    public static List<ScreenLabel> labels() { return labels; }

    public void register() {
        if (registered) return;
        registered = true;
        LevelExtractionEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> draw(context, false));
        LevelRenderEvents.END_MAIN.register(context -> draw(context, true));
    }

    private static StagedVertexBuffer vertexBuffer(String name) { return new StagedVertexBuffer(() -> name, 64 * 1024); }

    private static RenderPipeline pipeline(String name, boolean lines, boolean throughWalls) {
        // Depth is reversed in 26.2: a nearer fragment has the greater depth.
        return pipeline(name, lines, throughWalls ? CompareOp.ALWAYS_PASS : CompareOp.GREATER_THAN_OR_EQUAL);
    }

    private static RenderPipeline pipeline(String name, boolean lines, CompareOp depthTest) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("donut", "pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(!lines)
                .withDepthStencilState(new DepthStencilState(depthTest, false));
        if (lines) {
            // The vanilla line vertex shader expands each segment in screen space; it reads the fog uniforms.
            // The position-color fragment shader keeps these overlays independent of fog.
            builder.withBindGroupLayout(BindGroupLayouts.FOG)
                    .withVertexShader("core/rendertype_lines")
                    .withFragmentShader("core/position_color")
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
                    .withPrimitiveTopology(PrimitiveTopology.LINES);
        } else {
            builder.withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS);
        }
        return RenderPipelines.register(builder.build());
    }

    private boolean anyEnabled() {
        return config.storage.enabled || config.spawner.enabled || config.debris.enabled || config.activity.enabled
                || config.tunnels.enabled || config.entities.enabled || config.bases.enabled || config.chunks.enabled
                || SchematicBuilder.busy();
    }

    private void extract(LevelExtractionContext context) {
        CameraRenderState cameraState = context.levelState().cameraRenderState;
        FabricRenderState state = (FabricRenderState) context.levelState();
        if (closed || !anyEnabled()) {
            state.setData(frame, Frame.EMPTY);
            // A closed renderer leaves the labels to the one still drawing.
            if (!closed) labels = List.of();
            return;
        }

        Vec3 camera = cameraState.pos;
        // This frame's projection, with its hurt tilt and view bobbing: the level renders after extraction.
        Matrix4f bob = bob(cameraState);
        Matrix4f projection = new Matrix4f(cameraState.projectionMatrix).mul(bob);
        // A point the bobbing camera shows at the crosshair, so tracers start there while walking.
        Vector4f ahead = new Matrix4f(bob).mul(cameraState.viewRotationMatrix).invert().transform(new Vector4f(0, 0, -1, 1));
        Vec3 crosshair = new Vec3(ahead.x / ahead.w, ahead.y / ahead.w, ahead.z / ahead.w);
        Minecraft client = Minecraft.getInstance();
        boolean labelling = config.hud.labels && !client.gui.hud.isHidden();
        // Vanilla thickens its lines on screens wider than 1080p; so do these.
        float widthScale = client.getWindow().getAppropriateLineWidth() / 2.5f;
        Extraction out = new Extraction(context, camera, crosshair, labelling, widthScale);
        for (FoundBlock block : scanner.snapshot()) {
            FinderSettings settings = block.kind().settings(config);
            if (!settings.enabled || !block.kind().included(settings)) continue;
            double distance = Vec3.atCenterOf(block.pos()).distanceTo(camera);
            if (distance > settings.range) continue;

            if (!LoadedChunks.has(context.level(), block.pos())) continue;
            var currentState = context.level().getBlockState(block.pos());
            // A seed estimate marks the netherrack the server shows in place of hidden debris, and revealed debris can
            // be hidden as netherrack again when the server resends its chunk.
            boolean estimate = block.flagged() && block.kind().isDebris();
            boolean rehidden = !estimate && block.kind().isDebris() && currentState.is(Blocks.NETHERRACK);
            if (!estimate && !rehidden && TargetKind.from(currentState) != block.kind()) continue;
            VoxelShape shape = currentState.getShape(context.level(), block.pos());
            if (shape.isEmpty()) continue;
            AABB bounds = withPartner(context, block.pos(), currentState, shape.bounds().move(block.pos()).inflate(0.003));
            boolean flagged = estimate || block.flagged() && block.kind().isSpawner() && settings.activatedSpawners;
            String label = !out.labelling ? null : estimate ? block.kind().label() + " (seed)"
                    : flagged ? block.kind().label() + " (activated)" : block.kind().label();
            out.target(settings, bounds, color(settings, block.kind(), flagged), distance, 1.0, label);
        }
        if (config.tunnels.enabled) addTunnels(out);
        if (config.activity.enabled) addActivity(out);
        if (config.bases.enabled) addBases(out);
        if (config.chunks.enabled) addChunks(out);
        if (config.debris.enabled && config.debris.searchAreas) addDebrisAreas(out);
        if (config.entities.enabled || config.storage.enabled && config.storage.minecarts) addEntities(out);
        if (SchematicBuilder.busy()) addSchematic(out);
        state.setData(frame, new Frame(List.copyOf(out.boxes), List.copyOf(out.tracers(MAX_TRACERS))));
        labels = out.labelling ? out.labels(projection, config.hud.maxLabels * LABEL_CANDIDATES) : List.of();
    }

    /** The bob transform GameRenderer.renderLevel applies to this frame's projection. */
    private static Matrix4f bob(CameraRenderState cameraState) {
        Minecraft client = Minecraft.getInstance();
        PoseStack stack = new PoseStack();
        GameRendererAccess renderer = (GameRendererAccess) client.gameRenderer;
        renderer.donut$bobHurt(cameraState, stack);
        if (client.options.bobView().get()) renderer.donut$bobView(cameraState, stack);
        return new Matrix4f(stack.last().pose());
    }

    /** A double chest is found once; its box covers both halves. */
    private static AABB withPartner(LevelExtractionContext context, BlockPos pos, BlockState state, AABB bounds) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return bounds;
        BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
        BlockState other = context.level().getBlockState(partner);
        if (other.getBlock() != state.getBlock()) return bounds;
        VoxelShape shape = other.getShape(context.level(), partner);
        return shape.isEmpty() ? bounds : bounds.minmax(shape.bounds().move(partner).inflate(0.003));
    }

    private void addTunnels(Extraction out) {
        FinderSettings settings = config.tunnels;
        for (TunnelDetector.Segment segment : scanner.tunnels()) {
            int color = switch (segment.kind()) {
                case TUNNEL -> settings.color;
                case HOLE -> settings.secondaryColor;
                case STAIRS -> settings.tertiaryColor;
            };
            String name = !out.labelling ? null : switch (segment.kind()) {
                case TUNNEL -> "Tunnel, " + segment.total() + " long";
                case HOLE -> "Hole, " + segment.total() + " deep";
                case STAIRS -> "Staircase, " + segment.total() + " steps";
            };
            if (segment.kind() == TunnelDetector.Kind.STAIRS) {
                // A diagonal staircase is drawn step by step; one box around it would cover solid rock.
                for (int i = 0; i < segment.length(); i++) {
                    int x = segment.x() + segment.dx() * i, y = segment.y() + segment.dy() * i, z = segment.z() + segment.dz() * i;
                    AABB step = new AABB(x, y, z, x + 1, y + 3, z + 1);
                    double distance = step.getCenter().distanceTo(out.camera);
                    if (distance > settings.range) continue;
                    // Only the middle step carries a label and a tracer.
                    if (i == segment.length() / 2) out.target(settings, step, color, distance, 1.0, name);
                    else out.styledBox(settings, step, color, distance, 1.0);
                }
                continue;
            }
            int last = segment.length() - 1;
            int x2 = segment.x() + segment.dx() * last, y2 = segment.y() + segment.dy() * last, z2 = segment.z() + segment.dz() * last;
            AABB box = new AABB(Math.min(segment.x(), x2), Math.min(segment.y(), y2), Math.min(segment.z(), z2),
                    Math.max(segment.x(), x2) + 1, Math.max(segment.y(), y2) + segment.cellHeight(), Math.max(segment.z(), z2) + 1);
            double distance = Math.sqrt(box.distanceToSqr(out.camera));
            if (distance > settings.range) continue;
            out.target(settings, box, color, distance, 1.0, name);
        }
    }

    private void addActivity(Extraction out) {
        FinderSettings settings = config.activity;
        double range = scanner.activityRange();
        boolean fades = settings.distanceFade && !settings.allLoadedChunks;
        for (ActivityArea area : scanner.activity()) {
            double distance = area.horizontalDistance(out.camera);
            if (distance > range || area.score() < settings.minActivity) continue;
            // Chunks just past the threshold stay faint; fully grown geodes draw at the configured opacity.
            double headroom = 1.0 - settings.minActivity;
            double emphasis = headroom <= 0 ? 1.0 : 0.35 + 0.65 * (area.score() - settings.minActivity) / headroom;
            double strength = emphasis * (fades ? fade(distance, range) : 1.0);
            int fill = settings.mode != RenderMode.OUTLINE ? argb(settings.color, settings.fillOpacity * strength) : 0;
            int outline = settings.mode != RenderMode.FILL ? argb(settings.color, settings.outlineOpacity * strength) : 0;
            float width = (float) Math.clamp(settings.lineWidth, 0.5, 8.0);
            String label = out.labelling ? "Activity " + Math.round(area.score() * 100) + "%" : null;
            double visible = fades ? fade(distance, range) : 1.0;
            if (settings.surfaceMarker) {
                // The guide's corner edges lead from the square down to the geode's level.
                int guide = settings.mode != RenderMode.FILL ? argb(settings.color, settings.outlineOpacity * strength * 0.4) : 0;
                out.box(area.guideBounds(), 0, guide, width * 0.75f, settings.throughWalls);
                out.box(area.surfaceBounds(), fill, outline, width, settings.throughWalls);
                out.extras(settings, area.surfaceBounds(), settings.color, distance, visible, label);
            } else {
                out.box(area.bounds(), fill, outline, width, settings.throughWalls);
                out.extras(settings, area.bounds(), settings.color, distance, visible, label);
            }
        }
    }

    /** A chunk-wide box around the evidence, labelled with its score and strongest reasons. */
    private void addBases(Extraction out) {
        FinderSettings settings = config.bases;
        double range = scanner.baseRange();
        boolean fades = settings.distanceFade && !settings.allLoadedChunks;
        for (BaseArea area : scanner.bases()) {
            double distance = area.horizontalDistance(out.camera);
            if (distance > range) continue;
            double visible = fades ? fade(distance, range) : 1.0;
            int fill = settings.mode != RenderMode.OUTLINE ? argb(settings.color, settings.fillOpacity * visible) : 0;
            int outline = settings.mode != RenderMode.FILL ? argb(settings.color, settings.outlineOpacity * visible) : 0;
            out.box(area.bounds(), fill, outline, (float) Math.clamp(settings.lineWidth, 0.5, 8.0), settings.throughWalls);
            String label = out.labelling ? "Base? " + area.score() + ": " + area.reasons() : null;
            out.extras(settings, area.bounds(), settings.color, distance, visible, label);
        }
    }

    /**
     * ChunkFinder: a square on each spot's surface and a beam over the best, so it shows from afar; with signs shown, a
     * square on each chunk the spots keep away from, labelled with why. Only the spots get tracers.
     */
    private void addChunks(Extraction out) {
        FinderSettings settings = config.chunks;
        float width = (float) Math.clamp(settings.lineWidth, 0.5, 8.0);
        int fill = settings.mode != RenderMode.OUTLINE ? argb(settings.color, settings.fillOpacity) : 0;
        int outline = settings.mode != RenderMode.FILL ? argb(settings.color, settings.outlineOpacity) : 0;
        List<GoodChunk> spots = scanner.goodChunks();
        for (int i = 0; i < spots.size(); i++) {
            GoodChunk spot = spots.get(i);
            double distance = spot.horizontalDistance(out.camera);
            AABB square = spot.surfaceBounds();
            out.box(square, fill, outline, width, settings.throughWalls);
            if (i == 0) {
                Vec3 center = spot.center();
                AABB beam = new AABB(center.x - 0.5, center.y, center.z - 0.5, center.x + 0.5, center.y + 64, center.z + 0.5);
                out.box(beam, argb(settings.color, Math.max(settings.fillOpacity, 0.25)), outline, width, true);
            }
            String label = !out.labelling ? null : (i == 0 ? "Best chunk" : "Good chunk") + ", clear " + spot.clearance();
            out.extras(settings, square, settings.color, distance, 1.0, label);
        }
        if (!settings.showSigns) return;
        int signFill = settings.mode != RenderMode.OUTLINE ? argb(settings.secondaryColor, settings.fillOpacity * 0.6) : 0;
        int signOutline = settings.mode != RenderMode.FILL ? argb(settings.secondaryColor, settings.outlineOpacity * 0.5) : 0;
        for (ChunkSign sign : scanner.chunkSigns()) {
            AABB square = sign.surfaceBounds();
            out.box(square, signFill, signOutline, width * 0.75f, settings.throughWalls);
            if (out.labelling) out.label(square, sign.reason(), settings.secondaryColor, sign.horizontalDistance(out.camera));
        }
    }

    /**
     * The schematic's bounds, and the blocks left in the layer being built: the next one white, those without
     * materials red, spots another block holds amber. Seen through walls, since a layer is often behind the last.
     */
    private void addSchematic(Extraction out) {
        for (SchematicBuilder.Preview preview : SchematicBuilder.getInstance().preview()) {
            int fill = preview.filled() ? argb(preview.color(), 0.18) : 0;
            out.box(preview.box(), fill, argb(preview.color(), preview.filled() ? 0.75 : 0.9), preview.filled() ? 1.5f : 2f, true);
        }
    }

    /** Outlines only: an area says debris is somewhere in these 4096 blocks, not where. */
    private void addDebrisAreas(Extraction out) {
        FinderSettings settings = config.debris;
        float width = (float) Math.clamp(settings.lineWidth, 0.5, 8.0);
        for (SectionPos section : scanner.debrisAreas()) {
            AABB bounds = new AABB(section.minBlockX(), section.minBlockY(), section.minBlockZ(),
                    section.minBlockX() + 16, section.minBlockY() + 16, section.minBlockZ() + 16);
            double distance = bounds.getCenter().distanceTo(out.camera);
            if (distance > settings.range + 14) continue;
            double fade = settings.distanceFade ? fade(distance, settings.range) : 1.0;
            out.box(bounds, 0, argb(settings.tertiaryColor, settings.outlineOpacity * fade), width, settings.throughWalls);
            out.extras(settings, bounds, settings.tertiaryColor, distance, fade, "Debris area");
        }
    }

    private void addEntities(Extraction out) {
        float partialTick = out.context.deltaTracker().getGameTimeDeltaPartialTick(false);
        for (Entity entity : out.context.level().entitiesForRendering()) {
            EntityKind kind = EntityKind.of(entity);
            if (kind == null) continue;
            FinderSettings settings = kind.settings(config);
            if (!settings.enabled || !kind.included(settings) || entity.isRemoved()) continue;
            // Draw where the entity is rendered this frame, between its last two ticks.
            Vec3 at = entity.getPosition(partialTick);
            AABB bounds = entity.getBoundingBox().move(at.subtract(entity.position())).inflate(0.05);
            double distance = bounds.getCenter().distanceTo(out.camera);
            if (distance > settings.range) continue;
            out.target(settings, bounds, kind.color(settings), distance, 1.0, out.labelling ? entity.getName().getString() : null);
        }
    }

    private static int color(FinderSettings settings, TargetKind kind, boolean flagged) {
        if (flagged) return settings.secondaryColor;
        if (!settings.perTypeColors) return settings.color;
        return switch (kind) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST, BARREL -> settings.chestColor;
            case SHULKER -> settings.shulkerColor;
            case DROPPER, DISPENSER, HOPPER -> settings.dropperColor;
            case FURNACE -> settings.furnaceColor;
            case SPAWNER, TRIAL_SPAWNER, ANCIENT_DEBRIS -> settings.color;
        };
    }

    private static double fade(double distance, double range) {
        double progress = Math.clamp((distance / Math.max(1.0, range) - 0.5) * 2.0, 0.0, 1.0);
        return 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }

    private static int argb(int rgb, double opacity) {
        return ((int) Math.round(Math.clamp(opacity, 0.0, 1.0) * 255.0) << 24) | (rgb & 0xFFFFFF);
    }

    /** Collects one frame's boxes, tracers and label candidates in camera-relative coordinates. */
    private static final class Extraction {
        final LevelExtractionContext context;
        final Vec3 camera;
        /** Camera-relative; projects to the crosshair. */
        final Vec3 crosshair;
        /** Whether the HUD can show labels; without them no label text is built. */
        final boolean labelling;
        final float widthScale;
        final List<DrawBox> boxes = new ArrayList<>();
        final List<DrawLine> tracerCandidates = new ArrayList<>();
        final List<Candidate> labelCandidates = new ArrayList<>();

        Extraction(LevelExtractionContext context, Vec3 camera, Vec3 crosshair, boolean labelling, float widthScale) {
            this.context = context;
            this.camera = camera;
            this.crosshair = crosshair;
            this.labelling = labelling;
            this.widthScale = widthScale;
        }

        /** A highlight with the finder's styling, plus its tracer and label. */
        void target(FinderSettings settings, AABB bounds, int color, double distance, double strength, String label) {
            styledBox(settings, bounds, color, distance, strength);
            extras(settings, bounds, color, distance, settings.distanceFade ? fade(distance, settings.range) : 1.0, label);
        }

        void styledBox(FinderSettings settings, AABB bounds, int color, double distance, double strength) {
            double fade = settings.distanceFade ? fade(distance, settings.range) : 1.0;
            int fill = settings.mode != RenderMode.OUTLINE ? argb(color, settings.fillOpacity * fade * strength) : 0;
            int outline = settings.mode != RenderMode.FILL ? argb(color, settings.outlineOpacity * fade * strength) : 0;
            box(bounds, fill, outline, (float) Math.clamp(settings.lineWidth, 0.5, 8.0), settings.throughWalls);
        }

        /** A box faded out entirely gets no tracer or label either; fainter boxes get fainter tracers. */
        void extras(FinderSettings settings, AABB bounds, int color, double distance, double fade, String label) {
            if (fade <= 0) return;
            if (settings.tracers) {
                Vec3 center = bounds.getCenter().subtract(camera);
                tracerCandidates.add(new DrawLine(crosshair, center, argb(color, Math.max(0.35, settings.outlineOpacity) * fade),
                        (float) Math.clamp(settings.lineWidth, 0.5, 8.0) * widthScale, distance));
            }
            if (labelling && label != null) labelCandidates.add(new Candidate(bounds, label, color, distance));
        }

        /** A label without a tracer. */
        void label(AABB bounds, String label, int color, double distance) {
            labelCandidates.add(new Candidate(bounds, label, color, distance));
        }

        void box(AABB box, int fill, int outline, float width, boolean throughWalls) {
            if ((fill >>> 24) == 0 && (outline >>> 24) == 0) return;
            AABB bounds = box.inflate(0.003);
            if (!context.levelState().cameraRenderState.cullFrustum.isVisible(bounds)) return;
            // Subtract in double precision before making vertices, including near the world border.
            boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z), fill, outline, width * widthScale, throughWalls));
        }

        List<DrawLine> tracers(int max) {
            if (tracerCandidates.size() > max) tracerCandidates.sort(Comparator.comparingDouble(DrawLine::distance));
            return tracerCandidates.subList(0, Math.min(max, tracerCandidates.size()));
        }

        /** Projects the nearest label candidates that are in front of the camera. */
        List<ScreenLabel> labels(Matrix4fc projection, int max) {
            if (labelCandidates.isEmpty()) return List.of();
            labelCandidates.sort(Comparator.comparingDouble(Candidate::distance));
            Matrix4f transform = new Matrix4f(projection).mul(context.levelState().cameraRenderState.viewRotationMatrix);
            List<ScreenLabel> result = new ArrayList<>();
            Vector4f point = new Vector4f();
            for (Candidate candidate : labelCandidates) {
                if (result.size() >= max) break;
                AABB b = candidate.bounds;
                point.set((float) ((b.minX + b.maxX) / 2 - camera.x), (float) (b.maxY + 0.25 - camera.y),
                        (float) ((b.minZ + b.maxZ) / 2 - camera.z), 1f);
                transform.transform(point);
                if (point.w <= 0.05f) continue;
                double x = point.x / point.w, y = point.y / point.w;
                if (Math.abs(x) > 1.05 || Math.abs(y) > 1.05) continue;
                String text = candidate.label + "  " + Math.round(candidate.distance) + "m";
                result.add(new ScreenLabel((x + 1) / 2, (1 - y) / 2, text, candidate.color, candidate.distance));
            }
            return List.copyOf(result);
        }
    }

    /** Draws either the highlights that respect walls or those that don't, with the tracers among the latter. */
    private void draw(LevelRenderContext context, boolean through) {
        if (closed) return;
        Frame boxes = ((FabricRenderState) context.levelState()).getData(frame);
        if (boxes == null || boxes.isEmpty()) return;
        StagedVertexBuffer vertices = through ? throughVertices : visibleVertices;
        PoseStack.Pose pose = context.poseStack().last();
        boolean broke = false;
        // A frame that fails partway must still end, or every later frame refuses new draws.
        try {
            var sorting = RenderSystem.getProjectionType().vertexSorting();
            StagedVertexBuffer.Draw fill = vertices.appendDraw(DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS, sorting);
            StagedVertexBuffer.Draw line = vertices.appendDraw(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PrimitiveTopology.LINES);
            StagedVertexBuffer.Draw tracers = through
                    ? vertices.appendDraw(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PrimitiveTopology.LINES) : null;
            // One builder at a time: each draw is written whole before the next.
            VertexConsumer buffer = null;
            for (DrawBox box : boxes.boxes) {
                if (box.throughWalls != through || (box.fill >>> 24) == 0) continue;
                if (buffer == null) buffer = vertices.getVertexBuilder(fill);
                filled(buffer, pose.pose(), box.bounds, box.fill);
            }
            buffer = null;
            for (DrawBox box : boxes.boxes) {
                if (box.throughWalls != through || (box.outline >>> 24) == 0) continue;
                if (buffer == null) buffer = vertices.getVertexBuilder(line);
                outlined(buffer, pose, box.bounds, box.outline, box.width);
            }
            if (through && !boxes.tracers.isEmpty()) {
                buffer = vertices.getVertexBuilder(tracers);
                for (DrawLine tracer : boxes.tracers) segment(buffer, pose, tracer);
            }
            vertices.upload();
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
            if (through) {
                // The same vertices twice: faint where something hides them, full where they are in view.
                GpuBufferSlice faint = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), HIDDEN_TINT);
                drawPass(vertices, FILL_HIDDEN, fill, faint);
                drawPass(vertices, FILL_VISIBLE, fill, transform);
                drawPass(vertices, LINE_HIDDEN, line, faint);
                drawPass(vertices, LINE_VISIBLE, line, transform);
                drawPass(vertices, LINE_THROUGH, tracers, transform);
            } else {
                drawPass(vertices, FILL_VISIBLE, fill, transform);
                drawPass(vertices, LINE_VISIBLE, line, transform);
            }
        } catch (RuntimeException e) {
            // A broken frame must not crash the game; the overlay skips it and starts over with a fresh buffer.
            broke = true;
            if (!failed) {
                failed = true;
                LOGGER.error("Finder highlights failed to draw; skipping the frame", e);
            }
        } finally {
            vertices.endFrame();
        }
        if (broke) {
            vertices.close();
            if (through) throughVertices = vertexBuffer("Donut finder highlights through walls");
            else visibleVertices = vertexBuffer("Donut finder highlights");
        }
    }

    /** Draws one staged batch onto the main target, the way vanilla's prepared render types do. */
    private void drawPass(StagedVertexBuffer vertices, RenderPipeline pipeline, StagedVertexBuffer.Draw draw, GpuBufferSlice transform) {
        StagedVertexBuffer.ExecuteInfo info = vertices.getExecuteInfo(draw);
        if (info == null) return;
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Donut finder overlay",
                target.getColorTextureView(), Optional.empty(), target.useDepth ? target.getDepthTextureView() : null, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private static void filled(VertexConsumer buffer, Matrix4fc pose, AABB bounds, int color) {
        for (int[] face : FACES) {
            for (int corner : face) {
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner)).setColor(color);
            }
        }
    }

    private static void outlined(VertexConsumer buffer, PoseStack.Pose pose, AABB bounds, int color, float width) {
        for (int corner = 0; corner < 8; corner++) {
            for (int axis = 1; axis <= 4; axis *= 2) {
                if ((corner & axis) != 0) continue;
                int other = corner | axis;
                float normalX = axis == 1 ? 1 : 0;
                float normalY = axis == 2 ? 1 : 0;
                float normalZ = axis == 4 ? 1 : 0;
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner))
                        .setColor(color).setNormal(pose, normalX, normalY, normalZ).setLineWidth(width);
                buffer.addVertex(pose, x(bounds, other), y(bounds, other), z(bounds, other))
                        .setColor(color).setNormal(pose, normalX, normalY, normalZ).setLineWidth(width);
            }
        }
    }

    /** The line shader expands each segment across its direction, which it reads from the normal. */
    private static void segment(VertexConsumer buffer, PoseStack.Pose pose, DrawLine line) {
        Vec3 direction = line.to.subtract(line.from);
        if (direction.lengthSqr() < 1.0e-6) return;
        direction = direction.normalize();
        float nx = (float) direction.x, ny = (float) direction.y, nz = (float) direction.z;
        buffer.addVertex(pose, (float) line.from.x, (float) line.from.y, (float) line.from.z)
                .setColor(line.color).setNormal(pose, nx, ny, nz).setLineWidth(line.width);
        buffer.addVertex(pose, (float) line.to.x, (float) line.to.y, (float) line.to.z)
                .setColor(line.color).setNormal(pose, nx, ny, nz).setLineWidth(line.width);
    }

    private static float x(AABB box, int corner) { return (float) ((corner & 1) == 0 ? box.minX : box.maxX); }
    private static float y(AABB box, int corner) { return (float) ((corner & 2) == 0 ? box.minY : box.maxY); }
    private static float z(AABB box, int corner) { return (float) ((corner & 4) == 0 ? box.minZ : box.maxZ); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        labels = List.of();
        visibleVertices.close();
        throughVertices.close();
    }

    private record Frame(List<DrawBox> boxes, List<DrawLine> tracers) {
        static final Frame EMPTY = new Frame(List.of(), List.of());
        boolean isEmpty() { return boxes.isEmpty() && tracers.isEmpty(); }
    }

    private record DrawBox(AABB bounds, int fill, int outline, float width, boolean throughWalls) { }

    /** A tracer from a point just in front of the camera that projects to the crosshair, to a target. */
    private record DrawLine(Vec3 from, Vec3 to, int color, float width, double distance) { }

    private record Candidate(AABB bounds, String label, int color, double distance) { }
}
