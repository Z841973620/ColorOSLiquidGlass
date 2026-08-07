package net.z841973620.colorosliquidglass.glass;

import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Samples a fixed on-screen menu region for Ashmem publish (Launcher process).
 * <p>
 * Layered soft cache (region-sized, ARGB_8888 — Ashmem cannot take HARDWARE bitmaps):
 * <ul>
 *   <li>Wallpaper layer — rebuild on size / wallpaper zoom</li>
 *   <li>Icons layer — {@link DesktopIconOverlay} + DragView/popup overlays; rebuild on
 *       scroll / invalidate</li>
 *   <li>Compose into a triple-buffer publish slot</li>
 * </ul>
 * Do <b>not</b> use {@code DragLayer.draw} on a software canvas — OEM HW layers paint empty
 * and previously caused “wallpaper-only” glass.
 */
public final class DesktopBackdropSampler {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final int PLATE_SLOTS = 3;

    private static volatile WeakReference<View> launcherRoot = new WeakReference<>(null);

    private static final Bitmap[] plates = new Bitmap[PLATE_SLOTS];
    private static Bitmap wallpaperLayer;
    private static Bitmap iconsLayer;
    private static int drawIdx;
    private static final Rect liveCrop = new Rect();

    private static boolean wallpaperDirty = true;
    private static boolean iconsDirty = true;
    private static float layerWallpaperScale = Float.NaN;
    private static int layerScrollX = Integer.MIN_VALUE;
    private static int layerScrollY = Integer.MIN_VALUE;
    private static int layerRecentsScrollX = Integer.MIN_VALUE;
    private static int layerRecentsScrollY = Integer.MIN_VALUE;
    private static int layerW;
    private static int layerH;

    private static int contentGeneration;
    private static int lastCaptureIdx = -1;

    private DesktopBackdropSampler() {}

    public static int contentGeneration() {
        return contentGeneration;
    }

    public static int lastCaptureIndex() {
        return lastCaptureIdx;
    }

    public static void setLauncherRoot(View root) {
        if (root == null) return;
        View decor = root.getRootView() != null ? root.getRootView() : root;
        launcherRoot = new WeakReference<>(decor);
        markAllDirty();
    }

    public static void setLiveCrop(Rect screenCrop) {
        if (screenCrop == null || screenCrop.isEmpty()) {
            liveCrop.setEmpty();
            return;
        }
        if (!liveCrop.equals(screenCrop)) {
            liveCrop.set(screenCrop);
            markAllDirty();
        }
    }

    public static Rect liveCrop() {
        return new Rect(liveCrop);
    }

    public static void invalidateCache() {
        // Dirty icons only. Clearing folder snaps here dropped LiquidGlass folder composites
        // from float ashmem whenever CellLayout/popup laid out; after OEM-only strips live
        // plate glass, ashmem could only rebuild empty chrome.
        iconsDirty = true;
    }

    /** Workspace scroll / wallpaper zoom — icons (and maybe wallpaper) must refresh. */
    public static void invalidateAll() {
        markAllDirty();
    }

    /**
     * Compose the LIVE menu region into a free plate slot.
     * Skips layer redraws when dirty flags / scroll / zoom are unchanged.
     */
    public static Bitmap captureLiveRegion(int publishBusyIndex) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("DesktopBackdropSampler requires main thread");
        }
        if (liveCrop.isEmpty()) return null;
        View root = resolveRoot();
        if (root == null || !root.isAttachedToWindow()) return null;

        int target = nextDrawIndex(publishBusyIndex);
        drawIdx = target;
        if (!composeRegion(root, target, liveCrop)) return null;
        lastCaptureIdx = target;
        return plates[target];
    }

    public static Bitmap captureLivePlate(int publishBusyIndex) {
        return captureLiveRegion(publishBusyIndex);
    }

    public static Bitmap sampleFullScreen() {
        return captureLiveRegion(-1);
    }

    public static Bitmap sampleScreenRect(Rect screenRect) {
        if (screenRect == null || screenRect.isEmpty()) return null;
        setLiveCrop(screenRect);
        return captureLiveRegion(-1);
    }

    public static void releaseLiveBuffers() {
        for (int i = 0; i < PLATE_SLOTS; i++) {
            recycle(plates[i]);
            plates[i] = null;
        }
        recycle(wallpaperLayer);
        recycle(iconsLayer);
        wallpaperLayer = null;
        iconsLayer = null;
        liveCrop.setEmpty();
        markAllDirty();
        lastCaptureIdx = -1;
        contentGeneration++;
    }

    private static void markAllDirty() {
        wallpaperDirty = true;
        iconsDirty = true;
        layerWallpaperScale = Float.NaN;
        layerScrollX = Integer.MIN_VALUE;
        layerScrollY = Integer.MIN_VALUE;
        layerRecentsScrollX = Integer.MIN_VALUE;
        layerRecentsScrollY = Integer.MIN_VALUE;
    }

    private static int nextDrawIndex(int publishBusyIndex) {
        for (int step = 1; step <= PLATE_SLOTS; step++) {
            int c = (drawIdx + step) % PLATE_SLOTS;
            if (c != publishBusyIndex) return c;
        }
        return (drawIdx + 1) % PLATE_SLOTS;
    }

    private static boolean composeRegion(View root, int slot, Rect crop) {
        int w = crop.width();
        int h = crop.height();
        if (w <= 0 || h <= 0) return false;

        View workspace = findWorkspace(root);
        View recents = findRecentsView(root);
        boolean overview = LauncherAshmemMode.isOverviewActive() || isRecentsPanelShowing(recents);
        float wallpaperScale = WallpaperScaleTracker.current();
        int scrollX = workspace != null ? workspace.getScrollX() : 0;
        int scrollY = workspace != null ? workspace.getScrollY() : 0;
        int recentsScrollX = recents != null ? recents.getScrollX() : 0;
        int recentsScrollY = recents != null ? recents.getScrollY() : 0;

        // Dragging/selection: DragView moves without Workspace scroll — force icons rebuild
        // every frame so the float-menu glass streams like page flips.
        // Open Folder / create-folder delegated plates similarly change without scroll.
        // Overview: Recents cards animate/scroll independently of Workspace.
        if (hasLiveDragView(root)
                || DesktopIconOverlay.hasVisibleOpenFolder(root)
                || DesktopIconOverlay.hasDelegatedFolderPreview(root)
                || DesktopIconOverlay.hasDesktopPopupGlass(root)
                || overview) {
            iconsDirty = true;
        }

        if (layerW != w || layerH != h) {
            markAllDirty();
            layerW = w;
            layerH = h;
        }
        if (Float.compare(layerWallpaperScale, wallpaperScale) != 0) {
            wallpaperDirty = true;
            layerWallpaperScale = wallpaperScale;
        }
        if (layerScrollX != scrollX || layerScrollY != scrollY) {
            iconsDirty = true;
            layerScrollX = scrollX;
            layerScrollY = scrollY;
        }
        if (layerRecentsScrollX != recentsScrollX || layerRecentsScrollY != recentsScrollY) {
            iconsDirty = true;
            layerRecentsScrollX = recentsScrollX;
            layerRecentsScrollY = recentsScrollY;
        }

        boolean rebuilt = false;
        if (wallpaperDirty) {
            wallpaperLayer = ensureLayer(wallpaperLayer, w, h);
            paintWallpaperLayer(root, wallpaperLayer, crop);
            wallpaperDirty = false;
            rebuilt = true;
        }
        if (iconsDirty) {
            iconsLayer = ensureLayer(iconsLayer, w, h);
            paintIconsLayer(root, workspace, iconsLayer, crop);
            iconsDirty = false;
            rebuilt = true;
        }

        Bitmap existing = plates[slot];
        if (!rebuilt && existing != null && !existing.isRecycled()
                && existing.getWidth() == w && existing.getHeight() == h) {
            lastCaptureIdx = slot;
            return true;
        }

        Bitmap target = ensureLayer(plates[slot], w, h);
        plates[slot] = target;
        Canvas canvas = new Canvas(target);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        if (wallpaperLayer != null && !wallpaperLayer.isRecycled()) {
            canvas.drawBitmap(wallpaperLayer, 0f, 0f, BITMAP_PAINT);
        }
        if (iconsLayer != null && !iconsLayer.isRecycled()) {
            canvas.drawBitmap(iconsLayer, 0f, 0f, BITMAP_PAINT);
        }
        contentGeneration++;
        return true;
    }

    private static void paintWallpaperLayer(View root, Bitmap layer, Rect crop) {
        Canvas canvas = new Canvas(layer);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        canvas.save();
        canvas.translate(-crop.left, -crop.top);
        try {
            Drawable wallpaper = WallpaperManager.getInstance(root.getContext()).getDrawable();
            if (wallpaper == null) {
                canvas.restore();
                return;
            }
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
            int[] rootLoc = new int[2];
            root.getLocationOnScreen(rootLoc);
            wallpaper.setBounds(rootLoc[0] + left, rootLoc[1] + top,
                    rootLoc[0] + left + drawWidth, rootLoc[1] + top + drawHeight);
            float zoom = WallpaperScaleTracker.current();
            int save = canvas.save();
            if (Math.abs(zoom - 1f) > 0.0005f) {
                float cx = rootLoc[0] + width * 0.5f;
                float cy = rootLoc[1] + height * 0.5f;
                canvas.scale(zoom, zoom, cx, cy);
            }
            wallpaper.draw(canvas);
            canvas.restoreToCount(save);
            wallpaper.setBounds(old);
        } catch (Throwable ignored) {
        } finally {
            canvas.restore();
        }
    }

    /**
     * Home: {@link DesktopIconOverlay}. Overview: Recents cards via {@link TaskContentOverlay}.
     * Never paint desktop icons while Overview is active — that was the stale-desktop bug.
     */
    private static void paintIconsLayer(View root, View workspace, Bitmap layer, Rect crop) {
        Canvas canvas = new Canvas(layer);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        try {
            View recents = findRecentsView(root);
            boolean overview = LauncherAshmemMode.isOverviewActive() || isRecentsPanelShowing(recents);
            if (overview) {
                TaskContentOverlay.paintRecentsIntoScreenRect(crop, canvas, root);
            } else {
                DesktopIconOverlay.paintIntoScreenRect(crop, canvas,
                        workspace != null ? workspace : root);
            }
        } catch (Throwable t) {
            Log.w(TAG, "region content paint failed", t);
        }
        try {
            paintPopupOverlays(root, canvas, crop);
        } catch (Throwable t) {
            Log.w(TAG, "popup overlay paint failed", t);
        }
        try {
            // AGSL cannot soft-draw; HW plate + soft children (same as folder under float).
            DesktopIconOverlay.paintDesktopPopupGlassIntoScreenRect(crop, canvas, root);
        } catch (Throwable t) {
            Log.w(TAG, "desktop popup glass paint failed", t);
        }
    }

    /** Popup menus only — DragViews are handled by DesktopIconOverlay FastBitmap path. */
    private static void paintPopupOverlays(View root, Canvas canvas, Rect crop) {
        View decor = root.getRootView() != null ? root.getRootView() : root;
        java.util.ArrayList<View> suppressed = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        suppressBlurViews(decor, suppressed, alphas);
        try {
            paintPopupOverlaysRecursive(decor, canvas, crop);
        } finally {
            restoreSuppressedViews(suppressed, alphas);
        }
    }

    private static void paintPopupOverlaysRecursive(View view, Canvas canvas, Rect crop) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (view.getAlpha() < 0.01f) {
            if (view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                for (int i = 0; i < g.getChildCount(); i++) {
                    paintPopupOverlaysRecursive(g.getChildAt(i), canvas, crop);
                }
            }
            return;
        }
        String name = view.getClass().getSimpleName();
        if (name != null && (name.contains("Blur") || name.contains("blur")
                || name.endsWith("DragView"))) {
            if (view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                for (int i = 0; i < g.getChildCount(); i++) {
                    paintPopupOverlaysRecursive(g.getChildAt(i), canvas, crop);
                }
            }
            return;
        }
        boolean overlay = name != null && (name.contains("Popup")
                || name.contains("Arrow")
                || name.contains("DeepShortcut")
                || name.contains("SystemShortcut")
                || name.contains("ResizeFrame")
                || name.contains("ItemResize"));
        if (overlay && view.getWidth() > 0 && view.getHeight() > 0) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            Rect bounds = new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
            boolean huge = bounds.width() >= crop.width() * 2
                    || bounds.height() >= crop.height() * 2;
            // Skip soft draw for LiquidGlass menu plates / their ancestors — SoftGlass
            // fallback would replace AGSL. HW path paints those hosts afterward.
            if (!huge && Rect.intersects(bounds, crop)
                    && !DesktopIconOverlay.isOrContainsDesktopPopupGlass(view)) {
                int save = canvas.save();
                try {
                    canvas.translate(loc[0] - crop.left, loc[1] - crop.top);
                    view.draw(canvas);
                } catch (Throwable ignored) {
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                paintPopupOverlaysRecursive(g.getChildAt(i), canvas, crop);
            }
        }
    }

    /** Hide OEM selection/popup blur views so view.draw of parents cannot fog the sample. */
    private static void suppressBlurViews(View view, java.util.ArrayList<View> out,
            java.util.ArrayList<Float> alphas) {
        if (view == null) return;
        String name = view.getClass().getSimpleName();
        if (name != null && (name.contains("Blur") || name.contains("blur"))) {
            if (view.getVisibility() == View.VISIBLE && view.getAlpha() > 0.01f) {
                out.add(view);
                alphas.add(view.getAlpha());
                view.setAlpha(0f);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                suppressBlurViews(g.getChildAt(i), out, alphas);
            }
        }
    }

    private static void restoreSuppressedViews(java.util.ArrayList<View> suppressed,
            java.util.ArrayList<Float> alphas) {
        for (int i = 0; i < suppressed.size(); i++) {
            try {
                suppressed.get(i).setAlpha(alphas.get(i));
            } catch (Throwable ignored) { }
        }
    }

    private static Bitmap ensureLayer(Bitmap existing, int w, int h) {
        if (existing != null && !existing.isRecycled()
                && existing.getWidth() == w && existing.getHeight() == h) {
            return existing;
        }
        recycle(existing);
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try { b.recycle(); } catch (Throwable ignored) { }
        }
    }

    private static View resolveRoot() {
        View cached = launcherRoot.get();
        if (cached != null && cached.isAttachedToWindow()) return cached;
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            if (launcher instanceof android.app.Activity) {
                View decor = ((android.app.Activity) launcher).getWindow().getDecorView();
                setLauncherRoot(decor);
                return decor;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static View findWorkspace(View root) {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) return (View) ws;
        } catch (Throwable ignored) { }
        return findByName(root, "Workspace", true);
    }

    private static View findRecentsView(View root) {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            Object panel = invokeNoArgs(launcher, "getOverviewPanel");
            if (panel instanceof View) return (View) panel;
            Object recents = invokeNoArgs(launcher, "getRecentsView");
            if (recents instanceof View) return (View) recents;
        } catch (Throwable ignored) { }
        View found = findByName(root, "RecentsView", false);
        if (found != null) return found;
        return findByName(root, "OverviewPanel", false);
    }

    private static boolean isRecentsPanelShowing(View recents) {
        if (recents == null) return false;
        if (recents.getVisibility() != View.VISIBLE) return false;
        if (recents.getAlpha() < 0.05f) return false;
        return recents.getWidth() > 0 && recents.getHeight() > 0;
    }

    private static View findByName(View root, String token, boolean excludeCell) {
        if (root == null) return null;
        String name = root.getClass().getName();
        if (name != null && name.contains(token)
                && (!excludeCell || !name.contains("Cell"))) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findByName(g.getChildAt(i), token, excludeCell);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when a live *DragView is on the decor (selected / moving icon or folder). */
    private static boolean hasLiveDragView(View root) {
        View decor = root != null && root.getRootView() != null ? root.getRootView() : root;
        return findDragViewInTree(decor) != null;
    }

    private static View findDragViewInTree(View root) {
        if (root == null) return null;
        String name = root.getClass().getSimpleName();
        if (name != null && name.endsWith("DragView")
                && root.getVisibility() != View.GONE
                && root.getWidth() > 0 && root.getHeight() > 0) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findDragViewInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
