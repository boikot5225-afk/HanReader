package com.hanreader.app.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.runtime.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hanreader.app.Appearance
import com.hanreader.app.GlossMode
import com.hanreader.app.ReaderKeys
import com.hanreader.app.ReadingMode
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.LexiconIcons
import com.hanreader.app.Panel
import com.hanreader.app.ReaderSave
import com.hanreader.app.Speaker
import com.hanreader.app.AppearanceStore
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.WordFont
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.isHan
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tapped word: paragraph index and token range, which the user can widen or narrow. */
/**
 * Selected tokens [first..last] of paragraph [block]. [phrase]: selected by long press and drag
 * (or «всё предложение»): the phrase sheet opens once [done], when the finger is lifted.
 */
private data class Selection(val block: Int, val first: Int, val last: Int, val phrase: Boolean = false, val done: Boolean = true)


@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onClose: () -> Unit) {
    val open by vm.open.collectAsState()
    val saved by vm.savedWords.collectAsState()
    // Underlined as "in study": once learned (30+ days) a word reads like any known word.
    val studying by vm.studyingWords.collectAsState()
    val engine by vm.engine.collectAsState()
    val known by vm.knownWords.collectAsState()
    val pinyinMode by vm.pinyinMode.collectAsState()
    val speed by vm.speed.collectAsState()
    val book = open ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    val speaker = remember(context) { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.close() } }

    val appearance by AppearanceStore.appearance.collectAsState()
    var showAppearance by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showPrepare by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    // The menu over the text: hidden while reading, a tap in the middle shows it.
    var chrome by remember { mutableStateOf(false) }
    BackHandler { if (chrome) chrome = false else onClose() }
    // A search match to mark in the text, and the place to return to after a jump.
    var highlight by remember { mutableStateOf<SearchHit?>(null) }
    var returnTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Chapters whose «трудная глава» note was already shown in this session.
    var hardShown by remember(book.entity.id) { mutableStateOf(setOf<Int>()) }
    // Shows paragraph [block] at character [char] (the page holding it); set by the page or scroll view.
    var blockJump by remember { mutableStateOf<((Int, Int) -> Unit)?>(null) }
    // One subscription per book: calling vm.bookmarks() in every recomposition re-queried the database each time.
    val bookmarksFlow = remember(book.entity.id) { vm.bookmarks(book.entity.id) }
    val bookmarks by bookmarksFlow.collectAsState(initial = emptyList())
    val highlightsFlow = remember(book.entity.id) { vm.highlights(book.entity.id) }
    val highlights by highlightsFlow.collectAsState(initial = emptyList())

    val sheetOpen = showAppearance || showToc || showPrepare || selection != null
    // Reading aloud: one sentence at a time, the page follows the voice.
    val readAloud = remember(context) { ReadAloud(context) }
    DisposableEffect(readAloud) { onDispose { readAloud.close() } }
    ImmersiveEffects(hideBars = !chrome && !sheetOpen, keepOn = appearance.keepScreenOn || readAloud.playing, brightness = appearance.brightness)

    val spec = LocalThemeSpec.current
    val scheme = MaterialTheme.colorScheme
    val hanFamily = WordFont
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val metrics = rememberRubyMetrics(measurer, appearance, spec, hanFamily)
    val colors = rubyColors(appearance, spec)

    val chapter = book.chapter
    val matcher = remember(known, engine) { KnownMatcher(known, engine) }
    // Words of this chapter treated as known (marked, learned or transparent compounds of them).
    val knownHere = remember(chapter, matcher) { chapter?.let { matcher.knownIn(it) } ?: emptySet() }
    val bookWordsFlow = remember(book.entity.id) { vm.bookWords(book.entity.id) }
    val bookWords by bookWordsFlow.collectAsState(initial = emptyList())
    val userNames by vm.userNames.collectAsState()
    val userMeanings by vm.userMeanings.collectAsState()
    val userSplits by vm.userSplits.collectAsState()
    // Names of this book (found or «Не учить») and the reader's own «Это имя».
    val names = remember(bookWords, userNames) { bookWords.filter { it.ignored }.mapTo(HashSet()) { it.word }.apply { addAll(userNames) } }
    val coverage = remember(chapter, matcher, names) { chapter?.let { KnownCoverage.of(it, matcher, names, engine) } }
    val showPinyin = pinyinMode != PinyinMode.OFF
    // Recommended words for the reader's level (blue), words not known yet (red).
    val levelMark by vm.levelMark.collectAsState()
    val readerLevel = remember(matcher, levelMark, engine) { engine?.let { Recommendations.level(levelMark, matcher, it.hsk) } }
    val bookWordMap = remember(bookWords) { bookWords.associateBy { it.word } }
    val wantRecommended = appearance.markRecommended || pinyinMode == PinyinMode.RECOMMENDED || appearance.gloss == GlossMode.RECOMMENDED
    val recommended = remember(chapter, matcher, saved, names, bookWordMap, readerLevel, appearance.recommendedPerChapter, wantRecommended) {
        val eng = engine
        if (!wantRecommended || chapter == null || eng == null || readerLevel == null) emptyList()
        else Recommendations.forChapter(chapter, matcher, saved, names, bookWordMap, eng, readerLevel, appearance.recommendedPerChapter)
    }
    val recommendedSet = remember(recommended) { recommended.mapTo(HashSet()) { it.word } }
    val chapterWords = remember(chapter) {
        chapter?.blocks?.flatMap { b -> (b as? ReaderBlock.Text)?.tokens?.filter { it.kind == TokenKind.HAN }?.map { it.text }.orEmpty() }?.toHashSet() ?: hashSetOf()
    }
    val wantUnknown = appearance.markUnknown || appearance.gloss == GlossMode.UNKNOWN
    val unknownSet = remember(chapterWords, knownHere, saved, names, wantUnknown) {
        val eng = engine
        if (!wantUnknown) emptySet()
        else chapterWords.filterTo(HashSet()) { it !in knownHere && it !in saved && it !in names && (eng == null || !VocabularyStats.isName(it, eng)) }
    }
    // Short translations above the pinyin: over unknown words or over recommended ones.
    val glossFor: Map<String, String>? = remember(appearance.gloss, unknownSet, recommendedSet, engine, chapter, userMeanings) {
        val eng = engine
        val words = when (appearance.gloss) {
            GlossMode.OFF -> null
            GlossMode.UNKNOWN -> unknownSet
            GlossMode.RECOMMENDED -> recommendedSet
        }
        if (words == null || eng == null) null
        else HashMap<String, String>().apply {
            // The reader's own meaning wins over the dictionary's.
            for (w in words) (userMeanings[w] ?: eng.glossShort(w))?.let { put(w, it) }
            // A single character gets the meaning of each reading it has on these pages (慢慢地 de, 土地 dì).
            chapter?.blocks?.forEach { b ->
                (b as? ReaderBlock.Text)?.tokens?.forEach { t ->
                    if (t.text.length == 1 && t.text in words && t.pinyin.isNotEmpty() && t.text !in userMeanings) {
                        val key = t.text + "|" + t.pinyin
                        if (key !in this) eng.glossShort(t.text, t.pinyin)?.let { put(key, it) }
                    }
                }
            }
        }
    }
    // Fading translations: a word's first few appearances in the chapter carry it, the later ones only the underline.
    val glossTokens: Map<Int, Set<Int>>? = remember(glossFor, chapter, appearance.glossFade) {
        val g = glossFor
        if (g == null || chapter == null || !appearance.glossFade) null else fadingGlosses(chapter, g, com.hanreader.app.Appearance.GLOSS_REPEATS)
    }
    val hidePinyinFor = when (pinyinMode) {
        PinyinMode.UNKNOWN -> knownHere
        PinyinMode.RECOMMENDED -> chapterWords - recommendedSet
        else -> null
    }
    val chapterCount = book.epub.chapters.size

    // Fixed insets: hiding the system bars must not reflow the text.
    val bars = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).asPaddingValues()
    var progress by remember { mutableStateOf(ReadingProgress()) }
    val scope = rememberCoroutineScope()
    // Page mode exposes its controls to the bottom panel through these.
    var jumpToPage by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    val highlightsHere = remember(highlights, chapter?.index) { highlights.filter { it.chapterIndex == chapter?.index }.groupBy { it.blockIndex } }
    val paragraph: @Composable (ReaderBlock.Text, Int, IntRange?) -> Unit = { block, i, lines ->
        val (hlMarked, hlNoted) = remember(block, highlightsHere[i]) { Highlights.tokens(block, highlightsHere[i]) }
        RubyParagraph(
            marked = hlMarked,
            noted = hlNoted,
            block = block,
            metrics = metrics,
            showPinyin = showPinyin,
            hidePinyinFor = hidePinyinFor,
            saved = studying,
            selection = selection?.takeIf { it.block == i }?.let { it.first..it.last },
            colors = colors,
            onTap = { t -> selection = Selection(i, t, t); chrome = false; highlight = null },
            onRange = { a, b, done ->
                chrome = false; highlight = null
                selection = Selection(i, minOf(a, b), maxOf(a, b), phrase = true, done = done)
            },
            lines = lines,
            glossFor = glossFor,
            glossOnly = glossTokens?.let { it[i] ?: emptySet() },
            unknownMarks = if (appearance.markUnknown) unknownSet else null,
            noMarks = remember(block, names, engine) { engine?.let { VocabularyStats.nameTokens(block, names, it) } ?: emptySet() },
            recommendedMarks = if (appearance.markRecommended) recommendedSet else null,
            highlight = highlight?.takeIf { it.chapter == chapter?.index && it.block == i }?.let { h ->
                val first = block.tokens.indexOfFirst { it.end > h.start }
                val last = block.tokens.indexOfLast { it.start < h.end }
                if (first in 0..last) first..last else null
            } ?: readAloud.current?.takeIf { it.chapter == chapter?.index && it.block == i }?.let { s ->
                Highlights.range(block, s.start, s.end)
            },
            spoken = readAloud.current?.takeIf { it.chapter == chapter?.index && it.block == i }?.let { tokensOf(block, readAloud.word) }
        )
    }

    // ---- reading aloud: next chapter, following the voice, stopping in the background ----
    readAloud.onChapterEnd = {
        val c = chapter
        if (c != null && c.index < chapterCount - 1) vm.goToChapter(c.index + 1) else readAloud.stop()
    }
    // A new chapter on screen while reading (the previous one ended, or the reader went there): read it from its position.
    LaunchedEffect(chapter?.index) {
        val c = chapter ?: return@LaunchedEffect
        if (readAloud.active && readAloud.chapterIndex != c.index) {
            val here = book.entity.chapterIndex == c.index
            if (readAloud.playing) readAloud.start(c, if (here) book.entity.blockIndex else 0, if (here) book.entity.charOffset else 0)
            else readAloud.stop()
        }
    }
    // The page turns to the sentence being read.
    LaunchedEffect(readAloud.current) {
        val s = readAloud.current ?: return@LaunchedEffect
        if (s.chapter == chapter?.index) blockJump?.invoke(s.block, s.start)
    }
    // Leaving the app pauses: without a background service Android may stop the voice at any moment.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, readAloud) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && readAloud.playing) readAloud.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    /** Jump to a paragraph, remembering where the reader was. */
    fun jump(toChapter: Int, toBlock: Int) {
        val cur = chapter ?: return
        returnTo = cur.index to book.entity.blockIndex
        chrome = false
        if (toChapter == cur.index) blockJump?.invoke(toBlock, 0) else vm.goToChapter(toChapter, block = toBlock)
    }
    val chapterBookmarks = bookmarks.filter { it.chapterIndex == chapter?.index }
    val pageBookmark = chapterBookmarks.firstOrNull { it.blockIndex in progress.firstBlock..progress.lastBlock }

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        when {
            book.error != null -> Text(book.error, Modifier.padding(bars).padding(24.dp), color = scheme.error)
            chapter == null || book.loading && chapter.index != book.entity.chapterIndex -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(if (engine == null) "Готовлю словарь…" else "Открываю главу…", color = scheme.onSurfaceVariant)
                if (engine == null) {
                    Text(
                        "После установки или обновления — несколько секунд, один раз",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            appearance.mode == ReadingMode.PAGES -> PagedChapter(
                vm = vm,
                book = book,
                chapter = chapter,
                metrics = metrics,
                colors = colors,
                appearance = appearance,
                showPinyin = showPinyin,
                hidePinyinFor = hidePinyinFor,
                showBanner = false,
                glossFor = glossFor,
                banner = {
                    if (engine != null && coverage != null && coverage.total > 0) {
                        KnownIndicator(coverage.percent, coverage.unknownDistinct, if (appearance.markRecommended) recommended.size else 0) { showPrepare = true }
                    }
                },
                paragraph = paragraph,
                bars = bars,
                speed = speed,
                keysEnabled = appearance.volumeKeys && !sheetOpen,
                onTapCenter = { chrome = !chrome },
                onAnyTap = { if (chrome) { chrome = false; true } else false },
                onProgress = { progress = it },
                registerJump = { jumpToPage = it },
                registerBlockJump = { blockJump = it },
                bookmarkedBlocks = chapterBookmarks.map { it.blockIndex }.toSet()
            )
            else -> ScrollChapter(
                vm = vm,
                book = book,
                chapter = chapter,
                appearance = appearance,
                paragraph = paragraph,
                bars = bars,
                speed = speed,
                banner = {
                    if (engine != null && coverage != null && coverage.total > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            KnownIndicator(coverage.percent, coverage.unknownDistinct, if (appearance.markRecommended) recommended.size else 0) { showPrepare = true }
                        }
                    }
                },
                footer = {
                    ChapterFooter(
                        chapter = chapter, matcher = matcher, names = names, engine = engine, count = chapterCount,
                        engineMs = engine?.loadMs,
                        onPrev = { vm.goToChapter(chapter.index - 1) },
                        onNext = { vm.goToChapter(chapter.index + 1) }
                    )
                },
                onTap = { chrome = !chrome },
                onProgress = { progress = it },
                registerJump = { jumpToPage = it },
                registerBlockJump = { blockJump = it }
            )
        }

        // ---- the menu over the text ----
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier.fillMaxWidth().background(scheme.surface)
                    .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top))
            ) {
                ReaderTopBar(
                    title = chapter?.title ?: book.entity.title,
                    subtitle = book.entity.title,
                    pinyinMode = pinyinMode,
                    onBack = onClose,
                    onToc = { showToc = true },
                    onPinyin = vm::setPinyinMode,
                    appearanceOpen = showAppearance,
                    onAppearance = { showAppearance = true },
                    reading = readAloud.active,
                    onReadAloud = {
                        val c = chapter
                        if (readAloud.active) readAloud.stop()
                        else if (c != null) {
                            val here = book.entity.chapterIndex == c.index
                            readAloud.start(c, if (here) book.entity.blockIndex else 0, if (here) book.entity.charOffset else 0)
                            chrome = false
                        }
                    },
                    bookmarked = pageBookmark != null,
                    onBookmark = {
                        val c = chapter ?: return@ReaderTopBar
                        val existing = pageBookmark
                        if (existing != null) vm.removeBookmark(existing.id) else vm.addBookmark(c, progress.firstBlock)
                    }
                )
                HorizontalDivider(color = scheme.outlineVariant)
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ReaderBottomPanel(
                progress = progress,
                chapterIndex = chapter?.index ?: 0,
                chapterCount = chapterCount,
                coverage = coverage,
                appearance = appearance,
                onJump = { target ->
                    chapter?.let { returnTo = it.index to book.entity.blockIndex }
                    jumpToPage?.invoke(target)
                },
                onPrevChapter = { chapter?.let { if (it.index > 0) vm.goToChapter(it.index - 1) } },
                onNextChapter = { chapter?.let { if (it.index < chapterCount - 1) vm.goToChapter(it.index + 1) } },
                onToc = { showToc = true },
                onPrepare = { showPrepare = true }
            )
        }
        val back = returnTo
        if (back != null && !chrome) {
            ReturnPill(
                onReturn = { returnTo = null; jump(back.first, back.second); returnTo = null },
                onDismiss = { returnTo = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bars).padding(bottom = if (readAloud.active) 110.dp else 36.dp)
            )
        }
        // A chapter well below comfortable reading: offer to prepare its words, once, for a few seconds.
        val cov = coverage
        val hard = chapter != null && cov != null && cov.total >= 50 && cov.percent < 90 && chapter.index !in hardShown
        LaunchedEffect(chapter?.index, hard) {
            if (hard) { delay(9_000); chapter?.let { hardShown = hardShown + it.index } }
        }
        if (hard && !chrome && back == null && !readAloud.active && cov != null) {
            HardChapterPill(
                percent = cov.percent,
                onPrepare = { chapter?.let { hardShown = hardShown + it.index }; showPrepare = true },
                onDismiss = { chapter?.let { hardShown = hardShown + it.index } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bars).padding(bottom = 36.dp, start = 16.dp, end = 16.dp)
            )
        }
        if (readAloud.active && !chrome) {
            ReadAloudBar(
                readAloud,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bars).padding(bottom = 36.dp, start = 16.dp, end = 16.dp)
            )
        }
    }

    val prepEngine = engine
    if (showPrepare && chapter != null && prepEngine != null) {
        PrepareChapterSheet(vm, book, chapter, prepEngine, onDismiss = { showPrepare = false })
    }

    if (showAppearance) {
        AppearanceSheet(
            onDismiss = { showAppearance = false },
            levelText = readerLevel?.let { it.title + if (it.auto) " — определён автоматически" else " — выбран в библиотеке" }
        )
    }

    if (showToc) {
        NavigationSheet(
            vm = vm,
            book = book,
            bookmarks = bookmarks,
            onDismiss = { showToc = false },
            onChapter = { index ->
                showToc = false
                selection = null
                highlight = null
                jump(index, 0)
            },
            onBookmark = { b ->
                showToc = false
                highlight = null
                jump(b.chapterIndex, b.blockIndex)
            },
            onRemoveBookmark = { vm.removeBookmark(it.id) },
            highlights = highlights,
            onHighlight = { h ->
                showToc = false
                highlight = null
                jump(h.chapterIndex, h.blockIndex)
            },
            onRemoveHighlight = { vm.removeHighlight(it.id) },
            onExport = {
                val md = Highlights.markdown(book.entity.title, book.entity.author, highlights)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, book.entity.title + " — выделения")
                    putExtra(android.content.Intent.EXTRA_TEXT, md)
                }
                runCatching { context.startActivity(android.content.Intent.createChooser(send, "Экспорт выделений")) }
            },
            onHit = { h ->
                showToc = false
                highlight = h
                jump(h.chapter, h.block)
            }
        )
    }

    val sel = selection
    val block = sel?.let { chapter?.blocks?.getOrNull(it.block) as? ReaderBlock.Text }
    val eng = engine
    // A long press on one word is the same as a tap on it.
    val asWord = sel != null && block != null && sel.phrase && sel.done && sel.first == sel.last && block.tokens[sel.first].kind == TokenKind.HAN
    if (sel != null && block != null && chapter != null && sel.phrase && sel.done && !asWord) {
        val start = block.tokens[sel.first].start
        val end = block.tokens[sel.last].end
        PhraseSheet(
            text = block.shown.substring(start, end),
            reading = block.tokens.subList(sel.first, sel.last + 1).filter { it.kind == TokenKind.HAN }.joinToString(" ") { it.pinyin },
            highlight = Highlights.at(highlights, chapter.index, sel.block, start, end),
            canWiden = Highlights.sentence(block, sel.first, sel.last) != sel.first..sel.last,
            onSentence = { val r = Highlights.sentence(block, sel.first, sel.last); selection = sel.copy(first = r.first, last = r.last) },
            onColor = { color, existing ->
                if (existing != null) vm.updateHighlight(existing.copy(color = color))
                else vm.addHighlight(chapter, sel.block, start, end, color)
            },
            onNote = { h, note -> vm.updateHighlight(h.copy(note = note)) },
            onRemove = { vm.removeHighlight(it.id); selection = null },
            onDismiss = { selection = null }
        )
    }
    if (sel != null && block != null && eng != null && chapter != null && (!sel.phrase || asWord)) {
        WordSheet(
            selection = sel,
            block = block,
            engine = eng,
            saved = saved,
            known = known,
            isKnown = matcher::isKnown,
            markOf = vm::knownMark,
            speaker = speaker,
            onKnown = vm::setKnown,
            onClearKnown = vm::clearKnown,
            highlight = Highlights.at(highlights, chapter.index, sel.block, block.tokens[sel.first].start, block.tokens[sel.last].end),
            onHighlight = { color, existing ->
                if (existing != null) vm.updateHighlight(existing.copy(color = color))
                else vm.addHighlight(chapter, sel.block, block.tokens[sel.first].start, block.tokens[sel.last].end, color)
            },
            onNote = { h, note -> vm.updateHighlight(h.copy(note = note)) },
            onRemoveHighlight = { vm.removeHighlight(it.id) },
            userMeaning = userMeanings[block.text.substring(block.tokens[sel.first].start, block.tokens[sel.last].end)],
            onUserMeaning = vm::setUserMeaning,
            userSplits = userSplits,
            onSplit = { w, split -> vm.setSplit(w, split); selection = null },
            onChange = { selection = it },
            onDismiss = { selection = null },
            recommendations = recommended.associateBy { it.word },
            userNames = userNames,
            onUserName = { w, on -> vm.setUserName(w, on); selection = null },
            onSave = { word, reading, translation, sentence, done ->
                vm.saveWord(
                    ReaderSave(
                        word = word,
                        reading = reading,
                        translation = translation,
                        sentence = sentence,
                        bookId = book.entity.id,
                        bookTitle = book.entity.title,
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title
                    ),
                    done
                )
            }
        )
    }
}

/** Where the reader is inside the chapter, for the running footer and the bottom panel. */
data class ReadingProgress(
    val position: Int = 0,
    val count: Int = 1,
    val minutesLeft: Int = 0,
    val pages: Boolean = true,
    /** Paragraphs on screen: a bookmark on any of them marks this page. */
    val firstBlock: Int = 0,
    val lastBlock: Int = 0
)

private fun minutesLeft(chars: Int, speed: Float): Int =
    if (chars <= 0) 0 else kotlin.math.ceil(chars / speed.coerceAtLeast(30f)).toInt()

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Full-screen reading: system bars hidden while reading, screen kept on, reader brightness. */
@Composable
private fun ImmersiveEffects(hideBars: Boolean, keepOn: Boolean, brightness: Float) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, hideBars) {
        val w = window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hideBars) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { w?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(window, brightness) {
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness.coerceIn(0.02f, 1f)
            w.attributes = lp
        }
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }
}

private val HEADER_H = 44.dp
/** How far a vertical swipe on a page must go to open the menu. */
private val MENU_SWIPE = 56.dp
private val FOOTER_H = 30.dp
private val BANNER_H = 64.dp

@Composable
private fun RunningHeader(text: String, bookmarked: Boolean = false, indicator: @Composable () -> Unit = {}) {
    val spec = LocalThemeSpec.current
    Row(Modifier.fillMaxWidth().height(HEADER_H), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = spec.muted, fontFamily = spec.display, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (bookmarked) Icon(LexiconIcons.BookmarkFilled, contentDescription = "Закладка", tint = spec.accent, modifier = Modifier.size(18.dp))
        indicator()
    }
}

/** «← Вернуться» after a jump by contents, search, bookmark or slider. */
@Composable
private fun ReturnPill(onReturn: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = scheme.inverseSurface, contentColor = scheme.inverseOnSurface, shadowElevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReturn) { Text("← Вернуться", color = scheme.inverseOnSurface) }
            IconButton(onClick = onDismiss) { Icon(LexiconIcons.Close, contentDescription = "Не возвращаться", tint = scheme.inverseOnSurface, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** «Трудная глава: знакомо 74%» with a way to prepare its words first. */
@Composable
private fun HardChapterPill(percent: Int, onPrepare: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = scheme.inverseSurface, contentColor = scheme.inverseOnSurface, shadowElevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text("Трудная глава: знакомо $percent%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onPrepare) { Text("Подготовиться", color = scheme.inverseOnSurface, fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = onDismiss) { Icon(LexiconIcons.Close, contentDescription = "Скрыть", tint = scheme.inverseOnSurface, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun RunningFooter(left: String, right: String) {
    val spec = LocalThemeSpec.current
    Row(Modifier.fillMaxWidth().height(FOOTER_H), verticalAlignment = Alignment.CenterVertically) {
        Text(left, Modifier.weight(1f), color = spec.muted, fontFamily = spec.display, fontSize = 12.sp, maxLines = 1)
        Text(right, color = spec.muted, fontFamily = spec.display, fontSize = 12.sp)
    }
}

/** Page mode: the chapter split into screens, turned by swipe, side taps or the volume keys. */
@Composable
internal fun PagedChapter(
    vm: ReaderViewModel,
    book: OpenBook,
    chapter: ChapterContent,
    metrics: RubyMetrics,
    colors: RubyColors,
    appearance: Appearance,
    showPinyin: Boolean,
    hidePinyinFor: Set<String>?,
    showBanner: Boolean,
    glossFor: Map<String, String>?,
    banner: @Composable () -> Unit,
    paragraph: @Composable (ReaderBlock.Text, Int, IntRange?) -> Unit,
    bars: PaddingValues,
    speed: Float,
    keysEnabled: Boolean,
    onTapCenter: () -> Unit,
    onAnyTap: () -> Boolean,
    onProgress: (ReadingProgress) -> Unit,
    registerJump: (((Int) -> Unit)?) -> Unit,
    registerBlockJump: (((Int, Int) -> Unit)?) -> Unit,
    bookmarkedBlocks: Set<Int>
) {
    val density = LocalDensity.current
    val spec = LocalThemeSpec.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(bars)) {
        val margin = appearance.margin.dp
        val widthPx = with(density) { (maxWidth - margin * 2).toPx() }
        val heightPx = with(density) { (maxHeight - HEADER_H - FOOTER_H).toPx() }
        val pageSpec = PageSpec(
            widthPx = widthPx,
            heightPx = heightPx,
            paragraphSpacingPx = with(density) { appearance.paragraphSpacing.dp.toPx() },
            bannerHeightPx = with(density) { BANNER_H.toPx() },
            showBanner = showBanner,
            gapPx = with(density) { colors.wordGap.toPx() },
            lineGapPx = with(density) { colors.lineGap.toPx() },
            showPinyin = showPinyin,
            hidePinyinFor = hidePinyinFor,
            justify = colors.justify,
            indent = colors.indent,
            glossFor = glossFor
        )
        // The pages remember which chapter they were made for: produceState keeps its last value
        // while the next one is computed, and pages of another chapter must never be drawn with
        // this chapter's paragraphs (that crashed: a page item pointing at an image found text).
        val pages by produceState<Pair<ChapterContent, List<Page>>?>(null, chapter, pageSpec, metrics) {
            value = chapter to Paginator.paginate(chapter, metrics, pageSpec) { vm.imageAspect(it) }
        }
        val list = pages?.takeIf { it.first === chapter }?.second
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }
        val hasPrev = chapter.index > 0
        val hasNext = chapter.index < book.epub.chapters.size - 1
        val lead = if (hasPrev) 1 else 0
        val total = list.size + lead + (if (hasNext) 1 else 0)
        val here = book.entity.chapterIndex == chapter.index
        val savedBlock = if (here) book.entity.blockIndex else 0
        val savedChar = if (here) book.entity.charOffset else 0
        key(chapter.index, list) {
            // The saved spot; pages are rebuilt when hints or fonts change, and a rebuilt page
            // that still holds this spot must not overwrite it with its own (earlier) start.
            val saved = remember { intArrayOf(savedBlock, savedChar) }
            val pager = rememberPagerState(initialPage = lead + Paginator.pageFor(list, savedBlock, savedChar)) { total }
            val scope = rememberCoroutineScope()
            fun turn(by: Int) {
                val target = (pager.currentPage + by).coerceIn(0, total - 1)
                scope.launch { pager.animateScrollToPage(target) }
            }
            DisposableEffect(pager) {
                registerJump { page -> scope.launch { pager.scrollToPage(lead + page.coerceIn(0, list.size - 1)) } }
                registerBlockJump { block, char ->
                    val target = lead + Paginator.pageFor(list, block, char)
                    if (pager.currentPage != target) scope.launch { pager.scrollToPage(target) }
                }
                onDispose { registerJump(null); registerBlockJump(null) }
            }
            DisposableEffect(keysEnabled) {
                ReaderKeys.active = keysEnabled
                onDispose { ReaderKeys.active = false }
            }
            LaunchedEffect(pager) { ReaderKeys.turns.collect { turn(it) } }
            LaunchedEffect(pager, speed) {
                var shownAt = System.currentTimeMillis()
                var shownChars = 0
                snapshotFlow { pager.settledPage }.collect { p ->
                    val now = System.currentTimeMillis()
                    vm.recordPage(shownChars, now - shownAt)
                    shownAt = now
                    when {
                        p < lead -> vm.goToChapter(chapter.index - 1, atEnd = true)
                        p >= lead + list.size -> vm.goToChapter(chapter.index + 1)
                        else -> {
                            val page = list[p - lead]
                            shownChars = page.chars
                            if (Paginator.pageFor(list, saved[0], saved[1]) != p - lead) {
                                saved[0] = page.firstBlock; saved[1] = page.firstChar
                                vm.savePosition(chapter.index, page.firstBlock, page.firstChar)
                            }
                            val left = list.subList(p - lead, list.size).sumOf { it.chars }
                            val blocks = page.items.filter { it !is PageItem.Banner }.map { it.block }
                            onProgress(
                                ReadingProgress(
                                    p - lead, list.size, minutesLeft(left, speed), pages = true,
                                    firstBlock = blocks.minOrNull() ?: page.firstBlock, lastBlock = blocks.maxOrNull() ?: page.firstBlock
                                )
                            )
                        }
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                Column(
                    Modifier.fillMaxSize().background(spec.background).padding(horizontal = margin)
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                if (onAnyTap()) return@detectTapGestures
                                // The running header and footer have no words: a tap anywhere on them is the menu.
                                val edge = pos.y < HEADER_H.toPx() || pos.y > size.height - FOOTER_H.toPx()
                                when {
                                    edge -> onTapCenter()
                                    pos.x < size.width * 0.3f -> turn(-1)
                                    pos.x > size.width * 0.7f -> turn(1)
                                    else -> onTapCenter()
                                }
                            }
                        }
                        // Pages turn sideways, so a swipe up or down is free: it shows or hides the menu.
                        .pointerInput(Unit) {
                            var moved = 0f
                            detectVerticalDragGestures(
                                onDragStart = { moved = 0f },
                                onDragEnd = { if (kotlin.math.abs(moved) > MENU_SWIPE.toPx()) onTapCenter() },
                                onVerticalDrag = { change, dy -> moved += dy; change.consume() }
                            )
                        }
                ) {
                    val pageIndex = index - lead
                    val onPage = list.getOrNull(pageIndex)?.items?.map { it.block }?.toSet().orEmpty()
                    RunningHeader(chapter.title, bookmarked = onPage.any { it in bookmarkedBlocks }, indicator = banner)
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        if (pageIndex < 0 || pageIndex >= list.size) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    if (pageIndex < 0) "← Предыдущий раздел" else "Следующий раздел →",
                                    color = spec.muted, fontFamily = spec.display
                                )
                            }
                        } else {
                            for (item in list[pageIndex].items) {
                                when (item) {
                                    PageItem.Banner -> Box(Modifier.fillMaxWidth().height(BANNER_H)) { banner() }
                                    is PageItem.Lines -> {
                                        val b = chapter.blocks.getOrNull(item.block) as? ReaderBlock.Text
                                        if (b != null) {
                                            if (item.spacingBefore) Spacer(Modifier.height(appearance.paragraphSpacing.dp))
                                            paragraph(b, item.block, item.lines)
                                        }
                                    }
                                    is PageItem.Image -> {
                                        val img = chapter.blocks.getOrNull(item.block) as? ReaderBlock.Image
                                        if (img != null) {
                                            if (item.spacingBefore) Spacer(Modifier.height(appearance.paragraphSpacing.dp))
                                            Box(Modifier.fillMaxWidth().height(with(density) { item.heightPx.toDp() })) { BookImage(vm, img.path) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val shown = pageIndex.coerceIn(0, list.size - 1)
                    val left = list.subList(shown, list.size).sumOf { it.chars }
                    RunningFooter(
                        left = if (left > 0) "≈ ${minutesLeft(left, speed)} мин до конца раздела" else "",
                        right = "${shown + 1} / ${list.size}"
                    )
                }
            }
        }
    }
}

/** Scroll mode: the chapter as one long column. */
@OptIn(FlowPreview::class)
@Composable
private fun ScrollChapter(
    vm: ReaderViewModel,
    book: OpenBook,
    chapter: ChapterContent,
    appearance: Appearance,
    paragraph: @Composable (ReaderBlock.Text, Int, IntRange?) -> Unit,
    bars: PaddingValues,
    speed: Float,
    banner: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    onTap: () -> Unit,
    onProgress: (ReadingProgress) -> Unit,
    registerJump: (((Int) -> Unit)?) -> Unit,
    registerBlockJump: (((Int, Int) -> Unit)?) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val blockChars = remember(chapter) {
        chapter.blocks.map { b -> if (b is ReaderBlock.Text) b.tokens.sumOf { t -> if (t.kind == TokenKind.HAN) t.text.length else 0 } else 0 }
    }
    DisposableEffect(listState) {
        registerJump { block -> scope.launch { listState.scrollToItem(block.coerceIn(0, chapter.blocks.size) + 1) } }
        registerBlockJump { block, char ->
            val item = block.coerceIn(0, chapter.blocks.size) + 1
            // Already on screen (reading aloud moved within it): stay; a jump from elsewhere scrolls.
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == item }
            if (char == 0 || !visible) scope.launch { listState.scrollToItem(item) }
        }
        onDispose { registerJump(null); registerBlockJump(null) }
    }
    // Restore the saved position once per chapter, then keep saving it while scrolling.
    LaunchedEffect(chapter.index) {
        val target = if (chapter.index == book.entity.chapterIndex) book.entity.blockIndex else 0
        // Item 0 is the chapter banner, paragraph i is item i + 1.
        if (target > 0) listState.scrollToItem(target.coerceIn(0, chapter.blocks.size) + 1)
        var saved = target
        // Reading time in scroll mode: paragraphs passed forward since the last stop, and the time it took.
        var readFrom = target
        var readAt = System.currentTimeMillis()
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(400)
            .collect { first ->
                val block = (first - 1).coerceIn(0, (chapter.blocks.size - 1).coerceAtLeast(0))
                // Still the restored paragraph: keep the exact spot saved in page mode.
                if (block != saved) { saved = block; vm.savePosition(chapter.index, block) }
                val now = System.currentTimeMillis()
                if (block > readFrom) vm.recordPage(blockChars.subList(readFrom, block).sum(), now - readAt)
                readFrom = block
                readAt = now
                val left = blockChars.drop(block).sum()
                onProgress(
                    ReadingProgress(
                        block, chapter.blocks.size.coerceAtLeast(1), minutesLeft(left, speed), pages = false,
                        firstBlock = block, lastBlock = (first + listState.layoutInfo.visibleItemsInfo.size - 2).coerceAtLeast(block)
                    )
                )
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } },
        contentPadding = PaddingValues(
            start = appearance.margin.dp, end = appearance.margin.dp,
            top = bars.calculateTopPadding() + 16.dp, bottom = bars.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(appearance.paragraphSpacing.dp)
    ) {
        item(key = "prepare:${chapter.index}") { banner() }
        itemsIndexed(chapter.blocks, key = { i, _ -> "${chapter.index}:$i" }) { i, block ->
            when (block) {
                is ReaderBlock.Text -> paragraph(block, i, null)
                is ReaderBlock.Image -> BookImage(vm, block.path)
            }
        }
        item(key = "footer:${chapter.index}") { footer() }
    }
}

/** The bottom half of the menu: position in the chapter, time left, chapters, brightness. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBottomPanel(
    progress: ReadingProgress,
    chapterIndex: Int,
    chapterCount: Int,
    coverage: KnownCoverage?,
    appearance: Appearance,
    onJump: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToc: () -> Unit,
    onPrepare: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxWidth().background(scheme.surface)
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider(color = scheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = (dragging?.toInt() ?: progress.position) + 1
            Text(
                "Раздел ${chapterIndex + 1} из $chapterCount · " + (if (progress.pages) "стр. $shown из ${progress.count}" else "абзац $shown из ${progress.count}"),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
            )
            if (progress.minutesLeft > 0) {
                Text("≈ ${progress.minutesLeft} мин", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        if (progress.count > 1) {
            Slider(
                value = dragging ?: progress.position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onJump(it.toInt()) }; dragging = null },
                valueRange = 0f..(progress.count - 1).toFloat()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevChapter, enabled = chapterIndex > 0) { Text("← Раздел") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToc) { Text("Оглавление") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNextChapter, enabled = chapterIndex < chapterCount - 1) { Text("Раздел →") }
        }
        if (coverage != null && coverage.total > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Знакомо ${coverage.percent}% · незнакомых слов: ${coverage.unknownDistinct}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (coverage.unknownDistinct > 0) TextButton(onClick = onPrepare) { Text("Подготовиться") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LexiconIcons.Sun, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            val system = appearance.brightness < 0f
            Slider(
                value = if (system) 0.5f else appearance.brightness,
                onValueChange = { v -> AppearanceStore.update { it.copy(brightness = v.coerceIn(0.02f, 1f)) } },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Яркость" }
            )
            FilterChip(
                selected = system,
                onClick = { AppearanceStore.update { it.copy(brightness = if (system) 0.5f else -1f) } },
                label = { Text("Как в системе") }
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    pinyinMode: PinyinMode,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onPinyin: (PinyinMode) -> Unit,
    appearanceOpen: Boolean,
    onAppearance: () -> Unit,
    bookmarked: Boolean = false,
    onBookmark: () -> Unit = {},
    reading: Boolean = false,
    onReadAloud: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(LexiconIcons.Back, contentDescription = "Назад в библиотеку") }
        Column(Modifier.weight(1f).clickable(onClick = onToc).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onReadAloud) {
            Icon(
                LexiconIcons.Headphones,
                contentDescription = if (reading) "Остановить чтение вслух" else "Читать вслух",
                tint = if (reading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = onBookmark) {
            Icon(
                if (bookmarked) LexiconIcons.BookmarkFilled else LexiconIcons.BookmarkLine,
                contentDescription = if (bookmarked) "Убрать закладку" else "Добавить закладку",
                tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = onToc) { Icon(LexiconIcons.ListBullets, contentDescription = "Оглавление, закладки, поиск") }
        PinyinMenu(pinyinMode, onPinyin)
        TextButton(onClick = onAppearance) {
            Text("Aa", fontFamily = WordFont, color = if (appearanceOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PinyinMenu(mode: PinyinMode, onPick: (PinyinMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                when (mode) {
                    PinyinMode.ALL -> "pīn"
                    PinyinMode.UNKNOWN -> "pīn·?"
                    PinyinMode.RECOMMENDED -> "pīn·★"
                    PinyinMode.OFF -> "pīn"
                },
                color = if (mode == PinyinMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontWeight = if (mode == PinyinMode.OFF) FontWeight.Normal else FontWeight.Bold
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Пиньинь",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PinyinMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = { open = false; onPick(option) },
                    trailingIcon = {
                        if (option == mode) Icon(LexiconIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChapterFooter(
    chapter: ChapterContent,
    matcher: KnownMatcher,
    names: Set<String>,
    engine: ZhEngine?,
    count: Int,
    engineMs: Long?,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val coverage = remember(chapter, matcher, names) { KnownCoverage.of(chapter, matcher, names, engine) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPrev, enabled = chapter.index > 0) { Text("← Назад") }
            OutlinedButton(onClick = onNext, enabled = chapter.index < count - 1) { Text("Дальше →") }
        }
        Spacer(Modifier.height(12.dp))
        if (coverage.total > 0) {
            Text(
                "Знакомо ${coverage.percent}% слов раздела · незнакомых разных слов: ${coverage.unknownDistinct}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            "Раздел ${chapter.index + 1} из $count · ${chapter.chars} знаков · разбор ${chapter.parseMs} мс" +
                (engineMs?.let { " · словарь $it мс" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BookImage(vm: ReaderViewModel, path: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val bitmap by produceState<android.graphics.Bitmap?>(null, path, maxPx) { value = vm.image(path, maxPx) }
        val bmp = bitmap
        if (bmp != null) {
            val density = LocalDensity.current
            val w = with(density) { minOf(bmp.width, maxPx).toDp() }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.width(w).heightIn(max = 520.dp),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WordSheet(
    selection: Selection,
    block: ReaderBlock.Text,
    engine: ZhEngine,
    saved: Set<String>,
    known: Set<String>,
    /** Whether a word counts as known (marks, level, cards or known parts). */
    isKnown: (String) -> Boolean,
    /** The reader's / the level's mark of a word. */
    markOf: (String) -> kotlinx.coroutines.flow.Flow<com.hanreader.app.KnownWord?>,
    speaker: Speaker,
    onKnown: (String, Boolean) -> Unit,
    onClearKnown: (String) -> Unit,
    highlight: com.hanreader.app.Highlight?,
    onHighlight: (Int, com.hanreader.app.Highlight?) -> Unit,
    onNote: (com.hanreader.app.Highlight, String) -> Unit,
    onRemoveHighlight: (com.hanreader.app.Highlight) -> Unit,
    /** The reader's own meaning of the word, if any, and how to change it (blank removes it). */
    userMeaning: String? = null,
    onUserMeaning: (String, String) -> Unit = { _, _ -> },
    /** Words the reader split, and how to split or join one. */
    userSplits: Set<String> = emptySet(),
    onSplit: (String, Boolean) -> Unit = { _, _ -> },
    onChange: (Selection) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, (Result<Boolean>) -> Unit) -> Unit,
    recommendations: Map<String, Recommendation> = emptyMap(),
    userNames: Set<String> = emptySet(),
    onUserName: (String, Boolean) -> Unit = { _, _ -> }
) {
    val tokens = block.tokens
    val first = tokens[selection.first]
    val last = tokens[selection.last]
    val word = block.text.substring(first.start, last.end)
    val entry = remember(word) { engine.lookup(word)?.takeIf { it.hasDefinition } }
    // A combination the dictionary lacks: its dictionary words (革命 + 党), else its characters.
    val composed = remember(word) { if (entry == null) engine.compose(word) else null }
    val chars = remember(word) { if (entry == null) composed ?: engine.characters(word) else emptyList() }
    // One token: its reading in context (慢慢地 de, not the dictionary's dì).
    val reading = if (selection.first == selection.last && first.pinyin.isNotEmpty() && first.text.length == 1) first.pinyin
        else entry?.pinyin ?: tokens.subList(selection.first, selection.last + 1).joinToString(" ") { it.pinyin }
    // A single character: the meanings of the reading on the page (得 de after a verb, not dé «получить»).
    val pageReading = if (selection.first == selection.last && first.text.length == 1) first.pinyin.takeIf { it.isNotEmpty() } else null
    val russian = remember(word, pageReading) { engine.russianSenses(word, pageReading) }
    val otherReadings = remember(word, pageReading) { engine.otherReadings(word, pageReading) }
    // A tapped marker of a construction in this sentence (把, 虽然 … 但是): what the construction means.
    val grammar = remember(word, selection) {
        if (selection.first != selection.last) emptyList() else {
            val r = Highlights.sentence(block, selection.first, selection.first)
            com.hanreader.app.zh.Grammar.at(tokens.subList(r.first, r.last + 1).map { it.text }, selection.first - r.first)
        }
    }
    val translation = userMeaning ?: engine.cardTranslation(word, pageReading)
        ?: chars.joinToString(" + ") { engine.cardTranslation(it.word) ?: it.shortGloss(1) }.ifEmpty { "нет в словаре" }
    val sentence = remember(word, selection) { ChapterBuilder.sentence(block.text, first.start, last.end) }
    var status by remember(word) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isSaved = word in saved

    // Neighbouring Han tokens for widening the selection.
    val canGrowLeft = selection.first > 0 && tokens[selection.first - 1].kind == TokenKind.HAN
    val canGrowRight = selection.last < tokens.lastIndex && tokens[selection.last + 1].kind == TokenKind.HAN
    val canShrink = selection.last > selection.first

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Wraps: a long combination must not squeeze the reading into a column.
            val markFlow = remember(word) { markOf(word) }
            val mark by markFlow.collectAsState(initial = null)
            val knownNow = isKnown(word)
            val canMark = word.length <= 8 && word.all { it.isHan() }
            val knownReason = when {
                mark?.source == ReaderViewModel.UNKNOWN -> "отмечено «Не знаю»"
                mark?.source == "manual" -> "отмечено «Знаю»"
                mark != null -> "знакомо по уровню " + mark!!.source.replace("hsk3:", "HSK 3.0 · ").replace("hsk2:", "HSK 2.0 · ")
                word in known -> "выучено в карточках"
                knownNow -> "знакомо по частям слова"
                else -> null
            }
            Row(verticalAlignment = Alignment.Top) {
            FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shownWord = block.shown.substring(first.start, last.end)
                // 3-4 characters share the row with the breakdown: a little smaller, so the word stays on one line.
                Text(shownWord, fontFamily = WordFont, fontSize = when { word.length > 6 -> 28.sp; word.length >= 3 -> 32.sp; else -> 40.sp }, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alignByBaseline())
                if (shownWord != word) {
                    Text(word, fontFamily = WordFont, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
                Text(reading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alignByBaseline())
            }
            // Beside the word: the switch, and under it what each character means (目 глаз, 不 не…).
            val charRows = remember(word, selection) {
                val inWord = tokens.subList(selection.first, selection.last + 1)
                val syllables = inWord.flatMap { t -> if (t.syllables.size == t.text.length) t.syllables else List(t.text.length) { "" } }
                if (entry == null || word.length !in 2..4 || !word.all { it.isHan() }) emptyList()
                else word.mapIndexed { k, c -> c.toString() to engine.charGloss(c.toString(), syllables.getOrNull(k)?.takeIf { it.isNotEmpty() }) }
                    .takeIf { rows -> rows.all { it.second != null } }.orEmpty()
            }
            if (canMark || charRows.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp).widthIn(max = 190.dp), horizontalAlignment = Alignment.End) {
                    if (canMark) KnownToggle(known = knownNow, onKnown = { onKnown(word, it) })
                    if (charRows.isNotEmpty()) CharBreakdown(charRows, Modifier.padding(top = 10.dp))
                }
            }
            }
            if (canMark && knownReason != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(knownReason.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (mark?.source == ReaderViewModel.UNKNOWN || mark?.source == "manual") {
                        TextButton(onClick = { onClearKnown(word) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("Сбросить", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OwnMeaning(word, userMeaning, onUserMeaning)
            if (russian.isNotEmpty()) {
                russian.forEachIndexed { i, sense ->
                    Text("${i + 1}. $sense", style = MaterialTheme.typography.bodyLarge)
                }
                engine.russianSource(word)?.let { src ->
                    Text(
                        "Русский · ${src.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (otherReadings.isNotEmpty()) {
                Text("Другие чтения", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                otherReadings.take(3).forEach { (p, ru) ->
                    Text("$p — $ru", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            grammar.forEach { GrammarNote(it.point) }
            if (entry != null) {
                if (russian.isNotEmpty()) {
                    Text("English", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.gloss.split("; ", "/").map { it.trim() }.filter { it.isNotEmpty() }
                    .take(if (russian.isEmpty()) 6 else 3).forEachIndexed { i, sense ->
                    Text(
                        "${i + 1}. $sense",
                        style = if (russian.isEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (russian.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                recommendations[word]?.let { r ->
                    Text(
                        "Рекомендовано для твоего уровня: в книге ${r.bookCount} раз" +
                            (if (r.level in 1..7) " · HSK ${HskSystem.HSK3.levelTitle(r.level)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalThemeSpec.current.recommendedLine
                    )
                }
                if (entry.rank > 0) {
                    Text("Частотность: №${entry.rank}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    if (composed != null) "Сочетание слов:" else "Слова нет в словаре. По иероглифам:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                chars.forEach { c ->
                    Text("${c.word}  ${c.pinyin} — ${engine.cardTranslation(c.word) ?: c.shortGloss(2)}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChange(selection.copy(first = selection.first - 1)) }, enabled = canGrowLeft) { Text("← +") }
                OutlinedButton(onClick = { onChange(selection.copy(last = selection.last - 1)) }, enabled = canShrink) { Text("−") }
                OutlinedButton(onClick = { onChange(selection.copy(last = selection.last + 1)) }, enabled = canGrowRight) { Text("+ →") }
            }
            Text(
                "Границы слова: расширь выделение, если словарь разрезал сочетание.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The dictionary glued two words together (把头 «подрядчик» where the text means 把 + 头): split it.
            val oneMultiChar = selection.first == selection.last && word.length >= 2 && word.all { it.isHan() }
            if (oneMultiChar) {
                TextButton(onClick = { onSplit(word, true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Разделить «$word» на отдельные слова")
                }
            } else if (word in userSplits) {
                TextButton(onClick = { onSplit(word, false) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Соединить «$word» обратно")
                }
            }
            Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(sentence, Modifier.padding(14.dp), fontFamily = WordFont, style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { speaker.speak(word, STUDY_LANGUAGE) }) {
                    Icon(LexiconIcons.Volume, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Слово")
                }
                OutlinedButton(onClick = { speaker.speak(sentence, STUDY_LANGUAGE) }) {
                    Icon(LexiconIcons.Volume, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Фраза")
                }
            }
            Surface(
                onClick = {
                    onSave(word, reading, translation, sentence) { r ->
                        scope.launch {
                            status = r.fold(
                                onSuccess = { created -> if (created) "Добавлено в карточки" else "Уже в карточках: добавлен новый пример" },
                                onFailure = { "Не удалось сохранить: ${it.message}" }
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (isSaved) "Добавить пример к карточке" else "Сохранить в карточки", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (entry?.gloss?.contains("(idiom)") == true) {
                Text("成语 · идиома", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val lexiconName = engine.nameKind(word)
            val isUserName = word in userNames
            if (lexiconName != null || isUserName) {
                Text(
                    "Имя собственное · " + (lexiconName?.title ?: "отмечено тобой"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (lexiconName == null) {
                OutlinedButton(
                    onClick = { onUserName(word, !isUserName) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (isUserName) "Это не имя" else "Это имя — не учить, не подчёркивать")
                }
            }
            val sentenceRange = remember(selection) { Highlights.sentence(block, selection.first, selection.last) }
            HighlightPanel(
                current = highlight,
                canWiden = sentenceRange != selection.first..selection.last,
                onColor = { onHighlight(it, highlight) },
                onSentence = { onChange(selection.copy(first = sentenceRange.first, last = sentenceRange.last, phrase = true, done = true)) },
                onNote = { n -> highlight?.let { onNote(it, n) } },
                onRemove = { highlight?.let(onRemoveHighlight) }
            )
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            speaker.message.takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A grammar construction the tapped word belongs to: its pattern, what it means, an example. */
@Composable
private fun GrammarNote(point: com.hanreader.app.zh.GrammarPoint) {
    Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Грамматика · ${point.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(point.explanation, style = MaterialTheme.typography.bodyMedium)
            Text(point.example, fontFamily = WordFont, style = MaterialTheme.typography.titleMedium)
            Text(point.exampleRu, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** «Знаю» / «Не знаю» as one small two-part switch next to the word: the current state is filled. */
@Composable
internal fun KnownToggle(known: Boolean, onKnown: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.height(36.dp).border(1.dp, scheme.outline, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
    ) {
        for (value in listOf(true, false)) {
            val selected = known == value
            Box(
                Modifier.fillMaxHeight().background(if (selected) scheme.primary else Color.Transparent)
                    .clickable { onKnown(value) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (value) "Знаю" else "Не знаю",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The read-aloud player over the text: previous / pause-play / next sentence, speed, stop. */
@Composable
private fun ReadAloudBar(r: ReadAloud, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = r::previous) { Icon(LexiconIcons.SkipBack, contentDescription = "Предыдущее предложение") }
                IconButton(onClick = { if (r.playing) r.pause() else r.resume() }) {
                    Icon(if (r.playing) LexiconIcons.Pause else LexiconIcons.Play,
                        contentDescription = if (r.playing) "Пауза" else "Продолжить", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = r::next) { Icon(LexiconIcons.SkipForward, contentDescription = "Следующее предложение") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val i = ReadAloud.RATES.indexOfFirst { it >= r.rate - 0.01f }.let { if (it < 0) 0 else it }
                    r.changeRate(ReadAloud.RATES[(i + 1) % ReadAloud.RATES.size])
                }) { Text(String.format(java.util.Locale.ROOT, "%.1f×", r.rate)) }
                IconButton(onClick = r::stop) { Icon(LexiconIcons.Close, contentDescription = "Остановить чтение") }
            }
            if (r.message.isNotEmpty()) {
                Text(r.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            }
        }
    }
}

/** The reader's own meaning: shown first; added, edited or removed in place. */
@Composable
private fun OwnMeaning(word: String, meaning: String?, onChange: (String, String) -> Unit) {
    var editing by remember(word) { mutableStateOf(false) }
    var text by remember(word, meaning) { mutableStateOf(meaning.orEmpty()) }
    if (editing) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Моё значение") }, placeholder = { Text("например: держать в руках") },
                shape = RoundedCornerShape(14.dp), maxLines = 4
            )
            Row {
                TextButton(onClick = { onChange(word, text); editing = false }) { Text("Сохранить") }
                if (meaning != null) TextButton(onClick = { onChange(word, ""); editing = false }) { Text("Удалить") }
                TextButton(onClick = { text = meaning.orEmpty(); editing = false }) { Text("Отмена") }
            }
        }
    } else if (meaning != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Моё значение", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(meaning, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = { editing = true }) { Text("Изменить") }
        }
    } else {
        TextButton(onClick = { editing = true }, contentPadding = PaddingValues(0.dp)) { Text("+ Своё значение") }
    }
}

/** One line per character of a word: the character and its meaning in a few words. */
@Composable
internal fun CharBreakdown(rows: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((c, gloss) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c, fontFamily = WordFont, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                    gloss.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
