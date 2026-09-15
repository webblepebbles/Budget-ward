package game.world;

import game.assets.ObjLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MapFixes {

    static final float[] LOOP_EAST = {140f, 158f, 0.5f, 28.5f, 32f, 74f};

    static final float[] HUB_BACK = {-20f, 10f, 0.5f, 28.5f, -28.6f, -26.2f};

    static final float[] STAIR_W_TOP = {-95.5f, -64.5f, 35.2f, 62f, -127f, -55.8f};

    static final float[] STAIR_E_TOP = {47.5f, 78.5f, 35.2f, 62f, -127f, -55.8f};

    static final float[] LID_W = {-95.5f, -64.5f, 28f, 35.4f, -127f, -122f};

    static final float[] LID_E = {47.5f, 78.5f, 28f, 35.4f, -127f, -122f};

    static final String[] STRAYS = {
            "DT5_H",
            "T5_Bed_Frame", "T5_Bed_Mattress", "T5_Bed_Pillow",
            "T5_Bed_Headboard", "T5_Bed_Footboard",
            "T5_Bed_Wheel_-0.38_-0.9", "T5_Bed_Wheel_-0.38_0.9",
            "T5_Bed_Wheel_0.38_-0.9", "T5_Bed_Wheel_0.38_0.9",
            "Art_W1_Heart2", "Art_W1_Heart3",
    };

    public static final java.util.Set<String> NO_COLLIDE = java.util.Set.of(
            "StairE_SignPlate",
            "StairW_SignPlate",
            "StairE_Guard_E",
            "StairE_Guard_N",
            "StairE_Guard_W");

    public static java.util.Set<String> withNoCollide(java.util.Set<String> named) {
        java.util.HashSet<String> out = new java.util.HashSet<>(named);
        out.addAll(NO_COLLIDE);
        return java.util.Set.copyOf(out);
    }

    static final float[][] BED_PLACEMENTS = {

            {-124.0f + -13.0f, -119.0f - 90.0f},
            {106.5f - 13.0f, -119.0f - 90.0f},
            {-9.0f - 13.0f, -119.0f - 90.0f},
            {207.0f - 13.0f, -8.5f - 90.0f},
            {-224.0f - 13.0f, -8.5f - 90.0f},
            {1.5f - 13.0f, 25.0f - 90.0f},
    };

    public static List<ObjLoader.MeshPart> buildBedClones(ObjLoader.Model bedsModel) {
        List<ObjLoader.MeshPart> out = new ArrayList<>();
        if (bedsModel == null || bedsModel.parts.isEmpty()) {
            return out;
        }

        int triCount = 0;
        for (ObjLoader.MeshPart p : bedsModel.parts) {
            if (!p.isTransparent()) {
                triCount += p.positions.length / 9;
            }
        }
        float[] tp = new float[triCount * 9];
        float[] tc = new float[triCount * 12];
        int w = 0;
        for (ObjLoader.MeshPart p : bedsModel.parts) {
            if (p.isTransparent()) {
                continue;
            }
            System.arraycopy(p.positions, 0, tp, w, p.positions.length);
            System.arraycopy(p.colors, 0, tc, w / 9 * 12, p.colors.length);
            w += p.positions.length;
        }
        for (int i = 0; i < BED_PLACEMENTS.length; i++) {
            float dx = BED_PLACEMENTS[i][0], dz = BED_PLACEMENTS[i][1];
            float[] np = new float[tp.length];
            System.arraycopy(tp, 0, np, 0, tp.length);
            for (int v = 0; v < np.length; v += 3) {
                np[v] += dx;
                np[v + 2] += dz;
            }
            float[] nc = new float[tc.length];
            System.arraycopy(tc, 0, nc, 0, tc.length);
            if (i == 5) {

                for (int c = 0; c < nc.length; c += 4) {
                    float r = nc[c], g = nc[c + 1], b = nc[c + 2];
                    boolean bluish = b > r + 0.15f;
                    if (bluish) {

                        nc[c] = (float) Math.pow(0.85, 2.2);
                        nc[c + 1] = (float) Math.pow(0.25, 2.2);
                        nc[c + 2] = (float) Math.pow(0.22, 2.2);
                    }
                }
            }
            float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bz0 = Float.MAX_VALUE;
            float bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE, bz1 = -Float.MAX_VALUE;
            for (int v = 0; v < np.length; v += 3) {
                bx0 = Math.min(bx0, np[v]); by0 = Math.min(by0, np[v + 1]); bz0 = Math.min(bz0, np[v + 2]);
                bx1 = Math.max(bx1, np[v]); by1 = Math.max(by1, np[v + 1]); bz1 = Math.max(bz1, np[v + 2]);
            }
            out.add(new ObjLoader.MeshPart("BW_bed", "BedClone" + i, np, nc, 1f, false, false,
                    new float[]{bx0, by0, bz0}, new float[]{bx1, by1, bz1}));
        }
        return out;
    }

    public static int apply(ObjLoader.Model map) {
        int touched = 0;
        touched += carve(map, LOOP_EAST);
        touched += carve(map, HUB_BACK);
        touched += carve(map, STAIR_W_TOP);
        touched += carve(map, STAIR_E_TOP);
        touched += carve(map, LID_W);
        touched += carve(map, LID_E);

        for (int i = map.parts.size() - 1; i >= 0; i--) {
            ObjLoader.MeshPart p = map.parts.get(i);
            for (String s : STRAYS) {
                if (s.equals(p.objectName)) {
                    map.parts.remove(i);
                    touched++;
                    break;
                }
            }
        }
        touched += recolorWhites(map);
        map.rebuildGlobalBounds();
        return touched;
    }

    static int recolorWhites(ObjLoader.Model map) {

        List<float[]> canvasBoxes = new ArrayList<>();
        List<float[]> canvasCols = new ArrayList<>();
        float[][] accents = {
                {1.00f, 0.25f, 0.20f},
                {0.10f, 0.80f, 0.75f},
                {1.00f, 0.55f, 0.10f},
                {0.65f, 0.35f, 1.00f},
                {0.25f, 0.85f, 0.35f},
                {0.25f, 0.50f, 1.00f},
                {1.00f, 0.40f, 0.70f},
                {1.00f, 0.85f, 0.15f},
        };
        int ai = 0;
        for (Map.Entry<String, float[]> e : map.objectBounds.entrySet()) {
            String n = e.getKey();
            if (!n.startsWith("Art_") || !n.contains("Canvas")) {
                continue;
            }
            float[] b = e.getValue();
            if (b == null || b[0] == Float.MAX_VALUE) {
                continue;
            }
            canvasBoxes.add(b);
            float[] c = accents[ai % accents.length];
            ai++;
            canvasCols.add(new float[]{
                    (float) Math.pow(c[0], 2.2),
                    (float) Math.pow(c[1], 2.2),
                    (float) Math.pow(c[2], 2.2)});
        }

        int tris = 0;
        tris += recolorPartList(map.parts, canvasBoxes, canvasCols);
        for (ObjLoader.MeshPart head : map.namedParts.values()) {
            for (ObjLoader.MeshPart p = head; p != null; p = p.next) {

                List<ObjLoader.MeshPart> one = new ArrayList<>(1);
                one.add(p);
                tris += recolorPartList(one, canvasBoxes, canvasCols);
            }
        }
        if (tris > 0) {
            System.out.println("MapFixes: retinted " + tris + " near-white triangle(s)");
        }
        return tris;
    }

    private static int recolorPartList(List<ObjLoader.MeshPart> parts,
                                       List<float[]> canvasBoxes, List<float[]> canvasCols) {
        int tris = 0;
        for (ObjLoader.MeshPart p : parts) {
            if (p.transparent || p.emissive) {
                continue;
            }
            String mat = p.material;
            int dot = mat.indexOf('.');
            String base = dot >= 0 ? mat.substring(0, dot) : mat;
            switch (base) {
                case "BW_white", "BW_offwhite", "BW_paint_white", "BW_ceil_tile",
                     "BW_glue_white", "BW_treat_upper", "BW_speckle", "BW_counter_speck",
                     "BW_warm_gray", "BW_concrete" -> { }
                default -> { continue; }
            }
            float[] pos = p.positions;
            float[] col = p.colors;
            int verts = pos.length / 3;
            for (int t = 0; t + 2 < verts; t += 3) {

                boolean nearWhite = true;
                float cx = 0f, cy = 0f, cz = 0f;
                for (int k = 0; k < 3; k++) {
                    int v = t + k;
                    float r = col[v * 4], g = col[v * 4 + 1], b = col[v * 4 + 2];
                    float mn = Math.min(r, Math.min(g, b));
                    float mx = Math.max(r, Math.max(g, b));
                    if (mn < 0.45f || mx - mn > 0.30f || (r + g + b) / 3f < 0.55f) {
                        nearWhite = false;
                        break;
                    }
                    cx += pos[v * 3]; cy += pos[v * 3 + 1]; cz += pos[v * 3 + 2];
                }
                if (!nearWhite) {
                    continue;
                }
                cx /= 3f; cy /= 3f; cz /= 3f;
                if (cy < 2.0f) {
                    continue;
                }
                float[] tint = null;
                for (int c = 0; c < canvasBoxes.size(); c++) {
                    float[] bb = canvasBoxes.get(c);
                    if (cx >= bb[0] - 0.6f && cx <= bb[3] + 0.6f
                            && cy >= bb[1] - 0.6f && cy <= bb[4] + 0.6f
                            && cz >= bb[2] - 0.6f && cz <= bb[5] + 0.6f) {
                        tint = canvasCols.get(c);
                        break;
                    }
                }
                if (tint == null) {
                    tint = zoneTint(cx, cy, cz);
                }
                for (int k = 0; k < 3; k++) {
                    int v = t + k;
                    col[v * 4] = tint[0];
                    col[v * 4 + 1] = tint[1];
                    col[v * 4 + 2] = tint[2];
                }
                tris += 1;
            }
        }
        return tris;
    }

    private static float[] zoneTint(float x, float y, float z) {
        float[] c;
        if (y > 30f) {

            if (x < -80f) {
                c = new float[]{0.72f, 0.92f, 0.78f};
            } else if (x > 90f) {
                c = new float[]{0.66f, 0.84f, 1.00f};
            } else {
                c = new float[]{0.82f, 0.76f, 1.00f};
            }
        } else if (z > 85f && x > -70f && x < 45f) {
            c = new float[]{1.00f, 0.82f, 0.64f};
        } else if (z > -15f && z <= 85f && x > -75f && x < 55f) {
            c = new float[]{0.66f, 0.84f, 1.00f};
        } else if (x >= 80f) {
            c = new float[]{0.82f, 0.76f, 1.00f};
        } else if (x <= -80f) {
            c = new float[]{0.68f, 0.92f, 0.80f};
        } else if (z <= -60f) {
            c = new float[]{1.00f, 0.92f, 0.66f};
        } else {
            c = new float[]{1.00f, 0.76f, 0.70f};
        }
        float shade = ((y > 28f && y < 34f) || y > 55f) ? 0.90f : 1.0f;
        return new float[]{
                (float) Math.pow(c[0] * shade, 2.2),
                (float) Math.pow(c[1] * shade, 2.2),
                (float) Math.pow(c[2] * shade, 2.2)};
    }

    public static boolean isStrayObject(String objectName) {
        if (objectName == null || objectName.isEmpty()) {
            return false;
        }
        for (String s : STRAYS) {
            if (s.equals(objectName)) {
                return true;
            }
        }
        return false;
    }

    private static int carve(ObjLoader.Model m, float[] hole) {
        float hx0 = hole[0], hx1 = hole[1];
        float hy0 = hole[2], hy1 = hole[3];
        float hz0 = hole[4], hz1 = hole[5];
        int splitParts = 0;
        for (int pi = 0; pi < m.parts.size(); pi++) {
            ObjLoader.MeshPart p = m.parts.get(pi);
            if (NO_COLLIDE.contains(p.objectName)) {
                continue;
            }
            float[] pos = p.positions;
            float[] col = p.colors;
            if (p.boundsMax[0] < hx0 || p.boundsMin[0] > hx1
                    || p.boundsMax[1] < hy0 || p.boundsMin[1] > hy1
                    || p.boundsMax[2] < hz0 || p.boundsMin[2] > hz1) {
                continue;
            }
            java.util.List<float[]> out = new ArrayList<>();
            boolean touched = false;
            for (int i = 0; i + 8 < pos.length; i += 9) {
                float[] entry = new float[21];
                System.arraycopy(pos, i, entry, 0, 9);
                System.arraycopy(col, i / 9 * 12, entry, 9, 12);
                int before = out.size();
                carveTri(entry, hx0, hx1, hy0, hy1, hz0, hz1, out);
                if (out.size() != before + 1) {
                    touched = true;
                }
            }
            if (!touched) {
                continue;
            }
            splitParts++;
            int n = out.size();
            float[] np = new float[n * 9];
            float[] nc = new float[n * 12];
            float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bz0 = Float.MAX_VALUE;
            float bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE, bz1 = -Float.MAX_VALUE;
            for (int t = 0; t < n; t++) {
                float[] e = out.get(t);
                System.arraycopy(e, 0, np, t * 9, 9);
                System.arraycopy(e, 9, nc, t * 12, 12);
                for (int v = 0; v < 3; v++) {
                    float x = e[v * 3], y = e[v * 3 + 1], z = e[v * 3 + 2];
                    bx0 = Math.min(bx0, x); by0 = Math.min(by0, y); bz0 = Math.min(bz0, z);
                    bx1 = Math.max(bx1, x); by1 = Math.max(by1, y); bz1 = Math.max(bz1, z);
                }
            }
            ObjLoader.MeshPart replacement = new ObjLoader.MeshPart(p.material, p.objectName,
                    np, nc, p.alpha, p.emissive, p.transparent,
                    new float[]{bx0, by0, bz0}, new float[]{bx1, by1, bz1});
            m.parts.set(pi, replacement);
        }
        return splitParts;
    }

    private static void carveTri(float[] entry,
                                 float hx0, float hx1, float hy0, float hy1, float hz0, float hz1,
                                 java.util.List<float[]> out) {
        java.util.List<float[]> hole = new ArrayList<>();
        hole.add(entry);
        hole = clipHalf(hole, 0, hx0, false, out);
        hole = clipHalf(hole, 0, hx1, true, out);
        hole = clipHalf(hole, 1, hy0, false, out);
        hole = clipHalf(hole, 1, hy1, true, out);
        hole = clipHalf(hole, 2, hz0, false, out);
        hole = clipHalf(hole, 2, hz1, true, out);
    }

    private static boolean holeSide(float v, float bound, boolean keepLess) {
        return keepLess ? v <= bound : v >= bound;
    }

    private static java.util.List<float[]> clipHalf(java.util.List<float[]> polys,
                                                    int axis, float bound, boolean keepLess,
                                                    java.util.List<float[]> out) {
        java.util.List<float[]> nextHole = new ArrayList<>();
        for (float[] p : polys) {
            int n = (p.length - 12) / 3;
            boolean[] in = new boolean[n];
            int nin = 0;
            for (int v = 0; v < n; v++) {
                in[v] = holeSide(p[v * 3 + axis], bound, keepLess);
                if (in[v]) {
                    nin++;
                }
            }
            if (nin == n) {
                nextHole.add(p);
                continue;
            }
            if (nin == 0) {
                emitPoly(p, out);
                continue;
            }
            java.util.List<Float> aPos = new ArrayList<>();
            java.util.List<Float> bPos = new ArrayList<>();
            for (int v = 0; v < n; v++) {
                int u = (v + 1) % n;
                java.util.List<Float> dst = in[v] ? aPos : bPos;
                dst.add(p[v * 3]); dst.add(p[v * 3 + 1]); dst.add(p[v * 3 + 2]);
                if (in[v] != in[u]) {
                    float t = (bound - p[v * 3 + axis]) / (p[u * 3 + axis] - p[v * 3 + axis]);
                    for (int c = 0; c < 3; c++) {
                        float x = p[v * 3 + c] + t * (p[u * 3 + c] - p[v * 3 + c]);
                        aPos.add(x);
                        bPos.add(x);
                    }
                }
            }
            if (!aPos.isEmpty()) {
                nextHole.add(makePoly(aPos, p));
            }
            if (!bPos.isEmpty()) {
                emitPoly(makePoly(bPos, p), out);
            }
        }
        return nextHole;
    }

    private static float[] makePoly(java.util.List<Float> pos, float[] src) {
        int n = pos.size() / 3;
        float[] poly = new float[n * 3 + 12];
        for (int i = 0; i < n * 3; i++) {
            poly[i] = pos.get(i);
        }
        System.arraycopy(src, src.length - 12, poly, n * 3, 12);
        return poly;
    }

    private static void emitPoly(float[] poly, java.util.List<float[]> out) {
        int n = (poly.length - 12) / 3;
        for (int t = 1; t < n - 1; t++) {
            float[] e = new float[21];
            System.arraycopy(poly, 0, e, 0, 3);
            System.arraycopy(poly, t * 3, e, 3, 3);
            System.arraycopy(poly, (t + 1) * 3, e, 6, 3);
            System.arraycopy(poly, poly.length - 12, e, 9, 12);
            out.add(e);
        }
    }

    public static final class PLight {
        public final float x, y, z, radius, r, g, b;
        public PLight(float x, float y, float z, float radius, float r, float g, float b) {
            this.x = x; this.y = y; this.z = z; this.radius = radius;
            this.r = r; this.g = g; this.b = b;
        }
    }

    public static PLight lightForObject(String objectName, float cx, float cy, float cz) {

        String n = objectName == null ? "" : objectName;
        if (n.startsWith("Corr_Panel") || n.startsWith("UpCorr_Panel")) {
            return new PLight(cx, cy - 1.5f, cz, 88f, 1.0f, 0.97f, 0.90f);
        }
        if (n.startsWith("Corr_Spot") || n.startsWith("UpCorr_Panel")) {
            return new PLight(cx, cy - 2f, cz, 56f, 1.0f, 0.94f, 0.84f);
        }
        if (n.startsWith("Pendant_") && n.contains("Globe")) {
            return new PLight(cx, cy - 2f, cz, 82f, 1.0f, 0.90f, 0.76f);
        }
        if (n.startsWith("Canopy_Downlight")) {
            return new PLight(cx, cy - 3f, cz, 64f, 1.0f, 0.93f, 0.80f);
        }
        if (n.startsWith("T1_Spot") || n.startsWith("T2_Spot") || n.startsWith("T3_Spot")
                || n.startsWith("T4_Spot") || n.startsWith("T5_Spot")) {
            return new PLight(cx, cy - 2f, cz, 64f, 1.0f, 0.96f, 0.88f);
        }
        if (n.endsWith("_CeilPanel") || (n.startsWith("HUB_") && n.contains("Spot"))) {
            return new PLight(cx, cy - 2f, cz, 84f, 1.0f, 0.97f, 0.90f);
        }
        if (n.contains("Lamp_Head") || n.contains("Lamp_Lens")) {
            return new PLight(cx, cy - 2f, cz, 70f, 0.96f, 1.0f, 0.96f);
        }
        if (n.contains("MC_Heart") || n.contains("Clock") || n.contains("StatusLED")) {
            return new PLight(cx, cy, cz, 32f, 0.72f, 0.24f, 0.18f);
        }
        if (n.contains("Elev_Cab_Light")) {
            return new PLight(cx, cy - 2f, cz, 64f, 1.0f, 0.98f, 0.92f);
        }
        if (n.startsWith("Bollard_Light") || n.startsWith("Lot_Lamp")) {
            return new PLight(cx, cy, cz, 72f, 1.0f, 0.86f, 0.62f);
        }
        if (n.startsWith("Tank_Lid") || n.startsWith("Tank_Glass")) {
            return new PLight(cx, cy, cz, 46f, 0.55f, 0.84f, 0.95f);
        }
        return null;
    }

    public static List<PLight> collectLights(ObjLoader.Model map) {
        List<PLight> out = new ArrayList<>();
        for (Map.Entry<String, float[]> e : map.objectBounds.entrySet()) {
            String name = e.getKey();
            float[] b = e.getValue();
            if (b == null || b[0] == Float.MAX_VALUE) {
                continue;
            }
            float cx = (b[0] + b[3]) * 0.5f;
            float cy = (b[1] + b[4]) * 0.5f;
            float cz = (b[2] + b[5]) * 0.5f;
            PLight l = lightForObject(name, cx, cy, cz);
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    public static final class Spot {
        public final String id;
        public final float x, z;
        public final float radius;
        public Spot(String id, float x, float z, float radius) {
            this.id = id; this.x = x; this.z = z; this.radius = radius;
        }
    }

    public static List<Spot> spots() {
        List<Spot> s = new ArrayList<>();
        s.add(new Spot("checkin", -8f, 48f, 20f));
        s.add(new Spot("hub_nurse_w", -48f, 1f, 16f));
        s.add(new Spot("hub_nurse_e", 30f, 1f, 16f));
        s.add(new Spot("discharge", -8f, 22f, 16f));
        s.add(new Spot("supply", 1f, -20f, 16f));

        s.add(new Spot("or_request_1", -172f, -50f, 18f));
        s.add(new Spot("or_request_2", -8f, -50f, 18f));
        s.add(new Spot("or_request_3", 155f, -50f, 18f));

        s.add(new Spot("surgery_1", -148.4f, -99.3f, 16f));
        s.add(new Spot("surgery_2", 15.8f, -99.3f, 16f));
        s.add(new Spot("surgery_3", 179.2f, -99.3f, 16f));
        s.add(new Spot("treat_1", -124f, -101f, 20f));
        s.add(new Spot("treat_2", 106f, -101f, 20f));
        s.add(new Spot("treat_3", -9f, -101f, 20f));
        s.add(new Spot("treat_4", 207f, -14f, 20f));
        s.add(new Spot("treat_5", -224f, -14f, 20f));
        s.add(new Spot("treat_6", 1.5f, 25.0f, 20f));
        return s;
    }
}
