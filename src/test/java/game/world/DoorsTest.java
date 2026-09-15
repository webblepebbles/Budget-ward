package game.world;

import game.assets.ObjLoader;
import game.player.CollisionWorld;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DoorsTest {

    private static final Path MAP = Path.of("budget_ward_map.obj");
    private static final boolean MAP_PRESENT = Files.isRegularFile(MAP);

    @TempDir
    Path dir;

    private ObjLoader.Model syntheticLeafModel() throws Exception {
        Files.writeString(dir.resolve("s.mtl"), "newmtl wood\nKd 0.4 0.2 0.1\n");

        Files.writeString(dir.resolve("s.obj"), """
                mtllib s.mtl
                o SL_Leaf
                usemtl wood
                v 0 0 0
                v 12 0 0
                v 12 20 0
                v 0 20 0
                f 1 2 3
                f 1 3 4
                """);
        return ObjLoader.load(dir.resolve("s.obj"), Set.of("SL_Leaf"), Map.of());
    }

    @Test
    void closedPose_mapsExportDirToClosedDir() throws Exception {
        CollisionWorld w = new CollisionWorld();
        Doors doors = new Doors(w);
        ObjLoader.Model m = syntheticLeafModel();
        ObjLoader.MeshPart part = m.namedParts.get("SL_Leaf");

        Doors.Leaf leaf = Doors.swingForTest(doors, part, "SL",
                0f, 0f,
                0f, 12f,
                1f, 0f);
        doors.leavesForTest().add(leaf);

        float[] seg = leaf.barrierSegment(0f);

        assertEquals(12f, seg[2] - seg[0], 0.05f, "closed barrier spans +X");
        assertEquals(0f, seg[3] - seg[1], 0.05f, "closed barrier has no z span");

        float[] segOpen = leaf.barrierSegment(1f);
        float dx = segOpen[2] - segOpen[0];
        float dz = segOpen[3] - segOpen[1];
        assertTrue(Math.abs(dz) > Math.abs(dx) * 0.9f,
                "open pose must equal the export pose (+Z) (dx=" + dx + " dz=" + dz + ")");
    }

    @Test
    void autoOpen_openThenCloseWithHysteresis() {
        CollisionWorld w = new CollisionWorld();
        Doors doors = new Doors(w);

        Doors.Door d = Doors.doorForTest(doors, "T", new Doors.Leaf[1], -5, 0, 5, 0);
        Doors.Leaf fake = new Doors.Leaf(
                testPart(), "T", true, 1, 0, 10f,
                0, 0, 0, 0, 0, 0, 0, 0, 0, w);
        d.leaves[0] = fake;
        doors.leavesForTest().add(fake);
        doors.doorsForTest().add(d);

        doors.update(100f, 100f, 0.016f);
        assertFalse(d.open);
        assertEquals(0f, fake.open, 1e-3f);

        doors.update(0f, 0f, 0.016f);
        assertTrue(d.open);
        for (int i = 0; i < 200; i++) {
            doors.update(0f, 0f, 0.016f);
        }
        assertEquals(1f, fake.open, 1e-3f, "leaf animates fully open");

        doors.update(100f, 100f, 0.016f);
        assertTrue(d.open, "hold time keeps door open just after leaving");
        for (int i = 0; i < 400; i++) {
            doors.update(100f, 100f, 0.016f);
        }
        assertFalse(d.open, "door closes after the player leaves");
        assertEquals(0f, fake.open, 1e-3f);
    }

    private static ObjLoader.MeshPart testPart() {
        return new ObjLoader.MeshPart("m", "T",
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0},
                new float[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
                1f, false, false,
                new float[]{0, 0, 0}, new float[]{1, 1, 0});
    }

    @Test
    void mainEntrance_slidesClearOfDoorway() throws Exception {
        Assumptions.assumeTrue(MAP_PRESENT, "map OBJ not present");
        long t0 = System.currentTimeMillis();
        ObjLoader.Model m = ObjLoader.load(MAP, Doors.namedObjects(), Doors.groups());
        CollisionWorld w = new CollisionWorld();
        Doors doors = Doors.build(w, m);
        Doors.Door main = doors.all().stream()
                .filter(d -> d.name.equals("DMain")).findFirst().orElse(null);
        assertNotNull(main, "DMain must be built from the map");
        assertEquals(2, main.leaves.length);

        float[] l0 = main.leaves[0].barrierSegment(0f);
        float[] r0 = main.leaves[1].barrierSegment(0f);
        float span = Math.max(l0[2], r0[2]) - Math.min(l0[0], r0[0]);
        assertEquals(23f, span, 1.5f, "leaves together span the opening");

        float[] l1 = main.leaves[0].barrierSegment(1f);
        float[] r1 = main.leaves[1].barrierSegment(1f);
        assertTrue(l1[2] < main.sx + 1.0f,
                "left leaf parks left of the opening (right edge " + l1[2] + ")");
        assertTrue(r1[0] > main.ex - 1.0f,
                "right leaf parks right of the opening (left edge " + r1[0] + ")");

        System.out.printf("DoorsTest: %d doors in %d ms%n",
                doors.doorCount(), System.currentTimeMillis() - t0);
    }

    @Test
    void swingDoors_bakeToClosedAlongDoorway() throws Exception {
        Assumptions.assumeTrue(MAP_PRESENT, "map OBJ not present");
        ObjLoader.Model m = ObjLoader.load(MAP, Doors.namedObjects(), Doors.groups());
        Doors doors = Doors.build(new CollisionWorld(), m);
        for (Doors.Door d : doors.all()) {
            if (d.name.equals("DMain")) {
                continue;
            }
            for (Doors.Leaf l : d.leaves) {
                float[] seg = l.barrierSegment(0f);
                float dx = seg[2] - seg[0], dz = seg[3] - seg[1];

                boolean alongX = Math.abs(dx) > Math.abs(dz) * 5f;
                boolean alongZ = Math.abs(dz) > Math.abs(dx) * 5f;
                assertTrue(alongX || alongZ,
                        d.name + " closed leaf not axis-aligned with doorway: dx=" + dx + " dz=" + dz);
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                assertEquals(13.4f, len, 2.0f, d.name + " leaf length matches its 13.4-unit doorway");
            }
        }
    }
}
