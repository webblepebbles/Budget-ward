package game.world;

import game.player.CollisionWorld;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class NavGrid {

    public static final float CELL = 8f;

    private static final float NPC_RADIUS = 2.6f;
    private static final float NPC_HEIGHT = 14f;
    private static final float NPC_STEP = 3.5f;

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};

    private final float minX, minZ;
    private final int nx, nz;

    private final float[] y0, y1;

    private final byte[] open0, done0, open1, done1;

    private NavGrid(float minX, float minZ, int nx, int nz, float[] y0, float[] y1) {
        this.minX = minX;
        this.minZ = minZ;
        this.nx = nx;
        this.nz = nz;
        this.y0 = y0;
        this.y1 = y1;
        this.open0 = new byte[nx * nz];
        this.done0 = new byte[nx * nz];
        this.open1 = new byte[nx * nz];
        this.done1 = new byte[nx * nz];
    }

    public static NavGrid build(CollisionWorld world,
                                float boundMinX, float boundMinZ,
                                float boundMaxX, float boundMaxZ) {
        float minX = boundMinX - 16f, minZ = boundMinZ - 16f;
        float maxX = boundMaxX + 16f, maxZ = boundMaxZ + 16f;
        int nx = Math.max(1, (int) Math.ceil((maxX - minX) / CELL));
        int nz = Math.max(1, (int) Math.ceil((maxZ - minZ) / CELL));
        float[] y0 = new float[nx * nz];
        float[] y1 = new float[nx * nz];
        java.util.Arrays.fill(y0, Float.NaN);
        java.util.Arrays.fill(y1, Float.NaN);

        float[] out = new float[2];
        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                float cx = minX + (ix + 0.5f) * CELL;
                float cz = minZ + (iz + 0.5f) * CELL;
                float g0 = world.groundHeight(cx, 10f, cz, 2.0f);
                if (g0 != -Float.MAX_VALUE && g0 >= -4f && g0 <= 12f
                        && clearAt(world, cx, g0, cz, out)) {
                    y0[iz * nx + ix] = g0;
                }

                float g1 = world.groundHeight(cx, 37f, cz, 2.0f);
                if (g1 != -Float.MAX_VALUE && g1 >= 28f && g1 <= 37f
                        && clearAt(world, cx, g1, cz, out)) {
                    y1[iz * nx + ix] = g1;
                }
            }
        }
        return new NavGrid(minX, minZ, nx, nz, y0, y1);
    }

    private static boolean clearAt(CollisionWorld world, float x, float y, float z, float[] out) {
        world.resolveHorizontal(x, y, z, NPC_RADIUS, NPC_HEIGHT, NPC_STEP, out, true);
        float dx = out[0] - x, dz = out[1] - z;
        return dx * dx + dz * dz < 1f;
    }

    public int walkableCells() {
        int n = 0;
        for (float v : y0) {
            if (v == v) {
                n++;
            }
        }
        for (float v : y1) {
            if (v == v) {
                n++;
            }
        }
        return n;
    }

    private float[] layerFor(float feetY) {
        return feetY > 20f ? y1 : y0;
    }

    private int nearestCell(float x, float z, float[] layer) {
        int cx = (int) Math.floor((x - minX) / CELL);
        int cz = (int) Math.floor((z - minZ) / CELL);
        if (inBounds(cx, cz) && layer[cz * nx + cx] == layer[cz * nx + cx]) {
            return cz * nx + cx;
        }
        for (int r = 1; r <= 7; r++) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int ix = cx + dx, iz = cz + dz;
                    if (!inBounds(ix, iz)) {
                        continue;
                    }
                    float v = layer[iz * nx + ix];
                    if (v != v) {
                        continue;
                    }
                    float wx = minX + (ix + 0.5f) * CELL;
                    float wz = minZ + (iz + 0.5f) * CELL;
                    float d = (wx - x) * (wx - x) + (wz - z) * (wz - z);
                    if (d < bestD) {
                        bestD = d;
                        best = iz * nx + ix;
                    }
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }

    private boolean inBounds(int ix, int iz) {
        return ix >= 0 && iz >= 0 && ix < nx && iz < nz;
    }

    public float[] findPath(CollisionWorld world,
                            float sx, float sy, float sz,
                            float tx, float ty, float tz) {
        boolean up = sy > 20f;

        if ((ty > 20f) != up) {
            return null;
        }
        float[] layer = layerFor(sy);
        int start = nearestCell(sx, sz, layer);
        int goal = nearestCell(tx, tz, layer);
        if (start < 0 || goal < 0) {
            return null;
        }
        if (start == goal) {
            return new float[]{tx, tz, layer[goal]};
        }

        int total = nx * nz;
        int[] prev = new int[total];
        java.util.Arrays.fill(prev, -1);
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        prev[start] = start;
        boolean upper = layer == y1;
        boolean found = false;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur == goal) {
                found = true;
                break;
            }
            int cx = cur % nx, cz = cur / nx;
            for (int dir = 0; dir < 8; dir++) {
                int ix = cx + DX[dir], iz = cz + DZ[dir];
                if (!inBounds(ix, iz)) {
                    continue;
                }
                int ni = iz * nx + ix;
                if (prev[ni] != -1) {
                    continue;
                }
                if (!edgeOpen(world, layer, upper, cur, dir)) {
                    continue;
                }
                prev[ni] = cur;
                queue.add(ni);
            }
        }
        if (!found) {
            return null;
        }

        List<Integer> cells = new ArrayList<>();
        int cur = goal;
        while (cur != start) {
            cells.add(cur);
            cur = prev[cur];
        }
        cells.add(start);
        java.util.Collections.reverse(cells);

        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{sx, sz, layer[start]});
        int anchor = 0;
        for (int i = 2; i < cells.size(); i++) {
            if (!segmentClear(world, cells.get(anchor), cells.get(i), layer)) {
                anchor = i - 1;
                pts.add(cellCenter(cells.get(anchor), layer));
            }
        }
        pts.add(new float[]{tx, tz, layer[goal]});

        float[] out = new float[pts.size() * 3];
        for (int i = 0; i < pts.size(); i++) {
            out[i * 3] = pts.get(i)[0];
            out[i * 3 + 1] = pts.get(i)[1];
            out[i * 3 + 2] = pts.get(i)[2];
        }
        return out;
    }

    private float[] cellCenter(int cell, float[] layer) {
        int ix = cell % nx, iz = cell / nx;
        return new float[]{minX + (ix + 0.5f) * CELL, minZ + (iz + 0.5f) * CELL, layer[cell]};
    }

    private final float[] probeOut = new float[2];

    private boolean probeClear(CollisionWorld world, float x, float y, float z) {
        return probePush(world, x, y, z) < 1f;
    }

    private boolean probeTight(CollisionWorld world, float x, float y, float z) {
        return probePush(world, x, y, z) < 0.0625f;
    }

    private float probePush(CollisionWorld world, float x, float y, float z) {
        world.resolveHorizontal(x, y, z, NPC_RADIUS, NPC_HEIGHT, NPC_STEP, probeOut, true);
        float ox = probeOut[0] - x, oz = probeOut[1] - z;
        return ox * ox + oz * oz;
    }

    private boolean edgeOpen(CollisionWorld world, float[] layer, boolean upper,
                             int cell, int dir) {
        byte[] open = upper ? open1 : open0;
        byte[] done = upper ? done1 : done0;
        int bit = 1 << dir;
        if ((done[cell] & bit) != 0) {
            return (open[cell] & bit) != 0;
        }
        boolean ok = computeEdge(world, layer, cell, dir);
        done[cell] |= bit;
        if (ok) {
            open[cell] |= bit;
        }
        return ok;
    }

    private boolean computeEdge(CollisionWorld world, float[] layer, int cell, int dir) {
        int cx = cell % nx, cz = cell / nx;
        int ix = cx + DX[dir], iz = cz + DZ[dir];
        if (!inBounds(ix, iz)) {
            return false;
        }
        int ni = iz * nx + ix;
        float ya = layer[cell], yb = layer[ni];
        if (ya != ya || yb != yb) {
            return false;
        }
        if (DX[dir] != 0 && DZ[dir] != 0) {

            float a = layer[cz * nx + ix];
            float b = layer[(cz + DZ[dir]) * nx + cx];
            if (a != a || b != b) {
                return false;
            }
        }
        float ax = minX + (cx + 0.5f) * CELL, az = minZ + (cz + 0.5f) * CELL;
        float bx = minX + (ix + 0.5f) * CELL, bz = minZ + (iz + 0.5f) * CELL;
        float mx = (ax + bx) * 0.5f, mz = (az + bz) * 0.5f;
        return probeTight(world, mx, Math.min(ya, yb), mz);
    }

    private boolean segmentClear(CollisionWorld world, int a, int b, float[] layer) {
        float[] pa = cellCenter(a, layer);
        float[] pb = cellCenter(b, layer);
        float dx = pb[0] - pa[0], dz = pb[1] - pa[1];
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) (dist / 1.5f));
        float y = Math.min(pa[2], pb[2]);
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            if (!probeTight(world, pa[0] + dx * t, y, pa[1] + dz * t)) {
                return false;
            }
        }
        return true;
    }
}
