package net.z841973620.colorosliquidglass.glass;

import android.os.Bundle;
import android.os.SystemClock;

/**
 * ColorOS zooms the system wallpaper on a separate surface via
 * {@code WallpaperManager.sendWallpaperCommand} (scale 1.0 ↔ 1.2 for Recents / app
 * transitions). {@link android.app.WallpaperManager#getDrawable()} is not scaled, so glass
 * capture must synthesize the same center-scale while the wallpaper animation runs.
 */
public final class WallpaperScaleTracker {
    private static final Object LOCK = new Object();
    private static float currentScale = 1f;
    private static float fromScale = 1f;
    private static float toScale = 1f;
    private static long startUptimeMs;
    private static long durationMs;
    private static boolean animating;

    private WallpaperScaleTracker() {}

    /** Starts tracking a wallpaper command whose Bundle carries scale_from/to + duration. */
    public static void onWallpaperCommand(Bundle extras) {
        if (extras == null) return;
        float from = extras.getFloat("scale_from_x", Float.NaN);
        float to = extras.getFloat("scale_to_x", Float.NaN);
        if (Float.isNaN(from) || Float.isNaN(to)) return;
        int duration = extras.getInt("duration", 0);
        start(from, to, duration);
    }

    static void start(float from, float to, long duration) {
        synchronized (LOCK) {
            fromScale = sanitize(from);
            toScale = sanitize(to);
            durationMs = Math.max(0L, duration);
            startUptimeMs = SystemClock.uptimeMillis();
            if (durationMs <= 1L || Math.abs(fromScale - toScale) < 0.0005f) {
                currentScale = toScale;
                animating = false;
            } else {
                currentScale = fromScale;
                animating = true;
            }
        }
    }

    static void setImmediate(float scale) {
        synchronized (LOCK) {
            currentScale = sanitize(scale);
            fromScale = currentScale;
            toScale = currentScale;
            animating = false;
        }
    }

    /** Visual wallpaper scale relative to the unscaled WallpaperManager drawable (1 = identity). */
    static float current() {
        synchronized (LOCK) {
            if (!animating) return currentScale;
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            if (elapsed >= durationMs) {
                currentScale = toScale;
                animating = false;
                return currentScale;
            }
            float t = elapsed / (float) durationMs;
            // Critically-damped-ish ease-out: ColorOS uses spring(stiffness=50, damping=1).
            t = 1f - (float) Math.pow(1f - t, 3);
            currentScale = fromScale + (toScale - fromScale) * t;
            return currentScale;
        }
    }

    static boolean isAnimating() {
        current();
        synchronized (LOCK) {
            return animating;
        }
    }

    private static float sanitize(float scale) {
        if (Float.isNaN(scale) || scale <= 0.01f) return 1f;
        return Math.max(0.5f, Math.min(2f, scale));
    }
}
