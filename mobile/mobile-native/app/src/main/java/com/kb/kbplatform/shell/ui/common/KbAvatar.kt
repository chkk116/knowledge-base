package com.kb.kbplatform.shell.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest

/**
 * 头像内容（只画「图」或「首字」，外层的圆形底、描边、点击由调用方负责）。
 *
 * 为什么单独抽出来：2026-10-27 用户反馈「头像没了」。查下来 admin 的头像是
 * `https://api.dicebear.com/9.x/fun-emoji/svg?...` —— **SVG**，而工程只引了
 * `coil-compose`，Coil 2 默认解不了 SVG，加载失败后 `AsyncImage` 就留了个空圈。
 *
 * 两件事一起做掉：
 * 1) 加 `coil-svg` 依赖，并**显式**把 SvgDecoder 注册进 ImageLoader ——
 *    不依赖「加了依赖就自动识别」的隐式行为，避免哪天依赖变化又悄悄失效；
 * 2) 加载中/失败都回落成名字首字 —— dicebear 实测 4 秒才回包，弱网大概率超时，
 *    没有回落就是一个空心圆，比没头像更难看。
 */
private var cachedLoader: ImageLoader? = null

private fun imageLoaderFor(ctx: Context): ImageLoader {
    cachedLoader?.let { return it }
    val built = ImageLoader.Builder(ctx.applicationContext)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(true)
        .build()
    cachedLoader = built
    return built
}

@Composable
fun KbAvatarContent(
    url: String,
    fallbackText: String,
    fontSize: TextUnit,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val letter = fallbackText.ifBlank { "?" }.take(1)
    val ctx = LocalContext.current

    @Composable
    fun Letter() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(letter, color = tint, fontSize = fontSize, fontWeight = FontWeight.Bold)
        }
    }

    if (url.isBlank()) {
        Letter()
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
        imageLoader = imageLoaderFor(ctx),
        contentDescription = "头像",
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { Letter() },
        error = { Letter() }
    )
}
