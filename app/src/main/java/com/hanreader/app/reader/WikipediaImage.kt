package com.hanreader.app.reader

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanreader.app.wiki.*
import kotlinx.coroutines.*

@Composable
internal fun WikipediaImage(document: WikiDocument, image: WikiImage, source: WikipediaSource, paged: Boolean = false) {
    var retry by remember(image.url) { mutableIntStateOf(0) }
    var details by remember(image.url) { mutableStateOf(false) }
    var failed by remember(image.url, retry) { mutableStateOf(false) }
    val context = LocalContext.current
    val loaded by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, WikiImageInfo>?>(null, image.url, retry, source) {
        value = null
        try {
            value = withContext(Dispatchers.IO) {
                val asset = withTimeout(20_000) { source.image(document.article, image) }
                WikiImages.checkBitmap(asset.bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 1200 || bounds.outHeight / inSampleSize > 1200) inSampleSize *= 2 }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size, options))
                bitmap.asImageBitmap() to asset.info
            }
        } catch (_: TimeoutCancellationException) { failed = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxWidth().then(if (paged) Modifier.fillMaxHeight() else Modifier).testTag("wiki-image-${image.beforeBlock}")) {
        val area = if (paged) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio((image.width.toFloat() / image.height).coerceIn(.35f, 4f))
        Box(area, contentAlignment = androidx.compose.ui.Alignment.Center) {
            val result = loaded
            if (result != null) Image(result.first, image.caption.ifBlank { image.title.removePrefix("File:") }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (failed) TextButton(onClick = { retry++ }) { Text("Загрузить изображение") }
            else CircularProgressIndicator(Modifier.size(24.dp))
        }
        if (image.caption.isNotBlank()) Text(image.caption, style = MaterialTheme.typography.bodySmall, maxLines = if (paged) 2 else Int.MAX_VALUE,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        // Reserve attribution space before IO finishes: scrolling text must not jump.
        TextButton(enabled = loaded != null, onClick = { details = true }, contentPadding = PaddingValues(0.dp)) {
            Text(loaded?.second?.license?.takeIf { it.isNotBlank() } ?: "Автор и условия использования", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        loaded?.second?.let { info ->
            if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Об изображении") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { if (image.caption.isNotBlank()) Text(image.caption); Text(image.title.removePrefix("File:")); Text(info.author.ifBlank { "Автор указан на странице файла" }); Text(info.license.ifBlank { "Условия использования указаны на странице файла" }) } },
                confirmButton = { TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.page))) } }) { Text("Страница файла") } },
                dismissButton = { TextButton(onClick = { details = false }) { Text("Закрыть") } })
        }
    }
}
