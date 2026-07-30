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

    private static final class FolderSnap {
        Bitmap bitmap;
        Bitmap plateSource;
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
        ViewGroup icons = shortcutsAndWidgetsOf(seed);
        if (icons == null) return;
        Matrix targetToGlobal = new Matrix();
        glassHost.transformMatrixToGlobal(targetToGlobal);
        Matrix globalToTarget = new Matrix();
        if (!targetToGlobal.invert(globalToTarget)) return;

        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (!intersectsOnScreen(glassHost, child)) continue;
            View item = applicationChild(child);
            if (item == null || isDragSourceOrSelf(glassHost, item)) continue;
            try {
                if (isFolderIcon(item)) {
                    paintFolderIntoSample(item, canvas, globalToTarget);
                } else {
                    paintAppIcon(item, canvas, globalToTarget);
                    paintAppLabel(item, canvas, globalToTarget);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static void clearFolderSnaps() {
        for (FolderSnap snap : FOLDER_SNAPS.values()) {
            if (snap != null && snap.bitmap != null && !snap.bitmap.isRecycled()) {
                snap.bitmap.recycle();
            }
        }
        FOLDER_SNAPS.clear();
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

    private static View applicationChild(View item) {
        Object child = invokeNoArgs(item, "getApplicationChild");
        return child instanceof View ? (View) child : item;
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
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            if ("FolderIcon".equals(c.getSimpleName())) return true;
        }
        return false;
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
