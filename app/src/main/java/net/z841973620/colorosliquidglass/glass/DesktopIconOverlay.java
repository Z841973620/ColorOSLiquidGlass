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
     */
    static void paintIntoTargetLocal(View glassHost, Canvas canvas) {
        if (glassHost == null || canvas == null) return;
        if (findDragView(glassHost) == null) return;
        View seed = BackdropCapture.overlaySourceOf(glassHost);
        List<ViewGroup> containers = iconContainersUnderGlass(seed, glassHost);
        if (containers.isEmpty()) return;
        Matrix targetToGlobal = new Matrix();
        glassHost.transformMatrixToGlobal(targetToGlobal);
        Matrix globalToTarget = new Matrix();
        if (!targetToGlobal.invert(globalToTarget)) return;

        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                if (!intersectsOnScreen(glassHost, child)) continue;
                View host = resolveDesktopItem(child);
                if (host == null || isDragSourceOrSelf(glassHost, host)) continue;
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

        // Page dots sit between Workspace icons and Hotseat — not inside either container.
        View workspace = findWorkspaceAncestor(seed != null ? seed : glassHost);
        View pageIndicator = findPageIndicator(seed != null ? seed : glassHost, workspace);
        if (pageIndicator != null
                && pageIndicator.getVisibility() == View.VISIBLE
                && pageIndicator.getAlpha() > 0.01f
                && pageIndicator.getWidth() > 0 && pageIndicator.getHeight() > 0
                && intersectsOnScreen(glassHost, pageIndicator)) {
            try {
                paintDesktopChrome(pageIndicator, canvas, globalToTarget);
            } catch (Throwable ignored) { }
        }
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
     * Same offscreen draw approach as widgets — no window PixelCopy / DragView hide.
     */
    private static void paintDesktopChrome(View host, Canvas canvas, Matrix globalToTarget) {
        if (host == null) return;
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap rendered = GlassHwRasterizer.render(w, h, host::draw);
        if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            rendered = rasterizeViewSoftware(host, w, h);
        }
        if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            return;
        }
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
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return true;
        int stepX = Math.max(1, w / 6);
        int stepY = Math.max(1, h / 6);
        int opaque = 0;
        for (int y = stepY / 2; y < h; y += stepY) {
            for (int x = stepX / 2; x < w; x += stepX) {
                if (((bitmap.getPixel(x, y) >>> 24) & 0xff) > 16) opaque++;
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
        int w = folderIcon.getWidth();
        int h = folderIcon.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap composite = folderCompositeBitmap(folderIcon, w, h);
        if (composite == null || composite.isRecycled()) return;
        RectF dest = mapItemRectToTarget(folderIcon, new Rect(0, 0, w, h), globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
        BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(composite, null, dest, BITMAP_PAINT);
    }

    private static Bitmap folderCompositeBitmap(View folderIcon, int width, int height) {
        Object background = fieldValue(folderIcon, "mBackground");
        Object bgViewObj = fieldValue(background, "mBgView");
        View bgView = bgViewObj instanceof View ? (View) bgViewObj : null;
        Bitmap plateSource = bgView == null ? null : BackdropCapture.snapshotOf(bgView);
        FolderSnap cached = FOLDER_SNAPS.get(folderIcon);
        if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()
                && cached.width == width && cached.height == height
                && cached.plateSource == plateSource) {
            return cached.bitmap;
        }

        Bitmap rendered = GlassHwRasterizer.render(width, height, hwCanvas -> {
            DEPTH.set(DEPTH.get() + 1);
            try {
                drawFolderPlate(folderIcon, hwCanvas);
                paintFolderPreviewBitmaps(folderIcon, hwCanvas);
                paintFolderName(folderIcon, hwCanvas);
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
        FolderSnap previous = FOLDER_SNAPS.put(folderIcon, snap);
        if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                && !previous.bitmap.isRecycled()) {
            previous.bitmap.recycle();
        }
        return rendered;
    }

    private static void drawFolderPlate(View folderIcon, Canvas canvas) {
        Object background = fieldValue(folderIcon, "mBackground");
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
        List<ViewGroup> out = new ArrayList<>();
        ViewGroup primary = shortcutsAndWidgetsOf(seed);
        if (primary != null) out.add(primary);
        View workspace = findWorkspaceAncestor(seed != null ? seed : glassHost);
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                View page = pages.getChildAt(i);
                if (page == null || page.getWidth() <= 0 || page.getHeight() <= 0) continue;
                if (!intersectsOnScreen(glassHost, page)) continue;
                ViewGroup icons = shortcutsAndWidgetsOf(page);
                if (icons != null && !out.contains(icons)) out.add(icons);
            }
        }
        // Hotseat is a CellLayout sibling of Workspace (not a Workspace page). Same paint
        // path as desktop icons/folders — include when the glass overlaps the dock.
        View hotseat = findHotseat(seed != null ? seed : glassHost, workspace);
        if (hotseat != null && hotseat.getWidth() > 0 && hotseat.getHeight() > 0
                && intersectsOnScreen(glassHost, hotseat)) {
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
