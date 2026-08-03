package net.z841973620.colorosliquidglass

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

object RootController {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun saveConfig(context: Context, config: GlassConfig, done: (Boolean, String) -> Unit) {
        val service = App.xposedService
        if (service == null) {
            done(false, "LSPosed 服务未连接：请先在 LSPosed 中启用模块，再重新打开本应用")
            return
        }
        val remote = try {
            service.getRemotePreferences(GlassConfig.PREFS)
        } catch (t: Throwable) {
            done(false, "无法获取 LSPosed 远程配置：${t.message}")
            return
        }
        if (!config.write(remote) || !matches(remote, config)) {
            done(false, "LSPosed 远程配置写入或回读校验失败")
            return
        }
        if (!config.write(context)) {
            done(false, "本地界面配置写入失败（远程配置已保存）")
            return
        }
        done(true, "配置已同步")
    }

    /** Restarts both Launcher and SystemUI so Recents / float / split glass hooks reload. */
    fun restartSystemComponents(done: (Boolean, String) -> Unit) {
        executor.execute {
            val result = runRoot(
                "uid=\$(id -u); echo ROOT_UID=\$uid; " +
                    "if [ \"\$uid\" != \"0\" ]; then exit 126; fi; " +
                    "old_home=\$(pidof com.android.launcher); " +
                    "old_sysui=\$(pidof com.android.systemui); " +
                    "echo OLD_HOME=\$old_home; echo OLD_SYSUI=\$old_sysui; " +
                    "[ -z \"\$old_home\" ] || kill -9 \$old_home; " +
                    "[ -z \"\$old_sysui\" ] || kill -9 \$old_sysui; " +
                    "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; " +
                    "i=0; home_ok=0; sysui_ok=0; " +
                    "while [ \$i -lt 60 ]; do " +
                    "new_home=\$(pidof com.android.launcher); " +
                    "new_sysui=\$(pidof com.android.systemui); " +
                    "if [ -n \"\$new_home\" ] && [ \"\$new_home\" != \"\$old_home\" ]; then home_ok=1; fi; " +
                    "if [ -n \"\$new_sysui\" ] && [ \"\$new_sysui\" != \"\$old_sysui\" ]; then sysui_ok=1; fi; " +
                    "if [ \$home_ok -eq 1 ] && [ \$sysui_ok -eq 1 ]; then " +
                    "echo NEW_HOME=\$new_home; echo NEW_SYSUI=\$new_sysui; exit 0; fi; " +
                    "sleep 0.25; i=\$((i+1)); done; " +
                    "echo RESTART_TIMEOUT HOME=\$new_home SYSUI=\$new_sysui home_ok=\$home_ok sysui_ok=\$sysui_ok; exit 127"
            )
            main.post {
                if (result.code == 0 && result.output.contains("ROOT_UID=0")) {
                    done(true, "系统组件已重新拉起")
                } else {
                    done(false, "Root 重启失败 (${result.code})\n${result.output.takeLast(500)}")
                }
            }
        }
    }

    fun saveAndRestart(context: Context, config: GlassConfig, done: (Boolean, String) -> Unit) {
        saveConfig(context, config) { success, message ->
            if (!success) {
                done(false, message)
                return@saveConfig
            }
            restartSystemComponents { restartOk, restartMessage ->
                if (restartOk) done(true, "配置已同步，系统组件已重新拉起")
                else done(false, "$message\n$restartMessage")
            }
        }
    }

    private fun matches(preferences: android.content.SharedPreferences, config: GlassConfig): Boolean =
        preferences.getBoolean("enabled", !config.enabled) == config.enabled &&
            preferences.getBoolean("hide_desktop_icons", !config.hideDesktopIcons) == config.hideDesktopIcons &&
            preferences.getFloat("glass_intensity", Float.NaN) == config.glassIntensity &&
            preferences.getFloat("blur_radius", Float.NaN) == config.blurRadius &&
            preferences.getFloat("refraction_height", Float.NaN) == config.refractionHeight &&
            preferences.getFloat("refraction_amount", Float.NaN) == config.refractionAmount &&
            preferences.getFloat("chromatic_aberration", Float.NaN) == config.chromaticAberration &&
            preferences.getFloat("transparency", Float.NaN) == 0f &&
            preferences.getFloat("reflection_intensity", Float.NaN) == config.reflectionIntensity &&
            preferences.getFloat("highlight_intensity", Float.NaN) == config.highlightIntensity &&
            preferences.getLong("updated_at", 0L) > 0L

    private data class CommandResult(val code: Int, val output: String)

    private fun runRoot(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            CommandResult(process.waitFor(), output)
        } catch (t: Throwable) {
            CommandResult(-1, t.stackTraceToString())
        }
    }
}
