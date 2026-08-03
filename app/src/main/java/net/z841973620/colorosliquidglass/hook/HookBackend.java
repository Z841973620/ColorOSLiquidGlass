package net.z841973620.colorosliquidglass.hook;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/** Pluggable Hook surface selected by {@link ColorOsVersion}. */
public interface HookBackend {
    void hookLauncher(XposedModule module, ClassLoader cl, SharedPreferences prefs);

    void hookSystemUi(XposedModule module, ClassLoader cl, SharedPreferences prefs);
}
