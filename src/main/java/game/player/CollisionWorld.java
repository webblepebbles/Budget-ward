package game.player;

import game.assets.ObjLoader;

import java.util.ArrayList;
import java.util.List;

public final class CollisionWorld {

    public static final class ResolveResult {
        public int pushes;
        public boolean hitWall;
    }

    private static final float CELL = 16f;
    private static final float WALK_COS = 0.55f;
    private static final float SKIN = 0.01f;

    private final HashMapLongObject<Cell> grid = new HashMapLongObject<>();
    private final List<Tri> allTris = new ArrayList<>(1 << 16);

    private final List<Barrier> barriers = new ArrayList<>();

    private static final class Barrier {
        float x1, z1, x2, z2;
        float y0, y1;
        float halfThick = 0.35f;
    }

    private static final class Tri {
        float ax, ay, az, bx, by, bz, cx, cy, cz;
        float nx, ny, nz;
        float cosUp;
        float minY, maxY;
        int mark;
    }

    private static final class Cell {
        final List<Tri> tris = new ArrayList<>(8);
    }

    private static final class HashMapLongObject<T> {
        private long[] keys = new long[1 << 12];
        private Object[] vals = new Object[1 << 12];
        private boolean[] used = new boolean[1 << 12];
        private int size;
        private int mask = keys.length - 1;

        @SuppressWarnings("unchecked")
        T get(long key) {
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    return (T) vals[i];
                }
                i = (i + 1) & mask;
            }
            return null;
        }

        void put(long key, T val) {
            if (size * 3 >= keys.length * 2) {
                rehash();
            }
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    vals[i] = val;
                    return;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            vals[i] = val;
            size++;
        }

        private void rehash() {
            long[] ok = keys;
            Object[] ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length << 1];
            vals = new Object[ok.length << 1];
            used = new boolean[ok.length << 1];
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    @SuppressWarnings("unchecked")
                    T v = (T) ov[i];
                    put(ok[i], v);
                }
            }
        }

        private static int mix(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            k *= 0xc4ceb9fe1a85ec53L;
            k ^= k >>> 33;
            return (int) (k ^ (k >>> 32));
        }
    }

    public void addModel(ObjLoader.Model model) {
        for (ObjLoader.MeshPart p : model.parts) {
            addPart(p);
        }
    }

    public void addPart(ObjLoader.MeshPart p) {
        float[] pos = p.positions;
        for (int i = 0; i + 8 < pos.length; i += 9) {
            addTri(pos[i], pos[i + 1], pos[i + 2],
                    pos[i + 3], pos[i + 4], pos[i + 5],
                    pos[i + 6], pos[i + 7], pos[i + 8]);
        }
    }

    public void addTri(float ax, float ay, float az,
                       float bx, float by, float bz,
                       float cx, float cy, float cz) {
        Tri t = new Tri();
        t.ax = ax; t.ay = ay; t.az = az;
        t.bx = bx; t.by = by; t.bz = bz;
        t.cx = cx; t.cy = cy; t.cz = cz;
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nxv = uy * vz - uz * vy;
        float nyv = uz * vx - ux * vz;
        float nzv = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
        if (len > 1e-9f) {
            t.nx = nxv / len; t.ny = nyv / len; t.nz = nzv / len;
            t.cosUp = Math.abs(t.ny);
        } else {
            t.nx = 0; t.ny = 1; t.nz = 0;
            t.cosUp = 1;
        }
        t.minY = Math.min(ay, Math.min(by, cy));
        t.maxY = Math.max(ay, Math.max(by, cy));
        allTris.add(t);
        int minCx = cell(min3(ax, bx, cx));
        int maxCx = cell(max3(ax, bx, cx));
        int minCz = cell(min3(az, bz, cz));
        int maxCz = cell(max3(az, bz, cz));
        for (int x = minCx; x <= maxCx; x++) {
            for (int z = minCz; z <= maxCz; z++) {
                cellAt(x, z).tris.add(t);
            }
        }
    }

    private static float min3(float a, float b, float c) {
        return Math.min(a, Math.min(b, c));
    }

    Tri testTri(float ax, float ay, float az, float bx, float by, float bz,
                float cx, float cy, float cz) {
        Tri t = new Tri();
        t.ax = ax; t.ay = ay; t.az = az;
        t.bx = bx; t.by = by; t.bz = bz;
        t.cx = cx; t.cy = cy; t.cz = cz;
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nxv = uy * vz - uz * vy;
        float nyv = uz * vx - ux * vz;
        float nzv = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
        if (len > 1e-9f) {
            t.nx = nxv / len; t.ny = nyv / len; t.nz = nzv / len;
            t.cosUp = Math.abs(t.ny);
        } else {
            t.nx = 0; t.ny = 1; t.nz = 0;
            t.cosUp = 1;
        }
        t.minY = Math.min(ay, Math.min(by, cy));
        t.maxY = Math.max(ay, Math.max(by, cy));
        return t;
    }

    private static float max3(float a, float b, float c) {
        return Math.max(a, Math.max(b, c));
    }

    private static int cell(float v) {
        return (int) Math.floor(v / CELL);
    }

    private static long cellKey(int cx, int cz) {
        return ((long) (cx + 32768) << 17) | (long) (cz + 32768);
    }

    private Cell cellAt(int cx, int cz) {
        long key = cellKey(cx, cz);
        Cell c = grid.get(key);
        if (c == null) {
            c = new Cell();
            grid.put(key, c);
        }
        return c;
    }

    public int triangleCount() {
        return allTris.size();
    }

    public String debugProbe(float px, float feetY, float pz, float radius,
                             float height, float stepUp) {
        StringBuilder sb = new StringBuilder();
        float blockAbove = feetY + stepUp;
        float top = feetY + height;
        int cx0 = cell(px - 6), cx1 = cell(px + 6);
        int cz0 = cell(pz - 6), cz1 = cell(pz + 6);
        seenMark++;

        float[] bestD = new float[4];
        Tri[] bestT = new Tri[4];
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Cell c = grid.get(cellKey(cx, cz));
                if (c == null) {
                    continue;
                }
                for (int i = 0; i < c.tris.size(); i++) {
                    Tri t = c.tris.get(i);
                    if (t.mark == seenMark) {
                        continue;
                    }
                    t.mark = seenMark;
                    if (t.cosUp >= WALK_COS || t.maxY <= blockAbove || t.minY >= top) {
                        continue;
                    }
                    float d = triXZDistance(t, px, pz);
                    for (int k = 0; k < 4; k++) {
                        if (bestT[k] == null || d < bestD[k]) {
                            for (int m = 3; m > k; m--) {
                                bestD[m] = bestD[m - 1];
                                bestT[m] = bestT[m - 1];
                            }
                            bestD[k] = d;
                            bestT[k] = t;
                            break;
                        }
                    }
                }
            }
        }
        int reported = 0;
        for (int k = 0; k < 4; k++) {
            if (bestT[k] == null) {
                continue;
            }
            Tri t = bestT[k];
            sb.append(String.format(
                    "  tri d=%.1f a(%.1f,%.1f,%.1f) b(%.1f,%.1f,%.1f) c(%.1f,%.1f,%.1f)%n",
                    bestD[k] - radius,
                    t.ax, t.ay, t.az, t.bx, t.by, t.bz, t.cx, t.cy, t.cz));
            reported++;
        }
        if (reported == 0) {
            sb.append("  (no wall triangles within 6 units)\n");
        }
        for (int bi = 0; bi < barriers.size(); bi++) {
            Barrier b = barriers.get(bi);
            if (b.y0 >= top || b.y1 <= blockAbove) {
                continue;
            }
            float abx = b.x2 - b.x1, abz = b.z2 - b.z1;
            float len2 = abx * abx + abz * abz;
            float tt = 0f;
            if (len2 > 1e-9f) {
                tt = ((px - b.x1) * abx + (pz - b.z1) * abz) / len2;
                tt = tt < 0 ? 0 : (tt > 1 ? 1 : tt);
            }
            float dx = px - (b.x1 + abx * tt), dz = pz - (b.z1 + abz * tt);
            float d = (float) Math.sqrt(dx * dx + dz * dz) - radius - b.halfThick;
            if (d < 6.0f) {
                sb.append(String.format(
                        "  barrier#%d (%.1f,%.1f)-(%.1f,%.1f) clearance %.2f%n",
                        bi, b.x1, b.z1, b.x2, b.z2, d));
            }
        }
        return sb.toString();
    }

    private float triXZDistance(Tri t, float px, float pz) {
        float py = t.ay;
        closestOnTri(px, py, pz, t, tmpClose);
        float dx = tmpClose[0] - px, dz = tmpClose[2] - pz;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private int seenMark = 0;

    public int addBarrier(float x1, float z1, float x2, float z2, float y0, float y1) {
        Barrier b = new Barrier();
        b.x1 = x1; b.z1 = z1; b.x2 = x2; b.z2 = z2;
        b.y0 = y0; b.y1 = y1;
        barriers.add(b);
        return barriers.size() - 1;
    }

    public void moveBarrier(int id, float x1, float z1, float x2, float z2) {
        Barrier b = barriers.get(id);
        b.x1 = x1; b.z1 = z1; b.x2 = x2; b.z2 = z2;
    }

    public ResolveResult resolveHorizontal(float px, float feetY, float pz,
                                           float radius, float height, float stepUp,
                                           float[] outXz) {
        return resolveHorizontal(px, feetY, pz, radius, height, stepUp, outXz, false);
    }

    public ResolveResult resolveHorizontal(float px, float feetY, float pz,
                                           float radius, float height, float stepUp,
                                           float[] outXz, boolean ignoreBarriers) {
        float x = px, z = pz;
        ResolveResult res = new ResolveResult();
        float blockAbove = feetY + stepUp;
        float top = feetY + height;
        for (int iter = 0; iter < 4; iter++) {
            int pushed = 0;
            int minCx = cell(x - radius - SKIN);
            int maxCx = cell(x + radius + SKIN);
            int minCz = cell(z - radius - SKIN);
            int maxCz = cell(z + radius + SKIN);
            seenMark++;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    Cell c = grid.get(cellKey(cx, cz));
                    if (c == null) {
                        continue;
                    }
                    List<Tri> list = c.tris;
                    for (int i = 0, n = list.size(); i < n; i++) {
                        Tri t = list.get(i);
                        if (t.mark == seenMark) {
                            continue;
                        }
                        t.mark = seenMark;

                        if (t.cosUp >= WALK_COS || t.maxY <= blockAbove || t.minY >= top) {
                            continue;
                        }
                        if (pushCylinderOut(t, x, feetY, z, radius, height, outXz)) {
                            x = outXz[0];
                            z = outXz[1];
                            res.pushes++;
                            res.hitWall = true;
                            pushed++;
                        }
                    }
                }
            }

            if (!ignoreBarriers) {
                for (int bi = 0; bi < barriers.size(); bi++) {
                    Barrier b = barriers.get(bi);
                    if (b.y0 >= top || b.y1 <= blockAbove) {
                        continue;
                    }
                    if (pushBarrierOut(b, x, z, radius, outXz)) {
                        x = outXz[0];
                        z = outXz[1];
                        res.pushes++;
                        res.hitWall = true;
                        pushed++;
                    }
                }
            }
            if (pushed == 0) {
                break;
            }
        }
        outXz[0] = x;
        outXz[1] = z;
        return res;
    }

    boolean pushCylinderOut(Tri t, float px, float feetY, float pz,
                            float radius, float height, float[] out) {

        segTriClosest(px, feetY, pz, px, feetY + height, pz, t, triPt, segPt);
        float dx = segPt[0] - triPt[0];
        float dz = segPt[2] - triPt[2];
        float d2 = dx * dx + dz * dz;
        if (d2 >= radius * radius) {
            return false;
        }
        float d = (float) Math.sqrt(d2);
        if (d < 1e-5f) {

            float nl = (float) Math.sqrt(t.nx * t.nx + t.nz * t.nz);
            if (nl < 1e-5f) {
                return false;
            }
            float corr = radius + SKIN;
            out[0] = px + t.nx / nl * corr;
            out[1] = pz + t.nz / nl * corr;
            return true;
        }
        float corr = (radius - d + SKIN) / d;
        out[0] = px + dx * corr;
        out[1] = pz + dz * corr;
        return true;
    }

    private boolean pushBarrierOut(Barrier b, float px, float pz, float radius, float[] out) {
        float abx = b.x2 - b.x1, abz = b.z2 - b.z1;
        float abLen2 = abx * abx + abz * abz;
        float t = 0f;
        if (abLen2 > 1e-9f) {
            t = ((px - b.x1) * abx + (pz - b.z1) * abz) / abLen2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
        }
        float cx = b.x1 + abx * t;
        float cz = b.z1 + abz * t;
        float dx = px - cx, dz = pz - cz;
        float d2 = dx * dx + dz * dz;
        float rr = radius + b.halfThick;
        if (d2 >= rr * rr) {
            return false;
        }
        float d = (float) Math.sqrt(d2);
        if (d < 1e-5f) {

            float nl = (float) Math.sqrt(abLen2);
            if (nl < 1e-5f) {
                out[0] = px + rr;
                out[1] = pz;
                return true;
            }
            float nx = -abz / nl, nz = abx / nl;
            float side = (px - b.x1) * nx + (pz - b.z1) * nz;
            float s = side >= 0 ? 1 : -1;
            out[0] = px + nx * s * rr;
            out[1] = pz + nz * s * rr;
            return true;
        }
        float corr = (rr - d + SKIN) / d;
        out[0] = px + dx * corr;
        out[1] = pz + dz * corr;
        return true;
    }

    public float groundHeight(float px, float maxY, float pz, float radius) {
        float best = -Float.MAX_VALUE;
        int cx0 = cell(px - radius), cx1 = cell(px + radius);
        int cz0 = cell(pz - radius), cz1 = cell(pz + radius);
        seenMark++;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Cell c = grid.get(cellKey(cx, cz));
                if (c == null) {
                    continue;
                }
                List<Tri> list = c.tris;
                for (int i = 0, n = list.size(); i < n; i++) {
                    Tri t = list.get(i);
                    if (t.mark == seenMark || t.cosUp < WALK_COS || t.maxY > maxY + 1f) {
                        continue;
                    }
                    t.mark = seenMark;
                    float h = triHeightAt(t, px, pz, radius);
                    if (h != h) {
                        continue;
                    }
                    if (h <= maxY && h > best) {
                        best = h;
                    }
                }
            }
        }
        return best;
    }

    public float ceilingHeight(float px, float minY, float pz, float radius) {
        float best = Float.MAX_VALUE;
        int cx0 = cell(px - radius), cx1 = cell(px + radius);
        int cz0 = cell(pz - radius), cz1 = cell(pz + radius);
        seenMark++;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Cell c = grid.get(cellKey(cx, cz));
                if (c == null) {
                    continue;
                }
                List<Tri> list = c.tris;
                for (int i = 0, n = list.size(); i < n; i++) {
                    Tri t = list.get(i);
                    if (t.mark == seenMark || t.ny > -WALK_COS || t.minY < minY - 1f) {
                        continue;
                    }
                    t.mark = seenMark;
                    float h = triHeightAt(t, px, pz, radius);
                    if (h != h) {
                        continue;
                    }
                    if (h >= minY && h < best) {
                        best = h;
                    }
                }
            }
        }
        return best;
    }

    private static float triHeightAt(Tri t, float px, float pz, float radius) {
        if (Math.abs(t.ny) < 1e-5f) {
            return Float.NaN;
        }
        float y = t.ay + (t.nx * (px - t.ax) + t.nz * (pz - t.az)) / -t.ny;
        closestOnTri(px, y, pz, t, tmpClose);
        float dx = tmpClose[0] - px, dz = tmpClose[2] - pz;
        if (dx * dx + dz * dz <= radius * radius) {
            return y;
        }
        return Float.NaN;
    }

    private static final float[] tmpClose = new float[3];

    private final float[] triPt = new float[3];
    private final float[] segPt = new float[3];
    private final float[] cand = new float[3];
    private final float[] best = new float[7];
    private final float[] outSeg = new float[3];
    private final float[] outTri = new float[3];

    private void segTriClosest(float ax, float ay, float az,
                               float bx, float by, float bz,
                               Tri t, float[] triPt, float[] segPt) {
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float denom = t.nx * abx + t.ny * aby + t.nz * abz;
        float distA = t.nx * (ax - t.ax) + t.ny * (ay - t.ay) + t.nz * (az - t.az);

        best[0] = Float.MAX_VALUE;

        consider2(ax, ay, az, t, ax, ay, az);
        consider2(bx, by, bz, t, bx, by, bz);

        if (Math.abs(denom) > 1e-9f) {
            float s = -distA / denom;
            if (s >= 0f && s <= 1f) {
                float px = ax + abx * s, py = ay + aby * s, pz = az + abz * s;
                consider2(px, py, pz, t, px, py, pz);
            }
        }

        segSegClosest(ax, ay, az, bx, by, bz, t.ax, t.ay, t.az, t.bx, t.by, t.bz, outSeg, outTri);
        considerPair(outSeg, outTri);
        segSegClosest(ax, ay, az, bx, by, bz, t.bx, t.by, t.bz, t.cx, t.cy, t.cz, outSeg, outTri);
        considerPair(outSeg, outTri);
        segSegClosest(ax, ay, az, bx, by, bz, t.cx, t.cy, t.cz, t.ax, t.ay, t.az, outSeg, outTri);
        considerPair(outSeg, outTri);

        if (best[0] == Float.MAX_VALUE) {

            triPt[0] = (t.ax + t.bx + t.cx) / 3f;
            triPt[1] = (t.ay + t.by + t.cy) / 3f;
            triPt[2] = (t.az + t.bz + t.cz) / 3f;
            segPt[0] = ax; segPt[1] = ay; segPt[2] = az;
            return;
        }
        triPt[0] = best[1]; triPt[1] = best[2]; triPt[2] = best[3];
        segPt[0] = best[4]; segPt[1] = best[5]; segPt[2] = best[6];
    }

    private void consider2(float px, float py, float pz, Tri t,
                           float sx, float sy, float sz) {
        closestOnTri(px, py, pz, t, cand);
        float dx = sx - cand[0], dy = sy - cand[1], dz = sz - cand[2];
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < best[0]) {
            best[0] = d2;
            best[1] = cand[0]; best[2] = cand[1]; best[3] = cand[2];
            best[4] = sx; best[5] = sy; best[6] = sz;
        }
    }

    private void considerPair(float[] seg, float[] tri) {
        float dx = seg[0] - tri[0], dy = seg[1] - tri[1], dz = seg[2] - tri[2];
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < best[0]) {
            best[0] = d2;
            best[1] = tri[0]; best[2] = tri[1]; best[3] = tri[2];
            best[4] = seg[0]; best[5] = seg[1]; best[6] = seg[2];
        }
    }

    private static void closestOnTri(float px, float py, float pz, Tri t, float[] out) {
        float abx = t.bx - t.ax, aby = t.by - t.ay, abz = t.bz - t.az;
        float acx = t.cx - t.ax, acy = t.cy - t.ay, acz = t.cz - t.az;
        float apx = px - t.ax, apy = py - t.ay, apz = pz - t.az;
        float d1 = abx * apx + aby * apy + abz * apz;
        float d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) {
            out[0] = t.ax; out[1] = t.ay; out[2] = t.az;
            return;
        }
        float bpx = px - t.bx, bpy = py - t.by, bpz = pz - t.bz;
        float d3 = abx * bpx + aby * bpy + abz * bpz;
        float d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) {
            out[0] = t.bx; out[1] = t.by; out[2] = t.bz;
            return;
        }
        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            float v = d1 / (d1 - d3);
            out[0] = t.ax + abx * v; out[1] = t.ay + aby * v; out[2] = t.az + abz * v;
            return;
        }
        float cpx = px - t.cx, cpy = py - t.cy, cpz = pz - t.cz;
        float d5 = abx * cpx + aby * cpy + abz * cpz;
        float d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) {
            out[0] = t.cx; out[1] = t.cy; out[2] = t.cz;
            return;
        }
        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            float w = d2 / (d2 - d6);
            out[0] = t.ax + acx * w; out[1] = t.ay + acy * w; out[2] = t.az + acz * w;
            return;
        }
        float va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            out[0] = t.bx + (t.cx - t.bx) * w;
            out[1] = t.by + (t.cy - t.by) * w;
            out[2] = t.bz + (t.cz - t.bz) * w;
            return;
        }
        float denom = 1f / (va + vb + vc);
        float v = vb * denom;
        float w = vc * denom;
        out[0] = t.ax + abx * v + acx * w;
        out[1] = t.ay + aby * v + acy * w;
        out[2] = t.az + abz * v + acz * w;
    }

    private static void segSegClosest(float p1x, float p1y, float p1z,
                                      float q1x, float q1y, float q1z,
                                      float p2x, float p2y, float p2z,
                                      float q2x, float q2y, float q2z,
                                      float[] out1, float[] out2) {
        float d1x = q1x - p1x, d1y = q1y - p1y, d1z = q1z - p1z;
        float d2x = q2x - p2x, d2y = q2y - p2y, d2z = q2z - p2z;
        float rx = p1x - p2x, ry = p1y - p2y, rz = p1z - p2z;
        float a = d1x * d1x + d1y * d1y + d1z * d1z;
        float e = d2x * d2x + d2y * d2y + d2z * d2z;
        float f = d2x * rx + d2y * ry + d2z * rz;
        float c = d1x * rx + d1y * ry + d1z * rz;
        float b = d1x * d2x + d1y * d2y + d1z * d2z;
        float denom = a * e - b * b;

        float s, t;
        if (denom < 1e-9f) {
            s = 0f;
            t = f >= 0 ? (e > 1e-9f ? Math.min(f / e, 1f) : 0f) : 0f;
        } else {
            s = (b * f - c * e) / denom;
            s = clamp01(s);
            t = 0f;
        }

        for (int pass = 0; pass < 2; pass++) {
            float tc = b * s + f;
            if (tc < 0) {
                t = 0;
                s = clamp01(-c > 0 && a > 1e-9f ? -c / a : 0f);
            } else if (e > 1e-9f) {
                t = Math.min(tc / e, 1f);
            } else {
                t = 0;
            }
        }
        out1[0] = p1x + d1x * s;
        out1[1] = p1y + d1y * s;
        out1[2] = p1z + d1z * s;
        out2[0] = p2x + d2x * t;
        out2[1] = p2y + d2y * t;
        out2[2] = p2z + d2z * t;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
