package net.z841973620.colorosliquidglass.hook;

import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import net.z841973620.colorosliquidglass.GlassConfig;
import net.z841973620.colorosliquidglass.glass.BehindDisplayCapture;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.LauncherAshmemMode;
import net.z841973620.colorosliquidglass.glass.TaskContentOverlay;
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

public final class ModuleMain extends XposedModule {
    private static final String TAG = "ColorOSLiquidGlass";
    private final Set<Executable> hooked = new HashSet<>();
    /** OEM {@code mBgDrawable} swapped out while LiquidGlass owns the preview plate. */
    private static final Map<Object, Drawable> SAVED_PREVIEW_OEM_BG =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ColorDrawable TRANSPARENT_PREVIEW_BG = new ColorDrawable(Color.TRANSPARENT);
    private SharedPreferences remotePreferences;
    /** True while a folder DragView is active; also used with TOGGLE_BAR blur suppression. */
    private volatile boolean folderDragActive;
    /** True from Folder.animateOpen until close; suppresses wallpaper depth blur. */
    private volatile boolean folderOpenActive;
    /** True while page-indicator press-drag expands the frosted pill behind the dots. */
    private volatile boolean pageIndicatorFrameActive;
    /**
     * After leaving widget / PAGE_PREVIEW, the indicator pill keeps fading (and may shrink when
     * the blank drop-target page is removed). Hold glass through that window so OEM LayerBlur /
     * frosted paint cannot flash on an already-NORMAL desktop.
     */
    private volatile boolean pageIndicatorExitHold;
    private View pageIndicatorExitReleaseTarget;
    private Runnable pageIndicatorExitRelease;
    /** Bumps when a new unlock/resume refresh burst starts so older delayed posts are ignored. */
    private volatile int unlockGlassRefreshToken;
    /** Delayed float-menu glass applies — cancelled on dismiss so they cannot reopen ashmem. */
    private final java.util.ArrayList<Runnable> pendingFloatMenuGlass = new java.util.ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** ColorOS 13/14 backend; null when using the built-in ColorOS 15+ Hook path. */
    private Os14HookBackend legacyBackend;
    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!pkg.equals("com.android.launcher") && !pkg.equals("com.android.systemui")) return;
        try {
            remotePreferences = getRemotePreferences(GlassConfig.PREFS);
            GlassConfig loaded = GlassConfig.read(remotePreferences);
            log(4, TAG, "package=" + pkg + " first=" + param.isFirstPackage()
                    + " enabled=" + loaded.enabled
                    + " updatedAt=" + remotePreferences.getLong("updated_at", 0L));
        } catch (Throwable e) {
            remotePreferences = null;
            log(5, TAG, "Cannot read remote preferences; defaults are used", e);
        }
        // Do not return for !isFirstPackage or enabled=false. ColorOS may expose the useful
        // ClassLoader in a later callback, and hooks must already exist when the next config is read.
        ClassLoader cl = param.getDefaultClassLoader();
        ColorOsVersion.Flavor flavor = ColorOsVersion.detect(cl);
        boolean useLegacy = ColorOsVersion.usesLegacyLauncherApis(cl);
        log(4, TAG, "ColorOS flavor=" + flavor
                + " major=" + ColorOsVersion.majorVersion()
                + " useLegacyApis=" + useLegacy);
        if (useLegacy) {
            if (legacyBackend == null) legacyBackend = new Os14HookBackend();
            if (pkg.equals("com.android.launcher")) {
                legacyBackend.hookLauncher(this, cl, remotePreferences);
            } else {
                legacyBackend.hookSystemUi(this, cl, remotePreferences);
            }
            return;
        }
        if (pkg.equals("com.android.launcher")) {
            hookLauncher(cl);
        } else {
            // Live float app-options menus run in SystemUI (WM Shell), not Launcher.
            hookSystemUiFloatMenus(cl);
            DesktopBackdropClient.warmUp();
        }
    }

    private void hookLauncher(ClassLoader cl) {
        // Closed folder icons own a dedicated FolderRoundImageView. Never style Folder/OplusFolder:
        // FolderIcon still dispatches this child while the open animation is running, so a glass
        // background on the container obscures the opened folder's icons.
        hookFolderPreviewBackground(cl);
        hookCreateFolderPreviewGlass(cl);
        hookFolderVisibility(cl);
        hookFolderRefreshEvents(cl);
        hookFolderPopupBlur(cl);
        hookFolderDragPreview(cl);
        hookFolderDragDepthBlur(cl);
        hookRecentsClearButton(cl);
        hookRecentsTaskShortcuts(cl);
        hookToggleBarChrome(cl);
        hookDragViewMove(cl);
        hookWorkspaceDragOver(cl);
        hookUnlockGlassRefresh(cl);
        hookWallpaperScaleTracking(cl);
        hookDesktopBackdropIpc(cl);
        after("com.android.launcher3.folder.FolderIcon", cl, "onFolderClose", o -> {
            endFolderOpen(o);
            syncFolderIconDeferred(o, true);
        });
    }

    /**
     * Export folder-glass-equivalent desktop tiles to SystemUI float menus via SharedMemory.
     */
    private void hookDesktopBackdropIpc(ClassLoader cl) {
        DesktopBackdropHub.start();
        after("com.android.launcher3.Launcher", cl, "onResume", launcher -> {
            attachDesktopBackdropRoot(launcher);
            DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher.Launcher", cl, "onResume", launcher -> {
            attachDesktopBackdropRoot(launcher);
            DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.Launcher", cl, "onAttachedToWindow", this::attachDesktopBackdropRoot);
        after("com.android.launcher.Launcher", cl, "onAttachedToWindow", this::attachDesktopBackdropRoot);
        after("com.android.launcher3.Workspace", cl, "scrollTo", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.OplusWorkspace", cl, "scrollTo", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.Workspace", cl, "onScrollChanged", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.OplusWorkspace", cl, "onScrollChanged", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.CellLayout", cl, "onLayout", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.CellLayout", cl, "onViewAdded", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.CellLayout", cl, "onViewRemoved", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.hotseat.OplusHotseat", cl, "onLayout", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.dragndrop.DragView", cl, "move", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.OplusDragView", cl, "move", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        // ColorOS may reposition via translation/layout instead of move() — keep glass streaming.
        after("com.android.launcher3.dragndrop.DragView", cl, "setTranslationX", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.DragView", cl, "setTranslationY", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.OplusDragView", cl, "setTranslationX", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.OplusDragView", cl, "setTranslationY", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.popup.OplusPopupContainerWithArrow", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.popup.PopupContainerWithArrow", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher.layout.ItemResizeFrame", cl, "dispatchDraw", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher.layout.ItemResizeFrame", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
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
            syncDesktopHomeValidForLauncher(launcher);
        } catch (Throwable e) {
            log(5, TAG, "attachDesktopBackdropRoot failed", e);
        }
    }

    private void hookFolderPreviewBackground(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.OplusPreviewBackground";
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setBackground") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object host = chain.getArg(0);
                        if (!isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return result;
                        syncFolderPreview(host, chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, className + ".setBackground apply failed", e);
                    }
                    return result;
                });
            }
            if (!found) log(5, TAG, "Method not found: " + className + ".setBackground");

            after(className, cl, "setRadius", o -> {
                Object host = field(o, "mInvalidateDelegate");
                if (isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                    syncFolderPreviewDeferred(host, o, false);
                }
            });
            after(className, cl, "updateBgColorFilter", o -> {
                Object host = field(o, "mInvalidateDelegate");
                if (isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                    syncFolderPreviewDeferred(host, o, false);
                }
            });

            // Drag-over accept / create-folder: OEM hides mBgView and paints via CellLayout.
            // Keep canvas glass only while delegated; restore FolderIcon plate on rest.
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
                    boolean createFolder = resolvePreviewFolderIcon(preview) == null;
                    // CellLayout-parked glass host: OEM clearDrawingDelegate sets VISIBLE and the
                    // plate would remain as a drawn child — hide/detach before proceed.
                    if (methodName.equals("clearDrawingDelegate") && createFolder) {
                        hideAndDetachCreateFolderCaptureHost(preview);
                    }
                    Object result = chain.proceed();
                    try {
                        if (methodName.equals("clearDrawingDelegate")) {
                            if (createFolder) {
                                finishCreateFolderPreviewCleanup(preview);
                            } else {
                                restoreFolderPreviewAfterDelegate(preview);
                                keepHoverFolderGlassVisible(preview);
                            }
                        } else if (methodName.equals("animateToAccept")
                                || methodName.equals("lambda$animateToAccept$0")
                                || methodName.equals("delegateDrawing")) {
                            placeCreateFolderBgForCapture(preview);
                            keepHoverFolderGlassVisible(preview);
                        }
                        if (DesktopBackdropHub.isLive()) {
                            DesktopBackdropSampler.invalidateCache();
                        }
                    } catch (Throwable e) {
                        log(5, TAG, className + "." + methodName + " hover glass failed", e);
                    }
                    return result;
                });
            }

            // animateToRest lives on PreviewBackground (not overridden) — cancel create-folder
            // immediately so glass does not linger through the 100ms rest animation / VISIBLE flash.
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
                            if (resolvePreviewFolderIcon(preview) == null) {
                                cleanupCreateFolderPreview(preview);
                            } else {
                                restoreFolderPreviewAfterDelegate(preview);
                                keepHoverFolderGlassVisible(preview);
                            }
                            if (DesktopBackdropHub.isLive()) {
                                DesktopBackdropSampler.invalidateCache();
                            }
                        } catch (Throwable e) {
                            log(5, TAG, "PreviewBackground.animateToRest cleanup failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, "PreviewBackground.animateToRest hook unavailable", e);
            }

            // ColorOS paints translucent folder_icon_bg / LayerBlur via drawBackground while
            // mBgView is hidden. Replace with LiquidGlass and never fall through to OEM tint.
            try {
                Method drawBackground = c.getDeclaredMethod("drawBackground", Canvas.class, View.class);
                hookOnce(drawBackground, chain -> {
                    if (!enabled()) return chain.proceed();
                    // Capture re-entrancy: do not paint glass/OEM into the backdrop sample.
                    if (GlassInstaller.isPreviewBackdropCapturing()) return null;
                    Object preview = chain.getThisObject();
                    try {
                        suppressOemPreviewDrawable(preview);
                        if (drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "OplusPreviewBackground.drawBackground glass failed", e);
                    }
                    // Prefer empty plate over OEM translucent folder_icon_bg brightening glass.
                    suppressOemPreviewDrawable(preview);
                    return null;
                });
            } catch (Throwable e) {
                log(5, TAG, "OplusPreviewBackground.drawBackground hook unavailable", e);
            }

            // Resize-frame / convert path hides mBgView and draws LayerBlurDrawable via
            // drawBackground(Canvas, ConvertBgParams, ItemInfo). Replace that with glass.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("drawBackground") || m.getParameterCount() != 3
                        || m.isBridge() || m.isSynthetic()) continue;
                if (m.getParameterTypes()[0] != Canvas.class) continue;
                hookOnce(m, chain -> {
                    if (!enabled()) return chain.proceed();
                    try {
                        Object preview = chain.getThisObject();
                        Object host = field(preview, "mInvalidateDelegate");
                        if (!isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                            return chain.proceed();
                        }
                        if (drawGlassForConvertParams(preview, (Canvas) chain.getArg(0),
                                chain.getArg(1), host)) {
                            return null;
                        }
                    } catch (Throwable e) {
                        log(5, TAG, className + ".drawBackground(ConvertBgParams) apply failed", e);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Class unavailable: " + className, e);
        }
    }

    /**
     * Draws LiquidGlass using ConvertBgParams geometry used while the resize frame is active.
     * Relayouts mBgView to the animated rect so backdrop sampling tracks the intermediate size.
     * @return true if glass replaced the OEM LayerBlurDrawable draw
     */
    private boolean drawGlassForConvertParams(Object preview, Canvas canvas, Object params, Object host) {
        if (canvas == null || params == null || !(host instanceof View)) return false;
        Object bgView = field(preview, "mBgView");
        if (!(bgView instanceof ImageView)) return false;
        ImageView image = (ImageView) bgView;
        View folderIcon = (View) host;
        GlassInstaller.installImage(image, currentConfig());
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return false;

        Object xObj = invokeNoArgs(params, "getX");
        Object yObj = invokeNoArgs(params, "getY");
        Object sizeXObj = invokeNoArgs(params, "getSizeX");
        Object sizeYObj = invokeNoArgs(params, "getSizeY");
        if (!(xObj instanceof Number) || !(yObj instanceof Number)
                || !(sizeXObj instanceof Number) || !(sizeYObj instanceof Number)) {
            return false;
        }
        float x = ((Number) xObj).floatValue();
        float y = ((Number) yObj).floatValue();
        int sizeX = ((Number) sizeXObj).intValue();
        int sizeY = ((Number) sizeYObj).intValue();
        if (sizeX <= 0 || sizeY <= 0) return false;

        int left;
        int top = Math.round(y);
        int right;
        int bottom = Math.round(y + sizeY);
        if (folderIcon.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            left = Math.round((folderIcon.getWidth() - sizeX) - x);
            right = folderIcon.getWidth() - Math.round(x);
        } else {
            left = Math.round(x);
            right = Math.round(x + sizeX);
        }
        if (right <= left || bottom <= top) return false;

        // Match OEM LayerBlurDrawable bounds: lay out mBgView to the animated rect so
        // BackdropCapture samples the correct intermediate area each frame.
        // Corner radii stay whatever installImage/detectRadii already set (b73ba87 contract).
        image.measure(
                View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
        image.layout(left, top, right, bottom);
        glass.setBounds(0, 0, right - left, bottom - top);
        GlassInstaller.forceCapture(image);

        int save = canvas.save();
        canvas.translate(left, top);
        glass.draw(canvas);
        canvas.restoreToCount(save);
        return true;
    }

    private void hookFolderPopupBlur(ClassLoader cl) {
        final String className = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                // Cover open / reorder paths used by folders, app icons, and widgets alike.
                if ((name.equals("reorderAndShow")
                        || name.equals("onCreateOpenAnimation")
                        || name.equals("animateOpen")
                        || name.equals("populateAndShow")
                        || name.equals("showEditPopupContainer"))
                        && m.getParameterCount() <= 6) {
                    hookOnce(m, chain -> {
                        keepPopupBlurTransparent(chain.getThisObject());
                        Object result = chain.proceed();
                        keepPopupBlurTransparent(chain.getThisObject());
                        return result;
                    });
                }
            }
            // Static showForIcon is the shared entry for BubbleTextView / widget / folder menus.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("showForIcon") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result != null) keepPopupBlurTransparent(result);
                    } catch (Throwable e) {
                        log(5, TAG, "showForIcon popup blur suppress failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Popup blur hook unavailable", e);
        }
        // Belt-and-suspenders: open anim would fade PopupBlurView to 1 even if we zeroed alpha.
        try {
            Class<?> blur = Class.forName("com.android.launcher3.popup.PopupBlurView", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (!m.getName().equals("createBlurAnim") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled() && Boolean.TRUE.equals(chain.getArg(0))) {
                        Object self = chain.getThisObject();
                        if (self instanceof View) ((View) self).setAlpha(0f);
                        Object anim = chain.proceed();
                        if (anim instanceof ObjectAnimator) {
                            ObjectAnimator oa = (ObjectAnimator) anim;
                            oa.setFloatValues(0f, 0f);
                            oa.setDuration(0L);
                        }
                        return anim;
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PopupBlurView.createBlurAnim hook unavailable", e);
        }
    }

    /** Hides the fullscreen PopupBlurView for any long-press options menu (icon / widget / folder). */
    private void keepPopupBlurTransparent(Object popup) {
        if (!enabled() || popup == null) return;
        // Keep the PopupBlurView object alive so popup close/drag-resize bookkeeping still runs.
        // Only skip the open alpha animation that makes the full-screen blur visible.
        setField(popup, "mAddPopupBlurView", false);
        Object blur = field(popup, "mPopBlurView");
        if (blur instanceof View) ((View) blur).setAlpha(0f);
    }

    /**
     * Folder-only drag preview glass (ported from 408f380 onto the pre-widget-chrome tree).
     * Widget/card DragViews must never receive folder move glass via install/refresh paths.
     */
    private static boolean isFolderDragSource(Object source) {
        return source != null && (
                isClassOrSubclass(source, "com.android.launcher3.folder.FolderIcon")
                        || isClassOrSubclass(source, "com.android.launcher3.folder.FlexibleFolderIcon")
                        || source.getClass().getName().contains("FolderIcon"));
    }

    /** DragView content that belongs to a widget/card — must never receive folder drag glass. */
    private static boolean isWidgetDragContent(View content) {
        if (content == null) return false;
        String n = content.getClass().getName();
        if (n.contains("AppWidgetHostView") || n.contains("WrapWidget")
                || n.contains("LauncherCard") || n.contains("WrapCard")
                || n.contains("WrapAdaptive") || n.contains("WrapMultiSize")
                || n.contains("TitleCard") || n.contains("StackGroup")) {
            return true;
        }
        for (Class<?> c = content.getClass(); c != null; c = c.getSuperclass()) {
            String simple = c.getSimpleName();
            if (simple != null && simple.contains("AppWidgetHostView")) return true;
        }
        return false;
    }

    /** Strip any mistaken DragView glass from a non-folder drag preview. */
    private void stripWidgetDragViewGlass(Object dragView) {
        if (dragView == null) return;
        try {
            Object content = invokeNoArgs(dragView, "getContentView");
            if (content instanceof View) {
                View view = (View) content;
                GlassInstaller.setOverlaySource(view, null);
                if (GlassInstaller.get(view) != null) {
                    GlassInstaller.uninstall(view);
                } else if (view.getBackground() instanceof GlassDrawable) {
                    view.setBackground(null);
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child == null) continue;
                        GlassInstaller.setOverlaySource(child, null);
                        if (GlassInstaller.get(child) != null) GlassInstaller.uninstall(child);
                        else if (child.getBackground() instanceof GlassDrawable) {
                            child.setBackground(null);
                        }
                    }
                }
            }
            if (dragView instanceof View) {
                View dv = (View) dragView;
                GlassInstaller.setOverlaySource(dv, null);
                if (GlassInstaller.get(dv) != null) GlassInstaller.uninstall(dv);
                else if (dv.getBackground() instanceof GlassDrawable) dv.setBackground(null);
            }
        } catch (Throwable ignored) { }
    }

    private void hookFolderDragPreview(ClassLoader cl) {
        final String className = "com.android.launcher3.Workspace";
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("handleFolderBackground") || m.getParameterCount() != 2
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object source = chain.getArg(0);
                    Object dragView = chain.getArg(1);
                    // ColorOS copies LayerBlurDrawable onto the drag preview here. Skip that
                    // OEM blur layer entirely and let LiquidGlass own the dragged folder look.
                    if (enabled() && isFolderDragSource(source)) {
                        try {
                            Object content = invokeNoArgs(dragView, "getContentView");
                            // Never install folder-drag glass onto widget/card DragView content.
                            if (content instanceof View && isWidgetDragContent((View) content)) {
                                stripWidgetDragViewGlass(dragView);
                                return chain.proceed();
                            }
                            beginFolderDrag(source);
                            clearDragBlurProp(dragView);
                            if (content instanceof View) {
                                suppressLayerBlur((View) content);
                                GlassInstaller.installBackground((View) content, currentConfig());
                                // Keep glass opaque. Desktop icons under the folder are drawn
                                // live on the HW canvas in GlassDrawable (DesktopIconOverlay),
                                // not via translucent glass or software sample paste.
                                GlassDrawable glass = GlassInstaller.get((View) content);
                                if (glass != null) glass.setAlpha(255);
                                Object workspace = chain.getThisObject();
                                View seed = workspace instanceof View
                                        ? (View) workspace
                                        : desktopPageUnderDrag(workspace);
                                if (seed != null) {
                                    GlassInstaller.setOverlaySource((View) content, seed);
                                }
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + ".handleFolderBackground apply failed", e);
                        }
                        return null;
                    }
                    Object result = chain.proceed();
                    // Non-folder: strip any folder-drag glass that leaked onto the preview.
                    if (enabled() && dragView != null) {
                        stripWidgetDragViewGlass(dragView);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Folder drag preview hook unavailable", e);
        }

        // Belt: strip widget/card DragView glass on every onDragStart.
        try {
            for (String dragViewClass : new String[] {
                    "com.android.launcher3.dragndrop.OplusDragView",
                    "com.android.launcher3.dragndrop.DragView"
            }) {
                Class<?> c = Class.forName(dragViewClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!m.getName().equals("onDragStart") || m.getParameterCount() > 2) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (!enabled()) return result;
                            Object dragView = chain.getThisObject();
                            Object content = invokeNoArgs(dragView, "getContentView");
                            if (content instanceof View && isWidgetDragContent((View) content)) {
                                stripWidgetDragViewGlass(dragView);
                                if (folderDragActive && !isFolderDragSource(folderDragSource)) {
                                    folderDragActive = false;
                                    folderDragSource = null;
                                }
                            } else if (content instanceof View && !isFolderDragSource(folderDragSource)
                                    && GlassInstaller.get((View) content) != null
                                    && !folderDragActive) {
                                // Stale glass on a non-folder preview — drop it.
                                stripWidgetDragViewGlass(dragView);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, dragViewClass + ".onDragStart strip widget glass failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "Widget drag-start strip hook unavailable", e);
        }
    }

    /**
     * ColorOS applies wallpaper/background blur via OplusDepthController for TOGGLE_BAR /
     * PAGE_PREVIEW / OVERVIEW, and also during the swipe-up-to-Recents hand-follow spring
     * (SwipeToRecentAnimationHelper → createWallpaperBlurHandFollowAnim) while still in
     * NORMAL. Zero every wallpaper/icon blur write while the module is enabled so that
     * gesture transition never shows the frosted layer either.
     */
    private void hookFolderDragDepthBlur(ClassLoader cl) {
        try {
            Class<?> depth = Class.forName(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", false, cl);
            for (Method m : depth.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("setBlur") || name.equals("setBlurWithoutAnim")
                        || name.equals("setIconBlur") || name.equals("setIconBlurWithoutAnim"))) {
                    continue;
                }
                if (m.getParameterCount() < 1 || m.getParameterTypes()[0] != float.class) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    // Always clamp while enabled: gesture blur runs before OVERVIEW is settled.
                    if (enabled()) {
                        java.util.List<Object> list = chain.getArgs();
                        Object[] args = list.toArray(new Object[0]);
                        if (args.length > 0 && args[0] instanceof Number) {
                            args[0] = 0f;
                        }
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
            }
            // Folder open uses getFolderBlur()==1 in state handlers / onDraw recovery.
            for (Method m : depth.getDeclaredMethods()) {
                if (!m.getName().equals("getFolderBlur") || m.getParameterCount() != 0
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (!enabled()) return chain.proceed();
                    if (folderOpenActive || hasOpenFolder(findActiveLauncher())) return 0f;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "DepthController blur hook unavailable", e);
        }

        // Catch wallpaper blur writes that bypass OplusDepthController.setBlur (e.g. onDraw),
        // and rewrite hand-follow / state-switch blur animations used by swipe-to-Recents.
        try {
            Class<?> anim = Class.forName(
                    "com.android.quickstep.util.animation.DepthAnimImpl", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (name.equals("setWallpaperBlurWithoutAnim") || name.equals("setMirrorBlurWithoutAnim")
                        || name.equals("setIconBlurWithoutAnim")) {
                    if (m.getParameterCount() < 1 || m.getParameterTypes()[0] != float.class) continue;
                    hookOnce(m, chain -> {
                        if (enabled()) {
                            java.util.List<Object> list = chain.getArgs();
                            Object[] args = list.toArray(new Object[0]);
                            if (args.length > 0 && args[0] instanceof Number) {
                                args[0] = 0f;
                            }
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("stateSwitchForWallpaperBlur")
                        || name.equals("stateSwitchForMirrorBlur")) {
                    if (m.getParameterCount() < 2 || m.getParameterTypes()[1] != float.class) continue;
                    hookOnce(m, chain -> {
                        if (!enabled()) return chain.proceed();
                        java.util.List<Object> list = chain.getArgs();
                        Object[] args = list.toArray(new Object[0]);
                        args[1] = 0f;
                        return chain.proceed(args);
                    });
                } else if (name.equals("createWallpaperBlurHandFollowAnim")
                        || name.equals("createWallpaperBlurAnim")
                        || name.equals("createWallpaperBlurSpringAnim")
                        || name.equals("createIconBlurHandFollowAnim")
                        || name.equals("createIconBlurAnim")
                        || name.equals("createIconBlurSpringAnim")
                        || name.equals("createMirrorBlurAnim")
                        || name.equals("createMirrorBlurSpringAnim")) {
                    if (m.getParameterCount() < 1) continue;
                    hookOnce(m, chain -> {
                        if (enabled()) zeroAnimInfoEndValue(chain.getArg(0));
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "DepthAnimImpl wallpaper blur hook unavailable", e);
        }

        // Swipe-to-Recents hardcodes AnimInfo(..., 1f) and animateToFinalPosition(1f) while still
        // in NORMAL. Clamp those targets and force blur writes to 0 for the whole gesture.
        try {
            Class<?> helper = Class.forName(
                    "com.android.quickstep.touch.SwipeToRecentAnimationHelper", false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("createBlurAndDragLayerAlphaAnim")
                        || name.equals("doBackGroundAnim")
                        || name.equals("initState")
                        || name.equals("updatePaused"))) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) return result;
                    Object self = chain.getThisObject();
                    setField(self, "wallpaperBlurEndValue", 0f);
                    Object blurAnim = field(self, "mWallpaperBlurAnim");
                    invoke(blurAnim, "animateToFinalPosition",
                            new Class<?>[] { float.class }, 0f);
                    Object launcher = field(self, "mLauncher");
                    if (launcher != null) forceDepthBlur(launcher, 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "SwipeToRecent blur clamp unavailable", e);
        }

        // Keep state blur target at 0 in edit modes and Recents (Overview).
        for (String stateClass : new String[] {
                "com.android.launcher3.states.ToggleBarState",
                "com.android.launcher3.states.PagePreviewState",
                "com.android.launcher3.uioverrides.states.OverviewState",
                "com.android.launcher3.uioverrides.states.BackgroundAppState"
        }) {
            try {
                Class<?> c = Class.forName(stateClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("getBlurUnchecked") || m.getParameterCount() != 1
                            || m.isBridge() || m.isSynthetic()) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (enabled()) return 0f;
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, stateClass + ".getBlurUnchecked hook unavailable", e);
            }
        }

        after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "onDragStart", o -> {
            if (enabled()) beginFolderDrag(o);
        });
        after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "onDragEnd", o -> {
            endFolderDrag(o);
        });
        after("com.android.launcher3.dragndrop.DragController", cl, "endDrag", o -> {
            if (folderDragActive) endFolderDrag(field(o, "mActivity"));
        });
        after("com.android.launcher3.dragndrop.DragController", cl, "cancelDrag", o -> {
            if (folderDragActive) endFolderDrag(field(o, "mActivity"));
        });
        // Click-open / spring-load folder: FolderAnimUtil animates wallpaper blur 0->1; clear it.
        // Also invalidate ashmem so float-menu glass switches to the opened folder page.
        for (String folderClass : new String[] {
                "com.android.launcher3.folder.Folder",
                "com.android.launcher3.folder.OplusFolder"
        }) {
            try {
                Class<?> folder = Class.forName(folderClass, false, cl);
                for (Method m : folder.getDeclaredMethods()) {
                    String name = m.getName();
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!(name.equals("animateOpen")
                            || name.equals("beginExternalDrag")
                            || name.equals("onDropCompleted")
                            || name.equals("bind"))) {
                        continue;
                    }
                    if (name.equals("bind") && m.getParameterCount() == 0) continue;
                    final String hooked = name;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (!enabled()) return result;
                        try {
                            Object self = chain.getThisObject();
                            Object open = invokeNoArgs(self, "isOpen");
                            boolean isOpen = open instanceof Boolean ? (Boolean) open
                                    : Boolean.TRUE.equals(field(self, "mIsOpen"));
                            if (hooked.equals("animateOpen") || hooked.equals("beginExternalDrag")
                                    || isOpen) {
                                beginFolderOpen(self);
                                forceDepthBlur(self, 0f);
                                if (DesktopBackdropHub.isLive()) {
                                    DesktopBackdropSampler.invalidateCache();
                                }
                            }
                        } catch (Throwable e) {
                            log(5, TAG, folderClass + "." + hooked + " open glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, folderClass + " open hooks unavailable", e);
            }
        }
        // Long-press edit / Recents enter states with wallpaper blur=1; clear it.
        after("com.android.launcher3.statemanager.StateManager", cl, "goToState", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            syncPagePreviewFrameGlassForLauncher(activity);
            syncDesktopHomeValidForLauncher(activity);
        });
        after("com.android.launcher3.statemanager.StateManager", cl, "onStateTransitionEnd", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            syncPagePreviewFrameGlassForLauncher(activity);
            syncDesktopHomeValidForLauncher(activity);
        });
    }

    /**
     * Recents "清除" is a PressFeedbackButton that self-draws a solid/src fill. Replace that
     * chrome with LiquidGlass while keeping the label and press-scale feedback.
     */
    private void hookRecentsClearButton(ClassLoader cl) {
        after("com.oplus.quickstep.views.OplusClearAllPanelView", cl, "onFinishInflate", o -> {
            applyClearButtonGlass(o);
        });
        after("com.oplus.quickstep.views.OplusClearAllPanelView", cl, "onAttachedToWindow", o -> {
            applyClearButtonGlass(o);
        });
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
                    } catch (Throwable e) {
                        log(5, TAG, "PressFeedbackButton.setSrcDrawable glass reassert failed", e);
                    }
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
                    // OEM fills an opaque-ish rounded rect before text; zero the fill so glass shows.
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
            log(5, TAG, "PressFeedbackButton clear-glass hooks unavailable", e);
        }
    }

    /**
     * Recents "分屏" / "浮窗" chrome — two different surfaces:
     * <ol>
     *   <li>RapidReaction ({@code MultiTriggerPanelView}): capsules shown only while
     *       gesture-swiping up from an app into Overview.</li>
     *   <li>{@code OplusTaskMenuViewImpl}: ··· app-options popup (also lists 分屏/浮窗).</li>
     * </ol>
     * Phone header ImageButtons stay GONE without large-display features; still glass
     * them when VISIBLE on tablet / fold.
     */
    private void hookRecentsTaskShortcuts(ClassLoader cl) {
        hookRecentsRapidReactionGlass(cl);
        after("com.android.quickstep.views.OplusTaskMenuViewImpl", cl, "addMenuOptions",
                this::applyTaskMenuGlass);
        after("com.android.quickstep.views.OplusTaskMenuViewImpl", cl, "animateOpen",
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
                final String hooked = name;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        if (hooked.equals("animateOpenOrClosed")
                                && Boolean.TRUE.equals(chain.getArg(0))) {
                            return result;
                        }
                        applyTaskMenuGlass(chain.getThisObject());
                        if (hooked.equals("animateOpenOrClosed")) {
                            scheduleTaskMenuGlassSettle(chain.getThisObject());
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "OplusTaskMenuViewImpl." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusTaskMenuViewImpl glass hooks unavailable", e);
        }

        after("com.oplus.quickstep.views.OplusTaskHeaderView", cl, "onFinishInflate",
                this::applyTaskHeaderShortcutGlass);
        try {
            Class<?> header = Class.forName(
                    "com.oplus.quickstep.views.OplusTaskHeaderView", false, cl);
            for (Method m : header.getDeclaredMethods()) {
                if (!m.getName().equals("showWindowIcon") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof View) {
                            applyTaskHeaderShortcutButtonGlass((View) arg0);
                        }
                        applyTaskHeaderShortcutGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, "OplusTaskHeaderView.showWindowIcon glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusTaskHeaderView shortcut glass hooks unavailable", e);
        }
    }

    /**
     * From-app swipe RapidReaction capsules (分屏 / 浮窗). OEM paints opaque rounded rects
     * in {@code MultiRectangleBackgroundView}; replace those fills with LiquidGlass hosts
     * sized to each {@code RectPaint} and keep icon/title content above.
     */
    private void hookRecentsRapidReactionGlass(ClassLoader cl) {
        after("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", cl,
                "updateViewVisibility", this::applyRapidReactionGlass);
        after("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", cl,
                "onLayout", this::applyRapidReactionGlass);
        try {
            Class<?> panel = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", false, cl);
            for (Method m : panel.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.equals("updateAppSupportState")
                        || name.equals("updateProgress")
                        || name.equals("initAnimation")
                        || name.equals("setVisibility"))) {
                    continue;
                }
                final String hooked = name;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyRapidReactionGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, "MultiTriggerPanelView." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "MultiTriggerPanelView glass hooks unavailable", e);
        }
        try {
            Class<?> bg = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.MultiRectangleBackgroundView",
                    false, cl);
            for (Method m : bg.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    try {
                        if (enabled()) clearRapidReactionOpaqueFills(chain.getThisObject());
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "MultiRectangleBackgroundView.onDraw glass hook unavailable", e);
        }
    }

    private void applyRapidReactionGlass(Object panel) {
        if (!enabled() || !(panel instanceof ViewGroup)) return;
        final ViewGroup host = (ViewGroup) panel;
        if (host.getVisibility() != View.VISIBLE) return;
        try {
            Object bg = field(panel, "hintDoubleRectBackgroundView");
            if (!(bg instanceof View)) return;
            Object paints = field(bg, "mBgRectPaints");
            if (!(paints instanceof Iterable<?>)) return;
            float radius = readRapidReactionRadius((View) bg);
            clearRapidReactionOpaqueFills(bg);
            for (Object rectPaint : (Iterable<?>) paints) {
                if (rectPaint == null) continue;
                Object linked = invokeNoArgs(rectPaint, "getMLinkedEntranceType");
                if (!isRapidSplitOrFloat(linked)) continue;
                android.graphics.RectF rect = rapidPaintDrawRect(rectPaint);
                if (rect == null || rect.width() < 2f || rect.height() < 2f) continue;
                // RectPaint coords are local to MultiRectangleBackgroundView.
                rect.offset(((View) bg).getLeft(), ((View) bg).getTop());
                String typeKey = String.valueOf(linked);
                View glassHost = findOrCreateRapidGlassHost(host, (View) bg, typeKey);
                int prevW = glassHost.getWidth();
                int prevH = glassHost.getHeight();
                layoutRapidGlassHost(glassHost, rect);
                // Capsules sit under the live app surface — sample desktop/wallpaper only.
                // Never attach TaskView overlay (that regression was introduced in 0778e1c).
                GlassInstaller.setOverlaySource(glassHost, null);
                boolean firstInstall = GlassInstaller.get(glassHost) == null;
                GlassInstaller.installBackground(glassHost, currentConfig());
                GlassDrawable live = GlassInstaller.get(glassHost);
                if (live != null && radius > 0f) {
                    live.setCornerRadii(radius, radius, radius, radius);
                }
                boolean sizeChanged = glassHost.getWidth() != prevW || glassHost.getHeight() != prevH;
                if (firstInstall || sizeChanged) {
                    GlassInstaller.forceCapture(glassHost);
                }
                glassHost.invalidate();
            }
        } catch (Throwable e) {
            log(5, TAG, "applyRapidReactionGlass failed", e);
        }
    }

    private static boolean isRapidSplitOrFloat(Object selectionOptions) {
        if (selectionOptions == null) return false;
        String name = selectionOptions.toString();
        return name.contains("SPLIT_WINDOW") || name.contains("FLOATING_WINDOW");
    }

    private static void clearRapidReactionOpaqueFills(Object bgView) {
        Object paints = field(bgView, "mBgRectPaints");
        if (!(paints instanceof Iterable<?>)) return;
        for (Object rectPaint : (Iterable<?>) paints) {
            if (rectPaint == null) continue;
            Object linked = invokeNoArgs(rectPaint, "getMLinkedEntranceType");
            if (!isRapidSplitOrFloat(linked)) continue;
            Object paint = invokeNoArgs(rectPaint, "getMPaint");
            if (paint instanceof android.graphics.Paint) {
                ((android.graphics.Paint) paint).setColor(0);
            }
        }
    }

    private static android.graphics.RectF rapidPaintDrawRect(Object rectPaint) {
        Object draw = field(rectPaint, "mDrawRect");
        if (draw instanceof android.graphics.RectF) {
            android.graphics.RectF r = (android.graphics.RectF) draw;
            if (r.width() > 1f && r.height() > 1f) return new android.graphics.RectF(r);
        }
        Object base = invokeNoArgs(rectPaint, "getMBaseRect");
        if (!(base instanceof android.graphics.RectF)) base = field(rectPaint, "mBaseRect");
        if (!(base instanceof android.graphics.RectF)) return null;
        android.graphics.RectF rect = new android.graphics.RectF((android.graphics.RectF) base);
        Object tx = invokeNoArgs(rectPaint, "getMTranslationX");
        Object ty = invokeNoArgs(rectPaint, "getMTranslationY");
        float ox = tx instanceof Number ? ((Number) tx).floatValue() : 0f;
        float oy = ty instanceof Number ? ((Number) ty).floatValue() : 0f;
        rect.offset(ox, oy);
        Object sx = invokeNoArgs(rectPaint, "getMScaleX");
        Object sy = invokeNoArgs(rectPaint, "getMScaleY");
        float scaleX = sx instanceof Number ? ((Number) sx).floatValue() : 1f;
        float scaleY = sy instanceof Number ? ((Number) sy).floatValue() : 1f;
        if (Math.abs(scaleX - 1f) > 0.001f || Math.abs(scaleY - 1f) > 0.001f) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            float w = rect.width() * scaleX;
            float h = rect.height() * scaleY;
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }
        return rect;
    }

    private static final String RAPID_GLASS_TAG_PREFIX = "colg_rapid_";

    private static View findOrCreateRapidGlassHost(ViewGroup panel, View bgView, String typeKey) {
        String tag = RAPID_GLASS_TAG_PREFIX + typeKey;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child != null && tag.equals(child.getTag())) return child;
        }
        android.widget.FrameLayout host = new android.widget.FrameLayout(panel.getContext());
        host.setTag(tag);
        host.setClickable(false);
        host.setFocusable(false);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int index = panel.indexOfChild(bgView);
        if (index >= 0) panel.addView(host, index + 1);
        else panel.addView(host, 0);
        return host;
    }

    private static void layoutRapidGlassHost(View host, android.graphics.RectF rect) {
        int left = Math.round(rect.left);
        int top = Math.round(rect.top);
        int right = Math.round(rect.right);
        int bottom = Math.round(rect.bottom);
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        ViewGroup.LayoutParams lp = host.getLayoutParams();
        if (lp == null) {
            host.setLayoutParams(new ViewGroup.LayoutParams(width, height));
        } else {
            lp.width = width;
            lp.height = height;
            host.setLayoutParams(lp);
        }
        host.layout(left, top, left + width, top + height);
        host.setVisibility(View.VISIBLE);
    }

    private static float readRapidReactionRadius(View view) {
        try {
            int id = view.getResources().getIdentifier(
                    "rapid_reaction_double_rect_background_smooth_corner_radius",
                    "dimen", view.getContext().getPackageName());
            if (id != 0) return view.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 9f * view.getResources().getDisplayMetrics().density;
    }

    private View findRunningTaskViewForOverlay(Object panelOrMenu) {
        try {
            Object swipeRef = field(panelOrMenu, "swipeUpHandlerRef");
            Object swipe = swipeRef instanceof java.lang.ref.WeakReference
                    ? ((java.lang.ref.WeakReference<?>) swipeRef).get()
                    : null;
            Object recents = swipe != null ? field(swipe, "mRecentsView") : null;
            if (recents == null) recents = invokeNoArgs(swipe, "getRecentsView");
            if (recents == null) {
                Object launcher = findActiveLauncher();
                recents = invokeNoArgs(launcher, "getOverviewPanel");
            }
            Object running = invokeNoArgs(recents, "getRunningTaskView");
            if (running instanceof View) return (View) running;
            Object current = invokeNoArgs(recents, "getCurrentPageTaskView");
            if (current instanceof View) return (View) current;
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Menu open anim scales 0.9→1 over ~400ms. Re-bake protect and forceCapture when it
     * lands so the glass sample is not left at a ~0.99-scale freeze (upward jump / shrink).
     */
    private void scheduleTaskMenuGlassSettle(Object menu) {
        if (!(menu instanceof View)) return;
        final View host = (View) menu;
        Object listObj = field(menu, "mListView");
        final View glassHost = listObj instanceof View ? (View) listObj : host;
        final View taskView = resolveTaskMenuTaskView(menu);
        Runnable settle = new Runnable() {
            int tries = 0;
            @Override public void run() {
                try {
                    if (!enabled() || !glassHost.isAttachedToWindow()) return;
                    if ((Math.abs(host.getScaleX() - 1f) > 0.02f
                            || Math.abs(host.getScaleY() - 1f) > 0.02f)
                            && tries++ < 12) {
                        host.postDelayed(this, 50L);
                        return;
                    }
                    if (taskView != null) {
                        TaskContentOverlay.clearProtectCache(taskView);
                        TaskContentOverlay.prebakeProtect(taskView);
                    }
                    GlassInstaller.forceCapture(glassHost);
                    glassHost.invalidate();
                } catch (Throwable t) {
                    log(5, TAG, "task menu glass settle failed", t);
                }
            }
        };
        host.postDelayed(settle, 400L);
    }

    /** LiquidGlass behind the Recents ··· popup that also lists 分屏 / 浮窗. */
    private void applyTaskMenuGlass(Object menu) {
        if (!enabled() || !(menu instanceof View)) return;
        final View host = (View) menu;
        try {
            Object listObj = field(menu, "mListView");
            final View listView = listObj instanceof View ? (View) listObj : null;
            clearTaskMenuOpaqueChrome(menu, listView);

            // Prefer ListView as glass host: it owns the rounded popup panel bounds.
            final View glassHost = listView != null ? listView : host;
            if (listView != null && host.getBackground() instanceof GlassDrawable) {
                // Avoid double glass if we previously installed on the LinearLayout wrapper.
                GlassInstaller.uninstall(host);
            }
            if (host != glassHost) host.setBackground(null);

            GlassInstaller.installBackground(glassHost, currentConfig());
            View taskView = resolveTaskMenuTaskView(menu);
            if (taskView != null) {
                GlassInstaller.setOverlaySource(glassHost, taskView);
                // Bake protect content-layer BEFORE open animation so glass never shows an
                // empty/wrong plate over the real mask (ghosting) or rebakes at expand-max.
                TaskContentOverlay.prebakeProtect(taskView);
            }
            final View taskOverlay = taskView;
            Runnable refresh = () -> {
                clearTaskMenuOpaqueChrome(menu, listView);
                if (!(glassHost.getBackground() instanceof GlassDrawable)) {
                    GlassInstaller.installBackground(glassHost, currentConfig());
                }
                GlassDrawable live = GlassInstaller.get(glassHost);
                if (live == null) return;
                float radius = readTaskMenuRadius(glassHost);
                if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
                if (glassHost.getWidth() <= 0 || glassHost.getHeight() <= 0) return;
                if (taskOverlay != null) {
                    GlassInstaller.setOverlaySource(glassHost, taskOverlay);
                }
                GlassInstaller.forceCapture(glassHost);
                glassHost.invalidate();
                log(4, TAG, "TaskMenu glass ready size="
                        + glassHost.getWidth() + "x" + glassHost.getHeight()
                        + " radius=" + radius
                        + " taskOverlay=" + (taskOverlay != null)
                        + " host=" + glassHost.getClass().getSimpleName());
            };
            if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
                refresh.run();
                // One follow-up after first layout — protect cache is locked so this cannot jump.
                glassHost.post(refresh);
            } else {
                glassHost.post(refresh);
                glassHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (v.getWidth() > 0 && v.getHeight() > 0) {
                            v.removeOnLayoutChangeListener(this);
                            if (taskOverlay != null) {
                                TaskContentOverlay.prebakeProtect(taskOverlay);
                            }
                            refresh.run();
                        }
                    }
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "applyTaskMenuGlass failed", e);
        }
    }

    private View resolveTaskMenuTaskView(Object menu) {
        Object taskView = field(menu, "mTaskView");
        if (taskView instanceof View) return (View) taskView;
        Object via = invokeNoArgs(menu, "getTaskView");
        if (via instanceof View) return (View) via;
        Object container = field(menu, "mTaskContainer");
        Object fromContainer = invokeNoArgs(container, "getTaskView");
        return fromContainer instanceof View ? (View) fromContainer : null;
    }

    /**
     * Strip opaque OEM chrome that sits above LiquidGlass: ListView popup drawable,
     * RoundFrameLayout white fill, and the #ebebeb group divider between 分屏 and 锁定.
     */
    private void clearTaskMenuOpaqueChrome(Object menu, View listView) {
        if (listView != null) {
            // Never wipe an already-installed GlassDrawable — that left a clear hole
            // showing only the TaskView content underneath.
            if (!(listView.getBackground() instanceof GlassDrawable)) {
                listView.setBackground(null);
            }
            Object parent = listView.getParent();
            if (parent instanceof View) {
                View roundFrame = (View) parent;
                if (!(roundFrame.getBackground() instanceof GlassDrawable)) {
                    roundFrame.setBackground(null);
                }
                try {
                    // clipMode 1 forces setBackgroundColor(-1); keep clip but no opaque fill.
                    invoke(roundFrame, "setClipMode", new Class<?>[] { int.class }, 0);
                } catch (Throwable ignored) { }
                if (!(roundFrame.getBackground() instanceof GlassDrawable)) {
                    roundFrame.setBackground(null);
                }
            }
            if (listView instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) listView;
                clearTaskMenuDividerFills(group);
                if (group.getTag() != TAG_TASK_MENU_HIERARCHY) {
                    group.setTag(TAG_TASK_MENU_HIERARCHY);
                    group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                        @Override
                        public void onChildViewAdded(View parent, View child) {
                            clearTaskMenuDividerFillsIn(child);
                        }

                        @Override
                        public void onChildViewRemoved(View parent, View child) { }
                    });
                }
            }
        }
        if (menu instanceof View) {
            View menuView = (View) menu;
            // OEM solid shape on the wrapper would flash white around the ListView glass.
            if (!(menuView.getBackground() instanceof GlassDrawable)) {
                menuView.setBackground(null);
            }
        }
    }

    private static final Object TAG_TASK_MENU_HIERARCHY = new Object();

    private static void clearTaskMenuDividerFills(ViewGroup listView) {
        for (int i = 0; i < listView.getChildCount(); i++) {
            clearTaskMenuDividerFillsIn(listView.getChildAt(i));
        }
    }

    private static void clearTaskMenuDividerFillsIn(View row) {
        if (row == null) return;
        View groupDivider = findDescendantByIdName(row, "menu_divider");
        if (groupDivider != null) {
            groupDivider.setBackground(null);
            if (groupDivider instanceof ImageView) {
                ((ImageView) groupDivider).setImageDrawable(null);
            }
            groupDivider.setVisibility(View.INVISIBLE);
        }
        View itemDivider = findDescendantByIdName(row, "divider");
        if (itemDivider != null) {
            itemDivider.setBackground(null);
            if (itemDivider instanceof ImageView) {
                ((ImageView) itemDivider).setImageDrawable(null);
            }
        }
    }

    private static View findDescendantByIdName(View root, String idName) {
        if (root == null || idName == null) return null;
        try {
            int id = root.getResources().getIdentifier(
                    idName, "id", root.getContext().getPackageName());
            if (id != 0) {
                View found = root.findViewById(id);
                if (found != null) return found;
            }
        } catch (Throwable ignored) { }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findDescendantByIdName(group.getChildAt(i), idName);
            if (found != null) return found;
        }
        return null;
    }

    private static float readTaskMenuRadius(View view) {
        try {
            int id = view.getResources().getIdentifier(
                    "coui_round_corner_m", "dimen", view.getContext().getPackageName());
            if (id != 0) return view.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 12f * view.getResources().getDisplayMetrics().density;
    }

    /**
     * Live float app-options menus run in SystemUI (WM Shell), not Launcher:
     * {@code FlexibleMenuManager} → {@code COUIIsolatedPopupListWindow}
     * ({@code RoundFrameLayout mMainMenuWrapper}).
     * Split-screen menus are intentionally skipped for now.
     */
    private void hookSystemUiFloatMenus(ClassLoader cl) {
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "showPopupWindowMenu",
                this::scheduleFlexibleMenuGlass);
        // showPopupWindowMenu only posts work; glass after the real addView lambda.
        try {
            Class<?> mgr = Class.forName(
                    "com.oplus.flexibletask.menu.FlexibleMenuManager", false, cl);
            for (Method m : mgr.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || !name.contains("showPopupWindowMenu")
                        || name.equals("showPopupWindowMenu")) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyFlexibleMenuGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, "FlexibleMenuManager." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "FlexibleMenuManager lambda glass hooks unavailable", e);
        }
        // Backup: prepareShowMainMenu runs right before WM.addView for float menus.
        after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "prepareShowMainMenu",
                this::scheduleCouiPopupListGlass);
        // Re-bind DESKTOP/APP when the float bounds update while the menu is open.
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "updateInfo", manager -> {
            try {
                if (!DesktopBackdropClient.isReady()) return;
                Object popup = field(manager, "mPopupWindow");
                View wrapper = asView(field(popup, "mMainMenuWrapper"));
                if (wrapper == null || !wrapper.isAttachedToWindow()) return;
                if (!BehindDisplayCapture.isSysUiMenuGlass(wrapper)) return;
                bindFloatMenuCapture(wrapper, manager);
                GlassInstaller.forceCapture(wrapper);
            } catch (Throwable ignored) { }
        });
        // Dismiss / hide must cancel delayed glass posts and STOP Launcher ashmem publish.
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "dismiss",
                this::stopFloatMenuAshmem);
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "hidePopupWindow",
                this::stopFloatMenuAshmem);
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "hide",
                this::stopFloatMenuAshmem);
        after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "dismiss",
                this::stopFloatMenuAshmem);
        after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "dismissAllowingStateLoss",
                this::stopFloatMenuAshmem);
        after("android.widget.PopupWindow", cl, "dismiss", popup -> {
            try {
                if (popup != null && popup.getClass().getName().contains("COUIIsolatedPopupListWindow")) {
                    stopFloatMenuAshmem(popup);
                }
            } catch (Throwable ignored) { }
        });
    }

    private void cancelPendingFloatMenuGlass() {
        synchronized (pendingFloatMenuGlass) {
            for (Runnable r : pendingFloatMenuGlass) {
                mainHandler.removeCallbacks(r);
            }
            pendingFloatMenuGlass.clear();
        }
    }

    private void trackFloatMenuGlass(Runnable r) {
        synchronized (pendingFloatMenuGlass) {
            pendingFloatMenuGlass.add(r);
        }
    }

    private void stopFloatMenuAshmem(Object ignored) {
        try {
            cancelPendingFloatMenuGlass();
            BehindDisplayCapture.stopAllSessions();
        } catch (Throwable t) {
            log(5, TAG, "stopFloatMenuAshmem failed", t);
        }
    }

    private void scheduleFlexibleMenuGlass(Object manager) {
        if (!enabled() || manager == null) return;
        cancelPendingFloatMenuGlass();
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable apply = () -> {
            try {
                if (DesktopBackdropClient.sessionEpoch() != epoch) return;
                applyFlexibleMenuGlass(manager);
            } catch (Throwable e) {
                log(5, TAG, "applyFlexibleMenuGlass failed", e);
            }
        };
        trackFloatMenuGlass(apply);
        mainHandler.post(apply);
        mainHandler.postDelayed(apply, 80L);
        mainHandler.postDelayed(apply, 220L);
        mainHandler.postDelayed(apply, 480L);
    }

    private void applyFlexibleMenuGlass(Object manager) {
        if (!enabled() || manager == null) return;
        Object popup = field(manager, "mPopupWindow");
        if (popup == null) popup = invokeNoArgs(manager, "getPopupWindow");
        applyCouiPopupListGlass(popup, manager);
    }

    private void scheduleCouiPopupListGlass(Object popup) {
        if (!enabled() || popup == null) return;
        cancelPendingFloatMenuGlass();
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable apply = () -> {
            try {
                if (DesktopBackdropClient.sessionEpoch() != epoch) return;
                applyCouiPopupListGlass(popup, null);
            } catch (Throwable e) {
                log(5, TAG, "applyCouiPopupListGlass failed", e);
            }
        };
        trackFloatMenuGlass(apply);
        mainHandler.post(apply);
        mainHandler.postDelayed(apply, 80L);
        mainHandler.postDelayed(apply, 220L);
    }

    private void applyCouiPopupListGlass(Object popup, Object flexibleManager) {
        if (!enabled() || popup == null) return;
        View resolvedWrapper = asView(field(popup, "mMainMenuWrapper"));
        View resolvedList = asView(field(popup, "mMainListView"));
        if (resolvedList == null) resolvedList = asView(invokeNoArgs(popup, "getMainMenuListView"));
        if (resolvedWrapper == null && resolvedList != null) {
            Object parent = resolvedList.getParent();
            if (parent instanceof View) resolvedWrapper = (View) parent;
        }
        if (resolvedWrapper == null) {
            View content = asView(invokeNoArgs(popup, "getContentView"));
            if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
                View child = ((ViewGroup) content).getChildAt(0);
                if (child != null && child.getClass().getName().contains("RoundFrameLayout")) {
                    resolvedWrapper = child;
                }
            }
        }
        final View wrapper = resolvedWrapper;
        final View listView = resolvedList;
        clearCouiPopupOpaqueChrome(wrapper, listView);
        final View glassHost = wrapper != null ? wrapper : listView;
        if (glassHost == null) return;
        if (!glassHost.isAttachedToWindow()) return;
        BehindDisplayCapture.tagHost(glassHost);
        if (listView != null && listView != glassHost
                && listView.getBackground() instanceof GlassDrawable) {
            GlassInstaller.uninstall(listView);
            listView.setBackground(null);
        }
        bindFloatMenuCapture(glassHost, flexibleManager);
        try { glassHost.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignored) { }
        GlassInstaller.installBackground(glassHost, currentConfig());
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable refresh = () -> {
            if (DesktopBackdropClient.sessionEpoch() != epoch) return;
            if (!glassHost.isAttachedToWindow()) return;
            clearCouiPopupOpaqueChrome(wrapper, listView);
            if (!(glassHost.getBackground() instanceof GlassDrawable)) {
                GlassInstaller.installBackground(glassHost, currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(glassHost);
            if (live == null) return;
            float radius = readCouiPopupRadius(glassHost);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            bindFloatMenuCapture(glassHost, flexibleManager);
            if (glassHost.getWidth() <= 0 || glassHost.getHeight() <= 0) return;
            GlassInstaller.forceCapture(glassHost);
            glassHost.invalidate();
            log(4, TAG, "Flexible/COUI popup glass ready size="
                    + glassHost.getWidth() + "x" + glassHost.getHeight()
                    + " radius=" + radius
                    + " host=" + glassHost.getClass().getSimpleName());
        };
        trackFloatMenuGlass(refresh);
        scheduleGlassRefresh(glassHost, refresh);
    }

    /**
     * Ashmem only when the menu opens above the float <b>and</b> the launcher is visible
     * under/around the float. Otherwise {@code captureDisplay} (same as downward menu).
     */
    private void bindFloatMenuCapture(View glassHost, Object flexibleManager) {
        if (glassHost == null || !glassHost.isAttachedToWindow()) return;
        try {
            if (flexibleManager == null) {
                BehindDisplayCapture.bindApp(glassHost, null);
                return;
            }
            Object boundsObj = field(flexibleManager, "mCurrentBounds");
            android.graphics.Rect floatBounds = boundsObj instanceof android.graphics.Rect
                    ? new android.graphics.Rect((android.graphics.Rect) boundsObj) : null;
            if (floatBounds != null && floatBounds.isEmpty()) floatBounds = null;

            boolean above = floatBounds != null
                    && isFloatMenuAbove(glassHost, floatBounds, flexibleManager);
            boolean launcherUnder = isLauncherUnderFloat(glassHost, flexibleManager);
            // Prefer Launcher ashmem while home or Overview is showing. PAGE_PREVIEW /
            // TOGGLE_BAR clear desktopHomeValid so we fall back to captureDisplay.
            boolean homeDesktop = launcherUnder && DesktopBackdropClient.isDesktopHomeValid();

            if (above && homeDesktop) {
                android.graphics.Rect maxMenu = computeMaxMenuRegion(
                        glassHost, flexibleManager, floatBounds, true);
                if (maxMenu == null || maxMenu.isEmpty()) {
                    BehindDisplayCapture.bindApp(glassHost, floatBounds);
                    log(4, TAG, "float menu capture APP (no max menu region)");
                    return;
                }
                BehindDisplayCapture.bindDesktop(glassHost, floatBounds, maxMenu, true);
                log(4, TAG, "float menu capture DESKTOP region=" + maxMenu.toShortString());
            } else {
                BehindDisplayCapture.bindApp(glassHost, floatBounds);
                log(4, TAG, "float menu capture APP above=" + above
                        + " launcherUnder=" + launcherUnder
                        + " homeDesktop=" + homeDesktop);
            }
        } catch (Throwable t) {
            log(5, TAG, "bindFloatMenuCapture failed", t);
        }
    }

    /** True when home/launcher is visible behind the float (ashmem path is meaningful). */
    private static boolean isLauncherUnderFloat(View host, Object flexibleManager) {
        if (isFloatTaskLauncher(flexibleManager)) return true;
        if (host == null) return false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    host.getContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            @SuppressWarnings("deprecation")
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(12);
            if (tasks == null) return false;
            for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null) continue;
                android.content.ComponentName top = task.topActivity;
                if (top == null) top = task.baseActivity;
                if (top != null && isLauncherPackage(top.getPackageName())) {
                    try {
                        if (task.isVisible()) return true;
                    } catch (Throwable ignored) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isFloatTaskLauncher(Object flexibleManager) {
        if (flexibleManager == null) return false;
        try {
            Object info = field(flexibleManager, "mTaskInfo");
            if (info == null) return false;
            Object top = field(info, "topActivity");
            if (!(top instanceof android.content.ComponentName)) {
                top = field(info, "realActivity");
            }
            if (!(top instanceof android.content.ComponentName)) {
                top = field(info, "baseActivity");
            }
            if (top instanceof android.content.ComponentName) {
                return isLauncherPackage(((android.content.ComponentName) top).getPackageName());
            }
            Object intent = field(info, "baseIntent");
            if (intent instanceof android.content.Intent) {
                android.content.ComponentName cn = ((android.content.Intent) intent).getComponent();
                if (cn != null) return isLauncherPackage(cn.getPackageName());
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isLauncherPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.launcher")
                || pkg.equals("com.android.launcher3")
                || pkg.equals("com.oppo.launcher")
                || pkg.equals("com.oplus.launcher")
                || pkg.equals("com.coloros.launcher");
    }

    /**
     * Fixed max menu screen rect for one OPEN signal.
     * Prefer OEM mMenuWidth/Height + float anchor (stable) over animated host bounds so the
     * ashmem region matches 1:1 screen pixels and does not skew aspect.
     */
    private static android.graphics.Rect computeMaxMenuRegion(View glassHost, Object flexibleManager,
            android.graphics.Rect floatBounds, boolean above) {
        int mw = 0;
        int mh = 0;
        Object wObj = field(flexibleManager, "mMenuWidth");
        Object hObj = field(flexibleManager, "mMenuHeight");
        if (wObj instanceof Integer) mw = (Integer) wObj;
        if (hObj instanceof Integer) mh = (Integer) hObj;
        if (mw <= 0 && glassHost.getWidth() > 0) mw = glassHost.getWidth();
        if (mh <= 0 && glassHost.getHeight() > 0) mh = glassHost.getHeight();
        if (mw <= 0 || mh <= 0) return null;

        float density = glassHost.getResources().getDisplayMetrics().density;
        int screenW = glassHost.getResources().getDisplayMetrics().widthPixels;
        int screenH = glassHost.getResources().getDisplayMetrics().heightPixels;

        // Prefer laid-out host origin when size already matches OEM max (post-animation).
        if (glassHost.isAttachedToWindow() && glassHost.getWidth() > 0 && glassHost.getHeight() > 0
                && Math.abs(glassHost.getWidth() - mw) <= 2
                && Math.abs(glassHost.getHeight() - mh) <= 2) {
            int[] loc = new int[2];
            glassHost.getLocationOnScreen(loc);
            return new android.graphics.Rect(loc[0], loc[1], loc[0] + mw, loc[1] + mh);
        }

        if (floatBounds == null || floatBounds.isEmpty()) {
            if (glassHost.isAttachedToWindow() && glassHost.getWidth() > 0) {
                int[] loc = new int[2];
                glassHost.getLocationOnScreen(loc);
                return new android.graphics.Rect(loc[0], loc[1], loc[0] + mw, loc[1] + mh);
            }
            return null;
        }

        int left = Math.max(0, Math.min(screenW - mw, floatBounds.centerX() - mw / 2));
        int top;
        if (above) {
            int gap = Math.round(8f * density);
            top = Math.max(0, floatBounds.top - gap - mh);
        } else {
            top = floatBounds.top + Math.round(8f * density);
        }
        if (top + mh > screenH) top = Math.max(0, screenH - mh);
        return new android.graphics.Rect(left, top, left + mw, top + mh);
    }

    /**
     * Default: menu anchors at {@code float.top + 8dp} and opens downward over the float.
     * It flips above when there is not enough room below that anchor. Never treat an
     * unlaid-out host at (0,0) as "above" — that sticky-locked DESKTOP and blanked glass.
     */
    private static boolean isFloatMenuAbove(View glassHost, android.graphics.Rect floatBounds,
            Object flexibleManager) {
        if (glassHost == null || floatBounds == null || floatBounds.isEmpty()) return false;
        float density = glassHost.getResources().getDisplayMetrics().density;
        int screenH = glassHost.getResources().getDisplayMetrics().heightPixels;
        int menuH = 0;
        Object menuHObj = field(flexibleManager, "mMenuHeight");
        if (menuHObj instanceof Integer) menuH = (Integer) menuHObj;
        int anchorY = floatBounds.top + Math.round(8f * density);
        int bottomMargin = Math.round(16f * density);
        if (menuH > 0 && anchorY + menuH > screenH - bottomMargin) return true;

        int w = glassHost.getWidth();
        int h = glassHost.getHeight();
        if (w <= 0 || h <= 0 || !glassHost.isAttachedToWindow()) return false;
        int[] loc = new int[2];
        glassHost.getLocationOnScreen(loc);
        if (loc[1] == 0 && loc[0] == 0 && floatBounds.top > h) return false;
        return loc[1] < floatBounds.top;
    }

    private void clearCouiPopupOpaqueChrome(View wrapper, View listView) {
        if (listView != null && !(listView.getBackground() instanceof GlassDrawable)) {
            listView.setBackground(null);
        }
        if (wrapper != null && !(wrapper.getBackground() instanceof GlassDrawable)) {
            try {
                invoke(wrapper, "setClipMode", new Class<?>[] { int.class }, 0);
            } catch (Throwable ignored) { }
            if (!(wrapper.getBackground() instanceof GlassDrawable)) {
                wrapper.setBackground(null);
            }
        }
    }

    private void scheduleGlassRefresh(View glassHost, Runnable refresh) {
        if (glassHost == null || refresh == null) return;
        if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
            refresh.run();
            glassHost.post(refresh);
            glassHost.postDelayed(refresh, 120L);
            glassHost.postDelayed(refresh, 400L);
        } else {
            glassHost.post(refresh);
            glassHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (v.getWidth() > 0 && v.getHeight() > 0) {
                        v.removeOnLayoutChangeListener(this);
                        refresh.run();
                        v.postDelayed(refresh, 120L);
                        v.postDelayed(refresh, 400L);
                    }
                }
            });
        }
    }

    private static View asView(Object obj) {
        return obj instanceof View ? (View) obj : null;
    }

    private static float readCouiPopupRadius(View view) {
        try {
            int id = view.getResources().getIdentifier(
                    "coui_round_corner_m", "dimen", "com.support.appcompat");
            if (id == 0) {
                id = view.getResources().getIdentifier(
                        "coui_round_corner_m", "dimen", view.getContext().getPackageName());
            }
            if (id != 0) return view.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 16f * view.getResources().getDisplayMetrics().density;
    }

    private void applyTaskHeaderShortcutGlass(Object header) {
        if (!enabled() || header == null) return;
        applyTaskHeaderShortcutButtonGlass(field(header, "splitWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(invokeNoArgs(header, "getSplitWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(field(header, "miniWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(invokeNoArgs(header, "getMiniWindowBtn"));
    }

    /**
     * Large-display header 分屏 / 浮窗 ImageButtons. Skip GONE hosts — phones keep them
     * invisible forever behind {@code hasLargeDisplayFeatures()}.
     */
    private void applyTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!enabled() || !(buttonObj instanceof ImageView)) return;
        final View button = (View) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        try {
            GlassInstaller.installBackground(button, currentConfig());
            Runnable refresh = () -> {
                if (button.getVisibility() != View.VISIBLE) return;
                GlassDrawable live = GlassInstaller.get(button);
                if (live == null) return;
                float radius = Math.min(button.getWidth(), button.getHeight()) / 2f;
                if (radius <= 0f) {
                    radius = 14f * button.getResources().getDisplayMetrics().density;
                }
                live.setCornerRadii(radius, radius, radius, radius);
                if (button.getWidth() <= 0 || button.getHeight() <= 0) return;
                GlassInstaller.forceCapture(button);
                button.invalidate();
                log(4, TAG, "TaskHeader shortcut glass ready "
                        + button.getClass().getSimpleName()
                        + " size=" + button.getWidth() + "x" + button.getHeight());
            };
            if (button.getWidth() > 0 && button.getHeight() > 0) refresh.run();
            else {
                button.post(refresh);
                button.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (v.getVisibility() == View.VISIBLE
                                && v.getWidth() > 0 && v.getHeight() > 0) {
                            v.removeOnLayoutChangeListener(this);
                            refresh.run();
                        }
                    }
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "applyTaskHeaderShortcutButtonGlass failed", e);
        }
    }

    /**
     * ToggleBar chrome: main menu circles (插件 / 壁纸与个性化 / …), top toolbar buttons
     * (完成 / 取消 / 添加卡片…), and bottom page-preview thumbnails that can be dragged.
     */
    private void hookToggleBarChrome(ClassLoader cl) {
        after("com.android.launcher.togglebar.views.PressFeedbackLinearLayout", cl, "onFinishInflate",
                this::applyToggleBarItemGlass);
        after("com.android.launcher.togglebar.views.PressFeedbackLinearLayout", cl, "onAttachedToWindow",
                this::applyToggleBarItemGlass);
        after("com.android.launcher.togglebar.views.ToggleStateToolbar", cl, "onFinishInflate",
                this::applyToggleBarToolbarGlass);
        after("com.android.launcher.togglebar.views.ToggleStateToolbar", cl, "onAttachedToWindow",
                this::applyToggleBarToolbarGlass);
        hookPagePreviewFrameGlass(cl);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarMainUIAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        applyToggleBarItemGlass(field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        log(5, TAG, "ToggleBarMainUIAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "ToggleBarMainUIAdapter glass hook unavailable", e);
        }
        // OEM fills are redrawn every frame; skip them only when glass owns that view's chrome.
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
                        if (view instanceof View && (GlassInstaller.get((View) view) != null
                                || (isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)))) {
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PressFeedbackHandler glass fill skip unavailable", e);
        }
    }

    /**
     * Page-preview thumbnails (drawPressedColor frosted fill) + page-indicator expanded pill
     * (LayerBlur TYPE_PAGE_INDICATOR). Thumbnails install/remove with PAGE_PREVIEW / TOGGLE_BAR.
     * Indicator glass is held through the exit fade — including the blank-page dot shrink that
     * happens after the launcher is already back on NORMAL.
     */
    private void hookPagePreviewFrameGlass(ClassLoader cl) {
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateEnabled",
                this::applyPagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateTransitionEnd",
                this::applyPagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateDisabled",
                this::removePagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateDisableTransitionEnd",
                this::removePagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewListContainer", cl, "onPagePreviewStateEnable",
                this::applyPagePreviewListGlass);
        after("com.android.launcher.pagepreview.PagePreviewListContainer", cl, "onPagePreviewStateDisable",
                this::removePagePreviewListGlass);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled() || !isPagePreviewChromeActive(chain.getThisObject())) return result;
                        Object holder = chain.getArg(0);
                        applyPagePreviewItemGlass(field(holder, "itemView"));
                        applyPagePreviewItemGlass(field(holder, "mTwoPanelView1"));
                        applyPagePreviewItemGlass(field(holder, "mTwoPanelView2"));
                        applyPagePreviewItemGlass(invokeNoArgs(holder, "getMTwoPanelView1"));
                        applyPagePreviewItemGlass(invokeNoArgs(holder, "getMTwoPanelView2"));
                    } catch (Throwable e) {
                        log(5, TAG, "PagePreviewAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PagePreviewAdapter glass hook unavailable", e);
        }
        try {
            Class<?> preview = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewItemView", false, cl);
            for (Method m : preview.getDeclaredMethods()) {
                if (!m.getName().equals("onDragExit") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    if (self instanceof View) GlassInstaller.uninstall((View) self);
                    if (enabled() && isPagePreviewChromeActive(self)) {
                        applyPagePreviewItemGlass(self);
                    }
                    return result;
                });
            }
            for (Method m : preview.getDeclaredMethods()) {
                if (!m.getName().equals("onLayout") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (enabled() && isPagePreviewChromeActive(chain.getThisObject())) {
                        applyPagePreviewItemGlass(chain.getThisObject());
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PagePreviewItemView glass hooks unavailable", e);
        }
        try {
            Class<?> anim = Class.forName(
                    "com.android.launcher.pageindicators.PageIndicatorAnimHelper", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("startPressDragging") || name.equals("cancelPressDragging")
                        || name.equals("startIndicatorBgAnim") || name.equals("reverseIndicatorBgAnim")
                        || name.equals("startPageNumChangeAnim") || name.equals("startIndicatorDotAnim"))
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object helper = chain.getThisObject();
                    Object indicator = field(helper, "mPageIndicator");
                    try {
                        if (name.equals("startIndicatorDotAnim")) {
                            // OEM forces mBgAlpha=0 then animates 255→0 while shrinking dots.
                            // Hold glass across that dip or blur flashes on the reappear frame.
                            pageIndicatorFrameActive = false;
                            pageIndicatorExitHold = true;
                            applyPageIndicatorFrameGlass(indicator);
                        } else if (name.equals("startPressDragging")
                                || name.equals("startIndicatorBgAnim")) {
                            pageIndicatorExitHold = false;
                            pageIndicatorFrameActive = true;
                            applyPageIndicatorFrameGlass(indicator);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "PageIndicatorAnimHelper." + name + " pre-glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        if (name.equals("cancelPressDragging") || name.equals("reverseIndicatorBgAnim")) {
                            pageIndicatorFrameActive = false;
                            if (isPagePreviewChromeActive(helper)) {
                                applyPageIndicatorFrameGlass(indicator);
                            } else {
                                beginPageIndicatorExitHold(indicator);
                            }
                        } else if (name.equals("startPageNumChangeAnim")
                                || name.equals("startIndicatorDotAnim")) {
                            pageIndicatorExitHold = true;
                            applyPageIndicatorFrameGlass(indicator);
                            schedulePageIndicatorExitRelease(indicator);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "PageIndicatorAnimHelper." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PageIndicatorAnimHelper glass hooks unavailable", e);
        }
        try {
            Class<?> indicator = Class.forName(
                    "com.android.launcher.pageindicators.OplusPageIndicator", false, cl);
            for (Method m : indicator.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (name.equals("drawBackgroundIfNeeded")) {
                    hookOnce(m, chain -> {
                        if (GlassInstaller.suppressPageIndicatorBackground()) {
                            return null;
                        }
                        if (enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                            Object blurBg = field(chain.getThisObject(), "mBlurBgView");
                            if (blurBg instanceof View && GlassInstaller.get((View) blurBg) != null) {
                                return null;
                            }
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("initBlurBackground")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                        }
                        return result;
                    });
                } else if (name.equals("updateBlurBgIfNeed")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            Object blurBg = field(self, "mBlurBgView");
                            if (!(blurBg instanceof View)) return result;
                            View bg = (View) blurBg;
                            if (bg.getAlpha() <= 0.01f) {
                                // startIndicatorDotAnim zeros alpha then ramps back to 255.
                                // Never uninstall synchronously here while exit-hold is active.
                                if (pageIndicatorExitHold || pageIndicatorFrameActive
                                        || isPageIndicatorPageChangeAnimating(self)) {
                                    schedulePageIndicatorExitRelease(self);
                                } else if (GlassInstaller.get(bg) != null) {
                                    GlassInstaller.uninstall(bg);
                                }
                            } else if (shouldOwnPageIndicatorFrame(self)) {
                                applyPageIndicatorFrameGlass(self);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, "updateBlurBgIfNeed glass cleanup failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setMarkersCount")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusPageIndicator glass hooks unavailable", e);
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
                        if (view instanceof View
                                && isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)) {
                            setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            applyPagePreviewItemGlass(view);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PressFeedbackPreviewWrapper.onDraw glass hook unavailable", e);
        }
    }

    private void applyToggleBarToolbarGlass(Object toolbar) {
        if (!enabled() || toolbar == null) return;
        applyPressFeedbackButtonGlass(field(toolbar, "applyBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "backToMainPage"));
        applyPressFeedbackButtonGlass(field(toolbar, "addCardBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "mFinishBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "mAddCardBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "dragCancelButton"));
        applyPressFeedbackButtonGlass(field(toolbar, "mDragCancelButton"));
        // Also walk the tree so renamed fields / DragCancelButton subclasses are covered.
        if (toolbar instanceof View) applyPressFeedbackButtonsUnder((View) toolbar);
    }

    private void applyPressFeedbackButtonsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.views.PressFeedbackButton")) {
            applyPressFeedbackButtonGlass(root);
            return;
        }
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyPressFeedbackButtonsUnder(group.getChildAt(i));
        }
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
            float radius = readPagePreviewRadius(preview);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(preview);
                if (g == null) return;
                float r = readPagePreviewRadius(preview);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(preview);
                preview.invalidate();
            };
            if (preview.getWidth() > 0 && preview.getHeight() > 0) refresh.run();
            else preview.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyPagePreviewItemGlass failed", e);
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
            log(5, TAG, "removePagePreviewItemGlass failed", e);
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
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
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
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            hardRemovePagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private void syncPagePreviewFrameGlassForLauncher(Object launcher) {
        if (!enabled() || launcher == null) return;
        Object indicator = findPageIndicator(launcher);
        if (isPagePreviewChromeActive(launcher) || pageIndicatorFrameActive) {
            pageIndicatorExitHold = false;
            applyPagePreviewGlassForLauncher(launcher);
            applyPageIndicatorFrameGlass(indicator);
            return;
        }
        pageIndicatorFrameActive = false;
        // Thumbnails can drop immediately; indicator pill must keep glass through blank-page
        // shrink + fade that continues after NORMAL.
        Object root = findPagePreviewRoot(launcher);
        if (root instanceof View) hardRemovePagePreviewItemsUnder((View) root);
        beginPageIndicatorExitHold(indicator);
    }

    private void applyPagePreviewGlassForLauncher(Object launcher) {
        Object root = findPagePreviewRoot(launcher);
        if (root instanceof View) applyPagePreviewItemsUnder((View) root);
    }

    private Object findPagePreviewRoot(Object launcher) {
        Object manager = invokeNoArgs(launcher, "getPagePreviewManager");
        Object root = invokeNoArgs(manager, "getPagePreviewLayout");
        if (root == null) root = field(manager, "mPagePreviewLayout");
        return root;
    }

    private Object findPageIndicator(Object launcher) {
        Object workspace = invokeNoArgs(launcher, "getWorkspace");
        Object indicator = invokeNoArgs(workspace, "getPageIndicator");
        if (indicator == null) indicator = field(workspace, "mPageIndicator");
        return indicator;
    }

    private void beginPageIndicatorExitHold(Object indicator) {
        pageIndicatorExitHold = true;
        applyPageIndicatorFrameGlass(indicator);
        schedulePageIndicatorExitRelease(indicator);
    }

    private void schedulePageIndicatorExitRelease(Object indicator) {
        Object blurBg = field(indicator, "mBlurBgView");
        if (!(blurBg instanceof View)) return;
        View bg = (View) blurBg;
        if (pageIndicatorExitRelease != null && pageIndicatorExitReleaseTarget != null) {
            pageIndicatorExitReleaseTarget.removeCallbacks(pageIndicatorExitRelease);
        }
        pageIndicatorExitReleaseTarget = bg;
        final Object indRef = indicator;
        pageIndicatorExitRelease = () -> {
            try {
                if (pageIndicatorFrameActive || isPagePreviewChromeActive(indRef)) return;
                if (isPageIndicatorPageChangeAnimating(indRef)) {
                    schedulePageIndicatorExitRelease(indRef);
                    return;
                }
                Object liveBg = field(indRef, "mBlurBgView");
                View v = liveBg instanceof View ? (View) liveBg : bg;
                if (v.getAlpha() > 0.01f) {
                    pageIndicatorExitHold = true;
                    applyPageIndicatorFrameGlass(indRef);
                    schedulePageIndicatorExitRelease(indRef);
                    return;
                }
                pageIndicatorExitHold = false;
                if (GlassInstaller.get(v) != null) GlassInstaller.uninstall(v);
            } catch (Throwable e) {
                log(5, TAG, "pageIndicatorExitRelease failed", e);
            }
        };
        // startIndicatorDotAnim lasts 450ms and briefly zeros alpha before ramping up.
        bg.postDelayed(pageIndicatorExitRelease, 500);
    }

    private boolean isPageIndicatorPageChangeAnimating(Object indicatorOrHost) {
        Object indicator = indicatorOrHost;
        if (!isClassOrSubclass(indicator, "com.android.launcher.pageindicators.OplusPageIndicator")) {
            Object launcher = resolveLauncher(indicatorOrHost);
            if (launcher == null) launcher = findActiveLauncher();
            indicator = findPageIndicator(launcher);
        }
        Object helper = field(indicator, "mAnimHelper");
        Object animating = field(helper, "mPageChangeAnimating");
        return Boolean.TRUE.equals(animating);
    }

    private void applyPageIndicatorFrameGlass(Object indicator) {
        if (!enabled() || !(indicator instanceof View)) return;
        Object blurBg = field(indicator, "mBlurBgView");
        if (!(blurBg instanceof View)) return;
        final View bg = (View) blurBg;
        try {
            GlassDrawable existing = GlassInstaller.get(bg);
            boolean missing = existing == null || bg.getBackground() != existing;
            GlassInstaller.installBackground(bg, currentConfig());
            GlassDrawable live = GlassInstaller.get(bg);
            if (live == null) return;
            Object radiusObj = field(indicator, "mBackgroundRadius");
            float radius = radiusObj instanceof Number
                    ? ((Number) radiusObj).floatValue()
                    : 24f * bg.getResources().getDisplayMetrics().density;
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(bg);
                if (g == null) return;
                Object rObj = field(indicator, "mBackgroundRadius");
                float r = rObj instanceof Number
                        ? ((Number) rObj).floatValue()
                        : 24f * bg.getResources().getDisplayMetrics().density;
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(bg);
                bg.invalidate();
                ((View) indicator).invalidate();
            };
            if (bg.getWidth() > 0 && bg.getHeight() > 0) refresh.run();
            else bg.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyPageIndicatorFrameGlass failed", e);
        }
    }

    /** True when the expanded indicator pill should keep liquid glass (incl. exit fade). */
    private boolean shouldOwnPageIndicatorFrame(Object host) {
        if (!enabled()) return false;
        if (pageIndicatorFrameActive || pageIndicatorExitHold) return true;
        if (isPagePreviewChromeActive(host)) return true;
        Object indicator = host;
        if (!isClassOrSubclass(host, "com.android.launcher.pageindicators.OplusPageIndicator")) {
            Object launcher = resolveLauncher(host);
            if (launcher == null) launcher = findActiveLauncher();
            indicator = findPageIndicator(launcher);
        }
        Object blurBg = field(indicator, "mBlurBgView");
        return blurBg instanceof View && ((View) blurBg).getAlpha() > 0.01f
                && GlassInstaller.get((View) blurBg) != null;
    }

    /**
     * Thumbnail cards are shown in PAGE_PREVIEW / TOGGLE_BAR (widget tray), or while
     * press-dragging page dots.
     */
    private boolean isPagePreviewChromeActive(Object host) {
        if (pageIndicatorFrameActive) return true;
        Object launcher = resolveLauncher(host);
        if (launcher == null) launcher = findActiveLauncher();
        if (launcher == null) return false;
        return isInLauncherState(launcher, "PAGE_PREVIEW")
                || isInLauncherState(launcher, "TOGGLE_BAR");
    }

    private boolean isInLauncherState(Object launcher, String stateName) {
        if (launcher == null || stateName == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, cl);
            Object state = stateClass.getField(stateName).get(null);
            Method isInState = null;
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    isInState = m;
                    break;
                }
            }
            return isInState != null && state != null
                    && Boolean.TRUE.equals(isInState.invoke(launcher, state));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static float readPagePreviewRadius(View preview) {
        Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
        Object radius = invokeNoArgs(wrapper, "getRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object viaField = field(wrapper, "mRadius");
        if (viaField instanceof Number && ((Number) viaField).floatValue() > 0f) {
            return ((Number) viaField).floatValue();
        }
        // Fallback matches PreviewItemFrameRadius (8dp).
        return 8f * preview.getResources().getDisplayMetrics().density;
    }

    private void applyToggleBarItemGlass(Object itemRoot) {
        if (!enabled() || itemRoot == null) return;
        if (!isClassOrSubclass(itemRoot, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout")) {
            return;
        }
        Object icon = field(itemRoot, "iconImageView");
        if (!(icon instanceof View) && itemRoot instanceof View) {
            icon = findChildBySimpleName((View) itemRoot, "PressFeedbackCircleImageView");
        }
        if (!(icon instanceof View)) return;
        final View circle = (View) icon;
        try {
            // Preserve the OEM glyph; glass sits behind as background with the same circular shape.
            GlassInstaller.installBackground(circle, currentConfig());
            Runnable refresh = () -> {
                GlassDrawable live = GlassInstaller.get(circle);
                if (live == null) return;
                int size = Math.min(circle.getWidth(), circle.getHeight());
                if (size <= 0) return;
                float radius = size / 2f;
                live.setCornerRadii(radius, radius, radius, radius);
                GlassInstaller.forceCapture(circle);
                circle.invalidate();
            };
            if (circle.getWidth() > 0 && circle.getHeight() > 0) refresh.run();
            else circle.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyToggleBarItemGlass failed", e);
        }
    }

    private static View findChildBySimpleName(View root, String simpleName) {
        if (root == null || simpleName == null) return null;
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findChildBySimpleName(group.getChildAt(i), simpleName);
            if (found != null) return found;
        }
        return null;
    }

    private void applyClearButtonGlass(Object panel) {
        if (!enabled() || panel == null) return;
        applyPressFeedbackButtonGlass(field(panel, "mClearAllBtn"));
    }

    /** Shared installer for PressFeedbackButton chrome (清除 / 完成 / 取消 / …). */
    private void applyPressFeedbackButtonGlass(Object buttonObj) {
        if (!enabled() || !(buttonObj instanceof View)) return;
        if (!isClassOrSubclass(buttonObj, "com.android.launcher.views.PressFeedbackButton")) return;
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
                float radius = readClearButtonRadius(button);
                if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
            }
            Runnable refresh = () -> {
                GlassDrawable live = GlassInstaller.get(button);
                if (live == null) return;
                float radius = readClearButtonRadius(button);
                if (radius <= 0f && button.getHeight() > 0) {
                    radius = button.getHeight() / 2f;
                }
                if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
                GlassInstaller.forceCapture(button);
                button.invalidate();
            };
            if (button.getWidth() > 0 && button.getHeight() > 0) refresh.run();
            else button.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyPressFeedbackButtonGlass failed", e);
        }
    }

    /** Rewrites AnimInfo endValue so hand-follow / spring blur targets stay at 0. */
    private static void zeroAnimInfoEndValue(Object animInfo) {
        if (animInfo == null) return;
        setField(animInfo, "endValue", 0f);
    }

    private static float readClearButtonRadius(View button) {
        Object radius = field(button, "mDrawableRadius");
        if (radius instanceof Number) {
            float value = ((Number) radius).floatValue();
            if (value > 0f) return value;
        }
        Object viaGetter = invokeNoArgs(button, "getMDrawableRadius");
        if (viaGetter instanceof Number) {
            float value = ((Number) viaGetter).floatValue();
            if (value > 0f) return value;
        }
        return 0f;
    }

    private boolean shouldSuppressDepthBlur() {
        return shouldSuppressDepthBlur(null);
    }

    private boolean shouldSuppressDepthBlur(Object depthController) {
        if (!enabled()) return false;
        if (folderDragActive || folderOpenActive) return true;
        Object launcher = resolveLauncher(depthController != null
                ? field(depthController, "mLauncher") : null);
        if (launcher == null) launcher = findActiveLauncher();
        return shouldSuppressDepthBlurForLauncher(launcher);
    }

    private boolean shouldSuppressDepthBlurForLauncher(Object launcher) {
        return isInFrostedWallpaperBlurState(launcher) || hasOpenFolder(launcher);
    }

    /**
     * TOGGLE_BAR / PAGE_PREVIEW (icon edit / widget tray) and OVERVIEW* (Recents) share the
     * frosted wallpaper blur that should stay off while LiquidGlass is enabled.
     */
    private boolean isInFrostedWallpaperBlurState(Object launcher) {
        if (launcher == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, cl);
            Method isInState = null;
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    isInState = m;
                    break;
                }
            }
            if (isInState == null) return false;
            String[] fields = {
                    "TOGGLE_BAR", "PAGE_PREVIEW",
                    "OVERVIEW", "OVERVIEW_MODAL_TASK", "OVERVIEW_SPLIT_SELECT", "BACKGROUND_APP"
            };
            for (String name : fields) {
                Object state;
                try {
                    state = stateClass.getField(name).get(null);
                } catch (Throwable ignored) {
                    continue;
                }
                if (state != null && Boolean.TRUE.equals(isInState.invoke(launcher, state))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasOpenFolder(Object launcher) {
        if (launcher == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> afv = Class.forName("com.android.launcher3.AbstractFloatingView", false, cl);
            Method getOpenFolder = null;
            for (Method m : afv.getMethods()) {
                if ("getOpenFolder".equals(m.getName()) && m.getParameterCount() == 1) {
                    getOpenFolder = m;
                    break;
                }
            }
            if (getOpenFolder != null && getOpenFolder.invoke(null, launcher) != null) return true;
            Class<?> folder = Class.forName("com.android.launcher3.folder.Folder", false, cl);
            Method getOpen = null;
            for (Method m : folder.getMethods()) {
                if ("getOpen".equals(m.getName()) && m.getParameterCount() == 1) {
                    getOpen = m;
                    break;
                }
            }
            return getOpen != null && getOpen.invoke(null, launcher) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object findActiveLauncher() {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            try {
                Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
                Object created = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
                if (created != null) return created;
            } catch (Throwable ignored) { }
            try {
                return launcherClass.getMethod("getLauncher", android.content.Context.class)
                        .invoke(null, (Object) null);
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        return null;
    }

    private volatile Object folderDragSource;

    private void beginFolderDrag(Object folderIcon) {
        // Folder-only flag. Widgets must never flip this on — refreshFolderDragGlass would
        // otherwise reinstall glass onto whatever DragView content is currently moving.
        if (!isFolderDragSource(folderIcon)) return;
        folderDragActive = true;
        folderDragSource = folderIcon;
        forceDepthBlur(folderIcon, 0f);
    }

    private void endFolderDrag(Object host) {
        if (!folderDragActive) return;
        folderDragActive = false;
        Object source = folderDragSource;
        folderDragSource = null;
        // Finger-up: drop overlay seeds immediately. Otherwise the returned FolderIcon keeps
        // compositing the desktop into its own LiquidGlass (self secondary-refraction).
        try {
            View dragContent = findFolderDragContentView(null);
            GlassInstaller.clearDragOverlays();
            if (dragContent != null && GlassInstaller.get(dragContent) != null) {
                GlassInstaller.uninstall(dragContent);
            }
            if (source != null) syncFolderIconDeferred(source, true);
        } catch (Throwable ignored) { }
        // Stay suppressed while still in TOGGLE_BAR / open folder; otherwise restore.
        Object launcher = resolveLauncher(host);
        if (shouldSuppressDepthBlurForLauncher(launcher) || folderOpenActive) {
            forceDepthBlur(launcher != null ? launcher : host, 0f);
            return;
        }
        try {
            float restore = 0f;
            Object stateManager = invokeNoArgs(launcher, "getStateManager");
            Object state = invokeNoArgs(stateManager, "getState");
            Object blur = invoke(state, "getBlur", new Class<?>[] { android.content.Context.class },
                    launcher instanceof android.content.Context ? launcher : null);
            if (blur instanceof Number) restore = ((Number) blur).floatValue();
            forceDepthBlur(launcher != null ? launcher : host, restore);
        } catch (Throwable e) {
            log(5, TAG, "Restore depth blur after folder drag failed", e);
        }
    }

    private void beginFolderOpen(Object folder) {
        folderOpenActive = true;
        forceDepthBlur(folder, 0f);
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    private void endFolderOpen(Object host) {
        if (!folderOpenActive && !hasOpenFolder(resolveLauncher(host))) {
            return;
        }
        folderOpenActive = false;
        Object launcher = resolveLauncher(host);
        forceDepthBlur(launcher != null ? launcher : host, 0f);
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    /**
     * Tell SystemUI whether Launcher-backed ashmem is meaningful.
     * <ul>
     *   <li>Home + Overview/Recents → ashmem (icons or Recents cards from Launcher)</li>
     *   <li>PAGE_PREVIEW / TOGGLE_BAR → {@code captureDisplay}</li>
     * </ul>
     */
    private void syncDesktopHomeValidForLauncher(Object launcher) {
        try {
            boolean overview = isLauncherOverviewState(launcher);
            LauncherAshmemMode.setOverviewActive(overview);
            boolean ashmemOk = !isInLauncherState(launcher, "PAGE_PREVIEW")
                    && !isInLauncherState(launcher, "TOGGLE_BAR");
            DesktopBackdropHub.setDesktopHomeValid(ashmemOk);
            if (DesktopBackdropHub.isLive()) {
                DesktopBackdropSampler.invalidateCache();
            }
        } catch (Throwable ignored) { }
    }

    private boolean isLauncherOverviewState(Object launcher) {
        if (launcher == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, cl);
            Method isInState = null;
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    isInState = m;
                    break;
                }
            }
            if (isInState == null) return false;
            String[] fields = {
                    "OVERVIEW", "OVERVIEW_MODAL_TASK", "OVERVIEW_SPLIT_SELECT", "BACKGROUND_APP"
            };
            for (String name : fields) {
                Object state;
                try {
                    state = stateClass.getField(name).get(null);
                } catch (Throwable ignored) {
                    continue;
                }
                if (state != null && Boolean.TRUE.equals(isInState.invoke(launcher, state))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object resolveLauncher(Object host) {
        if (host == null) return null;
        if (isClassOrSubclass(host, "com.android.launcher3.Launcher")
                || isClassOrSubclass(host, "com.android.launcher.Launcher")) {
            return host;
        }
        Object launcher = field(host, "mLauncher");
        if (launcher != null) return launcher;
        return field(host, "mActivity");
    }

    private void forceDepthBlur(Object host, float value) {
        try {
            Object launcher = resolveLauncher(host);
            Object depth = invokeNoArgs(launcher, "getDepthController");
            if (depth == null) return;
            Method setBlurWithoutAnim = null;
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setBlurWithoutAnim") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    setBlurWithoutAnim = m;
                    break;
                }
            }
            if (setBlurWithoutAnim != null) {
                setBlurWithoutAnim.invoke(depth, value);
            }
            Method setIconBlurWithoutAnim = null;
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setIconBlurWithoutAnim") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    setIconBlurWithoutAnim = m;
                    break;
                }
            }
            if (setIconBlurWithoutAnim != null) {
                setIconBlurWithoutAnim.invoke(depth, 0f);
            }
        } catch (Throwable e) {
            log(5, TAG, "forceDepthBlur failed", e);
        }
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

    /** Drops any OplusBlurProperties already attached to the DragView. */
    private static void clearDragBlurProp(Object dragView) {
        Object blurProp = field(dragView, "mBlurProp");
        if (blurProp != null) {
            try {
                Method recycle = blurProp.getClass().getMethod("recycle");
                recycle.setAccessible(true);
                recycle.invoke(blurProp);
            } catch (Throwable ignored) { }
            setField(dragView, "mBlurProp", null);
        }
    }

    /** Neutralizes an OEM LayerBlurDrawable if it is still the content background. */
    private static void suppressLayerBlur(View content) {
        Drawable background = content.getBackground();
        if (background == null) return;
        if (!isClassOrSubclass(background, "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable")) {
            return;
        }
        try {
            Method setBlurAlpha = background.getClass().getMethod("setBlurAlpha", float.class);
            setBlurAlpha.setAccessible(true);
            setBlurAlpha.invoke(background, 0f);
        } catch (Throwable ignored) { }
        content.setBackground(null);
    }

    private void hookDragViewMove(ClassLoader cl) {
        final String className = "com.android.launcher3.dragndrop.DragView";
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("move") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object content = invokeNoArgs(chain.getThisObject(), "getContentView");
                        if (!(content instanceof View)) return result;
                        View view = (View) content;
                        // Widget / card DragView: never refresh folder glass; always strip.
                        if (isWidgetDragContent(view) || !isFolderDragSource(folderDragSource)) {
                            stripWidgetDragViewGlass(chain.getThisObject());
                            if (folderDragActive && !isFolderDragSource(folderDragSource)) {
                                folderDragActive = false;
                                folderDragSource = null;
                            }
                            return result;
                        }
                        if (folderDragActive && isFolderDragSource(folderDragSource)) {
                            // Finger motion dirties geometry; only reinstall if OEM cleared glass.
                            if (GlassInstaller.get(view) == null) {
                                refreshFolderDragGlass(chain.getThisObject(), true);
                            } else {
                                view.invalidate();
                            }
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "DragView.move glass refresh failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "DragView.move hook unavailable", e);
        }
    }

    private void hookWorkspaceDragOver(ClassLoader cl) {
        after("com.android.launcher3.OplusWorkspace", cl, "onDragOver", this::syncDragOverlaySource);
        after("com.android.launcher3.Workspace", cl, "onDragOver", this::syncDragOverlaySource);
        for (String className : new String[] {
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!m.getName().equals("setCurrentDropLayout")) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (folderDragActive && isFolderDragSource(folderDragSource)) {
                                syncDragOverlaySource(chain.getThisObject());
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + ".setCurrentDropLayout overlay sync failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, className + " setCurrentDropLayout hook unavailable", e);
            }
        }
        for (String className : new String[] {
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String name = m.getName();
                    if (!(name.equals("onScrollChanged")
                            || name.equals("onPageBeginMoving")
                            || name.equals("onPageEndTransition")
                            || name.equals("snapToPageForDrag"))) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (folderDragActive && isFolderDragSource(folderDragSource)) {
                                refreshFolderDragGlass(null, true);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + "." + name + " drag glass refresh failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, className + " page-scroll glass hook unavailable", e);
            }
        }
    }

    private void syncDragOverlaySource(Object workspace) {
        View desktopPage = desktopPageUnderDrag(workspace);
        Object hover = dragHoverView(workspace);
        boolean hoverIsFolder = hover != null
                && isClassOrSubclass(hover, "com.android.launcher3.folder.FolderIcon");

        // Overlay seed only while a real folder is being dragged — never for widget DragViews.
        if (folderDragActive && isFolderDragSource(folderDragSource)) {
            View content = findFolderDragContentView(null);
            if (content != null && GlassInstaller.get(content) != null) {
                GlassDrawable glass = GlassInstaller.get(content);
                if (glass != null) glass.setAlpha(255);
                View seed = workspace instanceof View ? (View) workspace : desktopPage;
                if (seed != null) {
                    GlassInstaller.setOverlaySource(content, seed);
                } else if (desktopPage != null) {
                    GlassInstaller.setOverlaySource(content, desktopPage);
                }
                GlassInstaller.forceCapture(content);
                content.invalidate();
            }
        }

        if (hoverIsFolder) {
            syncFolderIconDeferred(hover, true);
            keepHoverFolderGlassVisible(field(hover, "mBackground"));
        }
    }

    /**
     * Prefer OEM {@code getCurrentDropLayout()} (mNextPage || mCurrentPage); skip Hotseat.
     */
    private View desktopPageUnderDrag(Object workspace) {
        if (workspace == null) return null;
        try {
            Object drop = invokeNoArgs(workspace, "getCurrentDropLayout");
            if (drop instanceof View) return (View) drop;
        } catch (Throwable ignored) { }
        try {
            Object pageIndex = invokeNoArgs(workspace, "getNextPage");
            if (!(pageIndex instanceof Number) || ((Number) pageIndex).intValue() < 0) {
                pageIndex = invokeNoArgs(workspace, "getCurrentPage");
            }
            if (pageIndex instanceof Number) {
                Object page = invoke(workspace, "getPageAt",
                        new Class<?>[] { int.class },
                        ((Number) pageIndex).intValue());
                if (page instanceof View) return (View) page;
            }
        } catch (Throwable ignored) { }
        Object layout = field(workspace, "mDragTargetLayout");
        if (layout instanceof View) {
            String name = layout.getClass().getSimpleName();
            if (name == null || !name.contains("Hotseat")) return (View) layout;
        }
        try {
            Object pageIndex = invokeNoArgs(workspace, "getCurrentPage");
            if (pageIndex instanceof Number) {
                Object page = invoke(workspace, "getPageAt",
                        new Class<?>[] { int.class },
                        ((Number) pageIndex).intValue());
                if (page instanceof View) return (View) page;
            }
        } catch (Throwable ignored) { }
        return workspace instanceof View ? (View) workspace : null;
    }

    /** Prefer mDragOverView; fall back to mDragTargetLayout + mTargetCell. */
    private Object dragHoverView(Object workspace) {
        Object hover = field(workspace, "mDragOverView");
        if (hover instanceof View) return hover;
        Object layout = field(workspace, "mDragTargetLayout");
        Object cell = field(workspace, "mTargetCell");
        if (!(layout instanceof View) || !(cell instanceof int[])) return null;
        int[] targetCell = (int[]) cell;
        if (targetCell.length < 2) return null;
        try {
            Object child = invoke(layout, "getChildAt",
                    new Class<?>[] { int.class, int.class },
                    targetCell[0], targetCell[1]);
            return child instanceof View ? child : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Create-folder / folder-accept: OEM hides {@code mBgView} and paints via CellLayout
     * {@code drawBackground}. Install glass for that canvas path — never force mBgView
     * VISIBLE while delegated or while OEM hid it (that shows folder plate + canvas glass).
     * Never {@code layout(0,0)} an attached FolderIcon plate (that shifts glass to top-left).
     * Create-folder parks {@code mBgView} on CellLayout for capture; detach before FolderIcon
     * takes ownership (see {@link #preparePreviewTransferToFolder}).
     */
    private void keepHoverFolderGlassVisible(Object previewBackground) {
        if (!enabled() || previewBackground == null) return;
        ensurePreviewGlassInstalled(previewBackground);
        Object folderHost = resolvePreviewFolderIcon(previewBackground);
        if (folderHost != null && isFolderOpen(folderHost)) return;

        Object bgView = field(previewBackground, "mBgView");
        boolean delegated = isPreviewDrawingDelegated(previewBackground);
        if (delegated) {
            placeCreateFolderBgForCapture(previewBackground);
            suppressOemPreviewDrawable(previewBackground);
            // Folder accept: keep OEM-hidden plate hidden — canvas glass only.
            if (folderHost != null && bgView instanceof View) {
                ((View) bgView).setVisibility(View.INVISIBLE);
            }
            Object delegate = field(previewBackground, "mDrawingDelegate");
            if (delegate instanceof View) ((View) delegate).invalidate();
            Object invalidate = field(previewBackground, "mInvalidateDelegate");
            if (invalidate instanceof View) ((View) invalidate).invalidate();
            Object createHost = field(previewBackground, "mHostView");
            if (createHost instanceof View) ((View) createHost).invalidate();
            if (DesktopBackdropHub.isLive()) {
                DesktopBackdropSampler.invalidateCache();
            }
            return;
        }

        // animateToAccept hides mBgView before delegateDrawing runs — do not fight OEM.
        if (bgView instanceof View && ((View) bgView).getVisibility() != View.VISIBLE) {
            Object delegate = field(previewBackground, "mDrawingDelegate");
            if (delegate instanceof View) ((View) delegate).invalidate();
            return;
        }

        // Resting FolderIcon: keep attached mBgView visible with glass.
        if (bgView instanceof View && ((View) bgView).getParent() != null
                && folderHost != null) {
            View view = (View) bgView;
            view.setVisibility(View.VISIBLE);
            if (GlassInstaller.get(view) != null) {
                GlassInstaller.forceCapture(view);
                view.invalidate();
            }
        }
        if (folderHost instanceof View) ((View) folderHost).invalidate();
    }

    private static boolean isPreviewDrawingDelegated(Object previewBackground) {
        Object delegated = invokeNoArgs(previewBackground, "drawingDelegated");
        if (delegated instanceof Boolean) return (Boolean) delegated;
        return field(previewBackground, "mDrawingDelegate") != null;
    }

    private static Object resolvePreviewFolderIcon(Object previewBackground) {
        Object host = field(previewBackground, "mInvalidateDelegate");
        if (isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return host;
        Object createHost = field(previewBackground, "mHostView");
        if (isClassOrSubclass(createHost, "com.android.launcher3.folder.FolderIcon")) {
            return createHost;
        }
        return null;
    }

    /**
     * Install LiquidGlass on {@code OplusPreviewBackground.mBgView}. Create-folder parks the
     * ImageView on CellLayout for correct backdrop sampling; FolderIcon plates
     * keep OEM FrameLayout margins (never re-layout here).
     */
    private void ensurePreviewGlassInstalled(Object previewBackground) {
        if (!enabled() || previewBackground == null) return;
        Object folderHost = resolvePreviewFolderIcon(previewBackground);
        if (folderHost != null && isFolderOpen(folderHost)) return;

        Object bgView = field(previewBackground, "mBgView");
        if (!(bgView instanceof ImageView)) {
            try {
                Object ctx = field(previewBackground, "mContext");
                Object attachHost = folderHost != null ? folderHost
                        : field(previewBackground, "mInvalidateDelegate");
                if (ctx instanceof android.content.Context && attachHost instanceof View) {
                    invoke(previewBackground, "initBgViewIfNeed",
                            new Class<?>[]{android.content.Context.class, View.class},
                            ctx, attachHost);
                }
            } catch (Throwable ignored) { }
            bgView = field(previewBackground, "mBgView");
        }
        if (!(bgView instanceof ImageView)) {
            bgView = createDetachedPreviewBgView(previewBackground);
        }
        if (!(bgView instanceof ImageView)) return;
        ImageView image = (ImageView) bgView;
        // FolderIcon only: ensure child exists. Never re-layout resting / accept plates here.
        if (folderHost instanceof ViewGroup && image.getParent() == null) {
            try {
                ((ViewGroup) folderHost).addView(image, 0);
                invokeSetBackground(previewBackground, folderHost);
            } catch (Throwable ignored) { }
        }
        GlassInstaller.installImage(image, currentConfig());
        applyPreviewRadius(previewBackground, image);
        if (image.getDrawable() != null) image.setImageDrawable(null);
        suppressOemPreviewDrawable(previewBackground);
    }

    private ImageView createDetachedPreviewBgView(Object previewBackground) {
        try {
            Object ctx = field(previewBackground, "mContext");
            if (!(ctx instanceof android.content.Context)) return null;
            float radius = 0f;
            Object r = field(previewBackground, "mRadius");
            if (r instanceof Number) radius = ((Number) r).floatValue();
            Class<?> cls = Class.forName("com.android.launcher3.folder.FolderRoundImageView",
                    false, previewBackground.getClass().getClassLoader());
            Object created = cls.getConstructor(float.class, android.content.Context.class)
                    .newInstance(radius, ctx);
            if (created instanceof ImageView) {
                setField(previewBackground, "mBgView", created);
                return (ImageView) created;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Park create-folder {@code mBgView} on {@code CellLayout} at cell-local plate coords so
     * {@link GlassInstaller#forceCapture} samples the same screen region CellLayout paints.
     * DragLayer / detached {@code layout(0,0)} sampled the wrong region (top-left) and looked
     * bright. Detach before {@code FolderIcon.setPreviewBackground} (see preparePreviewTransfer).
     * CellLayout.onLayout only lays out ShortcutsAndWidgets, so this direct child keeps
     * manual {@code layout()}.
     */
    private void placeCreateFolderBgForCapture(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        if (!isPreviewDrawingDelegated(preview)) return;
        Object bgView = field(preview, "mBgView");
        Object delegate = field(preview, "mDrawingDelegate");
        if (!(bgView instanceof ImageView) || !(delegate instanceof ViewGroup)) return;
        ImageView image = (ImageView) bgView;
        ViewGroup cellLayout = (ViewGroup) delegate;
        try {
            if (image.getParent() != cellLayout) {
                detachCreateFolderCaptureHost(preview);
                cellLayout.addView(image);
            }
            repositionCreateFolderBgIfParked(preview);
        } catch (Throwable ignored) {
            cellLayout.post(() -> {
                try {
                    if (!isPreviewDrawingDelegated(preview)) return;
                    placeCreateFolderBgForCapture(preview);
                    cellLayout.invalidate();
                } catch (Throwable ignored2) { }
            });
        }
    }

    /** Update capture-host layout when already parented to CellLayout (safe during onDraw). */
    private void repositionCreateFolderBgIfParked(Object preview) {
        if (preview == null || resolvePreviewFolderIcon(preview) != null) return;
        Object bgView = field(preview, "mBgView");
        Object delegate = field(preview, "mDrawingDelegate");
        if (!(bgView instanceof ImageView) || !(delegate instanceof ViewGroup)) return;
        ImageView image = (ImageView) bgView;
        ViewGroup cellLayout = (ViewGroup) delegate;
        if (image.getParent() != cellLayout) return;

        Object rectObj = invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof android.graphics.Rect)) return;
        android.graphics.Rect r = (android.graphics.Rect) rectObj;
        if (r.width() <= 0 || r.height() <= 0) return;

        int cellX = 0;
        int cellY = 0;
        Object cx = field(preview, "mDelegateCellX");
        Object cy = field(preview, "mDelegateCellY");
        if (cx instanceof Number) cellX = ((Number) cx).intValue();
        if (cy instanceof Number) cellY = ((Number) cy).intValue();
        int[] cellPoint = new int[2];
        if (!cellToPoint(cellLayout, cellX, cellY, cellPoint)) return;

        try {
            // Same coordinate space as CellLayout.onDraw: translate(cellPoint) + getBackgroundRect.
            image.setVisibility(View.INVISIBLE);
            int left = cellPoint[0] + r.left;
            int top = cellPoint[1] + r.top;
            image.measure(
                    View.MeasureSpec.makeMeasureSpec(r.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.EXACTLY));
            image.layout(left, top, left + r.width(), top + r.height());
            GlassDrawable glass = GlassInstaller.get(image);
            if (glass != null) glass.setBounds(0, 0, r.width(), r.height());
            applyPreviewRadius(preview, image);
        } catch (Throwable ignored) { }
    }

    private static boolean cellToPoint(View cellLayout, int cellX, int cellY, int[] out) {
        if (out == null || out.length < 2) return false;
        for (Class<?> c = cellLayout.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("cellToPoint", int.class, int.class, int[].class);
                method.setAccessible(true);
                method.invoke(cellLayout, cellX, cellY, out);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private void applyPreviewRadius(Object preview, ImageView image) {
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return;
        float radius = 0f;
        Object rv = field(preview, "mRadius");
        if (rv instanceof Number) radius = ((Number) rv).floatValue();
        Object scale = field(preview, "mScale");
        if (scale instanceof Number) radius *= ((Number) scale).floatValue();
        if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
    }

    /**
     * Replace OEM folder_icon_bg / LayerBlur with a transparent placeholder.
     * {@code animateToAccept → setBlurAlpha(0)} sets {@code mDefaultAlpha=1} and would paint a
     * bright translucent fill if the drawable remained installed.
     */
    private static void suppressOemPreviewDrawable(Object preview) {
        if (preview == null) return;
        Object current = field(preview, "mBgDrawable");
        if (current != TRANSPARENT_PREVIEW_BG && current instanceof Drawable) {
            SAVED_PREVIEW_OEM_BG.putIfAbsent(preview, (Drawable) current);
            setField(preview, "mBgDrawable", TRANSPARENT_PREVIEW_BG);
        }
        Object bgView = field(preview, "mBgView");
        if (bgView instanceof ImageView) {
            ImageView image = (ImageView) bgView;
            Drawable imageDrawable = image.getDrawable();
            if (imageDrawable != null && !(imageDrawable instanceof GlassDrawable)
                    && imageDrawable != TRANSPARENT_PREVIEW_BG) {
                image.setImageDrawable(null);
            }
        }
    }

    private static void restoreOemPreviewDrawable(Object preview) {
        if (preview == null) return;
        Drawable saved = SAVED_PREVIEW_OEM_BG.remove(preview);
        if (saved != null) setField(preview, "mBgDrawable", saved);
    }

    private static void invokeSetBackground(Object preview, Object folderHost) {
        for (Class<?> c = preview.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setBackground") || m.getParameterCount() != 1) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(preview, folderHost);
                    return;
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Detach create-folder capture host from DragLayer / CellLayout (never FolderIcon). */
    private void detachCreateFolderCaptureHost(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        Object bgView = field(preview, "mBgView");
        if (!(bgView instanceof View)) return;
        View image = (View) bgView;
        if (!(image.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) image.getParent();
        String pname = parent.getClass().getSimpleName();
        if (pname != null && pname.contains("FolderIcon")) return;
        try {
            parent.removeView(image);
        } catch (Throwable ignored) { }
    }

    /** Hide + detach before OEM clearDrawingDelegate sets the CellLayout park host VISIBLE. */
    private void hideAndDetachCreateFolderCaptureHost(Object preview) {
        if (preview == null || resolvePreviewFolderIcon(preview) != null) return;
        Object bgView = field(preview, "mBgView");
        if (bgView instanceof View) {
            try {
                ((View) bgView).setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) { }
        }
        detachCreateFolderCaptureHost(preview);
    }

    /**
     * After create-folder delegate is cleared: ensure park host is gone, glass uninstalled, and
     * CellLayout invalidated so no residual plate remains.
     */
    private void finishCreateFolderPreviewCleanup(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        if (preview == activeCreateFolderPreview) activeCreateFolderPreview = null;
        hideAndDetachCreateFolderCaptureHost(preview);
        Object bgView = field(preview, "mBgView");
        if (bgView instanceof View) {
            View image = (View) bgView;
            try {
                image.setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) { }
            if (GlassInstaller.get(image) != null) {
                GlassInstaller.uninstall(image);
            }
        }
        restoreOemPreviewDrawable(preview);
        Object delegate = field(preview, "mDrawingDelegate");
        if (delegate instanceof View) ((View) delegate).invalidate();
        Object invalidate = field(preview, "mInvalidateDelegate");
        if (invalidate instanceof View) ((View) invalidate).invalidate();
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    /**
     * Cancel create-folder preview: clear CellLayout delegate drawing and detach park host.
     * OEM onDragEnd often nulls {@code mGroupCreatePreviewBg} without clearDrawingDelegate.
     * Move-away cancel uses animateToRest → this path must remove glass immediately.
     */
    private void cleanupCreateFolderPreview(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        hideAndDetachCreateFolderCaptureHost(preview);
        try {
            invokeNoArgs(preview, "clearDrawingDelegate");
        } catch (Throwable ignored) {
            try {
                invoke(preview, "clearDrawingDelegate", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored2) { }
        }
        // clearDrawingDelegate hook also finishes cleanup; call again in case hook missed.
        finishCreateFolderPreviewCleanup(preview);
    }

    /** Before FolderIcon takes ownership of the create-folder PreviewBackground. */
    private void preparePreviewTransferToFolder(Object preview) {
        if (preview == null) return;
        detachCreateFolderCaptureHost(preview);
        try {
            invokeNoArgs(preview, "clearDrawingDelegate");
        } catch (Throwable ignored) { }
        restoreOemPreviewDrawable(preview);
        Object bgView = field(preview, "mBgView");
        if (bgView instanceof View && ((View) bgView).getParent() != null
                && resolvePreviewFolderIcon(preview) == null) {
            // Still parented somewhere unexpected — force detach.
            detachCreateFolderCaptureHost(preview);
            if (((View) bgView).getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) ((View) bgView).getParent()).removeView((View) bgView);
                } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * After accept ends: restore FolderIcon plate layout via OEM {@code setBackground},
     * and detach create-folder capture host from CellLayout.
     */
    private void restoreFolderPreviewAfterDelegate(Object preview) {
        if (preview == null) return;
        Object folderHost = resolvePreviewFolderIcon(preview);
        Object bgView = field(preview, "mBgView");

        if (folderHost == null) {
            finishCreateFolderPreviewCleanup(preview);
            return;
        }

        if (folderHost instanceof View) {
            View icon = (View) folderHost;
            restoreOemPreviewDrawable(preview);
            try {
                Class<?> igroup = Class.forName(
                        "com.android.launcher3.stack.view.IGroupView",
                        false, preview.getClass().getClassLoader());
                invoke(preview, "setBackground", new Class<?>[]{igroup}, folderHost);
            } catch (Throwable ignored) {
                try {
                    invokeSetBackground(preview, folderHost);
                } catch (Throwable ignored2) { }
            }
            setFolderBackgroundVisibility(preview, true);
            syncFolderPreview(folderHost, preview);
            icon.requestLayout();
            icon.post(() -> {
                setFolderBackgroundVisibility(preview, true);
                syncFolderPreview(folderHost, preview);
                if (bgView instanceof View) {
                    ((View) bgView).setVisibility(View.VISIBLE);
                    ((View) bgView).invalidate();
                }
                icon.invalidate();
            });
        }
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    /** Draw installed preview glass into a CellLayout delegated canvas (create-folder / accept). */
    private boolean drawPreviewGlassOnCanvas(Object preview, Canvas canvas) {
        if (canvas == null || preview == null) return false;
        // After cancel / clearDrawingDelegate, never re-park or paint residual glass.
        if (!isPreviewDrawingDelegated(preview)) return false;
        ensurePreviewGlassInstalled(preview);
        boolean createFolder = resolvePreviewFolderIcon(preview) == null;
        if (createFolder) {
            // Must be parked on CellLayout at the plate before capture — detached layout(0,0)
            // samples screen top-left and looks bright (folder-accept path never hits this).
            placeCreateFolderBgForCapture(preview);
        }
        repositionCreateFolderBgIfParked(preview);
        suppressOemPreviewDrawable(preview);
        Object bgView = field(preview, "mBgView");
        if (!(bgView instanceof ImageView)) return false;
        ImageView image = (ImageView) bgView;
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return false;
        Object rectObj = invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof android.graphics.Rect)) return false;
        android.graphics.Rect r = (android.graphics.Rect) rectObj;
        if (r.width() <= 0 || r.height() <= 0) return false;

        if (createFolder && image.getParent() == null) {
            // No safe capture host yet — skip rather than paint bright AGSL-fallback / wrong sample.
            return false;
        }
        // FolderIcon: never image.layout(...) — that shifted plates to top-left.
        if (!createFolder && image.getParent() == null) {
            return false;
        }
        glass.setBounds(0, 0, r.width(), r.height());
        applyPreviewRadius(preview, image);

        Object folderHost = resolvePreviewFolderIcon(preview);
        if (folderHost != null && isPreviewDrawingDelegated(preview)) {
            image.setVisibility(View.INVISIBLE);
        }

        int save = canvas.save();
        try {
            canvas.translate(r.left, r.top);
            // Skip re-entrant drawBackground while sampling — otherwise glass/fallback is baked
            // into the backdrop and the plate looks like a bright translucent OEM tint.
            GlassInstaller.forceCapturePreviewPlate(image);
            // Without a backdrop frame, GlassDrawable paints a bright translucent fallback.
            if (!GlassInstaller.hasBackdropFrame(image)) return false;
            glass.draw(canvas);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void hookCreateFolderPreviewGlass(ClassLoader cl) {
        // BubbleTextView / ShortcutContainer receive the shared create-folder PreviewBackground.
        String[] hosts = {
                "com.android.launcher3.BubbleTextView",
                "com.android.launcher3.OplusBubbleTextView",
                "com.android.launcher3.viewcontainer.ShortcutContainer"
        };
        for (String className : hosts) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("setFolderBG") || m.isBridge() || m.isSynthetic()) {
                        continue;
                    }
                    hookOnce(m, chain -> {
                        // Read previous before OEM clears it — cancel path passes null without
                        // always calling clearDrawingDelegate (see OplusWorkspace.onDragEnd).
                        Object self = chain.getThisObject();
                        Object before = field(self, "mFolderPreviewBackGround");
                        if (before == null) before = field(self, "previewBackground");
                        Object bg = chain.getArg(0);
                        Object result = chain.proceed();
                        try {
                            if (bg == null) {
                                cleanupCreateFolderPreview(before != null ? before : activeCreateFolderPreview);
                            } else {
                                activeCreateFolderPreview = bg;
                                ensurePreviewGlassInstalled(bg);
                                placeCreateFolderBgForCapture(bg);
                                keepHoverFolderGlassVisible(bg);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + ".setFolderBG glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, "Class unavailable: " + className, e);
            }
        }

        // Folder create reuses the create-folder PreviewBackground — must detach any temp
        // CellLayout park before FolderIcon.addView(mBgView), else launcher crashes.
        try {
            Class<?> folderIcon = Class.forName("com.android.launcher3.folder.FolderIcon", false, cl);
            for (Method m : folderIcon.getDeclaredMethods()) {
                if (!m.getName().equals("setPreviewBackground") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object preview = chain.getArg(0);
                    try {
                        preparePreviewTransferToFolder(preview);
                    } catch (Throwable e) {
                        log(5, TAG, "FolderIcon.setPreviewBackground prepare failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        Object host = chain.getThisObject();
                        if (preview != null && host instanceof View) {
                            activeCreateFolderPreview = null;
                            invokeSetBackground(preview, host);
                            setFolderBackgroundVisibility(preview, true);
                            syncFolderPreview(host, preview);
                            ((View) host).requestLayout();
                            ((View) host).invalidate();
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "FolderIcon.setPreviewBackground glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "FolderIcon.setPreviewBackground hook unavailable", e);
        }
    }

    /** Shared create-folder PreviewBackground currently shown (for cancel cleanup). */
    private volatile Object activeCreateFolderPreview;

    /**
     * Keeps the dragged-folder LiquidGlass backdrop in sync while the desktop pages scroll
     * under a mostly stationary DragView, and reinstalls glass if OEM restored LayerBlur.
     */
    private void refreshFolderDragGlass(Object dragViewHint, boolean reassert) {
        if (!enabled() || !folderDragActive || !isFolderDragSource(folderDragSource)) return;
        View content = findFolderDragContentView(dragViewHint);
        if (content == null || isWidgetDragContent(content)) return;
        if (reassert || GlassInstaller.get(content) == null) {
            suppressLayerBlur(content);
            GlassInstaller.installBackground(content, currentConfig());
            GlassDrawable glass = GlassInstaller.get(content);
            if (glass != null) glass.setAlpha(255);
        }
        Object workspace = invokeNoArgs(findActiveLauncher(), "getWorkspace");
        if (workspace != null) {
            syncDragOverlaySource(workspace);
        } else if (GlassInstaller.get(content) != null) {
            GlassInstaller.forceCapture(content);
            content.invalidate();
        }
    }

    private View findFolderDragContentView(Object dragViewHint) {
        Object dragView = dragViewHint;
        if (dragView == null) {
            Object launcher = findActiveLauncher();
            Object dragController = invokeNoArgs(launcher, "getDragController");
            Object dragObject = field(dragController, "mDragObject");
            dragView = field(dragObject, "dragView");
        }
        Object content = invokeNoArgs(dragView, "getContentView");
        if (!(content instanceof View)) return null;
        View view = (View) content;
        // Never treat widget/card DragView content as the folder drag glass host.
        if (isWidgetDragContent(view)) return null;
        return view;
    }

    private void hookFolderRefreshEvents(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.FolderIcon";
        after(className, cl, "updateBackground", o -> syncFolderIconDeferred(o, false));
        after(className, cl, "onDrop", o -> syncFolderIconDeferred(o, false));
        // After resize finishes, ColorOS rebuilds background — reassert glass (do not sync on
        // every updateItemIconLayout frame). Corner radii stay on the b73ba87 installImage path.
        after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "updateItemIconPreview",
                this::restoreFolderGlassAfterResize);
    }

    /**
     * Unlock briefly zeros DragLayer alpha and resets wallpaper depth. Soft-refresh all glass
     * hosts through the settle window so backdrops catch up without flashing OEM blur/fallback.
     */
    private void hookUnlockGlassRefresh(ClassLoader cl) {
        after("com.android.launcher.Launcher", cl, "onResume", o -> scheduleUnlockGlassRefresh());
        after("com.android.launcher3.Launcher", cl, "onResume", o -> scheduleUnlockGlassRefresh());
        for (String className : new String[] {
                "com.android.launcher.Launcher",
                "com.android.launcher3.Launcher",
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (m.getName().equals("onWindowFocusChanged") && m.getParameterCount() == 1) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (Boolean.TRUE.equals(chain.getArg(0))) scheduleUnlockGlassRefresh();
                            } catch (Throwable e) {
                                log(5, TAG, className + ".onWindowFocusChanged refresh failed", e);
                            }
                            return result;
                        });
                    } else if (m.getName().equals("onScreenLockStateChange")
                            && m.getParameterCount() == 1) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                Object state = chain.getArg(0);
                                Object lock = invokeNoArgs(state, "getLockState");
                                if (lock instanceof Number && ((Number) lock).intValue() == 1) {
                                    scheduleUnlockGlassRefresh();
                                }
                            } catch (Throwable e) {
                                log(5, TAG, className + ".onScreenLockStateChange refresh failed", e);
                            }
                            return result;
                        });
                    }
                }
            } catch (Throwable e) {
                log(5, TAG, className + " unlock refresh hook unavailable", e);
            }
        }
    }

    private void scheduleUnlockGlassRefresh() {
        if (!enabled()) return;
        final int token = ++unlockGlassRefreshToken;
        GlassInstaller.refreshAll();
        for (long delay : new long[] { 48L, 120L, 280L, 500L, 900L }) {
            mainHandler.postDelayed(() -> {
                if (token != unlockGlassRefreshToken || !enabled()) return;
                GlassInstaller.refreshAll();
            }, delay);
        }
    }

    /**
     * Recents / app transitions zoom the system wallpaper via sendWallpaperCommand (1.0↔1.2).
     * That surface is not in the View hierarchy; track the Bundle so BackdropCapture can mirror it.
     */
    private void hookWallpaperScaleTracking(ClassLoader cl) {
        try {
            Class<?> depth = Class.forName(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", false, cl);
            for (Method m : depth.getDeclaredMethods()) {
                if (!m.getName().equals("startWallpaperAnimation") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 3 || types[2] != Bundle.class) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object extras = chain.getArg(2);
                        if (extras instanceof Bundle) {
                            WallpaperScaleTracker.onWallpaperCommand((Bundle) extras);
                            if (enabled()) GlassInstaller.refreshAll();
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "Wallpaper scale track failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Wallpaper scale tracking hook unavailable", e);
        }
    }

    /** Restores mBgView visibility/layout contract after leaving the resize-frame path. */
    private void restoreFolderGlassAfterResize(Object folderIcon) {
        if (!(folderIcon instanceof View)) {
            syncFolderIconDeferred(folderIcon, true);
            return;
        }
        View icon = (View) folderIcon;
        Object preview = field(folderIcon, "mBackground");
        Object bgView = field(preview, "mBgView");
        // Ask ColorOS to relayout the folder icon children so mBgView leaves the temporary
        // ConvertBgParams layout applied during the resize-frame draw path.
        icon.requestLayout();
        icon.post(() -> {
            setFolderBackgroundVisibility(preview, true);
            syncFolderPreview(folderIcon, preview);
            if (bgView instanceof View) ((View) bgView).invalidate();
            icon.invalidate();
        });
    }

    private void hookFolderVisibility(ClassLoader cl) {
        // FlexibleFolderIcon.setIconVisible calls super then setImageDrawable(mBgDrawable).
        // Hook the subclass method so glass is reasserted after that OEM overwrite.
        for (String className : new String[] {
                "com.android.launcher3.folder.FlexibleFolderIcon",
                "com.android.launcher3.folder.FolderIcon"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                Method method = c.getDeclaredMethod("setIconVisible", boolean.class);
                hookOnce(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object icon = chain.getThisObject();
                        Object preview = field(icon, "mBackground");
                        boolean visible = Boolean.TRUE.equals(chain.getArg(0)) && !isFolderOpen(icon);
                        setFolderBackgroundVisibility(preview, visible);
                        if (visible) syncFolderPreviewDeferred(icon, preview, false);
                    } catch (Throwable e) {
                        log(5, TAG, className + ".setIconVisible apply failed", e);
                    }
                    return result;
                });
            } catch (Throwable e) {
                log(5, TAG, className + ".setIconVisible hook unavailable", e);
            }
        }
        try {
            Class<?> c = Class.forName("com.android.launcher3.folder.FlexibleFolderIcon", false, cl);
            Method setVisibility = c.getDeclaredMethod("setVisibility", int.class);
            hookOnce(setVisibility, chain -> {
                Object result = chain.proceed();
                try {
                    Object icon = chain.getThisObject();
                    if (Integer.valueOf(View.VISIBLE).equals(chain.getArg(0)) && !isFolderOpen(icon)) {
                        syncFolderPreviewDeferred(icon, field(icon, "mBackground"), false);
                    }
                } catch (Throwable e) {
                    log(5, TAG, "FlexibleFolderIcon.setVisibility apply failed", e);
                }
                return result;
            });
        } catch (Throwable e) {
            log(5, TAG, "FlexibleFolderIcon.setVisibility hook unavailable", e);
        }
    }

    private void syncFolderIconDeferred(Object folderIcon, boolean forceVisible) {
        Object preview = field(folderIcon, "mBackground");
        if (forceVisible) setFolderBackgroundVisibility(preview, true);
        syncFolderPreviewDeferred(folderIcon, preview, forceVisible);
    }

    private void syncFolderPreviewDeferred(Object folderIcon, Object previewBackground, boolean forceVisible) {
        syncFolderPreview(folderIcon, previewBackground);
        if (folderIcon instanceof View) {
            ((View) folderIcon).post(() -> {
                if (forceVisible) setFolderBackgroundVisibility(previewBackground, true);
                syncFolderPreview(folderIcon, previewBackground);
            });
        }
    }

    private void syncFolderPreview(Object folderIcon, Object previewBackground) {
        Object bgView = field(previewBackground, "mBgView");
        if (!(bgView instanceof ImageView)) return;
        ImageView image = (ImageView) bgView;
        Object original = field(previewBackground, "mBgDrawable");
        GlassDrawable installed = GlassInstaller.get(image);
        boolean open = isFolderOpen(folderIcon);
        if (enabled() && !open) {
            // ColorOS calls setImageDrawable(mBgDrawable) again while binding/layouting a folder.
            // Reassert the image-layer contract even when our background drawable is still present.
            GlassInstaller.installImage(image, currentConfig());
        } else if (installed != null) {
            GlassInstaller.restoreImage(image, original instanceof Drawable ? (Drawable) original : null);
        }
        if (open) image.setVisibility(View.INVISIBLE);
    }

    private static void setFolderBackgroundVisibility(Object previewBackground, boolean visible) {
        Object bgView = field(previewBackground, "mBgView");
        if (bgView instanceof View) ((View) bgView).setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private static boolean isFolderOpen(Object folderIcon) {
        Object folder = field(folderIcon, "mFolder");
        Object result = invokeNoArgs(folder, "isOpen");
        if (result instanceof Boolean) return (Boolean) result;
        Object value = field(folder, "mIsOpen");
        return value instanceof Boolean && (Boolean) value;
    }

    private interface ObjectAction { void run(Object object) throws Throwable; }

    private void after(String className, ClassLoader cl, String methodName, ObjectAction action) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try { action.run(chain.getThisObject()); }
                    catch (Throwable e) { log(5, TAG, className + "." + methodName + " apply failed", e); }
                    return result;
                });
            }
            if (!found) log(5, TAG, "Method not found: " + className + "." + methodName);
        } catch (Throwable e) { log(5, TAG, "Class unavailable: " + className, e); }
    }

    private void hookOnce(Executable e, io.github.libxposed.api.XposedInterface.Hooker callback) {
        synchronized (hooked) {
            if (!hooked.add(e)) return;
            e.setAccessible(true);
            hook(e).intercept(callback);
            log(4, TAG, "Hooked: " + e.getDeclaringClass().getName() + "#" + e.getName());
        }
    }

    private GlassConfig currentConfig() {
        try { return remotePreferences == null ? new GlassConfig() : GlassConfig.read(remotePreferences); }
        catch (Throwable e) {
            log(5, TAG, "Remote preferences refresh failed", e);
            return new GlassConfig();
        }
    }

    private boolean enabled() { return currentConfig().enabled; }

    private static boolean isClassOrSubclass(Object object, String className) {
        if (object == null) return false;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) return true;
        }
        return false;
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (Throwable ignored) { c = c.getSuperclass(); }
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
            } catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return false;
    }

    private static Object invokeNoArgs(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
            catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

}
