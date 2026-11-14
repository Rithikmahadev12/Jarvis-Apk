package com.jarvis.assist.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * JarvisWallpaperService — Live Wallpaper.
 *
 * Mirrors OSO AugmaOS oso_wallpaper.xml — renders the JARVIS radar HUD
 * animation directly as the device's live wallpaper on the XT2271DL.
 *
 * Visual design:
 *   • Full black (#020406) background
 *   • Rotating radar sweep (red #CC2200)
 *   • Concentric cyan (#00AAFF) rings with range labels
 *   • JARVIS brand wordmark centered
 *   • Live clock + date in top-left
 *   • Corner HUD bar indicators (RUN / SLEEP / MOVE / VO2)
 *   • Subtle pulse dots on sweep intersections
 *
 * Optimized for XT2271DL (Moto G Pure):
 *   • Runs at 30fps to conserve battery on Helio G37
 *   • Pauses rendering when not visible (onVisibilityChanged)
 *   • Single Paint object pool — no GC pressure per frame
 */
public class JarvisWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new JarvisEngine();
    }

    private class JarvisEngine extends Engine {

        private static final int FPS       = 30;
        private static final long FRAME_MS = 1000L / FPS;

        private final Handler  mH        = new Handler(Looper.getMainLooper());
        private boolean        mVisible  = false;
        private float          mSweep    = 0f;   // degrees, 0–360
        private int            mW, mH2;

        // Paint pool
        private final Paint mBgP   = new Paint();
        private final Paint mRingP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSweepP= new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBrandP= new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBarP  = new Paint(Paint.ANTI_ALIAS_FLAG);

        private Typeface mMonoTf;
        private Typeface mBrandTf;

        // Ring radii as fractions of half-width
        private static final float[] RING_FRACTIONS = {0.25f, 0.50f, 0.75f, 1.00f};
        private static final String[] RING_LABELS   = {"25", "50", "75", "100"};

        // Corner bars
        private static final String[] BAR_LABELS  = {"RUN", "SLEEP", "MOVE", "VO2"};
        private static final float[]  BAR_FILLS   = {0.62f, 0.45f, 0.78f, 0.55f};
        private static final int[]    BAR_COLORS  = {
            Color.parseColor("#00AAFF"),
            Color.parseColor("#00AAFF"),
            Color.parseColor("#CC2200"),
            Color.parseColor("#00AAFF")
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            try {
                mMonoTf  = Typeface.createFromAsset(getAssets(), "fonts/sharetechmono_400.ttf");
                mBrandTf = Typeface.createFromAsset(getAssets(), "fonts/rajdhani_700.ttf");
            } catch (Exception e) {
                mMonoTf  = Typeface.MONOSPACE;
                mBrandTf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            }
            setupPaints();
        }

        private void setupPaints() {
            mBgP.setColor(Color.parseColor("#020406"));
            mBgP.setStyle(Paint.Style.FILL);

            mRingP.setColor(Color.parseColor("#1A00AAFF"));
            mRingP.setStyle(Paint.Style.STROKE);
            mRingP.setStrokeWidth(1.5f);

            mSweepP.setStyle(Paint.Style.FILL);

            mTextP.setColor(Color.parseColor("#4400AAFF"));
            mTextP.setTextSize(22f);
            mTextP.setTypeface(mMonoTf);

            mBrandP.setColor(Color.parseColor("#CC2200"));
            mBrandP.setTypeface(mBrandTf);
            mBrandP.setLetterSpacing(0.25f);

            mDotP.setColor(Color.parseColor("#CC2200"));
            mDotP.setStyle(Paint.Style.FILL);

            mBarP.setStyle(Paint.Style.FILL);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            if (visible) scheduleFrame();
            else mH.removeCallbacks(mFrameRunnable);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mW = width; mH2 = height;
            // Rebuild brand text size relative to screen
            mBrandP.setTextSize(mW * 0.085f);
            mTextP.setTextSize(mW * 0.028f);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            mH.removeCallbacks(mFrameRunnable);
        }

        private final Runnable mFrameRunnable = new Runnable() {
            @Override public void run() {
                drawFrame();
                if (mVisible) scheduleFrame();
            }
        };

        private void scheduleFrame() {
            mH.removeCallbacks(mFrameRunnable);
            mH.postDelayed(mFrameRunnable, FRAME_MS);
        }

        private void drawFrame() {
            final SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                render(c);
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
            mSweep = (mSweep + 1.2f) % 360f;
        }

        private void render(Canvas c) {
            final float cx = mW / 2f;
            final float cy = mH2 / 2f;
            final float maxR = cx * 0.92f;

            // ── Background
            c.drawRect(0, 0, mW, mH2, mBgP);

            // ── Radar rings
            for (int i = 0; i < RING_FRACTIONS.length; i++) {
                float r = maxR * RING_FRACTIONS[i];
                // Outer ring slightly brighter
                mRingP.setAlpha(i == RING_FRACTIONS.length - 1 ? 50 : 25);
                c.drawCircle(cx, cy, r, mRingP);
                // Ring label
                mTextP.setAlpha(80);
                c.drawText(RING_LABELS[i], cx + r + 4, cy - 3, mTextP);
            }

            // ── Cross-hairs
            mRingP.setAlpha(15);
            c.drawLine(cx, cy - maxR, cx, cy + maxR, mRingP);
            c.drawLine(cx - maxR, cy, cx + maxR, cy, mRingP);

            // ── Radar sweep (green→transparent gradient sector)
            mSweepP.setShader(new RadialGradient(
                cx, cy, maxR,
                new int[]{
                    Color.parseColor("#00CC2200"),
                    Color.parseColor("#33CC2200"),
                    Color.parseColor("#00CC2200")
                },
                new float[]{0f, 0.7f, 1f},
                Shader.TileMode.CLAMP
            ));
            RectF oval = new RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR);
            c.drawArc(oval, mSweep - 60f, 60f, true, mSweepP);

            // ── Sweep leading edge line
            Paint edgeP = new Paint(Paint.ANTI_ALIAS_FLAG);
            edgeP.setColor(Color.parseColor("#AA00AAFF"));
            edgeP.setStrokeWidth(2f);
            edgeP.setStyle(Paint.Style.STROKE);
            double rad = Math.toRadians(mSweep);
            c.drawLine(cx, cy,
                (float)(cx + maxR * Math.cos(rad)),
                (float)(cy + maxR * Math.sin(rad)),
                edgeP);

            // ── Sweep blip dots at ring intersections
            for (float frac : RING_FRACTIONS) {
                float dx = (float)(cx + maxR * frac * Math.cos(rad));
                float dy = (float)(cy + maxR * frac * Math.sin(rad));
                mDotP.setAlpha(200);
                c.drawCircle(dx, dy, 4f, mDotP);
            }

            // ── JARVIS brand wordmark (center)
            mBrandP.setAlpha(220);
            String brand = "JARVIS";
            float bw = mBrandP.measureText(brand);
            c.drawText(brand, cx - bw / 2f, cy + mBrandP.getTextSize() * 0.35f, mBrandP);

            // Sub-label
            mTextP.setAlpha(100);
            String sub = "SYSTEM ACTIVE";
            float sw = mTextP.measureText(sub);
            c.drawText(sub, cx - sw / 2f, cy + mBrandP.getTextSize() * 0.35f + mTextP.getTextSize() + 6f, mTextP);

            // ── Live clock (top-left)
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            String date = new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US);
            Paint clockP = new Paint(Paint.ANTI_ALIAS_FLAG);
            clockP.setTypeface(mMonoTf);
            clockP.setColor(Color.WHITE);
            clockP.setAlpha(200);
            clockP.setTextSize(mW * 0.055f);
            c.drawText(time, 36f, 72f, clockP);
            clockP.setColor(Color.parseColor("#00AAFF"));
            clockP.setTextSize(mW * 0.030f);
            clockP.setAlpha(170);
            c.drawText(date, 36f, 100f, clockP);

            // ── Corner HUD bars (right edge)
            float barX = mW - 80f;
            float barY = 90f;
            for (int i = 0; i < BAR_LABELS.length; i++) {
                // Label
                mTextP.setColor(Color.parseColor("#55FFFFFF"));
                mTextP.setAlpha(140);
                mTextP.setTextSize(mW * 0.028f);
                c.drawText(BAR_LABELS[i], barX, barY + 12f, mTextP);
                // Track
                Paint trackP = new Paint();
                trackP.setColor(Color.parseColor("#15FFFFFF"));
                trackP.setStyle(Paint.Style.FILL);
                RectF track = new RectF(barX, barY + 16f, barX + 60f, barY + 22f);
                c.drawRoundRect(track, 2f, 2f, trackP);
                // Fill
                mBarP.setColor(BAR_COLORS[i]);
                mBarP.setAlpha(200);
                RectF fill = new RectF(barX, barY + 16f, barX + 60f * BAR_FILLS[i], barY + 22f);
                c.drawRoundRect(fill, 2f, 2f, mBarP);
                barY += 32f;
            }

            // ── Bottom status strip
            Paint statusP = new Paint(Paint.ANTI_ALIAS_FLAG);
            statusP.setColor(Color.parseColor("#3300AAFF"));
            statusP.setStyle(Paint.Style.FILL);
            c.drawRect(0, mH2 - 48f, mW, mH2, statusP);
            mTextP.setColor(Color.parseColor("#00AAFF"));
            mTextP.setAlpha(180);
            mTextP.setTextSize(mW * 0.030f);
            c.drawText("JARVIS  ·  CONNECTED  ·  SYS ACTIVE", 20f, mH2 - 16f, mTextP);
        }
    }
}
