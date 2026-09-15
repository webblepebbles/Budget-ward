package game.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedPoseTest {

    @Test
    void occupantLiesOnMattressInsideFootprint() {
        MovableBed bed = new MovableBed(0, -129f, 0.6f, -111f);
        bed.yaw = 0f;

        Npc p = new Npc(Npc.Role.PATIENT, 0f, 0f, 0f, 0.78f, 0.86f, 0.95f);
        float fx = (float) Math.sin(bed.yaw);
        float fz = (float) Math.cos(bed.yaw);
        p.x = bed.x - fx * 16.5f;
        p.z = bed.z - fz * 16.5f;
        p.y = bed.y + 8.9f;
        p.yaw = bed.yaw - (float) (Math.PI * 0.5);
        p.lying = true;

        Matrix4f m = new Matrix4f();
        Vector3f tmp = new Vector3f();
        float mattressTop = bed.y + 7.8f;
        for (int pi = 0; pi < p.parts().size(); pi++) {
            Npc.Part part = p.parts().get(pi);
            p.partMatrix(pi, 0f, m);
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        tmp.set(sx * part.hx, sy * part.hy, sz * part.hz);
                        m.transformPosition(tmp);
                        float lx = tmp.x - bed.x, lz = tmp.z - bed.z;
                        assertTrue(Math.abs(lx) <= MovableBed.HALF_W + 2.5f,
                                "part " + pi + " hangs off the side: " + lx);
                        assertTrue(Math.abs(lz) <= MovableBed.HALF_L + 2.5f,
                                "part " + pi + " hangs off the end: " + lz);
                        assertTrue(tmp.y >= mattressTop - 1.5f,
                                "part " + pi + " sinks into the bed: " + tmp.y);
                        assertTrue(tmp.y <= mattressTop + 4.5f,
                                "part " + pi + " floats above the bed: " + tmp.y);
                    }
                }
            }
        }
    }
}
