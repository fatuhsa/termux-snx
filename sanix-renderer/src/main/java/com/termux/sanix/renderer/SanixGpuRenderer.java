package com.termux.sanix.renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;

import com.termux.terminal.TextStyle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class SanixGpuRenderer {

    private static final int ATLAS_COLS = 16;
    private static final int ATLAS_ROWS = 6;
    private static final int ATLAS_PAGES = 2;
    private static final int ATLAS_GLYPH_COUNT = 95;
    private static final int FIRST_GLYPH = 32;

    private static final int INSTANCE_STRIDE_FLOATS = 13;

    private final int mTextSize;
    private final Typeface mTypeface;
    private final Paint mPaint = new Paint();
    private final float mCellWidth;
    private final float mCellHeight;
    private final int mCellWidthPx;
    private final int mCellHeightPx;

    private int mCols;
    private int mRows;

    private int mAtlasTexture;
    private int mProgram;
    private int mQuadVbo;
    private int mQuadEbo;
    private int mInstanceVbo;
    private int mUViewport;
    private int mUCellSize;
    private int mUAtlasSize;
    private int mUOffset;
    private float mViewportWidth;
    private float mViewportHeight;

    private FloatBuffer mInstanceData;
    private final float[] mPalette = new float[TextStyle.COLOR_INDEX_CURSOR + 1];

    private static final String VERTEX_SHADER =
        "#version 300 es\n" +
        "layout(location=0) in vec2 aPos;\n" +
        "layout(location=1) in vec4 aCell;\n" +
        "layout(location=2) in vec4 aFg;\n" +
        "layout(location=3) in vec4 aBg;\n" +
        "layout(location=4) in float aFlags;\n" +
        "uniform vec2 uViewport;\n" +
        "uniform vec2 uCellSize;\n" +
        "uniform vec2 uAtlasSize;\n" +
        "uniform vec2 uOffset;\n" +
        "out vec2 vUv;\n" +
        "out vec4 vFg;\n" +
        "out vec4 vBg;\n" +
        "out float vFlags;\n" +
        "void main() {\n" +
        "    vec2 cellOrigin = uOffset + aCell.xy * uCellSize;\n" +
        "    vec2 ndc = (cellOrigin + aPos * uCellSize) / uViewport * 2.0 - 1.0;\n" +
        "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
        "    vec2 cellUvSize = uCellSize / uAtlasSize;\n" +
        "    vUv = (aCell.zw + aPos) * cellUvSize;\n" +
        "    vFg = aFg;\n" +
        "    vBg = aBg;\n" +
        "    vFlags = aFlags;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#version 300 es\n" +
        "precision mediump float;\n" +
        "in vec2 vUv;\n" +
        "in vec4 vFg;\n" +
        "in vec4 vBg;\n" +
        "in float vFlags;\n" +
        "uniform sampler2D uAtlas;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    float alpha = texture(uAtlas, vUv).a;\n" +
        "    vec4 color = mix(vBg, vFg, alpha);\n" +
        "    if (vFlags > 0.5 && vUv.y < 0.125) color = vFg;\n" +
        "    fragColor = color;\n" +
        "}\n";

    public SanixGpuRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;
        mPaint.setTypeface(typeface);
        mPaint.setAntiAlias(true);
        mPaint.setTextSize(textSize);
        mCellWidth = mPaint.measureText("X");
        mCellHeight = mPaint.getFontSpacing();
        mCellWidthPx = (int) Math.ceil(mCellWidth);
        mCellHeightPx = (int) Math.ceil(mCellHeight);
        defaultPalette();
    }

    private void defaultPalette() {
        int[] colors = {
            0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
            0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
            0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
            0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
            0xFF000000, 0xFF000000, 0xFFFFFFFF
        };
        for (int i = 0; i < colors.length; i++) setColor(i, colors[i]);
        for (int i = colors.length; i <= TextStyle.COLOR_INDEX_CURSOR; i++) setColor(i, 0xFF000000);
    }

    private void setColor(int index, int argb) {
        mPalette[index * 4] = ((argb >> 16) & 0xFF) / 255.f;
        mPalette[index * 4 + 1] = ((argb >> 8) & 0xFF) / 255.f;
        mPalette[index * 4 + 2] = (argb & 0xFF) / 255.f;
        mPalette[index * 4 + 3] = 1.f;
    }

    public void init() {
        mProgram = createProgram();
        mUViewport = GLES30.glGetUniformLocation(mProgram, "uViewport");
        mUCellSize = GLES30.glGetUniformLocation(mProgram, "uCellSize");
        mUAtlasSize = GLES30.glGetUniformLocation(mProgram, "uAtlasSize");
        mUOffset = GLES30.glGetUniformLocation(mProgram, "uOffset");

        mQuadVbo = createQuad();
        mQuadEbo = createIndices();
        mInstanceVbo = createInstanceBuffer();

        mAtlasTexture = createAtlas();
    }

    public void resize(int cols, int rows) {
        mCols = cols;
        mRows = rows;
    }

    public void setViewport(int width, int height) {
        mViewportWidth = width;
        mViewportHeight = height;
    }

    public void draw(char[][] text, long[][] styles, int cursorCol, int cursorRow, boolean cursorVisible, int[] palette) {
        GLES30.glClearColor(0.f, 0.f, 0.f, 1.f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        if (mCols == 0 || mRows == 0) return;

        for (int i = 0; i < palette.length && i <= TextStyle.COLOR_INDEX_CURSOR; i++) setColor(i, palette[i]);

        float scale = Math.min(mViewportWidth / (mCols * mCellWidthPx), mViewportHeight / (mRows * mCellHeightPx));
        if (scale <= 0.f) scale = 1.f;
        float cellW = mCellWidthPx * scale;
        float cellH = mCellHeightPx * scale;
        float offsetX = (mViewportWidth - mCols * cellW) / 2.f;
        float offsetY = (mViewportHeight - mRows * cellH) / 2.f;

        int cellCount = mCols * mRows;
        FloatBuffer data = mInstanceData;
        if (data == null || data.capacity() < cellCount * INSTANCE_STRIDE_FLOATS) {
            data = ByteBuffer.allocateDirect(cellCount * INSTANCE_STRIDE_FLOATS * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mInstanceData = data;
        }
        data.clear();
        for (int row = 0; row < mRows; row++) {
            char[] line = text[row];
            long[] rowStyles = styles[row];
            for (int col = 0; col < mCols; col++) {
                boolean cursorHere = cursorVisible && row == cursorRow && col == cursorCol;
                fillCell(data, line, rowStyles, col, row, cursorHere);
            }
        }
        data.flip();

        GLES30.glUseProgram(mProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mAtlasTexture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgram, "uAtlas"), 0);
        GLES30.glUniform2f(mUViewport, mViewportWidth, mViewportHeight);
        GLES30.glUniform2f(mUCellSize, cellW, cellH);
        GLES30.glUniform2f(mUAtlasSize, mCellWidthPx * ATLAS_COLS, mCellHeightPx * ATLAS_ROWS * ATLAS_PAGES);
        GLES30.glUniform2f(mUOffset, offsetX, offsetY);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadVbo);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mInstanceVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.remaining() * 4, data, GLES30.GL_DYNAMIC_DRAW);

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mQuadEbo);
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, 6, GLES30.GL_UNSIGNED_SHORT, 0, cellCount);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void fillCell(FloatBuffer data, char[] line, long[] rowStyles, int col, int row, boolean cursorHere) {
        char c = col < line.length ? line[col] : ' ';
        long style = col < rowStyles.length ? rowStyles[col] : 0L;
        int fg = TextStyle.decodeForeColor(style);
        int bg = TextStyle.decodeBackColor(style);
        int effect = TextStyle.decodeEffect(style);
        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean inverse = (effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0;
        boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        boolean invisible = (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0;
        boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((fg & 0xff000000) == 0) {
            if (bold && fg < 8) fg += 8;
            fg = mPaletteToArgb(fg);
        }
        if ((bg & 0xff000000) == 0) bg = mPaletteToArgb(bg);

        if (cursorHere) {
            bg = mPaletteToArgb(TextStyle.COLOR_INDEX_CURSOR);
        }
        if (inverse) {
            int tmp = fg;
            fg = bg;
            bg = tmp;
        }
        if (dim) {
            int red = ((fg >> 16) & 0xFF) * 2 / 3;
            int green = ((fg >> 8) & 0xFF) * 2 / 3;
            int blue = (fg & 0xFF) * 2 / 3;
            fg = 0xFF000000 + (red << 16) + (green << 8) + blue;
        }

        int glyph = c - FIRST_GLYPH;
        if (glyph < 0 || glyph >= ATLAS_GLYPH_COUNT) glyph = 0;
        int atlasCol = glyph % ATLAS_COLS;
        int atlasRow = glyph / ATLAS_COLS + (bold ? ATLAS_ROWS : 0);

        data.put(col);
        data.put(row);
        data.put(atlasCol);
        data.put(atlasRow);
        putColor(data, fg);
        putColor(data, bg);
        data.put(invisible ? 0.f : (underline ? 1.f : 0.f));
    }

    private int mPaletteToArgb(int index) {
        int i = index * 4;
        return 0xFF000000
            | ((int) (mPalette[i] * 255.f) << 16)
            | ((int) (mPalette[i + 1] * 255.f) << 8)
            | (int) (mPalette[i + 2] * 255.f);
    }

    private void putColor(FloatBuffer data, int argb) {
        data.put(((argb >> 16) & 0xFF) / 255.f);
        data.put(((argb >> 8) & 0xFF) / 255.f);
        data.put((argb & 0xFF) / 255.f);
        data.put(1.f);
    }

    private int createAtlas() {
        int atlasWidth = mCellWidthPx * ATLAS_COLS;
        int atlasHeight = mCellHeightPx * ATLAS_ROWS * ATLAS_PAGES;
        Bitmap atlas = Bitmap.createBitmap(atlasWidth, atlasHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(atlas);
        Paint paint = mPaint;
        paint.setColor(0xFFFFFFFF);
        paint.setFakeBoldText(false);
        float baseline = mCellHeightPx - paint.getFontMetrics().descent;
        for (int page = 0; page < ATLAS_PAGES; page++) {
            paint.setFakeBoldText(page == 1);
            for (int glyph = 0; glyph < ATLAS_GLYPH_COUNT; glyph++) {
                int col = glyph % ATLAS_COLS;
                int row = glyph / ATLAS_COLS + page * ATLAS_ROWS;
                canvas.drawText(String.valueOf((char) (glyph + FIRST_GLYPH)),
                    col * mCellWidthPx, row * mCellHeightPx + baseline, paint);
            }
        }
        int[] pixels = new int[atlasWidth * atlasHeight];
        atlas.getPixels(pixels, 0, atlasWidth, 0, 0, atlasWidth, atlasHeight);
        ByteBuffer alpha = ByteBuffer.allocateDirect(pixels.length);
        for (int pixel : pixels) alpha.put((byte) ((pixel >> 24) & 0xFF));
        alpha.flip();

        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_ALPHA, atlasWidth, atlasHeight, 0,
            GLES30.GL_ALPHA, GLES30.GL_UNSIGNED_BYTE, alpha);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        atlas.recycle();
        return textures[0];
    }

    private int createQuad() {
        float[] quad = {0.f, 0.f, 1.f, 0.f, 1.f, 1.f, 0.f, 1.f};
        FloatBuffer buffer = ByteBuffer.allocateDirect(quad.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(quad).flip();
        int[] buffers = new int[1];
        GLES30.glGenBuffers(1, buffers, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.length * 4, buffer, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        return buffers[0];
    }

    private int createIndices() {
        short[] indices = {0, 1, 2, 0, 2, 3};
        ByteBuffer buffer = ByteBuffer.allocateDirect(indices.length * 2).order(ByteOrder.nativeOrder());
        for (short index : indices) buffer.putShort(index);
        buffer.flip();
        int[] buffers = new int[1];
        GLES30.glGenBuffers(1, buffers, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[0]);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.length * 2, buffer, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        return buffers[0];
    }

    private int createInstanceBuffer() {
        int[] buffers = new int[1];
        GLES30.glGenBuffers(1, buffers, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0]);
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, INSTANCE_STRIDE_FLOATS * 4, 0);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, INSTANCE_STRIDE_FLOATS * 4, 16);
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, INSTANCE_STRIDE_FLOATS * 4, 32);
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, INSTANCE_STRIDE_FLOATS * 4, 48);
        GLES30.glVertexAttribDivisor(1, 1);
        GLES30.glVertexAttribDivisor(2, 1);
        GLES30.glVertexAttribDivisor(3, 1);
        GLES30.glVertexAttribDivisor(4, 1);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glEnableVertexAttribArray(3);
        GLES30.glEnableVertexAttribArray(4);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        return buffers[0];
    }

    private int createProgram() {
        int vertex = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertex);
        GLES30.glAttachShader(program, fragment);
        GLES30.glBindAttribLocation(program, 0, "aPos");
        GLES30.glLinkProgram(program);
        int[] linked = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            throw new IllegalStateException(GLES30.glGetProgramInfoLog(program));
        }
        GLES30.glDeleteShader(vertex);
        GLES30.glDeleteShader(fragment);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            throw new IllegalStateException(GLES30.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public void destroy() {
        int[] objects = new int[1];
        objects[0] = mAtlasTexture;
        GLES30.glDeleteTextures(1, objects, 0);
        objects[0] = mProgram;
        GLES30.glDeleteProgram(objects[0]);
        objects[0] = mQuadVbo;
        GLES30.glDeleteBuffers(1, objects, 0);
        objects[0] = mQuadEbo;
        GLES30.glDeleteBuffers(1, objects, 0);
        objects[0] = mInstanceVbo;
        GLES30.glDeleteBuffers(1, objects, 0);
    }
}