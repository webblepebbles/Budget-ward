package game.world;

import game.assets.ObjLoader;
import game.player.CollisionWorld;
import org.joml.Math;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Doors {

    public static final class Leaf {

        public final ObjLoader.MeshPart part;
        public final String doorName;
        public final boolean sliding;

        public final float slideDX, slideDZ, slideDist;

        public final float hx, hz;
        public final float bakeX, bakeZ;
        public final float len;
        public final float exportAngX, exportAngZ;
        public final float thetaClosed, thetaOpen;
        public final float y0, y1;

        public float open;
        public float target;
        public final int barrier;

        Leaf(ObjLoader.MeshPart part, String doorName, boolean sliding,
             float sdx, float sdz, float sdist,
             float hx, float hz, float bakeX, float bakeZ, float len,
             float eAx, float eAz, float thetaClosed, float thetaOpen,
             CollisionWorld world) {
            this.part = part;
            this.doorName = doorName;
            this.sliding = sliding;
            this.slideDX = sdx; this.slideDZ = sdz; this.slideDist = sdist;
            this.hx = hx; this.hz = hz;
            this.bakeX = bakeX; this.bakeZ = bakeZ;
            this.len = len;
            this.exportAngX = eAx; this.exportAngZ = eAz;
            this.thetaClosed = thetaClosed; this.thetaOpen = thetaOpen;
            this.y0 = part.boundsMin[1];
            this.y1 = part.boundsMax[1];
            float[] seg = barrierSegment(0f);
            this.barrier = world.addBarrier(seg[0], seg[1], seg[2], seg[3], y0, y1);
        }

        public java.util.List<ObjLoader.MeshPart> parts() {
            java.util.List<ObjLoader.MeshPart> out = new ArrayList<>();
            for (ObjLoader.MeshPart p = part; p != null; p = p.next) {
                out.add(p);
            }
            return out;
        }

        public float theta(float t) {
            return Math.lerp(thetaClosed, thetaOpen, t);
        }

        public Matrix4f model(float t, Matrix4f dest) {
            dest.identity();
            if (sliding) {
                dest.translate(slideDX * t * slideDist, 0f, slideDZ * t * slideDist);
            } else {

                dest.translate(bakeX + hx, 0f, bakeZ + hz);
                dest.rotateY(theta(t));
                dest.translate(-hx, 0f, -hz);
            }
            return dest;
        }

        public float[] barrierSegment(float t) {
            float[] out = new float[4];
            if (sliding) {
                float dx = slideDX * t * slideDist;
                float dz = slideDZ * t * slideDist;
                out[0] = part.boundsMin[0] + dx;
                out[1] = part.boundsMin[2] + dz;
                out[2] = part.boundsMax[0] + dx;
                out[3] = part.boundsMax[2] + dz;
            } else {

                float ang = theta(t);
                float c = Math.cos(ang), s = Math.sin(ang);
                float dx = exportAngX * c + exportAngZ * s;
                float dz = -exportAngX * s + exportAngZ * c;
                float px = hx + bakeX, pz = hz + bakeZ;
                out[0] = px; out[1] = pz;
                out[2] = px + dx * len; out[3] = pz + dz * len;
            }
            return out;
        }
    }

    public static final class Door {
        public final String name;
        public final Leaf[] leaves;
        public final float sx, sz, ex, ez;
        public boolean open;
        public float holdLeft;

        Door(String name, Leaf[] leaves, float sx, float sz, float ex, float ez) {
            this.name = name;
            this.leaves = leaves;
            this.sx = sx; this.sz = sz; this.ex = ex; this.ez = ez;
        }
    }

    private final CollisionWorld world;
    private final List<Door> doors = new ArrayList<>();
    private final List<Leaf> leaves = new ArrayList<>();

    public Doors(CollisionWorld world) {
        this.world = world;
    }

    private static Leaf swingFromExport(Doors doors, Map<String, ObjLoader.MeshPart> np,
                                        String partName, String doorName,
                                        float hx, float hz, float farX, float farZ,
                                        float closedDirX, float closedDirZ) {
        ObjLoader.MeshPart p = np.get(partName);
        if (p == null) {
            return null;
        }
        float dx = farX - hx, dz = farZ - hz;
        float len = (float) java.lang.Math.sqrt(dx * dx + dz * dz);
        float eAx = dx / len, eAz = dz / len;

        float thetaClosed = (float) (java.lang.Math.atan2(eAz, eAx)
                - java.lang.Math.atan2(closedDirZ, closedDirX));
        Leaf l = new Leaf(p, doorName, false, 0, 0, 0,
                hx, hz, 0, 0, len, eAx, eAz, thetaClosed, 0f, doors.world);
        doors.leaves.add(l);
        return l;
    }

    private static Leaf swingFromExportBaked(Doors doors, Map<String, ObjLoader.MeshPart> np,
                                             String partName, String doorName,
                                             float hx, float hz,
                                             float closedDirX, float closedDirZ,
                                             float bakeX, float bakeZ) {
        ObjLoader.MeshPart p = np.get(partName);
        if (p == null) {
            return null;
        }
        float fx = Math.abs(p.boundsMax[0] - hx) > Math.abs(p.boundsMin[0] - hx)
                ? p.boundsMax[0] : p.boundsMin[0];
        float fz = Math.abs(p.boundsMax[2] - hz) > Math.abs(p.boundsMin[2] - hz)
                ? p.boundsMax[2] : p.boundsMin[2];
        float dx = fx - hx, dz = fz - hz;
        float len = (float) java.lang.Math.sqrt(dx * dx + dz * dz);
        float eAx = dx / len, eAz = dz / len;
        float thetaClosed = (float) (java.lang.Math.atan2(eAz, eAx)
                - java.lang.Math.atan2(closedDirZ, closedDirX));
        Leaf l = new Leaf(p, doorName, false, 0, 0, 0,
                hx, hz, bakeX, bakeZ, len, eAx, eAz, thetaClosed, 0f, doors.world);
        doors.leaves.add(l);
        return l;
    }

    private static Leaf swingFromClosed(Doors doors, Map<String, ObjLoader.MeshPart> np,
                                        String partName, String doorName,
                                        float hx, float hz, float closedDirX, float closedDirZ,
                                        float swingDeg, float bakeX, float bakeZ) {
        ObjLoader.MeshPart p = np.get(partName);
        if (p == null) {
            return null;
        }
        float eAx = closedDirX, eAz = closedDirZ;

        float len = closedDirX != 0
                ? p.boundsMax[0] - p.boundsMin[0]
                : p.boundsMax[2] - p.boundsMin[2];
        Leaf l = new Leaf(p, doorName, false, 0, 0, 0,
                hx, hz, bakeX, bakeZ, len, eAx, eAz,
                0f, -(float) java.lang.Math.toRadians(swingDeg), doors.world);
        doors.leaves.add(l);
        return l;
    }

    public static Doors build(CollisionWorld world, ObjLoader.Model map) {
        Doors doors = new Doors(world);
        Map<String, ObjLoader.MeshPart> np = map.namedParts;

        ObjLoader.MeshPart lm = np.get("DMain_LeafL");
        ObjLoader.MeshPart rm = np.get("DMain_LeafR");
        if (lm != null && rm != null) {
            Leaf l = new Leaf(lm, "DMain", true, -1, 0, 11.6f,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, world);
            Leaf r = new Leaf(rm, "DMain", true, 1, 0, 11.6f,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, world);
            doors.leaves.add(l);
            doors.leaves.add(r);
            doors.doors.add(new Door("DMain", new Leaf[]{l, r},
                    -19.86f, 162.86f, 3.11f, 162.86f));
        }

        Leaf dt1 = swingFromExport(doors, np, "DT1", "DT1",
                -129.780f, -95.103f, -122.511f, -84.958f, 1, 0);
        if (dt1 != null) {
            doors.doors.add(new Door("DT1", new Leaf[]{dt1},
                    -128.99f, -94.635f, -117.50f, -94.635f));
        }

        Leaf dt2 = swingFromClosed(doors, np, "DT2", "DT2",
                100.75f, -94.635f, 1, 0, 100f, 0, 0);
        if (dt2 != null) {
            doors.doors.add(new Door("DT2", new Leaf[]{dt2},
                    100.75f, -94.635f, 112.23f, -94.635f));
        }
        Leaf dt3 = swingFromClosed(doors, np, "DT3", "DT3",
                -14.12f, -94.635f, 1, 0, 100f, 0, 0);
        if (dt3 != null) {
            doors.doors.add(new Door("DT3", new Leaf[]{dt3},
                    -14.12f, -94.635f, -2.63f, -94.635f));
        }

        Leaf dt4 = swingFromExportBaked(doors, np, "DT4", "DT4",
                184.12f, 5.01f, 1, 0, 139.04f - 184.12f, -28.25f - 5.01f);
        if (dt4 != null) {
            doors.doors.add(new Door("DT4", new Leaf[]{dt4},
                    139.04f, -28.25f, 150.52f, -28.25f));
        }
        Leaf dt5 = swingFromExportBaked(doors, np, "DT5", "DT5",
                -200.88f, 5.01f, 1, 0, 139.04f - (-200.88f), 356.07f - 5.01f);
        if (dt5 != null) {
            doors.doors.add(new Door("DT5", new Leaf[]{dt5},
                    139.04f, 356.07f, 150.52f, 356.07f));
        }

        Leaf or1 = swingFromExport(doors, np, "OR1", "OR1",
                -178.61f, -56.918f, -181.48f, -43.716f, 1, 0);
        if (or1 != null) {
            doors.doors.add(new Door("OR1", new Leaf[]{or1},
                    -178.88f, -56.25f, -165.48f, -56.25f));
        }
        Leaf or2 = swingFromExport(doors, np, "OR2", "OR2",
                -1.948f, -56.918f, 0.922f, -43.816f, -1, 0);
        if (or2 != null) {
            doors.doors.add(new Door("OR2", new Leaf[]{or2},
                    -15.08f, -56.25f, -1.68f, -56.25f));
        }
        Leaf or3 = swingFromExport(doors, np, "OR3", "OR3",
                148.999f, -56.918f, 146.129f, -43.816f, 1, 0);
        if (or3 != null) {
            doors.doors.add(new Door("OR3", new Leaf[]{or3},
                    148.73f, -56.25f, 162.13f, -56.25f));
        }
        return doors;
    }

    public void update(float px, float pz, float dt) {
        update(px, pz, dt, null);
    }

    public void update(float px, float pz, float dt, java.util.List<Npc> npcs) {
        final float senseR = 9.0f;
        final float releaseR = 13.0f;

        final float holdTime = 3.0f;
        for (Door d : doors) {
            float dist = segDist(px, pz, d.sx, d.sz, d.ex, d.ez);
            if (npcs != null) {
                for (Npc n : npcs) {
                    if (n.state != Npc.PatientState.FOLLOWING
                            && n.state != Npc.PatientState.TREATED
                            && n.state != Npc.PatientState.DISCHARGED_WALK
                            && n.state != Npc.PatientState.WAITING_LOBBY) {
                        continue;
                    }
                    float nd = segDist(n.x, n.z, d.sx, d.sz, d.ex, d.ez);
                    if (nd < dist) {
                        dist = nd;
                    }
                }
            }
            if (!d.open && dist < senseR) {
                d.open = true;
                d.holdLeft = holdTime;
            } else if (d.open) {
                if (dist > releaseR) {
                    d.holdLeft -= dt;
                    if (d.holdLeft <= 0f) {
                        d.open = false;
                    }
                } else {
                    d.holdLeft = holdTime;
                }
            }
            float target = d.open ? 1f : 0f;
            for (Leaf l : d.leaves) {
                l.target = target;
                float step = DOOR_SPEED * dt;
                if (l.open < target) {
                    l.open = Math.min(target, l.open + step);
                } else if (l.open > target) {
                    l.open = Math.max(target, l.open - step);
                }
                float[] seg = l.barrierSegment(l.open);
                world.moveBarrier(l.barrier, seg[0], seg[1], seg[2], seg[3]);
            }
        }
    }

    private static final float DOOR_SPEED = 1.8f;

    public static float segDist(float px, float pz,
                                float ax, float az, float bx, float bz) {
        float abx = bx - ax, abz = bz - az;
        float len2 = abx * abx + abz * abz;
        float t = 0f;
        if (len2 > 1e-9f) {
            t = ((px - ax) * abx + (pz - az) * abz) / len2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
        }
        float cx = ax + abx * t, cz = az + abz * t;
        float dx = px - cx, dz = pz - cz;
        return (float) java.lang.Math.sqrt(dx * dx + dz * dz);
    }

    public int doorCount() {
        return doors.size();
    }

    public List<Door> all() {
        return doors;
    }

    public List<Leaf> allLeaves() {
        return leaves;
    }

    public static java.util.Set<String> namedObjects() {
        return java.util.Set.of(
                "DMain_LeafL", "DMain_LeafR",
                "DT1_Leaf", "DT1_Port_Glass", "DT1_Port_Ring",
                "DT2_Leaf", "DT2_Port_Glass", "DT2_Port_Ring",
                "DT3_Leaf", "DT3_Port_Glass", "DT3_Port_Ring",
                "DT4_Leaf", "DT4_Port_Glass", "DT4_Port_Ring",
                "DT5_Leaf", "DT5_Port_Glass", "DT5_Port_Ring",
                "OR1_Door_Leaf", "OR1_Door_Port", "OR1_Door_Kick",
                "OR2_Door_Leaf", "OR2_Door_Port", "OR2_Door_Kick",
                "OR3_Door_Leaf", "OR3_Door_Port", "OR3_Door_Kick");
    }

    public static Map<String, String[]> groups() {
        return Map.of(
                "DMain_LeafL", new String[]{"DMain_LeafL"},
                "DMain_LeafR", new String[]{"DMain_LeafR"},
                "DT1", new String[]{"DT1_Leaf", "DT1_Port_Glass", "DT1_Port_Ring"},
                "DT2", new String[]{"DT2_Leaf", "DT2_Port_Glass", "DT2_Port_Ring"},
                "DT3", new String[]{"DT3_Leaf", "DT3_Port_Glass", "DT3_Port_Ring"},
                "DT4", new String[]{"DT4_Leaf", "DT4_Port_Glass", "DT4_Port_Ring"},
                "DT5", new String[]{"DT5_Leaf", "DT5_Port_Glass", "DT5_Port_Ring"},
                "OR1", new String[]{"OR1_Door_Leaf", "OR1_Door_Port", "OR1_Door_Kick"},
                "OR2", new String[]{"OR2_Door_Leaf", "OR2_Door_Port", "OR2_Door_Kick"},
                "OR3", new String[]{"OR3_Door_Leaf", "OR3_Door_Port", "OR3_Door_Kick"});
    }

    static Leaf swingForTest(Doors doors, ObjLoader.MeshPart part, String doorName,
                             float hx, float hz, float farX, float farZ,
                             float closedDirX, float closedDirZ) {
        float dx = farX - hx, dz = farZ - hz;
        float len = (float) java.lang.Math.sqrt(dx * dx + dz * dz);
        float eAx = dx / len, eAz = dz / len;
        float thetaClosed = (float) (java.lang.Math.atan2(eAz, eAx)
                - java.lang.Math.atan2(closedDirZ, closedDirX));
        Leaf l = new Leaf(part, doorName, false, 0, 0, 0,
                hx, hz, 0, 0, len, eAx, eAz, thetaClosed, 0f, doors.world);
        doors.leaves.add(l);
        return l;
    }

    static Door doorForTest(Doors doors, String name, Leaf[] leaves,
                            float sx, float sz, float ex, float ez) {
        Door d = new Door(name, leaves, sx, sz, ex, ez);
        doors.doors.add(d);
        return d;
    }

    List<Leaf> leavesForTest() {
        return leaves;
    }

    List<Door> doorsForTest() {
        return doors;
    }

    public Door findNear(float px, float pz, float maxDist) {
        Door best = null;
        float bestD = maxDist;
        for (Door d : doors) {
            float dist = segDist(px, pz, d.sx, d.sz, d.ex, d.ez);
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }
}
