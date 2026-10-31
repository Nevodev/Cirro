package com.nevoit.cirro.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.model.AudioQuality
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.login.AppleMusicLoginActivity
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec
import com.nevoit.material.core.component.BottomSheet
import com.nevoit.material.core.component.ModalTopBar
import com.nevoit.material.core.component.ModalTopBarShade
import com.nevoit.material.core.component.PopupMenu
import com.nevoit.material.core.component.PopupMenuAnchor
import com.nevoit.material.core.component.PopupMenuEntry
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.layout.ListColors
import com.nevoit.material.core.layout.ListRowAccessory
import com.nevoit.material.core.layout.ListStack
import com.nevoit.material.theme.MaterialTheme
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.Row as LayoutRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colors
    val account = koinInject<AccountRepository>()
    val audio = koinInject<AudioQualityStore>()
    val quality by audio.quality.collectAsStateWithLifecycle()
    val volumeBalance by audio.volumeBalance.collectAsStateWithLifecycle()
    val profile by account.profile.collectAsStateWithLifecycle()
    val session by account.session.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf(currentAppIcon(context)) }
    var iconMenuRequested by remember { mutableStateOf(false) }
    var qualityMenuRequested by remember { mutableStateOf(false) }
    val qualityOptions = remember(audio, quality) {
        AudioQuality.supported.map { option ->
            PopupMenuEntry.Item(
                text = option.displayName,
                selected = option == quality,
                onClick = { audio.set(option) },
            )
        }
    }
    val iconOptions = remember(context, current) {
        AppIcon.entries.map { icon ->
            PopupMenuEntry.Item(
                text = icon.label,
                selected = icon == current,
                leadingContent = {
                    Box(Modifier.size(40.dp)) { AppIconPreview(icon) }
                },
                onClick = {
                    if (icon != current) {
                        applyAppIcon(context, icon)
                        current = icon
                    }
                },
            )
        }
    }
    val bottomPadding = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues()
        .calculateBottomPadding() + 24.dp

    BottomSheet(onDismissed = onBack) { slideOut ->
        ListStack(
            modifier = Modifier.fillMaxSize(),
            colors = ListColors(
                background = colors.elevatedPageBackground,
                rowBackground = colors.elevatedCardBackground,
                headerText = colors.contentVariant,
                footerText = colors.contentVariant,
            ),
            contentPadding = PaddingValues(top = 72.dp, bottom = bottomPadding),
            scrollIndicator = ScrollIndicatorSpec(startPadding = 72.dp, endPadding = bottomPadding),
        ) {
            Section(key = "netease-account") {
                Row(key = "netease-profile") {
                    LayoutRow(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(colors.iconButtonBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            profile?.avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = profile?.nickname?.takeIf { it.isNotBlank() }
                                    ?: if (session == Session.LoggedOut) "未登录" else "网易云账号",
                                style = MaterialTheme.type.body,
                            )
                            if (profile?.userId != 0L) {
                                Text(
                                    text = profile?.userId.toString(),
                                    style = MaterialTheme.type.subHeadline,
                                    color = colors.contentVariant,
                                )
                            }
                        }
                    }
                }
            }
            Section(
                header = { "个性化" },
                key = "personalization",
            ) {
                Row(
                    key = "app-icon",
                    accessory = ListRowAccessory.SelectIndicator,
                    trailing = {
                        PopupMenu(
                            items = iconOptions,
                            anchor = PopupMenuAnchor.BottomEnd,
                            offset = DpOffset(x = 22.dp, y = 0.dp),
                        ) { openMenu ->
                            LaunchedEffect(iconMenuRequested) {
                                if (iconMenuRequested) {
                                    openMenu()
                                    iconMenuRequested = false
                                }
                            }
                            Text(
                                text = current.label,
                                style = MaterialTheme.type.body,
                                color = colors.contentVariant,
                            )
                        }
                    },
                    onClick = { iconMenuRequested = true },
                ) {
                    Text(text = "桌面图标", style = MaterialTheme.type.body)
                }
            }
            Section(header = { "音频" }, key = "audio") {
                Row(
                    key = "audio-quality",
                    accessory = ListRowAccessory.SelectIndicator,
                    trailing = {
                        PopupMenu(
                            items = qualityOptions,
                            anchor = PopupMenuAnchor.BottomEnd,
                            offset = DpOffset(x = 22.dp, y = 0.dp),
                        ) { openMenu ->
                            LaunchedEffect(qualityMenuRequested) {
                                if (qualityMenuRequested) {
                                    openMenu()
                                    qualityMenuRequested = false
                                }
                            }
                            Text(
                                text = quality.displayName,
                                style = MaterialTheme.type.body,
                                color = colors.contentVariant,
                            )
                        }
                    },
                    onClick = { qualityMenuRequested = true },
                ) {
                    Text(text = "音频质量", style = MaterialTheme.type.body)
                }
                SwitchRow(
                    key = "volume-balance",
                    checked = volumeBalance,
                    onCheckedChange = audio::setVolumeBalance,
                ) {
                    Column {
                        Text(text = "音量平衡", style = MaterialTheme.type.body)
                        Text(
                            text = "自动将歌曲播放音量调节到相同水平。",
                            style = MaterialTheme.type.subHeadline,
                            color = colors.contentVariant,
                        )
                    }
                }
            }
            Section(header = { "歌词" }, key = "accounts") {
                Row(
                    key = "apple-music",
                    accessory = ListRowAccessory.Chevron,
                    onClick = {
                        context.startActivity(Intent(context, AppleMusicLoginActivity::class.java))
                    },
                ) {
                    Column {
                        Text(text = "Apple Music 已订阅账号", style = MaterialTheme.type.body)
                        Text(
                            text = "登录后可从 Apple Music 获取歌词",
                            style = MaterialTheme.type.subHeadline,
                            color = colors.contentVariant,
                        )
                    }
                }
            }
            Section(key = "logout") {
                Row(key = "logout-action", onClick = account::logout) {
                    Text(
                        text = "退出登录",
                        style = MaterialTheme.type.body,
                        color = colors.error,
                    )
                }
            }
        }
        ModalTopBarShade(visible = true)
        ModalTopBar(
            title = "账户与设置",
            leading = {
                Action(
                    iconRes = R.drawable.xmark,
                    label = "关闭",
                    onClick = slideOut,
                )
            },
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun AppIconPreview(icon: AppIcon) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedRectangle((50f / 192f) * 40.dp))
            .background(icon.previewBackground),
        contentAlignment = Alignment.Center,
    ) {
        val previewModifier = Modifier
            .fillMaxSize()
            .scale(288f / 192f)
        icon.previewBackgroundRes?.let { backgroundRes ->
            Image(
                painter = painterResource(backgroundRes),
                contentDescription = null,
                modifier = previewModifier,
            )
        }
        Image(
            painter = painterResource(icon.previewRes),
            contentDescription = null,
            modifier = previewModifier,
        )
    }
}
