package com.nevoit.cirro.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.R
import com.nevoit.cirro.login.AppleMusicLoginActivity
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Surface
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VDivider
import com.nevoit.material.core.component.VGap
import com.nevoit.material.theme.MaterialTheme

/**
 * 设置页：整层盖在 Main 上面，不进导航栈。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colors
    var current by remember { mutableStateOf(currentAppIcon(context)) }

    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(title = "设置", modifier = Modifier.padding(horizontal = 24.dp))

            SectionLabel("桌面图标")
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clip(MaterialTheme.specs.cardShape)
                    .background(colors.cardBackground)
            ) {
                AppIcon.entries.forEachIndexed { index, icon ->
                    if (index > 0) VDivider(Modifier.padding(start = 72.dp))
                    AppIconRow(
                        icon = icon,
                        selected = icon == current,
                        onClick = {
                            if (icon != current) {
                                applyAppIcon(context, icon)
                                current = icon
                            }
                        },
                    )
                }
            }
            Text(
                text = "切换后部分桌面要过几秒才换过来。",
                style = MaterialTheme.type.footnote,
                color = colors.contentVariant,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp),
            )

            VGap(24.dp)

            SectionLabel("账号")
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clip(MaterialTheme.specs.cardShape)
                    .background(colors.cardBackground)
            ) {
                LinkRow(label = "Apple Music", detail = "登录 / 换账号") {
                    context.startActivity(Intent(context, AppleMusicLoginActivity::class.java))
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            CircleIconButton(
                iconRes = R.drawable.chevron_left_nav,
                label = "返回",
                background = colors.iconButtonBackground,
                tint = colors.onIconButton,
                onClick = onBack,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.type.subHeadline,
        color = MaterialTheme.colors.contentVariant,
        modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun AppIconRow(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(icon.previewBackground),
            contentAlignment = Alignment.Center,
        ) {
            val previewModifier = Modifier.fillMaxSize().scale(288f / 192f)
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
        HGap()
        Text(
            text = icon.label,
            style = MaterialTheme.type.body,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.checkmark),
                contentDescription = "已选中",
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.type.body)
            Text(
                text = detail,
                style = MaterialTheme.type.footnote,
                color = MaterialTheme.colors.contentVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.chevron_right_semibold),
            contentDescription = null,
            tint = MaterialTheme.colors.contentVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
