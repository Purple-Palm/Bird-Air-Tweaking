package io.github.purplepalm.birdair.phone

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Screen settings the owner changes. «Разрешить команды» is deliberately not here: it starts off every time. */
data class UiSettings(
    val units: String = Units.KMH,
    val keepScreenOn: Boolean = false,
    /** "dark" (default) or "system". */
    val theme: String = "dark",
    val batteryHintHidden: Boolean = false,
)

/** Private SharedPreferences. The GitHub token has a file of its own and is never logged or shown. */
class Prefs(ctx: Context) {
    private val main: SharedPreferences = ctx.getSharedPreferences("birdair", Context.MODE_PRIVATE)
    private val gh: SharedPreferences = ctx.getSharedPreferences("birdair.gh", Context.MODE_PRIVATE)
    private val secret: SharedPreferences = ctx.getSharedPreferences("birdair.gh.token", Context.MODE_PRIVATE)

    /** The chosen scooter. */
    var address: String?
        get() = main.getString("address", null)
        set(v) = main.edit { putString("address", v) }
    var deviceName: String?
        get() = main.getString("name", null)
        set(v) = main.edit { putString("name", v) }

    /** The owner wants the link up (set by «Подключить», cleared by «Отключить»): a sticky restart reconnects. */
    var wanted: Boolean
        get() = main.getBoolean("wanted", false)
        set(v) = main.edit { putBoolean("wanted", v) }

    var ui: UiSettings
        get() = UiSettings(
            units = Units.norm(main.getString("units", Units.KMH)),
            keepScreenOn = main.getBoolean("keepScreenOn", false),
            theme = if (main.getString("theme", "dark") == "system") "system" else "dark",
            batteryHintHidden = main.getBoolean("batteryHintHidden", false),
        )
        set(v) = main.edit {
            putString("units", v.units); putBoolean("keepScreenOn", v.keepScreenOn)
            putString("theme", v.theme); putBoolean("batteryHintHidden", v.batteryHintHidden)
        }

    var ghConfig: GhConfig
        get() {
            val d = GhConfig()
            return GhConfig(
                owner = gh.getString("owner", d.owner) ?: d.owner,
                repo = gh.getString("repo", d.repo) ?: d.repo,
                branch = gh.getString("branch", d.branch) ?: d.branch,
                folder = gh.getString("folder", d.folder) ?: d.folder,
                auto = gh.getBoolean("auto", false),
            )
        }
        set(v) = gh.edit {
            putString("owner", v.owner); putString("repo", v.repo); putString("branch", v.branch)
            putString("folder", v.folder); putBoolean("auto", v.auto)
        }

    var token: String
        get() = secret.getString("token", "") ?: ""
        set(v) = secret.edit { if (v.isEmpty()) remove("token") else putString("token", v) }
}
