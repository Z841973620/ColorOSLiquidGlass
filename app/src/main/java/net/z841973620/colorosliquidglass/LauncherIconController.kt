package net.z841973620.colorosliquidglass

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object LauncherIconController {
    private const val ALIAS_ACTIVITY_NAME =
        "net.z841973620.colorosliquidglass.MainActivityAlias"

    fun isLauncherIconVisible(context: Context): Boolean {
        val component = ComponentName(context, ALIAS_ACTIVITY_NAME)
        val manager = context.packageManager
        val intent = Intent().setComponent(component)
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return !list.isNullOrEmpty()
    }

    /** visible=true shows the app icon on the home screen; false hides it. */
    fun setLauncherIconVisible(context: Context, visible: Boolean) {
        if (isLauncherIconVisible(context) == visible) return
        val component = ComponentName(context, ALIAS_ACTIVITY_NAME)
        val newState = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }

    /** hideDesktopIcons config: true means hide this module's launcher icon. */
    fun applyHideDesktopIcons(context: Context, hideDesktopIcons: Boolean) {
        setLauncherIconVisible(context, visible = !hideDesktopIcons)
    }
}
