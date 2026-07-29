package net.z841973620.colorosliquidglass;

import android.content.Context;
import android.content.SharedPreferences;

public final class GlassConfig {
    public static final String PREFS = "config";
    public boolean enabled = true;
    public boolean hideDesktopIcons = false;
    public float glassIntensity = 1f;
    public float blurRadius = 0f;
    public float refractionHeight = .2f;
    public float refractionAmount = .2f;
    public float chromaticAberration = 0f;
    public float transparency = 0f;
    public float reflectionIntensity = 1f;
    public float highlightIntensity = 1f;

    public static GlassConfig read(SharedPreferences p) {
        GlassConfig c = new GlassConfig();
        c.enabled = p.getBoolean("enabled", true);
        c.hideDesktopIcons = p.getBoolean("hide_desktop_icons", false);
        c.glassIntensity = p.getFloat("glass_intensity", 1f);
        c.blurRadius = p.getFloat("blur_radius", 0f);
        c.refractionHeight = p.getFloat("refraction_height", .2f);
        c.refractionAmount = p.getFloat("refraction_amount", .2f);
        c.chromaticAberration = p.getFloat("chromatic_aberration", 0f);
        // Transparency is fixed at the default; the playground control was removed.
        c.transparency = 0f;
        c.reflectionIntensity = p.getFloat("reflection_intensity", 1f);
        c.highlightIntensity = p.getFloat("highlight_intensity", 1f);
        return c;
    }

    public static GlassConfig read(Context context) {
        return read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public boolean write(Context context) {
        return write(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public boolean write(SharedPreferences preferences) {
        SharedPreferences.Editor editor = preferences.edit();
        if (editor == null) return false;
        return editor.putBoolean("enabled", enabled)
                .putBoolean("hide_desktop_icons", hideDesktopIcons)
                .putFloat("glass_intensity", glassIntensity)
                .putFloat("blur_radius", blurRadius)
                .putFloat("refraction_height", refractionHeight)
                .putFloat("refraction_amount", refractionAmount)
                .putFloat("chromatic_aberration", chromaticAberration)
                .putFloat("transparency", 0f)
                .putFloat("reflection_intensity", reflectionIntensity)
                .putFloat("highlight_intensity", highlightIntensity)
                .putLong("updated_at", System.currentTimeMillis())
                .commit();
    }
}
