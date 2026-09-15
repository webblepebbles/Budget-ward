package game.render;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class FontAtlas implements AutoCloseable {

    public static final class Glyph {
        public final float u0, v0, u1, v1;
        public final int w, h;
        public final int xoff, yoff;
        public final float advance;

        Glyph(float u0, float v0, float u1, float v1,
              int w, int h, int xoff, int yoff, float advance) {
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            this.w = w; this.h = h; this.xoff = xoff; this.yoff = yoff;
            this.advance = advance;
        }
    }

    private static final int PAD = 2;
    private static final int PX = 64;
    private static final int ATLAS_W = 1024;

    public final int width, height;
    public final float ascent, descent, lineGap;
    private final float scale;
    private final STBTTFontinfo info;
    private final ByteBuffer ttf;
    private final Glyph[] glyphs;
    private final ByteBuffer pixels;

    public static List<String> fontCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> c = new ArrayList<>();
        if (os.contains("mac")) {
            c.add("/System/Library/Fonts/Supplemental/Comic Sans MS.ttf");
            c.add("/Library/Fonts/Comic Sans MS.ttf");
            c.add("/System/Library/Fonts/Supplemental/Chalkboard SE.ttf");
            c.add("/System/Library/Fonts/Supplemental/Arial.ttf");
        } else if (os.contains("win")) {
            c.add("C:/Windows/Fonts/comic.ttf");
            c.add("C:/Windows/Fonts/comicbd.ttf");
            c.add("C:/Windows/Fonts/segoeui.ttf");
            c.add("C:/Windows/Fonts/arial.ttf");
        } else {
            c.add("/usr/share/fonts/truetype/msttcorefonts/Comic_Sans_MS.ttf");
            c.add("/usr/share/fonts/truetype/comic/comic.ttf");
            c.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        }
        return c;
    }

    public static Path findFontFile() {
        for (String p : fontCandidates()) {
            Path path = Paths.get(p);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    public FontAtlas() throws Exception {
        this(findFontFile());
    }

    public FontAtlas(Path fontFile) throws Exception {
        if (fontFile == null || !Files.isRegularFile(fontFile)) {
            throw new IllegalStateException("No usable TrueType font found (looked for Comic Sans MS + fallbacks)");
        }
        byte[] bytes = Files.readAllBytes(fontFile);
        ttf = MemoryUtil.memAlloc(bytes.length);
        ttf.put(bytes).flip();

        info = STBTTFontinfo.calloc();
        if (!STBTruetype.stbtt_InitFont(info, ttf)) {
            freeInternal();
            throw new IllegalStateException("Failed to parse font " + fontFile);
        }

        scale = STBTruetype.stbtt_ScaleForPixelHeight(info, PX);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer asc = stack.mallocInt(1), desc = stack.mallocInt(1), gap = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(info, asc, desc, gap);
            ascent = asc.get(0) * scale;
            descent = desc.get(0) * scale;
            lineGap = gap.get(0) * scale;
        }

        int n = 95 + 1;
        glyphs = new Glyph[n];
        int[] cps = new int[n];
        for (int i = 0; i < 95; i++) {
            cps[i] = 32 + i;
        }
        cps[n - 1] = 167;

        int[] gw = new int[n], gh = new int[n], gx0 = new int[n], gy0 = new int[n];
        for (int i = 0; i < n; i++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x0 = stack.mallocInt(1), y0 = stack.mallocInt(1);
                IntBuffer x1 = stack.mallocInt(1), y1 = stack.mallocInt(1);
                STBTruetype.stbtt_GetCodepointBitmapBox(info, cps[i], scale, scale, x0, y0, x1, y1);
                gw[i] = Math.max(0, x1.get(0) - x0.get(0));
                gh[i] = Math.max(0, y1.get(0) - y0.get(0));
                gx0[i] = x0.get(0);
                gy0[i] = y0.get(0);
            }
        }

        int[] ux = new int[n], uy = new int[n];
        int cursorX = PAD, cursorY = PAD, rowH = 0;
        for (int i = 0; i < n; i++) {
            int w = gw[i] + PAD * 2, h = gh[i] + PAD * 2;
            if (cursorX + w > ATLAS_W) {
                cursorY += rowH;
                cursorX = PAD;
                rowH = 0;
            }
            ux[i] = cursorX + PAD;
            uy[i] = cursorY + PAD;
            cursorX += w;
            rowH = Math.max(rowH, h);
        }
        int atlasH = cursorY + rowH + PAD;
        width = ATLAS_W;
        height = atlasH;

        pixels = MemoryUtil.memCalloc((int) ((long) width * height));

        for (int i = 0; i < n; i++) {
            float advance;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer adv = stack.mallocInt(1), lsb = stack.mallocInt(1);
                STBTruetype.stbtt_GetCodepointHMetrics(info, cps[i], adv, lsb);
                advance = adv.get(0) * scale;
            }
            if (gw[i] <= 0 || gh[i] <= 0) {
                glyphs[i] = new Glyph(0, 0, 0, 0, 0, 0, 0, 0, advance);
                continue;
            }
            int x0 = ux[i], y0 = uy[i];
            ByteBuffer bmp = MemoryUtil.memAlloc(gw[i] * gh[i]);
            try {
                STBTruetype.stbtt_MakeCodepointBitmap(info, bmp, gw[i], gh[i], gw[i], scale, scale, cps[i]);
                for (int y = 0; y < gh[i]; y++) {
                    for (int x = 0; x < gw[i]; x++) {
                        byte v = bmp.get(y * gw[i] + x);
                        pixels.put((int) ((long) (y0 + y) * width + (x0 + x)), v);
                    }
                }
            } finally {
                MemoryUtil.memFree(bmp);
            }
            glyphs[i] = new Glyph(
                    x0 / (float) width, y0 / (float) height,
                    (x0 + gw[i]) / (float) width, (y0 + gh[i]) / (float) height,
                    gw[i], gh[i], gx0[i], gy0[i], advance);
        }
    }

    public Glyph glyph(int cp) {
        int i;
        if (cp >= 32 && cp < 127) {
            i = cp - 32;
        } else if (cp == 167) {
            i = 95;
        } else {
            i = cp < 32 ? 0 : 95;
        }
        return glyphs[i];
    }

    private float kern(int a, int b) {
        return STBTruetype.stbtt_GetCodepointKernAdvance(info, a, b) * scale;
    }

    public float measure(String s, float px) {
        float k = px / PX;
        float x = 0;
        int prev = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int cp = ch >= 128 ? 167 : ch;
            if (prev >= 0) {
                x += kern(prev, cp);
            }
            x += glyph(cp).advance;
            prev = cp;
        }
        return x * k;
    }

    public float ascent(float px) {
        return ascent * (px / PX);
    }

    public ByteBuffer pixels() {
        return pixels;
    }

    private void freeInternal() {
        if (pixels != null) {
            MemoryUtil.memFree(pixels);
        }
        if (ttf != null) {
            MemoryUtil.memFree(ttf);
        }
        if (info != null) {
            info.free();
        }
    }

    @Override
    public void close() {
        freeInternal();
    }
}
