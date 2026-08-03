package net.z841973620.colorosliquidglass.glass;

/**
 * Launcher → ashmem sampler mode. ModuleMain updates this on state transitions so
 * {@link DesktopBackdropSampler} does not rely on fragile {@code isInState} reflection alone.
 */
public final class LauncherAshmemMode {
    private static volatile boolean overviewActive;

    private LauncherAshmemMode() {}

    public static void setOverviewActive(boolean active) {
        if (overviewActive == active) return;
        overviewActive = active;
        DesktopBackdropSampler.invalidateCache();
    }

    public static boolean isOverviewActive() {
        return overviewActive;
    }
}
