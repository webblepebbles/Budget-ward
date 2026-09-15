package game.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class AmbientOcclusion {

    static final float CELL = 10f;

    static final float BASELINE = 42f;

    static final float STRENGTH = 0.0020f;

    static final float MIN_AO = 0.40f;

    private AmbientOcclusion() {}

    public static float[] compute(FloatBuffer pos, IntBuffer emi, int vertCount) {
        float[] ao = new float[vertCount];
        if (vertCount <= 0) {
            return ao;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int v = 0; v < vertCount; v++) {
            float x = pos.get(v * 3), y = pos.get(v * 3 + 1), z = pos.get(v * 3 + 2);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        int nx = Math.max(1, (int) ((maxX - minX) / CELL) + 1);
        int ny = Math.max(1, (int) ((maxY - minY) / CELL) + 1);
        int nz = Math.max(1, (int) ((maxZ - minZ) / CELL) + 1);

        float cell = CELL;
        while ((long) nx * ny * nz > 4_000_000L) {
            cell *= 2f;
            nx = Math.max(1, (int) ((maxX - minX) / cell) + 1);
            ny = Math.max(1, (int) ((maxY - minY) / cell) + 1);
            nz = Math.max(1, (int) ((maxZ - minZ) / cell) + 1);
        }
        long cells = (long) nx * ny * nz;
        float[] density = new float[(int) cells];
        int[] cellOf = new int[vertCount];
        for (int v = 0; v < vertCount; v++) {
            int cx = (int) ((pos.get(v * 3) - minX) / cell);
            int cy = (int) ((pos.get(v * 3 + 1) - minY) / cell);
            int cz = (int) ((pos.get(v * 3 + 2) - minZ) / cell);
            if (cx < 0) cx = 0; else if (cx >= nx) cx = nx - 1;
            if (cy < 0) cy = 0; else if (cy >= ny) cy = ny - 1;
            if (cz < 0) cz = 0; else if (cz >= nz) cz = nz - 1;
            int c = (cy * nz + cz) * nx + cx;
            cellOf[v] = c;
            density[c] += 1f;
        }

        float[] smooth = new float[density.length];
        for (int cy = 0; cy < ny; cy++) {
            for (int cz = 0; cz < nz; cz++) {
                for (int cx = 0; cx < nx; cx++) {
                    float sum = 0f;
                    int n = 0;
                    for (int oy = -1; oy <= 1; oy++) {
                        int y = cy + oy;
                        if (y < 0 || y >= ny) continue;
                        for (int oz = -1; oz <= 1; oz++) {
                            int z = cz + oz;
                            if (z < 0 || z >= nz) continue;
                            int base = (y * nz + z) * nx;
                            for (int ox = -1; ox <= 1; ox++) {
                                int x = cx + ox;
                                if (x < 0 || x >= nx) continue;
                                sum += density[base + x];
                                n++;
                            }
                        }
                    }
                    smooth[(cy * nz + cz) * nx + cx] = sum / n;
                }
            }
        }
        for (int v = 0; v < vertCount; v++) {
            if (emi != null && emi.get(v) != 0) {
                ao[v] = 1f;
                continue;
            }
            float excess = smooth[cellOf[v]] - BASELINE;
            float f = 1f - excess * STRENGTH;
            if (f > 1f) f = 1f;
            else if (f < MIN_AO) f = MIN_AO;
            ao[v] = f;
        }
        return ao;
    }

    public static void applyToColors(FloatBuffer col, float[] ao, int vertCount) {
        for (int v = 0; v < vertCount; v++) {
            float f = ao[v];
            if (f >= 1f) continue;
            int o = v * 4;
            col.put(o, col.get(o) * f);
            col.put(o + 1, col.get(o + 1) * f);
            col.put(o + 2, col.get(o + 2) * f);
        }
    }

    public static float verticalFactor(float worldY, float groundY) {
        float t = (worldY - groundY) / 12f;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return 0.80f + 0.20f * t;
    }
}
