package game.player;

import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class Player {

    public static final float WORLD_SCALE = 9.6f;

    private static final float EYE = 1.62f * WORLD_SCALE;
    private static final float HEIGHT = 1.80f * WORLD_SCALE;
    private static final float RADIUS = 0.30f * WORLD_SCALE;

    private static final float WALK = 3.0f * WORLD_SCALE;
    private static final float SPRINT = 5.2f * WORLD_SCALE;
    private static final float ACCEL_GROUND = 30f * WORLD_SCALE;
    private static final float ACCEL_AIR = 8f * WORLD_SCALE;
    private static final float FRICTION = 12f;
    private static final float GRAVITY = 18f * WORLD_SCALE;
    private static final float JUMP_VEL = 5.6f * WORLD_SCALE;

    private static final float STEP_UP = 0.36f * WORLD_SCALE;
    private static final float SNAP_DOWN = 0.34f * WORLD_SCALE;
    private static final float SNAP_UP = 0.36f * WORLD_SCALE;

    private final CollisionWorld world;

    public float x, y, z;
    private float velX, velY, velZ;
    private float yaw, pitch;
    private boolean grounded;
    private float coyote;

    private static final float SPAWN_X = -8.4f;
    private static final float SPAWN_Y = 2.0f;
    private static final float SPAWN_Z = 185f;
    private static final float FOV_DEG = 75f;

    public float fovDeg = FOV_DEG;

    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f fwd = new Vector3f();
    private final Vector3f center = new Vector3f();

    public Player(CollisionWorld world) {
        this.world = world;
        respawn();
    }

    public void respawn() {
        x = SPAWN_X;
        y = SPAWN_Y;
        z = SPAWN_Z;
        velX = velY = velZ = 0;
        yaw = (float) Math.PI;
        pitch = 0f;
        grounded = false;

        float g = world.groundHeight(x, y + 1f, z, RADIUS);
        if (g != -Float.MAX_VALUE) {
            y = g;
        }
    }

    public void addLook(float dxYaw, float dyPitch) {
        yaw -= dxYaw;
        pitch = Math.clamp(pitch + dyPitch, -1.55f, 1.55f);

        if (yaw > Math.PI) yaw -= Math.PI * 2;
        if (yaw < -Math.PI) yaw += Math.PI * 2;
    }

    public void setYaw(float yawRad) {
        this.yaw = yawRad;
    }

    public void setPitch(float pitchRad) {
        this.pitch = Math.clamp(pitchRad, -1.55f, 1.55f);
    }

    public void update(float dt, boolean fwdIn, boolean back, boolean left,
                       boolean right, boolean sprint, boolean jump) {
        float moveF = (fwdIn ? 1f : 0f) - (back ? 1f : 0f);
        float moveR = (right ? 1f : 0f) - (left ? 1f : 0f);

        float sinY = Math.sin(yaw);
        float fwdX = sinY, fwdZ = Math.cos(yaw);

        float rightX = -Math.cos(yaw), rightZ = sinY;

        float wishX = fwdX * moveF + rightX * moveR;
        float wishZ = fwdZ * moveF + rightZ * moveR;
        float wishLen = (float) Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (wishLen > 1e-6f) {
            wishX /= wishLen;
            wishZ /= wishLen;
        }
        float speed = sprint ? SPRINT : WALK;

        float accel = grounded ? ACCEL_GROUND : ACCEL_AIR;
        if (wishLen > 1e-6f) {
            velX = approach(velX, wishX * speed, accel * dt);
            velZ = approach(velZ, wishZ * speed, accel * dt);
        } else if (grounded) {
            float fric = FRICTION * dt;
            velX = approach(velX, 0, speed * fric);
            velZ = approach(velZ, 0, speed * fric);
        }

        if (grounded) {
            coyote = 0.12f;
        } else {
            coyote -= dt;
        }
        if (jump && coyote > 0f && velY <= 0.01f) {
            velY = JUMP_VEL;
            grounded = false;
            coyote = 0f;
        }

        velY -= GRAVITY * dt;
        if (velY < -60f * WORLD_SCALE) {
            velY = -60f * WORLD_SCALE;
        }

        moveHorizontal(velX * dt, velZ * dt);

        moveVertical(velY * dt);

        if (y < -40f * WORLD_SCALE) {
            respawn();
        }
    }

    private void moveHorizontal(float dx, float dz) {
        if (dx == 0 && dz == 0) {
            return;
        }
        float[] out = new float[2];
        x += dx;
        z += dz;
        world.resolveHorizontal(x, y, z, RADIUS, HEIGHT, STEP_UP, out);
        x = out[0];
        z = out[1];

    }

    private void moveVertical(float dy) {
        boolean wasGrounded = grounded;
        float oldY = y;
        y += dy;
        grounded = false;

        if (velY > 0) {
            float ceil = world.ceilingHeight(x, y + HEIGHT - 0.05f, z, RADIUS * 0.85f);
            if (ceil != Float.MAX_VALUE && y + HEIGHT > ceil) {
                y = ceil - HEIGHT;
                velY = 0;
            }
        }

        float searchTop = Math.max(oldY, y) + (wasGrounded && velY <= 0 ? SNAP_UP : 0.02f);
        float g = world.groundHeight(x, searchTop, z, RADIUS * 0.95f);
        if (g != -Float.MAX_VALUE) {
            boolean land = false;
            if (wasGrounded && velY <= 0) {

                if (y - g <= SNAP_DOWN && g - y <= SNAP_UP) {
                    land = true;
                }
            } else if (velY <= 0) {

                if (g <= y + 0.02f && g >= oldY - 0.02f) {
                    land = true;
                } else if (g > y && g <= oldY + 0.02f) {
                    land = true;
                }
            }
            if (land) {
                y = g;
                if (velY < 0) {
                    velY = 0;
                }
                grounded = true;
            }
        }
    }

    private static float approach(float cur, float target, float maxDelta) {
        float d = target - cur;
        if (d > maxDelta) {
            return cur + maxDelta;
        }
        if (d < -maxDelta) {
            return cur - maxDelta;
        }
        return target;
    }

    public Vector3f eyePosition(Vector3f dest) {
        return dest.set(x, y + EYE, z);
    }

    public void writeViewProj(java.nio.FloatBuffer dest, float aspect) {
        float cosP = Math.cos(pitch);
        eye.set(x, y + EYE, z);
        fwd.set(Math.sin(yaw) * cosP, Math.sin(pitch), Math.cos(yaw) * cosP);
        center.set(eye).add(fwd);
        proj.setPerspective((float) java.lang.Math.toRadians(fovDeg), aspect,
                0.05f * WORLD_SCALE, 4000f);
        view.setLookAt(eye, center, UP);
        proj.mul(view).get(dest);
    }

    private static final Vector3f UP = new Vector3f(0f, 1f, 0f);

    public float eyeY() {
        return y + EYE;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean grounded() {
        return grounded;
    }

    public float radius() {
        return RADIUS;
    }

    public float height() {
        return HEIGHT;
    }

    public float stepUp() {
        return STEP_UP;
    }

    public float[] forwardXZ() {
        return new float[]{Math.sin(yaw), Math.cos(yaw)};
    }

    public static float interactRange() {
        return 3f * WORLD_SCALE;
    }
}
