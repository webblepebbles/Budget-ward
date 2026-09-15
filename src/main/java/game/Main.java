package game;

import game.assets.ObjLoader;
import game.hud.Hud;
import game.player.CollisionWorld;
import game.player.Player;
import game.render.FontAtlas;
import game.render.VkRenderer;
import game.world.Doors;
import game.world.GameState;
import game.world.MapFixes;
import game.world.MovableBed;
import game.world.NavGrid;
import game.world.Npc;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

public final class Main {

    private long window;
    private VkRenderer renderer;
    private CollisionWorld world;
    private Player player;
    private Hud hud;
    private FontAtlas font;
    private Doors doors;
    private GameState game = new GameState();
    private NavGrid nav;
    private final List<MapFixes.Spot> spots = MapFixes.spots();
    private final List<MapFixes.PLight> lights = new ArrayList<>();
    private final List<Npc> allNpcs = new ArrayList<>();
    private final List<MovableBed> beds = new ArrayList<>();
    private Npc nurse;

    private int pushingBed = -1;

    private static final float FLOOR2_Y = 34.8f;

    private static final float[][] STAIR_WAYPOINTS_W = {
            {-87.1f, -99.0f, 0.4f},
            {-87.1f, -112.0f, 8.0f},
            {-87.1f, -123.5f, 17.8f},
            {-87.1f, -132.0f, 17.8f},
            {-73.2f, -132.0f, 18.0f},
            {-73.2f, -123.5f, 18.0f},
            {-73.2f, -112.0f, 26.0f},
            {-73.2f, -99.5f, 34.4f},
            {-73.2f, -96.0f, 34.8f},
    };
    private static final float[][] STAIR_WAYPOINTS_E = {
            { 69.1f, -99.0f, 0.4f},
            { 69.1f, -112.0f, 8.0f},
            { 69.1f, -123.5f, 17.8f},
            { 69.1f, -132.0f, 17.8f},
            { 55.2f, -132.0f, 18.0f},
            { 55.2f, -123.5f, 18.0f},
            { 55.2f, -112.0f, 26.0f},
            { 55.2f, -99.5f, 34.4f},
            { 55.2f, -96.0f, 34.8f},
    };

    private static int nearestStair(float x, float z) {
        float dw = (x + 87.1f) * (x + 87.1f) + (z + 99f) * (z + 99f);
        float de = (x - 69.1f) * (x - 69.1f) + (z + 99f) * (z + 99f);
        return dw <= de ? 0 : 1;
    }

    private boolean fwd, back, left, right, sprint, jumpHeld;
    private int hotbarSelected;
    private final boolean[] hotbarUnlocked = {true, true, true, true, true};
    private boolean mouseCaptured;
    private double lastX, lastY;
    private boolean firstMouse = true;
    private double menuMouseX, menuMouseY;
    private double transitionT = -1;
    private static final double TRANSITION_DUR = 1.4;
    private double fadeOutT;
    private static final double FADE_OUT_DUR = 0.5;
    private double menuTime;
    private boolean shiftWasDown, startWasDown;
    private boolean headlessDrive;
    private boolean tour;
    private Npc tourP;
    private int tourStage;
    private int tourStageFrame;
    private final StringBuilder tourReport = new StringBuilder();

    private double eHoldTime;
    private boolean eWasDown;

    private float holdNeed = -1f;
    private boolean holdActive;

    private boolean chartOpen;
    private boolean tabWasDown, escWasDown;
    private int chartOrgan = -1, chartIssue = -1;
    private Npc chartPatient;
    private double patientSpawnTimer = 3.0;

    private int autotestPhase;
    private double autotestTimer;
    private String autotestLog = "";
    private String lastAutotestPos = "";

    private double lastFrame;

    private GLFWErrorCallback errCb;
    private GLFWCursorPosCallback cursorCb;
    private GLFWMouseButtonCallback mouseCb;
    private GLFWFramebufferSizeCallback fbSizeCb;

    public static void main(String[] args) {
        new Main().run();
    }

    private void run() {
        try {
            initWindow();
            initRenderer();
            loadMap();
            initHud();
            loop();
        } catch (Exception e) {
            System.err.println("Fatal: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        } finally {
            cleanup();
        }
    }

    private void initWindow() {
        errCb = GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        if (!org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("Vulkan is not supported on this machine (MoltenVK missing?)");
        }
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        window = glfwCreateWindow(1280, 720, "Budget Ward", 0, 0);
        if (window == 0) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        glfwSetCursorPosCallback(window, cursorCb = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long win, double xpos, double ypos) {
                menuMouseX = xpos;
                menuMouseY = ypos;
                if (!mouseCaptured) {
                    return;
                }
                if (firstMouse) {
                    firstMouse = false;
                    lastX = xpos;
                    lastY = ypos;
                    return;
                }
                float sens = 0.0026f;
                player.addLook((float) ((xpos - lastX) * sens), (float) ((ypos - lastY) * -sens));
                lastX = xpos;
                lastY = ypos;
            }
        });

        glfwSetMouseButtonCallback(window, mouseCb = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long win, int button, int action, int mods) {
                if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) {
                    return;
                }
                if (chartOpen) {
                    handleChartClick(pickChartButton());
                    return;
                }
                if (mouseCaptured) {
                    return;
                }
                if (transitionT >= 0) {
                    return;
                }
                GameState.Screen s = game.screen;
                if (s == GameState.Screen.PLAYING) {
                    glfwSetInputMode(win, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    mouseCaptured = true;
                    firstMouse = true;
                    return;
                }

                int pick = pickMenuButton();
                if (pick == 1) {
                    menuPrimaryAction();
                } else if (pick == 2) {
                    glfwSetWindowShouldClose(win, true);
                }

            }
        });

        glfwSetFramebufferSizeCallback(window, fbSizeCb = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long win, int w, int h) {
                if (renderer != null) {
                    renderer.requestResize();
                }
            }
        });

        glfwMakeContextCurrent(0);
        glfwShowWindow(window);
    }

    private void initRenderer() {
        renderer = new VkRenderer(window);
    }

    private void loadMap() throws IOException {

        List<Path> objs = new ArrayList<>();
        try (var dir = Files.list(Path.of("."))) {
            dir.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".obj"))
               .sorted()
               .forEach(objs::add);
        }
        if (objs.isEmpty()) {
            throw new IOException("No .obj files found in the working directory");
        }

        world = new CollisionWorld();
        List<ObjLoader.Model> models = new ArrayList<>();
        ObjLoader.Model bedsModel = null;
        int tris = 0;
        for (Path p : objs) {
            System.out.println("Loading " + p + " ...");
            long t0 = System.currentTimeMillis();
            ObjLoader.Model m = ObjLoader.load(p, MapFixes.withNoCollide(DOOR_OBJECTS), DOOR_GROUPS, IGNORE_OBJECTS);
            carveFacadeDoorway(m);
            System.out.printf("  %d parts, %d tris in %d ms%n",
                    m.parts.size(), m.parts.stream().mapToInt(q -> q.positions.length / 9).sum(),
                    System.currentTimeMillis() - t0);
            if (p.getFileName().toString().toLowerCase().startsWith("beds")) {
                bedsModel = m;
                continue;
            }
            models.add(m);
            tris += m.parts.stream().mapToInt(q -> q.positions.length / 9).sum();
        }

        ObjLoader.Model map = models.get(0);

        int carved = MapFixes.apply(map);
        System.out.println("MapFixes: carved " + carved + " part(s)");

        for (String n : MapFixes.NO_COLLIDE) {
            for (ObjLoader.MeshPart p = map.namedParts.get(n); p != null; p = p.next) {
                map.parts.add(p);
            }
        }
        models.add(0, map);
        List<ObjLoader.MeshPart> bedClones = MapFixes.buildBedClones(bedsModel);
        for (ObjLoader.MeshPart bed : bedClones) {
            map.parts.add(bed);
            world.addPart(bed);
        }
        System.out.println("Beds cloned into rooms: " + bedClones.size());

        for (int i = 0; i < BED_SPOTS.length; i++) {
            float[] s = BED_SPOTS[i];
            beds.add(new MovableBed(i, s[0] + 8f, 0.4f, s[1] + 8f));
        }
        System.out.println("Movable beds spawned: " + beds.size());
        renderer.beds = beds;
        lights.addAll(MapFixes.collectLights(map));
        renderer.uploadLights(lights);
        spots.clear();
        spots.addAll(MapFixes.spots());

        for (ObjLoader.MeshPart part : map.parts) {
            if (!MapFixes.NO_COLLIDE.contains(part.objectName)) {
                world.addPart(part);
            }
        }

        long navT0 = System.currentTimeMillis();
        nav = NavGrid.build(world,
                map.boundsMin[0], map.boundsMin[2],
                map.boundsMax[0], map.boundsMax[2]);
        System.out.println("NavGrid: " + nav.walkableCells() + " cells in "
                + (System.currentTimeMillis() - navT0) + " ms");

        ObjLoader.Model doorModel = map;
        doors = doorModel != null && doorModel.namedParts.containsKey("DMain_LeafL")
                ? Doors.build(world, doorModel) : new Doors(world);
        System.out.println("Doors: " + doors.doorCount() + " ("
                + doors.allLeaves().size() + " leaves)");

        renderer.uploadMap(models, doors.allLeaves());
        System.out.println("GPU upload complete: " + tris + " triangles");

        player = new Player(world);

        nurse = new Npc(Npc.Role.NURSE, -48f, 0.4f, 1f, 0.20f, 0.62f, 0.60f);
        nurse.name = "Nurse";
        allNpcs.add(nurse);
        game.staff.add(nurse);
    }

    private void initHud() throws Exception {
        Path font = FontAtlas.findFontFile();
        System.out.println("HUD font: " + (font != null ? font : "NONE (fatal)"));
        this.font = new FontAtlas(font);
        renderer.uploadFontAtlas(this.font);
        hud = new Hud(this.font);
    }

    private void resetInput() {
        fwd = back = left = right = sprint = jumpHeld = false;
    }

    private void drive(float yawDeg, boolean f, boolean b, boolean l, boolean r, boolean sp) {
        if (player != null) {
            player.setYaw((float) Math.toRadians(yawDeg));
        }
        fwd = f; back = b; left = l; right = r; sprint = sp;
    }

    private void loop() {

        String smoke = System.getProperty("game.smokeTest", "");
        int smokeFrames = smoke.isEmpty() ? -1 : Integer.parseInt(smoke);

        String shotPath = System.getProperty("game.screenshot", "");

        boolean autotest = Boolean.parseBoolean(System.getProperty("game.autotest", "false"));

        boolean playtest = Boolean.parseBoolean(System.getProperty("game.playtest", "false"));
        tour = Boolean.parseBoolean(System.getProperty("game.tour", "false"));

        if (playtest) {
            game.screen = GameState.Screen.PLAYING;
        }
        headlessDrive = autotest || playtest || tour;

        int frames = 0;
        double fpsT0 = glfwGetTime();
        int fpsN = 0;

        lastFrame = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) Math.min(now - lastFrame, 0.05);
            lastFrame = now;

            glfwPollEvents();

            boolean playing = game.screen == GameState.Screen.PLAYING;
            if (autotest) {
                runAutotest(dt, frames);
            } else if (tour) {
                runTour(dt, frames);
            } else if (playtest) {
                runPlaytest(dt, frames);
            } else if (playing) {
                if (chartOpen) {
                    resetInput();
                } else {
                    readKeyboard();
                }
            } else {
                resetInput();
                readMenuKeys();
            }

            if (playing && !autotest && !playtest) {
                boolean tabDown = glfwGetKey(window, GLFW_KEY_TAB) == GLFW_PRESS;
                if (tabDown && !tabWasDown) {
                    setChartOpen(!chartOpen);
                }
                tabWasDown = tabDown;
                boolean escDown = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
                if (chartOpen && escDown && !escWasDown) {
                    setChartOpen(false);
                }
                escWasDown = escDown;
            }

            if (playing && !chartOpen
                    && glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS && mouseCaptured) {
                game.screen = GameState.Screen.PAUSED;
                mouseCaptured = false;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }

            if (transitionT >= 0 && !headlessDrive) {
                updateTransition(dt);
                playing = game.screen == GameState.Screen.PLAYING;
            } else if (game.screen == GameState.Screen.MAIN_MENU && !headlessDrive) {
                menuCinematic(dt);
            }
            if (fadeOutT > 0) {
                fadeOutT = Math.max(0, fadeOutT - dt);
            }

            if (game.screen == GameState.Screen.PAUSED
                    && glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS) {
                resumeGame();
            }

            if (player != null && (playing || autotest || playtest)) {
                player.update(dt, fwd, back, left, right, sprint, jumpHeld);
            }
            if (doors != null && player != null) {
                doors.update(player.x, player.z, dt, allNpcs);
            }
            if (playing) {
                game.tick(dt);
                updateNpcs(dt);
                updateBeds(dt);
                updateSurgery(dt);
                updateInteractions(dt);
                updateSpawning(dt);
                checkBedArrivals();
                checkDischargeArrival();
                checkDischargeAction();
                checkDayEnd();
            }

            int vw = 1280, vh = 720;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
                glfwGetFramebufferSize(window, w, h);
                vw = Math.max(1, w.get(0));
                vh = Math.max(1, h.get(0));
            }
            buildHud(vw, vh);
            int[] verts = new int[1];
            java.nio.ByteBuffer hudMesh = hud.buildBuffer(verts);

            renderer.npcList = allNpcs;
            renderer.renderFrame(player, hudMesh, verts[0]);
            MemoryUtil.memFree(hudMesh);

            frames++;
            fpsN++;
            if (smokeFrames > 0) {
                if (frames % 120 == 0) {
                    double t = glfwGetTime();
                    System.out.printf("frame %d: %.1f fps (pos %.1f,%.1f,%.1f)%n",
                            frames, fpsN / (t - fpsT0), player.x, player.y, player.z);
                    fpsT0 = t;
                    fpsN = 0;
                }
                if (frames >= smokeFrames) {
                    System.out.println("Smoke test passed: " + frames + " frames rendered");
                    break;
                }
            }
            if (!shotPath.isEmpty()) {
                boolean capture = autotest
                        ? autotestTimer >= 8.0
                        : frames == 90;
                if (capture) {
                    byte[] bgra = renderer.captureFrame();
                    saveScreenshot(shotPath, bgra);
                    System.out.println("Screenshot saved: " + shotPath);
                    break;
                }
            }
            if ((autotest || playtest) && frames >= 9000) {
                break;
            }
        }
        if (autotest || playtest) {
            System.out.println("Autotest done. Log:\n" + autotestLog);
            System.out.printf("Final pos: %.1f,%.1f,%.1f doors open-check done%n",
                    player != null ? player.x : 0f, player != null ? player.y : 0f,
                    player != null ? player.z : 0f);
        }
    }

    private void readKeyboard() {
        if (player == null) {
            return;
        }
        fwd = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        back = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        left = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        right = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
        sprint = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
        jumpHeld = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (glfwGetKey(window, GLFW_KEY_1) == GLFW_PRESS) { hotbarSelected = 0; game.toolSelected = 0; }
        if (glfwGetKey(window, GLFW_KEY_2) == GLFW_PRESS) { hotbarSelected = 1; game.toolSelected = 1; }
        if (glfwGetKey(window, GLFW_KEY_3) == GLFW_PRESS) { hotbarSelected = 2; game.toolSelected = 2; }
        if (glfwGetKey(window, GLFW_KEY_4) == GLFW_PRESS) { hotbarSelected = 3; game.toolSelected = 3; }
        if (glfwGetKey(window, GLFW_KEY_5) == GLFW_PRESS) { hotbarSelected = 4; game.toolSelected = 4; }
        if (glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS) player.respawn();
    }

    private void readMenuKeys() {
        boolean shiftDown = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
                || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
        boolean startDown = shiftDown
                || glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                || glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;

        if (startDown && !startWasDown) {
            if (game.screen == GameState.Screen.MAIN_MENU) {
                menuPrimaryAction();
            } else if (game.screen == GameState.Screen.DAY_END) {
                nextDay();
            } else if (game.screen == GameState.Screen.PAUSED
                    && glfwGetKey(window, GLFW_KEY_P) != GLFW_PRESS) {
                menuPrimaryAction();
            }
        }
        startWasDown = startDown;
        shiftWasDown = shiftDown;
        if (game.screen == GameState.Screen.PAUSED
                && glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS) {
            resumeGame();
        }
    }

    private void menuPrimaryAction() {
        if (transitionT >= 0) {
            return;
        }
        if (game.screen == GameState.Screen.PAUSED) {
            resumeGame();
            return;
        }
        beginStartTransition();
    }

    private void beginStartTransition() {
        transitionT = 0;
        resetInput();
    }

    private void resumeGame() {
        setChartOpen(false);
        game.screen = GameState.Screen.PLAYING;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        mouseCaptured = true;
        firstMouse = true;
    }

    private void setChartOpen(boolean open) {
        if (chartOpen == open) {
            return;
        }
        chartOpen = open;
        eWasDown = false;
        eHoldTime = 0;
        resetInput();
        if (open) {
            chartOrgan = -1;
            chartIssue = -1;
            chartPatient = null;
            mouseCaptured = false;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        } else {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            mouseCaptured = true;
            firstMouse = true;
        }
    }

    private void nextDay() {
        setChartOpen(false);
        game.day++;
        game.goalTarget += 1;
        game.goalDone = 0;
        game.money += 40;
        game.screen = GameState.Screen.PLAYING;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        mouseCaptured = true;
        firstMouse = true;
        player.fovDeg = 75f;
        fadeOutT = FADE_OUT_DUR;
        game.banner("DAY " + game.day + " - discharge " + game.goalTarget + " patients!");
        admitFirstPatients();
    }

    private boolean admitToBed(Npc p, int b) {
        if (b < 0 || b >= BED_SPOTS.length || b >= beds.size() || bedTaken[b]) {
            return false;
        }
        bedTaken[b] = true;
        p.bedIndex = b;
        p.state = Npc.PatientState.IN_BED;
        p.lying = b < 5;
        p.sitting = false;
        p.setPath();
        MovableBed bed = beds.get(b);
        bed.occupant = p;
        seatOccupant(bed, p);
        p.critical = Math.random() < 0.25;
        p.criticalTimer = p.critical ? 45f : 0;
        p.navAge = 99f;
        return true;
    }

    private void admitFirstPatients() {
        int placed = 0;
        for (int b = 0; b < BED_SPOTS.length && placed < 2; b++) {
            if (bedTaken[b]) {
                continue;
            }
            Npc p = game.spawnPatient();
            sendToLobby(p);
            allNpcs.add(p);
            if (admitToBed(p, b)) {
                placed++;
                game.banner(p.name + " in bed " + (b + 1)
                        + (p.critical ? " - CRITICAL!" : ""));
            }
        }
    }

    private void startDay() {
        game.screen = GameState.Screen.PLAYING;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        mouseCaptured = true;
        firstMouse = true;
        player.fovDeg = 75f;
        fadeOutT = FADE_OUT_DUR;
        game.money += 40;
        game.banner("DAY " + game.day + " - discharge " + game.goalTarget + " patients!");
        Npc first = game.spawnPatient();
        sendToLobby(first);
        allNpcs.add(first);
        admitFirstPatients();
    }

    private int pickMenuButton() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fw = stack.mallocInt(1), fh = stack.mallocInt(1);
            IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            glfwGetFramebufferSize(window, fw, fh);
            glfwGetWindowSize(window, ww, wh);
            float sx = ww.get(0) > 0 ? (float) fw.get(0) / ww.get(0) : 1f;
            float sy = wh.get(0) > 0 ? (float) fh.get(0) / wh.get(0) : 1f;
            return Hud.pickMenuButton((float) (menuMouseX * sx), (float) (menuMouseY * sy),
                    Math.max(1, fw.get(0)), Math.max(1, fh.get(0)));
        }
    }

    private Npc nearestChartPatient() {
        Npc bestP = null;
        float bestD = 45f * 45f;
        for (Npc p : game.patients) {
            if (p.state != Npc.PatientState.WAITING_LOBBY
                    && p.state != Npc.PatientState.FOLLOWING
                    && p.state != Npc.PatientState.IN_BED
                    && p.state != Npc.PatientState.TREATED) {
                continue;
            }
            float dx = player.x - p.x, dz = player.z - p.z;
            float dy = player.y - p.y;
            if (dy > 12f || dy < -12f) {
                continue;
            }
            float d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                bestP = p;
            }
        }
        return bestP;
    }

    private boolean validateChartPatient() {
        if (chartPatient != null
                && (chartPatient.state == Npc.PatientState.GONE
                || chartPatient.state == Npc.PatientState.DISCHARGED_WALK)) {
            chartPatient = null;
            chartOrgan = -1;
            chartIssue = -1;
        }
        if (chartPatient != null) {
            return true;
        }
        Npc near = nearestChartPatient();
        if (near != null) {
            chartPatient = near;
            chartOrgan = -1;
            chartIssue = -1;
            return true;
        }
        return false;
    }

    private int chartStepCount() {
        if (chartPatient == null) {
            return 1;
        }
        GameState.Illness ill = game.findIllness(chartPatient.illness);
        return ill != null ? Math.max(1, ill.steps.length) : 1;
    }

    private int pickChartButton() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fw = stack.mallocInt(1), fh = stack.mallocInt(1);
            IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            glfwGetFramebufferSize(window, fw, fh);
            glfwGetWindowSize(window, ww, wh);
            float sx = ww.get(0) > 0 ? (float) fw.get(0) / ww.get(0) : 1f;
            float sy = wh.get(0) > 0 ? (float) fh.get(0) / wh.get(0) : 1f;
            return Hud.pickChartButton((float) (menuMouseX * sx), (float) (menuMouseY * sy),
                    Math.max(1, fw.get(0)), Math.max(1, fh.get(0)),
                    GameState.organs().length, GameState.issues().length, chartStepCount());
        }
    }

    private void handleChartClick(int code) {
        if (!validateChartPatient()) {
            game.banner("No patient in range - walk closer");
            return;
        }
        String[] organs = GameState.organs();
        String[] issues = GameState.issues();
        if (code >= Hud.CHART_ORGAN && code < Hud.CHART_ORGAN + organs.length) {
            chartOrgan = code - Hud.CHART_ORGAN;
        } else if (code >= Hud.CHART_ISSUE && code < Hud.CHART_ISSUE + issues.length) {
            chartIssue = code - Hud.CHART_ISSUE;
        } else if (code == Hud.CHART_DIAGNOSE) {
            doChartDiagnose();
        } else if (code == Hud.CHART_DISCHARGE) {
            doChartDischarge();
        }
    }

    private void doChartDiagnose() {
        Npc p = chartPatient;
        if (p == null) {
            return;
        }
        if (p.diagnosed) {
            game.banner(p.name + " already diagnosed");
            return;
        }
        String[] organs = GameState.organs();
        String[] issues = GameState.issues();
        String organ = chartOrgan >= 0 && chartOrgan < organs.length ? organs[chartOrgan] : null;
        String issue = chartIssue >= 0 && chartIssue < issues.length ? issues[chartIssue] : null;
        GameState.Illness ill = game.findIllness(p.illness);
        if (organ == null || issue == null) {
            game.banner("Stamp an organ + pick an issue first, then DIAGNOSE");
            return;
        }
        if (GameState.diagnosisCorrect(ill, organ, issue)) {
            p.diagnosed = true;
            game.banner(p.name + " diagnosed: " + p.illness + "!");
        } else {
            GameState.Illness di = game.findIllness(p.illness);
            String loc = di != null ? locName(GameState.visualFor(di)) : "body";
            String want = di != null
                    ? "needs " + di.organ + " + " + di.symptom
                    + " (" + GameState.symptomColorName(di) + " badge on the " + loc + ")"
                    : "check the badge";
            game.banner("Wrong: " + organ + " + " + issue + " - "
                    + p.name + "'s badge is " + p.symptom + " on the " + loc + " = " + want);
        }
    }

    private boolean chartCanDischarge(Npc p) {
        return isDischargeable(p);
    }

    private static boolean isDischargeable(Npc p) {
        if (p == null) {
            return false;
        }
        return p.state == Npc.PatientState.TREATED
                || p.state == Npc.PatientState.IN_BED
                || p.state == Npc.PatientState.FOLLOWING
                || p.state == Npc.PatientState.WAITING_LOBBY;
    }

    private String nurseAdvice(Npc p, GameState.Illness ill) {
        if (p == null) {
            return "walk up to a patient";
        }
        if (!p.diagnosed) {
            String loc = ill != null ? locName(GameState.visualFor(ill)) : "body";
            String col = GameState.symptomColorName(ill);
            String want = ill != null ? ill.organ + " + " + ill.symptom : p.symptom;
            return "stamp " + want + " (" + col + " badge: " + p.symptom + " on the " + loc + ")";
        }
        int need = ill != null ? ill.steps.length : 2;
        if (p.treatStepsDone < need) {
            String next = ill != null && p.treatStepsDone < ill.steps.length
                    ? ill.steps[p.treatStepsDone] : "treat at the bed";
            return "step " + (p.treatStepsDone + 1) + "/" + need + ": " + next;
        }
        if (ill != null && ill.or && !p.orReady) {
            return "request OR at HUB / OR door, then lead upstairs";
        }
        if (p.orReady) {
            return "lead upstairs to the OR table on foot";
        }
        return "discharge at the HUB counter or this chart";
    }

    private void doChartDischarge() {
        Npc p = chartPatient;
        if (p == null) {
            return;
        }
        if (!isDischargeable(p)) {
            game.banner("Nothing to discharge");
            return;
        }
        boolean full = p.state == Npc.PatientState.TREATED;
        releaseBed(p);
        int pay = dischargePatient(p);
        game.banner(full ? "DISCHARGED! +$" + pay
                : "Early discharge +$" + pay + " (treat first for full pay)");
    }

    private int dischargePatient(Npc p) {
        releaseBed(p);

        p.orReady = false;
        p.surgeryTimer = -1f;
        if (p.orSlot >= 0 && p.orSlot < game.orBusy.length) {
            game.orBusy[p.orSlot] = false;
        }
        p.orSlot = -1;
        if (p.orRequested) {
            p.orRequested = false;
            if (game.orRequested >= 0) {
                game.orBusy[game.orRequested] = false;
                game.orRequested = -1;
            }
        }
        p.state = Npc.PatientState.DISCHARGED_WALK;
        p.lying = false;
        p.sitting = false;
        int pay = game.dischargePay(p);
        float[] exit = null;
        if (nav != null) {
            exit = nav.findPath(world, p.x, p.y, p.z, -8.4f, 0.4f, 170f);
        }
        if (exit != null && exit.length >= 3) {
            p.setPath3(exit);
        } else {
            p.setPath(0f, 8f, -8.4f, 120f, -8.4f, 158f, -8.4f, 170f);
        }
        p.navAge = 99f;
        eHoldTime = 0;
        return pay;
    }

    private float[] menuCursorFb(int vw, int vh) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            glfwGetWindowSize(window, ww, wh);
            float sx = ww.get(0) > 0 ? (float) vw / ww.get(0) : 1f;
            float sy = wh.get(0) > 0 ? (float) vh / wh.get(0) : 1f;
            return new float[]{(float) (menuMouseX * sx), (float) (menuMouseY * sy)};
        }
    }

    private void menuCinematic(float dt) {
        menuTime += dt;
        if (player == null) {
            return;
        }
        player.setYaw((float) Math.PI + (float) Math.sin(menuTime * 0.16) * 0.22f);
        player.setPitch(-0.015f + (float) Math.sin(menuTime * 0.11) * 0.02f);
        player.fovDeg = 62f;
    }

    private void updateTransition(float dt) {
        transitionT += dt;
        double k = Math.min(1.0, transitionT / TRANSITION_DUR);

        float ease = (float) (k * k * (3.0 - 2.0 * k));
        if (player != null) {
            player.fovDeg = 62f + (40f - 62f) * ease;
            player.setYaw((float) Math.PI);
            player.setPitch(-0.015f);
        }
        if (k >= 1.0) {
            transitionT = -1;
            startDay();
        }
    }

    private float transitionFade() {
        if (transitionT < 0) {
            return 0f;
        }
        double k = Math.min(1.0, transitionT / TRANSITION_DUR);
        double f = Math.min(1.0, Math.max(0.0, (k - 0.35) / 0.65));
        return (float) (f * f * (3.0 - 2.0 * f));
    }

    private static boolean upstairs(float y) {
        return y > 30f;
    }

    private static float[] routeUp(float x, float z, float tx, float tz) {
        float[][] w = STAIR_WAYPOINTS_W;
        if (nearestStair(x, z) == 1) w = STAIR_WAYPOINTS_E;
        float[] p = new float[w.length * 3 + 3];
        int i = 0;
        for (float[] s : w) { p[i++] = s[0]; p[i++] = s[1]; p[i++] = s[2]; }
        p[i++] = tx; p[i++] = tz; p[i++] = FLOOR2_Y;
        return p;
    }

    private static float[] routeDown(float x, float z, float tx, float tz) {
        float[][] w = STAIR_WAYPOINTS_W;

        if (nearestStair(x, z) == 1) w = STAIR_WAYPOINTS_E;
        float[] p = new float[w.length * 3 + 3];
        int i = 0;

        for (int k = w.length - 1; k >= 0; k--) {
            p[i++] = w[k][0]; p[i++] = w[k][1]; p[i++] = w[k][2];
        }
        p[i++] = tx; p[i++] = tz; p[i++] = 0.4f;
        return p;
    }

    private static float approach(float cur, float target, float maxDelta) {
        float d = target - cur;
        if (d > maxDelta) return cur + maxDelta;
        if (d < -maxDelta) return cur - maxDelta;
        return target;
    }

    private void updateNpcs(float dt) {
        boolean playerUp = upstairs(player.y);
        for (Npc n : allNpcs) {
            if (n.role == Npc.Role.PATIENT
                    && (n.state == Npc.PatientState.FOLLOWING
                    || n.state == Npc.PatientState.TREATED
                    || n.state == Npc.PatientState.WAITING_LOBBY)) {
                float sx = n.x - n.stallX, sz = n.z - n.stallZ;
                if (sx * sx + sz * sz > 1f) {
                    n.stallX = n.x;
                    n.stallZ = n.z;
                    n.stallT = 0f;
                } else if (!n.pathDone()) {
                    n.stallT += dt;
                    if (n.stallT > 1.5f) {

                        n.setPath();
                        n.navAge = 99f;
                        n.stairAge = 99f;
                        n.stallT = 0f;
                        if (n.state == Npc.PatientState.WAITING_LOBBY) {
                            sendToLobby(n);
                        }
                    }
                }
            }

            if (n.state == Npc.PatientState.FOLLOWING) {
                float dx = player.x - n.x, dz = player.z - n.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > 14f) {
                    boolean nUp = upstairs(n.y);
                    if (nUp != playerUp) {

                        if (n.planTag != 1 && n.planTag != 2) {
                            n.setPath();
                        }
                        stairHandoff(n, dt, playerUp);
                    } else {
                        steerTo(n, dt, player.x, player.z);
                    }
                } else if (dist < 8f) {
                    n.setPath();
                    n.navAge = 99f;
                }
            } else if (n.state == Npc.PatientState.TREATED) {

                float dx = player.x - n.x, dz = player.z - n.z;
                if (dx * dx + dz * dz > 12f * 12f) {
                    boolean nUp = upstairs(n.y);
                    if (nUp != playerUp) {
                        if (n.planTag != 1 && n.planTag != 2) {
                            n.setPath();
                        }
                        stairHandoff(n, dt, playerUp);
                    } else {
                        steerTo(n, dt, player.x, player.z);
                    }
                }
            }
            n.update(dt, (float) glfwGetTime(), world);
            rescueNpc(n);
            if (n.critical && n.state == Npc.PatientState.IN_BED) {
                n.criticalTimer -= dt;
            }
        }

        if (nurse != null && nurse.pathDone()) {
            float nx = -48f + (float) Math.random() * 70f - 35f;
            float nz = 1f + (float) Math.random() * 30f - 15f;
            steerTo(nurse, dt, nx, nz);
        }
    }

    private void rescueNpc(Npc n) {
        if (n.state == Npc.PatientState.IN_BED || n.state == Npc.PatientState.GONE) {
            return;
        }
        float g = world.groundHeight(n.x, n.y + 6f, n.z, 2.0f);
        if (g != -Float.MAX_VALUE) {
            n.groundY = g;
            if (n.y < g - 1f) {
                n.y = g;
            }
            return;
        }
        if (n.y < -10f) {
            if (n.state == Npc.PatientState.FOLLOWING
                    || n.state == Npc.PatientState.TREATED) {
                n.x = player.x;
                n.z = player.z;
                n.y = player.y;
            } else {
                n.x = -8.4f;
                n.z = 120f;
                n.y = 0.4f;
            }
            n.setPath();
            n.navAge = 99f;
        }
    }

    private float[] stairEntryFor(Npc n, boolean playerUp) {
        if (nearestStair(n.x, n.z) == 0) {
            return playerUp ? new float[]{-87.1f, -99.0f} : new float[]{-73.2f, -96.0f};
        }
        return playerUp ? new float[]{69.1f, -99.0f} : new float[]{55.2f, -96.0f};
    }

    private void issueStairChain(Npc n, boolean playerUp) {
        if (playerUp) {
            n.setPath3(routeUp(n.x, n.z, player.x, player.z));
        } else {
            n.setPath3(routeDown(n.x, n.z, player.x, player.z));
        }

        n.planTag = 1;
        n.stairAge = 0f;
        n.stallT = 0f;
        n.stallX = n.x;
        n.stallZ = n.z;
    }

    private void stairHandoff(Npc n, float dt, boolean playerUp) {
        boolean onStairs = n.y > 3f && n.y < 32f;
        if (onStairs) {

            if (n.pathDone()) {
                issueStairChain(n, playerUp);
            }
            return;
        }
        float[] e = stairEntryFor(n, playerUp);
        float edx = e[0] - n.x, edz = e[1] - n.z;
        if (edx * edx + edz * edz < 900f) {

            n.stairAge += dt;
            if (n.pathDone() || (n.stairAge > 1.0f && !n.pathStarted())) {
                issueStairChain(n, playerUp);
            }
            return;
        }
        if (n.pathDone()) {
            float[] r = (nav != null)
                    ? nav.findPath(world, n.x, n.y, n.z, e[0], n.y, e[1]) : null;
            if (r != null && r.length >= 3) {
                steerTo(n, dt, e[0], e[1]);
            } else {

                issueStairChain(n, playerUp);
            }
        }
    }

    private void steerTo(Npc n, float dt, float tx, float tz) {
        float moved = (tx - n.navTx) * (tx - n.navTx) + (tz - n.navTz) * (tz - n.navTz);
        n.navAge += dt;
        if (!n.pathDone() && n.navAge <= 0.6f && moved <= 64f) {
            return;
        }
        float[] route = null;
        if (nav != null) {
            route = nav.findPath(world, n.x, n.y, n.z, tx, n.y, tz);
        }
        if (route != null && route.length >= 3) {
            n.setPath3(route);
        } else {
            n.setPath(tx, tz);
        }
        n.navTx = tx;
        n.navTz = tz;
        n.navAge = 0f;
        n.planTag = 2;
    }

    private void sendToLobby(Npc p) {
        float[] route = null;
        if (nav != null) {
            route = nav.findPath(world, p.x, p.y, p.z, -8.4f, 0.4f, 120f);
        }
        if (route != null && route.length >= 3) {
            p.setPath3(route);
        } else {
            p.setPath(-8.4f, 158f, -8.4f, 120f);
        }
        p.navAge = 99f;
    }

    private void updateBeds(float dt) {
        MovableBed pushed = pushingBed >= 0 ? beds.get(pushingBed) : null;
        if (pushed != null
                && (player.x - pushed.x) * (player.x - pushed.x)
                + (player.z - pushed.z) * (player.z - pushed.z) > 30f * 30f) {
            pushingBed = -1;
            pushed = null;
            game.banner("Bed released");
        }
        for (MovableBed b : beds) {
            b.update(dt, b == pushed ? player : null, world);

            float g = world.groundHeight(b.x, b.y + 6f, b.z, MovableBed.HALF_W * 0.7f);
            if (g != -Float.MAX_VALUE) {
                b.setGroundY(g);
                b.groundY = g;
            }

            if (b.occupant != null) {
                seatOccupant(b, b.occupant);
            }
        }
    }

    private int nearestBed() {
        int best = -1;
        float bestD = 14f * 14f;
        for (int i = 0; i < beds.size(); i++) {
            MovableBed b = beds.get(i);
            float dx = player.x - b.x, dz = player.z - b.z;
            float d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private String bedPushLabel() {
        if (pushingBed >= 0) return "Release bed [tap E]";
        int i = nearestBed();
        if (i < 0) return "";
        MovableBed b = beds.get(i);
        return b.occupant != null
                ? "Push bed with " + b.occupant.name + " [tap E]"
                : "Push bed [tap E]";
    }

    private void bedPushAction() {
        if (pushingBed >= 0) {
            pushingBed = -1;
            game.banner("Bed released");
            return;
        }
        int i = nearestBed();
        if (i >= 0) {
            pushingBed = i;
            game.banner("Pushing bed (E to release) - beds stay downstairs, OR patients walk up");
        }
    }

    private void updateSpawning(float dt) {
        patientSpawnTimer -= dt;
        int waiting = 0;
        for (Npc p : game.patients) {
            if (p.state == Npc.PatientState.WAITING_LOBBY) waiting++;
        }
        if (patientSpawnTimer <= 0 && waiting < 4
                && game.patients.size() < 6) {
            patientSpawnTimer = 12.0 + Math.random() * 8.0;
            Npc p = game.spawnPatient();
            sendToLobby(p);
            allNpcs.add(p);
            game.banner("New patient: " + p.illness);
        }
    }

    private void updateInteractions(float dt) {
        game.prompt = "";
        if (player == null) {
            return;
        }
        if (chartOpen) {
            eHoldTime = 0;
            eWasDown = false;
            holdActive = false;
            holdNeed = -1f;
            return;
        }

        MapFixes.Spot best = null;
        float bestD = Float.MAX_VALUE;
        boolean up = upstairs(player.y);
        for (MapFixes.Spot s : spots) {
            boolean spotUp = s.id.startsWith("or_request_") || s.id.startsWith("surgery_");
            if (spotUp != up) continue;
            float dx = player.x - s.x, dz = player.z - s.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < s.radius && d < bestD) {
                bestD = d;
                best = s;
            }
        }
        boolean eDown = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS
                && (mouseCaptured || headlessDrive)
                && game.screen == GameState.Screen.PLAYING;

        boolean releasedEdge = !eDown && eWasDown;
        float releasedHold = (float) eHoldTime;
        if (eDown) {
            eHoldTime += dt;
        } else {
            eHoldTime = 0;
        }

        String bedLabel = bedPushLabel();
        boolean bedNear = !bedLabel.isEmpty();
        if (releasedEdge && releasedHold < 0.35f && (pushingBed >= 0 || bedNear)) {
            bedPushAction();
            bedLabel = bedPushLabel();
            bedNear = !bedLabel.isEmpty();
        }
        eWasDown = eDown;

        if (best != null) {
            String label = spotLabel(best);
            holdNeed = holdTargetFor(best);
            holdActive = eDown;
            if (bedNear && pushingBed < 0) {
                game.prompt = label + "   |   bed [tap E]";
            } else if (pushingBed >= 0) {
                game.prompt = bedLabel + "   |   " + label + "  (hold E...)";
            } else {
                game.prompt = eDown ? label + "  (hold E...)" : label + "  [E]";
            }
            if (eDown) {
                doSpotAction(best, (float) eHoldTime);
            }
        } else {
            holdActive = false;
            holdNeed = -1f;
        }
        if (game.prompt.isEmpty()) {

            Npc w = nearestPatientByState(Npc.PatientState.WAITING_LOBBY, 10f);
            if (w != null) {
                game.prompt = "Examine " + w.name + " (" + w.illness + ")  [hold E to lead]";
                holdNeed = 0.5f;
                holdActive = eDown;
                if (eDown && eHoldTime > 0.5f) {
                    w.state = Npc.PatientState.FOLLOWING;
                    w.navAge = 99f;
                    game.banner("Fetch " + w.name + " to a free bed (they follow you)");
                    eHoldTime = 0;
                }
            } else {
                Npc f = nearestPatientByState(Npc.PatientState.FOLLOWING, 8f);
                if (f != null) {
                    game.prompt = "Leading " + f.name + " - bring them to a free bed";
                }
            }
        }
        if (game.prompt.isEmpty() && bedNear) {
            game.prompt = bedLabel;
        }
    }

    private Npc nearestPatientByState(Npc.PatientState state, float radius) {
        Npc bestP = null;
        float bestD = radius * radius;
        for (Npc p : game.patients) {
            if (p.state != state) {
                continue;
            }
            float dx = player.x - p.x, dz = player.z - p.z;
            float dy = player.y - p.y;
            if (dy > 12f || dy < -12f) {
                continue;
            }
            float d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                bestP = p;
            }
        }
        return bestP;
    }

    private String spotLabel(MapFixes.Spot s) {
        switch (s.id) {
            case "checkin": {
                Npc p = firstWaiting();
                return p != null ? "Check in " + p.name + " (" + p.illness + ")" : "Reception (no patients waiting)";
            }
            case "hub_nurse_w": return "Nurse advice: " + nurseHint();
            case "hub_nurse_e": return game.orRequested >= 0
                    ? "OR" + (game.orRequested + 1) + " incoming..."
                    : "Request OR [E]";
            case "discharge": {
                Npc p = firstDischargeable();
                if (p == null) {
                    return "Discharge (no patients)";
                }
                return p.state == Npc.PatientState.TREATED
                        ? "Discharge " + p.name
                        : "Discharge " + p.name + " (untreated: partial pay)";
            }
            case "supply": return "Take supplies (infinite)";
            case "or_request_1": return orDoorLabel(0);
            case "or_request_2": return orDoorLabel(1);
            case "or_request_3": return orDoorLabel(2);
            case "surgery_1": case "surgery_2": case "surgery_3": {
                Npc p = patientReadyForSurgery();
                if (p != null && p.surgeryTimer > 0f) {
                    return "Operating on " + p.name + "... " + (int) Math.ceil(p.surgeryTimer) + "s";
                }
                return p != null
                        ? "Operate on " + p.name + " [Stand with them at the table]"
                        : "Op table (no OR-ready patient)";
            }
            default:
                if (s.id.startsWith("treat_")) {
                    return treatLabel(s.id);
                }
                return s.id;
        }
    }

    private String orDoorLabel(int i) {
        if (game.orRequested == i) {
            return "OR" + (i + 1) + ": " + (int) Math.max(0, game.orReadyAt[i] - game.dayTime) + "s...";
        }
        if (game.orBusy[i]) {
            return "OR" + (i + 1) + ": BUSY";
        }
        return "OR" + (i + 1) + ": READY - request [E]";
    }

    private String treatLabel(String id) {
        Npc p = patientAtBed(id);
        if (p == null) {
            return "Bed (no patient)";
        }
        if (!p.diagnosed) {
            GameState.Illness ill = game.findIllness(p.illness);
            String want = ill != null
                    ? "stamp " + ill.organ + " + pick " + ill.symptom
                    + " (" + GameState.symptomColorName(ill) + " " + p.symptom + " badge)"
                    : p.symptom;
            return "STOP: open chart [Tab] - " + want;
        }
        if (p.critical && p.criticalTimer <= 0) {
            return "CRASH CART! " + p.name + " is crashing!";
        }
        if (p.state == Npc.PatientState.IN_BED && treatProgress(p) >= 1f) {
            return p.name + " treated! Take to HUB discharge";
        }
        return "Treat " + p.name + " (" + p.illness + ") [Hold E]";
    }

    private String nurseHint() {
        Npc p = firstWaiting();
        if (p == null) return "All quiet.";
        GameState.Illness i = game.findIllness(p.illness);
        if (i == null) return p.illness;
        return p.illness + ": " + GameState.symptomColorName(i) + " badge ("
                + p.symptom + ") on the " + locName(GameState.visualFor(i))
                + " = stamp " + i.organ + " + pick " + i.symptom;
    }

    private static String locName(String key) {
        return switch (key) {
            case "head" -> "head";
            case "leg" -> "leg";
            case "arm" -> "arm";
            case "pale" -> "whole body (pale)";
            case "belly" -> "belly";
            default -> "chest";
        };
    }

    private Npc firstWaiting() {
        for (Npc p : game.patients) {
            if (p.state == Npc.PatientState.WAITING_LOBBY) return p;
        }
        return null;
    }

    private String objective() {
        if (game.screen != GameState.Screen.PLAYING) {
            return "";
        }
        if (game.goalDone >= game.goalTarget) {
            return "NEXT: Shift complete - nice scrubbing!";
        }
        Npc surgery = null, orGo = null, treated = null, treating = null,
                undiag = null, following = null, waiting = null;
        String treatDetail = "";
        for (Npc p : game.patients) {
            switch (p.state) {
                case IN_BED -> {
                    if (p.surgeryTimer > 0f) {
                        surgery = p;
                    } else if (p.orReady) {
                        orGo = p;
                    } else if (!p.diagnosed) {
                        if (undiag == null) undiag = p;
                    } else {
                        GameState.Illness ill = game.findIllness(p.illness);
                        int need = ill != null ? ill.steps.length : 2;
                        if (p.treatStepsDone < need) {
                            if (treating == null) {
                                treating = p;
                                treatDetail = "step " + (p.treatStepsDone + 1) + "/" + need;
                            }
                        } else if (p.orRequired && !p.orReady && orGo == null) {
                            orGo = p;
                        }
                    }
                }
                case FOLLOWING -> {
                    if (p.orReady) {
                        orGo = p;
                    } else if (following == null) {
                        following = p;
                    }
                }
                case TREATED -> {
                    if (treated == null) treated = p;
                }
                case WAITING_LOBBY -> {
                    if (waiting == null) waiting = p;
                }
                default -> { }
            }
        }
        if (surgery != null) {
            return "NEXT: Surgery on " + surgery.name + " in progress - wait for it";
        }
        if (orGo != null && orGo.orReady) {
            return "NEXT: Lead " + orGo.name + " UPSTAIRS on foot to the OR table";
        }
        if (game.orRequested >= 0) {
            return "NEXT: OR" + (game.orRequested + 1) + " incoming - treat others meanwhile";
        }
        if (orGo != null) {
            return "NEXT: Request OR for " + orGo.name + " at HUB / OR door [hold E]";
        }
        if (treated != null) {
            return "NEXT: Discharge " + treated.name + " at the HUB counter [hold E]";
        }
        if (treating != null) {
            return "NEXT: Hold E at " + treating.name + "'s bed to treat (" + treatDetail + ")";
        }
        if (undiag != null) {
            GameState.Illness ui = game.findIllness(undiag.illness);
            String want = ui != null ? ui.organ + " + " + ui.symptom : undiag.symptom;
            return "NEXT: Open " + undiag.name + "'s chart [Tab]: stamp " + want;
        }
        if (following != null) {
            return "NEXT: Lead " + following.name + " to a free ward bed (walk to it)";
        }
        if (waiting != null) {
            return "NEXT: Check in " + waiting.name + " at RECEPTION [hold E]";
        }
        return "NEXT: Discharge " + game.goalDone + "/" + game.goalTarget + " patients";
    }

    private Npc firstDischargeable() {
        Npc fallback = null;
        for (Npc p : game.patients) {
            if (p.state == Npc.PatientState.TREATED) {
                return p;
            }
            if (fallback == null && isDischargeable(p)) {
                fallback = p;
            }
        }
        return fallback;
    }

    private Npc patientAtBed(String spotId) {
        int bedIdx = Integer.parseInt(spotId.substring("treat_".length())) - 1;

        for (Npc p : game.patients) {
            if (p.state == Npc.PatientState.IN_BED && p.bedIndex == bedIdx) return p;
        }
        return null;
    }

    private float treatProgress(Npc p) {
        GameState.Illness ill = game.findIllness(p.illness);
        int need = ill != null ? ill.steps.length : 2;
        return Math.min(1f, p.treatStepsDone / (float) need);
    }

    private float holdTargetFor(MapFixes.Spot s) {
        return switch (s.id) {
            case "supply", "hub_nurse_w" -> 0.3f;
            case "treat_1", "treat_2", "treat_3", "treat_4", "treat_5", "treat_6" -> 0.5f;
            case "hub_nurse_e", "discharge" -> 1.0f;
            case "checkin" -> 1.2f;
            default -> 1.0f;
        };
    }

    private Npc firstDischargeableNear(float radius) {
        Npc bestP = null;
        float bestD = radius * radius;
        for (Npc p : game.patients) {
            if (p.state != Npc.PatientState.TREATED) {
                continue;
            }
            float dx = p.x - -8f, dz = p.z - 22f;
            float d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                bestP = p;
            }
        }
        if (bestP != null) {
            return bestP;
        }
        for (Npc p : game.patients) {
            if (!isDischargeable(p) || p.state == Npc.PatientState.TREATED) {
                continue;
            }
            float dx = p.x - -8f, dz = p.z - 22f;
            float d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                bestP = p;
            }
        }

        if (bestP == null) {
            bestP = firstDischargeable();
        }
        return bestP;
    }

    private void doSpotAction(MapFixes.Spot s, float hold) {
        switch (s.id) {
            case "checkin": {
                Npc p = firstWaiting();
                if (p != null && hold > 1.2f) {
                    p.state = Npc.PatientState.FOLLOWING;
                    p.setPath(player.x, player.z);
                    game.banner("Fetch " + p.name + " to a bed (they follow you)");
                    eHoldTime = 0;
                }
                break;
            }
            case "hub_nurse_e": {
                if (hold > 1.0f) {
                    Npc need = null;
                    for (Npc p : game.patients) {
                        if (p.orRequired && p.diagnosed && p.treatStepsDone >= 1 && !p.orReady) {
                            need = p;
                            break;
                        }
                    }
                    if (need != null && game.requestOr()) {
                        need.orRequested = true;
                        game.banner("OR requested - wait " + (int) game.orWaitTotal + "s");
                    }
                    eHoldTime = 0;
                }
                break;
            }
            case "or_request_1":
            case "or_request_2":
            case "or_request_3": {
                int orIdx = Integer.parseInt(s.id.substring("or_request_".length())) - 1;
                Npc need = null;
                for (Npc p : game.patients) {
                    if (p.orRequired && p.diagnosed && p.treatStepsDone >= 1 && !p.orReady) {
                        need = p;
                        break;
                    }
                }
                if (need != null && !game.orBusy[orIdx] && game.orRequested < 0) {

                    need.orRequested = true;
                    game.orBusy[orIdx] = true;
                    game.orRequested = orIdx;
                    double base = 10.0 + Math.random() * 20.0;
                    game.orWaitTotal = base;
                    game.orReadyAt[orIdx] = game.dayTime + base;
                    game.banner("OR" + (orIdx + 1) + " requested - wait " + (int) base + "s");
                }
                break;
            }
            case "supply": {
                if (hold > 0.3f) {
                    game.banner("Supplies grabbed (infinite supply, zero budget)");
                    eHoldTime = 0;
                }
                break;
            }
            case "hub_nurse_w": {
                if (hold > 0.3f) {
                    game.banner(nurseHint());
                    eHoldTime = 0;
                }
                break;
            }
            case "discharge": {

                if (hold > 1.0f) {
                    Npc p = firstDischargeableNear(20f);
                    if (p != null) {
                        boolean full = p.state == Npc.PatientState.TREATED;
                        int pay = dischargePatient(p);
                        game.banner(full ? "DISCHARGED! +$" + pay
                                : "Early discharge +$" + pay + " (treat first for full pay)");
                    } else {
                        game.banner("Bring a patient to the counter first");
                    }
                    eHoldTime = 0;
                }
                break;
            }
            default:
                if (s.id.startsWith("treat_")) {
                    Npc p = patientAtBed(s.id);
                    if (p != null && hold > 0.5f) {
                        if (p.orReady) {

                            p.state = Npc.PatientState.FOLLOWING;
                            p.lying = false;
                            p.sitting = false;
                            releaseBed(p);
                            p.y = 0.4f;
                            game.banner("Lead " + p.name + " up the stairs to the OR!");
                        } else if (!p.diagnosed) {

                            GameState.Illness di2 = game.findIllness(p.illness);
                            String want2 = di2 != null
                                    ? "stamp " + di2.organ + " + pick " + di2.symptom
                                    + " (" + GameState.symptomColorName(di2) + " badge)"
                                    : p.symptom;
                            game.banner("Undiagnosed - open the chart [Tab]: "
                                    + want2 + " (" + p.name + ")");
                        } else if (p.critical && p.criticalTimer <= 0) {
                            p.criticalTimer = 30f;
                            game.banner("Crash cart! " + p.name + " stabilized");
                        } else {
                            GameState.Illness ill = game.findIllness(p.illness);
                            int need = ill != null ? ill.steps.length : 2;
                            if (p.treatStepsDone < need) {
                                p.treatStepsDone++;
                                game.banner("Step " + p.treatStepsDone + "/" + need
                                        + ": " + ill.steps[Math.min(p.treatStepsDone - 1, ill.steps.length - 1)]);
                                if (p.treatStepsDone >= need) {
                                    if (p.orRequired) {
                                        game.banner("Needs the OR! Request at HUB or OR door");
                                    } else {
                                        p.state = Npc.PatientState.TREATED;
                                        p.lying = false;
                                        p.sitting = false;
                                        p.setPath(player.x, player.z);
                                        releaseBed(p);
                                        game.banner(p.name + " treated! Discharge at HUB");
                                    }
                                }
                            } else if (!p.orRequired && p.state == Npc.PatientState.IN_BED) {
                                p.state = Npc.PatientState.TREATED;
                                p.lying = false;
                                p.sitting = false;
                                p.setPath(player.x, player.z);
                                releaseBed(p);
                            }
                        }
                        eHoldTime = 0;
                    }
                }
                break;
        }
    }

    private final boolean[] bedTaken = new boolean[6];

    private Npc patientReadyForSurgery() {
        for (Npc p : game.patients) {
            if (p.orReady && p.diagnosed) return p;
        }
        return null;
    }

    private void updateSurgery(float dt) {
        for (Npc p : game.patients) {
            if (!p.orReady) continue;

            if (p.surgeryTimer > 0f) {
                p.surgeryTimer -= dt;
                if (p.surgeryTimer <= 0f) {
                    p.state = Npc.PatientState.TREATED;
                    p.sitting = false;
                    p.lying = false;
                    releaseBed(p);
                    if (p.orTable != null) {
                        p.x = p.orTable[0]; p.z = p.orTable[1] + 14f; p.y = FLOOR2_Y;
                        p.orTable = null;
                    }
                    p.orReady = false;
                    if (p.orSlot >= 0 && p.orSlot < game.orBusy.length) {
                        game.orBusy[p.orSlot] = false;
                    }
                    p.orSlot = -1;
                    p.setPath(player.x, player.z);
                    game.banner(p.name + " surgery complete! Take to HUB discharge");
                }
                return;
            }

            if (p.state != Npc.PatientState.IN_BED && p.state != Npc.PatientState.FOLLOWING) continue;

            for (int i = 0; i < OR_TABLES.length; i++) {
                float[] t = OR_TABLES[i];
                float pdx = p.x - t[0], pdz = p.z - t[1];
                float pdx2 = player.x - t[0], pdz2 = player.z - t[1];
                boolean patientNear = pdx * pdx + pdz * pdz < 16f * 16f;
                boolean playerNear = pdx2 * pdx2 + pdz2 * pdz2 < 15f * 15f;
                if (patientNear && playerNear && upstairs(player.y) && upstairs(p.y)
                        && !game.orBusy[i]) {

                    p.surgeryTimer = 8f;
                    p.orSlot = i;
                    game.orBusy[i] = true;
                    p.orTable = t;
                    p.state = Npc.PatientState.IN_BED;
                    p.setPath();
                    releaseBed(p);
                    p.lying = true;
                    p.sitting = false;
                    p.x = t[0]; p.z = t[1]; p.y = FLOOR2_Y + 0.35f;
                    game.banner("Surgery on " + p.name + " in progress...");
                    return;
                }
            }
        }
    }

    private void seatOccupant(MovableBed b, Npc p) {
        float fx = (float) Math.sin(b.yaw);
        float fz = (float) Math.cos(b.yaw);
        p.x = b.x - fx * 16.5f;
        p.z = b.z - fz * 16.5f;
        p.y = b.y + 8.9f;
        p.yaw = b.yaw - (float) (Math.PI * 0.5);
    }

    private void releaseBed(Npc p) {
        if (p.bedIndex >= 0 && p.bedIndex < bedTaken.length) {
            bedTaken[p.bedIndex] = false;
            if (p.bedIndex < beds.size() && beds.get(p.bedIndex).occupant == p) {
                beds.get(p.bedIndex).occupant = null;
            }
            p.bedIndex = -1;
        }
    }

    private void checkBedArrivals() {
        for (Npc p : game.patients) {
            if (p.state != Npc.PatientState.FOLLOWING) continue;
            for (int b = 0; b < BED_SPOTS.length && b < beds.size(); b++) {
                if (bedTaken[b]) continue;

                MovableBed bed = beds.get(b);
                float dx = player.x - bed.x, dz = player.z - bed.z;
                if (dx * dx + dz * dz < 400f && admitToBed(p, b)) {
                    game.banner(p.name + " in bed " + (b + 1)
                            + (p.critical ? " - CRITICAL!" : ""));
                    break;
                }
            }
        }
    }

    private static final float[][] BED_SPOTS = {
            {-137.0f, -119.0f}, {105.5f, -119.0f}, {-22.0f, -119.0f},
            {207.0f, -14.0f}, {-224.0f, -14.0f}, {1.5f, 25.0f},
    };

    private static final float[][] OR_TABLES = {
            {-148.4f, -99.3f}, {15.8f, -99.3f}, {179.2f, -99.3f},
    };

    private float bedY(int b) {
        return b < 5 ? 0.4f : 0.4f;
    }

    private void checkDischargeArrival() {
        for (Npc p : game.patients) {
            if (p.state == Npc.PatientState.DISCHARGED_WALK && p.pathDone()) {
                p.state = Npc.PatientState.GONE;
                releaseBed(p);
            }
        }
        game.patients.removeIf(n -> n.state == Npc.PatientState.GONE);
        allNpcs.removeIf(n -> n.state == Npc.PatientState.GONE);
    }

    private void checkDischargeAction() {
        if (chartOpen) {
            return;
        }
        for (Npc p : game.patients) {
            if (!isDischargeable(p)) {
                continue;
            }
            float dx = player.x - -8f, dz = player.z - 22f;
            boolean near = dx * dx + dz * dz < 256f;
            float pdx = p.x - -8f, pdz = p.z - 22f;
            boolean patientNear = pdx * pdx + pdz * pdz < 20f * 20f;
            if (near && patientNear && game.prompt.isEmpty()) {
                game.prompt = p.state == Npc.PatientState.TREATED
                        ? "Discharge " + p.name + " at the counter [Hold E]"
                        : "Discharge " + p.name + " early (partial pay) [Hold E]";
            }
            boolean eDown = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS
                    && (mouseCaptured || headlessDrive)
                    && game.screen == GameState.Screen.PLAYING;
            if (near && patientNear && eDown) {

                if (eHoldTime > 1.0f) {
                    boolean full = p.state == Npc.PatientState.TREATED;
                    int pay = dischargePatient(p);
                    game.banner(full ? "DISCHARGED! +$" + pay
                            : "Early discharge +$" + pay + " (treat first for full pay)");
                }
            }
        }
    }

    private void checkDayEnd() {
        if (game.goalDone >= game.goalTarget) {
            setChartOpen(false);
            game.screen = GameState.Screen.DAY_END;
        }
    }

    private void buildHud(int vw, int vh) {
        boolean playing = game.screen == GameState.Screen.PLAYING
                || game.screen == GameState.Screen.PAUSED;
        int waiting = 0, critical = 0;
        for (Npc p : game.patients) {
            if (p.state == Npc.PatientState.WAITING_LOBBY || p.state == Npc.PatientState.FOLLOWING) waiting++;
            if (p.critical && p.state == Npc.PatientState.IN_BED) critical++;
        }
        double now = glfwGetTime();
        float[] cursor = menuCursorFb(vw, vh);

        if (!headlessDrive && game.screen == GameState.Screen.MAIN_MENU) {
            hud.buildMenu(vw, vh, game.day, (int) game.money,
                    cursor[0], cursor[1], Hud.MODE_MAIN, now);
            hud.fadeOverlay(vw, vh, transitionFade());
            return;
        }
        if (!headlessDrive && game.screen == GameState.Screen.PAUSED) {
            hud.build(vw, vh, hotbarSelected, hotbarUnlocked,
                    game.money, game.goalDone, game.goalTarget, waiting,
                    critical > 0, "", now);
            hud.buildMenu(vw, vh, game.day, (int) game.money,
                    cursor[0], cursor[1], Hud.MODE_PAUSE, now);
            return;
        }
        if (!headlessDrive && game.screen == GameState.Screen.DAY_END) {
            hud.buildMenu(vw, vh, game.day, (int) game.money,
                    cursor[0], cursor[1], Hud.MODE_DAYEND, now);
            if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS) {
                nextDay();
            }
            return;
        }
        float holdFrac = -1f;
        if (holdActive && holdNeed > 0f && !chartOpen) {
            holdFrac = (float) (eHoldTime / holdNeed);
        }
        hud.build(vw, vh, hotbarSelected, hotbarUnlocked,
                game.money, game.goalDone, game.goalTarget, waiting,
                critical > 0, chartOpen ? "" : game.prompt, now, holdFrac,
                chartOpen ? "" : objective());
        if (!game.banner.isEmpty()) {
            hud.banner(game.banner, vw, vh, 26f, new float[]{0.44f, 0.93f, 0.55f, 1f});
        }
        if (chartOpen && player != null) {
            buildChartOverlay(vw, vh, cursor);
        }
        hud.fadeOverlay(vw, vh, transitionFade());
        if (fadeOutT > 0) {
            hud.fadeOverlay(vw, vh, (float) (fadeOutT / FADE_OUT_DUR) * 0.9f);
        }
    }

    private void buildChartOverlay(int vw, int vh, float[] cursor) {
        if (!validateChartPatient()) {

            hud.buildChart(vw, vh, cursor[0], cursor[1],
                    "NO PATIENT IN RANGE", "Walk up to a bed, follower, or lobby patient",
                    "The chart locks onto the nearest patient",
                    "Chart stays open - get closer", true,
                    GameState.organs(), GameState.issues(),
                    -1, -1, false, "walk closer, then stamp + issue + diagnose",
                    new String[]{"Stand near a patient first"}, 0, false);
            return;
        }
        Npc p = chartPatient;
        GameState.Illness ill = game.findIllness(p.illness);
        String stateLine = "" + p.state
                + (p.bedIndex >= 0 ? " (bed " + (p.bedIndex + 1) + ")" : "");
        String symptomLine = ill != null
                ? "Symptom: " + p.symptom + " - " + GameState.symptomColorName(ill)
                + " badge on the " + locName(GameState.visualFor(ill))
                + " = stamp " + ill.organ + " + pick " + ill.symptom
                : "Symptom: " + p.symptom;
        String statusLine = p.critical ? "CRITICAL - stabilize fast!" : "Stable";
        if (!p.diagnosed) {
            statusLine += " - undiagnosed";
        }
        String[] steps = ill != null ? ill.steps : new String[]{"Treat", "Treat"};
        hud.buildChart(vw, vh, cursor[0], cursor[1],
                p.name, stateLine, symptomLine, statusLine, p.critical,
                GameState.organs(), GameState.issues(),
                chartOrgan, chartIssue, p.diagnosed,
                nurseAdvice(p, ill),
                steps, Math.min(p.treatStepsDone, steps.length),
                chartCanDischarge(p));
    }

    private void saveScreenshot(String path, byte[] bgra) {
        int w = renderer.swapWidth(), h = renderer.swapHeight();
        java.nio.ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            int s = i * 4;
            rgba.put(bgra[s + 2]);
            rgba.put(bgra[s + 1]);
            rgba.put(bgra[s]);
            rgba.put(bgra[s + 3]);
        }
        rgba.flip();
        org.lwjgl.stb.STBImageWrite.stbi_write_png(path, w, h, 4, rgba, w * 4);
        MemoryUtil.memFree(rgba);
    }

    private void runPlaytest(float dt, int frames) {
        if (player == null) {
            return;
        }
        autotestTimer += dt;

        float[][] wp = {
                {-8.4f, 168f, 180f},
                {-14f, 158f, 180f},
                {-8f, 120f, 0f},
                {-48f, 60f, 270f},
                {-48f, 20f, 0f},
                {-8f, 8f, 90f},
                {40f, 8f, 90f},
                {168f, 8f, 90f},
                {168f, 60f, 0f},
                {168f, 100f, 0f},
                {80f, 130f, 180f},
                {-8f, 120f, 180f},
        };
        if (autotestPhase < wp.length) {
            float[] t = wp[autotestPhase];
            float dx = t[0] - player.x;
            float dz = t[1] - player.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            float headYaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            drive(headYaw, dist > 1.2f, false, false, false, true);
            if (dist < 6f) {
                autotestLog += String.format("phase %d reached (%.1f,%.1f) y=%.1f%n",
                        autotestPhase, player.x, player.z, player.y);
                autotestPhase++;
                resetInput();
            }
        } else {
            resetInput();
        }
        if (frames == 120 || (frames > 0 && frames % 900 == 0)) {
            byte[] bgra = renderer.captureFrame();
            saveScreenshot(String.format("/tmp/playtest_%04d.png", frames), bgra);
            autotestLog += "screenshot /tmp/playtest_" + frames + ".png at ("
                    + (int) player.x + "," + (int) player.z + ") y=" + (int) player.y + "\n";
        }
    }

    private MapFixes.Spot spotById(String id) {
        for (MapFixes.Spot s : spots) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    private void tourShot(String name) {
        byte[] bgra = renderer.captureFrame();
        saveScreenshot("/tmp/" + name + ".png", bgra);
    }

    private void tourCheck(String stage, boolean ok, String detail) {
        tourReport.append(String.format("%s %s %s%n", ok ? "PASS" : "FAIL", stage, detail));
        System.out.printf("TOUR %s %s %s%n", ok ? "PASS" : "FAIL", stage, detail);
    }

    private void runTour(float dt, int frames) {
        if (player == null) {
            return;
        }
        resetInput();
        mouseCaptured = true;
        if (frames == 0) {
            game.screen = GameState.Screen.PLAYING;
            tourP = game.spawnPatient();
            sendToLobby(tourP);
            allNpcs.add(tourP);

            tourP.illness = "Collapsed Lung";
            tourP.symptom = "Collapse";
            GameState.Illness tourIll = game.findIllness("Collapsed Lung");
            float[] tourRgb = GameState.symptomColor(tourIll);
            tourP.setSymptomMarker(GameState.visualFor(tourIll), tourRgb[0], tourRgb[1], tourRgb[2]);
            tourP.orRequired = true;
            tourP.diagnosed = false;
            tourStage = 0;
            tourStageFrame = 0;
        }
        tourStageFrame++;
        MovableBed bed0 = beds.get(0);

        switch (tourStage) {
            case 0: {
                if (tourStageFrame == 60) {
                    player.x = -8f; player.z = 150f; player.y = 2f;
                    player.setYaw((float) Math.PI);
                    player.setPitch(-0.05f);
                }
                if (tourStageFrame == 100) {
                    tourShot("tour_0_lobby");
                }
                if (tourStageFrame == 120) {
                    player.x = -8f; player.z = 48f; player.y = 0.8f;
                }
                if (tourStageFrame >= 140) {
                    doSpotAction(spotById("checkin"), 2.0f);
                    if (tourP.state == Npc.PatientState.FOLLOWING) {
                        tourCheck("checkin", true, "P FOLLOWING");
                        tourStage = 1;
                        tourStageFrame = 0;
                    } else if (tourStageFrame > 300) {
                        tourCheck("checkin", false, "state=" + tourP.state);
                        tourStage = 1;
                        tourStageFrame = 0;
                    }
                }
                break;
            }
            case 1: {
                player.x = bed0.x;
                player.z = bed0.z;
                player.y = 0.6f;
                if (tourP.state == Npc.PatientState.IN_BED && tourP.bedIndex == 0) {
                    tourCheck("bed-arrival", true,
                            String.format("IN_BED@(%.1f,%.1f,%.1f) occ=%s", tourP.x, tourP.y, tourP.z,
                                    bed0.occupant != null ? bed0.occupant.name : "none"));
                    player.x = -124f; player.z = -101f; player.y = 0.4f;
                    player.setYaw((float) Math.atan2(bed0.x - player.x, bed0.z - player.z));
                    player.setPitch(-0.08f);
                    tourStage = 2;
                    tourStageFrame = 0;
                } else if (tourStageFrame > 1500) {
                    tourCheck("bed-arrival", false,
                            String.format("state=%s pos=(%.1f,%.1f,%.1f)", tourP.state,
                                    tourP.x, tourP.y, tourP.z));
                    tourStage = 2;
                    tourStageFrame = 0;
                }
                break;
            }
            case 2: {
                if (tourStageFrame == 60) {
                    tourShot("tour_1_bed");
                }
                if (tourStageFrame == 80) {

                    chartPatient = tourP;
                    String[] organs = GameState.organs();
                    String[] issues = GameState.issues();
                    GameState.Illness di = game.findIllness(tourP.illness);
                    chartOrgan = -1;
                    chartIssue = -1;
                    for (int oi = 0; oi < organs.length; oi++) {
                        if (organs[oi].equals(di.organ)) {
                            chartOrgan = oi;
                        }
                    }
                    for (int ii = 0; ii < issues.length; ii++) {
                        if (issues[ii].equals(di.symptom)) {
                            chartIssue = ii;
                        }
                    }
                    doChartDiagnose();
                }
                if (tourStageFrame >= 100 && tourStageFrame % 40 == 0) {
                    doSpotAction(spotById("treat_1"), 1.0f);
                }
                GameState.Illness ill = game.findIllness(tourP.illness);
                int need = ill != null ? ill.steps.length : 2;
                if (tourP.diagnosed && tourP.treatStepsDone >= need) {
                    tourCheck("diagnose-treat", true,
                            "diag steps=" + tourP.treatStepsDone + "/" + need);
                    setChartOpen(true);
                    tourStage = 3;
                    tourStageFrame = 0;
                } else if (tourStageFrame > 600) {
                    tourCheck("diagnose-treat", false,
                            "diag=" + tourP.diagnosed + " steps=" + tourP.treatStepsDone);
                    tourStage = 3;
                    tourStageFrame = 0;
                }
                break;
            }
            case 3: {
                if (tourStageFrame == 80) {
                    tourShot("tour_2_chart");
                    setChartOpen(false);
                    doSpotAction(spotById("hub_nurse_e"), 2.0f);
                    tourCheck("or-request", tourP.orRequested,
                            "wait=" + (int) game.orWaitTotal + "s");
                }
                if (tourStageFrame > 100) {
                    tourStage = 4;
                    tourStageFrame = 0;
                }
                break;
            }
            case 4: {
                if (tourP.orReady) {
                    tourCheck("or-wait", true, "OR ready flag set by tick");

                    player.x = -124f; player.z = -101f; player.y = 0.4f;
                    doSpotAction(spotById("treat_1"), 1.0f);
                    if (tourP.state == Npc.PatientState.FOLLOWING) {
                        tourCheck("or-leadout", true, "released to FOLLOWING");
                    } else {
                        tourCheck("or-leadout", false, "state=" + tourP.state);
                    }
                    player.x = -148.4f; player.z = -90f; player.y = FLOOR2_Y;
                    tourP.x = -148.4f; tourP.z = -92f; tourP.y = FLOOR2_Y;
                    tourP.setPath();
                    tourP.navAge = 99f;
                    tourStage = 5;
                    tourStageFrame = 0;
                } else if (tourStageFrame > 2400) {
                    tourCheck("or-wait", false, "orReady never set");
                    tourStage = 5;
                    tourStageFrame = 0;
                }
                break;
            }
            case 5: {
                player.x = -148.4f; player.z = -90f; player.y = FLOOR2_Y;
                if (tourP.state == Npc.PatientState.TREATED && !tourP.orReady) {
                    tourCheck("surgery", true, "TREATED after op");
                    tourShot("tour_3_or");
                    tourStage = 6;
                    tourStageFrame = 0;
                } else if (tourStageFrame > 900) {
                    tourCheck("surgery", false,
                            String.format("state=%s timer=%.1f pos=(%.1f,%.1f,%.1f)",
                                    tourP.state, tourP.surgeryTimer, tourP.x, tourP.y, tourP.z));
                    tourStage = 6;
                    tourStageFrame = 0;
                }
                break;
            }
            case 6: {
                player.x = -8f; player.z = 22f; player.y = 0.8f;
                if (tourStageFrame % 300 == 0) {
                    float[] tg = tourP.debugTarget();
                    System.out.printf("TRAIL %d (%.1f,%.1f,%.1f) done=%s tag=%d %s tgt=%s%n",
                            tourStageFrame, tourP.x, tourP.y, tourP.z,
                            tourP.pathDone(), tourP.planTag, tourP.debugPathKind(),
                            tg != null ? String.format("(%.1f,%.1f,y%.1f)[%d/%d]",
                                    tg[0], tg[1], tg[2], (int) tg[4], (int) tg[3]) : "none");
                }
                boolean down = tourP.y < 30f;
                float dx = tourP.x + 8f, dz = tourP.z - 22f;
                boolean near = dx * dx + dz * dz < 15f * 15f;
                if (tourP.state == Npc.PatientState.TREATED && down && near) {
                    int pay = dischargePatient(tourP);
                    tourCheck("discharge", true, "+$" + pay + " goal=" + game.goalDone);
                    tourStage = 7;
                    tourStageFrame = 0;
                } else if (tourStageFrame > 3600) {
                    tourCheck("discharge", false,
                            String.format("state=%s y=%.1f pos=(%.1f,%.1f) done=%s navAge=%.1f stairAge=%.1f",
                                    tourP.state, tourP.y, tourP.x, tourP.z,
                                    tourP.pathDone(), tourP.navAge, tourP.stairAge));
                    tourStage = 7;
                    tourStageFrame = 0;
                }
                break;
            }
            default: {
                if (tourStageFrame == 200) {
                    tourShot("tour_4_exit");
                }
                if (tourStageFrame > 260) {
                    System.out.println("TOUR REPORT:\n" + tourReport);
                    glfwSetWindowShouldClose(window, true);
                }
                break;
            }
        }
    }

    private void runAutotest(float dt, int frames) {
        if (player == null) {
            return;
        }
        autotestTimer += dt;

        float[][] wp = {
                {-8.4f, 190f, 180f},
                {-14f, 180f, 180f},
                {-14f, 172f, 180f},
                {-14f, 158f, 180f},
                {-14f, 140f, 180f},
        };
        if (autotestPhase < wp.length) {
            float[] t = wp[autotestPhase];
            float dx = t[0] - player.x;
            float dz = t[1] - player.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            float headYaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            drive(headYaw, dist > 0.8f, false, false, false, true);
            if (dist < 4f) {
                autotestLog += String.format(
                        "phase %d reached (%.1f,%.1f) door DMain open-state tested%n",
                        autotestPhase, player.x, player.z);
                autotestPhase++;
                resetInput();
                if (autotestPhase >= wp.length) {
                    Doors.Door d = doors.findNear(player.x, player.z, 1000f);
                    Doors.Door main = null;
                    for (Doors.Door dd : doors.all()) {
                        if (dd.name.equals("DMain")) {
                            main = dd;
                        }
                    }
                    if (main != null) {
                        autotestLog += String.format(
                                "DMain open=%s leafOpen=%.2f (walked through)%n",
                                main.open, main.leaves[0].open);
                    }
                }
            }
            if (frames % 120 == 0) {
                autotestLog += String.format("phase %d pos=(%.1f,%.1f,%.1f)%n",
                        autotestPhase, player.x, player.y, player.z);
                String posKey = String.format("%.1f,%.1f", player.x, player.z);
                if (posKey.equals(lastAutotestPos)) {
                    autotestLog += "  STUCK at " + posKey + "; collision probe:\n"
                            + world.debugProbe(player.x, player.y, player.z,
                                    player.radius(), player.height(), player.stepUp());
                }
                lastAutotestPos = posKey;
            }
        } else {
            resetInput();
        }
    }

    private void cleanup() {
        if (font != null) {
            font.close();
        }
        if (renderer != null) {
            renderer.close();
        }
        glfwDestroyWindow(window);
        if (cursorCb != null) cursorCb.free();
        if (mouseCb != null) mouseCb.free();
        if (fbSizeCb != null) fbSizeCb.free();
        if (errCb != null) errCb.free();
        glfwTerminate();
    }

    private static final java.util.Set<String> DOOR_OBJECTS = java.util.Set.of(
            "DMain_LeafL", "DMain_LeafR",
            "DT1_Leaf", "DT1_Port_Glass", "DT1_Port_Ring",
            "DT2_Leaf", "DT2_Port_Glass", "DT2_Port_Ring",
            "DT3_Leaf", "DT3_Port_Glass", "DT3_Port_Ring",
            "DT4_Leaf", "DT4_Port_Glass", "DT4_Port_Ring",
            "DT5_Leaf", "DT5_Port_Glass", "DT5_Port_Ring",
            "OR1_Door_Leaf", "OR1_Door_Port", "OR1_Door_Kick",
            "OR2_Door_Leaf", "OR2_Door_Port", "OR2_Door_Kick",
            "OR3_Door_Leaf", "OR3_Door_Port", "OR3_Door_Kick");

    private static final Map<String, String[]> DOOR_GROUPS = Map.of(
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

    private static final java.util.Set<String> IGNORE_OBJECTS = java.util.Set.of("X1");

    private static final float[] MAIN_DOOR_HOLE = {
            -19.86f, 3.11f,
            0.0f, 57.8f,

            160.5f, 164.0f};

    private static void carveFacadeDoorway(ObjLoader.Model m) {
        float hx0 = MAIN_DOOR_HOLE[0], hx1 = MAIN_DOOR_HOLE[1];
        float hy0 = MAIN_DOOR_HOLE[2], hy1 = MAIN_DOOR_HOLE[3];
        float hz0 = MAIN_DOOR_HOLE[4], hz1 = MAIN_DOOR_HOLE[5];
        int splitParts = 0;
        int trisBefore = 0, trisAfter = 0;
        for (int pi = 0; pi < m.parts.size(); pi++) {
            ObjLoader.MeshPart p = m.parts.get(pi);
            float[] pos = p.positions;
            float[] col = p.colors;
            int origTris = pos.length / 9;
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
            trisBefore += origTris;
            trisAfter += out.size();
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
        if (splitParts > 0) {
            System.out.println("Carved main-doorway hole into " + splitParts
                    + " part(s) (" + trisBefore + " -> " + trisAfter + " triangles)");
            m.rebuildGlobalBounds();
        }
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
}
