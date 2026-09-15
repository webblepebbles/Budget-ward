package game.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlassAlphaTest {

    @TempDir
    Path dir;

    @Test
    void glassOpacity_becomesVertexAlpha() throws Exception {
        Files.writeString(dir.resolve("t.mtl"), """
                newmtl BW_glass
                Kd 0.60 0.78 0.80
                d 0.35
                newmtl BW_wall
                Kd 0.9 0.9 0.9
                """);
        Files.writeString(dir.resolve("t.obj"), """
                mtllib t.mtl
                o Wall
                usemtl BW_wall
                v 0 0 0
                v 1 0 0
                v 1 1 0
                f 1 2 3
                o Glass
                usemtl BW_glass
                v 2 0 0
                v 3 0 0
                v 3 1 0
                f 4 5 6
                """);

        ObjLoader.Model m = ObjLoader.load(dir.resolve("t.obj"));
        boolean sawOpaque = false, sawGlass = false;
        for (ObjLoader.MeshPart p : m.parts) {
            float a = p.colors[3];
            if (p.alpha < 0.999f) {
                sawGlass = true;
                assertEquals(0.35f, a, 1e-4f, "glass alpha must come from MTL d");
                assertTrue(p.isTransparent(), "glass part flagged transparent");
                assertTrue(p.vertexCount() == 3);
            } else {
                sawOpaque = true;
                assertEquals(1f, a, 1e-4f);
                assertFalse(p.isTransparent());
            }
        }
        assertTrue(sawOpaque && sawGlass, "both parts must be present");
    }

    @Test
    void doorGroup_extractsPartsAndChainsTransparent() throws Exception {

        Files.writeString(dir.resolve("d.mtl"), """
                newmtl wood
                Kd 0.4 0.2 0.1
                newmtl BW_glass
                Kd 0.6 0.8 0.8
                d 0.35
                """);
        Files.writeString(dir.resolve("d.obj"), """
                mtllib d.mtl
                o DT2_Leaf
                usemtl wood
                v 0 0 0
                v 10 0 0
                v 10 20 0
                f 1 2 3
                o DT2_Port_Glass
                usemtl BW_glass
                v 5 10 0
                v 6 10 0
                v 6 11 0
                f 4 5 6
                """);

        ObjLoader.Model m = ObjLoader.load(dir.resolve("d.obj"),
                Set.of(), Map.of("DT2", new String[]{"DT2_Leaf", "DT2_Port_Glass"}));

        assertTrue(m.parts.isEmpty(), "grouped parts must not be in the merged list");
        ObjLoader.MeshPart head = m.namedParts.get("DT2");
        assertNotNull(head, "group head must be present");
        assertEquals(3, head.vertexCount());
        assertFalse(head.isTransparent(), "leaf body is opaque");
        assertNotNull(head.next, "transparent sub-part must chain");
        assertTrue(head.next.isTransparent(), "porthole is transparent");
        assertEquals(3, head.next.vertexCount());
    }

    @Test
    void unknownMaterial_isLoudMagenta() throws Exception {
        Files.writeString(dir.resolve("u.obj"), """
                o X
                usemtl nope
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                """);
        ObjLoader.Model m = ObjLoader.load(dir.resolve("u.obj"));
        assertEquals(1, m.parts.size());
        assertEquals(0.8f, m.parts.get(0).colors[0], 1e-4f);
        assertEquals(0.15f, m.parts.get(0).colors[1], 1e-4f);
        assertEquals(0.7f, m.parts.get(0).colors[2], 1e-4f);
    }
}
