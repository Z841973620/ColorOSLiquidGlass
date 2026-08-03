package net.z841973620.colorosliquidglass.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects ColorOS major version so Hook backends can pick the matching OEM API surface.
 * <p>
 * ColorOS 13 / 14 share the pre-{@code LayerBlurDrawable} / pre-{@code mBgView} launcher APIs.
 * ColorOS 15+ use the existing ColorOS 16 Hook path in {@link ModuleMain}.
 */
public final class ColorOsVersion {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Pattern MAJOR = Pattern.compile("(\\d{1,2})");

    public enum Flavor {
        /** ColorOS 13 & 14 — drawable-based folder plate, SurfaceControl depth blur. */
        LEGACY_13_14,
        /** ColorOS 15+ — LayerBlur + mBgView path already implemented. */
        MODERN,
        /** Properties / probes inconclusive; caller should keep probing per ClassLoader. */
        UNKNOWN
    }

    private static volatile Flavor cached;
    private static volatile int cachedMajor = -1;

    private ColorOsVersion() {}

    public static Flavor detect() {
        Flavor local = cached;
        if (local != null && local != Flavor.UNKNOWN) return local;
        synchronized (ColorOsVersion.class) {
            if (cached != null && cached != Flavor.UNKNOWN) return cached;
            int major = readMajorVersion();
            cachedMajor = major;
            if (major == 13 || major == 14) {
                cached = Flavor.LEGACY_13_14;
            } else if (major > 0) {
                cached = Flavor.MODERN;
            } else {
                cached = Flavor.UNKNOWN;
            }
            Log.i(TAG, "ColorOS detect major=" + major + " flavor=" + cached);
            return cached;
        }
    }

    /**
     * Refine detection with the target package ClassLoader when system properties are missing.
     * A conclusive property-based major version is never overridden.
     */
    public static Flavor detect(ClassLoader cl) {
        Flavor fromProps = detect();
        if (cachedMajor > 0) return fromProps;
        if (cl == null) return fromProps;
        synchronized (ColorOsVersion.class) {
            if (cachedMajor > 0) return cached;
            try {
                boolean hasLayerBlur = classExists(cl,
                        "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable");
                boolean hasBgView = hasField(cl,
                        "com.android.launcher3.folder.OplusPreviewBackground", "mBgView");
                if (hasLayerBlur || hasBgView) {
                    cached = Flavor.MODERN;
                    Log.i(TAG, "ColorOS API probe → MODERN (LayerBlur/mBgView present)");
                    return cached;
                }
                if (classExists(cl, "com.android.launcher3.folder.OplusPreviewBackground")) {
                    cached = Flavor.LEGACY_13_14;
                    Log.i(TAG, "ColorOS API probe → LEGACY_13_14 (no LayerBlur/mBgView)");
                    return cached;
                }
                // SystemUI ClassLoader: FlexibleMenuManager is ColorOS 15+/16 float-menu surface.
                if (classExists(cl, "com.oplus.flexibletask.menu.FlexibleMenuManager")) {
                    cached = Flavor.MODERN;
                    Log.i(TAG, "ColorOS API probe → MODERN (FlexibleMenuManager present)");
                    return cached;
                }
            } catch (Throwable t) {
                Log.w(TAG, "ColorOS API probe failed", t);
            }
            return cached != null ? cached : Flavor.UNKNOWN;
        }
    }

    /** True when ColorOS 13/14 Hook backend should be used. */
    public static boolean usesLegacyLauncherApis(ClassLoader cl) {
        Flavor flavor = detect(cl);
        if (flavor == Flavor.LEGACY_13_14) return true;
        if (flavor == Flavor.MODERN) return false;
        if (cl == null) return false;
        // UNKNOWN: decide from API markers visible in this ClassLoader.
        if (classExists(cl, "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable")
                || hasField(cl, "com.android.launcher3.folder.OplusPreviewBackground", "mBgView")
                || classExists(cl, "com.oplus.flexibletask.menu.FlexibleMenuManager")) {
            return false;
        }
        if (classExists(cl, "com.android.launcher3.folder.OplusPreviewBackground")) {
            return true;
        }
        // SystemUI ClassLoader on ColorOS 14 has neither FlexibleMenuManager nor launcher
        // preview classes — prefer the legacy backend (COUI-only, no float-menu assumption).
        return true;
    }

    public static int majorVersion() {
        detect();
        return cachedMajor;
    }

    private static int readMajorVersion() {
        String[] keys = {
                "ro.build.version.oplusrom",
                "ro.build.version.opporom",
                "ro.oplus.version",
                "ro.rom.version"
        };
        for (String key : keys) {
            String value = systemProperty(key);
            int major = parseMajor(value);
            if (major > 0) return major;
        }
        int fromOplusBuild = oplusBuildMajor();
        if (fromOplusBuild > 0) return fromOplusBuild;
        return -1;
    }

    private static int parseMajor(String raw) {
        if (raw == null || raw.isEmpty()) return -1;
        String s = raw.trim();
        if (s.regionMatches(true, 0, "ColorOS", 0, 7)) {
            s = s.substring(7).trim();
        }
        if (!s.isEmpty() && (s.charAt(0) == 'V' || s.charAt(0) == 'v')) {
            s = s.substring(1);
        }
        Matcher m = MAJOR.matcher(s);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v instanceof String ? (String) v : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Best-effort map from {@code OplusBuild.getOplusOSVERSION()} / {@code VERSION.SDK_VERSION}.
     * ColorOS 13 ≈ 24–26, 14 ≈ 27–28, 15 ≈ 29–32, 16 ≈ 33+. Values vary by build; only used
     * when rom properties are empty.
     */
    private static int oplusBuildMajor() {
        try {
            Class<?> build = Class.forName("com.oplus.os.OplusBuild");
            int ver = -1;
            try {
                Method m = build.getDeclaredMethod("getOplusOSVERSION");
                Object r = m.invoke(null);
                if (r instanceof Number) ver = ((Number) r).intValue();
            } catch (Throwable ignored) { }
            if (ver < 0) {
                try {
                    Class<?> version = Class.forName("com.oplus.os.OplusBuild$VERSION");
                    Field f = version.getField("SDK_VERSION");
                    Object r = f.get(null);
                    if (r instanceof Number) ver = ((Number) r).intValue();
                } catch (Throwable ignored) { }
            }
            if (ver < 0) return -1;
            if (ver >= 33) return 16;
            if (ver >= 29) return 15;
            if (ver >= 27) return 14;
            if (ver >= 24) return 13;
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean classExists(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasField(ClassLoader cl, String className, String field) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            Class<?> cur = c;
            while (cur != null) {
                try {
                    cur.getDeclaredField(field);
                    return true;
                } catch (NoSuchFieldException e) {
                    cur = cur.getSuperclass();
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }
}
