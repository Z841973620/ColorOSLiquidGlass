package net.z841973620.colorosliquidglass.glass;

import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

    /**
     * Captures a target-local backdrop for one Launcher folder background.
     * Capture bitmaps stay 1:1 with the glass view so backdrop detail matches screen pixels.
     * Sampling runs every frame while the glass is moving (geometry / drag / wallpaper zoom);
     * while stationary the last good frame is kept (no idle re-sample).
     * <p>
     * Dragged-folder glass paints wallpaper + {@link DesktopIconOverlay} without hiding
     * {@code *DragView} (hiding flickers). Idle folder icons still hide the host and
     * software-draw the hierarchy.
     */
    final class BackdropCapture implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
    private static final Map<View, BackdropCapture> CAPTURES = new WeakHashMap<>();
    private static final Map<View, WeakReference<View>> OVERLAY_SOURCES = new WeakHashMap<>();

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
        View previous;
        synchronized (CAPTURES) {
            WeakReference<View> reference = target == null ? null : OVERLAY_SOURCES.get(target);
            previous = reference == null ? null : reference.get();
            if (target == null || overlay == null || overlay == target) {
                if (target != null) OVERLAY_SOURCES.remove(target);
            } else {
                OVERLAY_SOURCES.put(target, new WeakReference<>(overlay));
                BackdropCapture capture = CAPTURES.get(target);
                if (capture != null) {
                    capture.markDirty();
                    capture.dragSeedGeneration++;
                }
            }
        }
        if (previous != overlay) {
            DesktopIconOverlay.clearFolderSnaps();
        }
        if (target != null) target.invalidate();
    }

    /** Drop every drag-glass overlay seed (finger-up must not leave seeds on FolderIcons). */
    static void clearAllOverlaySources() {
        synchronized (CAPTURES) {
            OVERLAY_SOURCES.clear();
        }
        DesktopIconOverlay.clearFolderSnaps();
    }

    /** Desktop CellLayout (or page) bound for {@link DesktopIconOverlay}. */
    static View overlaySourceOf(View target) {
        if (target == null) return null;
        synchronized (CAPTURES) {
            WeakReference<View> reference = OVERLAY_SOURCES.get(target);
            return reference == null ? null : reference.get();
        }
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
        capture.dirty = true;
        capture.capture(root, target, true);
    }

    /**
     * Marks every live capture dirty. Used after unlock / resume when wallpaper and
     * DragLayer alpha settle asynchronously.
     * Keeps the last good frame until a meaningful replacement arrives (no fallback flash).
     */
    static void refreshAll() {
        List<View> targets = new ArrayList<>();
        synchronized (CAPTURES) {
            for (Map.Entry<View, BackdropCapture> entry : CAPTURES.entrySet()) {
                View target = entry.getKey();
                BackdropCapture capture = entry.getValue();
                if (capture == null) continue;
                capture.dirty = true;
                // Force geometry re-evaluation so depth/alpha changes are not missed.
                capture.lastWallpaperDepth = Float.NaN;
                capture.lastRootScale = Float.NaN;
                capture.lastHierarchyScale = Float.NaN;
                capture.lastHierarchyAlpha = Float.NaN;
                capture.lastRootAlpha = Float.NaN;
                capture.lastWallpaperScale = Float.NaN;
                capture.lastWorkspaceScrollX = Integer.MIN_VALUE;
                capture.lastWorkspaceScrollY = Integer.MIN_VALUE;
                if (target != null) targets.add(target);
            }
        }
        for (View target : targets) target.invalidate();
    }

    private final WeakReference<View> targetRef;
    private WeakReference<View> rootRef;
    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private int[] rowScratch;
    private final int[] locationScratch = new int[2];
    private final Rect visibleScratch = new Rect();
    private boolean validFrame;
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
    private float lastHierarchyAlpha = Float.NaN;
    private float lastRootAlpha = Float.NaN;
    private float lastWallpaperScale = Float.NaN;
    private int lastWorkspaceScrollX = Integer.MIN_VALUE;
    private int lastWorkspaceScrollY = Integer.MIN_VALUE;
    private float bitmapScaleX = 1f;
    private float bitmapScaleY = 1f;
    /** Bumps when overlay CellLayout changes so cross-page samples always publish. */
    private int dragSeedGeneration;
    private int publishedDragSeedGeneration = -1;

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

    /** Latest useful backdrop for a glass host, if any. */
    static Bitmap snapshotOf(View target) {
        if (target == null) return null;
        synchronized (CAPTURES) {
            BackdropCapture capture = CAPTURES.get(target);
            return capture == null ? null : capture.bitmap();
        }
    }

    /** View-space → capture-bitmap scale (1,1 when capture is full resolution). */
    float[] sampleScale() {
        return new float[] { bitmapScaleX, bitmapScaleY };
    }

    private void markDirty() {
        dirty = true;
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
        if (geometryChanged) dirty = true;

        // Lock / early unlock: DragLayer alpha is 0. Skip sampling so we do not overwrite a
        // good snapshot with an empty frame; stay dirty until the hierarchy is visible again.
        if (cumulativeAlpha(owner) < 0.08f || root.getAlpha() < 0.08f) {
            dirty = true;
            return true;
        }

        // Skip the invalidate echo from publishing a frame, but never drop a real geometry /
        // background-scale change (Recents wallpaper zoom keeps folder layout coords fixed).
        if (skipCaptureFromSelfInvalidate) {
            skipCaptureFromSelfInvalidate = false;
            if (!geometryChanged && !dirty) return true;
        }

        boolean wallpaperAnimating = WallpaperScaleTracker.isAnimating();
        boolean underDrag = dragViewAncestor(target) != null;
        boolean moving = geometryChanged || underDrag || wallpaperAnimating;

        // Stationary: keep the last good frame (no idle re-sample). Still allow a one-shot
        // when there is no frame yet, or dirty was set by unlock / install / forceCapture.
        if (!moving) {
            if (validFrame && !dirty) return true;
        }

        capture(root, target, false);
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
        float hierarchyAlpha = cumulativeAlpha(owner);
        float rootAlpha = root.getAlpha();
        float wallpaperScale = WallpaperScaleTracker.current();
        int[] workspaceScroll = readWorkspaceScroll(owner, root);
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
                || scaleChanged(wallpaperDepth, lastWallpaperDepth)
                || scaleChanged(hierarchyAlpha, lastHierarchyAlpha)
                || scaleChanged(rootAlpha, lastRootAlpha)
                || scaleChanged(wallpaperScale, lastWallpaperScale)
                || workspaceScroll[0] != lastWorkspaceScrollX
                || workspaceScroll[1] != lastWorkspaceScrollY;
        lastTargetW = w;
        lastTargetH = h;
        lastWindowX = locationScratch[0];
        lastWindowY = locationScratch[1];
        lastVisibleW = visibleW;
        lastVisibleH = visibleH;
        lastHierarchyScale = hierarchyScale;
        lastRootScale = rootScale;
        lastWallpaperDepth = wallpaperDepth;
        lastHierarchyAlpha = hierarchyAlpha;
        lastRootAlpha = rootAlpha;
        lastWallpaperScale = wallpaperScale;
        lastWorkspaceScrollX = workspaceScroll[0];
        lastWorkspaceScrollY = workspaceScroll[1];
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

    /** Product of alphas up the parent chain (DragLayer goes to 0 while the keyguard is up). */
    private static float cumulativeAlpha(View view) {
        float alpha = 1f;
        for (View current = view; current != null; ) {
            alpha *= current.getAlpha();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return alpha;
    }

    /**
     * Desktop page flips scroll Workspace under a stationary DragView. Tracking that scroll
     * dirties capture even when the glass host's window position is unchanged.
     */
    private static int[] readWorkspaceScroll(View owner, View root) {
        View workspace = findWorkspaceAncestor(owner);
        if (workspace == null) workspace = findWorkspaceViaLauncher(root);
        if (workspace == null) return new int[] { Integer.MIN_VALUE, Integer.MIN_VALUE };
        return new int[] { workspace.getScrollX(), workspace.getScrollY() };
    }

    private static View findWorkspaceAncestor(View start) {
        for (View current = start; current != null; ) {
            if (isWorkspaceView(current)) return current;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    private static boolean isWorkspaceView(View view) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals("com.android.launcher3.Workspace")
                    || name.equals("com.android.launcher3.OplusWorkspace")
                    || name.endsWith(".Workspace")) {
                return true;
            }
        }
        return false;
    }

    private static View findWorkspaceViaLauncher(View root) {
        if (root == null) return null;
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
            if (launcher == null) return null;
            Object workspace = launcherClass.getMethod("getWorkspace").invoke(launcher);
            return workspace instanceof View ? (View) workspace : null;
        } catch (Throwable ignored) {
            return null;
        }
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
        boolean dragGlass = overlaySourceOf(target) != null && dragViewAncestor(target) != null;
        int seedGen = dragSeedGeneration;
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
            if (dragGlass) {
                // Never hide *DragView during drag capture — that flickers the preview.
                canvas.restore();
                canvas.save();
                canvas.scale(scale, scale);
                try {
                    DesktopIconOverlay.paintIntoTargetLocal(target, canvas);
                } catch (Throwable ignored) {
                }
                canvas.restore();
            } else {
                View dragView = dragViewAncestor(target);
                float oldDragAlpha = dragView == null ? 1f : dragView.getAlpha();
                owner.setAlpha(0f);
                if (dragView != null) dragView.setAlpha(0f);
                try {
                    root.draw(canvas);
                } catch (Throwable ignored) {
                } finally {
                    if (dragView != null) dragView.setAlpha(oldDragAlpha);
                }
                canvas.restore();
            }
            boolean seedChanged = dragGlass && seedGen != publishedDragSeedGeneration;
            complete = isMeaningful(backBitmap)
                    || (forced && !validFrame)
                    || (dragGlass && (forced || seedChanged));
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
            if (dragGlass) publishedDragSeedGeneration = seedGen;
            skipCaptureFromSelfInvalidate = true;
            target.invalidate();
        }
    }

    /** Always capture at view pixel size — downscaling made the glass backdrop look soft. */
    private static float captureScale(int width, int height) {
        return 1f;
    }

    private static void translateRootToTarget(View root, View target, Canvas canvas) {
        int[] rootLocation = new int[2];
        int[] targetLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        target.getLocationInWindow(targetLocation);
        canvas.translate(rootLocation[0] - targetLocation[0],
                rootLocation[1] - targetLocation[1]);
    }

    /** Match DragView and OEM subclasses (OplusDragView, …). */
    private static View dragViewAncestor(View target) {
        for (View current = target; current != null; ) {
            String name = current.getClass().getSimpleName();
            if (name != null && name.endsWith("DragView")) return current;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
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
            float cover = Math.max(width / (float) sourceWidth, height / (float) sourceHeight);
            int drawWidth = Math.round(sourceWidth * cover);
            int drawHeight = Math.round(sourceHeight * cover);
            int left = (width - drawWidth) / 2;
            int top = (height - drawHeight) / 2;
            wallpaper.setBounds(left, top, left + drawWidth, top + drawHeight);
            // System wallpaper zoom lives on another surface; mirror its center-scale here.
            float zoom = WallpaperScaleTracker.current();
            int save = canvas.save();
            if (Math.abs(zoom - 1f) > 0.0005f) {
                canvas.scale(zoom, zoom, width * 0.5f, height * 0.5f);
            }
            wallpaper.draw(canvas);
            canvas.restoreToCount(save);
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
