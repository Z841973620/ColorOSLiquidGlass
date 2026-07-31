package net.z841973620.colorosliquidglass.hook;

import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.graphics.Canvas;
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
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.WallpaperScaleTracker;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "ColorOSLiquidGlass";
    private final Set<Executable> hooked = new HashSet<>();
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!pkg.equals("com.android.launcher")) return;
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
        hookLauncher(cl);
    }

    private void hookLauncher(ClassLoader cl) {
        // Closed folder icons own a dedicated FolderRoundImageView. Never style Folder/OplusFolder:
        // FolderIcon still dispatches this child while the open animation is running, so a glass
        // background on the container obscures the opened folder's icons.
        hookFolderPreviewBackground(cl);
        hookFolderVisibility(cl);
        hookFolderRefreshEvents(cl);
        hookFolderPopupBlur(cl);
        hookFolderDragPreview(cl);
        hookFolderDragDepthBlur(cl);
        hookRecentsClearButton(cl);
        hookToggleBarChrome(cl);
        hookDragViewMove(cl);
        hookWorkspaceDragOver(cl);
        hookUnlockGlassRefresh(cl);
        hookWallpaperScaleTracking(cl);
        after("com.android.launcher3.folder.FolderIcon", cl, "onFolderClose", o -> {
            endFolderOpen(o);
            syncFolderIconDeferred(o, true);
        });
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

            // Drag-over accept hides mBgView and paints OEM blur via CellLayout delegate.
            // Keep the glass-bearing mBgView visible; drawBackground(Canvas,View) already
            // no-ops OEM fill when LiquidGlass is installed.
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("animateToAccept")
                        || name.equals("animateToRest")
                        || name.equals("clearDrawingDelegate")
                        || name.equals("delegateDrawing")
                        || name.equals("lambda$animateToAccept$0"))) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        keepHoverFolderGlassVisible(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, className + "." + name + " hover glass failed", e);
                    }
                    return result;
                });
            }

            // ColorOS also paints mBgDrawable directly from PreviewBackground.drawBackground;
            // suppress that object-level blur/background after the dedicated mBgView owns glass.
            try {
                Method drawBackground = c.getDeclaredMethod("drawBackground", Canvas.class, View.class);
                hookOnce(drawBackground, chain -> {
                    if (!enabled()) return chain.proceed();
                    Object preview = chain.getThisObject();
                    Object bgView = field(preview, "mBgView");
                    if (bgView instanceof View && GlassInstaller.get((View) bgView) != null) {
                        return null;
                    }
                    return chain.proceed();
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
        // Click-open folder: FolderAnimUtil animates wallpaper blur 0->1; clear it.
        try {
            Class<?> folder = Class.forName("com.android.launcher3.folder.Folder", false, cl);
            for (Method m : folder.getDeclaredMethods()) {
                if (!m.getName().equals("animateOpen") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled()) beginFolderOpen(chain.getThisObject());
                    Object result = chain.proceed();
                    if (enabled()) forceDepthBlur(chain.getThisObject(), 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Folder.animateOpen blur hook unavailable", e);
        }
        // Long-press edit / Recents enter states with wallpaper blur=1; clear it.
        after("com.android.launcher3.statemanager.StateManager", cl, "goToState", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            syncPagePreviewFrameGlassForLauncher(activity);
        });
        after("com.android.launcher3.statemanager.StateManager", cl, "onStateTransitionEnd", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            syncPagePreviewFrameGlassForLauncher(activity);
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
    }

    private void endFolderOpen(Object host) {
        if (!folderOpenActive && !hasOpenFolder(resolveLauncher(host))) {
            return;
        }
        folderOpenActive = false;
        Object launcher = resolveLauncher(host);
        forceDepthBlur(launcher != null ? launcher : host, 0f);
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
     * While another item is dragged onto a folder, ColorOS hides {@code mBgView} and delegates
     * OEM blur drawing to CellLayout. Restore the glass host and reassert LiquidGlass.
     */
    private void keepHoverFolderGlassVisible(Object previewBackground) {
        if (!enabled() || previewBackground == null) return;
        Object host = field(previewBackground, "mInvalidateDelegate");
        if (!isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return;
        if (isFolderOpen(host)) return;
        Object bgView = field(previewBackground, "mBgView");
        if (bgView instanceof View) {
            ((View) bgView).setVisibility(View.VISIBLE);
        }
        setFolderBackgroundVisibility(previewBackground, true);
        syncFolderPreview(host, previewBackground);
        if (bgView instanceof View) {
            View view = (View) bgView;
            if (GlassInstaller.get(view) != null) {
                GlassInstaller.forceCapture(view);
                view.invalidate();
            }
        }
        if (host instanceof View) ((View) host).invalidate();
    }

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
