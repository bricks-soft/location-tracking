package com.brickssoft.locationtracking.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.brickssoft.locationtracking.core.Logger
import java.util.Locale

/**
 * Known OEM power-manager / auto-start / background-activity screens, picked by `Build.MANUFACTURER`
 * (case-insensitive). Every package listed here is declared in the plugin manifest's `<queries>`, so
 * `resolveActivity` can see it on Android 11+. All entries are best-effort: OEMs move these screens between
 * ROM versions, which is why each maker has several candidates, tried in order.
 */
internal object OemPowerManagers {
    private const val TAG = "LT.DeviceSettings"

    /** Value put into an extra of an [OemScreen] intent. */
    enum class ExtraValue { PACKAGE_NAME, APP_LABEL }

    /** One candidate screen: an explicit component (or an action scoped to [packageName]). */
    data class OemScreen(
        val packageName: String,
        val className: String?,
        val action: String? = null,
        val dataUri: String? = null,
        val extras: Map<String, ExtraValue> = emptyMap(),
    ) {
        fun toIntent(context: Context): Intent {
            val intent = if (className != null) {
                Intent().setComponent(ComponentName(packageName, className))
            } else {
                Intent().setPackage(packageName)
            }
            action?.let { intent.setAction(it) }
            dataUri?.let { intent.setData(Uri.parse(it)) }
            extras.forEach { (key, value) ->
                when (value) {
                    ExtraValue.PACKAGE_NAME -> intent.putExtra(key, context.packageName)
                    ExtraValue.APP_LABEL -> intent.putExtra(key, appLabel(context))
                }
            }
            return intent
        }
    }

    private class Family(val manufacturers: Set<String>, val screens: List<OemScreen>)

    private const val HUAWEI = "com.huawei.systemmanager"
    private const val HONOR = "com.hihonor.systemmanager"
    private const val MIUI_SECURITY = "com.miui.securitycenter"
    private const val MIUI_POWER = "com.miui.powerkeeper"
    private const val COLOROS_SAFE = "com.coloros.safecenter"
    private const val OPPO_SAFE = "com.oppo.safe"
    private const val COLOROS_GUARD = "com.coloros.oppoguardelf"
    private const val IQOO = "com.iqoo.secure"
    private const val VIVO_PERMISSIONS = "com.vivo.permissionmanager"
    private const val SAMSUNG_LOOL = "com.samsung.android.lool"
    private const val SAMSUNG_SM = "com.samsung.android.sm"
    private const val ONEPLUS = "com.oneplus.security"
    private const val ASUS = "com.asus.mobilemanager"
    private const val LETV = "com.letv.android.letvsafe"
    private const val HTC = "com.htc.pitroad"
    private const val MEIZU = "com.meizu.safe"
    private const val NOKIA = "com.evenwell.powersaving.g3"

    private val huaweiScreens = listOf(
        OemScreen(HUAWEI, "$HUAWEI.startupmgr.ui.StartupNormalAppListActivity"),
        OemScreen(HUAWEI, "$HUAWEI.optimize.process.ProtectActivity"),
        OemScreen(HUAWEI, "$HUAWEI.appcontrol.activity.StartupAppControlActivity"),
    )

    private val honorScreens = listOf(
        OemScreen(HONOR, "$HONOR.startupmgr.ui.StartupNormalAppListActivity"),
        OemScreen(HONOR, "$HONOR.optimize.process.ProtectActivity"),
        OemScreen(HONOR, "$HONOR.appcontrol.activity.StartupAppControlActivity"),
    )

    private val xiaomiScreens = listOf(
        OemScreen(MIUI_SECURITY, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        OemScreen(
            MIUI_POWER,
            "$MIUI_POWER.ui.HiddenAppsConfigActivity",
            extras = mapOf("package_name" to ExtraValue.PACKAGE_NAME, "package_label" to ExtraValue.APP_LABEL),
        ),
    )

    private val colorOsScreens = listOf(
        OemScreen(COLOROS_SAFE, "$COLOROS_SAFE.permission.startup.StartupAppListActivity"),
        OemScreen(COLOROS_SAFE, "$COLOROS_SAFE.startupapp.StartupAppListActivity"),
        OemScreen(OPPO_SAFE, "$OPPO_SAFE.permission.startup.StartupAppListActivity"),
        OemScreen(COLOROS_GUARD, "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        OemScreen(COLOROS_GUARD, "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity"),
    )

    private val vivoScreens = listOf(
        OemScreen(IQOO, "$IQOO.ui.phoneoptimize.AddWhiteListActivity"),
        OemScreen(IQOO, "$IQOO.ui.phoneoptimize.BgStartUpManager"),
        OemScreen(VIVO_PERMISSIONS, "$VIVO_PERMISSIONS.activity.BgStartUpManagerActivity"),
    )

    private val samsungScreens = listOf(
        OemScreen(SAMSUNG_LOOL, "com.samsung.android.sm.ui.battery.BatteryActivity"),
        OemScreen(SAMSUNG_LOOL, "com.samsung.android.sm.battery.ui.BatteryActivity"),
        OemScreen(SAMSUNG_SM, "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )

    private val onePlusScreens = listOf(
        OemScreen(ONEPLUS, "$ONEPLUS.chainlaunch.view.ChainLaunchAppListActivity"),
    )

    private val asusScreens = listOf(
        OemScreen(ASUS, "$ASUS.entry.FunctionActivity", dataUri = "mobilemanager://function/entry/AutoStart"),
        OemScreen(ASUS, "$ASUS.autostart.AutoStartActivity"),
        OemScreen(ASUS, "$ASUS.MainActivity"),
    )

    private val letvScreens = listOf(
        OemScreen(LETV, "$LETV.AutobootManageActivity"),
        OemScreen(LETV, "$LETV.BackgroundAppManageActivity"),
    )

    private val htcScreens = listOf(
        OemScreen(HTC, "$HTC.landingpage.activity.LandingPageActivity"),
    )

    private val meizuScreens = listOf(
        OemScreen(MEIZU, "$MEIZU.permission.SmartBGActivity"),
        OemScreen(
            MEIZU,
            className = null,
            action = "com.meizu.safe.security.SHOW_APPSEC",
            extras = mapOf("packageName" to ExtraValue.PACKAGE_NAME),
        ),
    )

    private val nokiaScreens = listOf(
        OemScreen(NOKIA, "$NOKIA.exception.PowerSaverExceptionActivity"),
    )

    private val families = listOf(
        Family(setOf("huawei"), huaweiScreens),
        // Honor phones from before the 2020 split still ship Huawei's system manager.
        Family(setOf("honor"), honorScreens + huaweiScreens),
        Family(setOf("xiaomi", "redmi", "poco"), xiaomiScreens),
        Family(setOf("oppo", "realme"), colorOsScreens),
        // OxygenOS 12+ is built on ColorOS.
        Family(setOf("oneplus"), onePlusScreens + colorOsScreens),
        Family(setOf("vivo", "iqoo"), vivoScreens),
        Family(setOf("samsung"), samsungScreens),
        Family(setOf("asus"), asusScreens),
        Family(setOf("letv", "leeco", "lemobile"), letvScreens),
        Family(setOf("htc"), htcScreens),
        Family(setOf("meizu"), meizuScreens),
        Family(setOf("hmd global", "nokia"), nokiaScreens),
    )

    /** Every package the table can target (all must be in the manifest `<queries>`). */
    val allPackages: Set<String> get() = families.flatMap { f -> f.screens.map { it.packageName } }.toSet()

    /** Candidate screens for [manufacturer], in the order they should be tried. */
    fun screensFor(manufacturer: String?): List<OemScreen> {
        val name = manufacturer?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (name.isEmpty()) return emptyList()
        return families
            .filter { family -> family.manufacturers.any { name == it || name.startsWith("$it ") } }
            .flatMap { it.screens }
            .distinct()
    }

    /** Intents for [manufacturer]'s screens that resolve to an exported activity we are allowed to start. */
    fun launchableIntents(context: Context, manufacturer: String?): List<Intent> =
        screensFor(manufacturer).map { it.toIntent(context) }.filter { isLaunchable(context, it) }

    private fun isLaunchable(context: Context, intent: Intent): Boolean {
        val info = try {
            @Suppress("DEPRECATION")
            // MATCH_DEFAULT_ONLY: startActivity adds CATEGORY_DEFAULT to the package-scoped (implicit) intents.
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: RuntimeException) {
            Logger.w(TAG, "resolveActivity failed for $intent", e)
            null
        }
        val activity = info?.activityInfo ?: return false
        if (!activity.exported) return false
        val permission = activity.permission ?: return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun appLabel(context: Context): String =
        try {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        } catch (e: RuntimeException) {
            context.packageName
        }
}
