package game.world;

import game.assets.ObjLoader;
import game.player.CollisionWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NavGridTest {

    private static CollisionWorld world;
    private static NavGrid nav;

    @BeforeAll
    static void bakeRealMap() throws Exception {
        Path obj = Path.of("budget_ward_map.obj");
        assertTrue(java.nio.file.Files.isRegularFile(obj),
                "run Gradle tests from the project directory");
        Set<String> named = Doors.namedObjects();
        Map<String, String[]> groups = Doors.groups();
        ObjLoader.Model map = ObjLoader.load(obj, MapFixes.withNoCollide(named), groups, Set.of("X1"));
        MapFixes.apply(map);

        world = new CollisionWorld();
        for (ObjLoader.MeshPart p : map.parts) {
            if (!MapFixes.NO_COLLIDE.contains(p.objectName)) world.addPart(p);
        }
        nav = NavGrid.build(world,
                map.boundsMin[0], map.boundsMin[2],
                map.boundsMax[0], map.boundsMax[2]);
        System.out.println("NavGridTest: " + nav.walkableCells() + " walkable cells");
        assertTrue(nav.walkableCells() > 1000, "hospital must bake a usable nav grid");
    }

    @Test
    void lobbyToWard_hasRoute() {
        float[] route = nav.findPath(world, -8.4f, 0.4f, 120f, -137f, 0.4f, -119f);
        assertNotNull(route, "lobby -> ward bed 1 must be routable (no beeline through walls)");
        assertTrue(route.length >= 6, "route must contain waypoints");
        assertTrue(route.length / 3 < 200, "route must be sane length");
    }

    @Test
    void debugStairTop() {
        float[] out = new float[2];

        for (float z = -104f; z <= -88f; z += 2f) {
            StringBuilder row = new StringBuilder();
            for (float x = -82f; x <= -58f; x += 2f) {
                world.resolveHorizontal(x, 34.8f, z, 2.6f, 1.6f * 9.6f, 0.36f * 9.6f, out);
                float dx = out[0] - x, dz = out[1] - z;
                row.append(dx * dx + dz * dz < 1f ? '.' : '#');
            }
            System.out.println("z=" + z + " [" + row + "]");
        }
    }

    @Test
    void upstairs_orWingToStairTop_hasRoute() {
        float[] route = nav.findPath(world,
                -148.4f, 34.8f, -93f, -73.2f, 34.8f, -96f);
        assertNotNull(route, "OR wing -> west stair top must be routable");
        System.out.println("upstairs route pts=" + route.length / 3);
    }

    @Test
    void simulatedWalk_reachesBedWithoutCrossingWalls() {
        float[] route = nav.findPath(world, -8.4f, 0.4f, 120f, -137f, 0.4f, -119f);
        assertNotNull(route);

        float x = -8.4f, y = 0.4f, z = 120f;
        float[] out = new float[2];
        int wi = 0;
        int steps = 0;
        int repaths = 0;
        float maxPush = 0f;
        float bestDist = Float.MAX_VALUE;
        int lastImprove = 0;
        while (wi * 3 < route.length && steps < 40000 && repaths <= 8) {
            float wx = route[wi * 3], wz = route[wi * 3 + 1], wy = route[wi * 3 + 2];
            float dx = wx - x, dz = wz - z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < 3f) {
                wi++;
                continue;
            }
            float step = 2.6f * 9.6f / 60f;
            x += dx / dist * step;
            z += dz / dist * step;

            float dy = wy - y;
            float maxDy = step * 0.9f;
            y += Math.max(-maxDy, Math.min(maxDy, dy));
            world.resolveHorizontal(x, y, z, 2.6f, 14f, 3.5f, out, true);
            float push = (float) Math.sqrt(
                    (out[0] - x) * (out[0] - x) + (out[1] - z) * (out[1] - z));
            maxPush = Math.max(maxPush, push);
            x = out[0];
            z = out[1];
            steps++;
            float tdxNow = x - -137f, tdzNow = z - -119f;
            float distNow = (float) Math.sqrt(tdxNow * tdxNow + tdzNow * tdzNow);
            if (distNow < bestDist - 0.5f) {
                bestDist = distNow;
                lastImprove = steps;
            }
            if (steps - lastImprove > 400) {

                route = nav.findPath(world, x, y, z, -137f, 0.4f, -119f);
                assertNotNull(route, "repath from stall must exist");
                wi = 0;
                repaths++;
                lastImprove = steps;
            }
        }
        float tdx = x - -137f, tdz = z - -119f;
        float arrived = (float) Math.sqrt(tdx * tdx + tdz * tdz);
        System.out.println("NavGridTest: arrived within " + arrived
                + "u in " + steps + " steps, " + repaths + " repaths, max wall push " + maxPush);
        assertTrue(arrived < 15f, "walker must reach the bed area, got " + arrived);
        assertTrue(repaths <= 2, "route must not wedge the walker, repaths " + repaths);
    }
}
