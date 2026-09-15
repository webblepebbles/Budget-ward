package game.hud;

import game.render.FontAtlas;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Hud implements AutoCloseable {

    private float[] pos = new float[4096];
    private float[] uv = new float[4096];
    private float[] col = new float[8192];
    private int vCount;

    private final FontAtlas font;

    private static final int HOTBAR_SLOTS = 5;
    private static final float SLOT = 64f;
    private static final float SLOT_PAD = 7f;

    private static final float[] PANEL = {0.07f, 0.075f, 0.10f, 0.72f};
    private static final float[] PANEL_SOLID = {0.07f, 0.075f, 0.10f, 0.96f};
    private static final float[] BORDER_DIM = {0.42f, 0.45f, 0.52f, 0.85f};
    private static final float[] BORDER_HOT = {1.00f, 0.83f, 0.35f, 1.00f};
    private static final float[] TEXT_WHITE = {0.96f, 0.97f, 1.00f, 1.00f};
    private static final float[] TEXT_GOLD = {1.00f, 0.84f, 0.30f, 1.00f};
    private static final float[] TEXT_GREEN = {0.44f, 0.93f, 0.55f, 1.00f};
    private static final float[] TEXT_RED = {1.00f, 0.32f, 0.30f, 1.00f};
    private static final float[] TEXT_LOCKED = {0.52f, 0.54f, 0.60f, 0.85f};
    private static final float[] SHADOW = {0.00f, 0.00f, 0.00f, 0.65f};
    private static final float[] SLOT_FILL = {0.11f, 0.12f, 0.16f, 0.90f};
    private static final float[] SLOT_FILL_SEL = {0.20f, 0.19f, 0.13f, 0.95f};
    private static final float[] BAR_TRACK = {0.22f, 0.23f, 0.27f, 1.00f};

    private static final float[][] SLOT_TINTS = {
            {0.13f, 0.38f, 0.42f, 0.92f},
            {0.48f, 0.24f, 0.18f, 0.92f},
            {0.18f, 0.36f, 0.55f, 0.92f},
            {0.48f, 0.38f, 0.14f, 0.92f},
            {0.48f, 0.20f, 0.34f, 0.92f},
    };
    private static final float[][] SLOT_TINTS_SEL = {
            {0.20f, 0.55f, 0.58f, 0.97f},
            {0.68f, 0.36f, 0.24f, 0.97f},
            {0.26f, 0.52f, 0.78f, 0.97f},
            {0.68f, 0.55f, 0.20f, 0.97f},
            {0.68f, 0.30f, 0.48f, 0.97f},
    };

    private static final float[] MENU_DIM = {0.02f, 0.04f, 0.09f, 0.52f};
    private static final float[] CARD_FILL = {0.98f, 0.94f, 0.80f, 0.97f};
    private static final float[] CARD_EDGE = {0.35f, 0.30f, 0.20f, 1.00f};
    private static final float[] CARD_TEXT = {0.18f, 0.15f, 0.10f, 1.00f};
    private static final float[] CARD_SUB = {0.42f, 0.36f, 0.25f, 1.00f};
    private static final float[] BTN_GOLD = {1.00f, 0.80f, 0.28f, 1.00f};
    private static final float[] BTN_GOLD_HOVER = {1.00f, 0.88f, 0.45f, 1.00f};
    private static final float[] BTN_DARK = {0.16f, 0.14f, 0.10f, 1.00f};
    private static final float[] BTN_DARK_HOVER = {0.28f, 0.24f, 0.16f, 1.00f};
    private static final float[] FADE_BLACK = {0.00f, 0.00f, 0.00f, 1.00f};

    public static final int MODE_MAIN = 0;
    public static final int MODE_PAUSE = 1;
    public static final int MODE_DAYEND = 2;

    private static final String[] SLOT_NAMES = {
            "Chart", "Pump", "Ice", "Tape", "Fluid"
    };

    public Hud(FontAtlas font) {
        this.font = font;
    }

    private static final int MAX_VERTS = 6000;
    private boolean overflow;

    private void ensure(int extra) {
        if ((vCount + extra) * 2 > pos.length) {
            int cap = Math.max(pos.length * 2, (vCount + extra) * 2);
            pos = Arrays.copyOf(pos, cap);
            uv = Arrays.copyOf(uv, cap);
            col = Arrays.copyOf(col, cap * 2);
        }
    }

    private void vert(float px, float py, float u, float v, float r, float g, float b, float a) {
        if (vCount >= MAX_VERTS) {
            overflow = true;
            return;
        }
        ensure(1);
        int pi = vCount * 2;
        pos[pi] = px;
        pos[pi + 1] = py;
        uv[pi] = u;
        uv[pi + 1] = v;
        int ci = vCount * 4;
        col[ci] = r; col[ci + 1] = g; col[ci + 2] = b; col[ci + 3] = a;
        vCount++;
    }

    private void tri(float ax, float ay, float bx, float by, float cx, float cy,
                     float au, float av, float bu, float bv, float cu, float cv,
                     float[] c) {
        vert(ax, ay, au, av, c[0], c[1], c[2], c[3]);
        vert(bx, by, bu, bv, c[0], c[1], c[2], c[3]);
        vert(cx, cy, cu, cv, c[0], c[1], c[2], c[3]);
    }

    public void rect(float x, float y, float w, float h, float[] color) {
        float u = 2f, v = 2f;
        float x1 = x + w, y1 = y + h;
        tri(x, y, x1, y, x1, y1, u, v, u, v, u, v, color);
        tri(x, y, x1, y1, x, y1, u, v, u, v, u, v, color);
    }

    public void rrect(float x, float y, float w, float h, float r, float[] color) {
        float u = 2f, v = 2f;
        r = Math.max(0f, Math.min(r, Math.min(w, h) * 0.5f));
        if (r < 1f) {
            rect(x, y, w, h, color);
            return;
        }
        float x0 = x, x1 = x + w, y0 = y, y1 = y + h;

        tri(x0 + r, y0, x1 - r, y0, x1 - r, y1, u, v, u, v, u, v, color);
        tri(x0 + r, y0, x1 - r, y1, x0 + r, y1, u, v, u, v, u, v, color);
        tri(x0, y0 + r, x1, y0 + r, x1, y1 - r, u, v, u, v, u, v, color);
        tri(x0, y0 + r, x1, y1 - r, x0, y1 - r, u, v, u, v, u, v, color);

        float[][] centers = {
                {x0 + r, y0 + r}, {x1 - r, y0 + r}, {x1 - r, y1 - r}, {x0 + r, y1 - r},
        };
        float[] starts = {(float) Math.PI, (float) (Math.PI * 1.5), 0f, (float) (Math.PI * 0.5)};
        int segs = 3;
        for (int c = 0; c < 4; c++) {
            float cx = centers[c][0], cy = centers[c][1], a0 = starts[c];
            for (int s = 0; s < segs; s++) {
                float a1 = a0 + (float) (Math.PI * 0.5) * s / segs;
                float a2 = a0 + (float) (Math.PI * 0.5) * (s + 1) / segs;
                float ax = cx + (float) Math.cos(a1) * r, ay = cy + (float) Math.sin(a1) * r;
                float bx = cx + (float) Math.cos(a2) * r, by = cy + (float) Math.sin(a2) * r;
                tri(cx, cy, ax, ay, bx, by, u, v, u, v, u, v, color);
            }
        }
    }

    public void panel(float x, float y, float w, float h, float[] fill, float[] edge) {
        rrect(x, y, w, h, 10f, edge);
        rrect(x + 2, y + 2, w - 4, h - 4, 8f, fill);
    }

    public float text(String s, float x, float y, float px, float[] color) {
        return textImpl(s, x, y, px, color, true);
    }

    public float textPlain(String s, float x, float y, float px, float[] color) {
        return textImpl(s, x, y, px, color, false);
    }

    private float textImpl(String s, float x, float y, float px, float[] color, boolean shadow) {
        if (shadow) {
            textRaw(s, x + 2, y + 2, px, SHADOW);
        }
        return textRaw(s, x, y, px, color);
    }

    private float textRaw(String s, float x, float y, float px, float[] color) {
        ByteBuffer bytes = StandardCharsets.US_ASCII.encode(s);
        float k = px / 64f;
        float pen = x;
        for (int i = 0; i < bytes.remaining(); i++) {
            int cp = bytes.get(i) & 0xFF;
            if (cp >= 128) {
                cp = 167;
            }
            FontAtlas.Glyph g = font.glyph(cp);
            float adv = g.advance * k;
            if (g.w > 0 && g.h > 0) {
                float gx = pen + g.xoff * k;
                float gy = y + (font.ascent + g.yoff) * k;
                float gw = g.w * k, gh = g.h * k;
                float u0 = g.u0, v0 = g.v0, u1 = g.u1, v1 = g.v1;
                tri(gx, gy, gx + gw, gy, gx + gw, gy + gh, u0, v0, u1, v0, u1, v1, color);
                tri(gx, gy, gx + gw, gy + gh, gx, gy + gh, u0, v0, u1, v1, u0, v1, color);
            }
            pen += adv;
        }
        return pen - x;
    }

    public float textCentered(String s, float cx, float y, float px, float[] color) {
        float w = font.measure(s, px);
        text(s, cx - w * 0.5f, y, px, color);
        return w;
    }

    public float textCenteredPlain(String s, float cx, float y, float px, float[] color) {
        float w = font.measure(s, px);
        textPlain(s, cx - w * 0.5f, y, px, color);
        return w;
    }

    public void build(int vw, int vh,
                      int hotbarSelected,
                      boolean[] hotbarUnlocked,
                      double money,
                      int goalDone, int goalTarget,
                      int patientsWaiting,
                      boolean criticalActive,
                      String prompt) {
        build(vw, vh, hotbarSelected, hotbarUnlocked, money, goalDone, goalTarget,
                patientsWaiting, criticalActive, prompt, System.nanoTime() * 1e-9);
    }

    public void build(int vw, int vh,
                      int hotbarSelected,
                      boolean[] hotbarUnlocked,
                      double money,
                      int goalDone, int goalTarget,
                      int patientsWaiting,
                      boolean criticalActive,
                      String prompt,
                      double time) {
        build(vw, vh, hotbarSelected, hotbarUnlocked, money, goalDone, goalTarget,
                patientsWaiting, criticalActive, prompt, time, -1f, "");
    }

    public void build(int vw, int vh,
                      int hotbarSelected,
                      boolean[] hotbarUnlocked,
                      double money,
                      int goalDone, int goalTarget,
                      int patientsWaiting,
                      boolean criticalActive,
                      String prompt,
                      double time,
                      float holdFrac) {
        build(vw, vh, hotbarSelected, hotbarUnlocked, money, goalDone, goalTarget,
                patientsWaiting, criticalActive, prompt, time, holdFrac, "");
    }

    public void build(int vw, int vh,
                      int hotbarSelected,
                      boolean[] hotbarUnlocked,
                      double money,
                      int goalDone, int goalTarget,
                      int patientsWaiting,
                      boolean criticalActive,
                      String prompt,
                      double time,
                      float holdFrac,
                      String objective) {
        vCount = 0;
        overflow = false;
        float margin = 20f;

        crosshair(vw, vh);

        float hbW = HOTBAR_SLOTS * SLOT + (HOTBAR_SLOTS - 1) * SLOT_PAD;
        float hbX = (vw - hbW) * 0.5f;
        float hbY = vh - SLOT - 26f;
        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            float sx = hbX + i * (SLOT + SLOT_PAD);
            boolean selected = i == hotbarSelected;
            float[] fill = hotbarUnlocked[i]
                    ? (selected ? SLOT_TINTS_SEL[i] : SLOT_TINTS[i])
                    : new float[]{0.08f, 0.08f, 0.10f, 0.80f};
            float[] edge = selected ? BORDER_HOT : BORDER_DIM;

            float sy = selected ? hbY - 4f : hbY;
            panel(sx, sy, SLOT, SLOT, fill, edge);

            textPlain(String.valueOf(i + 1), sx + 7, sy + 6, 18f,
                    selected ? BORDER_HOT : TEXT_LOCKED);

            float[] nameCol = hotbarUnlocked[i] ? TEXT_WHITE : TEXT_LOCKED;
            float namePx = 16f;
            float nw = font.measure(SLOT_NAMES[i], namePx);
            text(SLOT_NAMES[i], sx + (SLOT - nw) * 0.5f, sy + SLOT - 26f, namePx, nameCol);
        }

        String moneyStr = "$" + String.format("%.0f", money);
        float moneyPx = 34f;
        float mw = font.measure(moneyStr, moneyPx);
        String goalStr = "Goal  " + goalDone + " / " + goalTarget;
        float goalPx = 20f;
        float goalBarW = Math.max(220f, mw), goalBarH = 14f;
        float cardW = Math.max(mw, Math.max(font.measure(goalStr, goalPx), goalBarW)) + 30;
        float cardH = moneyPx + goalPx + goalBarH + 46;
        panel(margin, margin, cardW, cardH, PANEL_SOLID, BORDER_DIM);

        text(moneyStr, margin + 15, margin + 10, moneyPx, TEXT_GOLD);
        rect(margin + 15, margin + moneyPx + 20, cardW - 30, 2f, BORDER_HOT);
        text(goalStr, margin + 15, margin + moneyPx + 28, goalPx, TEXT_WHITE);
        float frac = goalTarget > 0 ? Math.min(1f, goalDone / (float) goalTarget) : 0f;
        float barX = margin + 15, barY = margin + moneyPx + goalPx + 34;
        rect(barX, barY, goalBarW, goalBarH, BAR_TRACK);
        if (frac > 0f) {
            rect(barX, barY, goalBarW * frac, goalBarH, TEXT_GREEN);
        }

        String patStr = "Patients  " + patientsWaiting;
        float patPx = 26f;
        float patW = font.measure(patStr, patPx);
        panel(vw - margin - patW - 44, margin, patW + 44, patPx + 20, PANEL_SOLID, BORDER_DIM);
        text(patStr, vw - margin - patW - 22, margin + 10, patPx,
                patientsWaiting > 0 ? TEXT_WHITE : TEXT_LOCKED);

        float guideY = margin;
        if (criticalActive) {
            float pulse = 0.72f + 0.28f * (float) Math.sin(time * 6.0);
            float[] fill = {0.34f * pulse + 0.10f, 0.04f, 0.04f, 0.90f};
            String crit = "!! PATIENT CRITICAL !!";
            float critPx = 30f;
            float cw = font.measure(crit, critPx);
            panel(vw * 0.5f - cw * 0.5f - 18, margin, cw + 36, critPx + 20, fill, TEXT_RED);
            textCentered(crit, vw * 0.5f, margin + 10, critPx, TEXT_RED);
            guideY += critPx + 32f;
        }

        if (objective != null && !objective.isEmpty()) {
            float objPx = 23f;
            float ow = font.measure(objective, objPx);
            float maxW = vw - 420f;
            if (ow > maxW && ow > 0f) {
                objPx = Math.max(15f, objPx * maxW / ow);
                ow = font.measure(objective, objPx);
            }
            panel(vw * 0.5f - ow * 0.5f - 18, guideY, ow + 36, objPx + 20,
                    PANEL_SOLID, BORDER_HOT);
            textCentered(objective, vw * 0.5f, guideY + 10, objPx, TEXT_GOLD);
        }

        if (prompt != null && !prompt.isEmpty()) {
            float promptPx = 22f;
            float pw = font.measure(prompt, promptPx);
            float px = (vw - pw) * 0.5f;
            float py = hbY - promptPx - 28f;
            panel(px - 16, py - 10, pw + 32, promptPx + 20, PANEL_SOLID, BORDER_HOT);
            text(prompt, px, py, promptPx, TEXT_WHITE);

            if (holdFrac >= 0f) {
                float bw = pw + 32, bx = px - 16, by = py + promptPx + 16;
                float f = Math.max(0f, Math.min(1f, holdFrac));
                rrect(bx, by, bw, 12f, 6f, BAR_TRACK);
                if (f > 0f) {
                    rrect(bx, by, bw * f, 12f, 6f,
                            new float[]{0.25f, 0.80f, 0.85f, 1f});
                }
            }
        }

        if (overflow) {
            System.err.println("HUD vertex budget exceeded; some widgets dropped");
        }
    }

    public static float[] menuCard(int vw, int vh) {
        float cw = Math.min(600f, vw * 0.86f);
        float ch = Math.min(500f, vh * 0.78f);
        float cx = vw * 0.5f;
        return new float[]{cx - cw * 0.5f, vh * 0.5f - ch * 0.5f, cw, ch};
    }

    public static float[] menuPrimaryRect(int vw, int vh) {
        float[] card = menuCard(vw, vh);
        float bw = card[2] - 120f, bh = 72f;
        float bx = card[0] + (card[2] - bw) * 0.5f;
        float by = card[1] + card[3] - 218f;
        return new float[]{bx, by, bw, bh};
    }

    public static float[] menuSecondaryRect(int vw, int vh) {
        float[] card = menuCard(vw, vh);
        float bw = card[2] - 120f, bh = 56f;
        float bx = card[0] + (card[2] - bw) * 0.5f;
        float by = card[1] + card[3] - 130f;
        return new float[]{bx, by, bw, bh};
    }

    private static boolean inside(float mx, float my, float[] r) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    public static int pickMenuButton(float mx, float my, int vw, int vh) {
        if (inside(mx, my, menuPrimaryRect(vw, vh))) {
            return 1;
        }
        if (inside(mx, my, menuSecondaryRect(vw, vh))) {
            return 2;
        }
        return 0;
    }

    public static String menuPrimaryLabel(int mode) {
        switch (mode) {
            case MODE_PAUSE: return "RESUME";
            case MODE_DAYEND: return "NEXT DAY";
            default: return "START SHIFT";
        }
    }

    public void buildMenu(int vw, int vh, int day, int money) {
        buildMenu(vw, vh, day, money, -1f, -1f, MODE_MAIN, System.nanoTime() * 1e-9);
    }

    public void buildMenu(int vw, int vh, int day, int money,
                          float mouseX, float mouseY, int mode, double time) {
        vCount = 0;
        overflow = false;

        rect(0, 0, vw, vh, MENU_DIM);

        float[] card = menuCard(vw, vh);
        float cx = vw * 0.5f;

        rect(card[0] + 8, card[1] + 12, card[2], card[3], SHADOW);
        panel(card[0], card[1], card[2], card[3], CARD_FILL, CARD_EDGE);

        String title = mode == MODE_PAUSE ? "PAUSED" : mode == MODE_DAYEND ? "DAY COMPLETE" : "BUDGET WARD";
        float tPx = 68f;

        rrect(card[0] + 24, card[1] + 14, card[2] - 48, tPx + 56, 14f,
                new float[]{0.16f, 0.45f, 0.50f, 1.0f});
        float[][] dotCols = {
                new float[]{1.0f, 0.55f, 0.40f, 1f}, new float[]{1.0f, 0.85f, 0.35f, 1f},
                new float[]{0.45f, 0.90f, 0.55f, 1f}, new float[]{0.55f, 0.65f, 1.0f, 1f},
        };
        for (int di = 0; di < 4; di++) {
            float dx = cx - 45f + di * 30f;
            float dy = card[1] + 26 + tPx + 30f;
            rrect(dx, dy, 18f, 10f, 5f, dotCols[di]);
        }
        textCentered(title, cx, card[1] + 26, tPx, new float[]{1f, 0.98f, 0.92f, 1f});
        String sub = mode == MODE_MAIN ? "a very cheap hospital sim"
                : mode == MODE_PAUSE ? "the patients can wait (briefly)"
                : "the board is pleased. mostly.";
        textCenteredPlain(sub, cx, card[1] + 26 + tPx + 8, 24f, new float[]{0.92f, 0.95f, 0.95f, 1f});

        rrect(card[0] + 60, card[1] + tPx + 96, card[2] - 120, 5f, 2f, BTN_GOLD);

        int hover = pickMenuButton(mouseX, mouseY, vw, vh);
        float[] prim = menuPrimaryRect(vw, vh);
        float[] sec = menuSecondaryRect(vw, vh);
        boolean primHover = hover == 1;
        boolean secHover = hover == 2;

        panel(prim[0], prim[1], prim[2], prim[3],
                primHover ? BTN_GOLD_HOVER : BTN_GOLD, CARD_EDGE);
        String primLabel = menuPrimaryLabel(mode);
        float primPx = 30f;
        float primW = font.measure(primLabel + "  [Shift]", primPx);
        textPlain(primLabel + "  [Shift]", cx - primW * 0.5f,
                prim[1] + (prim[3] - primPx) * 0.5f - 4f, primPx, CARD_TEXT);

        panel(sec[0], sec[1], sec[2], sec[3],
                secHover ? new float[]{0.22f, 0.60f, 0.62f, 1f} : new float[]{0.14f, 0.38f, 0.42f, 1f},
                secHover ? BTN_GOLD : CARD_EDGE);
        float secPx = 26f;
        float secW = font.measure("QUIT", secPx);
        textPlain("QUIT", cx - secW * 0.5f,
                sec[1] + (sec[3] - secPx) * 0.5f - 4f, secPx,
                secHover ? BTN_GOLD : TEXT_WHITE);

        String moneyStr = "Day " + day + "    $" + money;
        textCenteredPlain(moneyStr, cx, card[1] + card[3] - 76, 24f, CARD_SUB);
        if (mode == MODE_MAIN) {
            textCenteredPlain("WASD move - E interact - Tab chart - 1-5 tools - Esc pause",
                    cx, card[1] + card[3] - 44, 19f, CARD_SUB);
        } else if (mode == MODE_PAUSE) {
            textCenteredPlain("P or RESUME to scrub back in - Esc releases mouse",
                    cx, card[1] + card[3] - 44, 19f, CARD_SUB);
        } else {
            textCenteredPlain("press ENTER or NEXT DAY for day " + (day + 1),
                    cx, card[1] + card[3] - 44, 19f, CARD_SUB);
        }

        if (overflow) {
            System.err.println("HUD vertex budget exceeded; some widgets dropped");
        }
    }

    private static final float CHART_W = 800f;
    private static final int CHART_ORG_COLS = 3;
    private static final int CHART_ISSUE_COLS = 4;
    private static final float CHART_BTNH = 30f;
    private static final float CHART_GAP = 5f;
    private static final float[] CHART_DIM = {0.00f, 0.00f, 0.00f, 0.45f};
    private static final float[] BTN_FILL = {0.16f, 0.17f, 0.22f, 0.96f};
    private static final float[] BTN_FILL_SEL = {0.34f, 0.30f, 0.16f, 0.97f};
    private static final float[] BTN_FILL_HOVER = {0.24f, 0.25f, 0.31f, 0.97f};
    private static final float[] BTN_LOCKED_FILL = {0.10f, 0.10f, 0.13f, 0.90f};

    private static final float[] CHART_GOOD = {0.10f, 0.45f, 0.20f, 1.00f};
    private static final float[] CHART_BAD = {0.70f, 0.15f, 0.12f, 1.00f};

    public static final int CHART_NONE = 0;
    public static final int CHART_DIAGNOSE = 1;
    public static final int CHART_DISCHARGE = 2;

    public static final int CHART_ORGAN = 10;
    public static final int CHART_ISSUE = 20;

    public static final class ChartLayout {
        public float[] panel;
        public float titleY, nameY, symptomY, statusY;
        public float organLabelY;
        public float[][] organs;
        public float issueLabelY;
        public float[][] issues;
        public float[] diagnose;
        public float adviceY;
        public float treatLabelY, stepY;
        public float[] discharge;
        public float hintY;
    }

    private static float[][] gridRects(float cx, float cy, float w,
                                       float btnH, float gap, int count, int cols) {
        float btnW = (w - gap * (cols - 1)) / cols;
        float[][] out = new float[count][];
        for (int i = 0; i < count; i++) {
            int row = i / cols, col = i % cols;
            out[i] = new float[]{cx + col * (btnW + gap), cy + row * (btnH + gap), btnW, btnH};
        }
        return out;
    }

    private static float[][] gridRects(float cx, float cy, float w,
                                       float btnH, float gap, int count) {
        return gridRects(cx, cy, w, btnH, gap, count, 2);
    }

    public static ChartLayout chartLayout(int vw, int vh, int organCount,
                                          int issueCount, int stepCount) {

        ChartLayout L = new ChartLayout();
        float cardW = Math.min(CHART_W, Math.max(200f, vw - 16f));
        float x = (vw - cardW) * 0.5f;
        if (x < 8f) {
            x = 8f;
        }
        float cx = x + 16f;
        float w = cardW - 32f;

        float cy = 10f;
        L.titleY = cy; cy += 36f;
        L.nameY = cy; cy += 30f;
        L.symptomY = cy; cy += 26f;
        L.statusY = cy; cy += 26f + 4f;
        L.organLabelY = cy; cy += 20f;
        L.organs = gridRects(cx, cy, w, CHART_BTNH, CHART_GAP, organCount, CHART_ORG_COLS);
        cy += ((organCount + CHART_ORG_COLS - 1) / CHART_ORG_COLS) * (CHART_BTNH + CHART_GAP) + 5f;
        L.issueLabelY = cy; cy += 20f;
        L.issues = gridRects(cx, cy, w, CHART_BTNH, CHART_GAP, issueCount, CHART_ISSUE_COLS);
        cy += ((issueCount + CHART_ISSUE_COLS - 1) / CHART_ISSUE_COLS) * (CHART_BTNH + CHART_GAP) + 5f;
        L.diagnose = new float[]{cx, cy, w, 40f}; cy += 40f + 5f;
        L.adviceY = cy; cy += 22f;
        L.treatLabelY = cy; cy += 20f;
        L.stepY = cy; cy += Math.max(1, stepCount) * 19f + 5f;
        L.discharge = new float[]{cx, cy, w, 40f}; cy += 40f + 5f;
        L.hintY = cy; cy += 18f;
        float contentH = cy + 8f;
        float y = (vh - contentH) * 0.5f;
        if (y < 8f) {
            y = 8f;
        }
        float dy = y - 0f;
        L.titleY += dy; L.nameY += dy; L.symptomY += dy; L.statusY += dy;
        L.organLabelY += dy; L.issueLabelY += dy; L.adviceY += dy;
        L.treatLabelY += dy; L.stepY += dy; L.hintY += dy;
        for (float[] r : L.organs) {
            r[1] += dy;
        }
        for (float[] r : L.issues) {
            r[1] += dy;
        }
        L.diagnose[1] += dy;
        L.discharge[1] += dy;
        L.panel = new float[]{x, y, cardW, contentH};
        return L;
    }

    public static int pickChartButton(float mx, float my, int vw, int vh,
                                      int organCount, int issueCount, int stepCount) {
        ChartLayout L = chartLayout(vw, vh, organCount, issueCount, stepCount);
        for (int i = 0; i < L.organs.length; i++) {
            if (inside(mx, my, L.organs[i])) {
                return CHART_ORGAN + i;
            }
        }
        for (int i = 0; i < L.issues.length; i++) {
            if (inside(mx, my, L.issues[i])) {
                return CHART_ISSUE + i;
            }
        }
        if (inside(mx, my, L.diagnose)) {
            return CHART_DIAGNOSE;
        }
        if (inside(mx, my, L.discharge)) {
            return CHART_DISCHARGE;
        }
        return CHART_NONE;
    }

    private void chartButton(float[] r, String label, float px, boolean selected,
                             boolean hover, boolean locked) {
        float[] fill = locked ? BTN_LOCKED_FILL
                : selected ? BTN_FILL_SEL : hover ? BTN_FILL_HOVER : BTN_FILL;
        float[] edge = selected ? BORDER_HOT : locked ? BORDER_DIM : BORDER_DIM;
        if (hover && !locked) {
            edge = BORDER_HOT;
        }

        rect(r[0], r[1], r[2], r[3], edge);
        rect(r[0] + 2, r[1] + 2, r[2] - 4, r[3] - 4, fill);
        float[] tc = locked ? TEXT_LOCKED : TEXT_WHITE;
        textCenteredPlain(label, r[0] + r[2] * 0.5f,
                r[1] + (r[3] - px) * 0.5f - 2f, px, tc);
    }

    public void buildChart(int vw, int vh, float mouseX, float mouseY,
                           String patName, String stateLine, String symptomLine,
                           String statusLine, boolean statusBad,
                           String[] organs, String[] issues,
                           int selOrgan, int selIssue, boolean diagnosed,
                           String advice,
                           String[] steps, int stepsDone, boolean canDischarge) {
        ChartLayout L = chartLayout(vw, vh, organs.length, issues.length, steps.length);
        int hover = pickChartButton(mouseX, mouseY, vw, vh,
                organs.length, issues.length, steps.length);

        rect(0, 0, vw, vh, CHART_DIM);
        rect(L.panel[0] + 8, L.panel[1] + 12, L.panel[2], L.panel[3], SHADOW);
        panel(L.panel[0], L.panel[1], L.panel[2], L.panel[3], CARD_FILL, CARD_EDGE);

        float cx = L.panel[0] + 16f;
        float innerW = L.panel[2] - 32f;
        text("PATIENT CHART", cx, L.titleY, 34f, CARD_TEXT);
        drawChartSilhouette(L.panel[0] + L.panel[2] - 96f, L.titleY - 2f);

        String nameState = stateLine == null || stateLine.isEmpty()
                ? patName : patName + "  -  " + stateLine;
        textPlain(fitText(nameState, innerW, 26f), cx, L.nameY, 26f, CARD_TEXT);
        textPlain(fitText(symptomLine, innerW, 21f), cx, L.symptomY, 21f, CARD_SUB);
        textPlain(fitText(statusLine, innerW, 21f), cx, L.statusY, 21f,
                statusBad ? CHART_BAD : CHART_GOOD);

        textPlain("1. STAMP ORGAN", cx, L.organLabelY, 18f, CARD_SUB);
        for (int i = 0; i < L.organs.length; i++) {
            chartButton(L.organs[i], organs[i], 18f,
                    i == selOrgan, hover == CHART_ORGAN + i, false);
        }
        textPlain("2. PICK ISSUE", cx, L.issueLabelY, 18f, CARD_SUB);
        for (int i = 0; i < L.issues.length; i++) {
            chartButton(L.issues[i], issues[i], 17f,
                    i == selIssue, hover == CHART_ISSUE + i, false);
        }
        String diagLabel;
        if (diagnosed) {
            diagLabel = "DIAGNOSED - see nurse hotline below";
        } else if (selOrgan >= 0 && selOrgan < organs.length
                && selIssue >= 0 && selIssue < issues.length) {
            diagLabel = "3. DIAGNOSE: " + organs[selOrgan] + " + "
                    + issues[selIssue] + " (click)";
        } else {
            diagLabel = "3. DIAGNOSE (stamp + issue first)";
        }
        chartButton(L.diagnose, fitText(diagLabel, innerW - 16f, 20f), 20f,
                diagnosed, hover == CHART_DIAGNOSE, false);

        float[] strip = {cx, L.adviceY, innerW, 22f};
        rect(strip[0], strip[1], strip[2], strip[3], BTN_FILL);
        rect(strip[0], strip[1], strip[2], 2f, BORDER_HOT);
        String hotline = "HOTLINE: " + (advice == null || advice.isEmpty()
                ? (diagnosed ? "follow the steps below" : "diagnose first for advice")
                : advice);
        textPlain(fitText(hotline, innerW - 12f, 17f), cx + 6f, L.adviceY + 3f, 17f, TEXT_WHITE);

        textPlain("TREATMENT - hold E at the bed", cx, L.treatLabelY, 18f, CARD_SUB);
        for (int i = 0; i < steps.length; i++) {
            String mark = i < stepsDone ? "[x] " : "[ ] ";
            textPlain(fitText(mark + steps[i], innerW, 18f), cx + 4, L.stepY + i * 19f, 18f,
                    i < stepsDone ? CHART_GOOD : CARD_TEXT);
        }

        chartButton(L.discharge, canDischarge ? "4. DISCHARGE (click)"
                        : "4. DISCHARGE (locked: treat first)", 20f,
                false, hover == CHART_DISCHARGE, !canDischarge);
        textCenteredPlain("Tab / Esc: close", L.panel[0] + L.panel[2] * 0.5f,
                L.hintY, 16f, CARD_SUB);

        if (overflow) {
            System.err.println("HUD vertex budget exceeded; some widgets dropped");
        }
    }

    private String fitText(String s, float maxW, float px) {
        if (s == null) {
            return "";
        }
        if (font.measure(s, px) <= maxW) {
            return s;
        }
        String ell = "...";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            String t = s.substring(0, mid) + ell;
            if (font.measure(t, px) <= maxW) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int n = Math.max(0, lo - 1);
        return s.substring(0, n) + ell;
    }

    private void drawChartSilhouette(float x, float y) {
        float[] ink = {0.16f, 0.45f, 0.50f, 1.0f};
        float[] skin = {0.96f, 0.80f, 0.45f, 1.0f};

        rect(x + 22f, y, 36f, 30f, ink);
        rect(x + 24f, y + 2f, 32f, 26f, skin);

        rect(x + 30f, y + 10f, 7f, 7f, ink);
        rect(x + 43f, y + 10f, 7f, 7f, ink);

        rect(x + 8f, y + 32f, 64f, 30f, ink);
        rect(x + 36f, y + 32f, 8f, 30f, new float[]{0.97f, 0.98f, 1.0f, 1f});
    }

    public void fadeOverlay(int vw, int vh, float alpha) {
        if (alpha <= 0.001f) {
            return;
        }
        float a = Math.min(1f, alpha);
        rect(0, 0, vw, vh, new float[]{0f, 0f, 0f, a});
    }

    private void crosshair(int vw, int vh) {
        float cx = vw * 0.5f, cy = vh * 0.5f;
        float[] c = {1f, 1f, 1f, 0.85f};
        float gap = 7f, len = 6f;
        rect(cx - 1.5f, cy - 1.5f, 3f, 3f, c);
        rect(cx - 1.5f, cy - gap - len, 3f, len, c);
        rect(cx - 1.5f, cy + gap, 3f, len, c);
        rect(cx - gap - len, cy - 1.5f, len, 3f, c);
        rect(cx + gap, cy - 1.5f, len, 3f, c);
    }

    public void banner(String s, int vw, int vh, float px, float[] color) {
        float w = font.measure(s, px);
        float y = vh * 0.28f;
        panel((vw - w) * 0.5f - 22, y - 12, w + 44, px + 24, PANEL_SOLID, BORDER_DIM);
        text(s, (vw - w) * 0.5f, y, px, color);
    }

    public ByteBuffer buildBuffer(int[] outVerts) {
        int bytes = vCount * (2 + 2 + 4) * 4;
        ByteBuffer buf = MemoryUtil.memAlloc(bytes);

        for (int i = 0; i < vCount * 2; i++) {
            buf.putFloat(pos[i]);
        }

        for (int i = 0; i < vCount * 2; i++) {
            buf.putFloat(uv[i]);
        }

        for (int i = 0; i < vCount * 4; i++) {
            buf.putFloat(col[i]);
        }
        buf.flip();
        outVerts[0] = vCount;
        return buf;
    }

    public int vertexCount() {
        return vCount;
    }

    @Override
    public void close() {

    }
}
