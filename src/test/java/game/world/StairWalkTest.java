package game.world;

import game.assets.ObjLoader;
import game.player.CollisionWorld;
import game.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class StairWalkTest {
    static CollisionWorld world;

    @BeforeAll
    static void bake() throws Exception {
        Path obj = Path.of("budget_ward_map.obj");
        ObjLoader.Model map = ObjLoader.load(obj, MapFixes.withNoCollide(Doors.namedObjects()), Doors.groups(), Set.of("X1"));
        MapFixes.apply(map);
        world = new CollisionWorld();
        for (ObjLoader.MeshPart p : map.parts) {
            if (!MapFixes.NO_COLLIDE.contains(p.objectName)) world.addPart(p);
        }
    }

    private float[] walkStairs(float sx, float sz, float[][] wps) {
        Player pl = new Player(world);
        pl.x = sx; pl.z = sz; pl.y = 2f;
        float g = world.groundHeight(pl.x, pl.y + 1f, pl.z, pl.radius());
        if (g != -Float.MAX_VALUE) pl.y = g;
        int wi = 0;
        int stuckFrames = 0;
        float lx = pl.x, lz = pl.z;
        for (int f = 0; f < 3600 && wi < wps.length; f++) {
            float tx = wps[wi][0], tz = wps[wi][1];
            float dx = tx - pl.x, dz = tz - pl.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < 4f) { wi++; continue; }
            pl.setYaw((float) Math.atan2(dx, dz));
            pl.update(1f / 60f, true, false, false, false, false, false);
            if (f % 60 == 0) {
                float moved = (float) Math.sqrt(
                        (pl.x - lx) * (pl.x - lx) + (pl.z - lz) * (pl.z - lz));
                lx = pl.x; lz = pl.z;
                stuckFrames = moved < 2f ? stuckFrames + 1 : 0;
                if (stuckFrames >= 5) break;
            }
        }
        return new float[]{pl.x, pl.y, pl.z, wi};
    }

    @Test
    void westStairs_reachTop() {
        float[] end = walkStairs(-87.1f, -95f, new float[][]{
            {-87.1f, -112f}, {-87.1f, -123.5f}, {-87.1f, -132f},
            {-73.2f, -132f}, {-73.2f, -112f}, {-73.2f, -96f},
        });
        assertEquals(6, (int) end[3], "west climb must visit every waypoint");
        assertTrue(end[1] > 30f, "west climb must reach the OR floor, y=" + end[1]);
    }

    @Test
    void eastStairs_reachTop() {
        float[] end = walkStairs(69.1f, -95f, new float[][]{
            {69.1f, -112f}, {69.1f, -123.5f}, {69.1f, -132f},
            {55.2f, -132f}, {55.2f, -112f}, {55.2f, -96f},
        });
        assertEquals(6, (int) end[3], "east climb must visit every waypoint");
        assertTrue(end[1] > 30f, "east climb must reach the OR floor, y=" + end[1]);
    }
}
