package com.nevoit.cirro.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import com.nevoit.cirro.R

/**
 * 桌面图标：每个可选图标对应 manifest 里一个 activity-alias。
 *
 * [aliasName] 是 alias 的全限定名，装着 App 的桌面快捷方式记的就是它 —— 发布之后别改名。
 */
enum class AppIcon(
    val aliasName: String,
    val label: String,
    // painterResource 不支持 adaptive-icon XML，预览只能引用位图或矢量资源。
    val previewRes: Int,
    val previewBackground: Color,
    val previewBackgroundRes: Int? = null,
) {
    Default(
        aliasName = "com.nevoit.cirro.LauncherDefault",
        label = "默认",
        previewRes = R.mipmap.ic_launcher_foreground,
        previewBackground = Color.White,
    ),
    Classic(
        aliasName = "com.nevoit.cirro.LauncherClassic",
        label = "Classic",
        previewRes = R.mipmap.ic_launcher_classic_foreground,
        previewBackground = Color.Transparent,
    ),
    Crimson(
        aliasName = "com.nevoit.cirro.LauncherCrimson",
        label = "绯红",
        previewRes = R.mipmap.ic_launcher_crimson_foreground,
        previewBackground = Color.Transparent,
        previewBackgroundRes = R.mipmap.ic_launcher_crimson_background,
    ),
    Water(
        aliasName = "com.nevoit.cirro.LauncherWater",
        label = "水池",
        previewRes = R.mipmap.ic_launcher_water_foreground,
        previewBackground = Color.Transparent,
        previewBackgroundRes = R.mipmap.ic_launcher_water_background,
    ),
}

fun currentAppIcon(context: Context): AppIcon {
    val pm = context.packageManager
    return AppIcon.entries.firstOrNull {
        pm.getComponentEnabledSetting(ComponentName(context, it.aliasName)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } ?: AppIcon.Default
}

// 先启用目标再禁用其它：中间哪一步被打断，桌面上都至少还留着一颗图标。
fun applyAppIcon(context: Context, icon: AppIcon) {
    val pm = context.packageManager
    pm.setComponentEnabledSetting(
        ComponentName(context, icon.aliasName),
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
    AppIcon.entries.forEach {
        if (it != icon) {
            pm.setComponentEnabledSetting(
                ComponentName(context, it.aliasName),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
