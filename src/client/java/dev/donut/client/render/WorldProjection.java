package dev.donut.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** The projection the world was last rendered with, including FOV effects and view bobbing. */
public final class WorldProjection {
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean captured;

    private WorldProjection() { }

    /** Called on the render thread just before the level renders. */
    public static void capture(Matrix4fc projection) {
        PROJECTION.set(projection);
        captured = true;
    }

    /** @return the last projection, or null before the first world frame */
    static Matrix4fc projection() { return captured ? PROJECTION : null; }
}
