package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Desktop content under a dragged-folder glass, pasted into the backdrop sample so the
 * upper LiquidGlass AGSL can refract it.
 * <p>
 * Apps use software FastBitmap (+ label). Folders keep a real LiquidGlass plate by
 * HW-rasterizing {@link GlassDrawable} via {@link GlassHwRasterizer}, then copying that
 * bitmap into the software sample — a flat HW overlay on top of the drag glass would
 * block secondary refraction.
 */
final class DesktopIconOverlay {
    private static final Paint BITMAP_PAINT =
            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint TITLE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final ThreadLocal<Integer> DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final Map<View, FolderSnap> FOLDER_SNAPS = new WeakHashMap<>();
    private static final Map<View, WidgetSnap> WIDGET_SNAPS = new WeakHashMap<>();

    private static final class FolderSnap {
        Bitmap bitmap;
        Bitmap plateSource;
        int width;
        int height;
        boolean includeName;
    }

    private static final class WidgetSnap {
        Bitmap bitmap;
        int width;
        int height;
    }

    private DesktopIconOverlay() {}

    /**
     * Software backdrop path for an <em>active</em> dragged-folder glass only.
     * After finger-up the FolderIcon may keep a leftover overlay seed while no longer
     * under a DragView; painting then would secondary-refract the desktop into the
     * folder's own plate. Require a live *DragView ancestor (OEM: OplusDragView etc.).
     * <p>
     * Page-indicator dots live in the horizontal strip between Workspace and Hotseat.
     * A folder sitting entirely on that strip intersects neither icon container — still
     * paint the dots (do not early-return when containers are empty).
     */
    static void paintIntoTargetLocal(View glassHost, Canvas canvas) {
        if (glassHost == null || canvas == null) return;
        if (findDragView(glassHost) == null) return;
        View seed = BackdropCapture.overlaySourceOf(glassHost);
        Matrix targetToGlobal = new Matrix();
        glassHost.transformMatrixToGlobal(targetToGlobal);
        Matrix globalToTarget = new Matrix();
        if (!targetToGlobal.invert(globalToTarget)) return;
        RectF region = screenRect(glassHost);
        if (region == null) return;
        paintDesktopInto(seed != null ? seed : glassHost, canvas, globalToTarget, region, glassHost,
                null, null);
    }

    /**
     * Virtual folder-glass composite for an arbitrary on-screen rect (SystemUI float menu).
     * Same wallpaper companion path as drag glass: FastBitmap icons, folder plates, widgets,
     * page dots — no DragView / no real FolderIcon host required.
     */
    static void paintIntoScreenRect(Rect screenRect, Canvas canvas, View seed) {
        if (screenRect == null || screenRect.isEmpty() || canvas == null) return;
        Matrix globalToTarget = new Matrix();
        globalToTarget.setTranslate(-screenRect.left, -screenRect.top);
        RectF region = new RectF(screenRect);
        View anchor = seed != null ? seed : resolveWorkspaceSeed();
        if (anchor == null) return;
        View root = anchor.getRootView() != null ? anchor.getRootView() : anchor;
        // While a DragView is live, never paint the origin cell (even if the DragView is
        // outside the float-menu crop). Otherwise cancel move-out invents the icon at the
        // home cell while the real glyph is elsewhere. paintDragViewsInto only draws the
        // DragView when it intersects the sample; drag end clears the skip.
        java.util.HashSet<View> skipIcons = new java.util.HashSet<>();
        collectDragSources(root, skipIcons);
        collectDragControllerSources(anchor, skipIcons);
        collectCreateFolderHosts(anchor, skipIcons);
        View openFolder = findOpenFolderView(anchor);
        collectOpenFolderDesktopSkips(anchor, openFolder, skipIcons);
        // While dragging into an open Folder, hide workspace icons under the folder content
        // (avoids overlap with the Folder page). Cleared as soon as the folder closes or the
        // drag ends — cancel move-out restores those icons.
        RectF folderDropCover = folderDropIconCover(openFolder, root);
        paintDelegatedFolderPreviews(anchor, canvas, globalToTarget, region);
        paintDesktopInto(anchor, canvas, globalToTarget, region, null, skipIcons, folderDropCover);
        paintOpenFolders(anchor, canvas, globalToTarget, region);
        paintDragViewsInto(root, canvas, globalToTarget, region);
    }

    /**
     * ColorOS draws create-folder and folder-accept plates via
     * {@code CellLayout → OplusPreviewBackground.drawBackground}, not as FolderIcon children.
     * Ashmem sampling must paint those plates explicitly.
     */
    private static void paintDelegatedFolderPreviews(View seed, Canvas canvas,
            Matrix globalToTarget, RectF region) {
        View workspace = findWorkspaceAncestor(seed);
        if (workspace != null) {
            paintOneDelegatedPreview(fieldValue(workspace, "mGroupCreatePreviewBg"),
                    canvas, globalToTarget, region);
        }
        List<ViewGroup> containers = iconContainersUnderRegion(seed, region);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = resolveDesktopItem(child);
                View folder = isFolderIcon(host) ? host : (isFolderIcon(child) ? child : null);
                if (folder == null) continue;
                paintOneDelegatedPreview(fieldValue(folder, "mBackground"),
                        canvas, globalToTarget, region);
            }
        }
    }

    private static void paintOneDelegatedPreview(Object preview, Canvas canvas,
            Matrix globalToTarget, RectF region) {
        if (preview == null || canvas == null) return;
        if (!isPreviewDrawingDelegated(preview)) return;
        Object delegate = fieldValue(preview, "mDrawingDelegate");
        if (!(delegate instanceof View)) return;
        View cellLayout = (View) delegate;
        Object rectObj = invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof Rect)) return;
        Rect bg = (Rect) rectObj;
        if (bg.width() <= 0 || bg.height() <= 0) return;

        int cellX = intField(preview, "mDelegateCellX");
        int cellY = intField(preview, "mDelegateCellY");
        int[] cellPoint = new int[2];
        if (!cellToPoint(cellLayout, cellX, cellY, cellPoint)) return;

        Object bgViewObj = fieldValue(preview, "mBgView");
        View bgView = bgViewObj instanceof View ? (View) bgViewObj : null;
        GlassDrawable glass = bgView != null ? GlassInstaller.get(bgView) : null;
        if (glass == null) return;

        // Create-folder: park mBgView on CellLayout at the plate so forceCapture samples the
        // same screen pixels CellLayout paints (detached/wrong park → offset + bright).
        boolean createFolder = !isPreviewBoundToFolderIcon(preview);
        if (createFolder) {
            parkCreateFolderCaptureHost(preview, cellLayout, bg, cellPoint);
            bgViewObj = fieldValue(preview, "mBgView");
            bgView = bgViewObj instanceof View ? (View) bgViewObj : null;
            glass = bgView != null ? GlassInstaller.get(bgView) : null;
            if (glass == null) return;
        }

        // Dest must match the capture host when parked; otherwise cell math vs stale mBgView
        // location skews the plate (typically downward) inside float-menu glass.
        RectF dest = plateDestOnScreen(bgView, cellLayout, cellPoint, bg);
        if (dest == null || !RectF.intersects(region, dest)) return;
        globalToTarget.mapRect(dest);

        final int w = bg.width();
        final int h = bg.height();
        final View captureHost = bgView;
        final GlassDrawable plate = glass;
        if (captureHost != null && captureHost.getParent() != null
                && captureHost.getWidth() > 0 && captureHost.getHeight() > 0) {
            try {
                GlassInstaller.forceCapturePreviewPlate(captureHost);
            } catch (Throwable ignored) { }
        }
        if (captureHost == null || !GlassInstaller.hasBackdropFrame(captureHost)) {
            // Without a backdrop frame GlassDrawable paints bright translucent AGSL fallback.
            return;
        }
        Bitmap rendered = GlassHwRasterizer.render(w, h, hwCanvas -> {
            try {
                plate.setBounds(0, 0, w, h);
                float radius = 0f;
                Object rv = fieldValue(preview, "mRadius");
                if (rv instanceof Number) radius = ((Number) rv).floatValue();
                Object scale = fieldValue(preview, "mScale");
                if (scale instanceof Number) radius *= ((Number) scale).floatValue();
                if (radius > 0f) plate.setCornerRadii(radius, radius, radius, radius);
                plate.draw(hwCanvas);
            } catch (Throwable ignored) { }
        });
        if (rendered == null || rendered.isRecycled()) return;
        canvas.drawBitmap(rendered, null, dest, BITMAP_PAINT);
        if (!rendered.isRecycled()) rendered.recycle();

        // Plate glass alone is empty of preview glyphs — paint shrunk icons into the plate.
        if (createFolder) {
            paintCreateFolderPreviewIcons(preview, cellLayout, dest, canvas);
        } else {
            View folderIcon = resolveFolderIconFromPreview(preview);
            if (folderIcon != null) {
                paintFolderPreviewIconsIntoPlate(folderIcon, dest, canvas);
            }
        }
    }

    /** BubbleTextViews currently hosting the shared create-folder PreviewBackground. */
    private static void collectCreateFolderHosts(View seed, java.util.HashSet<View> out) {
        if (seed == null || out == null) return;
        View workspace = findWorkspaceAncestor(seed);
        Object createBg = workspace != null
                ? fieldValue(workspace, "mGroupCreatePreviewBg") : null;
        if (createBg == null || !isPreviewDrawingDelegated(createBg)) return;

        // Only leaf icon views — never CellLayout / Workspace (that wiped the whole page via
        // isListedDragSource descendant matching).
        addSkipIcon(out, fieldValue(createBg, "mInvalidateDelegate"));

        Object delegate = fieldValue(createBg, "mDrawingDelegate");
        if (delegate instanceof View) {
            addSkipIcon(out, findIconAtDelegateCell((View) delegate, createBg));
        }

        List<ViewGroup> containers = new ArrayList<>();
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup icons = shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (icons != null) containers.add(icons);
            }
        }
        ViewGroup primary = shortcutsAndWidgetsOf(workspace);
        if (primary != null && !containers.contains(primary)) containers.add(primary);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = resolveDesktopItem(child);
                View target = host != null ? host : child;
                if (isFolderIcon(target) || isBroadContainer(target)) continue;
                if (viewHoldsPreviewBackground(target, createBg)
                        || viewHoldsPreviewBackground(child, createBg)) {
                    addSkipIcon(out, target);
                    addSkipIcon(out, child);
                }
            }
        }
    }

    /** Add only desktop icon leaves to the skip set (never page/layout containers). */
    private static void addSkipIcon(java.util.HashSet<View> out, Object candidate) {
        if (out == null || !(candidate instanceof View)) return;
        View view = (View) candidate;
        if (isBroadContainer(view)) return;
        if (!(isDesktopIconLeaf(view) || isFolderIcon(view))) {
            View resolved = resolveDesktopItem(view);
            if (resolved != null && resolved != view && !isBroadContainer(resolved)
                    && (isDesktopIconLeaf(resolved) || isFolderIcon(resolved))) {
                out.add(resolved);
            }
            return;
        }
        out.add(view);
        View resolved = resolveDesktopItem(view);
        if (resolved != null && !isBroadContainer(resolved)) out.add(resolved);
    }

    private static boolean isBroadContainer(View view) {
        if (view == null) return false;
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("CellLayout") || name.contains("Workspace")
                    || name.contains("DragLayer") || name.contains("ShortcutAndWidget")
                    || name.equals("Folder") || name.endsWith("FolderPagedView")
                    || name.contains("Hotseat") && !name.contains("Icon")) {
                return true;
            }
        }
        return view instanceof ViewGroup && iconDrawableOf(view) == null && !isFolderIcon(view)
                && !(view instanceof TextView);
    }

    private static boolean isDesktopIconLeaf(View view) {
        if (view == null || isBroadContainer(view)) return false;
        if (isFolderIcon(view)) return true;
        if (iconDrawableOf(view) != null) return true;
        String name = view.getClass().getSimpleName();
        return name != null && (name.contains("BubbleText") || name.contains("ShortcutContainer")
                || name.contains("AppIcon"));
    }

    private static boolean viewHoldsPreviewBackground(View view, Object preview) {
        if (view == null || preview == null) return false;
        for (String name : new String[] {
                "mFolderPreviewBackGround", "previewBackground", "mFolderBackground",
                "mPreviewBackground", "folderBackground"
        }) {
            if (fieldValue(view, name) == preview) return true;
        }
        return false;
    }

    private static View findIconAtDelegateCell(View cellLayout, Object preview) {
        if (cellLayout == null || preview == null) return null;
        int cellX = intField(preview, "mDelegateCellX");
        int cellY = intField(preview, "mDelegateCellY");
        ViewGroup icons = shortcutsAndWidgetsOf(cellLayout);
        if (icons == null) return null;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child == null) continue;
            int[] xy = readCellXY(child);
            if (xy != null && xy[0] == cellX && xy[1] == cellY) return child;
            View host = resolveDesktopItem(child);
            if (host != null && host != child) {
                int[] hxy = readCellXY(host);
                if (hxy != null && hxy[0] == cellX && hxy[1] == cellY) return host;
            }
        }
        return null;
    }

    private static int[] readCellXY(View view) {
        if (view == null) return null;
        Object lp = view.getLayoutParams();
        if (lp == null) return null;
        Object cx = fieldValue(lp, "cellX");
        Object cy = fieldValue(lp, "cellY");
        if (!(cx instanceof Number) || !(cy instanceof Number)) return null;
        return new int[]{((Number) cx).intValue(), ((Number) cy).intValue()};
    }

    /** DragController.mDragObject.originalView — ColorOS may not keep content on DragView. */
    private static void collectDragControllerSources(View seed, java.util.HashSet<View> out) {
        if (seed == null || out == null) return;
        try {
            Object launcher = resolveLauncherAny(seed);
            Object dragCtrl = invokeNoArgs(launcher, "getDragController");
            if (dragCtrl == null) dragCtrl = fieldValue(launcher, "mDragController");
            Object dragObj = fieldValue(dragCtrl, "mDragObject");
            if (dragObj == null) return;
            // Only suppress the origin while a DragView is still live. After cancel / drop,
            // the DragView is gone and the origin must paint again with the desktop.
            View root = seed.getRootView() != null ? seed.getRootView() : seed;
            if (!hasLiveDragView(root)) return;
            for (String name : new String[] {
                    "originalView", "mOriginalView", "sourceView", "mSourceView"
            }) {
                addSkipIcon(out, fieldValue(dragObj, name));
            }
        } catch (Throwable ignored) { }
    }

    /**
     * When a Folder is open, skip only its FolderIcon on the workspace (not every icon under
     * the floating Folder bounds — that prevented restore on cancel).
     */
    private static void collectOpenFolderDesktopSkips(View seed, View openFolder,
            java.util.HashSet<View> out) {
        if (out == null) return;
        if (openFolder == null) openFolder = findOpenFolderView(seed);
        if (openFolder == null || !isFolderCurrentlyOpen(openFolder)) return;

        Object folderIcon = fieldValue(openFolder, "mFolderIcon");
        addSkipIcon(out, folderIcon);

        View workspace = findWorkspaceAncestor(seed);
        if (workspace == null) return;
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup icons = shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (icons == null) continue;
                for (int j = 0; j < icons.getChildCount(); j++) {
                    View child = icons.getChildAt(j);
                    if (child == null) continue;
                    View host = resolveDesktopItem(child);
                    View target = host != null ? host : child;
                    if (!isFolderIcon(target)) continue;
                    Object linked = fieldValue(target, "mFolder");
                    if (linked == openFolder) {
                        addSkipIcon(out, target);
                        addSkipIcon(out, child);
                    }
                }
            }
        }
    }

    /**
     * Screen rect of icons to hide while dragging into an open Folder.
     * <ul>
     *   <li>Requires {@code isOpen} — cancel/close clears this immediately.</li>
     *   <li>Requires a live DragView — idle open folder keeps workspace icons.</li>
     *   <li>Uses folder content bounds (not the full Folder chrome/scrim) so half the
     *       desktop is not wiped.</li>
     * </ul>
     */
    private static RectF folderDropIconCover(View openFolder, View root) {
        if (openFolder == null || !isFolderCurrentlyOpen(openFolder)) return null;
        if (!hasLiveDragView(root)) return null;
        View content = folderContentView(openFolder);
        RectF cover = content != null ? screenRect(content) : null;
        if (cover == null || cover.width() < 2f || cover.height() < 2f) {
            RectF full = screenRect(openFolder);
            if (full == null || full.width() < 2f || full.height() < 2f) return null;
            // Inset past scrim / margins so cancel-adjacent icons outside the page stay visible.
            cover = new RectF(full);
            float insetX = full.width() * 0.12f;
            float insetY = full.height() * 0.12f;
            cover.inset(insetX, insetY);
        }
        if (cover.width() < 2f || cover.height() < 2f) return null;
        return cover;
    }

    private static boolean hasLiveDragView(View root) {
        View decor = root != null && root.getRootView() != null ? root.getRootView() : root;
        return findDragViewInTree(decor) != null;
    }

    /** True when icon bounds are mostly under the open-folder drop cover. */
    private static boolean iconCoveredByFolderDrop(RectF cover, View view) {
        if (cover == null || view == null) return false;
        RectF icon = screenRect(view);
        if (icon == null || icon.isEmpty()) return false;
        if (!RectF.intersects(cover, icon)) return false;
        RectF overlap = new RectF();
        boolean ok = overlap.setIntersect(cover, icon);
        if (!ok) return false;
        float iconArea = Math.max(1f, icon.width() * icon.height());
        float overlapArea = overlap.width() * overlap.height();
        // Require substantial coverage so icons merely near the folder edge still paint;
        // cancel move-out restores them as soon as cover clears.
        return overlapArea / iconArea >= 0.45f;
    }

    private static View resolveFolderIconFromPreview(Object preview) {
        Object host = fieldValue(preview, "mInvalidateDelegate");
        if (host instanceof View && isFolderIcon((View) host)) return (View) host;
        Object createHost = fieldValue(preview, "mHostView");
        if (createHost instanceof View && isFolderIcon((View) createHost)) return (View) createHost;
        return null;
    }

    /**
     * Create-folder: paint drop-target + dragged icons as shrunk preview glyphs inside the
     * plate. Prefer a live FolderIcon layout rule; fall back to ColorOS-style 2×2 grid.
     */
    private static void paintCreateFolderPreviewIcons(Object preview, View cellLayout,
            RectF plateDest, Canvas canvas) {
        if (plateDest == null || plateDest.width() < 2f || plateDest.height() < 2f) return;
        java.util.ArrayList<View> hosts = new java.util.ArrayList<>(2);
        java.util.HashSet<View> hostSet = new java.util.HashSet<>();
        collectCreateFolderHosts(cellLayout != null ? cellLayout : resolveWorkspaceSeed(), hostSet);
        for (View host : hostSet) {
            if (host != null && iconDrawableOf(host) != null && !hosts.contains(host)) {
                // Prefer the resolved desktop item (has the icon drawable).
                View resolved = resolveDesktopItem(host);
                View use = resolved != null && iconDrawableOf(resolved) != null ? resolved : host;
                if (!hosts.contains(use)) hosts.add(use);
            }
        }
        View root = cellLayout != null
                ? (cellLayout.getRootView() != null ? cellLayout.getRootView() : cellLayout)
                : null;
        View drag = findDragViewInTree(root);
        Drawable dragDrawable = null;
        if (drag != null) {
            View content = dragContentView(drag);
            View origin = dragOriginView(drag);
            View iconSrc = resolveDragIconSource(content, origin);
            dragDrawable = iconSrc != null ? iconDrawableOf(iconSrc) : null;
            if (dragDrawable == null && content != null) dragDrawable = iconDrawableOf(content);
            if (origin != null && !hosts.contains(origin) && iconDrawableOf(origin) != null) {
                // Origin is the moving app — already skipped on desktop; use drawable only.
            }
        }
        java.util.ArrayList<Drawable> glyphs = new java.util.ArrayList<>(2);
        float intrinsic = 0f;
        for (View host : hosts) {
            Drawable d = iconDrawableOf(host);
            if (d == null) continue;
            glyphs.add(d);
            float sz = iconIntrinsicSize(host, d);
            if (sz > intrinsic) intrinsic = sz;
        }
        if (dragDrawable != null) {
            glyphs.add(dragDrawable);
            if (intrinsic <= 0f) {
                intrinsic = Math.max(dragDrawable.getIntrinsicWidth(),
                        dragDrawable.getIntrinsicHeight());
            }
        }
        if (glyphs.isEmpty()) return;
        int count = Math.min(4, glyphs.size());
        float plate = Math.min(plateDest.width(), plateDest.height());
        if (intrinsic < 1f) intrinsic = plate;

        // Try OEM layout rule from any FolderIcon (FlexibleFolderIcon uses ColorOS grid).
        boolean laidOut = paintGlyphsWithLayoutRule(cellLayout, glyphs, count, intrinsic, plate,
                plateDest, canvas);
        if (!laidOut) {
            paintGlyphsColorOsGrid(glyphs, count, plate, plateDest, canvas);
        }
    }

    private static float iconIntrinsicSize(View host, Drawable drawable) {
        Object size = invokeNoArgs(host, "getIconSize");
        if (size instanceof Number && ((Number) size).floatValue() > 0f) {
            return ((Number) size).floatValue();
        }
        if (drawable != null) {
            int w = drawable.getIntrinsicWidth();
            int h = drawable.getIntrinsicHeight();
            if (w > 0 && h > 0) return Math.max(w, h);
            Rect b = drawable.getBounds();
            if (b != null && !b.isEmpty()) return Math.max(b.width(), b.height());
        }
        return 0f;
    }

    private static boolean paintGlyphsWithLayoutRule(View seed, List<Drawable> glyphs, int count,
            float intrinsic, float plate, RectF plateDest, Canvas canvas) {
        try {
            View folderIcon = findFirstFolderIcon(seed);
            Object rule = folderIcon != null ? invokeNoArgs(folderIcon, "getLayoutRule") : null;
            if (rule == null) {
                ClassLoader cl = seed != null ? seed.getClass().getClassLoader()
                        : DesktopIconOverlay.class.getClassLoader();
                for (String name : new String[] {
                        "com.android.launcher3.folder.ClippedFolderIconLayoutRule",
                        "com.android.launcher3.folder.OplusClippedFolderIconLayoutRule",
                        "com.android.launcher3.folder.OplusFolderIconLayoutRule"
                }) {
                    try {
                        Class<?> cls = Class.forName(name, false, cl);
                        rule = cls.getDeclaredConstructor().newInstance();
                        break;
                    } catch (Throwable ignored) { }
                }
            }
            if (rule == null) return false;
            boolean inited = false;
            for (java.lang.reflect.Method m : rule.getClass().getMethods()) {
                if (!"init".equals(m.getName()) || m.getParameterCount() < 2) continue;
                try {
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 3 && types[2] == boolean.class) {
                        m.invoke(rule, Math.round(plate), intrinsic, false);
                        inited = true;
                        break;
                    }
                    if (types.length == 2) {
                        m.invoke(rule, Math.round(plate), intrinsic);
                        inited = true;
                        break;
                    }
                } catch (Throwable ignored) { }
            }
            if (!inited) return false;

            java.lang.reflect.Method compute = null;
            for (java.lang.reflect.Method m : rule.getClass().getMethods()) {
                if ("computePreviewItemDrawingParams".equals(m.getName())
                        && m.getParameterCount() == 3) {
                    compute = m;
                    break;
                }
            }
            if (compute == null) return false;

            for (int i = count - 1; i >= 0; i--) {
                Object params = compute.invoke(rule, i, count, null);
                if (params == null) continue;
                float transX = floatField(params, "transX");
                float transY = floatField(params, "transY");
                float scale = floatField(params, "scale");
                if (scale <= 0f) scale = 0.45f;
                float size = intrinsic * scale;
                Drawable drawable = glyphs.get(i);
                Bitmap bitmap = softwareBitmap(fastBitmapOf(drawable));
                if (bitmap == null || bitmap.isRecycled()) {
                    bitmap = rasterizeDrawable(drawable, Math.max(1, Math.round(size)),
                            Math.max(1, Math.round(size)));
                }
                if (bitmap == null || bitmap.isRecycled()) continue;
                float left = plateDest.left + transX * (plateDest.width() / plate);
                float top = plateDest.top + transY * (plateDest.height() / plate);
                float w = size * (plateDest.width() / plate);
                float h = size * (plateDest.height() / plate);
                BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h),
                        BITMAP_PAINT);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** ColorOS closed-folder preview is a padded 2×2/3×3 grid (not AOSP circle stack). */
    private static void paintGlyphsColorOsGrid(List<Drawable> glyphs, int count, float plate,
            RectF plateDest, Canvas canvas) {
        int grid = count <= 4 ? 2 : 3;
        float inset = plate * 0.14f;
        float gap = plate * 0.05f;
        float cell = (plate - 2f * inset - gap * (grid - 1)) / grid;
        float icon = cell * 0.88f;
        for (int i = count - 1; i >= 0; i--) {
            int row = i / grid;
            int col = i % grid;
            float cx = inset + col * (cell + gap) + cell * 0.5f;
            float cy = inset + row * (cell + gap) + cell * 0.5f;
            Drawable drawable = glyphs.get(i);
            Bitmap bitmap = softwareBitmap(fastBitmapOf(drawable));
            if (bitmap == null || bitmap.isRecycled()) {
                bitmap = rasterizeDrawable(drawable, Math.max(1, Math.round(icon)),
                        Math.max(1, Math.round(icon)));
            }
            if (bitmap == null || bitmap.isRecycled()) continue;
            float sx = plateDest.width() / plate;
            float sy = plateDest.height() / plate;
            float left = plateDest.left + (cx - icon * 0.5f) * sx;
            float top = plateDest.top + (cy - icon * 0.5f) * sy;
            float w = icon * sx;
            float h = icon * sy;
            BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h), BITMAP_PAINT);
        }
    }

    private static View findFirstFolderIcon(View seed) {
        View workspace = findWorkspaceAncestor(seed);
        if (workspace == null) workspace = resolveWorkspaceSeed();
        if (workspace == null) return null;
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup icons = shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (icons == null) continue;
                for (int j = 0; j < icons.getChildCount(); j++) {
                    View child = icons.getChildAt(j);
                    View host = resolveDesktopItem(child);
                    if (isFolderIcon(host)) return host;
                    if (isFolderIcon(child)) return child;
                }
            }
        }
        return null;
    }

    /**
     * ClippedFolderIconLayoutRule.getPosition for a square preview of size {@code plate}.
     * Returns top-left of the icon in preview-local coordinates.
     */
    private static float[] clippedPreviewPosition(int index, int curNumItems, float plate,
            float iconSize) {
        curNumItems = Math.max(curNumItems, 2);
        double theta0 = Math.PI;
        int direction = -1;
        double thetaShift = 0;
        if (curNumItems == 3) thetaShift = Math.PI / 2;
        else if (curNumItems == 4) thetaShift = Math.PI / 4;
        theta0 += direction * thetaShift;
        int idx = index;
        if (curNumItems == 4 && index == 3) idx = 2;
        else if (curNumItems == 4 && index == 2) idx = 3;
        float radius = (1.15f * plate / 2f)
                * (1f + 0.25f * (curNumItems - 2) / 2f);
        double theta = theta0 + idx * (2 * Math.PI / curNumItems) * direction;
        float half = iconSize / 2f;
        float x = plate / 2f + (float) (radius * Math.cos(theta) / 2) - half;
        float y = plate / 2f + (float) (-radius * Math.sin(theta) / 2) - half;
        return new float[]{x, y};
    }

    /** Folder accept: map PreviewItemManager glyphs into the scaled CellLayout plate dest. */
    private static void paintFolderPreviewIconsIntoPlate(View folderIcon, RectF plateDest,
            Canvas canvas) {
        if (folderIcon == null || plateDest == null) return;
        Object manager = fieldValue(folderIcon, "mPreviewItemManager");
        if (manager == null) return;
        Object paramsObj = invokeNoArgs(manager, "getCurrentPageParams");
        if (!(paramsObj instanceof List)) return;
        List<?> params = (List<?>) paramsObj;
        Object intrinsic = invokeNoArgs(manager, "getIntrinsicIconSize");
        float iconSize = intrinsic instanceof Number ? ((Number) intrinsic).floatValue() : 0f;
        // Preview items are laid out in FolderIcon plate space (bgView size).
        Rect plateLocal = folderPlateLocalBounds(folderIcon);
        float srcW = plateLocal != null && plateLocal.width() > 0
                ? plateLocal.width() : Math.max(1, folderIcon.getWidth());
        float srcH = plateLocal != null && plateLocal.height() > 0
                ? plateLocal.height() : Math.max(1, folderIcon.getHeight());
        float sx = plateDest.width() / srcW;
        float sy = plateDest.height() / srcH;
        for (int i = params.size() - 1; i >= 0; i--) {
            Object param = params.get(i);
            if (param == null) continue;
            Object hidden = fieldValue(param, "hidden");
            if (hidden instanceof Boolean && (Boolean) hidden) continue;
            Object d = fieldValue(param, "drawable");
            if (!(d instanceof Drawable)) continue;
            Drawable drawable = (Drawable) d;
            float transX = floatField(param, "transX");
            float transY = floatField(param, "transY");
            float scale = floatField(param, "scale");
            if (scale <= 0f) scale = 1f;
            Rect bounds = drawable.getBounds();
            float size = iconSize > 0f ? iconSize
                    : Math.max(1, bounds.isEmpty() ? 48 : bounds.width());
            Bitmap bitmap = softwareBitmap(fastBitmapOf(drawable));
            if (bitmap == null) {
                bitmap = rasterizeDrawable(drawable, Math.round(size), Math.round(size));
            }
            if (bitmap == null || bitmap.isRecycled()) continue;
            float left = plateDest.left + transX * sx;
            float top = plateDest.top + transY * sy;
            float w = size * scale * sx;
            float h = size * scale * sy;
            BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h), BITMAP_PAINT);
        }
    }

    /**
     * Screen rect for a delegated plate. Prefer the parked capture host's matrix so ashmem
     * dest matches the backdrop sample; fall back to CellLayout cell math.
     */
    private static RectF plateDestOnScreen(View bgView, View cellLayout, int[] cellPoint, Rect bg) {
        if (bgView != null && bgView.getParent() == cellLayout
                && bgView.getWidth() > 0 && bgView.getHeight() > 0) {
            Matrix localToGlobal = new Matrix();
            bgView.transformMatrixToGlobal(localToGlobal);
            RectF dest = new RectF(0f, 0f, bgView.getWidth(), bgView.getHeight());
            localToGlobal.mapRect(dest);
            if (dest.width() >= 1f && dest.height() >= 1f) return dest;
        }
        Matrix cellToGlobal = new Matrix();
        cellLayout.transformMatrixToGlobal(cellToGlobal);
        RectF dest = new RectF(
                cellPoint[0] + bg.left,
                cellPoint[1] + bg.top,
                cellPoint[0] + bg.right,
                cellPoint[1] + bg.bottom);
        cellToGlobal.mapRect(dest);
        return dest;
    }

    /** Park create-folder {@code mBgView} on CellLayout at cell-local plate coords. */
    private static void parkCreateFolderCaptureHost(Object preview, View cellLayout, Rect bg,
            int[] cellPoint) {
        Object bgViewObj = fieldValue(preview, "mBgView");
        if (!(bgViewObj instanceof View) || !(cellLayout instanceof ViewGroup)) return;
        if (bg.width() <= 0 || bg.height() <= 0) return;
        View image = (View) bgViewObj;
        ViewGroup parentCell = (ViewGroup) cellLayout;
        try {
            if (image.getParent() != parentCell) {
                if (image.getParent() instanceof ViewGroup) {
                    try {
                        ((ViewGroup) image.getParent()).removeView(image);
                    } catch (Throwable ignored) { }
                }
                parentCell.addView(image);
            }
            image.setVisibility(View.INVISIBLE);
            int left = cellPoint[0] + bg.left;
            int top = cellPoint[1] + bg.top;
            image.measure(
                    View.MeasureSpec.makeMeasureSpec(bg.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bg.height(), View.MeasureSpec.EXACTLY));
            image.layout(left, top, left + bg.width(), top + bg.height());
            GlassDrawable glass = GlassInstaller.get(image);
            if (glass != null) glass.setBounds(0, 0, bg.width(), bg.height());
        } catch (Throwable ignored) { }
    }

    private static boolean isPreviewBoundToFolderIcon(Object preview) {
        Object host = fieldValue(preview, "mInvalidateDelegate");
        if (host instanceof View && isFolderIcon((View) host)) return true;
        Object createHost = fieldValue(preview, "mHostView");
        return createHost instanceof View && isFolderIcon((View) createHost);
    }

    /**
     * Paint the open {@code Folder} floating page into the ashmem sample so float-menu glass
     * shows the opened folder (not only closed FolderIcon plates) while dropping an app in.
     */
    private static void paintOpenFolders(View seed, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        View folder = findOpenFolderView(seed);
        if (folder == null) return;
        if (folder.getVisibility() == View.GONE) return;
        float alpha = Math.max(folder.getAlpha(), 0f);
        if (alpha < 0.01f) alpha = 1f; // opening anim may briefly report 0
        int w = folder.getWidth();
        int h = folder.getHeight();
        if (w <= 0 || h <= 0) return;
        RectF screen = screenRect(folder);
        if (screen == null || !RectF.intersects(region, screen)) {
            // Folder chrome may not yet have screen coords during spring-load — still try
            // when a FolderIcon under the region reports this folder open.
            if (!openFolderAnchoredInRegion(seed, folder, region)) return;
        }
        java.util.ArrayList<View> suppressed = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        suppressBlurViewsForFolder(folder, suppressed, alphas);
        Bitmap rendered = null;
        try {
            // Always prefer the full Folder page (chrome + icons), not content-only.
            rendered = GlassHwRasterizer.render(w, h, folder::draw);
            if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                rendered = rasterizeViewSoftware(folder, w, h);
            }
            if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                View content = folderContentView(folder);
                if (content != null && content.getWidth() > 0 && content.getHeight() > 0) {
                    int dw = content.getWidth();
                    int dh = content.getHeight();
                    rendered = GlassHwRasterizer.render(dw, dh, content::draw);
                    if (rendered != null && !rendered.isRecycled()) {
                        RectF dest = mapItemRectToTarget(content,
                                new Rect(0, 0, dw, dh), globalToTarget);
                        if (dest != null && dest.width() >= 1f && dest.height() >= 1f
                                && RectF.intersects(dest, new RectF(0, 0,
                                region.width(), region.height()))) {
                            BITMAP_PAINT.setAlpha(Math.round(255f * Math.min(1f, alpha)));
                            canvas.drawBitmap(rendered, null, dest, BITMAP_PAINT);
                            BITMAP_PAINT.setAlpha(255);
                        }
                        if (!rendered.isRecycled()) rendered.recycle();
                        return;
                    }
                }
                return;
            }
            RectF dest = mapItemRectToTarget(folder, new Rect(0, 0, w, h), globalToTarget);
            if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
            BITMAP_PAINT.setAlpha(Math.round(255f * Math.min(1f, alpha)));
            canvas.drawBitmap(rendered, null, dest, BITMAP_PAINT);
            BITMAP_PAINT.setAlpha(255);
        } catch (Throwable ignored) {
        } finally {
            restoreSuppressedAlphas(suppressed, alphas);
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
        }
    }

    private static boolean openFolderAnchoredInRegion(View seed, View folder, RectF region) {
        if (folder == null || region == null) return false;
        List<ViewGroup> containers = iconContainersUnderRegion(
                seed != null ? seed : folder, region);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = resolveDesktopItem(child);
                View icon = isFolderIcon(host) ? host : (isFolderIcon(child) ? child : null);
                if (icon == null || !intersectsRegion(region, icon)) continue;
                Object linked = fieldValue(icon, "mFolder");
                if (linked == folder) return true;
            }
        }
        return false;
    }

    private static void suppressBlurViewsForFolder(View view, java.util.ArrayList<View> out,
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
        Object blurBg = fieldValue(view, "mBlurBgView");
        if (blurBg instanceof View && blurBg != view) {
            suppressBlurViewsForFolder((View) blurBg, out, alphas);
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                suppressBlurViewsForFolder(g.getChildAt(i), out, alphas);
            }
        }
    }

    private static void restoreSuppressedAlphas(java.util.ArrayList<View> views,
            java.util.ArrayList<Float> alphas) {
        for (int i = 0; i < views.size(); i++) {
            try {
                views.get(i).setAlpha(alphas.get(i));
            } catch (Throwable ignored) { }
        }
    }

    private static View folderContentView(View folder) {
        Object content = fieldValue(folder, "mContent");
        if (content instanceof View) return (View) content;
        content = invokeNoArgs(folder, "getContent");
        if (content instanceof View) return (View) content;
        content = fieldValue(folder, "mFolderContent");
        if (content instanceof View) return (View) content;
        return null;
    }

    private static View findOpenFolderView(View seed) {
        try {
            Object launcher = resolveLauncherAny(seed);
            if (launcher != null) {
                ClassLoader cl = launcher.getClass().getClassLoader();
                View fromAfv = invokeOpenFolder(launcher, cl,
                        "com.android.launcher3.AbstractFloatingView", "getOpenFolder");
                if (fromAfv != null) return fromAfv;
                for (String cls : new String[] {
                        "com.android.launcher3.folder.Folder",
                        "com.android.launcher3.folder.OplusFolder"
                }) {
                    View fromGet = invokeOpenFolder(launcher, cl, cls, "getOpen");
                    if (fromGet != null) return fromGet;
                }
            }
            View root = seed != null
                    ? (seed.getRootView() != null ? seed.getRootView() : seed) : null;
            View found = findOpenFolderInTree(root);
            if (found != null) return found;
            return findOpenFolderViaIcons(seed);
        } catch (Throwable ignored) { }
        return null;
    }

    private static View invokeOpenFolder(Object launcher, ClassLoader cl, String className,
            String methodName) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (!methodName.equals(m.getName()) || m.getParameterCount() != 1) continue;
                Object folder = m.invoke(null, launcher);
                if (folder instanceof View && isFolderCurrentlyOpen((View) folder)) {
                    return (View) folder;
                }
                break;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean isFolderCurrentlyOpen(View folder) {
        if (folder == null || !isFolderFloatingView(folder)) return false;
        if (folder.getVisibility() == View.GONE) return false;
        if (folder.getWidth() <= 0 || folder.getHeight() <= 0) return false;
        Object open = invokeNoArgs(folder, "isOpen");
        if (open instanceof Boolean) return (Boolean) open;
        return Boolean.TRUE.equals(fieldValue(folder, "mIsOpen"));
    }

    private static View findOpenFolderViaIcons(View seed) {
        View workspace = findWorkspaceAncestor(seed);
        if (workspace == null) {
            Object launcher = resolveLauncherAny(seed);
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) workspace = (View) ws;
        }
        if (workspace == null) return null;
        if (!(workspace instanceof ViewGroup)) return null;
        ViewGroup pages = (ViewGroup) workspace;
        for (int i = 0; i < pages.getChildCount(); i++) {
            ViewGroup icons = shortcutsAndWidgetsOf(pages.getChildAt(i));
            if (icons == null) continue;
            for (int j = 0; j < icons.getChildCount(); j++) {
                View child = icons.getChildAt(j);
                if (child == null) continue;
                View host = resolveDesktopItem(child);
                View icon = isFolderIcon(host) ? host : (isFolderIcon(child) ? child : null);
                if (icon == null) continue;
                Object folder = fieldValue(icon, "mFolder");
                if (folder instanceof View && isFolderCurrentlyOpen((View) folder)) {
                    return (View) folder;
                }
            }
        }
        return null;
    }

    private static Object resolveLauncherAny(View seed) {
        Object launcher = findLauncher(seed, findWorkspaceAncestor(seed));
        if (launcher != null) return launcher;
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            return tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code Folder} / {@code OplusFolder}, not FolderIcon / FolderPagedView / FolderName. */
    private static boolean isFolderFloatingView(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            if ("Folder".equals(c.getSimpleName())) return true;
        }
        return false;
    }

    private static View findOpenFolderInTree(View root) {
        if (root == null) return null;
        if (isFolderCurrentlyOpen(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findOpenFolderInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** True when an open Folder floating page is visible (force ashmem icons rebuild). */
    static boolean hasVisibleOpenFolder(View seed) {
        View folder = findOpenFolderView(seed);
        return folder != null && folder.getVisibility() != View.GONE
                && folder.getWidth() > 0 && folder.getHeight() > 0;
    }

    /** True when a create-folder / accept plate is CellLayout-delegated (force icons rebuild). */
    static boolean hasDelegatedFolderPreview(View seed) {
        View workspace = findWorkspaceAncestor(seed);
        if (workspace == null) {
            Object launcher = resolveLauncherAny(seed);
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) workspace = (View) ws;
        }
        if (workspace != null) {
            Object createBg = fieldValue(workspace, "mGroupCreatePreviewBg");
            if (isPreviewDrawingDelegated(createBg)) return true;
        }
        if (workspace == null) return false;
        List<ViewGroup> containers = new ArrayList<>();
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup pageIcons = shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (pageIcons != null) containers.add(pageIcons);
            }
        }
        ViewGroup primary = shortcutsAndWidgetsOf(workspace);
        if (primary != null && !containers.contains(primary)) containers.add(primary);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = resolveDesktopItem(child);
                View folder = isFolderIcon(host) ? host : (isFolderIcon(child) ? child : null);
                if (folder == null) continue;
                if (isPreviewDrawingDelegated(fieldValue(folder, "mBackground"))) return true;
            }
        }
        return false;
    }

    private static boolean isPreviewDrawingDelegated(Object preview) {
        Object delegated = invokeNoArgs(preview, "drawingDelegated");
        if (delegated instanceof Boolean) return (Boolean) delegated;
        return fieldValue(preview, "mDrawingDelegate") != null;
    }

    private static boolean cellToPoint(View cellLayout, int cellX, int cellY, int[] out) {
        if (out == null || out.length < 2) return false;
        for (Class<?> c = cellLayout.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method =
                        c.getDeclaredMethod("cellToPoint", int.class, int.class, int[].class);
                method.setAccessible(true);
                method.invoke(cellLayout, cellX, cellY, out);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static int intField(Object object, String name) {
        Object value = fieldValue(object, name);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /** Paint active *DragView contents into a screen-rect sample (float-menu ashmem path). */
    static void paintDragViewsIntoScreenRect(Rect screenRect, Canvas canvas, View root) {
        if (screenRect == null || screenRect.isEmpty() || canvas == null || root == null) return;
        Matrix globalToTarget = new Matrix();
        globalToTarget.setTranslate(-screenRect.left, -screenRect.top);
        paintDragViewsInto(root.getRootView() != null ? root.getRootView() : root,
                canvas, globalToTarget, new RectF(screenRect));
    }

    private static void paintDragViewsInto(View root, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        if (root == null) return;
        if (isDragView(root) && intersectsRegion(region, root)
                && root.getVisibility() != View.GONE
                && root.getWidth() > 0 && root.getHeight() > 0) {
            paintOneDragView(root, canvas, globalToTarget);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                paintDragViewsInto(g.getChildAt(i), canvas, globalToTarget, region);
            }
        }
    }

    private static boolean isDragView(View view) {
        if (view == null) return false;
        String name = view.getClass().getSimpleName();
        return name != null && name.endsWith("DragView");
    }

    /**
     * DragView on ColorOS often keeps only an outline in software {@code draw()} while the
     * real icon lives in {@code mContent} / {@code mOriginView} behind {@code mBlurProp}.
     * Paint FastBitmap / folder plate at DragView-local coords, then overlay the OEM stroke.
     */
    private static void paintOneDragView(View dragView, Canvas canvas, Matrix globalToTarget) {
        Object blurProp = fieldValue(dragView, "mBlurProp");
        boolean clearedBlur = clearField(dragView, "mBlurProp");
        try {
            View content = dragContentView(dragView);
            View origin = dragOriginView(dragView);
            View iconSrc = resolveDragIconSource(content, origin);
            View sizedSrc = null;
            if (content != null && (isDescendant(content, dragView) || content.getParent() == dragView)) {
                sizedSrc = content;
            } else if (iconSrc != null && isDescendant(iconSrc, dragView)) {
                sizedSrc = iconSrc;
            }

            boolean painted = false;
            if (iconSrc != null && isFolderIcon(iconSrc)) {
                View folder = (sizedSrc != null && isFolderIcon(sizedSrc)) ? sizedSrc : iconSrc;
                painted = paintFolderIntoSampleAt(folder, dragView, canvas, globalToTarget);
            }
            if (!painted && sizedSrc != null && !isFolderIcon(sizedSrc)) {
                painted = paintAppIconAt(sizedSrc, dragView, canvas, globalToTarget);
            }
            if (!painted && origin != null && !isFolderIcon(origin)) {
                painted = paintAppIconAt(origin, dragView, canvas, globalToTarget);
            }
            if (!painted && iconSrc != null && !isFolderIcon(iconSrc)) {
                painted = paintAppIconAt(iconSrc, dragView, canvas, globalToTarget);
            }
            if (!painted && paintDragCachedBitmap(dragView, canvas, globalToTarget)) {
                painted = true;
            }
            if (!painted) {
                View drawSrc = sizedSrc != null ? sizedSrc : (content != null ? content : iconSrc);
                if (drawSrc != null && drawSrc != dragView) {
                    painted = paintViewRasterAt(drawSrc, dragView, canvas, globalToTarget);
                }
            }
            if (!painted) {
                paintViewRasterAt(dragView, dragView, canvas, globalToTarget);
            }
            // Selection stroke — folders often draw on DragView; apps often on content.
            paintDragOutline(dragView, content != null ? content : iconSrc, canvas, globalToTarget);
        } finally {
            if (clearedBlur && blurProp != null) {
                restoreField(dragView, "mBlurProp", blurProp);
            }
        }
    }

    /**
     * Collect DragView origins to skip as cell ghosts for the whole drag lifetime.
     * Do not gate on the float-menu sample: when the DragView is outside the glass,
     * painting the origin would wrongly place the icon at the home cell.
     */
    private static void collectDragSources(View root, java.util.HashSet<View> out) {
        if (root == null || out == null) return;
        if (isDragView(root) && root.getVisibility() != View.GONE
                && root.getWidth() > 0 && root.getHeight() > 0) {
            addSkipIcon(out, dragOriginView(root));
            View content = dragContentView(root);
            // Content still on the workspace (not re-parented) — skip to avoid ghost.
            if (content != null && !isDescendant(content, root)) {
                addSkipIcon(out, content);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectDragSources(g.getChildAt(i), out);
            }
        }
    }

    /**
     * ColorOS selection chrome: stroke ring + bottom-right resize handle from
     * {@code ResizeFrameStrokeManager} / {@code IResizeFramePainter}. OEM
     * {@code drawResizeFrame} often paints empty on a software Canvas (OplusPath), so we
     * reconstruct the chrome with standard Path/Paint using the live StrokeManager metrics.
     */
    private static void paintDragOutline(View dragView, View content, Canvas canvas,
            Matrix globalToTarget) {
        if (dragView == null) return;
        if (paintOplusResizeFrame(dragView, canvas, globalToTarget)) return;

        // Last-resort: content chrome (border only).
        java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        Drawable icon = null;
        int iconAlpha = 255;
        CharSequence oldText = null;
        TextView label = null;
        try {
            if (content != null && isFolderIcon(content)) {
                suppressFolderFillForStroke(content, hidden, alphas);
                paintDesktopChrome(content, canvas, globalToTarget);
                return;
            }
            if (content != null) {
                icon = iconDrawableOf(content);
                if (icon != null) {
                    iconAlpha = icon.getAlpha();
                    icon.setAlpha(0);
                }
                if (content instanceof TextView) {
                    label = (TextView) content;
                    oldText = label.getText();
                    try { label.setText(""); } catch (Throwable ignored) { }
                }
                if (content.getWidth() > 0 && content.getHeight() > 0
                        && (isDescendant(content, dragView) || content.getParent() == dragView)) {
                    paintDesktopChrome(content, canvas, globalToTarget);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (icon != null) {
                try { icon.setAlpha(iconAlpha); } catch (Throwable ignored) { }
            }
            if (label != null && oldText != null) {
                try { label.setText(oldText); } catch (Throwable ignored) { }
            }
            for (int i = 0; i < hidden.size(); i++) {
                try {
                    hidden.get(i).setAlpha(alphas.get(i));
                } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Paint selection stroke + BR resize handle using StrokeManager widths/alpha and
     * {@code getResizeFrameBounds()} / {@code getResizeFrameRadius()}.
     */
    private static boolean paintOplusResizeFrame(View dragView, Canvas canvas,
            Matrix globalToTarget) {
        if (dragView == null || canvas == null) return false;
        Object stroke = fieldValue(dragView, "mStrokeManager");
        if (stroke == null) stroke = invokeNoArgs(dragView, "getStrokeManager");
        if (stroke == null) return false;

        Object boundsObj = invokeNoArgs(dragView, "getResizeFrameBounds");
        if (!(boundsObj instanceof Rect)) return false;
        Rect local = new Rect((Rect) boundsObj);
        if (local.isEmpty()) {
            local.set(0, 0, Math.max(1, dragView.getWidth()), Math.max(1, dragView.getHeight()));
        }

        float frameW = numberFloat(invokeNoArgs(stroke, "getMFrameStrokeWidth"), 4f);
        float handleW = numberFloat(invokeNoArgs(stroke, "getMHandleStrokeWidth"), frameW);
        float strokeAlpha = numberFloat(invokeNoArgs(stroke, "getMStrokeAlpha"), 1f);
        float radius = numberFloat(invokeNoArgs(dragView, "getResizeFrameRadius"),
                Math.min(local.width(), local.height()) * 0.22f);
        if (frameW < 0.5f) frameW = 4f;
        if (handleW < 0.5f) handleW = frameW;
        strokeAlpha = Math.max(0f, Math.min(1f, strokeAlpha));

        RectF dest = mapItemRectToTarget(dragView, local, globalToTarget);
        if (dest == null || dest.width() < 2f || dest.height() < 2f) return false;
        float scale = dest.width() / Math.max(1f, local.width());
        float strokePx = Math.max(1.5f, frameW * scale);
        float handlePx = Math.max(1.5f, handleW * scale);
        float radiusPx = Math.max(2f, radius * scale);

        int frameColor = resizeFrameColor(dragView, true);
        int handleColor = resizeFrameColor(dragView, false);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx);
        paint.setColor(frameColor);
        paint.setAlpha(Math.round(android.graphics.Color.alpha(frameColor) * strokeAlpha));

        RectF ring = new RectF(dest);
        float inset = strokePx * 0.5f;
        ring.inset(-inset, -inset);
        canvas.drawRoundRect(ring, radiusPx + inset, radiusPx + inset, paint);

        // Bottom-right resize handle (arc), matching IResizeFramePainter.drawResizeHandle.
        float handleRadius = radiusPx * 2f;
        RectF handleOval = new RectF(
                dest.right - handleRadius,
                dest.bottom - handleRadius,
                dest.right,
                dest.bottom);
        handleOval.offset(strokePx * 0.5f, strokePx * 0.5f);
        paint.setStrokeWidth(handlePx);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(handleColor);
        paint.setAlpha(Math.round(255f * strokeAlpha));
        canvas.drawArc(handleOval, 0f, 90f, false, paint);
        return true;
    }

    private static float numberFloat(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    private static int resizeFrameColor(View dragView, boolean frame) {
        try {
            Object launcher = fieldValue(dragView, "mActivity");
            if (launcher == null) {
                android.content.Context ctx = dragView.getContext();
                if (ctx instanceof android.content.ContextWrapper) {
                    // Activity may be wrapped; still try invoke getItemFrameColor / getHandleColor
                }
            }
            String method = frame ? "getItemFrameColor" : "getHandleColor";
            for (Class<?> c = dragView.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!method.equals(m.getName()) || m.getParameterCount() != 1) continue;
                    try {
                        m.setAccessible(true);
                        Object color = m.invoke(dragView, launcher != null ? launcher
                                : dragView.getContext());
                        if (color instanceof Integer) return (Integer) color;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return frame ? 0xE6FFFFFF : 0xFFFFFFFF;
    }

    private static void paintFolderDragOutline(View dragView, View folder, Canvas canvas,
            Matrix globalToTarget) {
        java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        try {
            suppressFolderFillForStroke(folder, hidden, alphas);
            if (folder.getWidth() > 0 && folder.getHeight() > 0) {
                paintDesktopChrome(folder, canvas, globalToTarget);
            }
            paintNamedStrokeChildren(dragView, folder, canvas, globalToTarget);
        } catch (Throwable ignored) {
        } finally {
            for (int i = 0; i < hidden.size(); i++) {
                try {
                    hidden.get(i).setAlpha(alphas.get(i));
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Sparse stroke/handle rings must not be rejected by {@link #isMostlyEmpty}. */
    private static boolean bitmapHasInk(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        Bitmap soft = softwareBitmap(bitmap);
        if (soft == null || soft.isRecycled()) return false;
        int w = soft.getWidth();
        int h = soft.getHeight();
        if (w <= 0 || h <= 0) return false;
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);
        try {
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    if (((soft.getPixel(x, y) >>> 24) & 0xff) > 8) return true;
                }
            }
            // Also probe corners / BR handle area.
            int[][] probes = {
                    {w / 2, 0}, {w / 2, h - 1}, {0, h / 2}, {w - 1, h / 2},
                    {w - 1, h - 1}, {w - 2, h - 2}, {Math.max(0, w - 8), Math.max(0, h - 8)}
            };
            for (int[] p : probes) {
                if (((soft.getPixel(p[0], p[1]) >>> 24) & 0xff) > 8) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (soft != bitmap && !soft.isRecycled()) {
                try { soft.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Hide plate / previews / title so only the selection stroke remains in FolderIcon.draw. */
    private static void suppressFolderFillForStroke(View folder,
            java.util.ArrayList<View> hidden, java.util.ArrayList<Float> alphas) {
        if (folder == null) return;
        Object background = fieldValue(folder, "mBackground");
        Object bgViewObj = fieldValue(background, "mBgView");
        if (bgViewObj instanceof View) {
            hideViewAlpha((View) bgViewObj, hidden, alphas);
        }
        TextView name = folderNameView(folder);
        if (name != null) hideViewAlpha(name, hidden, alphas);
        Object preview = fieldValue(folder, "mPreviewFrame");
        if (preview instanceof View) hideViewAlpha((View) preview, hidden, alphas);
        if (folder instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) folder;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child == null || isLikelyStrokeView(child)) continue;
                hideViewAlpha(child, hidden, alphas);
            }
        }
    }

    private static void paintNamedStrokeChildren(View dragView, View folder, Canvas canvas,
            Matrix globalToTarget) {
        if (!(dragView instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) dragView;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == null || child == folder) continue;
            if (!isLikelyStrokeView(child)) continue;
            if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.01f) continue;
            try {
                paintDesktopChrome(child, canvas, globalToTarget);
            } catch (Throwable ignored) { }
        }
    }

    private static boolean isLikelyStrokeView(View view) {
        if (view == null) return false;
        String name = view.getClass().getSimpleName();
        if (name == null) return false;
        return name.contains("Stroke")
                || name.contains("Outline")
                || name.contains("Border")
                || name.contains("Ring")
                || name.contains("SelectFrame")
                || name.contains("Selection");
    }

    private static void hideViewAlpha(View view, java.util.ArrayList<View> hidden,
            java.util.ArrayList<Float> alphas) {
        if (view == null) return;
        for (int i = 0; i < hidden.size(); i++) {
            if (hidden.get(i) == view) return;
        }
        hidden.add(view);
        alphas.add(view.getAlpha());
        view.setAlpha(0f);
    }

    private static void hideAllChildren(View root, java.util.ArrayList<View> hidden,
            java.util.ArrayList<Float> alphas) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == null) continue;
            hideViewAlpha(child, hidden, alphas);
        }
    }

    private static void hideDescendantsForOutline(View root, View content,
            java.util.ArrayList<View> hidden, java.util.ArrayList<Float> alphas) {
        hideAllChildren(root, hidden, alphas);
    }

    private static boolean clearField(Object target, String name) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(name); } catch (Throwable ignored) { }
            }
            if (f == null) return false;
            f.setAccessible(true);
            if (f.get(target) == null) return false;
            f.set(target, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void restoreField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(name); } catch (Throwable ignored) { }
            }
            if (f != null) {
                f.setAccessible(true);
                f.set(target, value);
            }
        } catch (Throwable ignored) { }
    }

    private static boolean paintDragCachedBitmap(View dragView, Canvas canvas,
            Matrix globalToTarget) {
        Bitmap bitmap = null;
        for (String field : new String[]{"mBitmap", "mDragBitmap", "mPreviewBitmap"}) {
            Object v = fieldValue(dragView, field);
            if (v instanceof Bitmap) {
                bitmap = softwareBitmap((Bitmap) v);
                if (bitmap != null && !bitmap.isRecycled() && !isMostlyEmpty(bitmap)) break;
                bitmap = null;
            }
        }
        if (bitmap == null) {
            Object got = invokeNoArgs(dragView, "getBitmap");
            if (got instanceof Bitmap) bitmap = softwareBitmap((Bitmap) got);
        }
        if (bitmap == null || bitmap.isRecycled() || isMostlyEmpty(bitmap)) return false;
        int bw = Math.max(1, bitmap.getWidth());
        int bh = Math.max(1, bitmap.getHeight());
        int pw = Math.max(1, dragView.getWidth());
        int ph = Math.max(1, dragView.getHeight());
        // Center intrinsic bitmap in DragView local space (avoids BR inset from icon bounds).
        int dw = Math.min(bw, pw);
        int dh = Math.min(bh, ph);
        int left = Math.max(0, (pw - dw) / 2);
        int top = Math.max(0, (ph - dh) / 2);
        RectF dest = mapItemRectToTarget(dragView, new Rect(left, top, left + dw, top + dh),
                globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(bitmap, null, dest, BITMAP_PAINT);
        return true;
    }

    private static View dragContentView(View dragView) {
        Object content = fieldValue(dragView, "mContent");
        if (content instanceof View) return (View) content;
        content = invokeNoArgs(dragView, "getContentView");
        if (content instanceof View) return (View) content;
        return null;
    }

    private static View dragOriginView(View dragView) {
        Object origin = fieldValue(dragView, "mOriginView");
        if (origin instanceof View) return (View) origin;
        origin = fieldValue(dragView, "mOriginalView");
        if (origin instanceof View) return (View) origin;
        origin = fieldValue(dragView, "mSourceView");
        if (origin instanceof View) return (View) origin;
        return null;
    }

    private static View resolveDragIconSource(View content, View origin) {
        if (content != null && (isFolderIcon(content) || iconDrawableOf(content) != null)) {
            return content;
        }
        if (origin != null && (isFolderIcon(origin) || iconDrawableOf(origin) != null)) {
            return origin;
        }
        if (content instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) content;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child != null && (isFolderIcon(child) || iconDrawableOf(child) != null)) {
                    return child;
                }
            }
        }
        return content != null ? content : origin;
    }

    private static boolean paintAppIconAt(View iconSrc, View positionHost, Canvas canvas,
            Matrix globalToTarget) {
        Drawable drawable = iconDrawableOf(iconSrc);
        Rect localBounds = iconLocalBounds(iconSrc, drawable);
        if (localBounds == null || localBounds.isEmpty()) {
            int iw = Math.max(1, iconSrc.getWidth() > 0 ? iconSrc.getWidth() : positionHost.getWidth());
            int ih = Math.max(1, iconSrc.getHeight() > 0 ? iconSrc.getHeight() : positionHost.getHeight());
            localBounds = new Rect(0, 0, iw, ih);
        }
        Bitmap bitmap = softwareBitmap(fastBitmapOf(drawable));
        if ((bitmap == null || bitmap.isRecycled() || isMostlyEmpty(bitmap)) && drawable != null) {
            bitmap = rasterizeDrawable(drawable,
                    Math.max(1, localBounds.width()), Math.max(1, localBounds.height()));
        }
        if (bitmap == null || bitmap.isRecycled() || isMostlyEmpty(bitmap)) return false;

        // Always map through DragView local space. Using iconSrc global + icon inset, or
        // DragView + icon inset from a detached origin, both push the glyph down-right.
        RectF dest = mapDragContentDest(positionHost, iconSrc, localBounds, globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(bitmap, null, dest, BITMAP_PAINT);
        return true;
    }

    /**
     * Map a content rect into the sample using {@code dragView}'s matrix only.
     * Local rect is content.left/top + bounds when content is a child; otherwise the
     * intrinsic size is centered in the DragView (no icon-padding false offset).
     */
    private static RectF mapDragContentDest(View dragView, View content, Rect localInContent,
            Matrix globalToTarget) {
        if (dragView == null || localInContent == null || localInContent.isEmpty()) return null;
        int iw = Math.max(1, localInContent.width());
        int ih = Math.max(1, localInContent.height());
        Rect localInDrag;
        if (content != null && content != dragView
                && (isDescendant(content, dragView) || content.getParent() == dragView)) {
            int l = content.getLeft() + localInContent.left;
            int t = content.getTop() + localInContent.top;
            localInDrag = new Rect(l, t, l + iw, t + ih);
        } else {
            int pw = Math.max(1, dragView.getWidth());
            int ph = Math.max(1, dragView.getHeight());
            int l = Math.max(0, (pw - iw) / 2);
            int t = Math.max(0, (ph - ih) / 2);
            localInDrag = new Rect(l, t, l + iw, t + ih);
        }
        return mapItemRectToTarget(dragView, localInDrag, globalToTarget);
    }

    private static boolean paintViewRasterAt(View drawSrc, View positionHost, Canvas canvas,
            Matrix globalToTarget) {
        if (drawSrc == null || positionHost == null) return false;
        int w = drawSrc.getWidth() > 0 ? drawSrc.getWidth() : positionHost.getWidth();
        int h = drawSrc.getHeight() > 0 ? drawSrc.getHeight() : positionHost.getHeight();
        if (w <= 0 || h <= 0) return false;
        float oldAlpha = drawSrc.getAlpha();
        int oldVis = drawSrc.getVisibility();
        try {
            if (oldVis != View.VISIBLE) drawSrc.setVisibility(View.VISIBLE);
            if (oldAlpha < 0.99f) drawSrc.setAlpha(1f);
            Bitmap rendered = rasterizeViewSoftware(drawSrc, w, h);
            if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                rendered = GlassHwRasterizer.render(w, h, drawSrc::draw);
            }
            if (rendered == null || rendered.isRecycled()) return false;
            try {
                RectF dest = mapDragContentDest(positionHost, drawSrc,
                        new Rect(0, 0, w, h), globalToTarget);
                if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
                BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(rendered, null, dest, BITMAP_PAINT);
                return !isMostlyEmpty(rendered);
            } finally {
                if (!rendered.isRecycled()) rendered.recycle();
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { drawSrc.setAlpha(oldAlpha); } catch (Throwable ignored) { }
            try { drawSrc.setVisibility(oldVis); } catch (Throwable ignored) { }
        }
    }

    /**
     * @param skipHost when non-null (drag glass), skip the dragged folder itself
     * @param skipIcons DragView origins / create-folder drop-target / open FolderIcon only
     * @param openFolderCover while dragging into an open Folder, skip icons under folder content
     */
    private static void paintDesktopInto(View seed, Canvas canvas, Matrix globalToTarget,
            RectF region, View skipHost, java.util.HashSet<View> skipIcons,
            RectF openFolderCover) {
        List<ViewGroup> containers = iconContainersUnderRegion(seed, region);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                // Do not skip low-alpha icons: ColorOS dims the workspace during create-folder /
                // folder accept; those icons must still appear in float-menu glass.
                if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                if (!intersectsRegion(region, child)) continue;
                View host = resolveDesktopItem(child);
                if (host == null) continue;
                if (skipHost != null && isDragSourceOrSelf(skipHost, host)) continue;
                if (skipIcons != null && (skipIcons.contains(host) || skipIcons.contains(child)
                        || isListedDragSource(host, child, skipIcons))) {
                    continue;
                }
                // Drop-into-open-folder: omit workspace icons under the folder page (overlap).
                // Cover is null once the folder closes or the drag ends → icons restore.
                if (openFolderCover != null
                        && (iconCoveredByFolderDrop(openFolderCover, child)
                        || iconCoveredByFolderDrop(openFolderCover, host))) {
                    continue;
                }
                try {
                    if (isFolderIcon(host) || isFolderIcon(child)) {
                        paintFolderIntoSample(isFolderIcon(host) ? host : child,
                                canvas, globalToTarget);
                    } else if (isWidgetOrCard(host) || isWidgetOrCard(child)) {
                        paintWidget(isWidgetOrCard(host) ? host : child,
                                canvas, globalToTarget);
                    } else {
                        paintAppIcon(host, canvas, globalToTarget);
                        paintAppLabel(host, canvas, globalToTarget);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        View workspace = findWorkspaceAncestor(seed);
        View pageIndicator = findPageIndicator(seed, workspace);
        if (pageIndicator != null
                && pageIndicator.getVisibility() == View.VISIBLE
                && pageIndicator.getAlpha() > 0.01f
                && pageIndicator.getWidth() > 0 && pageIndicator.getHeight() > 0
                && intersectsRegion(region, pageIndicator)) {
            try {
                paintDesktopChrome(pageIndicator, canvas, globalToTarget);
            } catch (Throwable ignored) { }
        }
    }

    private static boolean isListedDragSource(View host, View child,
            java.util.HashSet<View> dragSources) {
        for (View src : dragSources) {
            if (src == null || isBroadContainer(src)) continue;
            if (host == src || child == src) return true;
            // Limited descendant match for wrap containers — never against CellLayout/Workspace.
            if (isDescendant(host, src) || isDescendant(child, src)) return true;
        }
        return false;
    }

    private static View resolveWorkspaceSeed() {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) return (View) ws;
            if (launcher instanceof android.app.Activity) {
                return ((android.app.Activity) launcher).getWindow().getDecorView();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static void clearFolderSnaps() {
        for (FolderSnap snap : FOLDER_SNAPS.values()) {
            if (snap != null && snap.bitmap != null && !snap.bitmap.isRecycled()) {
                snap.bitmap.recycle();
            }
        }
        FOLDER_SNAPS.clear();
        for (WidgetSnap snap : WIDGET_SNAPS.values()) {
            if (snap != null && snap.bitmap != null && !snap.bitmap.isRecycled()) {
                snap.bitmap.recycle();
            }
        }
        WIDGET_SNAPS.clear();
    }

    /** Skip the dragged folder / glass host's own FolderIcon. */
    private static boolean isDragSourceOrSelf(View glassHost, View item) {
        if (item == glassHost) return true;
        if (isDescendant(glassHost, item)) return true;
        View dragView = findDragView(glassHost);
        if (dragView == null) return false;
        if (isDescendant(item, dragView)) return true;
        Object content = invokeNoArgs(dragView, "getContentView");
        if (content == item) return true;
        if (content instanceof View) {
            View contentView = (View) content;
            if (isDescendant(item, contentView) || isDescendant(contentView, item)) return true;
        }
        return false;
    }

    private static boolean isDescendant(View child, View ancestor) {
        for (View current = child; current != null; ) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return false;
    }

    /** Match DragView and OEM subclasses (OplusDragView, …). */
    private static View findDragView(View start) {
        for (View current = start; current != null; ) {
            String name = current.getClass().getSimpleName();
            if (name != null && name.endsWith("DragView")) return current;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    private static boolean hasActiveDragView(View seed) {
        View root = seed != null
                ? (seed.getRootView() != null ? seed.getRootView() : seed)
                : null;
        return findDragViewInTree(root) != null;
    }

    private static View findDragViewInTree(View root) {
        if (root == null) return null;
        if (isDragView(root) && root.getVisibility() != View.GONE
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

    private static void paintAppIcon(View item, Canvas canvas, Matrix globalToTarget) {
        Drawable drawable = iconDrawableOf(item);
        Rect localBounds = iconLocalBounds(item, drawable);
        if (localBounds == null || localBounds.isEmpty()) return;
        RectF dest = mapItemRectToTarget(item, localBounds, globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return;

        Bitmap bitmap = softwareBitmap(fastBitmapOf(drawable));
        if (bitmap == null) {
            bitmap = rasterizeDrawable(drawable, localBounds.width(), localBounds.height());
        }
        if (bitmap == null || bitmap.isRecycled()) return;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(bitmap, null, dest, BITMAP_PAINT);
    }

    /**
     * Rasterize a non-icon desktop chrome View (page indicator dots) into the sample.
     * Prefer software draw. Skip {@link #isMostlyEmpty}: padded wrap_content + few dots
     * is mostly transparent by design. Hide nested {@code mBlurBgView} LiquidGlass and the
     * OEM frosted pill ({@code mBgAlpha} / {@code drawBackgroundIfNeeded}) so only the dots
     * remain — otherwise float-menu glass bakes an opaque indicator plate.
     */
    private static void paintDesktopChrome(View host, Canvas canvas, Matrix globalToTarget) {
        if (host == null) return;
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return;

        View blurBg = null;
        float prevBlurAlpha = 1f;
        int prevBlurVis = View.VISIBLE;
        Object prevBgAlpha = fieldValue(host, "mBgAlpha");
        Object blurObj = fieldValue(host, "mBlurBgView");
        if (blurObj instanceof View) {
            blurBg = (View) blurObj;
            prevBlurAlpha = blurBg.getAlpha();
            prevBlurVis = blurBg.getVisibility();
        }

        Bitmap rendered = null;
        GlassInstaller.beginPageIndicatorDotSample();
        try {
            if (blurBg != null) {
                blurBg.setAlpha(0f);
                blurBg.setVisibility(View.INVISIBLE);
            }
            if (prevBgAlpha instanceof Number) {
                assignField(host, "mBgAlpha", 0);
            }
            // Software only: HW path can still bake GlassDrawable into an opaque plate.
            rendered = rasterizeViewSoftware(host, w, h);
        } finally {
            if (prevBgAlpha instanceof Number) {
                assignField(host, "mBgAlpha", prevBgAlpha);
            }
            if (blurBg != null) {
                try { blurBg.setAlpha(prevBlurAlpha); } catch (Throwable ignored) { }
                try { blurBg.setVisibility(prevBlurVis); } catch (Throwable ignored) { }
            }
            GlassInstaller.endPageIndicatorDotSample();
        }
        if (rendered == null || rendered.isRecycled()) return;
        try {
            RectF dest = mapItemRectToTarget(host,
                    new Rect(0, 0, rendered.getWidth(), rendered.getHeight()), globalToTarget);
            if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
            int alpha = Math.round(255f * Math.max(0f, Math.min(1f, host.getAlpha())));
            BITMAP_PAINT.setAlpha(alpha);
            canvas.drawBitmap(rendered, null, dest, BITMAP_PAINT);
        } finally {
            if (!rendered.isRecycled()) rendered.recycle();
        }
    }

    private static void assignField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(name); } catch (Throwable ignored) { }
            }
            if (f == null) return;
            f.setAccessible(true);
            Class<?> type = f.getType();
            if (value instanceof Number) {
                Number n = (Number) value;
                if (type == int.class || type == Integer.class) f.set(target, n.intValue());
                else if (type == float.class || type == Float.class) f.set(target, n.floatValue());
                else if (type == long.class || type == Long.class) f.set(target, n.longValue());
                else f.set(target, value);
            } else {
                f.set(target, value);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Widgets have no FastBitmap. Offscreen HW rasterize (same idea as folder plates);
     * never hide DragView / PixelCopy the window.
     */
    private static void paintWidget(View host, Canvas canvas, Matrix globalToTarget) {
        if (host == null) return;
        View content = widgetContentView(host);
        int w = content.getWidth();
        int h = content.getHeight();
        if (w <= 0 || h <= 0) {
            content = host;
            w = host.getWidth();
            h = host.getHeight();
        }
        if (w <= 0 || h <= 0) return;
        Bitmap snap = widgetCompositeBitmap(host, content, w, h);
        if (snap == null || snap.isRecycled()) return;
        View mapped = (snap.getWidth() == host.getWidth() && snap.getHeight() == host.getHeight())
                ? host : content;
        RectF dest = mapItemRectToTarget(mapped,
                new Rect(0, 0, snap.getWidth(), snap.getHeight()), globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(snap, null, dest, BITMAP_PAINT);
    }

    private static Bitmap widgetCompositeBitmap(View host, View content, int width, int height) {
        WidgetSnap cached = WIDGET_SNAPS.get(host);
        if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()
                && cached.width == width && cached.height == height) {
            return cached.bitmap;
        }
        final View drawTarget = content;
        Bitmap rendered = GlassHwRasterizer.render(width, height, drawTarget::draw);
        if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            rendered = rasterizeViewSoftware(drawTarget, width, height);
        }
        if ((rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered))
                && drawTarget != host) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            rendered = GlassHwRasterizer.render(host.getWidth(), host.getHeight(), host::draw);
            if (rendered != null && !isMostlyEmpty(rendered)) {
                width = host.getWidth();
                height = host.getHeight();
            } else if (rendered != null) {
                rendered.recycle();
                rendered = null;
            }
        }
        if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            return null;
        }
        WidgetSnap snap = new WidgetSnap();
        snap.bitmap = rendered;
        snap.width = width;
        snap.height = height;
        WidgetSnap previous = WIDGET_SNAPS.put(host, snap);
        if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                && !previous.bitmap.isRecycled()) {
            previous.bitmap.recycle();
        }
        return rendered;
    }

    private static boolean isMostlyEmpty(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        Bitmap soft = softwareBitmap(bitmap);
        if (soft == null || soft.isRecycled()) return true;
        int w = soft.getWidth();
        int h = soft.getHeight();
        if (w <= 0 || h <= 0) return true;
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

    private static Bitmap rasterizeViewSoftware(View view, int width, int height) {
        if (view == null || width <= 0 || height <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
        } catch (Throwable ignored) {
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    private static View widgetContentView(View host) {
        Object view = invokeNoArgs(host, "getWidgetView");
        if (view instanceof View && ((View) view).getWidth() > 0
                && ((View) view).getHeight() > 0) {
            return (View) view;
        }
        view = invokeNoArgs(host, "getWidgetChild");
        if (view instanceof View && ((View) view).getWidth() > 0
                && ((View) view).getHeight() > 0) {
            return (View) view;
        }
        return host;
    }

    private static void paintAppLabel(View item, Canvas canvas, Matrix globalToTarget) {
        if (!(item instanceof TextView)) return;
        TextView tv = (TextView) item;
        CharSequence text = tv.getText();
        if (TextUtils.isEmpty(text) || tv.getVisibility() != View.VISIBLE) return;
        if (tv.getAlpha() <= 0.01f) return;

        Matrix itemToGlobal = new Matrix();
        item.transformMatrixToGlobal(itemToGlobal);
        Matrix itemToHost = new Matrix();
        itemToHost.setConcat(globalToTarget, itemToGlobal);
        int save = canvas.save();
        canvas.concat(itemToHost);
        try {
            Layout layout = tv.getLayout();
            if (layout != null) {
                canvas.translate(tv.getCompoundPaddingLeft(), tv.getExtendedPaddingTop());
                int previous = tv.getPaint().getAlpha();
                tv.getPaint().setAlpha(Math.round(255f * Math.min(1f, tv.getAlpha())));
                layout.draw(canvas);
                tv.getPaint().setAlpha(previous);
            } else {
                TITLE_PAINT.set(tv.getPaint());
                TITLE_PAINT.setColor(tv.getCurrentTextColor());
                TITLE_PAINT.setTextAlign(Paint.Align.CENTER);
                float x = tv.getWidth() * 0.5f;
                float y = tv.getHeight() - tv.getPaddingBottom() - TITLE_PAINT.descent();
                canvas.drawText(text.toString(), x, y, TITLE_PAINT);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void paintFolderIntoSample(View folderIcon, Canvas canvas,
            Matrix globalToTarget) {
        // Accept/create delegated path: plate + shrunk glyphs are painted by
        // paintOneDelegatedPreview at CellLayout geometry. Skip resting FolderIcon composite
        // (would show full-size / unscaled preview icons).
        Object background = fieldValue(folderIcon, "mBackground");
        if (isPreviewDrawingDelegated(background)) return;
        // Open folder: floating Folder page is painted separately.
        Object folder = fieldValue(folderIcon, "mFolder");
        if (folder instanceof View) {
            Object open = invokeNoArgs(folder, "isOpen");
            if (Boolean.TRUE.equals(open)
                    || Boolean.TRUE.equals(fieldValue(folder, "mIsOpen"))) {
                return;
            }
        }
        int w = folderIcon.getWidth();
        int h = folderIcon.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap composite = folderCompositeBitmap(folderIcon, w, h, true);
        if (composite == null || composite.isRecycled()) return;
        RectF dest = mapItemRectToTarget(folderIcon, new Rect(0, 0, w, h), globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(composite, null, dest, BITMAP_PAINT);
    }

    /**
     * Folder plate for DragView — plate + previews only (no title), sized to the plate /
     * DragView visual, mapped through DragView local space to avoid BR offset.
     */
    private static boolean paintFolderIntoSampleAt(View folderIcon, View positionHost, Canvas canvas,
            Matrix globalToTarget) {
        Rect plateLocal = folderPlateLocalBounds(folderIcon);
        int w;
        int h;
        if (plateLocal != null && !plateLocal.isEmpty()) {
            w = plateLocal.width();
            h = plateLocal.height();
        } else {
            w = folderIcon.getWidth() > 0 ? folderIcon.getWidth() : positionHost.getWidth();
            h = folderIcon.getHeight() > 0 ? folderIcon.getHeight() : positionHost.getHeight();
            // Strip approximate label row when we cannot resolve the plate view.
            TextView name = folderNameView(folderIcon);
            if (name != null && name.getVisibility() == View.VISIBLE && name.getHeight() > 0) {
                h = Math.max(1, h - name.getHeight());
            }
            plateLocal = new Rect(0, 0, w, h);
        }
        if (w <= 0 || h <= 0) return false;
        Bitmap composite = folderCompositeBitmap(folderIcon, w, h, false);
        if (composite == null || composite.isRecycled()) return false;
        RectF dest = mapDragContentDest(positionHost, folderIcon, plateLocal, globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(composite, null, dest, BITMAP_PAINT);
        return true;
    }

    /** Plate bounds inside the FolderIcon (excludes the title TextView). */
    private static Rect folderPlateLocalBounds(View folderIcon) {
        Object background = fieldValue(folderIcon, "mBackground");
        Object bgViewObj = fieldValue(background, "mBgView");
        if (bgViewObj instanceof View) {
            View bg = (View) bgViewObj;
            if (bg.getWidth() > 0 && bg.getHeight() > 0) {
                return new Rect(bg.getLeft(), bg.getTop(),
                        bg.getLeft() + bg.getWidth(), bg.getTop() + bg.getHeight());
            }
        }
        Object preview = fieldValue(folderIcon, "mPreviewFrame");
        if (preview instanceof View) {
            View frame = (View) preview;
            if (frame.getWidth() > 0 && frame.getHeight() > 0) {
                return new Rect(frame.getLeft(), frame.getTop(),
                        frame.getLeft() + frame.getWidth(), frame.getTop() + frame.getHeight());
            }
        }
        return null;
    }

    private static Bitmap folderCompositeBitmap(View folderIcon, int width, int height) {
        return folderCompositeBitmap(folderIcon, width, height, true);
    }

    private static Bitmap folderCompositeBitmap(View folderIcon, int width, int height,
            boolean includeName) {
        Object background = fieldValue(folderIcon, "mBackground");
        Object bgViewObj = fieldValue(background, "mBgView");
        View bgView = bgViewObj instanceof View ? (View) bgViewObj : null;
        Bitmap plateSource = bgView == null ? null : BackdropCapture.snapshotOf(bgView);
        FolderSnap cached = FOLDER_SNAPS.get(folderIcon);
        if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()
                && cached.width == width && cached.height == height
                && cached.includeName == includeName
                && cached.plateSource == plateSource) {
            return cached.bitmap;
        }

        final int drawW = width;
        final int drawH = height;
        final boolean drawName = includeName;
        Bitmap rendered = GlassHwRasterizer.render(drawW, drawH, hwCanvas -> {
            DEPTH.set(DEPTH.get() + 1);
            try {
                // When compositing plate-only, shift so bgView top-left maps to (0,0).
                int dx = 0;
                int dy = 0;
                if (!drawName && bgView != null) {
                    dx = -bgView.getLeft();
                    dy = -bgView.getTop();
                }
                int save = hwCanvas.save();
                hwCanvas.translate(dx, dy);
                try {
                    drawFolderPlate(folderIcon, hwCanvas);
                    paintFolderPreviewBitmaps(folderIcon, hwCanvas);
                    if (drawName) {
                        paintFolderName(folderIcon, hwCanvas);
                    }
                } finally {
                    hwCanvas.restoreToCount(save);
                }
            } finally {
                DEPTH.set(Math.max(0, DEPTH.get() - 1));
            }
        });
        if (rendered == null || rendered.isRecycled()) return null;

        FolderSnap snap = new FolderSnap();
        snap.bitmap = rendered;
        snap.plateSource = plateSource;
        snap.width = width;
        snap.height = height;
        snap.includeName = includeName;
        FolderSnap previous = FOLDER_SNAPS.put(folderIcon, snap);
        if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                && !previous.bitmap.isRecycled()) {
            previous.bitmap.recycle();
        }
        return rendered;
    }

    private static void drawFolderPlate(View folderIcon, Canvas canvas) {
        Object background = fieldValue(folderIcon, "mBackground");
        // Accept/create path: plate is painted by paintDelegatedFolderPreviews at CellLayout
        // geometry (scaled). Skip the resting mBgView plate to avoid a stale double draw.
        if (isPreviewDrawingDelegated(background)) return;
        Object bgViewObj = fieldValue(background, "mBgView");
        if (!(bgViewObj instanceof View)) return;
        View bgView = (View) bgViewObj;
        if (bgView.getWidth() <= 0 || bgView.getHeight() <= 0) return;
        int save = canvas.save();
        canvas.translate(bgView.getLeft(), bgView.getTop());
        try {
            GlassDrawable glass = GlassInstaller.get(bgView);
            if (glass != null) {
                Rect old = new Rect(glass.getBounds());
                if (old.isEmpty()) {
                    glass.setBounds(0, 0, bgView.getWidth(), bgView.getHeight());
                }
                glass.draw(canvas);
                if (old.isEmpty()) glass.setBounds(old);
                return;
            }
            Drawable drawable = bgView.getBackground();
            if (drawable != null) {
                Rect old = new Rect(drawable.getBounds());
                if (old.isEmpty()) {
                    drawable.setBounds(0, 0, bgView.getWidth(), bgView.getHeight());
                }
                drawable.draw(canvas);
                if (old.isEmpty()) drawable.setBounds(old);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void paintFolderPreviewBitmaps(View folderIcon, Canvas canvas) {
        Object manager = fieldValue(folderIcon, "mPreviewItemManager");
        if (manager == null) return;
        Object paramsObj = invokeNoArgs(manager, "getCurrentPageParams");
        if (!(paramsObj instanceof List)) return;
        List<?> params = (List<?>) paramsObj;
        Object intrinsic = invokeNoArgs(manager, "getIntrinsicIconSize");
        float iconSize = intrinsic instanceof Number
                ? ((Number) intrinsic).floatValue()
                : 0f;
        for (int i = params.size() - 1; i >= 0; i--) {
            Object param = params.get(i);
            if (param == null) continue;
            Object hidden = fieldValue(param, "hidden");
            if (hidden instanceof Boolean && (Boolean) hidden) continue;
            Object d = fieldValue(param, "drawable");
            if (!(d instanceof Drawable)) continue;
            Drawable drawable = (Drawable) d;
            float transX = floatField(param, "transX");
            float transY = floatField(param, "transY");
            float scale = floatField(param, "scale");
            if (scale <= 0f) scale = 1f;
            Rect bounds = drawable.getBounds();
            float size = iconSize > 0f ? iconSize
                    : Math.max(1, bounds.isEmpty() ? 48 : bounds.width());
            Bitmap bitmap = softwareBitmap(fastBitmapOf(drawable));
            if (bitmap == null) {
                bitmap = rasterizeDrawable(drawable, Math.round(size), Math.round(size));
            }
            if (bitmap == null || bitmap.isRecycled()) continue;
            int save = canvas.save();
            canvas.translate(transX, transY);
            canvas.scale(scale, scale);
            BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(bitmap, null, new RectF(0f, 0f, size, size), BITMAP_PAINT);
            canvas.restoreToCount(save);
        }
    }

    private static void paintFolderName(View folderIcon, Canvas canvas) {
        TextView name = folderNameView(folderIcon);
        if (name == null || name.getVisibility() != View.VISIBLE) return;
        CharSequence text = name.getText();
        if (TextUtils.isEmpty(text)) return;
        int save = canvas.save();
        canvas.translate(name.getLeft(), name.getTop());
        try {
            float previous = name.getAlpha();
            name.setAlpha(1f);
            name.draw(canvas);
            name.setAlpha(previous);
        } catch (Throwable ignored) {
            TITLE_PAINT.setColor(name.getCurrentTextColor());
            TITLE_PAINT.setTextSize(name.getTextSize());
            TITLE_PAINT.setTypeface(name.getTypeface());
            TITLE_PAINT.setTextAlign(Paint.Align.CENTER);
            float x = name.getWidth() * 0.5f;
            float y = name.getBaseline();
            if (y <= 0f) y = name.getPaddingTop() - TITLE_PAINT.ascent();
            canvas.drawText(text.toString(), x, y, TITLE_PAINT);
        }
        canvas.restoreToCount(save);
    }

    private static TextView folderNameView(View folderIcon) {
        Object field = fieldValue(folderIcon, "mFolderName");
        if (field instanceof TextView) return (TextView) field;
        Object got = invokeNoArgs(folderIcon, "getFolderName");
        if (got instanceof TextView) return (TextView) got;
        Object title = invokeNoArgs(folderIcon, "getTitle");
        if (title instanceof TextView) return (TextView) title;
        return null;
    }

    private static Bitmap softwareBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        if (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                return copy == null || copy.isRecycled() ? null : copy;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return bitmap;
    }

    private static Bitmap rasterizeDrawable(Drawable drawable, int width, int height) {
        if (drawable == null || width <= 0 || height <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Rect old = new Rect(drawable.getBounds());
        int previous = drawable.getAlpha();
        drawable.setAlpha(255);
        drawable.setBounds(0, 0, width, height);
        try {
            drawable.draw(canvas);
        } catch (Throwable ignored) {
            bitmap.recycle();
            return null;
        } finally {
            drawable.setAlpha(previous);
            drawable.setBounds(old);
        }
        return bitmap;
    }

    private static float floatField(Object object, String name) {
        Object value = fieldValue(object, name);
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }

    private static RectF mapItemRectToTarget(View item, Rect localBounds,
            Matrix globalToTarget) {
        Matrix itemToGlobal = new Matrix();
        item.transformMatrixToGlobal(itemToGlobal);
        RectF screen = new RectF(localBounds);
        itemToGlobal.mapRect(screen);
        RectF dest = new RectF(screen);
        globalToTarget.mapRect(dest);
        return dest;
    }

    private static ViewGroup shortcutsAndWidgetsOf(View seed) {
        if (seed == null) return null;
        Object direct = invokeNoArgs(seed, "getShortcutsAndWidgets");
        if (direct instanceof ViewGroup) return (ViewGroup) direct;
        for (View current = seed; current != null; ) {
            Object widgets = invokeNoArgs(current, "getShortcutsAndWidgets");
            if (widgets instanceof ViewGroup) return (ViewGroup) widgets;
            String name = current.getClass().getName();
            if (name.endsWith("ShortcutAndWidgetContainer") && current instanceof ViewGroup) {
                return (ViewGroup) current;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    /**
     * All ShortcutAndWidgetContainers whose page intersects the glass.
     * Cross-page drag must not depend on a single (possibly stale) CellLayout seed —
     * paint every on-screen Workspace page under the finger, plus Hotseat (bottom dock).
     */
    private static List<ViewGroup> iconContainersUnderGlass(View seed, View glassHost) {
        RectF region = screenRect(glassHost);
        return iconContainersUnderRegion(seed != null ? seed : glassHost, region);
    }

    private static List<ViewGroup> iconContainersUnderRegion(View seed, RectF region) {
        List<ViewGroup> out = new ArrayList<>();
        if (region == null) return out;
        ViewGroup primary = shortcutsAndWidgetsOf(seed);
        if (primary != null) out.add(primary);
        View workspace = findWorkspaceAncestor(seed);
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                View page = pages.getChildAt(i);
                if (page == null || page.getWidth() <= 0 || page.getHeight() <= 0) continue;
                if (!intersectsRegion(region, page)) continue;
                ViewGroup icons = shortcutsAndWidgetsOf(page);
                if (icons != null && !out.contains(icons)) out.add(icons);
            }
        }
        // Hotseat is a CellLayout sibling of Workspace (not a Workspace page). Same paint
        // path as desktop icons/folders — include when the glass overlaps the dock.
        View hotseat = findHotseat(seed, workspace);
        if (hotseat != null && hotseat.getWidth() > 0 && hotseat.getHeight() > 0
                && intersectsRegion(region, hotseat)) {
            ViewGroup icons = shortcutsAndWidgetsOf(hotseat);
            if (icons != null && !out.contains(icons)) out.add(icons);
        }
        return out;
    }

    /**
     * ColorOS: {@code Launcher.getHotseat()} → {@code OplusHotseat} extends CellLayout.
     * Fall back to scanning the Workspace parent (DragLayer) for a *Hotseat* child.
     */
    private static View findHotseat(View start, View workspace) {
        Object launcher = findLauncher(start, workspace);
        Object hotseat = invokeNoArgs(launcher, "getHotseat");
        if (hotseat instanceof View) return (View) hotseat;

        ViewParent parent = workspace != null ? workspace.getParent()
                : (start != null ? start.getParent() : null);
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getSimpleName();
                if (name != null && name.contains("Hotseat")) return child;
            }
        }
        return null;
    }

    /**
     * ColorOS: {@code Workspace.getPageIndicator()} → {@code OplusPageIndicator}
     * (dots between icon grid and Hotseat). Also accept DragLayer siblings named *PageIndicator*.
     */
    private static View findPageIndicator(View start, View workspace) {
        if (workspace != null) {
            Object indicator = invokeNoArgs(workspace, "getPageIndicator");
            if (indicator == null) indicator = fieldValue(workspace, "mPageIndicator");
            if (indicator instanceof View) return (View) indicator;
        }
        Object launcher = findLauncher(start, workspace);
        Object ws = invokeNoArgs(launcher, "getWorkspace");
        if (ws != null && ws != workspace) {
            Object indicator = invokeNoArgs(ws, "getPageIndicator");
            if (indicator == null) indicator = fieldValue(ws, "mPageIndicator");
            if (indicator instanceof View) return (View) indicator;
        }
        ViewParent parent = workspace != null ? workspace.getParent()
                : (start != null ? start.getParent() : null);
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getName();
                if (name != null && name.contains("PageIndicator")) return child;
            }
        }
        return null;
    }

    private static Object findLauncher(View start, View workspace) {
        Object launcher = null;
        if (workspace != null) {
            launcher = invokeNoArgs(workspace, "getLauncher");
            if (launcher == null) launcher = fieldValue(workspace, "mLauncher");
            if (launcher == null) launcher = fieldValue(workspace, "mActivity");
        }
        if (launcher == null && start != null) {
            for (View current = start; current != null; ) {
                Object got = invokeNoArgs(current, "getLauncher");
                if (got == null) got = fieldValue(current, "mLauncher");
                if (got == null) got = fieldValue(current, "mActivity");
                if (got != null) return got;
                String name = current.getClass().getSimpleName();
                if (name != null && name.contains("Launcher") && !name.contains("AppWidget")) {
                    return current;
                }
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
        }
        return launcher;
    }

    private static View findWorkspaceAncestor(View start) {
        for (View current = start; current != null; ) {
            String name = current.getClass().getSimpleName();
            if (name != null && name.contains("Workspace") && !name.contains("Cell")) {
                return current;
            }
            Object got = invokeNoArgs(current, "getWorkspace");
            if (got instanceof View) return (View) got;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    private static View applicationChild(View item) {
        Object child = invokeNoArgs(item, "getApplicationChild");
        return child instanceof View ? (View) child : item;
    }

    private static View resolveDesktopItem(View child) {
        if (child == null) return null;
        if (isWrapContainer(child)) {
            Object target = invokeNoArgs(child, "curWidgetView");
            if (!(target instanceof View)) target = invokeNoArgs(child, "getTargetView");
            if (target instanceof View) return (View) target;
        }
        return applicationChild(child);
    }

    private static boolean intersectsOnScreen(View a, View b) {
        RectF ra = screenRect(a);
        RectF rb = screenRect(b);
        return ra != null && rb != null && RectF.intersects(ra, rb);
    }

    private static boolean intersectsRegion(RectF region, View view) {
        if (region == null) return false;
        RectF rb = screenRect(view);
        return rb != null && RectF.intersects(region, rb);
    }

    private static RectF screenRect(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Matrix matrix = new Matrix();
        view.transformMatrixToGlobal(matrix);
        RectF rect = new RectF(0f, 0f, view.getWidth(), view.getHeight());
        matrix.mapRect(rect);
        return rect;
    }

    private static boolean isFolderIcon(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            if ("FolderIcon".equals(c.getSimpleName())) return true;
        }
        return false;
    }

    private static boolean isWrapContainer(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("WrapWidget") || name.contains("WrapCard")
                    || name.contains("WrapMultiSize") || name.contains("WrapAdaptive")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWidgetOrCard(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("AppWidgetHostView")
                    || name.contains("LauncherAppWidget")
                    || name.equals("LauncherCardView")
                    || name.equals("TitleCardView")
                    || name.contains("CardHostView")) {
                return true;
            }
        }
        return isWrapContainer(view);
    }

    private static Drawable iconDrawableOf(View iconView) {
        Object icon = invokeNoArgs(iconView, "getIcon");
        if (icon instanceof Drawable) return (Drawable) icon;
        if (iconView instanceof TextView) {
            for (Drawable d : ((TextView) iconView).getCompoundDrawables()) {
                if (d != null) return d;
            }
        }
        return null;
    }

    private static Bitmap fastBitmapOf(Drawable drawable) {
        if (drawable == null) return null;
        Object bitmap = fieldValue(drawable, "mBitmap");
        if (bitmap instanceof Bitmap) return (Bitmap) bitmap;
        Object got = invokeNoArgs(drawable, "getBitmap");
        if (got instanceof Bitmap) return (Bitmap) got;
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
        }
        return null;
    }

    private static Rect iconLocalBounds(View iconView, Drawable drawable) {
        Rect fromMethod = new Rect();
        if (invokeIconBounds(iconView, fromMethod) && !fromMethod.isEmpty()) {
            return fromMethod;
        }
        if (drawable != null) {
            Rect bounds = drawable.getBounds();
            if (bounds != null && !bounds.isEmpty()) return new Rect(bounds);
        }
        Object itemBounds = invokeNoArgs(iconView, "getItemIconBounds");
        if (itemBounds instanceof Rect) {
            Rect r = (Rect) itemBounds;
            if (!r.isEmpty()) return new Rect(r);
        } else if (itemBounds instanceof RectF) {
            Rect r = new Rect();
            ((RectF) itemBounds).round(r);
            if (!r.isEmpty()) return r;
        }
        int edge = Math.min(iconView.getWidth(), iconView.getHeight());
        return edge > 0 ? new Rect(0, 0, edge, edge) : null;
    }

    private static boolean invokeIconBounds(View iconView, Rect out) {
        for (Class<?> c = iconView.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod("getIconBounds", Rect.class);
                method.setAccessible(true);
                method.invoke(iconView, out);
                return true;
            } catch (Throwable ignored) { }
            try {
                java.lang.reflect.Method method =
                        c.getDeclaredMethod("getIconBounds", int.class, Rect.class);
                method.setAccessible(true);
                Object size = invokeNoArgs(iconView, "getIconSize");
                int iconSize = size instanceof Number
                        ? ((Number) size).intValue()
                        : Math.min(iconView.getWidth(), iconView.getHeight());
                method.invoke(iconView, iconSize, out);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static Object fieldValue(Object object, String name) {
        for (Class<?> c = object == null ? null : object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object invokeNoArgs(Object object, String name) {
        for (Class<?> c = object == null ? null : object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(object);
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
