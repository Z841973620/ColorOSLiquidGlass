package net.z841973620.colorosliquidglass.glass;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import net.z841973620.colorosliquidglass.GlassConfig;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class GlassInstaller {
    private static final String TAG = "ColorOSLiquidGlass";
    // The value must be weak too: GlassDrawable references its owner, so a strong value would
    // keep the WeakHashMap key alive forever (map -> drawable -> owner).
    private static final Map<View, WeakReference<GlassDrawable>> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ImageView, Drawable> ORIGINAL_IMAGE_BACKGROUNDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private GlassInstaller() {}

    public static GlassDrawable get(View view) {
        if (view == null) return null;
        WeakReference<GlassDrawable> reference = INSTALLED.get(view);
        GlassDrawable glass = reference == null ? null : reference.get();
        if (glass == null && view.getBackground() instanceof GlassDrawable) {
            glass = (GlassDrawable) view.getBackground();
        }
        if (glass != null && (reference == null || reference.get() != glass)) {
            INSTALLED.put(view, new WeakReference<>(glass));
        }
        return glass;
    }

    /** Refreshes asymmetric OEM corners without replacing or redrawing the hidden OEM layer. */
    public static void refreshCornerRadii(View view) {
        GlassDrawable glass = get(view);
        float[] radii = readCornerRadii(view);
        if (glass != null && radii != null) glass.setCornerRadii(radii);
    }

    /** Installs glass as the ImageView background so ImageView scale matrices cannot corrupt sampling coordinates. */
    public static void installImage(ImageView view, GlassConfig config) {
        if (view == null || !config.enabled) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installImageNow(view, config);
        } else if (!view.post(() -> installImageNow(view, config))) {
            Log.e(TAG, "ImageView.post rejected for " + view.getClass().getName());
        }
    }

    /** Installs glass as a background while preserving the target's existing content drawable. */
    public static void installBackground(View view, GlassConfig config) {
        if (view == null || !config.enabled) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installBackgroundNow(view, config);
        } else if (!view.post(() -> installBackgroundNow(view, config))) {
            Log.e(TAG, "Background post rejected for " + view.getClass().getName());
        }
    }

    public static void setOverlaySource(View target, View overlay) {
        BackdropCapture.setOverlaySource(target, overlay);
    }

    /** Clear all drag overlay seeds and folder raster caches after finger-up. */
    public static void clearDragOverlays() {
        BackdropCapture.clearAllOverlaySources();
    }

    /** Forces a fresh backdrop sample for a view whose size/position just changed. */
    public static void forceCapture(View view) {
        if (view == null) return;
        BackdropCapture.forceCapture(view);
    }

    /**
     * Soft-refreshes every installed glass host after unlock / resume. Keeps the last good
     * snapshot until a meaningful replacement is captured.
     */
    public static void refreshAll() {
        BackdropCapture.refreshAll();
        List<View> hosts;
        synchronized (INSTALLED) {
            hosts = new ArrayList<>(INSTALLED.keySet());
        }
        for (View view : hosts) {
            if (view != null) view.invalidate();
        }
    }

    /** Removes a previously installed glass background and clears installer bookkeeping. */
    public static void uninstall(View view) {
        if (view == null) return;
        Runnable remove = () -> {
            WeakReference<GlassDrawable> reference = INSTALLED.remove(view);
            GlassDrawable glass = reference == null ? null : reference.get();
            BackdropCapture.unregister(view);
            if (glass != null && view.getBackground() == glass) {
                view.setBackground(null);
            } else if (view.getBackground() instanceof GlassDrawable) {
                view.setBackground(null);
            }
            view.invalidate();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run();
        else if (!view.post(remove)) Log.e(TAG, "Uninstall post rejected for " + view.getClass().getName());
    }

    /** Restores an OEM image when the object leaves the state targeted by the module. */
    public static void restoreImage(ImageView view, Drawable original) {
        if (view == null) return;
        Runnable restore = () -> {
            WeakReference<GlassDrawable> reference = INSTALLED.remove(view);
            GlassDrawable glass = reference == null ? null : reference.get();
            BackdropCapture.unregister(view);
            Drawable originalBackground = ORIGINAL_IMAGE_BACKGROUNDS.remove(view);
            if (glass == null || view.getBackground() == glass) view.setBackground(originalBackground);
            view.setImageDrawable(original);
            view.invalidate();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) restore.run();
        else if (!view.post(restore)) Log.e(TAG, "Image restore post rejected for " + view.getClass().getName());
    }

    private static void installImageNow(ImageView view, GlassConfig config) {
        try {
            GlassDrawable old = get(view);
            if (old != null) {
                float[] liveRadii = readCornerRadii(view);
                if (liveRadii != null) old.setCornerRadii(liveRadii);
                else if (view.getBackground() != old) old.setCornerRadii(detectRadii(view));
                if (view.getDrawable() != null) view.setImageDrawable(null);
                if (view.getBackground() != old) view.setBackground(old);
                return;
            }
            float[] radii = detectRadii(view);
            Drawable original = view.getDrawable();
            ORIGINAL_IMAGE_BACKGROUNDS.put(view, view.getBackground());
            view.setImageDrawable(null);
            view.setRenderEffect(null);
            view.setBackground(null);
            GlassDrawable drawable = new GlassDrawable(view, config);
            drawable.setCornerRadii(radii[0], radii[1], radii[2], radii[3]);
            INSTALLED.put(view, new WeakReference<>(drawable));
            view.setBackground(drawable);
            view.setClipToOutline(false);
            Log.i(TAG, "Installed image layer: " + view.getClass().getName()
                    + " size=" + view.getWidth() + "x" + view.getHeight()
                    + " radii=" + Arrays.toString(radii)
                    + " original=" + (original == null ? "null" : original.getClass().getName()));
        } catch (Throwable error) {
            Log.e(TAG, "Image install failed for " + view.getClass().getName(), error);
        }
    }

    private static void installBackgroundNow(View view, GlassConfig config) {
        try {
            GlassDrawable old = get(view);
            if (old != null) {
                float[] liveRadii = readCornerRadii(view);
                if (liveRadii != null) old.setCornerRadii(liveRadii);
                else if (view.getBackground() != old) old.setCornerRadii(detectRadii(view));
                if (view.getBackground() != old) view.setBackground(old);
                return;
            }
            float[] radii = detectRadii(view);
            try { view.setRenderEffect(null); } catch (Throwable ignored) { }
            view.setBackground(null);
            GlassDrawable drawable = new GlassDrawable(view, config);
            drawable.setCornerRadii(radii[0], radii[1], radii[2], radii[3]);
            INSTALLED.put(view, new WeakReference<>(drawable));
            view.setBackground(drawable);
            view.setClipToOutline(false);
            Log.i(TAG, "Installed background layer: " + view.getClass().getName()
                    + " size=" + view.getWidth() + "x" + view.getHeight()
                    + " radii=" + Arrays.toString(radii));
        } catch (Throwable error) {
            Log.e(TAG, "Background install failed for " + view.getClass().getName(), error);
        }
    }

    private static float[] detectRadii(View view) {
        // Some OEM views expose live four-corner arrays. Reading this before the OEM layer is
        // suppressed preserves non-uniform folder icon shapes.
        float[] liveRadii = readCornerRadii(view);
        if (liveRadii != null) return liveRadii;
        Object pressRadius = readField(view, "mDrawableRadius");
        if (pressRadius instanceof Number && ((Number) pressRadius).floatValue() > 0f) {
            return uniformRadii(((Number) pressRadius).floatValue());
        }
        Drawable d = view.getBackground();
        // Prefer drawable-specific radius over View's often-generic background outline.
        float outlineRadius = drawableRadius(d);
        if (outlineRadius <= 0f && view instanceof ImageView) {
            outlineRadius = drawableRadius(((ImageView) view).getDrawable());
        }
        if (outlineRadius > 0f) return uniformRadii(outlineRadius);
        try {
            Outline o = new Outline();
            if (view.getOutlineProvider() != null) view.getOutlineProvider().getOutline(view, o);
            if (o.getRadius() > 0f) return uniformRadii(o.getRadius());
        } catch (Throwable ignored) { }
        if (d instanceof GradientDrawable) {
            try {
                Field f = GradientDrawable.class.getDeclaredField("mGradientState");
                f.setAccessible(true);
                Object state = f.get(d);
                Field rf = state.getClass().getDeclaredField("mRadius");
                rf.setAccessible(true);
                float r = rf.getFloat(state);
                if (r > 0) return uniformRadii(r);
            } catch (Throwable ignored) { }
        }
        return uniformRadii(16f * view.getResources().getDisplayMetrics().density);
    }

    private static float[] readCornerRadii(View view) {
        Object cornerArray = readField(view, "mCornerRadii");
        if (!(cornerArray instanceof float[]) || ((float[]) cornerArray).length < 8) return null;
        float[] c = (float[]) cornerArray;
        float[] result = new float[] { Math.max(c[0], c[1]), Math.max(c[2], c[3]),
                Math.max(c[4], c[5]), Math.max(c[6], c[7]) };
        return maxRadius(result) > 0f ? result : null;
    }

    private static float drawableRadius(Drawable drawable) {
        if (drawable == null) return 0f;
        for (Class<?> c = drawable.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod("getCornerRadius");
                method.setAccessible(true);
                Object value = method.invoke(drawable);
                if (value instanceof Number && ((Number) value).floatValue() > 0f) {
                    return ((Number) value).floatValue();
                }
            } catch (Throwable ignored) { }
        }
        try {
            Outline outline = new Outline();
            drawable.getOutline(outline);
            return Math.max(0f, outline.getRadius());
        } catch (Throwable ignored) { return 0f; }
    }

    private static Object readField(Object object, String name) {
        for (Class<?> c = object == null ? null : object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static float[] uniformRadii(float radius) {
        return new float[] { radius, radius, radius, radius };
    }

    private static float maxRadius(float[] radii) {
        float max = 0f;
        if (radii != null) for (float radius : radii) max = Math.max(max, radius);
        return max;
    }
}
