package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Composites a Recents {@code TaskView} thumbnail into the glass sample.
 * <p>
 * Non-protect: soft thumbnail + {@code PreviewPositionHelper} matrix (stable, no jitter).
 * <p>
 * Protect: the OEM view already draws only the protect plate. We take that on-screen content
 * <b>once</b> via {@link View#draw(Canvas)} into a soft bitmap that <b>replaces</b> the
 * thumbnail content cache, then paint it through the same {@code drawBitmap} path. No live
 * re-draw, no second aligned plate, no bake deferred until scale≈1 (that caused the jump at
 * menu-expand max).
 */
public final class TaskContentOverlay {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Map<Bitmap, Bitmap> SOFTWARE_COPIES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final class ProtectContent {
        final int width;
        final int height;
        final Bitmap bitmap;
        /** Once locked, never rebuild for this snapshot until {@link #clearProtectCache}. */
        final boolean locked;
        ProtectContent(int width, int height, Bitmap bitmap, boolean locked) {
            this.width = width;
            this.height = height;
            this.bitmap = bitmap;
            this.locked = locked;
        }
    }

    private static final Map<View, WeakReference<ProtectContent>> PROTECT_CONTENTS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private TaskContentOverlay() {}

    /**
     * Float-menu ashmem path: paint Overview/Recents content that intersects
     * {@code screenRect} into the Launcher-published sample (same pipe as desktop icons).
     * Prefers per-{@code TaskView} thumbnails; falls back to HW-rasterizing the Recents panel.
     */
    public static void paintRecentsIntoScreenRect(Rect screenRect, Canvas canvas, View seed) {
        if (screenRect == null || screenRect.isEmpty() || canvas == null) return;
        Matrix globalToTarget = new Matrix();
        globalToTarget.setTranslate(-screenRect.left, -screenRect.top);
        RectF region = new RectF(screenRect);
        View root = seed != null && seed.getRootView() != null ? seed.getRootView() : seed;
        View recents = findRecentsView(seed);
        if (recents == null) {
            recents = findRecentsInTree(root);
        }
        if (recents != null) {
            int painted = paintTaskViewsInto(recents, canvas, globalToTarget, region);
            if (painted <= 0) {
                // ColorOS may nest thumbnails outside *TaskView* naming — rasterize the panel.
                paintPanelIntoRegion(recents, canvas, globalToTarget, region);
            }
        }
        // "清除" lives in OplusClearAllPanelView — sibling under Overview, not inside RecentsView.
        View clearPanel = findClearAllPanel(root, recents);
        if (clearPanel != null
                && clearPanel.getVisibility() == View.VISIBLE
                && clearPanel.getAlpha() > 0.01f) {
            paintClearAllIntoRegion(clearPanel, canvas, globalToTarget, region);
        }
    }

    private static int paintTaskViewsInto(View root, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        int count = 0;
        if (root == null) return 0;
        if (isTaskView(root)
                && root.getVisibility() == View.VISIBLE
                && root.getAlpha() > 0.01f
                && root.getWidth() > 0 && root.getHeight() > 0
                && intersectsRegion(region, root)) {
            if (paintOneTaskView(root, canvas, globalToTarget)) count++;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            // RecentsView draws children back-to-front (last index under, first/current on top).
            // Forward iteration put the last task on top — reverse to match Overview stacking.
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                count += paintTaskViewsInto(g.getChildAt(i), canvas, globalToTarget, region);
            }
        }
        return count;
    }

    private static boolean paintOneTaskView(View taskView, Canvas canvas, Matrix globalToTarget) {
        View snapshot = resolveSnapshot(taskView);
        if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) return false;
        if (snapshot.getVisibility() != View.VISIBLE) return false;

        Matrix snapToGlobal = new Matrix();
        snapshot.transformMatrixToGlobal(snapToGlobal);
        Matrix snapToTarget = new Matrix();
        snapToTarget.setConcat(globalToTarget, snapToGlobal);

        int save = canvas.save();
        try {
            canvas.concat(snapToTarget);
            return paintSnapshotContent(snapshot, canvas);
        } catch (Throwable t) {
            Log.w(TAG, "paintOneTaskView failed", t);
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** HW/software rasterize an Overview panel and blit into the float-menu crop. */
    private static void paintPanelIntoRegion(View panel, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        paintPanelIntoRegion(panel, canvas, globalToTarget, region, false);
    }

    /**
     * @param sparseOk when true, skip {@link #isMostlyEmpty} (clear button is mostly
     *                 transparent chrome + LiquidGlass by design).
     */
    private static void paintPanelIntoRegion(View panel, Canvas canvas, Matrix globalToTarget,
            RectF region, boolean sparseOk) {
        if (panel == null || !intersectsRegion(region, panel)) return;
        int w = panel.getWidth();
        int h = panel.getHeight();
        if (w <= 0 || h <= 0) return;

        Bitmap rendered = GlassHwRasterizer.render(w, h, panel::draw);
        if (rendered == null || rendered.isRecycled()
                || (!sparseOk && isMostlyEmpty(rendered))) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(rendered);
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                panel.draw(c);
            } catch (Throwable t) {
                rendered.recycle();
                Log.w(TAG, "paintPanelIntoRegion software draw failed", t);
                return;
            }
            if (!sparseOk && isMostlyEmpty(rendered)) {
                rendered.recycle();
                return;
            }
        }
        try {
            Matrix panelToGlobal = new Matrix();
            panel.transformMatrixToGlobal(panelToGlobal);
            Matrix panelToTarget = new Matrix();
            panelToTarget.setConcat(globalToTarget, panelToGlobal);
            int save = canvas.save();
            try {
                canvas.concat(panelToTarget);
                canvas.drawBitmap(rendered, 0f, 0f, BITMAP_PAINT);
            } finally {
                canvas.restoreToCount(save);
            }
        } finally {
            if (!rendered.isRecycled()) rendered.recycle();
        }
    }

    /** Paint Overview "清除" panel (PressFeedbackButton + LiquidGlass) into the sample. */
    private static void paintClearAllIntoRegion(View panel, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        if (panel == null) return;
        // Prefer the button (owns LiquidGlass); fall back to the whole panel.
        View button = findClearAllButton(panel);
        View target = button != null
                && button.getVisibility() == View.VISIBLE
                && button.getWidth() > 0 && button.getHeight() > 0
                && intersectsRegion(region, button)
                ? button : panel;
        paintPanelIntoRegion(target, canvas, globalToTarget, region, true);
    }

    private static View findClearAllButton(View panel) {
        Object btn = field(panel, "mClearAllBtn");
        if (btn instanceof View) return (View) btn;
        btn = invokeNoArgs(panel, "getClearAllButton");
        if (btn instanceof View) return (View) btn;
        return findPressFeedbackButton(panel);
    }

    private static View findPressFeedbackButton(View root) {
        if (root == null) return null;
        String name = root.getClass().getName();
        if (name != null && name.contains("PressFeedbackButton")) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findPressFeedbackButton(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isMostlyEmpty(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return true;
        Bitmap soft = bitmap.getConfig() == Bitmap.Config.HARDWARE
                ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
        if (soft == null || soft.isRecycled()) return true;
        int stepX = Math.max(1, w / 6);
        int stepY = Math.max(1, h / 6);
        int opaque = 0;
        try {
            for (int y = stepY / 2; y < h; y += stepY) {
                for (int x = stepX / 2; x < w; x += stepX) {
                    if (((soft.getPixel(x, y) >>> 24) & 0xff) > 16) opaque++;
                }
            }
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (soft != bitmap && !soft.isRecycled()) {
                try { soft.recycle(); } catch (Throwable ignored) { }
            }
        }
        return opaque < 2;
    }

    private static boolean intersectsRegion(RectF region, View view) {
        if (region == null || view == null) return false;
        Matrix m = new Matrix();
        view.transformMatrixToGlobal(m);
        RectF bounds = new RectF(0f, 0f, view.getWidth(), view.getHeight());
        m.mapRect(bounds);
        return RectF.intersects(region, bounds);
    }

    private static View findRecentsView(View seed) {
        Object launcher = resolveLauncher(seed);
        Object panel = invokeNoArgs(launcher, "getOverviewPanel");
        if (panel instanceof View) return (View) panel;
        Object recents = invokeNoArgs(launcher, "getRecentsView");
        if (recents instanceof View) return (View) recents;
        return null;
    }

    private static View findRecentsInTree(View root) {
        if (root == null) return null;
        String name = root.getClass().getSimpleName();
        if (name != null && (name.contains("RecentsView") || name.contains("OverviewPanel"))) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findRecentsInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * ColorOS Overview "清除" host: {@code OplusClearAllPanelView} (PressFeedbackButton inside).
     * Prefer a DragLayer / Overview sibling of RecentsView; fall back to decor scan.
     */
    private static View findClearAllPanel(View root, View recents) {
        ViewParent parent = recents != null ? recents.getParent() : null;
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (isClearAllPanel(child)) return child;
                View nested = findClearAllPanelInTree(child);
                if (nested != null) return nested;
            }
        }
        Object launcher = resolveLauncher(root instanceof View ? (View) root : null);
        for (String name : new String[] {
                "mClearAllPanel", "mClearAllButton", "getClearAllPanel", "getClearAllButton"
        }) {
            Object v = name.startsWith("get") ? invokeNoArgs(launcher, name) : field(launcher, name);
            if (v instanceof View && isClearAllPanel((View) v)) return (View) v;
            if (v instanceof View) {
                View nested = findClearAllPanelInTree((View) v);
                if (nested != null) return nested;
            }
        }
        return findClearAllPanelInTree(root);
    }

    private static View findClearAllPanelInTree(View root) {
        if (root == null) return null;
        if (isClearAllPanel(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findClearAllPanelInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isClearAllPanel(View view) {
        if (view == null) return false;
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("ClearAllPanel") || name.equals("OplusClearAllPanelView")) {
                return true;
            }
        }
        return false;
    }

    private static Object resolveLauncher(View seed) {
        if (seed == null) return null;
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object created = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            if (created != null) return created;
        } catch (Throwable ignored) { }
        for (View current = seed; current != null; ) {
            Object got = invokeNoArgs(current, "getLauncher");
            if (got == null) got = field(current, "mLauncher");
            if (got == null) got = field(current, "mActivity");
            if (got != null) return got;
            String name = current.getClass().getSimpleName();
            if (name != null && name.contains("Launcher") && !name.contains("AppWidget")) {
                return current;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    /**
     * Eagerly bake the protect content-layer cache. Also used after menu scale settles (1.0)
     * so the locked bake matches the final on-screen mask.
     */
    public static void prebakeProtect(View taskViewOrSnapshot) {
        if (taskViewOrSnapshot == null) return;
        try {
            View snapshot = isTaskView(taskViewOrSnapshot)
                    ? resolveSnapshot(taskViewOrSnapshot) : taskViewOrSnapshot;
            if (snapshot == null || !isContentProtected(snapshot)) return;
            protectContentCache(snapshot);
        } catch (Throwable t) {
            Log.w(TAG, "prebakeProtect failed", t);
        }
    }

    static boolean isTaskView(View view) {
        if (view == null) return false;
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.endsWith(".TaskView") || name.endsWith("TaskView")) return true;
        }
        return false;
    }

    /** Drop protect content caches (call when the task menu closes / overlay is cleared). */
    public static void clearProtectCache(View seedOrSnapshot) {
        if (seedOrSnapshot == null) return;
        View snapshot = isTaskView(seedOrSnapshot) ? resolveSnapshot(seedOrSnapshot) : seedOrSnapshot;
        recycleProtect(snapshot);
        if (snapshot != seedOrSnapshot) recycleProtect(seedOrSnapshot);
    }

    static void clearAllProtectCaches() {
        synchronized (PROTECT_CONTENTS) {
            for (WeakReference<ProtectContent> ref : PROTECT_CONTENTS.values()) {
                ProtectContent c = ref == null ? null : ref.get();
                if (c != null && c.bitmap != null && !c.bitmap.isRecycled()) {
                    try { c.bitmap.recycle(); } catch (Throwable ignored) { }
                }
            }
            PROTECT_CONTENTS.clear();
        }
    }

    private static void recycleProtect(View key) {
        if (key == null) return;
        synchronized (PROTECT_CONTENTS) {
            WeakReference<ProtectContent> ref = PROTECT_CONTENTS.remove(key);
            ProtectContent c = ref == null ? null : ref.get();
            if (c != null && c.bitmap != null && !c.bitmap.isRecycled()) {
                try { c.bitmap.recycle(); } catch (Throwable ignored) { }
            }
        }
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
        float width = snapshot.getWidth();
        float height = snapshot.getHeight();

        Bitmap soft;
        Matrix preview;
        if (isContentProtected(snapshot)) {
            // Content-layer replacement: locked soft cache of the view's own protect drawing.
            soft = protectContentCache(snapshot);
            preview = null;
        } else {
            soft = softwareThumbnail(snapshot);
            preview = previewMatrix(snapshot);
        }
        if (soft == null || soft.isRecycled()) return false;

        if (preview != null) {
            BitmapShader shader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(preview);
            Paint paint = new Paint(BITMAP_PAINT);
            paint.setShader(shader);
            canvas.drawRect(0f, 0f, width, height, paint);
        } else {
            // Same destination as a fully view-mapped thumbnail.
            canvas.drawBitmap(soft, null, new RectF(0f, 0f, width, height), BITMAP_PAINT);
        }
        return true;
    }

    /**
     * One-shot soft cache of the snapshot view as drawn on screen (protect plate). Locked for
     * the menu session so delayed forceCapture / size flicker cannot replace it mid-animation
     * (that was the upward jump + shrink at expand max).
     */
    private static Bitmap protectContentCache(View snapshot) {
        final int w = snapshot.getWidth();
        final int h = snapshot.getHeight();
        if (w <= 0 || h <= 0) return null;

        synchronized (PROTECT_CONTENTS) {
            WeakReference<ProtectContent> ref = PROTECT_CONTENTS.get(snapshot);
            ProtectContent cached = ref == null ? null : ref.get();
            if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()) {
                if (cached.locked) return cached.bitmap;
                if (cached.width == w && cached.height == h) return cached.bitmap;
            }
        }

        Object icon = invokeNoArgs(snapshot, "getMContentProtectIcon");
        if (!(icon instanceof Bitmap) || ((Bitmap) icon).isRecycled()) {
            icon = field(snapshot, "mContentProtectIcon");
            if (!(icon instanceof Bitmap) || ((Bitmap) icon).isRecycled()) return null;
        }

        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        boolean ok = false;
        try {
            // Exact on-screen pixels (includes onDraw scale/insets + drawContentProtect).
            // This is the content-layer replacement — same visual as under the menu.
            snapshot.draw(c);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "protect View.draw bake failed, fallback", t);
            try {
                float corner = cornerRadius(snapshot);
                invokeDrawContentProtect(snapshot, c, 0f, 0f, w, h, corner);
                ok = true;
            } catch (Throwable t2) {
                Log.w(TAG, "protect fallback bake failed", t2);
            }
        }
        if (!ok) {
            layer.recycle();
            return null;
        }

        ProtectContent next = new ProtectContent(w, h, layer, true);
        synchronized (PROTECT_CONTENTS) {
            WeakReference<ProtectContent> ref = PROTECT_CONTENTS.get(snapshot);
            ProtectContent old = ref == null ? null : ref.get();
            if (old != null && old.locked && old.bitmap != null && !old.bitmap.isRecycled()) {
                // Session already locked — keep the first bake, drop this one.
                layer.recycle();
                return old.bitmap;
            }
            if (old != null && old.bitmap != null && old.bitmap != layer && !old.bitmap.isRecycled()) {
                old.bitmap.recycle();
            }
            PROTECT_CONTENTS.put(snapshot, new WeakReference<>(next));
        }
        return layer;
    }

    private static float cornerRadius(View snapshot) {
        Object current = field(snapshot, "mCurrentCornerRadius");
        if (current instanceof Number) {
            float v = ((Number) current).floatValue();
            if (v > 0f) return v;
        }
        Object via = invokeNoArgs(snapshot, "calculateCornerRadius");
        if (via instanceof Boolean) return 0f;
        if (via instanceof Number) return ((Number) via).floatValue();
        return 0f;
    }

    private static void invokeDrawContentProtect(View snapshot, Canvas canvas,
            float x, float y, float width, float height, float cornerRadius) {
        for (Class<?> c = snapshot.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("drawContentProtect",
                        Canvas.class, float.class, float.class, float.class, float.class, float.class);
                m.setAccessible(true);
                m.invoke(snapshot, canvas, x, y, width, height, cornerRadius);
                return;
            } catch (Throwable ignored) { }
        }
    }

    private static boolean isContentProtected(View snapshot) {
        Object flag = field(snapshot, "isContentProtection");
        if (flag instanceof Boolean) return (Boolean) flag;
        Object via = invokeNoArgs(snapshot, "getIsContentProtection");
        if (via instanceof Boolean) return (Boolean) via;
        via = invokeNoArgs(snapshot, "isContentProtection");
        if (via instanceof Boolean) return (Boolean) via;
        Object task = invokeNoArgs(snapshot, "getTask");
        if (task == null) {
            Object taskView = invokeNoArgs(snapshot, "getTaskView");
            task = invokeNoArgs(taskView, "getTask");
        }
        Object protect = field(task, "isContentProtect");
        return protect instanceof Boolean && (Boolean) protect;
    }

    private static Bitmap softwareThumbnail(View snapshot) {
        Bitmap raw = thumbnailBitmap(snapshot);
        if (raw == null || raw.isRecycled()) return null;
        if (raw.getConfig() != Bitmap.Config.HARDWARE) return raw;

        synchronized (SOFTWARE_COPIES) {
            Bitmap cached = SOFTWARE_COPIES.get(raw);
            if (cached != null && !cached.isRecycled()) return cached;
        }

        final Bitmap hw = raw;
        final int bw = Math.max(1, hw.getWidth());
        final int bh = Math.max(1, hw.getHeight());
        Bitmap soft = GlassHwRasterizer.render(bw, bh, c ->
                c.drawBitmap(hw, null, new RectF(0f, 0f, bw, bh), BITMAP_PAINT));
        if (soft == null || soft.isRecycled()) {
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
