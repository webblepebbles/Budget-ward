package game.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class Npc {

    public static final class Part {

        public final float hx, hy, hz;

        public final float ox, oy, oz;

        public final boolean swings;

        public final float side;

        public final float r, g, b;

        public final int cylSides;

        public Part(float hx, float hy, float hz, float ox, float oy, float oz,
                    boolean swings, float side, float r, float g, float b) {
            this(hx, hy, hz, ox, oy, oz, swings, side, r, g, b, 0);
        }

        public Part(float hx, float hy, float hz, float ox, float oy, float oz,
                    boolean swings, float side, float r, float g, float b, int cylSides) {
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.swings = swings; this.side = side;
            this.r = r; this.g = g; this.b = b;
            this.cylSides = cylSides;
        }

        public int vertCount() {
            return cylSides > 0 ? cylSides * 12 : 36;
        }
    }

    public enum Role { PATIENT, NURSE, DOCTOR }

    private static final float U = 9.6f;

    static final float[][] SKINS = {
            {0.96f, 0.80f, 0.64f},
            {0.93f, 0.74f, 0.54f},
            {0.86f, 0.64f, 0.44f},
            {0.72f, 0.50f, 0.34f},
            {0.52f, 0.34f, 0.22f},
            {0.98f, 0.88f, 0.78f},
            {0.90f, 0.68f, 0.48f},
            {0.40f, 0.26f, 0.18f},
    };

    static final float[][] HAIRS = {
            {0.12f, 0.10f, 0.09f},
            {0.28f, 0.18f, 0.10f},
            {0.46f, 0.28f, 0.12f},
            {0.74f, 0.62f, 0.34f},
            {0.58f, 0.58f, 0.60f},
            {0.82f, 0.32f, 0.48f},
            {0.18f, 0.42f, 0.50f},
            {0.20f, 0.22f, 0.38f},
    };

    public static final float[][] GOWNS = {
            {0.78f, 0.86f, 0.95f},
            {0.72f, 0.90f, 0.84f},
            {0.94f, 0.78f, 0.86f},
            {0.94f, 0.90f, 0.70f},
            {0.88f, 0.90f, 0.96f},
            {0.82f, 0.76f, 0.92f},
    };

    private final List<Part> parts = new ArrayList<>();
    public final Role role;
    public String name = "";

    public final int look;

    public float x, y, z;
    public float yaw;
    private float walkPhase;
    private float speed;
    private boolean moving;
    public boolean sitting;
    public boolean lying;

    private final List<float[]> path = new ArrayList<>();
    private int pathIdx;
    private float walkSpeed = 2.6f * U;

    public float navTx, navTz;
    public float navAge = 99f;

    public float stairAge = 99f;

    public float stallX, stallZ, stallT;

    public int planTag;

    public float groundY;

    public boolean isMoving() {
        return moving;
    }

    private final List<Float> pathY = new ArrayList<>();

    public enum PatientState { WAITING_LOBBY, FOLLOWING, IN_BED, TREATED, DISCHARGED_WALK, GONE }
    public PatientState state = PatientState.WAITING_LOBBY;
    public String illness = "";
    public String symptom = "";
    public boolean critical;
    public boolean orRequired;
    public boolean orRequested;
    public boolean orReady;
    public boolean diagnosed;
    public float criticalTimer;

    public int bedIndex = -1;

    public int treatStepsDone;

    public float surgeryTimer = -1f;

    public int orSlot = -1;

    public float[] orTable;

    public Npc(Role role, float x, float y, float z, float r, float g, float b) {
        this(role, x, y, z, r, g, b, 0);
    }

    public Npc(Role role, float x, float y, float z, float r, float g, float b, int look) {
        this.role = role;
        this.x = x; this.y = y; this.z = z;
        this.look = look;
        buildBody(r, g, b);
    }

    public int vertexCount() {
        int n = 0;
        for (Part p : parts) {
            n += p.vertCount();
        }
        return n;
    }

    private void part(float hx, float hy, float hz, float ox, float oy, float oz,
                      boolean swings, float side, float r, float g, float b) {
        parts.add(new Part(hx, hy, hz, ox, oy, oz, swings, side, r, g, b));
    }

    private void part(float hx, float hy, float hz, float ox, float oy, float oz,
                      boolean swings, float side, float r, float g, float b, int cyl) {
        parts.add(new Part(hx, hy, hz, ox, oy, oz, swings, side, r, g, b, cyl));
    }

    private void buildBody(float tr, float tg, float tb) {
        if (role == Role.PATIENT) {
            buildPatient(tr, tg, tb);
        } else {
            buildStaff(tr, tg, tb);
        }
    }

    private void buildPatient(float gownR, float gownG, float gownB) {
        int skinI = Math.floorMod(look, SKINS.length);
        int hairI = Math.floorMod(look / 3, HAIRS.length);
        int hairStyle = Math.floorMod(look / 2, 5);
        int body = Math.floorMod(look / 7, 3);
        float[] sk = SKINS[skinI];
        float sr = sk[0], sg = sk[1], sb = sk[2];
        float[] hr = HAIRS[hairI];
        float hR = hr[0], hG = hr[1], hB = hr[2];

        float wide = body == 2 ? 1.08f : body == 0 ? 0.92f : 1.0f;
        float thick = body == 2 ? 1.10f : body == 0 ? 0.94f : 1.0f;

        float torsoHx = 0.125f * U * wide;
        float torsoHy = 0.330f * U;
        float torsoHz = 0.150f * U * thick;
        float torsoOy = 0.975f * U;

        part(torsoHx, torsoHy, torsoHz, 0, torsoOy, 0, false, 0, gownR, gownG, gownB);

        part(torsoHx * 1.04f, 0.055f * U, torsoHz * 1.06f,
                0, torsoOy - torsoHy + 0.02f * U, 0.01f * U,
                false, 0, gownR * 0.78f, gownG * 0.82f, gownB * 0.88f);

        part(torsoHx * 0.55f, 0.07f * U, 0.04f * U,
                0, torsoOy + torsoHy - 0.02f * U, torsoHz + 0.01f * U,
                false, 0, 0.94f, 0.95f, 0.97f);

        part(0.018f * U, 0.16f * U, 0.012f * U,
                0, torsoOy + 0.04f * U, torsoHz + 0.008f * U,
                false, 0, 0.97f, 0.98f, 1.0f);

        part(0.055f * U, 0.045f * U, 0.012f * U,
                torsoHx * 0.45f, torsoOy - 0.04f * U, torsoHz + 0.01f * U,
                false, 0, gownR * 0.88f, gownG * 0.90f, gownB * 0.92f);

        part(0.016f * U, 0.09f * U, 0.016f * U,
                -torsoHx - 0.02f * U, torsoOy - 0.02f * U, -0.02f * U,
                false, 0, 0.92f, 0.93f, 0.90f);

        float neckR = 0.07f * U;
        float neckHy = 0.055f * U;
        float neckOy = torsoOy + torsoHy + neckHy;
        part(neckR, neckHy, neckR, 0, neckOy, 0, false, 0, sr, sg, sb, 8);

        float headR = 0.175f * U;
        float headOy = neckOy + neckHy + headR * 0.92f;
        part(headR, headR, headR, 0, headOy, 0, false, 0, sr, sg, sb, 12);

        float eyeOy = headOy + 0.02f * U;
        float eyeOz = headR + 0.006f * U;
        float eyeX = 0.055f * U;
        part(0.028f * U, 0.024f * U, 0.012f * U, -eyeX, eyeOy, eyeOz,
                false, 0, 0.97f, 0.97f, 0.98f);
        part(0.028f * U, 0.024f * U, 0.012f * U, eyeX, eyeOy, eyeOz,
                false, 0, 0.97f, 0.97f, 0.98f);
        part(0.014f * U, 0.016f * U, 0.013f * U, -eyeX, eyeOy, eyeOz + 0.004f * U,
                false, 0, 0.10f, 0.10f, 0.12f);
        part(0.014f * U, 0.016f * U, 0.013f * U, eyeX, eyeOy, eyeOz + 0.004f * U,
                false, 0, 0.10f, 0.10f, 0.12f);

        addHair(hairStyle, headR, headOy, hR, hG, hB);

        float armHx = 0.055f * U;
        float armHy = 0.24f * U;
        float armOx = torsoHx + armHx + 0.015f * U;
        float armOy = 0.99f * U;
        part(armHx, armHy, 0.055f * U, -armOx, armOy, 0, true, -1, gownR, gownG, gownB, 8);
        part(armHx, armHy, 0.055f * U, armOx, armOy, 0, true, 1, gownR, gownG, gownB, 8);

        part(0.052f * U, 0.055f * U, 0.052f * U, -armOx, 0.70f * U, 0, true, -1, sr, sg, sb, 8);
        part(0.052f * U, 0.055f * U, 0.052f * U, armOx, 0.70f * U, 0, true, 1, sr, sg, sb, 8);

        part(0.058f * U, 0.022f * U, 0.058f * U, -armOx, 0.78f * U, 0,
                true, -1, 0.95f, 0.55f, 0.22f, 8);

        float legHx = 0.085f * U * wide;
        float legHy = 0.270f * U;
        float legOx = 0.125f * U * wide;
        part(legHx, legHy, 0.085f * U, -legOx, 0.275f * U, 0, true, -1, sr * 0.92f, sg * 0.92f, sb * 0.92f, 8);
        part(legHx, legHy, 0.085f * U, legOx, 0.275f * U, 0, true, 1, sr * 0.92f, sg * 0.92f, sb * 0.92f, 8);

        float slipR = 0.90f, slipG = 0.90f, slipB = 0.93f;
        part(0.090f * U, 0.040f * U, 0.125f * U, -legOx, 0.040f * U, 0.02f * U,
                false, 0, slipR, slipG, slipB);
        part(0.090f * U, 0.040f * U, 0.125f * U, legOx, 0.040f * U, 0.02f * U,
                false, 0, slipR, slipG, slipB);
        part(0.070f * U, 0.018f * U, 0.040f * U, -legOx, 0.022f * U, 0.10f * U,
                false, 0, 0.55f, 0.72f, 0.82f);
        part(0.070f * U, 0.018f * U, 0.040f * U, legOx, 0.022f * U, 0.10f * U,
                false, 0, 0.55f, 0.72f, 0.82f);
    }

    private void addHair(int style, float headR, float headOy, float hR, float hG, float hB) {
        float capY = headOy + headR * 0.55f;
        if (style == 0) {

            part(headR * 0.72f, 0.028f * U, headR * 0.72f,
                    0, headOy + headR * 0.82f, 0, false, 0, hR, hG, hB, 8);
            return;
        }

        part(headR * 1.02f, 0.055f * U, headR * 1.02f,
                0, capY, -0.01f * U, false, 0, hR, hG, hB, 10);
        if (style == 2) {

            part(headR * 0.95f, 0.08f * U, 0.04f * U,
                    0, headOy + 0.02f * U, -headR * 0.85f, false, 0, hR, hG, hB);
        } else if (style == 3) {

            part(headR * 1.08f, 0.07f * U, 0.045f * U,
                    0, headOy - 0.02f * U, -headR * 0.70f, false, 0, hR, hG, hB);
            part(0.04f * U, 0.06f * U, 0.04f * U,
                    -headR * 0.85f, headOy + 0.02f * U, 0, false, 0, hR, hG, hB);
            part(0.04f * U, 0.06f * U, 0.04f * U,
                    headR * 0.85f, headOy + 0.02f * U, 0, false, 0, hR, hG, hB);
        } else if (style == 4) {

            part(0.04f * U, 0.07f * U, 0.04f * U,
                    -0.06f * U, headOy + headR + 0.02f * U, -0.02f * U, false, 0, hR, hG, hB);
            part(0.035f * U, 0.08f * U, 0.035f * U,
                    0.05f * U, headOy + headR + 0.03f * U, 0.02f * U, false, 0, hR, hG, hB);
            part(0.03f * U, 0.06f * U, 0.03f * U,
                    0.0f, headOy + headR + 0.01f * U, -0.06f * U, false, 0, hR, hG, hB);
        }
    }

    private void buildStaff(float tr, float tg, float tb) {
        float sr = 0.96f, sg = 0.80f, sb = 0.64f;
        float legR = 0.18f, legG = 0.22f, legB = 0.32f;
        if (role == Role.NURSE) {
            sr = 0.93f; sg = 0.74f; sb = 0.54f;
        }

        part(0.120f * U, 0.335f * U, 0.145f * U, 0, 0.975f * U, 0, false, 0, tr, tg, tb);

        part(0.080f * U, 0.045f * U, 0.04f * U,
                0, 1.28f * U, 0.12f * U, false, 0, 0.96f, 0.96f, 0.97f);

        part(0.09f * U, 0.270f * U, 0.09f * U, -0.125f * U, 0.275f * U, 0,
                true, -1, legR, legG, legB, 8);
        part(0.09f * U, 0.270f * U, 0.09f * U, 0.125f * U, 0.275f * U, 0,
                true, 1, legR, legG, legB, 8);

        part(0.095f * U, 0.045f * U, 0.13f * U, -0.125f * U, 0.045f * U, 0.02f * U,
                false, 0, 0.14f, 0.14f, 0.16f);
        part(0.095f * U, 0.045f * U, 0.13f * U, 0.125f * U, 0.045f * U, 0.02f * U,
                false, 0, 0.14f, 0.14f, 0.16f);

        part(0.055f * U, 0.24f * U, 0.055f * U, -0.335f * U, 0.99f * U, 0,
                true, -1, tr, tg, tb, 8);
        part(0.055f * U, 0.24f * U, 0.055f * U, 0.335f * U, 0.99f * U, 0,
                true, 1, tr, tg, tb, 8);

        part(0.052f * U, 0.055f * U, 0.052f * U, -0.335f * U, 0.70f * U, 0,
                true, -1, sr, sg, sb, 8);
        part(0.052f * U, 0.055f * U, 0.052f * U, 0.335f * U, 0.70f * U, 0,
                true, 1, sr, sg, sb, 8);

        float neckR = 0.065f * U;
        part(neckR, 0.05f * U, neckR, 0, 1.38f * U, 0, false, 0, sr, sg, sb, 8);

        float headOy = 1.52f * U, headR = 0.175f * U;
        part(headR, headR, headR, 0, headOy, 0, false, 0, sr, sg, sb, 12);

        float eyeOy = headOy + 0.02f * U, eyeOz = headR + 0.006f * U;
        part(0.028f * U, 0.024f * U, 0.012f * U, -0.055f * U, eyeOy, eyeOz,
                false, 0, 0.97f, 0.97f, 0.98f);
        part(0.028f * U, 0.024f * U, 0.012f * U, 0.055f * U, eyeOy, eyeOz,
                false, 0, 0.97f, 0.97f, 0.98f);
        part(0.014f * U, 0.016f * U, 0.013f * U, -0.055f * U, eyeOy, eyeOz + 0.004f * U,
                false, 0, 0.10f, 0.10f, 0.12f);
        part(0.014f * U, 0.016f * U, 0.013f * U, 0.055f * U, eyeOy, eyeOz + 0.004f * U,
                false, 0, 0.10f, 0.10f, 0.12f);

        if (role == Role.NURSE) {
            part(0.12f * U, 0.045f * U, 0.12f * U, 0,
                    headOy + headR + 0.02f * U, 0, false, 0,
                    0.95f, 0.95f, 0.96f, 10);
            part(headR * 0.9f, 0.04f * U, headR * 0.5f,
                    0, headOy + headR * 0.35f, -headR * 0.55f, false, 0,
                    0.28f, 0.18f, 0.10f);
            float crossOz = 0.145f * U + 0.008f * U;
            part(0.018f * U, 0.055f * U, 0.012f * U, 0, 0.99f * U, crossOz,
                    false, 0, 0.85f, 0.15f, 0.15f);
            part(0.055f * U, 0.018f * U, 0.012f * U, 0, 0.99f * U, crossOz,
                    false, 0, 0.85f, 0.15f, 0.15f);
        }
    }

    public List<Part> parts() {
        return parts;
    }

    public void setSymptomMarker(String symptom) {
        setSymptomMarker(symptom, -1f, -1f, -1f);
    }

    public void setSymptomMarker(String symptom, float r, float g, float b) {
        boolean custom = r >= 0f && g >= 0f && b >= 0f;
        float s = U;
        if ("chest".equals(symptom)) {
            float cr = custom ? r : 1.0f, cg = custom ? g : 0.18f, cb = custom ? b : 0.10f;
            parts.add(new Part(0.13f * s, 0.10f * s, 0.03f * s, 0, 1.10f * s, 0.16f * s,
                    false, 0, cr, cg, cb));
            parts.add(new Part(0.04f * s, 0.04f * s, 0.035f * s, 0.07f * s, 1.10f * s, 0.16f * s,
                    false, 0, 1.0f, 1.0f, 1.0f));
        } else if ("head".equals(symptom)) {
            float cr = custom ? r : 1.0f, cg = custom ? g : 0.80f, cb = custom ? b : 0.10f;
            parts.add(new Part(0.10f * s, 0.08f * s, 0.10f * s, 0.16f * s, 1.62f * s, 0,
                    false, 0, cr, cg, cb, 8));
        } else if ("leg".equals(symptom)) {
            float cr = custom ? r : 1.0f, cg = custom ? g : 0.45f, cb = custom ? b : 0.05f;
            parts.add(new Part(0.11f * s, 0.09f * s, 0.11f * s, 0.125f * s, 0.30f * s, 0,
                    false, 0, cr, cg, cb, 8));
        } else if ("arm".equals(symptom)) {
            float cr = custom ? r : 1.0f, cg = custom ? g : 0.15f, cb = custom ? b : 0.15f;
            parts.add(new Part(0.09f * s, 0.09f * s, 0.11f * s, -0.335f * s, 0.95f * s, 0,
                    false, 0, cr, cg, cb, 8));
        } else if ("pale".equals(symptom)) {
            float cr = custom ? r : 0.75f, cg = custom ? g : 0.90f, cb = custom ? b : 1.0f;
            parts.add(new Part(0.13f * s, 0.20f * s, 0.02f * s, 0, 1.05f * s, 0.155f * s,
                    false, 0, cr, cg, cb));
        } else if ("belly".equals(symptom)) {
            float cr = custom ? r : 0.30f, cg = custom ? g : 1.0f, cb = custom ? b : 0.35f;
            parts.add(new Part(0.12f * s, 0.10f * s, 0.03f * s, 0, 0.82f * s, 0.155f * s,
                    false, 0, cr, cg, cb));
        }
    }

    public Matrix4f partMatrix(int i, float time, Matrix4f dest) {
        Part p = parts.get(i);
        dest.identity();
        dest.translate(x, y, z);
        if (lying) {

            dest.rotateY(yaw);
            dest.rotateZ((float) Math.toRadians(-90));
            dest.translate(0, 0.9f * U, 0);
        } else {
            if (sitting) {
                dest.translate(0, -0.42f * U, 0);
            }
            dest.rotateY(yaw);
        }
        float swing = 0f;
        if (p.swings && moving && !sitting && !lying) {
            swing = (float) Math.sin(walkPhase * 2f + (p.side > 0 ? 0 : Math.PI)) * 0.55f;
        } else if (p.swings && (sitting || lying)) {
            swing = 0.15f;
        }

        float jointY = p.oy + p.hy;
        float ox = p.ox;
        if (lying && Math.abs(p.ox) > 2f) {

            ox = p.ox * 0.1f;
        }
        dest.translate(ox, jointY, 0);
        dest.rotateX(swing);
        dest.translate(0, -p.hy, 0);
        return dest;
    }

    public void update(float dt, float time) {
        update(dt, time, null);
    }

    public void update(float dt, float time, game.player.CollisionWorld world) {
        moving = false;

        if (state == PatientState.IN_BED && role == Role.PATIENT) {
            speed = 0f;
            return;
        }
        if (!path.isEmpty() && pathIdx < path.size()) {
            float[] t = path.get(pathIdx);
            float dx = t[0] - x, dz = t[1] - z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < 3f) {
                pathIdx++;
            } else {
                float step = walkSpeed * dt;
                float nx = dx / dist, nz = dz / dist;
                x += nx * step;
                z += nz * step;

                if (world != null) {
                    float[] out = new float[2];
                    world.resolveHorizontal(x, y, z, 2.6f, 1.6f * U, 0.36f * U, out);
                    x = out[0];
                    z = out[1];
                }

                if (pathIdx < pathY.size() && pathY.get(pathIdx) != null) {
                    float ty = pathY.get(pathIdx);
                    float dy = ty - y;
                    float maxDy = step * 0.9f;
                    y += Math.max(-maxDy, Math.min(maxDy, dy));
                } else if (world != null) {
                    float g = world.groundHeight(x, y + 6f, z, 2.0f);
                    if (g != -Float.MAX_VALUE && Math.abs(g - y) < 6f) {
                        y = g;
                    }
                }
                float targetYaw = (float) Math.atan2(nx, nz);
                yaw = turnToward(yaw, targetYaw, 6f * dt);
                moving = true;
                walkPhase += step * 0.16f;
            }
        }
        speed = moving ? walkSpeed : 0f;
    }

    private static float turnToward(float cur, float target, float maxStep) {
        float d = target - cur;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return cur + d;
    }

    public void setPath(float... xy) {
        path.clear();
        pathY.clear();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            path.add(new float[]{xy[i], xy[i + 1]});
            pathY.add(null);
        }
        pathIdx = 0;
        planTag = 0;
    }

    public void setPath3(float... xyz) {
        path.clear();
        pathY.clear();
        for (int i = 0; i + 2 < xyz.length; i += 3) {
            path.add(new float[]{xyz[i], xyz[i + 1]});
            pathY.add(xyz[i + 2]);
        }
        pathIdx = 0;
        planTag = 0;
    }

    public boolean pathDone() {
        return pathIdx >= path.size();
    }

    public boolean pathStarted() {
        return pathIdx > 0 && pathIdx < path.size();
    }

    public float[] debugTarget() {
        if (pathIdx < 0 || pathIdx >= path.size()) {
            return null;
        }
        float[] t = path.get(pathIdx);
        Float y = pathIdx < pathY.size() ? pathY.get(pathIdx) : null;
        return new float[]{t[0], t[1], y != null ? y : Float.NaN, path.size(), pathIdx};
    }

    public String debugPathKind() {
        int nulls = 0;
        for (Float f : pathY) {
            if (f == null) {
                nulls++;
            }
        }
        return "pts=" + path.size() + " nullY=" + nulls;
    }

    public Vector3f center(Vector3f dest) {
        return dest.set(x, y + 0.95f * U, z);
    }
}
