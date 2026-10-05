package com.nevoit.cirro.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.nevoit.cirro.R
import com.nevoit.material.core.component.Button
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

private val QrTileSize = 232.dp
private val QrTilePadding = 14.dp
private val QrImageSize = QrTileSize - QrTilePadding * 2
private const val SuccessDismissMillis = 900L

@Composable
fun QrLoginScreen(
    onClose: () -> Unit,
    onLoginConfirmed: () -> Unit,
    viewModel: QrLoginViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }

    val state = viewModel.state

    LaunchedEffect(state) {
        if (state is QrLoginState.Confirmed) {
            delay(SuccessDismissMillis)
            onLoginConfirmed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(
                title = "扫码登录",
                subTitle = "使用「网易云音乐」App 扫码",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                QrLoginPanel(
                    state = state,
                    qrUrl = viewModel.qrUrl,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        CloseButton(onClick = onClose)
    }
}

@Composable
private fun QrLoginPanel(
    state: QrLoginState,
    qrUrl: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QrTile(state = state, qrUrl = qrUrl, onRefresh = onRefresh)
        VGap(20.dp)
        QrStatus(state = state)
    }
}

@Composable
private fun QrTile(
    state: QrLoginState,
    qrUrl: String?,
    onRefresh: () -> Unit,
) {
    // 二维码必须深色模块压浅底才认得出，所以这层永远是白底，不跟主题走。
    Box(
        modifier = Modifier
            .size(QrTileSize)
            .clip(MaterialTheme.specs.cardShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (qrUrl != null) {
            QrImage(
                content = qrUrl,
                modifier = Modifier.size(QrImageSize),
            )
        } else {
            Text(
                text = if (state is QrLoginState.Failed) "二维码加载失败" else "正在获取二维码…",
                style = MaterialTheme.type.footnote,
                color = Color.Black.copy(alpha = 0.4f),
            )
        }

        when (state) {
            QrLoginState.Expired -> QrTileOverlay {
                Button(onClick = onRefresh) {
                    Text(text = "点击刷新")
                }
            }

            QrLoginState.Scanned -> QrTileOverlay {
                Text(
                    text = "扫码成功\n请在手机上确认",
                    style = MaterialTheme.type.headline,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                )
            }

            QrLoginState.Confirmed -> QrTileOverlay {
                Text(
                    text = "登录成功",
                    style = MaterialTheme.type.headline,
                    color = Color.Black,
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun QrTileOverlay(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun QrStatus(state: QrLoginState) {
    val (title, footnote) = when (state) {
        is QrLoginState.Waiting -> {
            "请使用「网易云音乐」App 扫描二维码" to "二维码 ${state.remainingSeconds} 秒后失效"
        }

        QrLoginState.Scanned -> "已扫码，请在手机上确认" to null
        QrLoginState.Expired -> "二维码已过期" to "请点击刷新重新获取"
        QrLoginState.Confirmed -> "登录成功" to null
        QrLoginState.Loading -> "正在获取二维码…" to null
        is QrLoginState.Failed -> "登录失败" to state.message
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.type.subHeadline,
            color = if (state is QrLoginState.Failed) {
                MaterialTheme.colors.error
            } else {
                MaterialTheme.colors.content
            },
        )
        footnote?.let {
            VGap(4.dp)
            Text(
                text = it,
                style = MaterialTheme.type.footnote,
                color = MaterialTheme.colors.contentVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QrImage(
    content: String,
    modifier: Modifier = Modifier,
) {
    val targetSizePx = with(LocalDensity.current) { QrImageSize.roundToPx() }
    val bitmap = remember(content, targetSizePx) { encodeQr(content, targetSizePx) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "登录二维码",
            modifier = modifier,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

private fun encodeQr(content: String, targetSizePx: Int): ImageBitmap? = runCatching {
    val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = Encoder.encode(content, ErrorCorrectionLevel.M, hints).matrix

    // 每个模块占整数像素。非整数缩放会把模块边缘重新采样糊掉，识别率会掉。
    val moduleSize = (targetSizePx / matrix.width).coerceAtLeast(1)
    val size = moduleSize * matrix.width
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x / moduleSize, y / moduleSize].toInt() == 1) {
                QrDark
            } else {
                QrLight
            }
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

private const val QrDark = 0xFF000000.toInt()
private const val QrLight = 0xFFFFFFFF.toInt()

@Composable
private fun BoxScope.CloseButton(onClick: () -> Unit) {
    ProvideContentColor(MaterialTheme.colors.onSecondaryContainer) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(CircleShape)
                .size(48.dp)
                .background(MaterialTheme.colors.secondaryContainer)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.chevron_left),
                tint = MaterialTheme.colors.onSecondaryContainer,
                contentDescription = "返回",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
