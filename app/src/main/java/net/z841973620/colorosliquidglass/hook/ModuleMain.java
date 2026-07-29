package net.z841973620.colorosliquidglass.hook;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import net.z841973620.colorosliquidglass.GlassConfig;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;

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
        hookDragViewMove(cl);
        hookWorkspaceDragOver(cl);
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

            // ColorOS also paints mBgDrawable directly from PreviewBackground.drawBackground;
            // suppress that object-level blur/background after the dedicated mBgView owns glass.
            try {
                Method drawBackground = c.getDeclaredMethod("drawBackground", Canvas.class, View.class);
                hookOnce(drawBackground, chain -> {
                    Object bgView = field(chain.getThisObject(), "mBgView");
                    if (enabled() && bgView instanceof View && GlassInstaller.get((View) bgView) != null) {
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
                if ((m.getName().equals("reorderAndShow")
                        || m.getName().equals("onCreateOpenAnimation")
                        || m.getName().equals("animateOpen")) && m.getParameterCount() <= 1
                        && !m.isBridge() && !m.isSynthetic()) {
                    hookOnce(m, chain -> {
                        keepFolderPopupBlurTransparent(chain.getThisObject());
                        Object result = chain.proceed();
                        keepFolderPopupBlurTransparent(chain.getThisObject());
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "Folder popup blur hook unavailable", e);
        }
    }

    private void keepFolderPopupBlurTransparent(Object popup) {
        if (!enabled()) return;
        Object longPressed = field(popup, "mLongPressedView");
        if (!isClassOrSubclass(longPressed, "com.android.launcher3.folder.FolderIcon")) return;
        // Keep the PopupBlurView object alive so popup close/drag-resize bookkeeping still runs.
        // Only skip the open alpha animation that makes the full-screen blur visible.
        setField(popup, "mAddPopupBlurView", false);
        Object blur = field(popup, "mPopBlurView");
        if (blur instanceof View) ((View) blur).setAlpha(0f);
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
                    if (enabled() && isClassOrSubclass(source, "com.android.launcher3.folder.FolderIcon")) {
                        try {
                            beginFolderDrag(source);
                            clearDragBlurProp(dragView);
                            Object content = invokeNoArgs(dragView, "getContentView");
                            if (content instanceof View) {
                                suppressLayerBlur((View) content);
                                GlassInstaller.installBackground((View) content, currentConfig());
                                GlassDrawable glass = GlassInstaller.get((View) content);
                                if (glass != null) glass.setAlpha(184);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + ".handleFolderBackground apply failed", e);
                        }
                        return null;
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Folder drag preview hook unavailable", e);
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
        });
        after("com.android.launcher3.statemanager.StateManager", cl, "onStateTransitionEnd", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
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

    private void applyClearButtonGlass(Object panel) {
        if (!enabled() || panel == null) return;
        Object clearBtn = field(panel, "mClearAllBtn");
        if (!(clearBtn instanceof View)) return;
        View button = (View) clearBtn;
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
            log(5, TAG, "applyClearButtonGlass failed", e);
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
                        .invoke(null, null);
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        return null;
    }

    private void beginFolderDrag(Object folderIcon) {
        folderDragActive = true;
        forceDepthBlur(folderIcon, 0f);
    }

    private void endFolderDrag(Object host) {
        if (!folderDragActive) return;
        folderDragActive = false;
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
                    Object content = invokeNoArgs(chain.getThisObject(), "getContentView");
                    if (content instanceof View && GlassInstaller.get((View) content) != null) {
                        ((View) content).invalidate();
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
    }

    private void syncDragOverlaySource(Object workspace) {
        Object hover = field(workspace, "mDragOverView");
        Object launcher = field(workspace, "mLauncher");
        Object dragController = invokeNoArgs(launcher, "getDragController");
        Object dragObject = field(dragController, "mDragObject");
        Object dragView = field(dragObject, "dragView");
        Object content = invokeNoArgs(dragView, "getContentView");
        if (content instanceof View && GlassInstaller.get((View) content) != null) {
            GlassInstaller.setOverlaySource((View) content, hover instanceof View ? (View) hover : null);
        }
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
