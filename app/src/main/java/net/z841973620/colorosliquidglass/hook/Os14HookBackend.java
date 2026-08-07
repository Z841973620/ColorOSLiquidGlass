package net.z841973620.colorosliquidglass.hook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import io.github.libxposed.api.XposedModule;

import net.z841973620.colorosliquidglass.GlassConfig;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.WallpaperScaleTracker;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ColorOS 13 / 14 Hook surface.
 * <p>
 * Folder plates use {@code OplusPreviewBackground.mBgDrawable} + {@code drawBackground(Canvas)}
 * (no {@code mBgView} / {@code LayerBlurDrawable}). Depth blur is
 * {@code OplusDepthController.setBlur*} → {@code SurfaceControl.setBackgroundBlurRadius}.
 * Existing ColorOS 16 Hooks in {@link ModuleMain} are left untouched.
 */
public final class Os14HookBackend implements HookBackend {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final ColorDrawable TRANSPARENT = new ColorDrawable(Color.TRANSPARENT);
    private static final Object HOST_TAG = new Object();

    private final Set<Executable> hooked = new HashSet<>();
    private final Map<Object, ImageView> previewHosts =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Drawable> savedOemBg =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean folderOpenActive;
    private volatile boolean folderDragActive;
    private Object folderDragSource;
    private SharedPreferences prefs;
    private XposedModule module;

    @Override
    public void hookLauncher(XposedModule module, ClassLoader cl, SharedPreferences prefs) {
        this.module = module;
        this.prefs = prefs;
        log(4, "Os14HookBackend: hooking Launcher (ColorOS 13/14 APIs)");
        hookFolderPreviewBackground(cl);
        hookFolderVisibility(cl);
        hookFolderCloseMorphGlass(cl);
        hookFolderRefreshEvents(cl);
        hookFolderPopupBlur(cl);
        hookFolderDragPreview(cl);
        hookFolderDragDepthBlur(cl);
        hookRecentsClearButton(cl);
        hookRecentsTaskShortcuts(cl);
        hookToggleBarChrome(cl);
        hookToggleBarSecondLevelPreviewGlass(cl);
        hookToggleBarApplyButtonGlass(cl);
        hookPagePreviewItemGlass(cl);
        hookPendingCardPreviewGlass(cl);
        hookPageIndicatorGlass(cl);
        hookUnlockGlassRefresh(cl);
        hookWallpaperScaleTracking(cl);
        hookDesktopBackdropIpc(cl);
        after(cl, "com.android.launcher3.folder.FolderIcon", "onFolderClose", o -> {
            endFolderOpen(o);
            syncFolderIcon(o);
        });
    }

    @Override
    public void hookSystemUi(XposedModule module, ClassLoader cl, SharedPreferences prefs) {
        this.module = module;
        this.prefs = prefs;
        log(4, "Os14HookBackend: hooking SystemUI (ColorOS 13/14 APIs)");
        // ColorOS 14 SystemUI has no FlexibleMenuManager liquid-glass float menus.
        // COUI popup windows may still appear in some shells — install glass when present.
        after(cl, "com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", "prepareShowMainMenu",
                this::applyCouiPopupListGlass);
        after(cl, "com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", "dismiss",
                ignored -> {
                    try {
                        net.z841973620.colorosliquidglass.glass.BehindDisplayCapture.stopAllSessions();
                    } catch (Throwable ignored2) { }
                });
        DesktopBackdropClient.warmUp();
    }

    // ── Folder preview (mBgDrawable / drawBackground(Canvas)) ───────────────

    private void hookFolderPreviewBackground(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.OplusPreviewBackground";
        try {
            Class<?> c = Class.forName(className, false, cl);

            // setup(...) — attach capture host + install glass after OEM builds mBgDrawable.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setup") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object preview = chain.getThisObject();
                        Object host = field(preview, "mInvalidateDelegate");
                        if (isFolderIcon(host)) syncFolderPreview(host, preview);
                    } catch (Throwable e) {
                        log(5, "Os14 setup glass failed", e);
                    }
                    return result;
                });
            }

            after(cl, className, "updateBgColorFilter", preview -> {
                // Long-press resetPressAnimStateForLongClick restores OEM white via this
                // method — always strip it while the module is on.
                if (enabled()) suppressOemDrawable(preview);
                Object host = field(preview, "mInvalidateDelegate");
                if (isFolderIcon(host)) syncFolderPreview(host, preview);
            });
            // BigFolderBackground overrides updateBgColorFilter — hook it too.
            try {
                Class<?> bigBg = Class.forName(
                        "com.android.launcher3.folder.big.BigFolderBackground", false, cl);
                for (Method m : bigBg.getDeclaredMethods()) {
                    if (!m.getName().equals("updateBgColorFilter") || m.isBridge() || m.isSynthetic()) {
                        continue;
                    }
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (enabled()) suppressOemDrawable(chain.getThisObject());
                        } catch (Throwable ignored) { }
                        return result;
                    });
                }
                for (Method m : bigBg.getDeclaredMethods()) {
                    if (!m.getName().equals("drawBackground") || m.getParameterCount() < 1
                            || m.isBridge() || m.isSynthetic()) continue;
                    hookOnce(m, chain -> {
                        if (!enabled()) return chain.proceed();
                        Object preview = chain.getThisObject();
                        suppressOemDrawable(preview);
                        // Only skip canvas glass for the dragged folder (DragView bake).
                        // Sibling folders must keep drawing glass while one folder moves.
                        if (folderDragActive && !isCreateFolderPreview(preview)
                                && isDragSourcePreview(preview)) {
                            return null;
                        }
                        if (chain.getArgs().size() >= 1 && chain.getArg(0) instanceof Canvas
                                && drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                        suppressOemDrawable(preview);
                        return null;
                    });
                }
            } catch (Throwable e) {
                log(5, "Os14 BigFolderBackground glass unavailable", e);
            }

            // Resting FolderIcon + delegated CellLayout create/accept all call drawBackground(Canvas).
            try {
                Method drawBg = c.getDeclaredMethod("drawBackground", Canvas.class);
                hookOnce(drawBg, chain -> {
                    if (!enabled()) return chain.proceed();
                    if (GlassInstaller.isPreviewBackdropCapturing()) return null;
                    Object preview = chain.getThisObject();
                    try {
                        suppressOemDrawable(preview);
                        // DragView bake: suppress white only for the dragged folder.
                        // Create-folder CellLayout preview must still draw glass on canvas.
                        if (folderDragActive && !isCreateFolderPreview(preview)
                                && isDragSourcePreview(preview)) {
                            return null;
                        }
                        if (drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 drawBackground glass failed", e);
                    }
                    // Prefer empty plate over OEM white brightening the glass.
                    suppressOemDrawable(preview);
                    return null;
                });
            } catch (Throwable e) {
                log(5, "Os14 drawBackground(Canvas) hook unavailable", e);
            }

            // Keep glass alive through accept / create-folder delegate transitions.
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("animateToAccept")
                        || name.equals("clearDrawingDelegate")
                        || name.equals("delegateDrawing")
                        || name.equals("lambda$animateToAccept$0"))) continue;
                final String methodName = name;
                hookOnce(m, chain -> {
                    Object preview = chain.getThisObject();
                    boolean createFolder = isCreateFolderPreview(preview)
                            || resolveFolderIcon(preview) == null;
                    if (methodName.equals("clearDrawingDelegate") && createFolder) {
                        detachCreateFolderCaptureHost(preview);
                    }
                    Object result = chain.proceed();
                    try {
                        if (methodName.equals("clearDrawingDelegate")) {
                            if (createFolder || resolveFolderIcon(preview) == null) {
                                detachCreateFolderCaptureHost(preview);
                                suppressOemDrawable(preview);
                            } else {
                                Object host = resolveFolderIcon(preview);
                                if (host != null) syncFolderPreview(host, preview);
                            }
                        } else if (methodName.equals("animateToAccept")
                                || methodName.equals("lambda$animateToAccept$0")
                                || methodName.equals("delegateDrawing")) {
                            suppressOemDrawable(preview);
                            if (resolveFolderIcon(preview) == null) {
                                placeCreateFolderCaptureHost(preview);
                            } else {
                                ensureCaptureHost(preview);
                            }
                            Object host = resolveFolderIcon(preview);
                            if (host instanceof View) ((View) host).invalidate();
                            Object delegate = field(preview, "mDrawingDelegate");
                            if (delegate instanceof View) ((View) delegate).invalidate();
                        }
                        if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
                    } catch (Throwable e) {
                        log(5, "Os14 " + methodName + " glass failed", e);
                    }
                    return result;
                });
            }

            try {
                Class<?> previewBg = Class.forName(
                        "com.android.launcher3.folder.PreviewBackground", false, cl);
                for (Method m : previewBg.getDeclaredMethods()) {
                    if (!m.getName().equals("animateToRest") || m.isBridge() || m.isSynthetic()) {
                        continue;
                    }
                    hookOnce(m, chain -> {
                        Object preview = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            Object host = resolveFolderIcon(preview);
                            if (host == null) {
                                detachCreateFolderCaptureHost(preview);
                                suppressOemDrawable(preview);
                            } else {
                                syncFolderPreview(host, preview);
                            }
                            if (DesktopBackdropHub.isLive()) {
                                DesktopBackdropSampler.invalidateCache();
                            }
                        } catch (Throwable e) {
                            log(5, "Os14 animateToRest glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, "Os14 PreviewBackground.animateToRest unavailable", e);
            }
        } catch (Throwable e) {
            log(5, "Os14 OplusPreviewBackground unavailable", e);
        }

        // FolderIcon.setFolderBackground — used when create-folder becomes a real folder.
        after(cl, "com.android.launcher3.folder.FolderIcon", "setFolderBackground", icon -> {
            Object preview = field(icon, "mBackground");
            if (preview != null) syncFolderPreview(icon, preview);
        });
        after(cl, "com.android.launcher3.folder.OplusFolderIcon", "setFolderBackground", icon -> {
            Object preview = field(icon, "mBackground");
            if (preview != null) syncFolderPreview(icon, preview);
        });
    }

    private boolean drawPreviewGlassOnCanvas(Object preview, Canvas canvas) {
        if (canvas == null || preview == null) return false;
        Object folderHost = resolveFolderIcon(preview);
        if (folderHost != null && isFolderOpen(folderHost)) return false;

        boolean createFolder = isCreateFolderPreview(preview);
        if (createFolder) {
            // Park on DragLayer (never CellLayout / ShortcutsAndWidgets — wrong LayoutParams crash).
            placeCreateFolderCaptureHost(preview);
        }

        ImageView host = ensureCaptureHost(preview);
        if (host == null) return false;
        if (createFolder) {
            placeCreateFolderCaptureHost(preview);
            host = previewHosts.get(preview);
            if (host == null) return false;
        }

        suppressOemDrawable(preview);
        GlassInstaller.installImage(host, currentConfig());
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null) return false;

        Rect plate = backgroundRect(preview);
        if (plate == null || plate.width() <= 0 || plate.height() <= 0) return false;

        if (!createFolder) {
            layoutCaptureHost(preview, host, plate);
        }
        applyPreviewRadius(preview, host, plate);
        glass.setBounds(0, 0, plate.width(), plate.height());
        host.setVisibility(View.INVISIBLE);

        // Create-folder: require an attached DragLayer host; otherwise skip bright fallback.
        if (createFolder && (host.getParent() == null || !host.isAttachedToWindow())) {
            invalidatePreviewDelegate(preview);
            return false;
        }

        int save = canvas.save();
        try {
            canvas.translate(plate.left, plate.top);
            GlassInstaller.forceCapturePreviewPlate(host);
            // Without a backdrop frame, GlassDrawable paints a bright translucent fallback.
            if (!GlassInstaller.hasBackdropFrame(host)) {
                invalidatePreviewDelegate(preview);
                return false;
            }
            glass.draw(canvas);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** True while Workspace mFolderCreateBg is delegated onto CellLayout (no FolderIcon yet). */
    private boolean isCreateFolderPreview(Object preview) {
        if (preview == null) return false;
        if (resolveFolderIcon(preview) != null) return false;
        return field(preview, "mDrawingDelegate") != null;
    }

    /**
     * Park create-folder capture host on DragLayer at the plate's window position.
     * Never addView to CellLayout / ShortcutAndWidgetContainer — they require
     * {@code CellLayout.LayoutParams} and ClassCastException restarts Launcher.
     */
    private void placeCreateFolderCaptureHost(Object preview) {
        if (!enabled() || preview == null) return;
        if (resolveFolderIcon(preview) != null) return;
        Object delegate = field(preview, "mDrawingDelegate");
        if (!(delegate instanceof ViewGroup)) return;
        ViewGroup cellLayout = (ViewGroup) delegate;
        ViewGroup dragLayer = resolveDragLayer(cellLayout);
        if (dragLayer == null) return;

        ImageView host = ensureCaptureHost(preview);
        if (host == null) return;

        Rect plate = backgroundRect(preview);
        if (plate == null || plate.width() <= 0 || plate.height() <= 0) return;

        int cellX = 0;
        int cellY = 0;
        Object cx = field(preview, "mDelegateCellX");
        Object cy = field(preview, "mDelegateCellY");
        if (cx instanceof Number) cellX = ((Number) cx).intValue();
        if (cy instanceof Number) cellY = ((Number) cy).intValue();
        int[] cellPoint = new int[2];
        if (!cellToPoint(cellLayout, cellX, cellY, cellPoint)) return;

        int[] cellLoc = new int[2];
        int[] dlLoc = new int[2];
        try {
            cellLayout.getLocationInWindow(cellLoc);
            dragLayer.getLocationInWindow(dlLoc);
        } catch (Throwable ignored) {
            return;
        }

        int left = cellLoc[0] + cellPoint[0] + plate.left - dlLoc[0];
        int top = cellLoc[1] + cellPoint[1] + plate.top - dlLoc[1];
        int width = plate.width();
        int height = plate.height();

        try {
            if (host.getParent() != null && host.getParent() != dragLayer) {
                detachCreateFolderCaptureHost(preview);
                host = ensureCaptureHost(preview);
                if (host == null) return;
            }
            if (host.getParent() != dragLayer) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
                lp.leftMargin = left;
                lp.topMargin = top;
                dragLayer.addView(host, lp);
            } else {
                ViewGroup.LayoutParams raw = host.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                    lp.width = width;
                    lp.height = height;
                    lp.leftMargin = left;
                    lp.topMargin = top;
                    host.setLayoutParams(lp);
                }
            }
            host.setVisibility(View.INVISIBLE);
            host.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            host.layout(left, top, left + width, top + height);
            applyPreviewRadius(preview, host, plate);
        } catch (Throwable e) {
            log(5, "Os14 placeCreateFolderCaptureHost failed", e);
        }
    }

    private void detachCreateFolderCaptureHost(Object preview) {
        if (preview == null) return;
        ImageView host = previewHosts.get(preview);
        if (host == null) return;
        try {
            GlassInstaller.setOverlaySource(host, null);
        } catch (Throwable ignored) { }
        try {
            ViewGroup parent = host.getParent() instanceof ViewGroup
                    ? (ViewGroup) host.getParent() : null;
            // Only detach DragLayer parks — never remove FolderIcon children here.
            if (parent != null && !isFolderIcon(parent)) {
                parent.removeView(host);
            }
        } catch (Throwable ignored) { }
        try {
            GlassInstaller.uninstall(host);
        } catch (Throwable ignored) { }
        previewHosts.remove(preview);
    }

    private static ViewGroup resolveDragLayer(View from) {
        for (View current = from; current != null; ) {
            if (isClassOrSubclass(current, "com.android.launcher3.dragndrop.DragLayer")
                    || isClassOrSubclass(current, "com.android.launcher3.views.BaseDragLayer")
                    || isClassOrSubclass(current, "com.android.launcher3.OplusDragLayer")) {
                return current instanceof ViewGroup ? (ViewGroup) current : null;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void invalidatePreviewDelegate(Object preview) {
        Object delegate = field(preview, "mDrawingDelegate");
        if (delegate instanceof View) {
            ((View) delegate).postInvalidateOnAnimation();
        }
    }

    private void syncFolderPreview(Object folderIcon, Object preview) {
        if (!enabled() || preview == null) return;
        if (folderIcon != null && isFolderOpen(folderIcon)) {
            ImageView host = previewHosts.get(preview);
            if (host != null) host.setVisibility(View.INVISIBLE);
            return;
        }
        ensureCaptureHost(preview);
        suppressOemDrawable(preview);
        if (folderIcon instanceof View) {
            ((View) folderIcon).invalidate();
            ((View) folderIcon).post(() -> {
                ensureCaptureHost(preview);
                suppressOemDrawable(preview);
                ((View) folderIcon).invalidate();
            });
        }
    }

    private void syncFolderIcon(Object folderIcon) {
        Object preview = field(folderIcon, "mBackground");
        if (preview != null) syncFolderPreview(folderIcon, preview);
    }

    private ImageView ensureCaptureHost(Object preview) {
        if (preview == null) return null;
        ImageView existing = previewHosts.get(preview);
        if (existing != null && existing.getTag() == HOST_TAG) {
            GlassInstaller.installImage(existing, currentConfig());
            return existing;
        }

        Context ctx = null;
        Object ctxObj = field(preview, "mContext");
        if (ctxObj instanceof Context) ctx = (Context) ctxObj;
        Object attach = resolveFolderIcon(preview);
        if (attach == null) attach = field(preview, "mInvalidateDelegate");
        if (attach == null) attach = field(preview, "mDrawingDelegate");
        if (!(attach instanceof ViewGroup)) return null;
        if (ctx == null) ctx = ((View) attach).getContext();
        if (ctx == null) return null;

        ImageView host = new ImageView(ctx);
        host.setTag(HOST_TAG);
        host.setVisibility(View.INVISIBLE);
        host.setClickable(false);
        host.setFocusable(false);
        try {
            ViewGroup parent = (ViewGroup) attach;
            // Resting FolderIcon only. Never addView to CellLayout — it requires
            // CellLayout.LayoutParams and will crash on layout/measure.
            if (isFolderIcon(attach) && host.getParent() == null) {
                parent.addView(host, 0, new FrameLayout.LayoutParams(0, 0));
            } else if (host.getParent() == null && isFolderIcon(attach)) {
                parent.addView(host, 0);
            } else if (host.getParent() == null && !isClassOrSubclass(attach,
                    "com.android.launcher3.CellLayout")
                    && !isClassOrSubclass(attach, "com.android.launcher3.OplusCellLayout")) {
                parent.addView(host, 0);
            }
            // Create-folder delegated to CellLayout: keep host detached; canvas path skips
            // until a FolderIcon owns the preview.
        } catch (Throwable e) {
            log(5, "Os14 attach capture host failed", e);
            return null;
        }
        previewHosts.put(preview, host);
        GlassInstaller.installImage(host, currentConfig());
        Rect plate = backgroundRect(preview);
        if (plate != null) layoutCaptureHost(preview, host, plate);
        return host;
    }

    private void layoutCaptureHost(Object preview, ImageView host, Rect plate) {
        if (host == null || plate == null) return;
        Object delegate = field(preview, "mDrawingDelegate");
        ViewGroup parent = host.getParent() instanceof ViewGroup
                ? (ViewGroup) host.getParent() : null;
        int left = plate.left;
        int top = plate.top;
        int right = plate.right;
        int bottom = plate.bottom;
        // When parked on CellLayout, getBackgroundRect is cell-local; offset by cell origin.
        if (delegate instanceof ViewGroup && parent == delegate) {
            int cellX = 0;
            int cellY = 0;
            Object cx = field(preview, "mDelegateCellX");
            Object cy = field(preview, "mDelegateCellY");
            if (cx instanceof Number) cellX = ((Number) cx).intValue();
            if (cy instanceof Number) cellY = ((Number) cy).intValue();
            int[] cellPoint = new int[2];
            if (cellToPoint(delegate, cellX, cellY, cellPoint)) {
                left = cellPoint[0] + plate.left;
                top = cellPoint[1] + plate.top;
                right = cellPoint[0] + plate.right;
                bottom = cellPoint[1] + plate.bottom;
            }
        }
        try {
            host.measure(
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, right - left), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, bottom - top), View.MeasureSpec.EXACTLY));
            host.layout(left, top, right, bottom);
        } catch (Throwable ignored) { }
    }

    private void applyPreviewRadius(Object preview, ImageView host, Rect plate) {
        float radius = 0f;
        Object r = field(preview, "mRadius");
        if (r instanceof Number) radius = ((Number) r).floatValue();
        if (radius <= 0f && plate != null) {
            // Match default icon corner when OEM has not set mRadius yet.
            radius = Math.min(plate.width(), plate.height()) * 0.22f;
        }
        Object scale = field(preview, "mScale");
        if (scale instanceof Number) radius *= ((Number) scale).floatValue();
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass != null && radius > 0f) {
            glass.setCornerRadii(radius, radius, radius, radius);
        }
    }

    private void suppressOemDrawable(Object preview) {
        if (preview == null) return;
        Object bg = field(preview, "mBgDrawable");
        if (bg instanceof Drawable && bg != TRANSPARENT) {
            savedOemBg.putIfAbsent(preview, (Drawable) bg);
            setField(preview, "mBgDrawable", TRANSPARENT);
        } else if (bg == null) {
            setField(preview, "mBgDrawable", TRANSPARENT);
        }
        // Parent PreviewBackground may still hold an opaque mBgColor for AOSP paint path.
        Object color = field(preview, "mBgColor");
        if (color instanceof Number && ((Number) color).intValue() != Color.TRANSPARENT) {
            setField(preview, "mBgColor", Color.TRANSPARENT);
        }
    }

    private static Rect backgroundRect(Object preview) {
        Object rectObj = invokeNoArgs(preview, "getBackgroundRect");
        if (rectObj instanceof Rect) return new Rect((Rect) rectObj);
        Object bounds = new Rect();
        try {
            Method getBounds = null;
            Class<?> c = preview.getClass();
            while (c != null && getBounds == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("getBounds") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Rect.class) {
                        getBounds = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (getBounds != null) {
                getBounds.setAccessible(true);
                getBounds.invoke(preview, bounds);
                return (Rect) bounds;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean cellToPoint(Object cellLayout, int cellX, int cellY, int[] out) {
        try {
            Method m = null;
            Class<?> c = cellLayout.getClass();
            while (c != null) {
                for (Method cand : c.getDeclaredMethods()) {
                    if (!cand.getName().equals("cellToPoint") || cand.getParameterCount() != 3) {
                        continue;
                    }
                    Class<?>[] p = cand.getParameterTypes();
                    if (p[0] == int.class && p[1] == int.class && p[2] == int[].class) {
                        m = cand;
                        break;
                    }
                }
                if (m != null) break;
                c = c.getSuperclass();
            }
            if (m == null) return false;
            m.setAccessible(true);
            m.invoke(cellLayout, cellX, cellY, out);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── Depth blur (no setIconBlur on ColorOS 14) ───────────────────────────

    private void hookFolderDragDepthBlur(ClassLoader cl) {
        try {
            Class<?> depth = Class.forName(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", false, cl);
            for (Method m : depth.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("setBlur") || name.equals("setBlurWithoutAnim"))) continue;
                if (m.getParameterCount() < 1 || m.getParameterTypes()[0] != float.class) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled()) {
                        Object[] args = chain.getArgs().toArray(new Object[0]);
                        if (args.length > 0 && args[0] instanceof Number) args[0] = 0f;
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
            }
            for (Method m : depth.getDeclaredMethods()) {
                if (!m.getName().equals("getFolderBlur") || m.getParameterCount() != 0
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (!enabled()) return chain.proceed();
                    if (folderOpenActive || folderDragActive || hasOpenFolder(null)) return 0f;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 DepthController blur hook unavailable", e);
        }

        // Depth blur writes are clamped above; static blur view follows setBlur(0).

        for (String stateClass : new String[] {
                "com.android.launcher3.states.ToggleBarState",
                "com.android.launcher3.states.PagePreviewState",
                "com.android.launcher3.uioverrides.states.OverviewState",
                "com.android.launcher3.uioverrides.states.BackgroundAppState",
                "com.android.launcher3.states.OplusSpringLoadedState"
        }) {
            try {
                Class<?> c = Class.forName(stateClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String name = m.getName();
                    if ((name.equals("getWallpaperBlur") || name.equals("getBlurUnchecked")
                            || name.equals("getDepthUnchecked"))
                            && m.getParameterCount() <= 1) {
                        hookOnce(m, chain -> enabled() ? 0f : chain.proceed());
                    } else if ((name.equals("getLauncherRootViewBgAlpha")
                            || name.equals("getCellLayoutBgAlpha"))
                            && m.getParameterCount() <= 1) {
                        hookOnce(m, chain -> enabled() ? 0 : chain.proceed());
                    }
                }
            } catch (Throwable ignored) { }
        }

        try {
            Class<?> helper = Class.forName(
                    "com.android.quickstep.touch.SwipeToRecentAnimationHelper", false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("createBlurAndDragLayerAlphaAnim")
                        || name.equals("doBackGroundAnim")
                        || name.equals("initState"))) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) return result;
                    Object self = chain.getThisObject();
                    setField(self, "wallpaperBlurEndValue", 0f);
                    forceDepthBlur(self, 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 SwipeToRecent blur clamp unavailable", e);
        }

        // Folder open/close drives gaussian via FolderExtImplV2 — keep depth at 0.
        try {
            Class<?> ext = Class.forName(
                    "com.android.launcher3.folder.FolderExtImplV2", false, cl);
            for (Method m : ext.getDeclaredMethods()) {
                if (!m.getName().equals("fadeGaussianBlurState") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (enabled()) forceDepthBlur(chain.getThisObject(), 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 fadeGaussianBlurState clamp unavailable", e);
        }
    }

    // ── Popup OEM blur suppress + ColorOS 14 desktop menu glass ─────────────

    private void hookFolderPopupBlur(ClassLoader cl) {
        final String className = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
        try {
            Class<?> c = Class.forName(className, false, cl);
            // Instance open paths. Never use method result as popup — populateAndShow
            // returns boolean on ColorOS 14 (unlike a View return).
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if ((name.equals("reorderAndShow")
                        || name.equals("onCreateOpenAnimation")
                        || name.equals("populateAndShow")
                        || name.equals("showEditPopupContainer"))
                        && m.getParameterCount() <= 6) {
                    hookOnce(m, chain -> {
                        Object popup = chain.getThisObject();
                        if (enabled()) keepPopupBlurTransparent(popup);
                        Object result = chain.proceed();
                        if (enabled()) keepPopupBlurTransparent(popup);
                        if (menuStyleEnabled()) applyDesktopPopupGlass(popup);
                        return result;
                    });
                }
            }
            // Static showForIcon — glass the returned popup View.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("showForIcon") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result != null && enabled()) {
                            keepPopupBlurTransparent(result);
                            if (menuStyleEnabled()) applyDesktopPopupGlass(result);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 showForIcon popup glass failed", e);
                    }
                    return result;
                });
            }
            // Close: reclaim glass if OEM rewrote white fills, then clean up.
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("getOpenCloseAnimatorWithoutPendingCard")
                        || name.equals("getOpenCloseAnimator")
                        || name.equals("animateClose"))) continue;
                if (name.startsWith("getOpenClose") && m.getParameterCount() < 1) continue;
                hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    boolean opening = name.startsWith("getOpenClose")
                            && m.getParameterCount() >= 1
                            && Boolean.TRUE.equals(chain.getArg(0));
                    boolean closing = name.equals("animateClose")
                            || (name.startsWith("getOpenClose")
                            && m.getParameterCount() >= 1
                            && Boolean.FALSE.equals(chain.getArg(0)));
                    Object result = chain.proceed();
                    try {
                        if (opening && result instanceof Animator && menuStyleEnabled()) {
                            final Object host = popup;
                            markDesktopPopupOpenAnimating(host, true);
                            ((Animator) result).addListener(new AnimatorListenerAdapter() {
                                private boolean canceled;

                                @Override
                                public void onAnimationCancel(Animator animation) {
                                    // Pre-drag end / animator recreate may cancel; keep pumping
                                    // so mid-release does not freeze the half-open backdrop.
                                    canceled = true;
                                    markDesktopPopupOpenAnimating(host, true);
                                }

                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if (canceled) {
                                        canceled = false;
                                        markDesktopPopupOpenAnimating(host, true);
                                        return;
                                    }
                                    markDesktopPopupOpenAnimating(host, false);
                                    captureDesktopPopupGlassAfterOpen(host);
                                    if (host instanceof View) {
                                        ((View) host).post(
                                                () -> captureDesktopPopupGlassAfterOpen(host));
                                    }
                                }
                            });
                        }
                        if (menuStyleEnabled() && closing) {
                            retainDesktopPopupGlassThroughClose(popup);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 " + className + "." + name + " popup glass failed", e);
                    }
                    return result;
                });
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("closeComplete") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        finishDesktopPopupGlass(popup);
                    } catch (Throwable e) {
                        log(5, "Os14 closeComplete popup glass cleanup failed", e);
                    }
                    return result;
                });
            }
            // OEM may restore opaque fills after measure/layout.
            after(cl, className, "onLayout", popup -> {
                if (enabled()) keepPopupBlurTransparent(popup);
                if (menuStyleEnabled()) reassertDesktopPopupGlass(popup);
            });
            after(cl, "com.android.launcher3.popup.PopupContainerWithArrow", "onLayout", popup -> {
                if (enabled()) keepPopupBlurTransparent(popup);
                if (menuStyleEnabled()) reassertDesktopPopupGlass(popup);
            });
        } catch (Throwable e) {
            log(5, "Os14 popup blur hook unavailable", e);
        }
        // animateOpen lives on ArrowPopup — filter to Oplus popup instances.
        try {
            Class<?> arrow = Class.forName(
                    "com.android.launcher3.popup.ArrowPopup", false, cl);
            for (Method m : arrow.getDeclaredMethods()) {
                if (!m.getName().equals("animateOpen") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        if (isClassOrSubclass(self,
                                "com.android.launcher3.popup.OplusPopupContainerWithArrow")) {
                            if (enabled()) keepPopupBlurTransparent(self);
                            if (menuStyleEnabled()) applyDesktopPopupGlass(self);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 ArrowPopup.animateOpen glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 ArrowPopup.animateOpen hook unavailable", e);
        }
        try {
            Class<?> blur = Class.forName("com.android.launcher3.popup.PopupBlurView", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (!m.getName().equals("createBlurAnim") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled() && Boolean.TRUE.equals(chain.getArg(0))) {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            ((View) self).setAlpha(0f);
                            ((View) self).setVisibility(View.INVISIBLE);
                        }
                        Object anim = chain.proceed();
                        if (anim instanceof android.animation.ObjectAnimator) {
                            android.animation.ObjectAnimator oa =
                                    (android.animation.ObjectAnimator) anim;
                            oa.setFloatValues(0f, 0f);
                            oa.setDuration(0L);
                        }
                        return anim;
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PopupBlurView.createBlurAnim unavailable", e);
        }
        // Offline wallpaper blur path used by ColorOS 14 popups.
        try {
            Class<?> helper = Class.forName(
                    "com.android.launcher3.popup.PopupBlurHelper", false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.equals("loadPopupBlurBg") || name.startsWith("blurBitmap")
                        || name.equals("loadBlurDragLayer"))) continue;
                hookOnce(m, chain -> {
                    if (enabled()) return null;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PopupBlurHelper suppress unavailable", e);
        }
    }

    private void keepPopupBlurTransparent(Object popup) {
        if (!enabled() || popup == null) return;
        setField(popup, "mAddPopupBlurView", false);
        Object blur = field(popup, "mPopBlurView");
        if (blur instanceof View) {
            ((View) blur).setAlpha(0f);
            ((View) blur).setVisibility(View.INVISIBLE);
        }
    }

    private static final String TAG_DESKTOP_POPUP = "colg_desktop_popup";

    /**
     * LiquidGlass on ColorOS 14 desktop long-press option panels.
     * ColorOS 14 has no {@code mAllPopupShortcutContainer}; glass mounts on
     * {@code mAppShortcutContainer} / {@code mSystemShortcutContainer} (and scanned panels).
     */
    private void applyDesktopPopupGlass(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            markDesktopPopupOpenAnimating(popup, true);
            applyDesktopPopupGlassNow(popup);
            if (popup instanceof View) {
                View root = (View) popup;
                root.post(() -> {
                    if (menuStyleEnabled() && root.isAttachedToWindow()) {
                        reassertDesktopPopupGlass(popup);
                    }
                });
                // Open scale anim ~400ms — reassert after settle in case OEM rewrote fills.
                root.postDelayed(() -> {
                    if (menuStyleEnabled() && root.isAttachedToWindow()) {
                        captureDesktopPopupGlassAfterOpen(popup);
                    }
                }, 420L);
            }
        } catch (Throwable e) {
            log(5, "Os14 applyDesktopPopupGlass failed", e);
        }
    }

    /** Light pass: strip restored row whites; reinstall only if OEM replaced glass. */
    private void reassertDesktopPopupGlass(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        clearDesktopPopupDividers((ViewGroup) popup);
        for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
            clearDeepShortcutRowFills(host);
            if (!(host.getBackground() instanceof GlassDrawable)) {
                installDesktopPopupPanelGlass(host);
            } else {
                // Do not forceCapture on every onLayout — wallpaper-only samples can erase
                // a good open-animation frame once the menu is fully opaque.
                host.invalidate();
            }
        }
    }

    private void retainDesktopPopupGlassThroughClose(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        clearDesktopPopupDividers((ViewGroup) popup);
        for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
            clearDeepShortcutRowFills(host);
            GlassDrawable glass = GlassInstaller.get(host);
            if (glass != null) {
                if (host.getBackground() != glass) host.setBackground(glass);
                host.setTag(TAG_DESKTOP_POPUP);
                host.invalidate();
            } else {
                installDesktopPopupPanelGlass(host);
            }
        }
    }

    private void finishDesktopPopupGlass(Object popup) {
        if (!(popup instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) popup;
        java.util.ArrayList<View> hosts = collectDesktopPopupPanelHosts(root);
        for (View host : hosts) {
            try {
                GlassInstaller.setOverlaySource(host, null);
                if (GlassInstaller.get(host) != null || host.getBackground() instanceof GlassDrawable) {
                    GlassInstaller.uninstall(host);
                }
                if (TAG_DESKTOP_POPUP.equals(host.getTag())) host.setTag(null);
            } catch (Throwable ignored) { }
        }
        uninstallNestedPopupGlass(root, new java.util.ArrayList<>());
    }

    /**
     * After open anim ends: restore chrome only. Do <b>not</b> {@code forceCapture} —
     * open-anim frames already have icons; settle forceCapture races OEM
     * {@code inflateOppositeView} then 花屏 (中途松手 → 完全打开后花屏).
     */
    private void captureDesktopPopupGlassAfterOpen(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            keepPopupBlurTransparent(popup);
            clearDesktopPopupDividers((ViewGroup) popup);
            for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
                clearDeepShortcutRowFills(host);
                if (!(host.getBackground() instanceof GlassDrawable)
                        && GlassInstaller.get(host) == null) {
                    installDesktopPopupPanelGlass(host);
                } else {
                    host.invalidate();
                }
            }
        } catch (Throwable e) {
            log(5, "Os14 captureDesktopPopupGlassAfterOpen failed", e);
        }
    }

    private void markDesktopPopupOpenAnimating(Object popup, boolean animating) {
        if (!(popup instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) popup;
        java.util.HashSet<View> hosts = new java.util.HashSet<>(collectDesktopPopupPanelHosts(root));
        collectTaggedDesktopPopupHosts(root, hosts);
        for (View host : hosts) {
            try {
                GlassInstaller.setDesktopPopupOpenAnimating(host, animating);
            } catch (Throwable ignored) { }
        }
    }

    private static void collectTaggedDesktopPopupHosts(View view, java.util.HashSet<View> out) {
        if (view == null || out == null) return;
        if (TAG_DESKTOP_POPUP.equals(view.getTag())) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTaggedDesktopPopupHosts(g.getChildAt(i), out);
            }
        }
    }

    private void applyDesktopPopupGlassNow(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        ViewGroup root = (ViewGroup) popup;
        clearDesktopPopupDividers(root);
        java.util.ArrayList<View> hosts = collectDesktopPopupPanelHosts(root);
        uninstallNestedPopupGlass(root, hosts);
        log(4, "Os14 desktop popup glass hosts=" + hosts.size()
                + " on " + root.getClass().getSimpleName());
        for (View host : hosts) {
            installDesktopPopupPanelGlass(host);
        }
    }

    private static java.util.ArrayList<View> collectDesktopPopupPanelHosts(ViewGroup root) {
        java.util.ArrayList<View> hosts = new java.util.ArrayList<>();
        Object popup = root;
        // ColorOS 16 all-container is absent on 14 — glass each shortcut panel separately.
        addPopupPanelHost(hosts, field(popup, "mAppShortcutContainer"), root);
        addPopupPanelHost(hosts, field(popup, "mSystemShortcutContainer"), root);
        addPopupPanelHost(hosts, field(popup, "mDeepShortcutContainer"), root);
        addPopupPanelHost(hosts, field(popup, "mNotificationContainer"), root);
        collectLikelyPopupPanels(root, root, hosts);
        // populateAndShow may leave mSystemShortcutContainer == this when only app shortcuts
        // exist; if no child panel was found, glass the root itself when it has an OEM plate.
        if (hosts.isEmpty() && isLikelyPopupShortcutPanel(root)) {
            hosts.add(root);
        }
        return hosts;
    }

    private static void collectLikelyPopupPanels(
            ViewGroup root, ViewGroup group, java.util.ArrayList<View> hosts) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (hosts.contains(child) || isUnderAnyHost(child, hosts)) {
                if (child instanceof ViewGroup && !hosts.contains(child)) {
                    collectLikelyPopupPanels(root, (ViewGroup) child, hosts);
                }
                continue;
            }
            if (isLikelyPopupShortcutPanel(child)) {
                hosts.add(child);
                continue;
            }
            if (child instanceof ViewGroup) {
                collectLikelyPopupPanels(root, (ViewGroup) child, hosts);
            }
        }
    }

    private static boolean isUnderAnyHost(View child, java.util.ArrayList<View> hosts) {
        for (View host : hosts) {
            if (host instanceof ViewGroup && isDescendantOf(child, (ViewGroup) host)) return true;
        }
        return false;
    }

    private static boolean isDescendantOf(View child, ViewGroup ancestor) {
        for (Object p = child.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static void addPopupPanelHost(
            java.util.ArrayList<View> hosts, Object candidate, ViewGroup popupRoot) {
        if (!(candidate instanceof View)) return;
        View view = (View) candidate;
        // ColorOS 14 temporarily assigns mSystemShortcutContainer = this before inflate.
        if (view == popupRoot) return;
        if (view.getVisibility() != View.VISIBLE) return;
        if (!hosts.contains(view)) hosts.add(view);
    }

    private static boolean isLikelyPopupShortcutPanel(View view) {
        if (!(view instanceof android.widget.LinearLayout)) return false;
        String name = view.getClass().getName();
        if (name.contains("PendingCard") || name.contains("Arrow") || name.contains("Space")
                || name.contains("BubbleText") || name.contains("DeepShortcut")) {
            return false;
        }
        Drawable bg = view.getBackground();
        if (bg != null && !(bg instanceof GlassDrawable) && isOpaquePanelFill(bg)) return true;
        return GlassInstaller.get(view) != null || TAG_DESKTOP_POPUP.equals(view.getTag());
    }

    private static void uninstallNestedPopupGlass(ViewGroup root, java.util.ArrayList<View> keep) {
        uninstallNestedPopupGlassRecurse(root, keep);
    }

    private static void uninstallNestedPopupGlassRecurse(
            ViewGroup group, java.util.ArrayList<View> keep) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (!keep.contains(child) && (child.getBackground() instanceof GlassDrawable
                    || GlassInstaller.get(child) != null
                    || TAG_DESKTOP_POPUP.equals(child.getTag()))) {
                try {
                    GlassInstaller.setOverlaySource(child, null);
                    GlassInstaller.uninstall(child);
                    if (TAG_DESKTOP_POPUP.equals(child.getTag())) child.setTag(null);
                } catch (Throwable ignored) { }
            }
            if (child instanceof ViewGroup) {
                uninstallNestedPopupGlassRecurse((ViewGroup) child, keep);
            }
        }
    }

    private void installDesktopPopupPanelGlass(View host) {
        if (host == null || !menuStyleEnabled()) return;
        if (host.getAlpha() < 1f) host.setAlpha(1f);
        clearDeepShortcutRowFills(host);
        Runnable install = () -> {
            if (!menuStyleEnabled() || !host.isAttachedToWindow()) return;
            clearDeepShortcutRowFills(host);
            float[] radii = readPopupPanelRadii(host);
            if (radii == null && host.getBackground() instanceof GlassDrawable) {
                try {
                    radii = ((GlassDrawable) host.getBackground()).getCornerRadii();
                } catch (Throwable ignored) { }
            }
            suppressPopupPanelOemLayer(host);
            host.setTag(TAG_DESKTOP_POPUP);
            View workspace = findWorkspaceNear(host);
            if (workspace != null) {
                GlassInstaller.setOverlaySource(host, workspace);
            }
            GlassInstaller.installBackground(host, currentConfig());
            GlassDrawable glass = GlassInstaller.get(host);
            if (glass == null) return;
            if (radii != null) {
                glass.setCornerRadii(radii);
            } else {
                float r = resolvePopupCornerFallback(host);
                glass.setCornerRadii(r, r, r, r);
            }
            if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
            GlassInstaller.setDesktopPopupOpenAnimating(host, true);
            GlassInstaller.forceCapture(host);
            host.invalidate();
        };
        if (host.getWidth() > 0 && host.getHeight() > 0) {
            install.run();
            host.post(install);
        } else {
            host.post(install);
        }
    }

    /** ColorOS 14 dimen {@code shortcut_background_radius} = 12dp. */
    private static float resolvePopupCornerFallback(View host) {
        try {
            int id = host.getResources().getIdentifier(
                    "shortcut_background_radius", "dimen", host.getContext().getPackageName());
            if (id != 0) {
                float dim = host.getResources().getDimension(id);
                if (dim > 0f) return dim;
            }
        } catch (Throwable ignored) { }
        return 12f * host.getResources().getDisplayMetrics().density;
    }

    private static View findWorkspaceNear(View from) {
        for (View current = from; current != null; ) {
            if (isClassOrSubclass(current, "com.android.launcher3.Workspace")
                    || isClassOrSubclass(current, "com.android.launcher3.OplusWorkspace")) {
                return current;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        try {
            Object launcher = resolveLauncher(from);
            if (launcher == null) {
                for (View current = from; current != null; ) {
                    Object ctx = unwrapContext(current.getContext());
                    if (ctx != null && isClassOrSubclass(ctx, "com.android.launcher3.Launcher")) {
                        launcher = ctx;
                        break;
                    }
                    Object parent = current.getParent();
                    current = parent instanceof View ? (View) parent : null;
                }
            }
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            return ws instanceof View ? (View) ws : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object unwrapContext(Object ctx) {
        Object cur = ctx;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (isClassOrSubclass(cur, "com.android.launcher3.Launcher")) return cur;
            if (cur instanceof android.content.ContextWrapper) {
                cur = ((android.content.ContextWrapper) cur).getBaseContext();
            } else {
                break;
            }
        }
        return ctx;
    }

    private static void clearDeepShortcutRowFills(View host) {
        if (!(host instanceof ViewGroup)) return;
        clearDesktopPopupDividers((ViewGroup) host);
        ViewGroup group = (ViewGroup) host;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (isDeepShortcutRow(child) || child instanceof android.widget.ScrollView
                    || popupClassContains(child, "PopupShortcutScroll")) {
                Drawable bg = child.getBackground();
                if (bg != null && !(bg instanceof GlassDrawable)) {
                    child.setBackground(null);
                }
            } else if (child instanceof ViewGroup
                    && !(child instanceof ImageView)
                    && isOpaquePanelFill(child.getBackground())) {
                child.setBackground(null);
            }
            if (child instanceof ViewGroup) clearDeepShortcutRowFills(child);
        }
    }

    private static void clearDesktopPopupDividers(ViewGroup root) {
        if (root == null) return;
        float density = root.getResources().getDisplayMetrics().density;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == null) continue;
            if (child instanceof android.widget.Space) {
                child.setVisibility(View.GONE);
                continue;
            }
            String idName = null;
            try {
                int id = child.getId();
                if (id != View.NO_ID) {
                    idName = child.getResources().getResourceEntryName(id);
                }
            } catch (Throwable ignored) { }
            boolean namedDivide = idName != null && (idName.contains("divider")
                    || idName.contains("divide") || idName.contains("separator"));
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            int lpH = lp != null ? lp.height : 0;
            int measuredH = child.getHeight();
            boolean thinStrip = child instanceof ImageView
                    && ((ImageView) child).getDrawable() == null
                    && ((measuredH > 0 && measuredH <= 8f * density)
                    || (lpH > 0 && lpH <= 8f * density));
            String className = child.getClass().getName();
            boolean divideLayout = className.contains("divide") || className.contains("Divide");
            if (namedDivide || thinStrip || divideLayout) {
                child.setVisibility(View.GONE);
            }
            if (child instanceof ViewGroup) clearDesktopPopupDividers((ViewGroup) child);
        }
    }

    private static boolean popupClassContains(View view, String token) {
        String name = view.getClass().getName();
        return name != null && name.contains(token);
    }

    private static boolean isOpaquePanelFill(Drawable bg) {
        if (bg == null || bg instanceof GlassDrawable) return false;
        String name = bg.getClass().getName();
        if (name.contains("LayerBlur") || name.contains("BlurDrawable")) return true;
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
            return Color.alpha(c) > 200;
        }
        return bg instanceof android.graphics.drawable.GradientDrawable
                || bg instanceof android.graphics.drawable.RippleDrawable
                || name.contains("Smooth") || name.contains("RoundRect")
                || name.contains("ShapeDrawable");
    }

    private static boolean isDeepShortcutRow(View view) {
        String name = view.getClass().getName();
        return name.contains("DeepShortcutView") || name.contains("DeepShortcut");
    }

    private static void suppressPopupPanelOemLayer(View host) {
        Drawable bg = host.getBackground();
        if (bg == null || bg instanceof GlassDrawable) return;
        // Always drop OEM plate so SmoothRect / LayerDrawable whites cannot sit under glass.
        host.setBackground(null);
    }

    private static float[] readPopupPanelRadii(View host) {
        Drawable bg = host.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            try {
                float[] corners = ((android.graphics.drawable.GradientDrawable) bg).getCornerRadii();
                if (corners != null && corners.length >= 8) {
                    return new float[] {
                            Math.max(corners[0], corners[1]),
                            Math.max(corners[2], corners[3]),
                            Math.max(corners[4], corners[5]),
                            Math.max(corners[6], corners[7])
                    };
                }
                float single = ((android.graphics.drawable.GradientDrawable) bg).getCornerRadius();
                if (single > 0f) {
                    return new float[] { single, single, single, single };
                }
            } catch (Throwable ignored) { }
        }
        if (bg instanceof android.graphics.drawable.RippleDrawable) {
            try {
                android.graphics.drawable.RippleDrawable ripple =
                        (android.graphics.drawable.RippleDrawable) bg;
                if (ripple.getNumberOfLayers() > 0) {
                    Drawable layer = ripple.getDrawable(0);
                    if (layer instanceof android.graphics.drawable.GradientDrawable) {
                        float[] corners = ((android.graphics.drawable.GradientDrawable) layer)
                                .getCornerRadii();
                        if (corners != null && corners.length >= 8) {
                            return new float[] {
                                    Math.max(corners[0], corners[1]),
                                    Math.max(corners[2], corners[3]),
                                    Math.max(corners[4], corners[5]),
                                    Math.max(corners[6], corners[7])
                            };
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    // ── Recents clear / toggle bar / IPC (shared glass installers) ──────────

    private void hookRecentsClearButton(ClassLoader cl) {
        after(cl, "com.oplus.quickstep.views.OplusClearAllPanelView", "onFinishInflate",
                this::applyClearButtonGlass);
        after(cl, "com.oplus.quickstep.views.OplusClearAllPanelView", "onAttachedToWindow",
                this::applyClearButtonGlass);
        try {
            Class<?> btn = Class.forName("com.android.launcher.views.PressFeedbackButton", false, cl);
            for (Method m : btn.getDeclaredMethods()) {
                if (!m.getName().equals("setSrcDrawable") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        View view = (View) chain.getThisObject();
                        if (enabled() && GlassInstaller.get(view) != null) {
                            setField(view, "mSrcDrawable", null);
                            view.invalidate();
                        }
                    } catch (Throwable ignored) { }
                    return result;
                });
            }
            for (Method m : btn.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    if (!(self instanceof View) || !enabled() || GlassInstaller.get((View) self) == null) {
                        return chain.proceed();
                    }
                    Object handler = field(self, "mPressFeedbackHandler");
                    Object savedAlpha = handler != null ? field(handler, "mCurrentColorAlpha") : null;
                    if (handler != null) setField(handler, "mCurrentColorAlpha", 0);
                    setField(self, "mSrcDrawable", null);
                    try {
                        return chain.proceed();
                    } finally {
                        if (handler != null && savedAlpha != null) {
                            setField(handler, "mCurrentColorAlpha", savedAlpha);
                        }
                    }
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PressFeedbackButton hooks unavailable", e);
        }
    }

    private void applyClearButtonGlass(Object panel) {
        if (!enabled() || panel == null) return;
        Object btn = field(panel, "mClearAllBtn");
        if (btn == null && panel instanceof ViewGroup) {
            btn = findPressFeedbackButton((View) panel);
        }
        if (!(btn instanceof View)) return;
        View button = (View) btn;
        try {
            setField(button, "mSrcDrawable", null);
            GlassInstaller.installBackground(button, currentConfig());
            GlassDrawable glass = GlassInstaller.get(button);
            if (glass != null) {
                float density = button.getResources().getDisplayMetrics().density;
                float r = 24f * density;
                glass.setCornerRadii(r, r, r, r);
            }
            button.invalidate();
        } catch (Throwable e) {
            log(5, "Os14 applyClearButtonGlass failed", e);
        }
    }

    private static View findPressFeedbackButton(View root) {
        if (root == null) return null;
        if (root.getClass().getName().equals("com.android.launcher.views.PressFeedbackButton")) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findPressFeedbackButton(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void hookToggleBarChrome(ClassLoader cl) {
        after(cl, "com.android.launcher.togglebar.views.ToggleStateToolbar", "onFinishInflate",
                this::applyToggleBarToolbarGlass);
        after(cl, "com.android.launcher.togglebar.views.ToggleStateToolbar", "onAttachedToWindow",
                this::applyToggleBarToolbarGlass);
        after(cl, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout", "onFinishInflate",
                this::applyToggleBarItemGlass);
        after(cl, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout", "onAttachedToWindow",
                this::applyToggleBarItemGlass);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarMainUIAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyToggleBarItemGlass(field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        log(5, "Os14 ToggleBarMainUIAdapter glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 ToggleBarMainUIAdapter unavailable", e);
        }
        try {
            Class<?> handler = Class.forName(
                    "com.android.launcher.togglebar.animation.PressFeedbackHandler", false, cl);
            for (Method m : handler.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("drawCircleColor") || name.equals("drawPressedColor"))
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled()) {
                        Object view = field(chain.getThisObject(), "mView");
                        if (view instanceof View && GlassInstaller.get((View) view) != null) {
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PressFeedbackHandler fill skip unavailable", e);
        }
    }

    /**
     * ColorOS 14 「布局」/「翻页」二级预览卡：OEM 用 {@code PressFeedbackPreviewWrapper}
     * 画实心磨砂底。装玻璃并关掉 fill，保留选中描边。
     */
    private void hookToggleBarSecondLevelPreviewGlass(ClassLoader cl) {
        after(cl, "com.android.launcher.togglebar.views.ToggleBarLayoutItemPreview", "onLayout",
                this::applyToggleBarPreviewItemGlass);
        after(cl, "com.android.launcher.togglebar.views.ToggleBarLayoutItemPreview", "onAttachedToWindow",
                this::applyToggleBarPreviewItemGlass);
        after(cl, "com.android.launcher.togglebar.views.ToggleBarEffectItemContainer", "onLayout",
                this::applyToggleBarPreviewItemGlass);
        after(cl, "com.android.launcher.togglebar.views.ToggleBarEffectItemContainer", "onAttachedToWindow",
                this::applyToggleBarPreviewItemGlass);
        after(cl, "com.android.launcher.togglebar.views.TwoPanelPagepreviewItemContainer", "onLayout",
                this::applyToggleBarPreviewItemGlass);
        after(cl, "com.android.launcher.togglebar.views.TwoPanelPagepreviewItemContainer", "onAttachedToWindow",
                this::applyToggleBarPreviewItemGlass);

        try {
            Class<?> layoutAdapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarLayoutAdapter", false, cl);
            for (Method m : layoutAdapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyToggleBarPreviewItemGlass(field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        log(5, "Os14 ToggleBarLayoutAdapter glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 ToggleBarLayoutAdapter unavailable", e);
        }
        try {
            Class<?> effectAdapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarEffectAdapter", false, cl);
            for (Method m : effectAdapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyToggleBarPreviewItemGlass(field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        log(5, "Os14 ToggleBarEffectAdapter glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 ToggleBarEffectAdapter unavailable", e);
        }

        try {
            Class<?> wrapper = Class.forName(
                    "com.android.launcher.togglebar.PressFeedbackPreviewWrapper", false, cl);
            for (Method m : wrapper.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled()) {
                        Object view = field(chain.getThisObject(), "mView");
                        if (view instanceof View && isToggleBarSecondLevelPreviewHost(view)) {
                            setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            invoke(chain.getThisObject(), "setIsNeedDrawPressColor",
                                    new Class<?>[] { boolean.class }, false);
                            applyToggleBarPreviewItemGlass(view);
                        } else if (view instanceof View
                                && isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)) {
                            setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            invoke(chain.getThisObject(), "setIsNeedDrawPressColor",
                                    new Class<?>[] { boolean.class }, false);
                            applyPagePreviewItemGlass(view);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PressFeedbackPreviewWrapper second-level glass unavailable", e);
        }
    }

    /**
     * ColorOS 14 二级「应用」按钮挂在 {@code toggle_bar_root} 内容里，不在 Toolbar 字段。
     * 用 initLayoutAnimation / setDrawableColor / onAttached 多路径兜底。
     */
    private void hookToggleBarApplyButtonGlass(ClassLoader cl) {
        after(cl, "com.android.launcher.togglebar.controller.ToggleBarLayoutUIController", "createView",
                this::applySecondLevelApplyButtonGlass);
        after(cl, "com.android.launcher.togglebar.controller.ToggleBarEffectUIController", "createView",
                this::applySecondLevelApplyButtonGlass);
        after(cl, "com.android.launcher.togglebar.controller.ToggleBarLayoutUIController", "resume",
                this::applySecondLevelApplyButtonGlass);
        after(cl, "com.android.launcher.togglebar.controller.ToggleBarEffectUIController", "resume",
                this::applySecondLevelApplyButtonGlass);

        try {
            Class<?> rv = Class.forName(
                    "com.android.launcher.togglebar.views.ToggleBarRecyclerView", false, cl);
            for (Method m : rv.getDeclaredMethods()) {
                if (!m.getName().equals("initLayoutAnimation") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled() && chain.getArgs().size() >= 1) {
                            Object btn = chain.getArg(0);
                            if (isApplyChangeButton(btn) || isPressFeedbackButton(btn)) {
                                // Layout/effect pass apply_change; page-preview may pass btn container.
                                if (btn instanceof ViewGroup) {
                                    applyPressFeedbackButtonsUnder((View) btn);
                                } else {
                                    applyPressFeedbackGlass(btn);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 ToggleBarRecyclerView.initLayoutAnimation glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 ToggleBarRecyclerView.initLayoutAnimation unavailable", e);
        }

        try {
            Class<?> btn = Class.forName("com.android.launcher.views.PressFeedbackButton", false, cl);
            for (Method m : btn.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("setDrawableColor") || name.equals("onAttachedToWindow")
                        || name.equals("onLayout") || name.equals("setEnabled"))) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (enabled() && isApplyChangeButton(self)) {
                            applyPressFeedbackGlass(self);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 PressFeedbackButton apply glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PressFeedbackButton apply hooks unavailable", e);
        }
    }

    private void applySecondLevelApplyButtonGlass(Object ctrl) {
        if (!enabled() || ctrl == null) return;
        try {
            View apply = findViewByIdSafe(ctrl, "apply_change");
            if (apply == null && ctrl instanceof View) {
                apply = findApplyChangeUnder((View) ctrl);
            }
            if (apply == null) {
                Object root = field(ctrl, "mRootContainerView");
                if (root instanceof View) apply = findApplyChangeUnder((View) root);
            }
            if (apply == null) {
                Object content = invokeNoArgs(ctrl, "getContentView");
                if (content instanceof View) apply = findApplyChangeUnder((View) content);
            }
            if (apply != null) applyPressFeedbackGlass(apply);
        } catch (Throwable e) {
            log(5, "Os14 applySecondLevelApplyButtonGlass failed", e);
        }
    }

    private static View findApplyChangeUnder(View root) {
        if (root == null) return null;
        if (isApplyChangeButton(root)) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View found = findApplyChangeUnder(g.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isApplyChangeButton(Object view) {
        if (!(view instanceof View)) return false;
        if (!isPressFeedbackButton(view)) return false;
        try {
            int id = ((View) view).getId();
            if (id == View.NO_ID) return false;
            String name = ((View) view).getResources().getResourceEntryName(id);
            return "apply_change".equals(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPressFeedbackButton(Object view) {
        return isClassOrSubclass(view, "com.android.launcher.views.PressFeedbackButton")
                || (view != null && view.getClass().getSimpleName().contains("PressFeedback"));
    }

    /**
     * 选中桌面图标后底部页预览缩略图（{@code PagePreviewItemView}）。
     * ColorOS 16 的同名逻辑在 {@code ModuleMain}；此处仅服务 Cos14。
     */
    private void hookPagePreviewItemGlass(ClassLoader cl) {
        after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateEnabled",
                this::applyPagePreviewRootGlass);
        after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateTransitionEnd",
                this::applyPagePreviewRootGlass);
        after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateDisabled",
                this::removePagePreviewRootGlass);
        after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateDisableTransitionEnd",
                this::removePagePreviewRootGlass);
        after(cl, "com.android.launcher.pagepreview.PagePreviewListContainer", "onPagePreviewStateEnable",
                this::applyPagePreviewListGlass);
        after(cl, "com.android.launcher.pagepreview.PagePreviewListContainer", "onPagePreviewStateDisable",
                this::removePagePreviewListGlass);

        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled() || !isPagePreviewChromeActive(chain.getThisObject())) {
                            return result;
                        }
                        Object holder = chain.getArg(0);
                        applyPagePreviewItemGlass(field(holder, "itemView"));
                        applyPagePreviewItemGlass(field(holder, "mTwoPanelView1"));
                        applyPagePreviewItemGlass(field(holder, "mTwoPanelView2"));
                        applyPagePreviewItemGlass(invokeNoArgs(holder, "getMTwoPanelView1"));
                        applyPagePreviewItemGlass(invokeNoArgs(holder, "getMTwoPanelView2"));
                    } catch (Throwable e) {
                        log(5, "Os14 PagePreviewAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PagePreviewAdapter glass unavailable", e);
        }

        try {
            Class<?> preview = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewItemView", false, cl);
            for (Method m : preview.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("onLayout") || name.equals("onAttachedToWindow")
                        || name.equals("onDragExit"))) continue;
                final String hooked = name;
                hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        if ("onDragExit".equals(hooked) && self instanceof View) {
                            GlassInstaller.uninstall((View) self);
                        }
                        if (enabled() && isPagePreviewChromeActive(self)) {
                            applyPagePreviewItemGlass(self);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 PagePreviewItemView." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PagePreviewItemView glass unavailable", e);
        }

        // Page-preview action buttons (移除 / 建文件夹) also use PressFeedbackButton.
        after(cl, "com.android.launcher.pagepreview.PagePreviewButtonContainer", "onFinishInflate",
                container -> {
                    if (!enabled()) return;
                    applyPressFeedbackGlass(field(container, "mRemoveBtn"));
                    applyPressFeedbackGlass(field(container, "mFolderFormBtn"));
                    if (container instanceof View) applyPressFeedbackButtonsUnder((View) container);
                });
        after(cl, "com.android.launcher.pagepreview.PagePreviewButtonContainer",
                "onWallpaperBrightnessChanged",
                container -> {
                    if (!enabled()) return;
                    applyPressFeedbackGlass(field(container, "mRemoveBtn"));
                    applyPressFeedbackGlass(field(container, "mFolderFormBtn"));
                });
    }

    private boolean isPagePreviewChromeActive(Object host) {
        Object launcher = resolveLauncher(host);
        if (launcher == null) launcher = findActiveLauncher();
        if (launcher == null) return false;
        return isInLauncherState(launcher, "PAGE_PREVIEW")
                || isInLauncherState(launcher, "TOGGLE_BAR");
    }

    private void applyPagePreviewItemGlass(Object item) {
        if (!enabled() || !(item instanceof View)) return;
        if (!isClassOrSubclass(item, "com.android.launcher.pagepreview.PagePreviewItemView")) return;
        if (!isPagePreviewChromeActive(item)) return;
        final View preview = (View) item;
        try {
            Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                setField(wrapper, "mIsNeedDrawPressColor", false);
                invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, false);
            }
            GlassDrawable existing = GlassInstaller.get(preview);
            boolean missing = existing == null || preview.getBackground() != existing;
            GlassInstaller.installBackground(preview, currentConfig());
            GlassDrawable live = GlassInstaller.get(preview);
            if (live == null) return;
            float radius = readToggleBarPreviewRadius(preview);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(preview);
                if (g == null) return;
                float r = readToggleBarPreviewRadius(preview);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(preview);
                preview.invalidate();
            };
            if (preview.getWidth() > 0 && preview.getHeight() > 0) refresh.run();
            else preview.post(refresh);
        } catch (Throwable e) {
            log(5, "Os14 applyPagePreviewItemGlass failed", e);
        }
    }

    private void hardRemovePagePreviewItemGlass(Object item) {
        if (!(item instanceof View)) return;
        if (!isClassOrSubclass(item, "com.android.launcher.pagepreview.PagePreviewItemView")) return;
        View preview = (View) item;
        try {
            Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                setField(wrapper, "mIsNeedDrawPressColor", true);
                invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, true);
            }
            GlassInstaller.uninstall(preview);
        } catch (Throwable e) {
            log(5, "Os14 removePagePreviewItemGlass failed", e);
        }
    }

    private void applyPagePreviewRootGlass(Object root) {
        if (!enabled() || !(root instanceof View)) return;
        applyPagePreviewItemsUnder((View) root);
    }

    private void removePagePreviewRootGlass(Object root) {
        if (!(root instanceof View)) return;
        hardRemovePagePreviewItemsUnder((View) root);
    }

    private void applyPagePreviewListGlass(Object list) {
        if (!enabled() || !(list instanceof View)) return;
        applyPagePreviewItemsUnder((View) list);
    }

    private void removePagePreviewListGlass(Object list) {
        if (!(list instanceof View)) return;
        hardRemovePagePreviewItemsUnder((View) list);
    }

    private void applyPagePreviewItemsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.pagepreview.PagePreviewItemView")) {
            applyPagePreviewItemGlass(root);
            return;
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyPagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private void hardRemovePagePreviewItemsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.pagepreview.PagePreviewItemView")) {
            hardRemovePagePreviewItemGlass(root);
            return;
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            hardRemovePagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private static boolean isToggleBarSecondLevelPreviewHost(Object view) {
        return isClassOrSubclass(view, "com.android.launcher.togglebar.views.ToggleBarLayoutItemPreview")
                || isClassOrSubclass(view, "com.android.launcher.togglebar.views.ToggleBarEffectItemContainer")
                || isClassOrSubclass(view, "com.android.launcher.togglebar.views.TwoPanelPagepreviewItemContainer");
    }

    private void applyToggleBarPreviewItemGlass(Object item) {
        if (!enabled() || !(item instanceof View)) return;
        if (!isToggleBarSecondLevelPreviewHost(item)) return;
        final View preview = (View) item;
        try {
            Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                setField(wrapper, "mIsNeedDrawPressColor", false);
                invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, false);
            }
            GlassDrawable existing = GlassInstaller.get(preview);
            boolean missing = existing == null || preview.getBackground() != existing;
            GlassInstaller.installBackground(preview, currentConfig());
            GlassDrawable live = GlassInstaller.get(preview);
            if (live == null) return;
            float radius = readToggleBarPreviewRadius(preview);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(preview);
                if (g == null) return;
                float r = readToggleBarPreviewRadius(preview);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(preview);
                preview.invalidate();
            };
            if (preview.getWidth() > 0 && preview.getHeight() > 0) refresh.run();
            else preview.post(refresh);
        } catch (Throwable e) {
            log(5, "Os14 applyToggleBarPreviewItemGlass failed", e);
        }
    }

    private static float readToggleBarPreviewRadius(View preview) {
        Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
        Object radius = invokeNoArgs(wrapper, "getMRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object viaField = field(wrapper, "mRadius");
        if (viaField instanceof Number && ((Number) viaField).floatValue() > 0f) {
            return ((Number) viaField).floatValue();
        }
        return 12f * preview.getResources().getDisplayMetrics().density;
    }

    private static View findViewByIdSafe(Object host, String idName) {
        if (host == null || idName == null) return null;
        try {
            Object found = invoke(host, "findViewById", new Class<?>[] { int.class },
                    resolveLauncherViewId(host, idName));
            return found instanceof View ? (View) found : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int resolveLauncherViewId(Object host, String idName) {
        try {
            ClassLoader cl = host.getClass().getClassLoader();
            Class<?> r = Class.forName("com.android.launcher.R$id", false, cl);
            Object v = r.getField(idName).get(null);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) { }
        return 0;
    }

    /**
     * 长按桌面图标下方的卡片预览底板（{@code card_page_bg} / pending_card_img_bg）。
     */
    private void hookPendingCardPreviewGlass(ClassLoader cl) {
        after(cl, "com.android.launcher3.popup.pendingcard.PendingCardContainer", "onFinishInflate",
                this::applyPendingCardPreviewGlass);
        after(cl, "com.android.launcher3.popup.pendingcard.PendingCardContainer", "onAttachedToWindow",
                this::applyPendingCardPreviewGlass);
        after(cl, "com.android.launcher3.popup.pendingcard.PendingCardContainer", "onLayout",
                this::applyPendingCardPreviewGlass);
        try {
            Class<?> container = Class.forName(
                    "com.android.launcher3.popup.pendingcard.PendingCardContainer", false, cl);
            for (Method m : container.getDeclaredMethods()) {
                if (!m.getName().equals("initCardPager") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (menuStyleEnabled()) applyPendingCardPreviewGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, "Os14 PendingCardContainer.initCardPager glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PendingCardContainer.initCardPager unavailable", e);
        }
        // Reclaim if OEM rewrote the plate while the options popup is open.
        after(cl, "com.android.launcher3.popup.OplusPopupContainerWithArrow", "onLayout", popup -> {
            if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
            applyPendingCardPreviewGlassUnder((ViewGroup) popup);
        });
    }

    private void applyPendingCardPreviewGlassUnder(ViewGroup root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher3.popup.pendingcard.PendingCardContainer")) {
            applyPendingCardPreviewGlass(root);
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) applyPendingCardPreviewGlassUnder((ViewGroup) child);
        }
    }

    private void applyPendingCardPreviewGlass(Object container) {
        if (!menuStyleEnabled() || container == null) return;
        if (!isClassOrSubclass(container, "com.android.launcher3.popup.pendingcard.PendingCardContainer")) {
            return;
        }
        try {
            Object bg = field(container, "mPendingCardRVFourSpanXBg");
            if (!(bg instanceof View) && container instanceof View) {
                bg = ((View) container).findViewById(
                        resolveLauncherViewId(container, "card_page_bg"));
            }
            if (!(bg instanceof View)) return;
            final View plate = (View) bg;
            GlassInstaller.installBackground(plate, currentConfig());
            GlassDrawable live = GlassInstaller.get(plate);
            if (live == null) return;
            float radius = readPendingCardRadius(plate, container);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            Runnable refresh = () -> {
                if (!menuStyleEnabled() || !plate.isAttachedToWindow()) return;
                GlassDrawable g = GlassInstaller.get(plate);
                if (g == null) {
                    GlassInstaller.installBackground(plate, currentConfig());
                    g = GlassInstaller.get(plate);
                }
                if (g == null) return;
                float r = readPendingCardRadius(plate, container);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(plate);
                plate.invalidate();
            };
            if (plate.getWidth() > 0 && plate.getHeight() > 0) refresh.run();
            else plate.post(refresh);
            plate.postDelayed(refresh, 120L);
        } catch (Throwable e) {
            log(5, "Os14 applyPendingCardPreviewGlass failed", e);
        }
    }

    private static float readPendingCardRadius(View plate, Object container) {
        try {
            int px = plate.getResources().getDimensionPixelSize(
                    plate.getResources().getIdentifier(
                            "pending_card_radius", "dimen", "com.android.launcher"));
            if (px > 0) return px;
        } catch (Throwable ignored) { }
        Object via = field(container, "mRadius");
        if (via instanceof Number && ((Number) via).floatValue() > 0f) {
            return ((Number) via).floatValue();
        }
        return 24f * plate.getResources().getDisplayMetrics().density;
    }

    // ── Page indicator frosted pill (TOGGLE_BAR / press-drag) ───────────────
    // ColorOS 14 draws the pill via Paint in drawBackgroundIfNeeded — no mBlurBgView.

    private void hookPageIndicatorGlass(ClassLoader cl) {
        try {
            Class<?> anim = Class.forName(
                    "com.android.launcher.pageindicators.PageIndicatorAnimHelper", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("startPressDragging") || name.equals("cancelPressDragging")
                        || name.equals("startIndicatorBgAnim") || name.equals("reverseIndicatorBgAnim")
                        || name.equals("startPageNumChangeAnim") || name.equals("startIndicatorDotAnim")
                        || name.equals("setBgAlpha"))
                        || m.isBridge() || m.isSynthetic()) continue;
                final String hooked = name;
                hookOnce(m, chain -> {
                    Object helper = chain.getThisObject();
                    Object indicator = field(helper, "mPageIndicator");
                    try {
                        if (hooked.equals("startPressDragging")
                                || hooked.equals("startIndicatorBgAnim")
                                || (hooked.equals("setBgAlpha") && enabled()
                                && chain.getArgs().size() >= 1
                                && chain.getArg(0) instanceof Number
                                && ((Number) chain.getArg(0)).intValue() > 0)) {
                            applyPageIndicatorGlass(indicator);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 PageIndicatorAnimHelper." + hooked + " pre-glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        if (hooked.equals("cancelPressDragging")
                                || hooked.equals("reverseIndicatorBgAnim")) {
                            if (shouldShowPageIndicatorGlass(indicator)) {
                                applyPageIndicatorGlass(indicator);
                            } else {
                                removePageIndicatorGlass(indicator);
                            }
                        } else if (hooked.equals("startPageNumChangeAnim")
                                || hooked.equals("startIndicatorDotAnim")) {
                            applyPageIndicatorGlass(indicator);
                        } else if (hooked.equals("setBgAlpha") && enabled()
                                && chain.getArgs().size() >= 1
                                && chain.getArg(0) instanceof Number
                                && ((Number) chain.getArg(0)).intValue() <= 0
                                && !shouldShowPageIndicatorGlass(indicator)) {
                            removePageIndicatorGlass(indicator);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 PageIndicatorAnimHelper." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 PageIndicatorAnimHelper glass unavailable", e);
        }

        try {
            Class<?> indicator = Class.forName(
                    "com.android.launcher.pageindicators.OplusPageIndicator", false, cl);
            for (Method m : indicator.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("drawBackgroundIfNeeded") && m.getParameterCount() == 1) {
                    hookOnce(m, chain -> {
                        if (!enabled()) return chain.proceed();
                        Object self = chain.getThisObject();
                        try {
                            if (shouldShowPageIndicatorGlass(self)) {
                                applyPageIndicatorGlass(self);
                                // Skip OEM translucent Paint pill — glass background draws instead.
                                return null;
                            }
                            removePageIndicatorGlass(self);
                        } catch (Throwable e) {
                            log(5, "Os14 page indicator drawBackground glass failed", e);
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("onDraw") && m.getParameterCount() == 1) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (enabled() && shouldShowPageIndicatorGlass(chain.getThisObject())) {
                                applyPageIndicatorGlass(chain.getThisObject());
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                } else if (name.equals("setBgAlpha") && m.getParameterCount() == 1) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object arg0 = chain.getArg(0);
                            if (enabled() && arg0 instanceof Number
                                    && ((Number) arg0).intValue() > 0) {
                                applyPageIndicatorGlass(chain.getThisObject());
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, "Os14 OplusPageIndicator glass unavailable", e);
        }

        after(cl, "com.android.launcher.togglebar.views.ToggleStateToolbar", "onAttachedToWindow",
                toolbar -> applyPageIndicatorGlassFromLauncher(resolveLauncher(toolbar)));
        after(cl, "com.android.launcher3.Launcher", "onResume",
                this::applyPageIndicatorGlassFromLauncher);
        after(cl, "com.android.launcher.Launcher", "onResume",
                this::applyPageIndicatorGlassFromLauncher);
    }

    private void applyPageIndicatorGlassFromLauncher(Object launcher) {
        if (!enabled()) return;
        Object indicator = findPageIndicator(launcher);
        if (indicator != null && shouldShowPageIndicatorGlass(indicator)) {
            applyPageIndicatorGlass(indicator);
        }
    }

    private Object findPageIndicator(Object launcher) {
        Object ws = invokeNoArgs(launcher, "getWorkspace");
        Object indicator = invokeNoArgs(ws, "getPageIndicator");
        if (indicator == null) indicator = field(ws, "mPageIndicator");
        return indicator;
    }

    private boolean shouldShowPageIndicatorGlass(Object indicator) {
        if (!(indicator instanceof View)) return false;
        try {
            Object anim = field(indicator, "mAnimHelper");
            Object alpha = invokeNoArgs(anim, "getBackgroundAlpha");
            if (alpha instanceof Number && ((Number) alpha).intValue() > 0) return true;
        } catch (Throwable ignored) { }
        Object launcher = resolveLauncher(indicator);
        if (launcher == null) launcher = field(indicator, "mLauncher");
        return isInLauncherState(launcher, "TOGGLE_BAR")
                || isInLauncherState(launcher, "PAGE_PREVIEW");
    }

    private void applyPageIndicatorGlass(Object indicator) {
        if (!enabled() || !(indicator instanceof View)) return;
        View view = (View) indicator;
        try {
            GlassDrawable existing = GlassInstaller.get(view);
            if (existing == null || view.getBackground() != existing) {
                GlassInstaller.installBackground(view, currentConfig());
                existing = GlassInstaller.get(view);
            }
            if (existing == null) return;
            float radius = 24f * view.getResources().getDisplayMetrics().density;
            Object r = field(indicator, "mBackgroundRadius");
            if (r instanceof Number && ((Number) r).floatValue() > 0f) {
                radius = ((Number) r).floatValue();
            }
            existing.setCornerRadii(radius, radius, radius, radius);
            if (view.getWidth() > 0 && view.getHeight() > 0) {
                GlassInstaller.forceCapture(view);
            }
            view.invalidate();
        } catch (Throwable e) {
            log(5, "Os14 applyPageIndicatorGlass failed", e);
        }
    }

    private void removePageIndicatorGlass(Object indicator) {
        if (!(indicator instanceof View)) return;
        View view = (View) indicator;
        try {
            if (GlassInstaller.get(view) != null || view.getBackground() instanceof GlassDrawable) {
                GlassInstaller.uninstall(view);
                if (view.getBackground() instanceof GlassDrawable) view.setBackground(null);
                view.invalidate();
            }
        } catch (Throwable ignored) { }
    }

    private boolean isInLauncherState(Object launcher, String stateName) {
        if (launcher == null || stateName == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Object state = null;
            try {
                state = Class.forName("com.android.launcher3.LauncherState", false, cl)
                        .getField(stateName).get(null);
            } catch (NoSuchFieldException ignored) {
                // ColorOS 14 keeps TOGGLE_BAR / PAGE_PREVIEW on OplusBaseLauncherState.
                state = Class.forName("com.android.launcher3.states.OplusBaseLauncherState", false, cl)
                        .getField(stateName).get(null);
            }
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    return state != null && Boolean.TRUE.equals(m.invoke(launcher, state));
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void applyToggleBarToolbarGlass(Object toolbar) {
        if (!enabled() || toolbar == null) return;
        applyPressFeedbackGlass(field(toolbar, "mFinishBtn"));
        applyPressFeedbackGlass(field(toolbar, "mAddCardBtn"));
        applyPressFeedbackGlass(field(toolbar, "mDragCancelButton"));
        applyPressFeedbackGlass(field(toolbar, "applyBtn"));
        applyPressFeedbackGlass(field(toolbar, "finishBtn"));
        if (toolbar instanceof View) applyPressFeedbackButtonsUnder((View) toolbar);
    }

    private void applyToggleBarItemGlass(Object itemRoot) {
        if (!enabled() || !(itemRoot instanceof View)) return;
        if (!isClassOrSubclass(itemRoot, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout")) {
            return;
        }
        Object icon = field(itemRoot, "iconImageView");
        if (!(icon instanceof View)) {
            icon = findChildBySimpleName((View) itemRoot, "PressFeedbackCircleImageView");
        }
        if (!(icon instanceof View)) return;
        View circle = (View) icon;
        GlassInstaller.installBackground(circle, currentConfig());
        circle.post(() -> {
            GlassDrawable live = GlassInstaller.get(circle);
            if (live == null) return;
            int size = Math.min(circle.getWidth(), circle.getHeight());
            if (size <= 0) return;
            float radius = size / 2f;
            live.setCornerRadii(radius, radius, radius, radius);
            GlassInstaller.forceCapture(circle);
            circle.invalidate();
        });
    }

    private void applyPressFeedbackGlass(Object buttonObj) {
        if (!enabled() || !(buttonObj instanceof View)) return;
        if (!isClassOrSubclass(buttonObj, "com.android.launcher.views.PressFeedbackButton")
                && !buttonObj.getClass().getSimpleName().contains("PressFeedback")) {
            return;
        }
        View button = (View) buttonObj;
        try {
            setField(button, "mSrcDrawable", null);
            Object handler = field(button, "mPressFeedbackHandler");
            if (handler != null) {
                invoke(handler, "setDrawableColor", new Class<?>[] { int.class }, 0);
            }
            GlassInstaller.installBackground(button, currentConfig());
            GlassDrawable glass = GlassInstaller.get(button);
            if (glass != null) {
                float radius = readPressFeedbackRadius(button);
                if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
            }
            Runnable refresh = () -> {
                if (!enabled()) return;
                setField(button, "mSrcDrawable", null);
                GlassDrawable live = GlassInstaller.get(button);
                if (live == null) {
                    GlassInstaller.installBackground(button, currentConfig());
                    live = GlassInstaller.get(button);
                }
                if (live == null) return;
                float radius = readPressFeedbackRadius(button);
                if (radius <= 0f && button.getHeight() > 0) radius = button.getHeight() / 2f;
                if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
                GlassInstaller.forceCapture(button);
                button.invalidate();
            };
            if (button.getWidth() > 0 && button.getHeight() > 0) refresh.run();
            else button.post(refresh);
            button.postDelayed(refresh, 80L);
            button.postDelayed(refresh, 200L);
        } catch (Throwable e) {
            log(5, "Os14 applyPressFeedbackGlass failed", e);
        }
    }

    private static float readPressFeedbackRadius(View button) {
        Object radius = field(button, "mDrawableRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object via = invokeNoArgs(button, "getMDrawableRadius");
        if (via instanceof Number && ((Number) via).floatValue() > 0f) {
            return ((Number) via).floatValue();
        }
        return 0f;
    }

    private void applyPressFeedbackButtonsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.views.PressFeedbackButton")) {
            applyPressFeedbackGlass(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyPressFeedbackButtonsUnder(g.getChildAt(i));
            }
        }
    }

    private static View findChildBySimpleName(View root, String simpleName) {
        if (root == null || simpleName == null) return null;
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findChildBySimpleName(g.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void hookFolderVisibility(ClassLoader cl) {
        // Only hook OplusFolder.animateOpen — it calls super.animateOpen(); hooking both
        // Folder + OplusFolder would run beginFolderOpen twice mid-open.
        after(cl, "com.android.launcher3.folder.OplusFolder", "animateOpen", this::beginFolderOpen);
        after(cl, "com.android.launcher3.folder.Folder", "animateOpen", folder -> {
            // AOSP Folder used when OplusFolder is absent.
            if (!isClassOrSubclass(folder, "com.android.launcher3.folder.OplusFolder")) {
                beginFolderOpen(folder);
            }
        });
        // ColorOS 14 ends close with closeComplete(boolean), not onFolderCloseCompleted.
        after(cl, "com.android.launcher3.folder.Folder", "closeComplete", folder -> {
            endFolderOpen(folder);
            Object icon = field(folder, "mFolderIcon");
            if (icon != null) syncFolderIcon(icon);
        });
        // handleClose / animateClosed start the morph — keep depth at 0; glass applied via
        // OplusFolderAnimationManager hooks after OEM sets the morph drawable.
        after(cl, "com.android.launcher3.folder.Folder", "handleClose", folder -> {
            folderOpenActive = true;
            forceDepthBlur(folder, 0f);
        });
        after(cl, "com.android.launcher3.folder.Folder", "animateClosed", folder -> {
            folderOpenActive = true;
            forceDepthBlur(folder, 0f);
            applyFolderMorphGlass(folder);
        });
        after(cl, "com.android.launcher3.folder.Folder", "close", folder -> {
            endFolderOpen(folder);
            Object icon = field(folder, "mFolderIcon");
            if (icon != null) syncFolderIcon(icon);
        });
    }

    private void hookFolderRefreshEvents(ClassLoader cl) {
        after(cl, "com.android.launcher3.folder.FolderIcon", "onItemsChanged", this::syncFolderIcon);
        after(cl, "com.android.launcher3.folder.OplusFolderIcon", "onItemsChanged", this::syncFolderIcon);
        after(cl, "com.android.launcher3.folder.PreviewItemManager", "onItemsChanged", mgr -> {
            Object icon = field(mgr, "mIcon");
            if (icon != null) syncFolderIcon(icon);
        });
    }

    private void hookUnlockGlassRefresh(ClassLoader cl) {
        after(cl, "com.android.launcher3.Launcher", "onResume", ignored -> GlassInstaller.refreshAll());
        after(cl, "com.android.launcher.Launcher", "onResume", ignored -> GlassInstaller.refreshAll());
    }

    private void hookWallpaperScaleTracking(ClassLoader cl) {
        try {
            Class<?> wm = Class.forName("android.app.WallpaperManager", false, cl);
            for (Method m : wm.getDeclaredMethods()) {
                if (!m.getName().equals("sendWallpaperCommand") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof android.os.Bundle) {
                                WallpaperScaleTracker.onWallpaperCommand((android.os.Bundle) arg);
                                break;
                            }
                        }
                    } catch (Throwable ignored) { }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 wallpaper scale tracking unavailable", e);
        }
    }

    private void hookDesktopBackdropIpc(ClassLoader cl) {
        DesktopBackdropHub.start();
        after(cl, "com.android.launcher3.Launcher", "onResume", this::attachDesktopBackdropRoot);
        after(cl, "com.android.launcher.Launcher", "onResume", this::attachDesktopBackdropRoot);
        after(cl, "com.android.launcher3.Launcher", "onAttachedToWindow", this::attachDesktopBackdropRoot);
        after(cl, "com.android.launcher.Launcher", "onAttachedToWindow", this::attachDesktopBackdropRoot);
        after(cl, "com.android.launcher3.Workspace", "scrollTo",
                o -> DesktopBackdropSampler.invalidateAll());
        after(cl, "com.android.launcher3.OplusWorkspace", "scrollTo",
                o -> DesktopBackdropSampler.invalidateAll());
        after(cl, "com.android.launcher3.CellLayout", "onLayout",
                o -> DesktopBackdropSampler.invalidateCache());
    }

    private void attachDesktopBackdropRoot(Object launcher) {
        try {
            if (launcher instanceof android.app.Activity) {
                View decor = ((android.app.Activity) launcher).getWindow().getDecorView();
                DesktopBackdropSampler.setLauncherRoot(decor);
            } else {
                Object window = invokeNoArgs(launcher, "getWindow");
                Object decor = invokeNoArgs(window, "getDecorView");
                if (decor instanceof View) DesktopBackdropSampler.setLauncherRoot((View) decor);
            }
            DesktopBackdropSampler.invalidateCache();
        } catch (Throwable e) {
            log(5, "Os14 attachDesktopBackdropRoot failed", e);
        }
    }

    private void applyCouiPopupListGlass(Object popup) {
        if (!menuStyleEnabled() || popup == null) return;
        try {
            View wrapper = asView(field(popup, "mMainMenuWrapper"));
            if (wrapper == null) wrapper = asView(field(popup, "mContentView"));
            if (wrapper == null && popup instanceof View) wrapper = (View) popup;
            if (wrapper == null) return;
            GlassInstaller.installBackground(wrapper, currentConfig());
            GlassInstaller.forceCapture(wrapper);
        } catch (Throwable e) {
            log(5, "Os14 COUI popup glass failed", e);
        }
    }

    // ── Folder close morph plate ────────────────────────────────────────────

    private void hookFolderCloseMorphGlass(ClassLoader cl) {
        // Do NOT clear morph background on inflate — updateAnimColorFilter NPEs on null
        // getBackground() when the folder later opens.
        try {
            Class<?> anim = Class.forName(
                    "com.android.launcher3.folder.OplusFolderAnimationManager", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("getFolderBackgroundAnimator")
                        || name.equals("updateAnimColorFilter")
                        || name.equals("createFolderBackgroundAnimatorSet"))) continue;
                final String hooked = name;
                hookOnce(m, chain -> {
                    // Ensure morph ImageView has a non-null background before OEM color-filter.
                    if ("updateAnimColorFilter".equals(hooked) && chain.getArgs().size() >= 1) {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof ImageView) {
                            ImageView iv = (ImageView) arg0;
                            if (iv.getBackground() == null) {
                                iv.setBackground(new ColorDrawable(0x66FFFFFF));
                            }
                        }
                    }
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        Object self = chain.getThisObject();
                        Object folder = field(self, "mColorFolder");
                        if (folder == null) folder = field(self, "mFolder");
                        // Replace OEM translucent plate with glass; never leave background null.
                        applyFolderMorphGlass(folder);
                        forceDepthBlur(folder != null ? folder : self, 0f);
                    } catch (Throwable e) {
                        log(5, "Os14 folder morph glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 OplusFolderAnimationManager glass unavailable", e);
        }
    }

    private void suppressFolderMorphBackground(Object folder) {
        // Safe variant: never leave ImageView background null (OEM updateAnimColorFilter NPEs).
        if (!enabled() || folder == null) return;
        applyFolderMorphGlass(folder);
    }

    private void applyFolderMorphGlass(Object folder) {
        if (!enabled() || folder == null) return;
        try {
            Object animBg = field(folder, "mContentAnimationBackground");
            if (!(animBg instanceof ImageView)) return;
            ImageView iv = (ImageView) animBg;
            iv.setImageDrawable(null);
            GlassInstaller.installBackground(iv, currentConfig());
            GlassDrawable glass = GlassInstaller.get(iv);
            if (glass != null) {
                float density = iv.getResources().getDisplayMetrics().density;
                float radius = 24f * density;
                Object preview = null;
                Object icon = field(folder, "mFolderIcon");
                if (icon != null) preview = field(icon, "mBackground");
                Object r = field(preview, "mRadius");
                if (r instanceof Number && ((Number) r).floatValue() > 0f) {
                    radius = ((Number) r).floatValue();
                }
                glass.setCornerRadii(radius, radius, radius, radius);
            }
            if (iv.getWidth() > 0 && iv.getHeight() > 0) {
                GlassInstaller.forceCapture(iv);
            }
            iv.invalidate();
        } catch (Throwable e) {
            log(5, "Os14 applyFolderMorphGlass failed", e);
        }
    }

    // ── Long-press folder DragView glass ────────────────────────────────────

    private void hookFolderDragPreview(ClassLoader cl) {
        // ColorOS 14 folder drag uses DragPreviewProvider.createDrawable() (not contentView):
        // resetPressAnimStateForLongClick restores white mBgDrawable, then view.draw() bakes it
        // into a FastBitmapDrawable. Glass on ImageView background sits under that white layer.
        try {
            Class<?> folderIcon = Class.forName(
                    "com.android.launcher3.folder.OplusFolderIcon", false, cl);
            for (Method m : folderIcon.getDeclaredMethods()) {
                if (!m.getName().equals("resetPressAnimStateForLongClick")
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) {
                            Object preview = field(chain.getThisObject(), "mBackground");
                            suppressOemDrawable(preview);
                        }
                    } catch (Throwable e) {
                        log(5, "Os14 resetPressAnimStateForLongClick suppress failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 resetPressAnimStateForLongClick hook unavailable", e);
        }

        try {
            Class<?> provider = Class.forName(
                    "com.android.launcher3.graphics.DragPreviewProvider", false, cl);
            for (Method m : provider.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                // createDrawable + drawDragView / lambda$createDrawable$0
                if (!(name.equals("createDrawable") || name.equals("drawDragView")
                        || name.contains("createDrawable"))) continue;
                hookOnce(m, chain -> {
                    try {
                        if (enabled()) {
                            Object view = field(chain.getThisObject(), "mView");
                            if (isFolderIcon(view)) {
                                beginFolderDrag(view);
                                suppressOemDrawable(field(view, "mBackground"));
                            }
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 DragPreviewProvider suppress unavailable", e);
        }

        try {
            Class<?> workspace = Class.forName("com.android.launcher3.Workspace", false, cl);
            for (Method m : workspace.getDeclaredMethods()) {
                if (!m.getName().equals("beginDragShared") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                if (m.getParameterCount() < 2) continue;
                hookOnce(m, chain -> {
                    try {
                        if (enabled() && chain.getArgs().size() >= 1
                                && isFolderIcon(chain.getArg(0))) {
                            beginFolderDrag(chain.getArg(0));
                            suppressOemDrawable(field(chain.getArg(0), "mBackground"));
                        }
                    } catch (Throwable ignored) { }
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        Object source = chain.getArgs().size() >= 1 ? chain.getArg(0) : null;
                        if (!isFolderIcon(source)) {
                            if (folderDragActive) endFolderDrag(source);
                            return result;
                        }
                        beginFolderDrag(source);
                        suppressOemDrawable(field(source, "mBackground"));
                        Object dragView = result;
                        if (dragView == null) {
                            Object controller = invokeNoArgs(chain.getThisObject(), "getDragController");
                            dragView = invokeNoArgs(controller, "getDragObject");
                            dragView = field(dragView, "dragView");
                            if (dragView == null) dragView = invokeNoArgs(controller, "getDragView");
                        }
                        installFolderDragGlass(dragView, chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, "Os14 beginDragShared folder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 beginDragShared hook unavailable", e);
        }

        for (String dragViewClass : new String[] {
                "com.android.launcher3.dragndrop.OplusDragView",
                "com.android.launcher3.dragndrop.DragView",
                "com.android.launcher3.dragndrop.LauncherDragView"
        }) {
            try {
                Class<?> c = Class.forName(dragViewClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String name = m.getName();
                    if (name.equals("onDragStart")) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (enabled() && folderDragActive) {
                                    installFolderDragGlass(chain.getThisObject(), null);
                                }
                            } catch (Throwable ignored) { }
                            return result;
                        });
                    } else if (name.equals("onDragEnd") || name.equals("remove")
                            || name.equals("detachContentView")) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (folderDragActive) endFolderDrag(chain.getThisObject());
                            } catch (Throwable ignored) { }
                            return result;
                        });
                    } else if (name.equals("move") || name.equals("setTranslationX")
                            || name.equals("setTranslationY")) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (enabled() && folderDragActive) {
                                    Object content = invokeNoArgs(chain.getThisObject(), "getContentView");
                                    if (content instanceof View && GlassInstaller.get((View) content) != null) {
                                        GlassInstaller.forceCapture((View) content);
                                    }
                                }
                            } catch (Throwable ignored) { }
                            return result;
                        });
                    }
                }
            } catch (Throwable ignored) { }
        }

        after(cl, "com.android.launcher3.dragndrop.DragController", "endDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
        after(cl, "com.android.launcher3.dragndrop.DragController", "cancelDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
        after(cl, "com.android.launcher3.dragndrop.OplusDragController", "endDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
        after(cl, "com.android.launcher3.dragndrop.OplusDragController", "cancelDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
    }

    private void installFolderDragGlass(Object dragView, Object workspace) {
        if (!enabled() || dragView == null) return;
        try {
            if (folderDragSource != null) {
                suppressOemDrawable(field(folderDragSource, "mBackground"));
            }
            Object content = invokeNoArgs(dragView, "getContentView");
            if (!(content instanceof View)) return;
            View view = (View) content;
            // ColorOS 14 createDrawable path: ImageView holds a bitmap that may still contain
            // the OEM white plate. Rebuild from the FolderIcon with white suppressed so glass
            // (View background) is visible through transparent plate pixels.
            if (view instanceof ImageView && folderDragSource instanceof View) {
                rebuildFolderDragBitmapWithoutOemPlate((ImageView) view, (View) folderDragSource);
            } else if (isFolderIcon(view)) {
                suppressOemDrawable(field(view, "mBackground"));
            }
            GlassInstaller.installBackground(view, currentConfig());
            GlassDrawable glass = GlassInstaller.get(view);
            if (glass != null) {
                glass.setAlpha(255);
                float radius = readFolderDragRadius(view);
                if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
            }
            View seed = workspace instanceof View ? (View) workspace : null;
            if (seed == null && folderDragSource instanceof View) {
                Object launcher = resolveLauncher(folderDragSource);
                Object ws = invokeNoArgs(launcher, "getWorkspace");
                if (ws instanceof View) seed = (View) ws;
            }
            if (seed != null) GlassInstaller.setOverlaySource(view, seed);
            GlassInstaller.forceCapture(view);
            view.invalidate();
            if (dragView instanceof View) ((View) dragView).invalidate();
        } catch (Throwable e) {
            log(5, "Os14 installFolderDragGlass failed", e);
        }
    }

    private void rebuildFolderDragBitmapWithoutOemPlate(ImageView image, View folderIcon) {
        try {
            Object preview = field(folderIcon, "mBackground");
            suppressOemDrawable(preview);
            Drawable current = image.getDrawable();
            int width = current != null ? Math.max(1, current.getIntrinsicWidth()) : 0;
            int height = current != null ? Math.max(1, current.getIntrinsicHeight()) : 0;
            if (width <= 1 || height <= 1) {
                Rect bounds = new Rect();
                try {
                    Method m = null;
                    for (Method cand : folderIcon.getClass().getMethods()) {
                        if ("getWorkspaceVisualDragBounds".equals(cand.getName())
                                && cand.getParameterCount() == 1) {
                            m = cand;
                            break;
                        }
                    }
                    if (m != null) {
                        m.invoke(folderIcon, bounds);
                        width = Math.max(1, bounds.width());
                        height = Math.max(1, bounds.height());
                    }
                } catch (Throwable ignored) { }
                if (width <= 1) width = Math.max(1, folderIcon.getWidth());
                if (height <= 1) height = Math.max(1, folderIcon.getHeight());
            }
            if (width <= 1 || height <= 1) return;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            int save = canvas.save();
            try {
                // Match DragPreviewProvider centering of the visual drag bounds.
                Rect plate = new Rect();
                try {
                    Method m = null;
                    for (Method cand : folderIcon.getClass().getMethods()) {
                        if ("getWorkspaceVisualDragBounds".equals(cand.getName())
                                && cand.getParameterCount() == 1) {
                            m = cand;
                            break;
                        }
                    }
                    if (m != null) {
                        m.invoke(folderIcon, plate);
                        canvas.translate(-plate.left, -plate.top);
                    }
                } catch (Throwable ignored) { }
                suppressOemDrawable(preview);
                folderIcon.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
            }
            image.setImageBitmap(bmp);
            image.setBackground(null);
        } catch (Throwable e) {
            log(5, "Os14 rebuildFolderDragBitmapWithoutOemPlate failed", e);
        }
    }

    private float readFolderDragRadius(View host) {
        try {
            Object preview = null;
            if (isFolderIcon(host)) preview = field(host, "mBackground");
            else if (folderDragSource != null) preview = field(folderDragSource, "mBackground");
            Object r = field(preview, "mRadius");
            if (r instanceof Number && ((Number) r).floatValue() > 0f) {
                return ((Number) r).floatValue();
            }
        } catch (Throwable ignored) { }
        return 16f * host.getResources().getDisplayMetrics().density;
    }

    private void beginFolderDrag(Object folderIcon) {
        folderDragActive = true;
        folderDragSource = folderIcon;
        try {
            suppressOemDrawable(field(folderIcon, "mBackground"));
        } catch (Throwable ignored) { }
        forceDepthBlur(folderIcon, 0f);
    }

    private void endFolderDrag(Object host) {
        if (!folderDragActive) return;
        folderDragActive = false;
        Object source = folderDragSource;
        folderDragSource = null;
        try {
            GlassInstaller.clearDragOverlays();
            if (source != null) syncFolderIcon(source);
            // Sibling folders skipped canvas glass only for the drag source; re-sync all.
            refreshWorkspaceFolderGlass(source != null ? source : host);
            // clearDragOverlays already re-arms menu open sampling; call again after folder
            // sync so open-anim capture wins over mid-release freeze.
            GlassInstaller.prioritizeDesktopPopupOpenSampling();
        } catch (Throwable ignored) { }
        forceDepthBlur(host != null ? host : source, 0f);
    }

    /** True when this preview belongs to the folder currently being dragged. */
    private boolean isDragSourcePreview(Object preview) {
        if (preview == null || folderDragSource == null) return false;
        Object host = resolveFolderIcon(preview);
        return host != null && host == folderDragSource;
    }

    private void refreshWorkspaceFolderGlass(Object from) {
        try {
            Object launcher = resolveLauncher(from);
            Object workspace = invokeNoArgs(launcher, "getWorkspace");
            if (workspace instanceof ViewGroup) {
                walkSyncFolderIcons((ViewGroup) workspace);
            }
        } catch (Throwable ignored) { }
    }

    private void walkSyncFolderIcons(ViewGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (isFolderIcon(child)) {
                syncFolderIcon(child);
                child.invalidate();
            } else if (child instanceof ViewGroup) {
                walkSyncFolderIcons((ViewGroup) child);
            }
        }
    }

    // ── Overview 浮窗 / 分屏 / TaskMenu ──────────────────────────────────────

    private static final Object TAG_TASK_HEADER_GLASS = new Object();

    private void hookRecentsTaskShortcuts(ClassLoader cl) {
        after(cl, "com.oplus.quickstep.views.OplusTaskHeaderView", "onFinishInflate",
                this::applyTaskHeaderShortcutGlass);
        try {
            Class<?> header = Class.forName(
                    "com.oplus.quickstep.views.OplusTaskHeaderView", false, cl);
            for (Method m : header.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("showWindowIcon")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (enabled() && chain.getArgs().size() >= 1) {
                                applyTaskHeaderShortcutButtonGlass(chain.getArg(0));
                            }
                        } catch (Throwable e) {
                            log(5, "Os14 showWindowIcon glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("hideWindowIcon")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getArgs().size() >= 1) {
                                removeTaskHeaderShortcutButtonGlass(chain.getArg(0));
                            }
                        } catch (Throwable e) {
                            log(5, "Os14 hideWindowIcon glass remove failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, "Os14 OplusTaskHeaderView show/hide glass unavailable", e);
        }
        after(cl, "com.oplus.quickstep.views.OplusTaskHeaderView", "onDetachedFromWindow", header -> {
            removeTaskHeaderShortcutButtonGlass(field(header, "miniWindowBtn"));
            removeTaskHeaderShortcutButtonGlass(field(header, "splitWindowBtn"));
        });

        after(cl, "com.android.quickstep.views.OplusTaskMenuViewImpl", "addMenuOptions",
                this::applyTaskMenuGlass);
        after(cl, "com.android.quickstep.views.OplusTaskMenuViewImpl", "animateOpen",
                this::applyTaskMenuGlass);
        try {
            Class<?> menu = Class.forName(
                    "com.android.quickstep.views.OplusTaskMenuViewImpl", false, cl);
            for (Method m : menu.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.equals("populateAndShowForTask") || name.equals("animateOpenOrClosed"))) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyTaskMenuGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, "Os14 TaskMenu." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 OplusTaskMenuViewImpl glass unavailable", e);
        }

        hookOs14RapidReactionGlass(cl);
    }

    private void applyTaskHeaderShortcutGlass(Object header) {
        if (!enabled() || header == null) return;
        applyTaskHeaderShortcutButtonGlass(field(header, "miniWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(field(header, "splitWindowBtn"));
    }

    private void applyTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!enabled() || !(buttonObj instanceof View)) return;
        final View button = (View) buttonObj;
        if (button.getVisibility() != View.VISIBLE) {
            removeTaskHeaderShortcutButtonGlass(button);
            return;
        }
        try {
            // Drop any stale glass first so reopen never stacks backgrounds.
            if (button.getTag() != TAG_TASK_HEADER_GLASS && GlassInstaller.get(button) != null) {
                GlassInstaller.uninstall(button);
            }
            GlassDrawable existing = GlassInstaller.get(button);
            if (existing != null && button.getTag() == TAG_TASK_HEADER_GLASS
                    && button.getBackground() == existing) {
                refreshTaskHeaderButtonGlass(button, existing);
                return;
            }
            if (existing != null && button.getBackground() != existing) {
                GlassInstaller.uninstall(button);
            }
            GlassInstaller.installBackground(button, currentConfig());
            button.setTag(TAG_TASK_HEADER_GLASS);
            GlassDrawable live = GlassInstaller.get(button);
            if (live != null) refreshTaskHeaderButtonGlass(button, live);
            else {
                button.post(() -> {
                    if (button.getVisibility() != View.VISIBLE) {
                        removeTaskHeaderShortcutButtonGlass(button);
                        return;
                    }
                    GlassInstaller.installBackground(button, currentConfig());
                    button.setTag(TAG_TASK_HEADER_GLASS);
                    GlassDrawable g = GlassInstaller.get(button);
                    if (g != null) refreshTaskHeaderButtonGlass(button, g);
                });
            }
        } catch (Throwable e) {
            log(5, "Os14 applyTaskHeaderShortcutButtonGlass failed", e);
        }
    }

    private void removeTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!(buttonObj instanceof View)) return;
        View button = (View) buttonObj;
        try {
            if (GlassInstaller.get(button) != null
                    || button.getBackground() instanceof GlassDrawable
                    || button.getTag() == TAG_TASK_HEADER_GLASS) {
                GlassInstaller.uninstall(button);
                if (button.getBackground() instanceof GlassDrawable) {
                    button.setBackground(null);
                }
                button.setTag(null);
                button.invalidate();
            }
        } catch (Throwable e) {
            log(5, "Os14 removeTaskHeaderShortcutButtonGlass failed", e);
        }
    }

    private void refreshTaskHeaderButtonGlass(View button, GlassDrawable live) {
        float radius = Math.min(button.getWidth(), button.getHeight()) / 2f;
        if (radius <= 0f) {
            radius = 14f * button.getResources().getDisplayMetrics().density;
        }
        live.setCornerRadii(radius, radius, radius, radius);
        if (button.getWidth() > 0 && button.getHeight() > 0) {
            GlassInstaller.forceCapture(button);
            button.invalidate();
        }
    }

    private void applyTaskMenuGlass(Object menu) {
        if (!menuStyleEnabled() || !(menu instanceof View)) return;
        try {
            Object listObj = field(menu, "mListView");
            View listView = listObj instanceof View ? (View) listObj : null;
            clearTaskMenuOpaqueChrome(menu, listView);
            View glassHost = listView != null ? listView : (View) menu;
            if (listView != null && ((View) menu).getBackground() instanceof GlassDrawable) {
                GlassInstaller.uninstall((View) menu);
            }
            if (menu != glassHost) ((View) menu).setBackground(null);
            GlassInstaller.installBackground(glassHost, currentConfig());
            Object taskView = field(menu, "mTaskView");
            if (!(taskView instanceof View)) taskView = invokeNoArgs(menu, "getTaskView");
            if (taskView instanceof View) {
                GlassInstaller.setOverlaySource(glassHost, (View) taskView);
                net.z841973620.colorosliquidglass.glass.TaskContentOverlay.prebakeProtect((View) taskView);
            }
            final View host = glassHost;
            final View taskOverlay = taskView instanceof View ? (View) taskView : null;
            Runnable refresh = () -> {
                clearTaskMenuOpaqueChrome(menu, listView);
                if (!(host.getBackground() instanceof GlassDrawable)) {
                    GlassInstaller.installBackground(host, currentConfig());
                }
                GlassDrawable live = GlassInstaller.get(host);
                if (live == null) return;
                float radius = 16f * host.getResources().getDisplayMetrics().density;
                live.setCornerRadii(radius, radius, radius, radius);
                if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
                if (taskOverlay != null) GlassInstaller.setOverlaySource(host, taskOverlay);
                GlassInstaller.forceCapture(host);
                host.invalidate();
            };
            if (host.getWidth() > 0 && host.getHeight() > 0) {
                refresh.run();
                host.post(refresh);
            } else {
                host.post(refresh);
            }
        } catch (Throwable e) {
            log(5, "Os14 applyTaskMenuGlass failed", e);
        }
    }

    private void clearTaskMenuOpaqueChrome(Object menu, View listView) {
        if (listView != null) {
            if (!(listView.getBackground() instanceof GlassDrawable)) listView.setBackground(null);
            Object parent = listView.getParent();
            if (parent instanceof View) {
                View roundFrame = (View) parent;
                if (!(roundFrame.getBackground() instanceof GlassDrawable)) {
                    roundFrame.setBackground(null);
                }
                try {
                    invoke(roundFrame, "setClipMode", new Class<?>[] { int.class }, 0);
                } catch (Throwable ignored) { }
            }
        }
        if (menu instanceof View) {
            View menuView = (View) menu;
            if (!(menuView.getBackground() instanceof GlassDrawable)) menuView.setBackground(null);
        }
    }

    private void hookOs14RapidReactionGlass(ClassLoader cl) {
        // ColorOS 14: TriggerPanelView + RectangleBackgroundView.
        // Swipe-up grows the capsule via RectangleBackgroundView.scaleFraction (onDraw);
        // reset() sets INVISIBLE. Glass hosts are tagged children: resize only relayouts,
        // close fully removes — never leave stale hosts for the next enter.
        try {
            Class<?> panel = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.TriggerPanelView", false, cl);
            for (Method m : panel.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("onLayout") || name.equals("updateProgress")
                        || name.equals("initAnimation") || name.equals("setPanelType")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            syncOs14RapidReactionGlass(chain.getThisObject());
                        } catch (Throwable e) {
                            log(5, "Os14 TriggerPanelView." + name + " glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("reset") || name.equals("resetIfNeed")) {
                    hookOnce(m, chain -> {
                        Object self = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            removeOs14RapidReactionGlass(self);
                        } catch (Throwable e) {
                            log(5, "Os14 TriggerPanelView." + name + " remove glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("onDetachedFromWindow")) {
                    hookOnce(m, chain -> {
                        try {
                            removeOs14RapidReactionGlass(chain.getThisObject());
                        } catch (Throwable ignored) { }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable e) {
            log(5, "Os14 TriggerPanelView glass unavailable", e);
        }
        try {
            Class<?> bg = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.RectangleBackgroundView", false, cl);
            for (Method m : bg.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("onDraw") && m.getParameterCount() == 1) {
                    hookOnce(m, chain -> {
                        try {
                            if (enabled()) clearOs14RapidOpaqueFills(chain.getThisObject());
                        } catch (Throwable ignored) { }
                        Object result = chain.proceed();
                        try {
                            // Rects are scaled in onDraw (scaleFraction) — resize glass after.
                            if (enabled()) {
                                Object parent = chain.getThisObject() instanceof View
                                        ? ((View) chain.getThisObject()).getParent() : null;
                                if (isClassOrSubclass(parent,
                                        "com.oplus.quickstep.rapidreaction.widget.TriggerPanelView")) {
                                    syncOs14RapidReactionGlass(parent);
                                }
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                } else if (name.equals("setScaleX") || name.equals("setScaleY")
                        || name.equals("reset")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (enabled()) {
                                Object parent = chain.getThisObject() instanceof View
                                        ? ((View) chain.getThisObject()).getParent() : null;
                                if (isClassOrSubclass(parent,
                                        "com.oplus.quickstep.rapidreaction.widget.TriggerPanelView")) {
                                    syncOs14RapidReactionGlass(parent);
                                }
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, "Os14 RectangleBackgroundView glass unavailable", e);
        }
    }

    private static final Object TAG_RAPID_FLOAT_LEFT = new Object();
    private static final Object TAG_RAPID_FLOAT_RIGHT = new Object();

    private void syncOs14RapidReactionGlass(Object panel) {
        if (!(panel instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) panel;
        // reset() sets INVISIBLE (4) and alreadyReset=true. Do not use alpha here —
        // enter animation starts VISIBLE at alpha 0 while the capsule is growing.
        if (!enabled() || host.getVisibility() != View.VISIBLE) {
            removeOs14RapidReactionGlass(panel);
            return;
        }
        Object alreadyReset = field(panel, "alreadyReset");
        if (Boolean.TRUE.equals(alreadyReset)) {
            removeOs14RapidReactionGlass(panel);
            return;
        }
        applyOs14RapidReactionGlass(panel);
    }

    private void applyOs14RapidReactionGlass(Object panel) {
        if (!enabled() || !(panel instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) panel;
        try {
            Object bg = field(panel, "hintBackgroundView");
            if (!(bg instanceof View)) return;
            clearOs14RapidOpaqueFills(bg);
            float radius = 0f;
            Object r = field(bg, "bgRectangleCornerRadius");
            if (r instanceof Number) radius = ((Number) r).floatValue();
            if (radius <= 0f) {
                radius = 20f * ((View) bg).getResources().getDisplayMetrics().density;
            }
            // One tagged host per rect. Swipe-up resize only relayouts — never addView again.
            installOs14RapidGlassForRect(host, (View) bg, field(bg, "basicAndLeftRect"),
                    TAG_RAPID_FLOAT_LEFT, radius);
            Object panelType = field(panel, "panelType");
            boolean dual = panelType instanceof Number && ((Number) panelType).intValue() != 1;
            if (dual) {
                installOs14RapidGlassForRect(host, (View) bg, field(bg, "rightRect"),
                        TAG_RAPID_FLOAT_RIGHT, radius);
            } else {
                removeOs14RapidGlassHost(host, TAG_RAPID_FLOAT_RIGHT);
            }
        } catch (Throwable e) {
            log(5, "Os14 applyOs14RapidReactionGlass failed", e);
        }
    }

    private void removeOs14RapidReactionGlass(Object panel) {
        if (!(panel instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) panel;
        try {
            removeOs14RapidGlassHost(host, TAG_RAPID_FLOAT_LEFT);
            removeOs14RapidGlassHost(host, TAG_RAPID_FLOAT_RIGHT);
            // Sweep any leftover glass children from earlier buggy installs.
            for (int i = host.getChildCount() - 1; i >= 0; i--) {
                View child = host.getChildAt(i);
                if (child == null) continue;
                Object tag = child.getTag();
                boolean ours = tag == TAG_RAPID_FLOAT_LEFT || tag == TAG_RAPID_FLOAT_RIGHT;
                // Only strip tagged hosts or plain View overlays that still carry GlassDrawable.
                if (ours || (child.getClass() == View.class
                        && (GlassInstaller.get(child) != null
                        || child.getBackground() instanceof GlassDrawable))) {
                    GlassInstaller.uninstall(child);
                    try {
                        host.removeView(child);
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable e) {
            log(5, "Os14 removeOs14RapidReactionGlass failed", e);
        }
    }

    private void installOs14RapidGlassForRect(ViewGroup panel, View bg, Object rectObj,
            Object tag, float radius) {
        if (!(rectObj instanceof android.graphics.RectF)) return;
        android.graphics.RectF rect = (android.graphics.RectF) rectObj;
        if (rect.width() < 2f || rect.height() < 2f) return;
        android.graphics.RectF local = new android.graphics.RectF(rect);
        local.offset(bg.getLeft(), bg.getTop());
        View glassHost = findOs14RapidGlassHost(panel, tag);
        if (glassHost == null) {
            glassHost = new View(panel.getContext());
            glassHost.setTag(tag);
            glassHost.setClickable(false);
            glassHost.setFocusable(false);
            glassHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            try {
                int index = panel.indexOfChild(bg);
                if (index >= 0) panel.addView(glassHost, index + 1);
                else panel.addView(glassHost, 0);
            } catch (Throwable e) {
                log(5, "Os14 rapid glass host attach failed", e);
                return;
            }
        }
        int left = Math.round(local.left);
        int top = Math.round(local.top);
        int right = Math.round(local.right);
        int bottom = Math.round(local.bottom);
        int prevW = glassHost.getWidth();
        int prevH = glassHost.getHeight();
        glassHost.measure(
                View.MeasureSpec.makeMeasureSpec(Math.max(1, right - left), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, bottom - top), View.MeasureSpec.EXACTLY));
        glassHost.layout(left, top, right, bottom);
        glassHost.setVisibility(View.VISIBLE);
        GlassInstaller.setOverlaySource(glassHost, null);
        GlassDrawable existing = GlassInstaller.get(glassHost);
        boolean firstInstall = existing == null || glassHost.getBackground() != existing;
        if (firstInstall) {
            GlassInstaller.installBackground(glassHost, currentConfig());
            existing = GlassInstaller.get(glassHost);
        }
        if (existing != null && radius > 0f) {
            existing.setCornerRadii(radius, radius, radius, radius);
        }
        boolean sizeChanged = glassHost.getWidth() != prevW || glassHost.getHeight() != prevH;
        if (firstInstall || sizeChanged) {
            GlassInstaller.forceCapture(glassHost);
        }
        glassHost.invalidate();
    }

    private static View findOs14RapidGlassHost(ViewGroup panel, Object tag) {
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child != null && child.getTag() == tag) return child;
        }
        return null;
    }

    private void removeOs14RapidGlassHost(ViewGroup panel, Object tag) {
        View glassHost = findOs14RapidGlassHost(panel, tag);
        if (glassHost == null) return;
        try {
            GlassInstaller.uninstall(glassHost);
            panel.removeView(glassHost);
        } catch (Throwable ignored) { }
    }

    private static void clearOs14RapidOpaqueFills(Object bgView) {
        for (String paintName : new String[] {
                "basicAndLeftPaint", "rightPaint",
                "basicAndLeftStrokePaint", "rightStrokePaint"
        }) {
            Object paint = field(bgView, paintName);
            if (paint instanceof android.graphics.Paint) {
                ((android.graphics.Paint) paint).setColor(0);
            }
        }
        setField(bgView, "currentBasicAndLeftColor", 0);
        setField(bgView, "currentRightColor", 0);
        setField(bgView, "currentBasicAndLeftStrokeColor", 0);
        setField(bgView, "currentRightStrokeColor", 0);
    }

    // ── Folder open/close depth helpers ─────────────────────────────────────

    private void beginFolderOpen(Object folder) {
        folderOpenActive = true;
        forceDepthBlur(folder, 0f);
        // Do not touch mContentAnimationBackground here — OEM updateAnimColorFilter requires
        // a non-null background; morph glass is applied from animation-manager hooks instead.
        if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
    }

    private void endFolderOpen(Object host) {
        folderOpenActive = false;
        forceDepthBlur(host, 0f);
        if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
    }

    private void forceDepthBlur(Object host, float value) {
        try {
            Object launcher = resolveLauncher(host);
            Object depth = invokeNoArgs(launcher, "getDepthController");
            if (depth == null) return;
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setBlurWithoutAnim") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    m.invoke(depth, value);
                    break;
                }
            }
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setBlur") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    m.invoke(depth, value);
                    break;
                }
            }
            Object staticBlur = field(depth, "mStaticBlurView");
            if (staticBlur instanceof View) ((View) staticBlur).setAlpha(0f);
        } catch (Throwable e) {
            log(5, "Os14 forceDepthBlur failed", e);
        }
    }

    private boolean hasOpenFolder(Object launcher) {
        try {
            if (launcher == null) launcher = findActiveLauncher();
            if (launcher == null) return folderOpenActive;
            Object open = invokeNoArgs(launcher, "getOpenFolder");
            if (open != null) return true;
            Object workspace = invokeNoArgs(launcher, "getWorkspace");
            Object folder = invokeNoArgs(workspace, "getOpenFolder");
            return folder != null;
        } catch (Throwable ignored) {
            return folderOpenActive;
        }
    }

    private Object findActiveLauncher() {
        try {
            for (Object host : new Object[] { folderDragSource }) {
                Object launcher = resolveLauncher(host);
                if (launcher != null) return launcher;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object resolveLauncher(Object host) {
        if (host == null) return null;
        if (isClassOrSubclass(host, "com.android.launcher3.Launcher")
                || isClassOrSubclass(host, "com.android.launcher.Launcher")) {
            return host;
        }
        Object launcher = field(host, "mLauncher");
        if (launcher != null) return launcher;
        Object activity = field(host, "mActivity");
        if (activity != null) return activity;
        Object folder = field(host, "mFolder");
        if (folder != null) return resolveLauncher(folder);
        Object icon = field(host, "mFolderIcon");
        if (icon != null) return resolveLauncher(icon);
        return field(host, "mColorFolder") != null
                ? resolveLauncher(field(host, "mColorFolder")) : null;
    }

    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(object, args);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private interface ObjectAction {
        void run(Object object) throws Throwable;
    }

    private void after(ClassLoader cl, String className, String methodName, ObjectAction action) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        action.run(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, className + "." + methodName + " apply failed", e);
                    }
                    return result;
                });
            }
            if (!found) log(5, "Method not found: " + className + "." + methodName);
        } catch (Throwable e) {
            log(5, "Class unavailable: " + className, e);
        }
    }

    private void hookOnce(Executable e, io.github.libxposed.api.XposedInterface.Hooker callback) {
        synchronized (hooked) {
            if (!hooked.add(e)) return;
            e.setAccessible(true);
            module.hook(e).intercept(callback);
            log(4, "Hooked: " + e.getDeclaringClass().getName() + "#" + e.getName());
        }
    }

    private GlassConfig currentConfig() {
        try {
            return prefs == null ? new GlassConfig() : GlassConfig.read(prefs);
        } catch (Throwable e) {
            return new GlassConfig();
        }
    }

    private boolean enabled() {
        return currentConfig().enabled;
    }

    /** Master enable + 「修改菜单栏样式」 for long-press / Overview / float app menus. */
    private boolean menuStyleEnabled() {
        GlassConfig config = currentConfig();
        return config.enabled && config.modifyMenuStyle;
    }

    private void log(int level, String msg) {
        if (module != null) module.log(level, TAG, msg);
    }

    private void log(int level, String msg, Throwable t) {
        if (module != null) module.log(level, TAG, msg, t);
    }

    private static boolean isFolderIcon(Object object) {
        return isClassOrSubclass(object, "com.android.launcher3.folder.FolderIcon");
    }

    private static Object resolveFolderIcon(Object preview) {
        Object host = field(preview, "mInvalidateDelegate");
        if (isFolderIcon(host)) return host;
        return null;
    }

    private static boolean isFolderOpen(Object folderIcon) {
        Object folder = field(folderIcon, "mFolder");
        Object result = invokeNoArgs(folder, "isOpen");
        if (result instanceof Boolean) return (Boolean) result;
        Object value = field(folder, "mIsOpen");
        return value instanceof Boolean && (Boolean) value;
    }

    private static boolean isClassOrSubclass(Object object, String className) {
        if (object == null) return false;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) return true;
        }
        return false;
    }

    private static View asView(Object o) {
        return o instanceof View ? (View) o : null;
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean setField(Object object, String name, Object value) {
        if (object == null) return false;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(object, value);
                return true;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    private static Object invokeNoArgs(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(object);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
