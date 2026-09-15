package game.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepUpTest {

    private static final float R = 0.30f * Player.WORLD_SCALE;
    private static final float H = 1.80f * Player.WORLD_SCALE;
    private static final float STEP = 0.36f * Player.WORLD_SCALE;

    private static CollisionWorld flatFloor() {
        CollisionWorld w = new CollisionWorld();

        w.addTri(-20, 0, -20, 20, 0, -20, 20, 0, 20);
        w.addTri(-20, 0, -20, 20, 0, 20, -20, 0, 20);
        return w;
    }

    @Test
    void lowRiser_doesNotBlockHorizontalMove() {
        CollisionWorld w = flatFloor();

        float riserH = 1.72f;
        w.addTri(5, 0, -6, 5, riserH, -6, 5, riserH, 6);
        w.addTri(5, riserH, -6, 15, riserH, -6, 15, riserH, 6);
        w.addTri(5, riserH, -6, 15, riserH, 6, 5, riserH, 6);

        float[] out = new float[2];
        w.resolveHorizontal(0f, 0f, 0f, R, H, STEP, out);

        out[0] = 8f; out[1] = 0f;
        w.resolveHorizontal(8f, 0f, 0f, R, H, STEP, out);
        assertTrue(out[0] > 7.9f,
                "1.72-unit riser (below step-up " + STEP + ") must not block; stopped at x=" + out[0]);
    }

    @Test
    void tallWall_doesBlockHorizontalMove() {
        CollisionWorld w = flatFloor();

        w.addTri(5, 0, -6, 5, 10, -6, 5, 10, 6);

        float[] out = new float[2];
        out[0] = 7.5f; out[1] = 0f;
        w.resolveHorizontal(7.5f, 0f, 0f, R, H, STEP, out);
        assertTrue(out[0] >= 5f + R - 0.1f,
                "Tall wall must keep the cylinder outside; ended at x=" + out[0]);
    }

    @Test
    void stairRiser_heightIsBelowStepUp() {

        assertTrue(STEP > 1.72f * 1.5f,
                "STEP_UP=" + STEP + " too small for 1.72-unit stair risers");
    }

    @Test
    void groundSnap_carriesPlayerUpSmallLedge() {
        CollisionWorld w = flatFloor();
        float ledge = 3f;
        w.addTri(5, ledge, -20, 15, ledge, -20, 15, ledge, 20);
        w.addTri(5, ledge, -20, 15, ledge, 20, 5, ledge, 20);

        float[] out = new float[2];
        out[0] = 8f; out[1] = 0f;
        w.resolveHorizontal(8f, 0f, 0f, R, H, STEP, out);
        float g = w.groundHeight(8f, 0f + STEP, 0f, R * 0.95f);
        assertTrue(Math.abs(g - ledge) < 0.05f,
                "groundHeight within snap-up must see the ledge; got " + g);
    }

    @Test
    void barrier_blocksAndFollows() {
        CollisionWorld w = flatFloor();
        int b = w.addBarrier(5, -6, 5, 6, 0, 17.3f);

        float[] out = new float[2];
        out[0] = 4.2f; out[1] = 0f;
        w.resolveHorizontal(4.2f, 0f, 0f, R, H, STEP, out);
        assertTrue(out[0] < 5f - 0.1f,
                "Closed door barrier must block from the near side (x=" + out[0] + ")");

        out[0] = 6.5f; out[1] = 0f;
        w.resolveHorizontal(6.5f, 0f, 0f, R, H, STEP, out);
        assertTrue(out[0] > 5f,
                "Overlapping player ejected away from the closed barrier (x=" + out[0] + ")");

        w.moveBarrier(b, 15, -6, 15, 6);
        out[0] = 6.5f; out[1] = 0f;
        w.resolveHorizontal(6.5f, 0f, 0f, R, H, STEP, out);
        assertTrue(Math.abs(out[0] - 6.5f) < 0.05f,
                "Opened barrier must not block; pushed to x=" + out[0]);
    }

    @Test
    void cylinderPush_pureHorizontalWall() {
        CollisionWorld w = new CollisionWorld();
        float[] out = new float[2];

        w.addTri(0, -10, -10, 0, 10, -10, 0, 10, 10);
        boolean pushed = w.pushCylinderOut(
                w.testTri(0f, -10f, -10f, 0f, 10f, -10f, 0f, 10f, 10f),
                0.9f, 0f, 0f, 1f, 2.0f, out);
        assertTrue(pushed, "Cylinder intersecting a wall must be pushed");
        assertTrue(out[0] >= 0.95f && out[0] <= 1.15f,
                "Push must land the cylinder just outside the wall (x ~ 1+skin): got " + out[0]);
    }
}
