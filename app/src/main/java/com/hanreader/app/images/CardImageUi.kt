package com.hanreader.app.images

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hanreader.app.WordCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The card's picture, or nothing when it has none. */
@Composable
fun CardPicture(cardId: Long, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val context = LocalContext.current
    val store = remember(context) { CardImageStore.get(context) }
    val revision by CardImageStore.revision.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, cardId, revision) {
        value = withContext(Dispatchers.IO) {
            store.file(cardId).takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    bitmap?.let {
        Image(it, contentDescription = "Картинка карточки", contentScale = ContentScale.Crop,
            modifier = modifier.height(height).aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(16.dp)))
    }
}

/** The picture with «Нарисовать / Заново / Из галереи / Убрать». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardPictureEditor(card: WordCard, meaning: String = card.translation, sentence: String = card.example) {
    val context = LocalContext.current
    val pictures = remember(context) { CardPictures.get(context) }
    val config by pictures.settings.config.collectAsState()
    val busy by pictures.busy.collectAsState()
    val messages by pictures.messages.collectAsState()
    val revision by CardImageStore.revision.collectAsState()
    val has = remember(card.id, revision) { pictures.store.has(card.id) }
    var settings by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { pictures.pick(card.id, it) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardPicture(card.id, Modifier.align(Alignment.CenterHorizontally))
        val drawing = card.id in busy
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !drawing, onClick = { if (config.ready) pictures.draw(card, meaning, sentence) else settings = true }) {
                Text(when { drawing -> "Рисую…"; has -> "Нарисовать заново"; else -> "Нарисовать картинку" })
            }
            TextButton(enabled = !drawing, onClick = { gallery.launch("image/*") }) { Text("Из галереи") }
            if (has && !drawing) TextButton(onClick = { pictures.delete(card.id) }) { Text("Убрать") }
        }
        messages[card.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (settings) CardImageSettingsDialog { settings = false }
}

@Composable
fun CardImageSettingsSection() {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text("Картинки к карточкам · Cloudflare FLUX") }
    if (show) CardImageSettingsDialog { show = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardImageSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { CardPictures.get(context).settings }
    val c by settings.config.collectAsState()
    val scope = rememberCoroutineScope()
    var account by remember(c.accountId) { mutableStateOf(c.accountId) }
    // Never kept in saved state; the stored token is never shown again.
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Картинки к карточкам", style = MaterialTheme.typography.titleLarge)
            Text("Рисует FLUX.1 [schnell] в Cloudflare Workers AI. Бесплатно — около 10 000 нейронов в день (примерно 150 картинок), " +
                "дальше по тарифу Cloudflare. В Cloudflare уходит только описание картинки: с включённым DeepSeek он сам пишет " +
                "его по-английски из слова, значения и предложения.")
            Text("Где взять: dash.cloudflare.com → Account ID (справа на главной). Токен: My Profile → API Tokens → Create Token → " +
                "шаблон «Workers AI».", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(account, { account = it.trim() }, label = { Text("Account ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text(if (c.hasToken) "Новый токен (токен сохранён)" else "API-токен Cloudflare") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(enabled = (account.isEmpty() || ImageSettings.validAccount(account)) && (account != c.accountId || token.isNotBlank()), onClick = {
                    val value = token.trim(); token = ""
                    scope.launch {
                        message = try {
                            settings.setAccount(account)
                            if (value.isNotEmpty()) withContext(Dispatchers.IO) { settings.vault.save(value) }
                            settings.refresh()
                            "Сохранено. Токен зашифрован на устройстве и не попадает в резервную копию."
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { "Не удалось сохранить. Проверь Account ID и токен." }
                    }
                }) { Text("Сохранить") }
                TextButton(enabled = c.hasToken, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { settings.vault.delete() }; settings.refresh(); message = "Токен удалён." }
                }) { Text("Удалить токен") }
            }
            if (account.isNotEmpty() && !ImageSettings.validAccount(account)) Text("Account ID — 32 латинские буквы и цифры.", color = MaterialTheme.colorScheme.error)
            Text(if (c.ready) "Готово: на карточках доступна кнопка «Нарисовать картинку»." else "Нужны и Account ID, и токен.",
                style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = onClose) { Text("Готово") }
        }
    }
}
