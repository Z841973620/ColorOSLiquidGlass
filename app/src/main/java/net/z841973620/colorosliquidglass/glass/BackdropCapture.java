package net.z841973620.colorosliquidglass.glass;

import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Captures a target-local backdrop for one Launcher folder background.
 * Large folders are expensive to sample, so capture is dirty-driven, downscaled, and
 * never re-entered from the invalidate that publishes a new frame.
 */
final class BackdropCapture implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    private static final Map<View, BackdropCapture> CAPTURES = new WeakHashMap<>();
    private static final Map<View, WeakReference<View>> OVERLAY_SOURCES = new WeakHashMap<>();
    /** While moving/resizing, refresh as often as predraw allows (1ms pacing). */
    private static final long ACTIVE_CAPTURE_INTERVAL_MS = 1L;
    /** Idle desktop folders barely change; avoid full-hierarchy redraws every frame. */
    private static final long IDLE_CAPTURE_INTERVAL_MS = 1000L;
    /** Keep active pacing until folder/wallpaper has been still for this long. */
    private static final long IDLE_SETTLE_MS = 1000L;
    /** Cap software bitmap edge so 2x2 folders do not allocate huge ARGB buffers. */
    private static final int MAX_CAPTURE_EDGE_PX = 320;

    static BackdropCapture register(View target) {
        synchronized (CAPTURES) {
            BackdropCapture capture = CAPTURES.get(target);
            if (capture == null) {
                capture = new BackdropCapture(target);
                CAPTURES.put(target, capture);
            }
            capture.markDirty();
            target.invalidate();
            return capture;
        }
    }

    static void unregister(View target) {
        synchronized (CAPTURES) {
            BackdropCapture capture = CAPTURES.remove(target);
            OVERLAY_SOURCES.remove(target);
            if (capture != null) capture.dispose();
        }
    }

    static void setOverlaySource(View target, View overlay) {
        synchronized (CAPTURES) {
            if (target == null || overlay == null || overlay == target) {
                if (target != null) OVERLAY_SOURCES.remove(target);
                return;
            }
            OVERLAY_SOURCES.put(target, new WeakReference<>(overlay));
            BackdropCapture capture = CAPTURES.get(target);
            if (capture != null) capture.markDirty();
        }
        if (target != null) target.invalidate();
    }

    /** Forces an immediate target-local capture, used while resize-frame geometry is animating. */
    static void forceCapture(View target) {
        if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        BackdropCapture capture;
        synchronized (CAPTURES) {
            capture = CAPTURES.get(target);
            if (capture == null) {
                capture = new BackdropCapture(target);
                CAPTURES.put(target, capture);
            }
        }
        View root = capture.rootRef == null ? null : capture.rootRef.get();
        if (root == null) {
            root = rootOf(target);
            capture.rootRef = new WeakReference<>(root);
        }
        if (capture.recording) return;
        capture.activeUntil = SystemClock.uptimeMillis() + IDLE_SETTLE_MS;
        capture.dirty = true;
        capture.capture(root, target, true);
        capture.lastCapture = SystemClock.uptimeMillis();
    }

    private final WeakReference<View> targetRef;
    private WeakReference<View> rootRef;
    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private int[] rowScratch;
    private final int[] locationScratch = new int[2];
    private final Rect visibleScratch = new Rect();
    private boolean validFrame;
    private long lastCapture;
    private long activeUntil;
    private boolean recording;
    private boolean observerAttached;
    /** Set after publishing a frame so the resulting invalidate does not recapture immediately. */
    private boolean skipCaptureFromSelfInvalidate;
    private boolean dirty = true;
    private int lastTargetW;
    private int lastTargetH;
    private int lastWindowX = Integer.MIN_VALUE;
    private int lastWindowY = Integer.MIN_VALUE;
    private int lastVisibleW = Integer.MIN_VALUE;
    private int lastVisibleH = Integer.MIN_VALUE;
    private float lastHierarchyScale = Float.NaN;
    private float lastRootScale = Float.NaN;
    private float lastWallpaperDepth = Float.NaN;
    private float bitmapScaleX = 1f;
    private float bitmapScaleY = 1f;

    private BackdropCapture(View target) {
        targetRef = new WeakReference<>(target);
        View root = rootOf(target);
        rootRef = new WeakReference<>(root);
        root.addOnAttachStateChangeListener(this);
        attach(root);
    }

    Bitmap bitmap() {
        return validFrame && frontBitmap != null && !frontBitmap.isRecycled() ? frontBitmap : null;
    }

    /** View-space → capture-bitmap scale (1,1 when capture is full resolution). */
    float[] sampleScale() {
        return new float[] { bitmapScaleX, bitmapScaleY };
    }

    private void markDirty() {
        dirty = true;
        activeUntil = Math.max(activeUntil, SystemClock.uptimeMillis() + IDLE_SETTLE_MS);
    }

    @Override public boolean onPreDraw() {
        View target = targetRef.get();
        View root = rootRef == null ? null : rootRef.get();
        if (target == null || root == null) return true;
        // During resize-frame drawing ColorOS sets mBgView to INVISIBLE and paints on the
        // FolderIcon canvas instead. Keep sampling while the folder icon itself is shown.
        View owner = captureOwner(target);
        if (recording || owner == null || !owner.isShown()
                || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return true;
        }

        boolean geometryChanged = updateGeometryState(target, owner, root);
        if (geometryChanged) {
            dirty = true;
            activeUntil = SystemClock.uptimeMillis() + IDLE_SETTLE_MS;
        }

        // Skip the invalidate echo from publishing a frame, but never drop a real geometry /
        // background-scale change (Recents wallpaper zoom keeps folder layout coords fixed).
        if (skipCaptureFromSelfInvalidate) {
            skipCaptureFromSelfInvalidate = false;
            if (!geometryChanged && !dirty) return true;
        }

        long now = SystemClock.uptimeMillis();
        boolean active = now <= activeUntil || geometryChanged;
        long interval = active ? ACTIVE_CAPTURE_INTERVAL_MS : IDLE_CAPTURE_INTERVAL_MS;
        // Spread idle refreshes so many large folders do not all redraw the hierarchy together.
        if (!active) interval += Math.floorMod(System.identityHashCode(this), 45);
        if (validFrame && !dirty && !geometryChanged && now - lastCapture < interval) return true;

        capture(root, target, false);
        lastCapture = now;
        dirty = false;
        return true;
    }

    /**
     * Tracks layout position plus transform scale. Opening Recents often zooms wallpaper /
     * DragLayer without changing FolderIcon layout left/top, so scale must dirty capture too.
     */
    private boolean updateGeometryState(View target, View owner, View root) {
        target.getLocationInWindow(locationScratch);
        int w = target.getWidth();
        int h = target.getHeight();
        float hierarchyScale = cumulativeScale(owner);
        float rootScale = Math.abs(root.getScaleX() * root.getScaleY());
        float wallpaperDepth = readWallpaperDepth(root);
        int visibleW = 0;
        int visibleH = 0;
        if (owner.getGlobalVisibleRect(visibleScratch)) {
            visibleW = visibleScratch.width();
            visibleH = visibleScratch.height();
        }

        boolean changed = w != lastTargetW || h != lastTargetH
                || locationScratch[0] != lastWindowX || locationScratch[1] != lastWindowY
                || visibleW != lastVisibleW || visibleH != lastVisibleH
                || scaleChanged(hierarchyScale, lastHierarchyScale)
                || scaleChanged(rootScale, lastRootScale)
                || scaleChanged(wallpaperDepth, lastWallpaperDepth);
        lastTargetW = w;
        lastTargetH = h;
        lastWindowX = locationScratch[0];
        lastWindowY = locationScratch[1];
        lastVisibleW = visibleW;
        lastVisibleH = visibleH;
        lastHierarchyScale = hierarchyScale;
        lastRootScale = rootScale;
        lastWallpaperDepth = wallpaperDepth;
        return changed;
    }

    private static boolean scaleChanged(float next, float previous) {
        if (Float.isNaN(next) && Float.isNaN(previous)) return false;
        if (Float.isNaN(previous) || Float.isNaN(next)) return true;
        return Math.abs(next - previous) > 0.0005f;
    }

    /** Product of scaleX*scaleY from {@code view} up to (and including) the root view. */
    private static float cumulativeScale(View view) {
        float scale = 1f;
        for (View current = view; current != null; ) {
            scale *= Math.abs(current.getScaleX() * current.getScaleY());
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return scale;
    }

    /**
     * ColorOS Recents zooms wallpaper through DepthController even when folder layout coords
     * stay fixed. Reading current depth/zoom dirty-captures those frames.
     */
    private static float readWallpaperDepth(View root) {
        if (root == null) return Float.NaN;
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher", false,
                    root.getContext().getClassLoader());
            Object launcher = null;
            try {
                launcher = launcherClass.getMethod("getLauncher", android.content.Context.class)
                        .invoke(null, root.getContext());
            } catch (Throwable ignored) { }
            if (launcher == null) {
                try {
                    Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
                    launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
                } catch (Throwable ignored) { }
            }
            if (launcher == null) return Float.NaN;
            Object depthController = launcherClass.getMethod("getDepthController").invoke(launcher);
            if (depthController == null) return Float.NaN;
            try {
                Object depth = depthController.getClass().getMethod("getCurrentDepth")
                        .invoke(depthController);
                if (depth instanceof Number) return ((Number) depth).floatValue();
            } catch (Throwable ignored) { }
            java.lang.reflect.Field field = null;
            for (Class<?> c = depthController.getClass(); c != null && field == null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField("mDepth");
                } catch (Throwable ignored) { }
            }
            if (field == null) return Float.NaN;
            field.setAccessible(true);
            return field.getFloat(depthController);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private void capture(View root, View target, boolean forced) {
        View owner = captureOwner(target);
        float oldAlpha = owner.getAlpha();
        recording = true;
        boolean complete = false;
        try {
            int viewW = target.getWidth();
            int viewH = target.getHeight();
            float scale = captureScale(viewW, viewH);
            int bitmapW = Math.max(1, Math.round(viewW * scale));
            int bitmapH = Math.max(1, Math.round(viewH * scale));
            bitmapScaleX = bitmapW / (float) viewW;
            bitmapScaleY = bitmapH / (float) viewH;
            ensureBackBitmap(bitmapW, bitmapH);
            Canvas canvas = new Canvas(backBitmap);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            canvas.save();
            canvas.scale(scale, scale);
            translateRootToTarget(root, target, canvas);
            drawWallpaper(root, canvas);
            owner.setAlpha(0f);
            root.draw(canvas);
            drawOverlaySource(target, canvas);
            canvas.restore();
            // Validate only until the first good frame; later / forced frames skip the pixel scan.
            complete = forced || validFrame || isMeaningful(backBitmap);
        } catch (Throwable ignored) {
            // Keep the previous useful frame.
        } finally {
            owner.setAlpha(oldAlpha);
            recording = false;
        }
        if (complete) {
            Bitmap previous = frontBitmap;
            frontBitmap = backBitmap;
            backBitmap = previous;
            validFrame = true;
            skipCaptureFromSelfInvalidate = true;
            target.invalidate();
        }
    }

    private static float captureScale(int width, int height) {
        int edge = Math.max(width, height);
        if (edge <= MAX_CAPTURE_EDGE_PX) return 1f;
        return MAX_CAPTURE_EDGE_PX / (float) edge;
    }

    private static void translateRootToTarget(View root, View target, Canvas canvas) {
        int[] rootLocation = new int[2];
        int[] targetLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        target.getLocationInWindow(targetLocation);
        canvas.translate(rootLocation[0] - targetLocation[0],
                rootLocation[1] - targetLocation[1]);
    }

    private static void drawOverlaySource(View target, Canvas canvas) {
        View overlay;
        synchronized (CAPTURES) {
            WeakReference<View> reference = OVERLAY_SOURCES.get(target);
            overlay = reference == null ? null : reference.get();
        }
        if (overlay == null || !overlay.isShown() || overlay.getWidth() <= 0 || overlay.getHeight() <= 0) {
            return;
        }
        int[] targetLocation = new int[2];
        int[] overlayLocation = new int[2];
        target.getLocationInWindow(targetLocation);
        overlay.getLocationInWindow(overlayLocation);
        int save = canvas.save();
        canvas.translate(overlayLocation[0] - targetLocation[0],
                overlayLocation[1] - targetLocation[1]);
        overlay.draw(canvas);
        canvas.restoreToCount(save);
    }

    private boolean isMeaningful(Bitmap bitmap) {
        if (bitmap == null) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return false;
        ensureRowBuffer(width);
        int stepX = Math.max(1, width / 8);
        int stepY = Math.max(1, height / 8);
        int opaque = 0;
        int min = 255;
        int max = 0;
        for (int y = stepY / 2; y < height; y += stepY) {
            bitmap.getPixels(rowScratch, 0, width, 0, y, width, 1);
            for (int x = stepX / 2; x < width; x += stepX) {
                int color = rowScratch[x];
                if (Color.alpha(color) < 24) continue;
                opaque++;
                int luma = (Color.red(color) * 54 + Color.green(color) * 183
                        + Color.blue(color) * 19) >> 8;
                min = Math.min(min, luma);
                max = Math.max(max, luma);
            }
        }
        return opaque >= 3 && max - min >= 2;
    }

    private void ensureRowBuffer(int width) {
        if (rowScratch == null || rowScratch.length < width) {
            rowScratch = new int[width];
        }
    }

    private static View captureOwner(View target) {
        View best = target;
        for (View current = target; current != null; ) {
            // ColorOS uses FlexibleFolderIcon (and similar *FolderIcon subclasses). Matching
            // only ".FolderIcon" left the host visible, so preview icons were sampled into the
            // backdrop and then refracted along the glass edge.
            if (isFolderIconView(current)) return current;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return best;
    }

    private static boolean isFolderIconView(View view) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.endsWith(".FolderIcon") || name.endsWith("FolderIcon")) return true;
        }
        return false;
    }

    private void ensureBackBitmap(int width, int height) {
        if (backBitmap != null && !backBitmap.isRecycled()
                && backBitmap.getWidth() == width && backBitmap.getHeight() == height) return;
        recycle(backBitmap);
        backBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private static void drawWallpaper(View root, Canvas canvas) {
        try {
            Drawable wallpaper = WallpaperManager.getInstance(root.getContext()).getDrawable();
            if (wallpaper == null) return;
            Rect old = wallpaper.copyBounds();
            int sourceWidth = Math.max(1, wallpaper.getIntrinsicWidth());
            int sourceHeight = Math.max(1, wallpaper.getIntrinsicHeight());
            int width = root.getWidth();
            int height = root.getHeight();
            float scale = Math.max(width / (float) sourceWidth, height / (float) sourceHeight);
            int drawWidth = Math.round(sourceWidth * scale);
            int drawHeight = Math.round(sourceHeight * scale);
            int left = (width - drawWidth) / 2;
            int top = (height - drawHeight) / 2;
            wallpaper.setBounds(left, top, left + drawWidth, top + drawHeight);
            wallpaper.draw(canvas);
            wallpaper.setBounds(old);
        } catch (Throwable ignored) { }
    }

    private static View rootOf(View target) {
        View root = target.getRootView();
        return root == null ? target : root;
    }

    private void attach(View root) {
        if (observerAttached) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.addOnPreDrawListener(this);
            observerAttached = true;
        }
    }

    private void detach(View root) {
        if (!observerAttached) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observerAttached = false;
    }

    private void dispose() {
        View root = rootRef == null ? null : rootRef.get();
        if (root != null) {
            detach(root);
            root.removeOnAttachStateChangeListener(this);
        }
        recycle(frontBitmap);
        recycle(backBitmap);
        frontBitmap = null;
        backBitmap = null;
        validFrame = false;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    @Override public void onViewAttachedToWindow(View v) {
        View target = targetRef.get();
        View root = target == null ? v : rootOf(target);
        if (root != v) {
            v.removeOnAttachStateChangeListener(this);
            root.addOnAttachStateChangeListener(this);
        }
        rootRef = new WeakReference<>(root);
        attach(root);
        markDirty();
    }

    @Override public void onViewDetachedFromWindow(View v) {
        detach(v);
    }
}
