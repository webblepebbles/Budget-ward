package game.player;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerControlsTest {

    private static Vector3f screenRight(float yaw, float pitch) {
        Player p = new Player(new CollisionWorld());
        p.setYaw(yaw);

        Vector3f eye = new Vector3f(0, 10, 0);
        float cosP = (float) Math.cos(pitch);
        Vector3f fwd = new Vector3f(
                (float) Math.sin(yaw) * cosP, (float) Math.sin(pitch), (float) Math.cos(yaw) * cosP);
        Matrix4f view = new Matrix4f().setLookAt(eye,
                new Vector3f(eye).add(fwd), new Vector3f(0, 1, 0));
        return new Vector3f(view.m00(), view.m10(), view.m20()).normalize();
    }

    private static final float EPS = 1e-4f;

    @Test
    void strafeD_movesScreenRight_atDefaultYaw() {
        Vector3f right = screenRight(0f, 0f);

        assertTrue(right.x < -0.99f && Math.abs(right.z) < EPS,
                "At yaw 0, screen right must be world -X (got " + right + ")");
    }

    @Test
    void playerRightVector_matchesViewMatrix_atAllYaws() {
        for (float yaw = -3.0f; yaw <= 3.0f; yaw += 0.37f) {
            Vector3f viewRight = screenRight(yaw, 0f);
            float rx = -(float) Math.cos(yaw);
            float rz = (float) Math.sin(yaw);
            assertTrue(Math.abs(viewRight.x - rx) < 1e-3f && Math.abs(viewRight.z - rz) < 1e-3f,
                    "yaw=" + yaw + ": player right (" + rx + "," + rz
                            + ") != view right (" + viewRight.x + "," + viewRight.z + ")");
        }
    }

    @Test
    void mouseRight_turnsViewRight() {

        float yaw0 = 0f;
        Vector3f fwd0 = new Vector3f((float) Math.sin(yaw0), 0, (float) Math.cos(yaw0));
        float yaw1 = yaw0 - 0.05f;
        Vector3f fwd1 = new Vector3f((float) Math.sin(yaw1), 0, (float) Math.cos(yaw1));
        Vector3f right = screenRight(yaw0, 0f);
        float gain = fwd1.dot(right) - fwd0.dot(right);
        assertTrue(gain > 0f,
                "+mouse dx must turn the view toward screen-right (gain=" + gain + ")");
    }
}
