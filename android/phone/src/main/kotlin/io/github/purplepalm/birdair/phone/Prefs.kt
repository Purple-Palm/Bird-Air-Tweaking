package io.github.purplepalm.birdair.phone

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.purplepalm.birdair.core.AppOdometer
import kotlinx.serialization.json.Json

/** Screen settings the owner changes. «Разрешить команды» is deliberately not here: it starts off every time. */
data class UiSettings(
    val units: String = Units.KMH,
    val keepScreenOn: Boolean = false,
    /** "dark" (default) or "system". */
    val theme: String = "dark",
    val batteryHintHidden: Boolean = false,
    /** «Быстрая мощность»: bar, notification and watch show [io.github.purplepalm.birdair.core.Telemetry.powerFastW]. */
    val fastPower: Boolean = true,
    /** «Плавная анимация»: the bar follows the data with a critically damped spring; off: it jumps. */
    val smooth: Boolean = true,
    /** The chart window, [ChartWindow]. */
    val chartWindow: String = "300",
)

/** Private SharedPreferences. The GitHub token has a file of its own and is never logged or shown. */
class Prefs(ctx: Context) {
    private val main: SharedPreferences = ctx.getSharedPreferences("birdair", Context.MODE_PRIVATE)
    private val gh: SharedPreferences = ctx.getSharedPreferences("birdair.gh", Context.MODE_PRIVATE)
    private val secret: SharedPreferences = ctx.getSharedPreferences("birdair.gh.token", Context.MODE_PRIVATE)
    /** Uploaded log files: file name → [SentLog] as JSON. */
    private val sent: SharedPreferences = ctx.getSharedPreferences("birdair.logs", Context.MODE_PRIVATE)

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
            fastPower = main.getBoolean("fastPower", true),
            smooth = main.getBoolean("smooth", true),
            chartWindow = ChartWindow.norm(main.getString("chartWindow", "300")),
        )
        set(v) = main.edit {
            putString("units", v.units); putBoolean("keepScreenOn", v.keepScreenOn)
            putString("theme", v.theme); putBoolean("batteryHintHidden", v.batteryHintHidden)
            putBoolean("fastPower", v.fastPower); putBoolean("smooth", v.smooth); putString("chartWindow", v.chartWindow)
        }

    /** The app odometer ([AppOdometer.State]); null before the first frame ever. */
    var odometer: AppOdometer.State?
        get() = try { main.getString("odometer", null)?.let { JSON.decodeFromString(AppOdometer.State.serializer(), it) } } catch (e: Exception) { null }
        set(v) = main.edit { if (v == null) remove("odometer") else putString("odometer", JSON.encodeToString(AppOdometer.State.serializer(), v)) }

    /** «Описание лога»: carried over to the next recording until changed. */
    var logNote: String
        get() = main.getString("logNote", "") ?: ""
        set(v) = main.edit { putString("logNote", v) }

    /** In-app update: when version.json was last read (wall clock) and what it said. */
    var updateCheckedAt: Long
        get() = main.getLong("updateCheckedAt", 0L)
        set(v) = main.edit { putLong("updateCheckedAt", v) }
    var updateJson: String?
        get() = main.getString("updateJson", null)
        set(v) = main.edit { putString("updateJson", v) }

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

    fun sentLogs(): Map<String, SentLog> = sent.all.mapNotNull { (k, v) -> LogFiles.decode(v as? String)?.let { k to it } }.toMap()
    fun sentLog(name: String): SentLog? = LogFiles.decode(sent.getString(name, null))
    fun setSent(name: String, s: SentLog?) = sent.edit { if (s == null) remove(name) else putString(name, LogFiles.encode(s)) }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
