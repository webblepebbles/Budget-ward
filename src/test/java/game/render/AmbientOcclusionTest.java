package game.render;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

class AmbientOcclusionTest {

    private static FloatBuffer posOf(float[][] pts) {
        FloatBuffer b = MemoryUtil.memAllocFloat(pts.length * 3);
        for (float[] p : pts) {
            b.put(p[0]).put(p[1]).put(p[2]);
        }
        b.flip();
        return b;
    }

    private static IntBuffer emiOf(int n, int... ones) {
        IntBuffer b = MemoryUtil.memAllocInt(n);
        for (int i = 0; i < n; i++) {
            b.put(0);
        }
        for (int o : ones) {
            b.put(o, 1);
        }
        return b;
    }

    @Test
    void sparseGeometry_staysUnoccluded() {
        float[][] pts = { {0f, 0f, 0f}, {500f, 0f, 0f}, {0f, 500f, 0f}, {0f, 0f, 500f} };
        FloatBuffer pos = posOf(pts);
        float[] ao = AmbientOcclusion.compute(pos, emiOf(4), 4);
        for (float f : ao) {
            assertEquals(1f, f, 1e-6f);
        }
        MemoryUtil.memFree(pos);
    }

    @Test
    void denseCluster_isDarkerThanOpenArea() {

        java.util.List<float[]> list = new java.util.ArrayList<>();
        for (float x = 0f; x < 30f; x += 1.5f) {
            for (float y = 0f; y < 30f; y += 1.5f) {
                for (float z = 0f; z < 30f; z += 1.5f) {
                    list.add(new float[]{ x, y, z });
                }
            }
        }
        list.add(new float[]{ 120f, 0f, 0f });
        float[][] pts = list.toArray(new float[0][]);
        FloatBuffer pos = posOf(pts);
        float[] ao = AmbientOcclusion.compute(pos, emiOf(pts.length), pts.length);
        assertEquals(1f, ao[pts.length - 1], 1e-6f, "lone vertex must stay bright");
        assertTrue(ao[0] < 1f, "clustered vertex must be occluded, got " + ao[0]);
        assertTrue(ao[0] >= AmbientOcclusion.MIN_AO - 1e-6f, "must respect floor");
        MemoryUtil.memFree(pos);
    }

    @Test
    void emissiveVerts_areNeverOccluded() {
        int n = 400;
        float[][] pts = new float[n][];
        for (int i = 0; i < n; i++) {
            pts[i] = new float[]{ (i % 20) * 0.4f, ((i / 20) % 20) * 0.4f, 0f };
        }
        FloatBuffer pos = posOf(pts);
        float[] ao = AmbientOcclusion.compute(pos, emiOf(n, 0), n);
        assertEquals(1f, ao[0], 1e-6f, "emissive lamp verts skip AO");
        assertTrue(ao[1] < 1f, "non-emissive twin must occlude");
        MemoryUtil.memFree(pos);
    }

    @Test
    void verticalFactor_groundsDarkHeadsBright() {
        assertTrue(AmbientOcclusion.verticalFactor(0f, 0f)
                < AmbientOcclusion.verticalFactor(12f, 0f));
        assertEquals(1f, AmbientOcclusion.verticalFactor(100f, 0f), 1e-6f);
        assertEquals(0.80f, AmbientOcclusion.verticalFactor(-5f, 0f), 1e-6f);
    }

    @Test
    void applyToColors_scalesRgbKeepsAlpha() {
        FloatBuffer col = MemoryUtil.memAllocFloat(8);
        col.put(0, 1f).put(1, 0.5f).put(2, 0.25f).put(3, 0.35f);
        col.put(4, 0.2f).put(5, 0.2f).put(6, 0.2f).put(7, 1f);
        AmbientOcclusion.applyToColors(col, new float[]{ 0.5f, 1f }, 2);
        assertEquals(0.5f, col.get(0), 1e-6f);
        assertEquals(0.25f, col.get(1), 1e-6f);
        assertEquals(0.125f, col.get(2), 1e-6f);
        assertEquals(0.35f, col.get(3), 1e-6f, "alpha untouched (glass/transparency)");
        assertEquals(0.2f, col.get(4), 1e-6f);
        MemoryUtil.memFree(col);
    }
}
