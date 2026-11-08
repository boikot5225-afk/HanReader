package com.hanreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hanreader.app.Bookmark
import com.hanreader.app.LexiconIcons
import com.hanreader.app.WordFont
import com.hanreader.app.epub.TocEntry

/** Contents, bookmarks and search in one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationSheet(
    vm: ReaderViewModel,
    book: OpenBook,
    bookmarks: List<Bookmark>,
    onDismiss: () -> Unit,
    onChapter: (Int) -> Unit,
    onBookmark: (Bookmark) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    onHit: (SearchHit) -> Unit,
    highlights: List<com.hanreader.app.Highlight> = emptyList(),
    onHighlight: (com.hanreader.app.Highlight) -> Unit = {},
    onRemoveHighlight: (com.hanreader.app.Highlight) -> Unit = {},
    onExport: () -> Unit = {}
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            listOf("Оглавление", "Закладки", "Выделения", "Поиск").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 620.dp)) {
            when (tab) {
                0 -> Contents(book, onChapter)
                1 -> Bookmarks(bookmarks, book, onBookmark, onRemoveBookmark)
                2 -> HighlightList(highlights, book.epub.chapters.size, onHighlight, onRemoveHighlight, onExport)
                else -> Search(vm, onHit)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Contents(book: OpenBook, onPick: (Int) -> Unit) {
    val current = book.chapter?.index ?: book.entity.chapterIndex
    val entries = book.epub.toc.ifEmpty { book.epub.chapters.map { TocEntry(it.title, it.index, 0) } }
    val start = entries.indexOfLast { it.chapterIndex <= current }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(entries) { i, e ->
            val isCurrent = i == start
            Text(
                e.title,
                modifier = Modifier.fillMaxWidth().clickable { onPick(e.chapterIndex) }
                    .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .padding(start = (24 + 16 * e.depth.coerceAtMost(3)).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                fontFamily = WordFont,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Bookmarks(list: List<Bookmark>, book: OpenBook, onPick: (Bookmark) -> Unit, onRemove: (Bookmark) -> Unit) {
    if (list.isEmpty()) {
        Text(
            "Закладок пока нет. Открой меню касанием в центр страницы и нажми на значок закладки.",
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(list, key = { _, b -> b.id }) { _, b ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(b) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(LexiconIcons.BookmarkFilled, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        "Раздел ${b.chapterIndex + 1} из ${book.epub.chapters.size} · ${b.chapterTitle}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(b.snippet, fontFamily = WordFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(b) }) {
                    Icon(LexiconIcons.Trash, contentDescription = "Удалить закладку", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Search(vm: ReaderViewModel, onHit: (SearchHit) -> Unit) {
    val state by vm.search.collectAsState()
    var query by rememberSaveable { mutableStateOf(state.query) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(if (com.hanreader.app.lang.Languages.current.readingTitle != null) "Иероглифы или пиньинь без тонов" else "Слово или фраза") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            trailingIcon = {
                IconButton(onClick = { vm.search(query) }) { Icon(LexiconIcons.Search, contentDescription = "Искать") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (state.running) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        if (state.query.isNotEmpty()) {
            Text(
                when {
                    state.running -> "Ищу… найдено: ${state.hits.size}"
                    state.hits.isEmpty() -> "Ничего не найдено"
                    state.hits.size >= BookSearch.MAX_HITS -> "Показаны первые ${BookSearch.MAX_HITS} совпадений"
                    else -> "Найдено: ${state.hits.size}"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(state.hits) { _, h ->
            Column(Modifier.fillMaxWidth().clickable { onHit(h) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(h.chapterTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val text = remember(h) {
                    buildAnnotatedString {
                        append(h.snippet.substring(0, h.inSnippet.first))
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                            append(h.snippet.substring(h.inSnippet.first, h.inSnippet.last + 1))
                        }
                        append(h.snippet.substring(h.inSnippet.last + 1))
                    }
                }
                Text(text, fontFamily = WordFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
