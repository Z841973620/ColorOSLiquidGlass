package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Paints a Recents {@code TaskView} snapshot into a glass host's local sample space.
 * Uses {@link GlassHwRasterizer} for HARDWARE thumbnails — never
 * {@link View#LAYER_TYPE_SOFTWARE} or per-frame software view draws.
 */
final class TaskContentOverlay {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** Soft ARGB copies of HARDWARE task snapshots; keyed by the HW bitmap identity. */
    private static final Map<Bitmap, Bitmap> SOFTWARE_COPIES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private TaskContentOverlay() {}

    static boolean isTaskView(View view) {
        if (view == null) return false;
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.endsWith(".TaskView") || name.endsWith("TaskView")) return true;
        }
        return false;
    }

    static void paintIntoTargetLocal(View glassHost, Canvas canvas) {
        if (glassHost == null || canvas == null) return;
        View seed = BackdropCapture.overlaySourceOf(glassHost);
        if (!isTaskView(seed)) return;

        View snapshot = resolveSnapshot(seed);
        if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) return;
        if (snapshot.getVisibility() != View.VISIBLE) return;

        Matrix targetToGlobal = new Matrix();
        glassHost.transformMatrixToGlobal(targetToGlobal);
        Matrix globalToTarget = new Matrix();
        boolean haveMatrix = targetToGlobal.invert(globalToTarget);

        int save = canvas.save();
        try {
            if (haveMatrix) {
                Matrix snapshotToGlobal = new Matrix();
                snapshot.transformMatrixToGlobal(snapshotToGlobal);
                Matrix snapshotToTarget = new Matrix();
                snapshotToTarget.setConcat(globalToTarget, snapshotToGlobal);
                canvas.concat(snapshotToTarget);
            } else {
                int[] glassLoc = new int[2];
                int[] snapLoc = new int[2];
                glassHost.getLocationInWindow(glassLoc);
                snapshot.getLocationInWindow(snapLoc);
                canvas.translate(snapLoc[0] - glassLoc[0], snapLoc[1] - glassLoc[1]);
            }
            paintSnapshotContent(snapshot, canvas);
        } catch (Throwable t) {
            Log.w(TAG, "TaskContentOverlay paint failed", t);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static boolean paintSnapshotContent(View snapshot, Canvas canvas) {
        Bitmap soft = softwareThumbnail(snapshot);
        if (soft == null || soft.isRecycled()) return false;

        float width = snapshot.getWidth();
        float height = snapshot.getHeight();
        Matrix preview = previewMatrix(snapshot);
        if (preview != null) {
            BitmapShader shader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(preview);
            Paint paint = new Paint(BITMAP_PAINT);
            paint.setShader(shader);
            canvas.drawRect(0f, 0f, width, height, paint);
        } else {
            canvas.drawBitmap(soft, null, new RectF(0f, 0f, width, height), BITMAP_PAINT);
        }
        return true;
    }

    /**
     * Returns a software bitmap suitable for the AGSL backdrop canvas.
     * HARDWARE snapshots are converted once via {@link GlassHwRasterizer} and cached.
     */
    private static Bitmap softwareThumbnail(View snapshot) {
        Bitmap raw = thumbnailBitmap(snapshot);
        if (raw == null || raw.isRecycled()) return null;
        if (raw.getConfig() != Bitmap.Config.HARDWARE) return raw;

        synchronized (SOFTWARE_COPIES) {
            Bitmap cached = SOFTWARE_COPIES.get(raw);
            if (cached != null && !cached.isRecycled()) return cached;
        }

        final Bitmap hw = raw;
        final int w = Math.max(1, hw.getWidth());
        final int h = Math.max(1, hw.getHeight());
        Bitmap soft = GlassHwRasterizer.render(w, h, c ->
                c.drawBitmap(hw, null, new RectF(0f, 0f, w, h), BITMAP_PAINT));
        if (soft == null || soft.isRecycled()) {
            // Last resort: Bitmap.copy (may fail for some buffers; never touch view layer type).
            try {
                soft = hw.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (soft != null && !soft.isRecycled()) {
            synchronized (SOFTWARE_COPIES) {
                SOFTWARE_COPIES.put(hw, soft);
            }
        }
        return soft;
    }

    private static Matrix previewMatrix(View snapshot) {
        Object helper = invokeNoArgs(snapshot, "getPreviewPositionHelper");
        if (helper == null) helper = field(snapshot, "mPreviewPositionHelper");
        Object matrix = field(helper, "mMatrix");
        if (matrix instanceof Matrix) {
            Matrix copy = new Matrix();
            copy.set((Matrix) matrix);
            return copy;
        }
        return null;
    }

    private static View resolveSnapshot(View taskView) {
        Object snap = field(taskView, "mSnapshotView");
        if (snap instanceof View) return (View) snap;
        Object via = invokeNoArgs(taskView, "getThumbnail");
        if (via instanceof View) return (View) via;
        return taskView;
    }

    private static Bitmap thumbnailBitmap(View snapshot) {
        Object bitmap = invokeNoArgs(snapshot, "getThumbnail");
        if (bitmap instanceof Bitmap) return (Bitmap) bitmap;
        Object data = field(snapshot, "mThumbnailData");
        Object embedded = field(data, "thumbnail");
        return embedded instanceof Bitmap ? (Bitmap) embedded : null;
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
