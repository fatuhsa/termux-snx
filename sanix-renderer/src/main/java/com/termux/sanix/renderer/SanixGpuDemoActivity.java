package com.termux.sanix.renderer;

import android.app.Activity;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;

import com.termux.terminal.TextStyle;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class SanixGpuDemoActivity extends Activity {

    private static final int COLS = 40;
    private static final int ROWS = 14;

    private static final int FG = TextStyle.COLOR_INDEX_FOREGROUND;
    private static final int BG = TextStyle.COLOR_INDEX_BACKGROUND;
    private static final int BOLD = TextStyle.CHARACTER_ATTRIBUTE_BOLD;
    private static final int UNDERLINE = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
    private static final int DIM = TextStyle.CHARACTER_ATTRIBUTE_DIM;
    private static final int INVERSE = TextStyle.CHARACTER_ATTRIBUTE_INVERSE;

    private GLSurfaceView mGlSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGlSurfaceView = new GLSurfaceView(this);
        mGlSurfaceView.setEGLContextClientVersion(3);
        mGlSurfaceView.setRenderer(new DemoRenderer());
        setContentView(mGlSurfaceView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mGlSurfaceView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGlSurfaceView.onResume();
    }

    private static final class DemoRenderer implements GLSurfaceView.Renderer {

        private static final int[] PALETTE = new int[TextStyle.NUM_INDEXED_COLORS];

        static {
            int[] colors = {
                0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
                0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
                0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
                0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
            };
            System.arraycopy(colors, 0, PALETTE, 0, colors.length);
            PALETTE[FG] = 0xFFE5E5E5;
            PALETTE[BG] = 0xFF000000;
            PALETTE[TextStyle.COLOR_INDEX_CURSOR] = 0xFFFFFFFF;
        }

        private final SanixGpuRenderer mRenderer = new SanixGpuRenderer(36, Typeface.MONOSPACE);
        private final char[][] mText = new char[ROWS][];
        private final long[][] mStyles = new long[ROWS][];
        private int mFilledRows;
        private int mCursorCol = -1;
        private int mCursorRow = -1;

        DemoRenderer() {
            addRow("Sanix GPU Renderer v0.1", plain());
            addRow("", plain());
            addRow("Hello Sanix!", plain());
            addRow("$ git status", plain());
            addRow("On branch master", plain());
            addRow("nothing to commit, working tree clean", plain());
            addRow("", plain());
            addRow("ANSI 16 colors:", plain());
            addColorRow();
            addRow("", plain());
            addEffectRow();
            addRow("", plain());
            addRow("next: wire to TerminalBuffer -> instanced draw", style(2, BG, 0));
            addRow("", plain());
            mCursorCol = 12;
            mCursorRow = 3;
        }

        private void addRow(String text, long style) {
            int len = Math.min(text.length(), COLS);
            char[] line = new char[COLS];
            long[] rowStyles = new long[COLS];
            for (int col = 0; col < COLS; col++) {
                line[col] = col < len ? text.charAt(col) : ' ';
                rowStyles[col] = style;
            }
            mText[mFilledRows] = line;
            mStyles[mFilledRows] = rowStyles;
            mFilledRows++;
        }

        private void addColorRow() {
            char[] line = new char[COLS];
            long[] rowStyles = new long[COLS];
            int col = 0;
            int[] colors = {1, 2, 3, 4, 5, 6, 7, 15};
            for (int color : colors) {
                String word = String.format(java.util.Locale.US, "%-5d", color);
                for (int i = 0; i < 5 && col < COLS; i++) {
                    line[col] = word.charAt(i);
                    rowStyles[col] = style(color, BG, 0);
                    col++;
                }
            }
            mText[mFilledRows] = line;
            mStyles[mFilledRows] = rowStyles;
            mFilledRows++;
        }

        private void addEffectRow() {
            char[] line = new char[COLS];
            long[] rowStyles = new long[COLS];
            for (int col = 0; col < COLS; col++) {
                line[col] = ' ';
                rowStyles[col] = style(1, BG, 0);
            }
            String[] words = {"bold", "underline", "dim", "inverse"};
            int[] effects = {BOLD, UNDERLINE, DIM, INVERSE};
            int col = 0;
            for (int i = 0; i < words.length && col < COLS; i++) {
                for (int j = 0; j < words[i].length() && col < COLS; j++) {
                    line[col] = words[i].charAt(j);
                    rowStyles[col] = style(1, BG, effects[i]);
                    col++;
                }
                if (col < COLS) col++;
            }
            mText[mFilledRows] = line;
            mStyles[mFilledRows] = rowStyles;
            mFilledRows++;
        }

        private static long plain() {
            return encode(FG, BG, 0);
        }

        private static long style(int fg, int bg, int effect) {
            return encode(fg, bg, effect);
        }

        private static long encode(int fg, int bg, int effect) {
            return effect | ((long) fg << 40) | ((long) bg << 16);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            android.util.Log.i("SanixGpuDemo", "onSurfaceCreated");
            mRenderer.init();
            mRenderer.setDebugAtlas(true);
            try {
                byte[] png = mRenderer.dumpAtlasPng();
                if (png != null) {
                    java.io.File f = new java.io.File(getFilesDir(), "atlas_bitmap.png");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(png);
                    fos.close();
                    android.util.Log.i("SanixGpuDemo", "atlas bitmap saved " + f.getAbsolutePath() + " " + png.length + " bytes");
                }
            } catch (Exception e) {
                android.util.Log.e("SanixGpuDemo", "save atlas bitmap failed", e);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            android.util.Log.i("SanixGpuDemo", "onSurfaceChanged " + width + "x" + height);
            GLES30.glViewport(0, 0, width, height);
            mRenderer.resize(COLS, ROWS);
            mRenderer.setViewport(width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            android.util.Log.i("SanixGpuDemo", "onDrawFrame");
            mRenderer.draw(mText, mStyles, mCursorCol, mCursorRow, true, PALETTE);
        }
    }
}