package dev.donut.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.EntityKind;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.TargetKind;
import dev.donut.client.finder.TunnelDetector;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Batches finder overlays using the 1.21.11 render pipeline and render-state APIs. */
public final class HighlightRenderer implements AutoCloseable {
    /** Tracers beyond this many nearest targets add clutter rather than direction. */
    private static final int MAX_TRACERS = 256;
    private static volatile List<ScreenLabel> labels = List.of();

    private final RenderStateDataKey<Frame> frame =
            RenderStateDataKey.create(() -> "Donut finder highlights");
    private static final RenderPipeline FILL_VISIBLE = pipeline("fill_visible", false, false);
    private static final RenderPipeline FILL_THROUGH = pipeline("fill_through", false, true);
    private static final RenderPipeline LINE_VISIBLE = pipeline("line_visible", true, false);
    private static final RenderPipeline LINE_THROUGH = pipeline("line_through", true, true);
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    // Corner bits are X=1, Y=2, Z=4. Winding is outward for all six faces.
    private static final int[][] FACES = {
            {0, 4, 6, 2}, {5, 1, 3, 7}, {4, 0, 1, 5},
            {2, 6, 7, 3}, {1, 0, 2, 3}, {4, 5, 7, 6}
    };

    private final ClientConfig config;
    private final FinderScanner scanner;
    private final Batch fillVisible = new Batch(FILL_VISIBLE);
    private final Batch fillThrough = new Batch(FILL_THROUGH);
    private final Batch lineVisible = new Batch(LINE_VISIBLE);
    private final Batch lineThrough = new Batch(LINE_THROUGH);
    private boolean registered;
    private boolean closed;

    public HighlightRenderer(ClientConfig config, FinderScanner scanner) {
        this.config = config;
        this.scanner = scanner;
    }

    /** Labels for the nearest highlights in the last rendered frame, for the HUD. */
    public static List<ScreenLabel> labels() { return labels; }

    public void register() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.END_EXTRACTION.register(this::extract);
        WorldRenderEvents.END_MAIN.register(this::draw);
    }

    private static RenderPipeline pipeline(String name, boolean lines, boolean throughWalls) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("donut", "pipeline/" + name))
                .withVertexShader(lines ? "core/rendertype_lines" : "core/position_color")
                .withFragmentShader("core/position_color")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(lines ? DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH
                        : DefaultVertexFormat.POSITION_COLOR, lines ? VertexFormat.Mode.LINES : VertexFormat.Mode.QUADS)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(!lines)
                .withDepthWrite(false)
                .withDepthTestFunction(throughWalls ? DepthTestFunction.NO_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST);
        if (lines) {
            // The vanilla line vertex shader expands each segment in screen space.
            // The position-color fragment shader keeps these overlays independent of fog.
            builder.withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER);
        }
        return RenderPipelines.register(builder.build());
    }

    private boolean anyEnabled() {
        return config.storage.enabled || config.spawner.enabled || config.debris.enabled || config.activity.enabled
                || config.tunnels.enabled || config.entities.enabled;
    }

    private void extract(WorldExtractionContext context) {
        FabricRenderState state = (FabricRenderState) context.worldState();
        if (closed || !anyEnabled()) {
            state.setData(frame, Frame.EMPTY);
            // A closed renderer leaves the labels to the one still drawing.
            if (!closed) labels = List.of();
            return;
        }

        Vec3 camera = context.worldState().cameraRenderState.pos;
        Vector3fc forward = context.camera().forwardVector();
        Extraction out = new Extraction(context, camera, new Vec3(forward.x(), forward.y(), forward.z()));
        for (FoundBlock block : scanner.snapshot()) {
            FinderSettings settings = block.kind().settings(config);
            if (!settings.enabled || !block.kind().included(settings)) continue;
            double distance = Vec3.atCenterOf(block.pos()).distanceTo(camera);
            if (distance > settings.range) continue;

            if (!context.world().hasChunkAt(block.pos())) continue;
            var currentState = context.world().getBlockState(block.pos());
            // A seed estimate marks the netherrack the server shows in place of hidden debris.
            boolean estimate = block.flagged() && block.kind().isDebris();
            if (!estimate && TargetKind.from(currentState) != block.kind()) continue;
            VoxelShape shape = currentState.getShape(context.world(), block.pos());
            if (shape.isEmpty()) continue;
            AABB bounds = shape.bounds().move(block.pos()).inflate(0.003);
            boolean flagged = estimate || block.flagged() && block.kind().isSpawner() && settings.activatedSpawners;
            String label = estimate ? block.kind().label() + " (seed)" : flagged ? block.kind().label() + " (activated)" : block.kind().label();
            out.target(settings, bounds, color(settings, block.kind(), flagged), distance, 1.0, label);
        }
        if (config.tunnels.enabled) addTunnels(out);
        if (config.activity.enabled) addActivity(out);
        if (config.debris.enabled && config.debris.searchAreas) addDebrisAreas(out);
        if (config.entities.enabled || config.storage.enabled && config.storage.minecarts) addEntities(out);
        state.setData(frame, new Frame(List.copyOf(out.boxes), List.copyOf(out.tracers(MAX_TRACERS))));
        labels = config.hud.labels ? out.labels(config.hud.maxLabels) : List.of();
    }

    private void addTunnels(Extraction out) {
        FinderSettings settings = config.tunnels;
        for (TunnelDetector.Segment segment : scanner.tunnels()) {
            int color = switch (segment.kind()) {
                case TUNNEL -> settings.color;
                case HOLE -> settings.secondaryColor;
                case STAIRS -> settings.tertiaryColor;
            };
            String name = switch (segment.kind()) {
                case TUNNEL -> "Tunnel"; case HOLE -> "Hole"; case STAIRS -> "Staircase";
            } + " (" + segment.length() + ")";
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
            String label = "Activity " + Math.round(area.score() * 100) + "%";
            if (settings.surfaceMarker) {
                // The guide's corner edges lead from the square down to the geode's level.
                int guide = settings.mode != RenderMode.FILL ? argb(settings.color, settings.outlineOpacity * strength * 0.4) : 0;
                out.box(area.guideBounds(), 0, guide, width * 0.75f, settings.throughWalls);
                out.box(area.surfaceBounds(), fill, outline, width, settings.throughWalls);
                out.extras(settings, area.surfaceBounds(), settings.color, distance, label);
            } else {
                out.box(area.bounds(), fill, outline, width, settings.throughWalls);
                out.extras(settings, area.bounds(), settings.color, distance, label);
            }
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
            out.extras(settings, bounds, settings.tertiaryColor, distance, "Debris area");
        }
    }

    private void addEntities(Extraction out) {
        float partialTick = out.context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (Entity entity : out.context.world().entitiesForRendering()) {
            EntityKind kind = EntityKind.of(entity);
            if (kind == null) continue;
            FinderSettings settings = kind.settings(config);
            if (!settings.enabled || !kind.included(settings) || entity.isRemoved()) continue;
            // Draw where the entity is rendered this frame, between its last two ticks.
            Vec3 at = entity.getPosition(partialTick);
            AABB bounds = entity.getBoundingBox().move(at.subtract(entity.position())).inflate(0.05);
            double distance = bounds.getCenter().distanceTo(out.camera);
            if (distance > settings.range) continue;
            out.target(settings, bounds, kind.color(settings), distance, 1.0, entity.getName().getString());
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
        final WorldExtractionContext context;
        final Vec3 camera;
        final Vec3 forward;
        final List<DrawBox> boxes = new ArrayList<>();
        final List<DrawLine> tracerCandidates = new ArrayList<>();
        final List<Candidate> labelCandidates = new ArrayList<>();

        Extraction(WorldExtractionContext context, Vec3 camera, Vec3 forward) {
            this.context = context;
            this.camera = camera;
            this.forward = forward;
        }

        /** A highlight with the finder's styling, plus its tracer and label. */
        void target(FinderSettings settings, AABB bounds, int color, double distance, double strength, String label) {
            styledBox(settings, bounds, color, distance, strength);
            extras(settings, bounds, color, distance, label);
        }

        void styledBox(FinderSettings settings, AABB bounds, int color, double distance, double strength) {
            double fade = settings.distanceFade ? fade(distance, settings.range) : 1.0;
            int fill = settings.mode != RenderMode.OUTLINE ? argb(color, settings.fillOpacity * fade * strength) : 0;
            int outline = settings.mode != RenderMode.FILL ? argb(color, settings.outlineOpacity * fade * strength) : 0;
            box(bounds, fill, outline, (float) Math.clamp(settings.lineWidth, 0.5, 8.0), settings.throughWalls);
        }

        void extras(FinderSettings settings, AABB bounds, int color, double distance, String label) {
            if (settings.tracers) {
                Vec3 center = bounds.getCenter().subtract(camera);
                tracerCandidates.add(new DrawLine(forward, center, argb(color, Math.max(0.35, settings.outlineOpacity)),
                        (float) Math.clamp(settings.lineWidth, 0.5, 8.0), distance));
            }
            if (label != null) labelCandidates.add(new Candidate(bounds, label, color, distance));
        }

        void box(AABB box, int fill, int outline, float width, boolean throughWalls) {
            if ((fill >>> 24) == 0 && (outline >>> 24) == 0) return;
            AABB bounds = box.inflate(0.003);
            if (!context.frustum().isVisible(bounds)) return;
            // Subtract in double precision before making vertices, including near the world border.
            boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z), fill, outline, width, throughWalls));
        }

        List<DrawLine> tracers(int max) {
            if (tracerCandidates.size() > max) tracerCandidates.sort(Comparator.comparingDouble(DrawLine::distance));
            return tracerCandidates.subList(0, Math.min(max, tracerCandidates.size()));
        }

        /** Projects the nearest label candidates that are in front of the camera. */
        List<ScreenLabel> labels(int max) {
            Matrix4fc projection = WorldProjection.projection();
            if (projection == null || labelCandidates.isEmpty()) return List.of();
            labelCandidates.sort(Comparator.comparingDouble(Candidate::distance));
            Matrix4f transform = new Matrix4f(projection).mul(context.viewMatrix());
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

    private void draw(WorldRenderContext context) {
        if (closed) return;
        Frame boxes = ((FabricRenderState) context.worldState()).getData(frame);
        if (boxes == null || boxes.isEmpty()) return;
        PoseStack.Pose pose = context.matrices().last();
        for (DrawBox box : boxes.boxes) {
            if ((box.fill >>> 24) != 0) {
                filled((box.throughWalls ? fillThrough : fillVisible).begin(), pose.pose(), box.bounds, box.fill);
            }
            if ((box.outline >>> 24) != 0) {
                outlined((box.throughWalls ? lineThrough : lineVisible).begin(), pose, box.bounds, box.outline, box.width);
            }
        }
        for (DrawLine line : boxes.tracers) segment(lineThrough.begin(), pose, line);
        // Drawing all fills before lines keeps translucent surfaces from dimming their own outlines.
        fillVisible.flush();
        fillThrough.flush();
        lineVisible.flush();
        lineThrough.flush();
    }

    private static void filled(BufferBuilder buffer, Matrix4fc pose, AABB bounds, int color) {
        for (int[] face : FACES) {
            for (int corner : face) {
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner)).setColor(color);
            }
        }
    }

    private static void outlined(BufferBuilder buffer, PoseStack.Pose pose, AABB bounds, int color, float width) {
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
    private static void segment(BufferBuilder buffer, PoseStack.Pose pose, DrawLine line) {
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
        fillVisible.close();
        fillThrough.close();
        lineVisible.close();
        lineThrough.close();
    }

    private record Frame(List<DrawBox> boxes, List<DrawLine> tracers) {
        static final Frame EMPTY = new Frame(List.of(), List.of());
        boolean isEmpty() { return boxes.isEmpty() && tracers.isEmpty(); }
    }

    private record DrawBox(AABB bounds, int fill, int outline, float width, boolean throughWalls) { }

    /** A tracer from a point just in front of the camera, which projects to the crosshair, to a target. */
    private record DrawLine(Vec3 from, Vec3 to, int color, float width, double distance) { }

    private record Candidate(AABB bounds, String label, int color, double distance) { }

    /** A persistent CPU allocator and rotating GPU vertex buffer for one pipeline. */
    private static final class Batch implements AutoCloseable {
        private final RenderPipeline pipeline;
        private final ByteBufferBuilder allocator = new ByteBufferBuilder(64 * 1024);
        private BufferBuilder builder;
        private MappableRingBuffer vertices;

        private Batch(RenderPipeline pipeline) {
            this.pipeline = pipeline;
        }

        private BufferBuilder begin() {
            if (builder == null) builder = new BufferBuilder(allocator, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
            return builder;
        }

        private void flush() {
            if (builder == null) return;
            MeshData mesh = builder.build();
            builder = null;
            if (mesh == null) return;
            try (mesh) {
                int bytes = mesh.vertexBuffer().remaining();
                if (vertices == null || vertices.size() < bytes) {
                    if (vertices != null) vertices.close();
                    vertices = new MappableRingBuffer(() -> "Donut highlights", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes);
                }
                try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                        .mapBuffer(vertices.currentBuffer().slice(0, bytes), false, true)) {
                    MemoryUtil.memCopy(mesh.vertexBuffer(), mapped.data());
                }

                GpuBuffer indices;
                VertexFormat.IndexType indexType;
                if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
                    mesh.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
                    indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
                    indexType = mesh.drawState().indexType();
                } else {
                    RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
                    indices = sequential.getBuffer(mesh.drawState().indexCount());
                    indexType = sequential.type();
                }

                GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                        .writeTransform(RenderSystem.getModelViewMatrix(), WHITE, ZERO, IDENTITY);
                var target = Minecraft.getInstance().getMainRenderTarget();
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "Donut finder overlay", target.getColorTextureView(), OptionalInt.empty(),
                        target.getDepthTextureView(), OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transform);
                    pass.setVertexBuffer(0, vertices.currentBuffer());
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
                vertices.rotate();
            }
        }

        @Override
        public void close() {
            builder = null;
            allocator.close();
            if (vertices != null) {
                vertices.close();
                vertices = null;
            }
        }
    }
}
