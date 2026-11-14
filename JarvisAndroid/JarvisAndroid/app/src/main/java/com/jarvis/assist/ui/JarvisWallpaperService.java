package com.jarvis.assist.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class JarvisWallpaperService extends WallpaperService {

    // ── module-level constants (top-level of outer class = legal) ────────────
    private static final float[] RING_FRACTIONS = {0.25f, 0.50f, 0.75f, 1.00f};
    private static final String[] RING_LABELS   = {"25",  "50",  "75",  "100"};

    private static final String[] BAR_LABELS  = {"RUN", "SLEEP", "MOVE", "VO2"};
    private static final float[]  BAR_FILLS   = {0.62f, 0.45f,   0.78f,  0.55f};
    private static final int[]    BAR_COLORS  = {
        0xFF00FFB2,
        0xFF0099FF,
        0xFFFF6A00,
        0xFFFF003C
    };

    private static final int   FRAME_RATE_MS   = 33;
    private static final float SWEEP_SPEED_DEG = 1.5f;
    private static final int   HUD_COLOR       = 0xFF00FFB2;
    private static final int   DIM_COLOR       = 0x3300FFB2;

    // ── WallpaperService contract ────────────────────────────────────────────
    @Override
    public Engine onCreateEngine() {
        return new JarvisEngine();
    }

    // ── inner Engine — non-static so it can extend WallpaperService.Engine ──
    private class JarvisEngine extends Engine {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint   paint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF   arcRect = new RectF();

        private float   sweepAngle = 0f;
        private float   cx, cy, baseR;
        private boolean visible    = false;

        private final Runnable drawLoop = new Runnable() {
            @Override public void run() {
                drawFrame();
                if (visible) handler.postDelayed(this, FRAME_RATE_MS);
            }
        };

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format,
                                     int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            cx    = width  / 2f;
            cy    = height / 2f;
            baseR = Math.min(cx, cy) * 0.72f;
        }

        @Override
        public void onVisibilityChanged(boolean vis) {
            visible = vis;
            if (vis) {
                handler.post(drawLoop);
            } else {
                handler.removeCallbacks(drawLoop);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
            handler.removeCallbacks(drawLoop);
        }

        // ── render loop ──────────────────────────────────────────────────────
        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) return;
                render(canvas);
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
            sweepAngle = (sweepAngle + SWEEP_SPEED_DEG) % 360f;
        }

        private void render(Canvas c) {
            c.drawColor(Color.BLACK);

            // concentric rings
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            for (int i = 0; i < RING_FRACTIONS.length; i++) {
                float r = baseR * RING_FRACTIONS[i];
                paint.setColor(DIM_COLOR);
                c.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(HUD_COLOR);
                paint.setTextSize(20f);
                paint.setTextAlign(Paint.Align.LEFT);
                c.drawText(RING_LABELS[i], cx + r + 6f, cy, paint);
                paint.setStyle(Paint.Style.STROKE);
            }

            // radar sweep wedge
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x2200FFB2);
            arcRect.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR);
            c.drawArc(arcRect, sweepAngle, 60f, true, paint);

            // sweep leading edge
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(HUD_COLOR);
            c.drawArc(arcRect, sweepAngle, 1f, true, paint);

            // outer ring
            paint.setStrokeWidth(3f);
            paint.setColor(HUD_COLOR);
            c.drawCircle(cx, cy, baseR, paint);

            // center dot
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(HUD_COLOR);
            c.drawCircle(cx, cy, 6f, paint);

            // JARVIS label
            paint.setTextSize(36f);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("JARVIS", cx, cy - baseR * 0.25f, paint);

            paint.setTextSize(20f);
            paint.setColor(DIM_COLOR);
            c.drawText("\u00b7 CONNECTED \u00b7", cx,
                       cy - baseR * 0.25f + 28f, paint);

            drawBars(c);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawBars(Canvas c) {
            float barW    = 80f;
            float barH    = 10f;
            float spacing = (c.getWidth() - BAR_LABELS.length * barW)
                            / (BAR_LABELS.length + 1f);
            float y       = c.getHeight() - 80f;

            for (int i = 0; i < BAR_LABELS.length; i++) {
                float x = spacing + i * (barW + spacing);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(HUD_COLOR);
                paint.setTextSize(18f);
                paint.setTextAlign(Paint.Align.LEFT);
                c.drawText(BAR_LABELS[i], x, y - 16f, paint);

                paint.setColor(DIM_COLOR);
                c.drawRect(x, y, x + barW, y + barH, paint);

                paint.setColor(BAR_COLORS[i]);
                c.drawRect(x, y, x + barW * BAR_FILLS[i], y + barH, paint);
            }
        }
    }
}
