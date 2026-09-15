package game.assets;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ObjLoader {

    public static final class MeshPart {
        public final String material;
        public final String objectName;
        public final float[] positions;
        public final float[] colors;
        public final float alpha;
        public final boolean emissive;
        public final boolean transparent;
        public final float[] boundsMin;
        public final float[] boundsMax;

        public MeshPart next;

        public MeshPart(String material, String objectName, float[] positions, float[] colors,
                        float alpha, boolean emissive, boolean transparent,
                        float[] boundsMin, float[] boundsMax) {
            this.material = material;
            this.objectName = objectName;
            this.positions = positions;
            this.colors = colors;
            this.alpha = alpha;
            this.emissive = emissive;
            this.transparent = transparent;
            this.boundsMin = boundsMin;
            this.boundsMax = boundsMax;
        }

        public int vertexCount() {
            return positions.length / 3;
        }

        public boolean isTransparent() {
            return transparent;
        }
    }

    public static final class Model {
        public final List<MeshPart> parts;

        public final Map<String, MeshPart> namedParts;

        public final Map<String, float[]> objectBounds;
        public final float[] boundsMin;
        public final float[] boundsMax;

        Model(List<MeshPart> parts, Map<String, MeshPart> namedParts,
              Map<String, float[]> objectBounds,
              float[] boundsMin, float[] boundsMax) {
            this.parts = parts;
            this.namedParts = namedParts;
            this.objectBounds = objectBounds;
            this.boundsMin = boundsMin;
            this.boundsMax = boundsMax;
        }

        public void rebuildGlobalBounds() {
            float gminx = Float.MAX_VALUE, gminy = Float.MAX_VALUE, gminz = Float.MAX_VALUE;
            float gmaxx = -Float.MAX_VALUE, gmaxy = -Float.MAX_VALUE, gmaxz = -Float.MAX_VALUE;
            boolean any = false;
            for (MeshPart p : parts) {
                gminx = Math.min(gminx, p.boundsMin[0]);
                gminy = Math.min(gminy, p.boundsMin[1]);
                gminz = Math.min(gminz, p.boundsMin[2]);
                gmaxx = Math.max(gmaxx, p.boundsMax[0]);
                gmaxy = Math.max(gmaxy, p.boundsMax[1]);
                gmaxz = Math.max(gmaxz, p.boundsMax[2]);
                any = true;
            }
            if (any) {
                boundsMin[0] = gminx; boundsMin[1] = gminy; boundsMin[2] = gminz;
                boundsMax[0] = gmaxx; boundsMax[1] = gmaxy; boundsMax[2] = gmaxz;
            }
        }
    }

    private static final class Material {
        float r = 0.8f, g = 0.8f, b = 0.8f;
        float er = 0f, eg = 0f, eb = 0f;
        float opacity = 1f;

        boolean emissive() {
            return er > 0.01f || eg > 0.01f || eb > 0.01f;
        }
    }

    private ObjLoader() {}

    public static Model load(Path objFile) throws IOException {
        return load(objFile, Set.of(), Map.of());
    }

    public static Model load(Path objFile, Set<String> namedObjects,
                             Map<String, String[]> groups) throws IOException {
        return load(objFile, namedObjects, groups, Set.of());
    }

    public static Model load(Path objFile, Set<String> namedObjects,
                             Map<String, String[]> groups, Set<String> ignoreObjects) throws IOException {
        Map<String, Material> materials = new HashMap<>();
        String mtlName = sniffMtllib(objFile);
        if (mtlName != null) {
            Path mtl = objFile.resolveSibling(mtlName);
            if (Files.isRegularFile(mtl)) {
                parseMtl(mtl, materials);
            }
        }

        Map<String, String> memberToGroup = new HashMap<>();
        Set<String> named = new java.util.HashSet<>(namedObjects);
        for (Map.Entry<String, String[]> e : groups.entrySet()) {
            for (String member : e.getValue()) {
                memberToGroup.put(member, e.getKey());
                named.add(member);
            }
        }

        ObjParser parser = new ObjParser(materials, named, memberToGroup,
                new java.util.HashSet<>(ignoreObjects));
        try (InputStream in = new BufferedInputStream(Files.newInputStream(objFile), 1 << 20)) {
            parser.parse(in);
        }
        return parser.build();
    }

    private static String sniffMtllib(Path objFile) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(objFile), 1 << 16)) {

            byte[] buf = in.readNBytes(1 << 16);
            String head = new String(buf, StandardCharsets.US_ASCII);
            for (String line : head.split("\n")) {
                line = line.trim();
                if (line.startsWith("mtllib ")) {
                    String name = line.substring(7).trim();
                    if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
                        name = name.substring(1, name.length() - 1);
                    }
                    return name;
                }
            }
        }
        return null;
    }

    private static void parseMtl(Path file, Map<String, Material> out) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Material cur = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tok = line.split("\\s+");
            switch (tok[0]) {
                case "newmtl" -> {
                    cur = new Material();
                    out.put(line.substring(7).trim(), cur);
                }
                case "Kd" -> {
                    if (cur != null && tok.length >= 4) {
                        cur.r = Float.parseFloat(tok[1]);
                        cur.g = Float.parseFloat(tok[2]);
                        cur.b = Float.parseFloat(tok[3]);
                    }
                }
                case "Ke" -> {
                    if (cur != null && tok.length >= 4) {
                        cur.er = Float.parseFloat(tok[1]);
                        cur.eg = Float.parseFloat(tok[2]);
                        cur.eb = Float.parseFloat(tok[3]);
                    }
                }
                case "d", "Tr" -> {
                    if (cur != null && tok.length >= 2) {
                        float v = Float.parseFloat(tok[1]);
                        cur.opacity = tok[0].equals("d") ? v : 1f - v;
                    }
                }
                default -> { }
            }
        }
    }

    private static final class ObjParser {
        private final Map<String, Material> materials;
        private final Set<String> namedObjects;
        private final Set<String> ignoreObjects;
        private final Map<String, String> memberToGroup;

        private float[] vx = new float[1 << 16];
        private float[] vy = new float[1 << 16];
        private float[] vz = new float[1 << 16];
        private float[] nx = new float[1 << 12];
        private float[] ny = new float[1 << 12];
        private float[] nz = new float[1 << 12];
        private int vCount = 0;
        private int nCount = 0;

        private static final class Sink {
            final String material;
            final String objectName;
            final float alpha;
            boolean transparent;
            boolean emissive;
            float[] pos = new float[1 << 14];
            float[] col = new float[1 << 14];
            int floats = 0;
            float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, minz = Float.MAX_VALUE;
            float maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE, maxz = -Float.MAX_VALUE;

            Sink(String material, String objectName, float alpha) {
                this.material = material;
                this.objectName = objectName;
                this.alpha = alpha;
            }

            void addTri(float ax, float ay, float az, float bx, float by, float bz,
                        float cx, float cy, float cz, float r, float g, float b, float a) {
                if (floats + 9 > pos.length || ((floats / 3) + 3) * 4 > col.length) {
                    pos = grow(pos);
                    col = grow(col);
                }
                put(ax, ay, az, r, g, b, a);
                put(bx, by, bz, r, g, b, a);
                put(cx, cy, cz, r, g, b, a);
                if (ax < minx) minx = ax; if (ax > maxx) maxx = ax;
                if (ay < miny) miny = ay; if (ay > maxy) maxy = ay;
                if (az < minz) minz = az; if (az > maxz) maxz = az;
                if (bx < minx) minx = bx; if (bx > maxx) maxx = bx;
                if (by < miny) miny = by; if (by > maxy) maxy = by;
                if (bz < minz) minz = bz; if (bz > maxz) maxz = bz;
                if (cx < minx) minx = cx; if (cx > maxx) maxx = cx;
                if (cy < miny) miny = cy; if (cy > maxy) maxy = cy;
                if (cz < minz) minz = cz; if (cz > maxz) maxz = cz;
            }

            private void put(float x, float y, float z, float r, float g, float b, float a) {
                int vi = floats / 3;
                pos[floats] = x; pos[floats + 1] = y; pos[floats + 2] = z;
                floats += 3;
                int ci = vi * 4;
                col[ci] = r; col[ci + 1] = g; col[ci + 2] = b; col[ci + 3] = a;
            }

            private static float[] grow(float[] src) {
                float[] dst = new float[src.length * 2];
                System.arraycopy(src, 0, dst, 0, src.length);
                return dst;
            }

            boolean hasData() { return floats > 0; }
        }

        private final Map<String, Sink> sinks = new HashMap<>();

        private final Map<String, float[]> objBounds = new HashMap<>();
        private final int[] faceV = new int[64];
        private final int[] faceN = new int[64];
        private float cr = 0.8f, cg = 0.8f, cb = 0.8f;
        private float cAlpha = 1f;
        private String currentMaterial = "default";
        private String currentObject = "";
        private boolean ignored;

        ObjParser(Map<String, Material> materials, Set<String> namedObjects,
                  Map<String, String> memberToGroup, Set<String> ignoreObjects) {
            this.materials = materials;
            this.namedObjects = namedObjects;
            this.memberToGroup = memberToGroup;
            this.ignoreObjects = ignoreObjects;
        }

        void parse(InputStream in) throws IOException {
            byte[] buf = new byte[1 << 16];
            var bout = new java.io.ByteArrayOutputStream(1 << 16);
            int read;
            while ((read = in.read(buf)) > 0) {
                bout.write(buf, 0, read);

                byte[] all = bout.toByteArray();
                int start = 0;
                for (int i = 0; i < all.length; i++) {
                    if (all[i] == '\n') {
                        handleLine(all, start, i);
                        start = i + 1;
                    }
                }
                bout.reset();
                bout.write(all, start, all.length - start);
            }
            byte[] all = bout.toByteArray();
            if (all.length > 0) {
                handleLine(all, 0, all.length);
            }
        }

        private void handleLine(byte[] buf, int from, int to) {

            while (to > from && (buf[to - 1] == '\r' || buf[to - 1] == ' ' || buf[to - 1] == '\t')) {
                to--;
            }
            if (to <= from) {
                return;
            }
            if (buf[from] == '#') {
                return;
            }
            String line = new String(buf, from, to - from, StandardCharsets.US_ASCII);
            if (line.startsWith("v ")) {
                addVertex(line);
            } else if (line.startsWith("vn ")) {
                addNormal(line);
            } else if (line.startsWith("f ")) {
                addFace(line);
            } else if (line.startsWith("usemtl ")) {
                setMaterial(line.substring(7).trim());
            } else if (line.startsWith("o ")) {
                currentObject = line.substring(2).trim();
                ignored = ignoreObjects.contains(currentObject);
            }

        }

        private void ensureVerts(int needed) {
            if (needed <= vx.length) {
                return;
            }
            int cap = vx.length;
            while (cap < needed) {
                cap <<= 1;
            }
            vx = grow(vx, cap); vy = grow(vy, cap); vz = grow(vz, cap);
        }

        private void ensureNorms(int needed) {
            if (needed <= nx.length) {
                return;
            }
            int cap = nx.length;
            while (cap < needed) {
                cap <<= 1;
            }
            nx = grow(nx, cap); ny = grow(ny, cap); nz = grow(nz, cap);
        }

        private static float[] grow(float[] src, int cap) {
            float[] dst = new float[cap];
            System.arraycopy(src, 0, dst, 0, src.length);
            return dst;
        }

        private void addVertex(String line) {
            int i = 2;
            float x = nextFloat(line, i);
            float y = nextFloat(line, i = nextTok(line, i));
            float z = nextFloat(line, i = nextTok(line, i));
            ensureVerts(vCount + 1);
            vx[vCount] = x; vy[vCount] = y; vz[vCount] = z;
            vCount++;
        }

        private void addNormal(String line) {
            int i = 3;
            float x = nextFloat(line, i);
            float y = nextFloat(line, i = nextTok(line, i));
            float z = nextFloat(line, i = nextTok(line, i));
            ensureNorms(nCount + 1);
            nx[nCount] = x; ny[nCount] = y; nz[nCount] = z;
            nCount++;
        }

        private static int nextTok(String s, int i) {
            while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t') {
                i++;
            }
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
                i++;
            }
            return i >= s.length() ? -1 : i;
        }

        private static float nextFloat(String s, int start) {
            if (start < 0) {
                return 0f;
            }
            int end = start;
            while (end < s.length() && s.charAt(end) != ' ' && s.charAt(end) != '\t'
                    && s.charAt(end) != '/') {
                end++;
            }
            return Float.parseFloat(s.substring(start, end));
        }

        private static float srgbToLinear(float c) {
            if (c <= 0f) return 0f;
            if (c >= 1f) return 1f;

            return (float) Math.pow(c, 2.2);
        }

        private void setMaterial(String name) {
            currentMaterial = name;
            Material m = materials.get(name);
            if (m != null) {

                cr = srgbToLinear(m.r); cg = srgbToLinear(m.g); cb = srgbToLinear(m.b);
                cAlpha = m.opacity;
            } else {

                cr = 0.8f; cg = 0.15f; cb = 0.7f;
                cAlpha = 1f;
            }
        }

        private void addFace(String line) {
            int count = 0;
            int i = 2;
            while (i >= 0 && i < line.length() && count < faceV.length) {
                int vIdx = (int) nextFloat(line, i) - 1;
                int slash = line.indexOf('/', i);
                int end = nextTok(line, i);
                if (end < 0) {
                    end = line.length();
                }
                int nIdx = -1;
                if (slash >= 0 && slash < end) {
                    int second = line.indexOf('/', slash + 1);
                    if (second >= 0 && second < end) {
                        int ns = second + 1;
                        if (ns < end && line.charAt(ns) != ' ') {
                            nIdx = (int) nextFloat(line, ns) - 1;
                        }
                    }
                }
                if (vIdx >= 0) {
                    faceV[count] = vIdx;
                    faceN[count] = nIdx;
                    count++;
                }
                i = nextTok(line, i);
                if (i < 0) {
                    break;
                }
            }
            if (count < 3 || ignored) {
                return;
            }
            noteObjectBounds(count);
            Sink sink = sinkFor(currentMaterial, currentObject, cr, cg, cb, cAlpha,
                    matEmissive(currentMaterial));
            int tris = count - 2;
            if (tris == 1) {
                emitTri(sink, faceV[0], faceV[1], faceV[2]);
            } else if (tris == 2) {
                emitTri(sink, faceV[0], faceV[1], faceV[2]);
                emitTri(sink, faceV[0], faceV[2], faceV[3]);
            } else {
                for (int t = 1; t <= tris; t++) {
                    emitTri(sink, faceV[0], faceV[t], faceV[t + 1]);
                }
            }
        }

        private void noteObjectBounds(int count) {
            if (currentObject == null || currentObject.isEmpty()) {
                return;
            }
            float[] b = objBounds.get(currentObject);
            if (b == null) {
                b = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                        -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                objBounds.put(currentObject, b);
            }
            for (int i = 0; i < count; i++) {
                int v = faceV[i];
                if (v < 0 || v >= vCount) {
                    continue;
                }
                b[0] = Math.min(b[0], vx[v]); b[3] = Math.max(b[3], vx[v]);
                b[1] = Math.min(b[1], vy[v]); b[4] = Math.max(b[4], vy[v]);
                b[2] = Math.min(b[2], vz[v]); b[5] = Math.max(b[5], vz[v]);
            }
        }

        private void emitTri(Sink sink, int a, int b, int c) {

            sink.addTri(vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c],
                    cr, cg, cb, cAlpha);
        }

        private boolean matEmissive(String material) {
            Material m = materials.get(material);
            return m != null && m.emissive();
        }

        private Sink sinkFor(String material, String objectName, float r, float g, float b,
                             float alpha, boolean emissive) {

            String groupName = memberToGroup.get(objectName);
            boolean named = groupName != null || namedObjects.contains(objectName);
            String sinkName = groupName != null ? groupName : (named ? objectName : "");
            if (named) {
                boolean trans = alpha < 0.999f;
                String key = "obj:" + sinkName + (trans ? "|T" : "|O");
                return sinks.computeIfAbsent(key, k -> {
                    Sink s = new Sink(material, sinkName, alpha);
                    s.transparent = trans;
                    return s;
                });
            }
            String key = material + "|" + (int) (r * 255) + "," + (int) (g * 255) + "," + (int) (b * 255)
                    + "|" + Float.floatToIntBits(alpha) + (emissive ? "|E" : "");
            return sinks.computeIfAbsent(key, k -> {
                Sink s = new Sink(material, "", alpha);
                s.emissive = emissive;
                s.transparent = alpha < 0.999f;
                return s;
            });
        }

        Model build() {
            List<MeshPart> parts = new ArrayList<>(sinks.size());
            Map<String, MeshPart> namedParts = new HashMap<>();
            float gminx = Float.MAX_VALUE, gminy = Float.MAX_VALUE, gminz = Float.MAX_VALUE;
            float gmaxx = -Float.MAX_VALUE, gmaxy = -Float.MAX_VALUE, gmaxz = -Float.MAX_VALUE;
            boolean any = false;
            for (Sink s : sinks.values()) {
                if (!s.hasData()) {
                    continue;
                }
                int verts = s.floats / 3;
                float[] pos = new float[s.floats];
                System.arraycopy(s.pos, 0, pos, 0, s.floats);
                float[] col = new float[verts * 4];
                System.arraycopy(s.col, 0, col, 0, verts * 4);
                MeshPart part = new MeshPart(s.material, s.objectName, pos, col, s.alpha,
                        s.emissive, s.transparent,
                        new float[]{s.minx, s.miny, s.minz},
                        new float[]{s.maxx, s.maxy, s.maxz});
                if (s.objectName.isEmpty()) {
                    parts.add(part);
                } else {
                    MeshPart head = namedParts.get(s.objectName);
                    if (head == null) {
                        namedParts.put(s.objectName, part);
                    } else {
                        MeshPart tail = head;
                        while (tail.next != null) {
                            tail = tail.next;
                        }
                        tail.next = part;
                    }
                }
                if (s.minx < gminx) gminx = s.minx;
                if (s.miny < gminy) gminy = s.miny;
                if (s.minz < gminz) gminz = s.minz;
                if (s.maxx > gmaxx) gmaxx = s.maxx;
                if (s.maxy > gmaxy) gmaxy = s.maxy;
                if (s.maxz > gmaxz) gmaxz = s.maxz;
                any = true;
            }
            if (!any) {
                return new Model(parts, namedParts, new HashMap<>(objBounds), new float[3], new float[3]);
            }
            return new Model(parts, namedParts, new HashMap<>(objBounds),
                    new float[]{gminx, gminy, gminz},
                    new float[]{gmaxx, gmaxy, gmaxz});
        }
    }
}
