package game.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class MovableBed {

    private static final float U = 9.6f;

    public static final float HALF_L = 1.05f * U;
    public static final float HALF_W = 0.475f * U;
    private static final float FRAME_H = 0.55f * U;
    private static final float WHEEL_R = 0.075f * U;

    public static final class Part {
        public final float hx, hy, hz, ox, oy, oz;
        public final float r, g, b;
        Part(float hx, float hy, float hz, float ox, float oy, float oz,
             float r, float g, float b) {
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.r = r; this.g = g; this.b = b;
        }
    }

    public final int id;
    public final List<Part> parts = new ArrayList<>();

    public final float homeX, homeY, homeZ;

    public float x, y, z;
    public float yaw;

    public int pusherId = -1;

    public Npc occupant;

    public float groundY;

    private float targetY;

    public MovableBed(int id, float x, float y, float z) {
        this.id = id;
        this.x = x; this.y = y; this.z = z;
        this.groundY = y;
        this.targetY = y;
        this.homeX = x; this.homeY = y; this.homeZ = z;
        buildBody(0.42f, 0.65f, 0.84f);
    }

    private void buildBody(float mr, float mg, float mb) {
        float fr = 0.94f, fg = 0.95f, fb = 0.97f;

        parts.add(new Part(HALF_W, FRAME_H * 0.5f, HALF_L,
                0, FRAME_H * 0.5f + WHEEL_R, 0, fr, fg, fb));

        parts.add(new Part(HALF_W * 0.94f, 0.09f * U, HALF_L * 0.96f,
                0, FRAME_H + WHEEL_R + 0.09f * U, 0, mr, mg, mb));

        parts.add(new Part(HALF_W * 0.8f, 0.06f * U, 0.22f * U,
                0, FRAME_H + WHEEL_R + 0.24f * U, HALF_L * 0.62f, 1f, 1f, 1f));

        parts.add(new Part(HALF_W, 0.34f * U, 0.05f * U,
                0, FRAME_H * 0.9f + WHEEL_R, -HALF_L + 0.05f * U, fr, fg, fb));
        parts.add(new Part(HALF_W, 0.22f * U, 0.05f * U,
                0, FRAME_H * 0.75f + WHEEL_R, HALF_L - 0.05f * U, fr, fg, fb));

        parts.add(new Part(HALF_W * 0.9f, 0.035f * U, 0.035f * U,
                0, FRAME_H + 0.30f * U + WHEEL_R, HALF_L + 0.06f * U,
                0.85f, 0.72f, 0.45f));

        float wx = HALF_W - WHEEL_R, wz = HALF_L - WHEEL_R;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                parts.add(new Part(WHEEL_R, WHEEL_R, WHEEL_R,
                        sx * wx, WHEEL_R, sz * wz, 0.12f, 0.12f, 0.14f));
            }
        }
    }

    public void update(float dt, game.player.Player pusher) {
        update(dt, pusher, null);
    }

    public void update(float dt, game.player.Player pusher, game.player.CollisionWorld world) {
        if (pusher != null) {

            float fx = (float) Math.sin(pusher.yaw());
            float fz = (float) Math.cos(pusher.yaw());
            float tx = pusher.x + fx * 1.5f * U;
            float tz = pusher.z + fz * 1.5f * U;
            float dx = tx - x, dz = tz - z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            float maxStep = 4.2f * U * dt;
            if (dist > 0.5f) {
                float m = Math.min(1f, maxStep / dist);
                x += dx * m;
                z += dz * m;
                yaw = (float) Math.atan2(fx, fz);
                if (world != null) {
                    float[] out = new float[2];
                    world.resolveHorizontal(x, y, z, HALF_W * 0.9f, FRAME_H * 3f, 0.36f * U, out);
                    x = out[0];
                    z = out[1];
                }
            }
        }

        float dy = targetY - y;
        float maxDy = 9f * U * dt;
        if (dy > maxDy) dy = maxDy;
        if (dy < -maxDy) dy = -maxDy;
        y += dy;
    }

    public void setGroundY(float groundY) {
        this.targetY = groundY;
    }

    public Matrix4f partMatrix(int i, Matrix4f dest) {
        Part p = parts.get(i);
        dest.identity();
        dest.translate(x, y, z);
        dest.rotateY(yaw);
        dest.translate(p.ox, p.oy, p.oz);
        return dest;
    }

    public List<Part> parts() {
        return parts;
    }

    public Vector3f center(Vector3f dest) {
        return dest.set(x, y + FRAME_H, z);
    }
}
