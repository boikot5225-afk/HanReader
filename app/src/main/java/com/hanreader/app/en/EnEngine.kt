package com.hanreader.app.en

import android.content.Context
import com.hanreader.app.BookWord
import com.hanreader.app.epub.BookSource
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.lang.*
import com.hanreader.app.reader.BookAnalysis
import com.hanreader.app.reader.RToken
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.*

/** First offline English reader module. Context rules are local and intentionally conservative. */
class EnEngine(val dictionary: EnDictionary, override val loadMs: Long = 0, private val rules: ContextRules = ContextRules.EMPTY) : AlphabeticModule {
    override val info get() = Languages.ENGLISH
    private val posNames = mapOf("noun" to "сущ.", "verb" to "гл.", "adj" to "прил.", "adv" to "нар.",
        "pron" to "мест.", "det" to "опред.", "article" to "артикль", "prep" to "предл.", "conj" to "союз",
        "intj" to "межд.", "num" to "числ.", "phrase" to "выраж.", "proverb" to "пословица",
        "particle" to "частица", "contraction" to "сокр.", "name" to "имя")
    private val cache = object : LinkedHashMap<String, List<LexAnalysis>>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LexAnalysis>>?) = size > 2_000
    }

    private fun convert(a: EnAnalysis, possessive: Boolean = false, canonical: EnAnalysis? = null): LexAnalysis {
        val e = a.entry
        val entry = LexEntry(canonical?.entry?.word ?: e.word, posNames[e.pos] ?: e.pos, e.russian.sortedBy { if (LOW_PRIORITY.containsMatchIn(it)) 1 else 0 }, e.english, rank = canonical?.entry?.rank ?: e.rank,
            key = (canonical?.entry?.word ?: e.word) + "/" + e.pos, pronunciation = (canonical?.entry ?: e).pronunciations.map { it.ipa }.distinct().take(2).joinToString(" · "),
            source = e.russianSource)
        val description = buildList {
            if (possessive) add("притяжательная форма")
            if (("plural" in a.tags || canonical != null) && e.pos != "verb") add("мн. ч.")
            if ("participle" in a.tags) add(if ("past" in a.tags) "причастие прошедшего времени" else "-ing форма")
            else if ("past" in a.tags) add("Past Simple")
            else if ("third-person" in a.tags) add("3-е лицо ед. ч., настоящее время")
            if ("gerund" in a.tags) add("-ing форма")
            if ("comparative" in a.tags) add("сравнительная степень")
            if ("superlative" in a.tags) add("превосходная степень")
            if ("alternative" in a.tags) add("вариант написания")
        }.distinct().joinToString(" · ")
        return LexAnalysis(a.form, entry, description)
    }

    private fun lexical(word: String): List<LexAnalysis> {
        synchronized(cache) { cache[word] }?.let { return it }
        val raw = dictionary.analyses(word)
        val nounBases = raw.filter { it.entry.pos == "noun" && "plural" in it.tags && EnForms.key(it.entry.word) != EnForms.key(word) }
            .distinctBy { it.entry.word }
        val base = nounBases.singleOrNull()
        // Preserve the plural article's Russian meaning while using its attested singular for study.
        val decoded = raw.map { a -> convert(a, canonical=base?.takeIf { a.entry.pos == "noun" && a.tags.isEmpty() && EnForms.key(a.entry.word)==EnForms.key(word) }) }
            .distinctBy { it.entry.key to it.grammar }
        return synchronized(cache) { cache.getOrPut(word) { decoded } }
    }

    private fun nextWord(tokens: List<RToken>, index: Int, direction: Int): Int? {
        val space = tokens.getOrNull(index + direction) ?: return null
        if (space.kind != TokenKind.SPACE || space.text.any { it == '\n' || it == '\r' }) return null
        return (index + 2 * direction).takeIf { tokens.getOrNull(it)?.kind == TokenKind.HAN }
    }

    private fun contraction(word: String, next: String?, lookup: EnLookup): LexAnalysis? {
        var expansions = lookup.form.expansions
        if (expansions.isEmpty()) return null
        val nextAnalyses = next?.let { dictionary.analyses(it) }.orEmpty()
        if (EnForms.key(word).endsWith("'d") && next != null) {
            val participle = EnForms.key(next) in UNCHANGED_PARTICIPLES ||
                nextAnalyses.any { it.entry.pos == "verb" && "participle" in it.tags && "past" in it.tags }
            val baseVerb = nextAnalyses.any { it.entry.pos == "verb" && EnForms.key(it.entry.word) == EnForms.key(next) }
            if (participle && !baseVerb) expansions = expansions.filter { "had" in it.words }
            else if (baseVerb && !participle) expansions = expansions.filter { "would" in it.words }
        }
        if (EnForms.key(word).endsWith("'s") && next != null) {
            if (EnForms.key(next) == "been") expansions = expansions.filter { "has" in it.words }
            else if (nextAnalyses.any { "participle" in it.tags && "present" in it.tags })
                expansions = expansions.filter { "is" in it.words }
        }
        if (expansions.isEmpty()) expansions = lookup.form.expansions
        val own = lexical(word).firstOrNull { it.entry.ru.isNotEmpty() }
        val shown = EnForms.key(word).let { if (it.startsWith("i'")) "I" + it.drop(1) else it }
        val ownRussian = own?.entry?.ru.orEmpty().takeIf { expansions==lookup.form.expansions }.orEmpty()
        val explained = expansions.mapNotNull { explain(it) }
        val entry = LexEntry(shown, "сокр.", ownRussian.ifEmpty { explained.ifEmpty { expansions.map { "Сокращение: ${it.text}" } } },
            expansions.map { it.text }, key = shown + "/contraction", pronunciation = own?.entry?.pronunciation.orEmpty(),
            source = own?.entry?.source ?: "грамматический разбор")
        return LexAnalysis(word, entry, "сокращение: " + expansions.joinToString(" / ") { it.text })
    }

    private fun explain(expansion: EnExpansion): String? {
        val words=expansion.words
        val first=words.firstOrNull() ?: return null
        val keys=words.map(EnForms::key)
        val pronouns=mapOf("i" to "я","you" to "ты / вы","he" to "он","she" to "она","it" to "это / оно",
            "we" to "мы","they" to "они","who" to "кто","what" to "что","where" to "где","when" to "когда",
            "why" to "почему","how" to "как","that" to "это","there" to "там","here" to "здесь","this" to "это")
        if(keys.size==2 && keys.last()=="not") return when(keys.first()) {
            "did" -> "не (${expansion.text} — отрицание в прошедшем времени)"
            "do","does" -> "не (${expansion.text} — отрицание в настоящем времени)"
            "can","could" -> "не мочь; не иметь возможности (${expansion.text})"
            "will","shall" -> "не (${expansion.text} — отрицание будущего или отказ)"
            else -> "не (${expansion.text} — отрицательная форма)"
        }
        val subject=pronouns[keys.first()] ?: lexical(first).firstOrNull { it.entry.pos=="сущ." }?.entry?.ru?.firstOrNull()?.substringBefore(',')?.substringBefore(';') ?: return null
        return when(keys.getOrNull(1)) {
            "am","is","are" -> "$subject (${expansion.text}; форма глагола «быть»)"
            "have","has","had" -> "$subject (${expansion.text}; вспомогательный глагол перфекта)"
            "would" -> "$subject бы (${expansion.text}; также будущее в прошедшем)"
            "will" -> "$subject (${expansion.text}; будущее время)"
            else -> null
        }
    }

    private fun choices(word: String, before: String? = null, after: String? = null, before2: String? = null, after2: String? = null): List<LexAnalysis> {
        val ordinary = lexical(word)
        val lookup = if (word.any { it in "'’‘" }) dictionary.lookup(word) else null
        val possessive = lookup?.possessiveAnalyses.orEmpty().map { convert(it, true) }
        val contracted = lookup?.let { contraction(word, after, it) }
        if (lookup == null && ordinary.size <= 1) return ordinary
        val next = after?.let { lexical(it) }.orEmpty()
        if (lookup?.form?.possessive == null && contracted != null) return listOf(contracted) + ordinary
        if (possessive.isNotEmpty()) {
            val nounPhraseAfter = after2?.let { EnForms.key(it) !in (OBJECTS+SUBJECTS+NOUN_MARKERS) && lexical(it).any { x -> x.entry.pos == "сущ." } } == true &&
                next.any { it.entry.pos == "прил." } &&
                (next.none { it.entry.pos == "сущ." } || dictionary.contains("$after $after2"))
            val contractionFirst = !nounPhraseAfter && (next.any { "причастие" in it.grammar || "-ing" in it.grammar } ||
                (next.any { it.entry.pos == "прил." } && next.none { it.entry.pos == "сущ." }))
            return if (contractionFirst && contracted != null) listOf(contracted) + possessive + ordinary
            else possessive + listOfNotNull(contracted) + ordinary
        }
        if (ordinary.isEmpty()) return listOfNotNull(contracted)
        val key = EnForms.key(word)
        val previous = before?.let(EnForms::key)
        val following = after?.let(EnForms::key)
        val preferredLemma = PAST_FIRST[key]
        val previousChoices = before?.let(::lexical).orEmpty()
        val pastCandidate = ordinary.any { it.entry.isVerb && ("Past Simple" in it.grammar || "причастие прошедшего" in it.grammar) }
        val infinitive = previous in setOf("can","could","may","might","will","would","shall","should","must","to")
        val title = key in setOf("private","general","major","captain","colonel","lieutenant","corporal") &&
            word.firstOrNull()?.isUpperCase()==true && after?.firstOrNull()?.isUpperCase()==true
        val secondBefore = before2?.let(EnForms::key)
        fun score(a: LexAnalysis): Int {
            var value = if (a.entry.ru.isEmpty()) 5 else 0
            if (a.entry.isName) value += 3
            if (a.entry.isVerb && a.entry.rank==0) value+=2
            if (key in MODALS && a.entry.isVerb && EnForms.key(a.entry.lemma)==key && (previous in SUBJECTS || following !in (FINITE_AUX+MODALS) && next.any { it.entry.isVerb && EnForms.key(it.entry.lemma)==following && it.grammar.isEmpty() })) value-=12
            if (pastCandidate && key !in MODALS && a.entry.isVerb && ("Past Simple" in a.grammar || "причастие прошедшего" in a.grammar)) value-=5
            if (a.entry.isVerb && infinitive) value-=24
            if (a.entry.isVerb && following in (OBJECTS + setOf("myself","yourself","himself","herself","itself","ourselves","yourselves","themselves"))) value-=26
            if (a.entry.isVerb && previousChoices.firstOrNull()?.let { it.entry.pos=="сущ." && "мн. ч." in it.grammar }==true) value-=12
            if (a.entry.pos=="сущ." && next.firstOrNull()?.entry?.pos!="сущ." && secondBefore in NOUN_MARKERS && previousChoices.any { it.entry.pos=="прил." }) value-=16
            if (a.entry.pos=="мест." && (previous in BE_FORMS || secondBefore in BE_FORMS && previous in SUBJECTS)) value-=24
            if (a.entry.pos=="нар." && previous in OBJECTS && before2?.let { lexical(it).any { x->x.entry.isVerb } }==true) value-=25
            if (title && a.entry.pos=="сущ.") value-=24
            if (preferredLemma != null && EnForms.key(a.entry.lemma) == preferredLemma && a.entry.isVerb) value -= 3
            if (previous in NOUN_MARKERS) {
                if (next.any { it.entry.pos == "сущ." } && a.entry.pos == "прил.") value -= 15
                else if (a.entry.pos == "сущ.") value -= 12
            } else if (previous in SUBJECTS || following in NOUN_MARKERS) {
                if (a.entry.isVerb && a.grammar.contains("Past Simple") && EnForms.key(a.entry.lemma) != key) value -= 12
                else if (a.entry.isVerb) value -= 8
            }
            if (previous in setOf("have", "has", "had") && a.grammar.contains("причастие")) value -= 20
            if (previous in setOf("am", "is", "are", "was", "were", "be", "been") &&
                (a.grammar.contains("-ing") || a.grammar.contains("причастие"))) value -= 20
            if (key == "left" && next.any { it.entry.pos == "сущ." } && a.entry.pos == "прил.") value -= 20
            return value
        }
        return ordinary.sortedWith(compareBy<LexAnalysis>(::score).thenBy { if (it.entry.isVerb && it.grammar.isNotEmpty()) it.entry.rank.takeIf { r -> r>0 } ?: Int.MAX_VALUE else 0 })
    }

    fun tokenize(text: String): List<RToken> {
        val result = ArrayList<RToken>()
        var i = 0
        while (i < text.length) {
            val start = i
            val c = text[i]
            val kind = when {
                info.isWordChar(c) || (c in "'’" && listOf("em", "cause").any { tail ->
                    text.regionMatches(i + 1, tail, 0, tail.length, ignoreCase = true) &&
                        (i + tail.length + 1 >= text.length || !text[i + tail.length + 1].isLetter()) }) -> {
                    i++
                    while (i < text.length && (info.isWordChar(text[i]) || Character.getType(text[i]) == Character.NON_SPACING_MARK.toInt() ||
                                (text[i] in "'’-" && i + 1 < text.length && info.isWordChar(text[i + 1])))) i++
                    // A terminal apostrophe after plural s belongs to the possessive, unless it closes a quotation.
                    if (i < text.length && text[i] in "'’" && text[i - 1].lowercaseChar() == 's' &&
                        (start == 0 || text[start - 1] !in "'‘’")) i++
                    TokenKind.HAN
                }
                c.isWhitespace() -> { while (i < text.length && text[i].isWhitespace()) i++; TokenKind.SPACE }
                c.isDigit() -> { while (i < text.length && text[i].isLetterOrDigit()) i++; TokenKind.OTHER }
                else -> { i++; TokenKind.PUNCT }
            }
            result += RToken(text.substring(start, i), "", kind, start, i, kind == TokenKind.HAN)
        }
        return result
    }

    override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
        val tokens = tokenize(text)
        val out = tokens.mapIndexed { i, token ->
            if (token.kind != TokenKind.HAN) token else {
                val chosen = choices(token.text, nextWord(tokens, i, -1)?.let { tokens[it].text },
                    nextWord(tokens, i, 1)?.let { tokens[it].text },
                    nextWord(tokens,i,-1)?.let { nextWord(tokens,it,-1) }?.let { tokens[it].text },
                    nextWord(tokens,i,1)?.let { nextWord(tokens,it,1) }?.let { tokens[it].text }).firstOrNull()
                token.copy(pinyin = chosen?.entry?.key.orEmpty(), defined = chosen != null)
            }
        }.toMutableList()
        // One hint per adjacent expression; never join across punctuation or a paragraph break.
        var i = 0
        while (i < out.size) {
            val range = phraseStarting(out, text, i)
            if (range != null) {
                val key = phraseKey(out, text, range.first, range.last)!!
                out[i] = out[i].copy(pinyin = key, contextMeaning = null)
                for (j in i + 1..range.last) if (out[j].kind == TokenKind.HAN) out[j] = out[j].copy(pinyin = IN_PHRASE, contextMeaning = null)
                i = range.last + 1
            } else i++
        }
        applySenses(out,text)
        PreparedText(text, text, out)
    }

    override fun cardAnalyses(tokens: List<RToken>, text: String, a: Int, b: Int): List<LexAnalysis> {
        val word = text.substring(tokens[a].start, tokens[b].end)
        val reading = tokens[a].pinyin
        val phrase = if (a!=b) phraseKey(tokens,text,a,b) else null
        val all = if (a!=b) choices(phrase ?: word)
            else if (' ' in reading && reading!=IN_PHRASE) choices(reading) + choices(word)
            else choices(word, nextWord(tokens,a,-1)?.let { tokens[it].text },nextWord(tokens,a,1)?.let { tokens[it].text },
                nextWord(tokens,a,-1)?.let { nextWord(tokens,it,-1) }?.let { tokens[it].text },
                nextWord(tokens,a,1)?.let { nextWord(tokens,it,1) }?.let { tokens[it].text }).sortedBy { if(it.entry.key==reading) 0 else 1 }
        val selected = tokens[a].contextMeaning.takeIf { a==b || phrase==reading }
        return all.mapIndexed { i,analysis -> if(i!=0 || selected==null || analysis.entry.pos=="сокр.") analysis
            else analysis.copy(entry=analysis.entry.copy(contextMeaning=selected,source="локальное контекстное правило")) }
    }

    override fun senses(entry: LexEntry): List<String> = entry.contextMeaning?.let { listOf(it) + entry.ru.filterNot { x -> x==it } } ?: entry.ru

    /** Choose an occurrence sense after morphology and expressions; lexical caches stay context-free. */
    private fun applySenses(tokens: MutableList<RToken>, text: String) {
        if (rules.isEmpty) return
        val words=tokens.indices.filter { tokens[it].kind==TokenKind.HAN }
        var start=0
        while(start<words.size) {
            var end=start
            while(end+1<words.size && (words[end]+1 until words[end+1]).none { j ->
                tokens[j].text.any { it=='\n' || it=='\r' } ||
                    tokens[j].kind==TokenKind.PUNCT && tokens[j].text.indices.any { k ->
                        tokens[j].text[k] in ".!?…;" && SentenceBoundary.ends(text,tokens[j].start+k) }
            }) end++
            val indices=words.subList(start,end+1)
            val candidates=HashMap<Int,List<LexAnalysis>>()
            fun analyses(k:Int)=candidates.getOrPut(k) {
                val t=tokens[indices[k]]
                if (' ' in t.pinyin && t.pinyin!=IN_PHRASE) lexical(t.pinyin) else lexical(t.text)
            }
            val sentence=ContextRules.Sentence(indices.map { EnForms.key(tokens[it].text) },
                { k -> analyses(k).flatMap { listOf(it.entry.key,it.entry.lemma) }.toSet() },
                { k -> analyses(k).firstOrNull { it.entry.key==tokens[indices[k]].pinyin }?.entry?.isVerb==true },
                { a,b -> (minOf(indices[a],indices[b])+1 until maxOf(indices[a],indices[b])).all { tokens[it].kind==TokenKind.SPACE } })
            for(k in indices.indices) {
                val t=tokens[indices[k]]
                if(t.pinyin==IN_PHRASE) continue
                val options=analyses(k)
                val keys=options.flatMap { listOf(it.entry.key,it.entry.lemma) }.toSet()
                if(!rules.covers(keys)) continue
                val selected=rules.resolve(keys,sentence,k) ?: continue
                val candidate=options.firstOrNull { it.entry.key==selected.lemma || it.entry.lemma==selected.lemma } ?: continue
                val key=if(' ' in t.pinyin && candidate.entry.lemma==t.pinyin) t.pinyin else candidate.entry.key
                tokens[indices[k]]=t.copy(pinyin=key,contextMeaning=selected.meaning)
            }
            start=end+1
        }
    }

    override fun phraseKey(tokens: List<RToken>, text: String, a: Int, b: Int): String? {
        if (a >= b || tokens[a].kind != TokenKind.HAN || tokens[b].kind != TokenKind.HAN) return null
        val selected = tokens.subList(a, b + 1)
        if (selected.any { it.kind !in setOf(TokenKind.HAN, TokenKind.SPACE) || '\n' in it.text || '\r' in it.text }) return null
        val surface = selected.filter { it.kind == TokenKind.HAN }.joinToString(" ") { it.text }
        val exact = if (dictionary.contains(surface)) lexical(surface).firstOrNull { usefulPhrase(it.entry.lemma) } else null
        fun valid(key: String): Boolean {
            val parts=EnForms.key(key).split(' ')
            if (parts.size!=2 || parts.last() !in OBJECTS) return true
            val first=selected.first().text
            return EnForms.key(first)==parts.first() && nextWord(tokens,a,-1)?.let { EnForms.key(tokens[it].text) } !in (SUBJECTS + BE_FORMS + setOf("have","has","had","would","will","could","can","should","must"))
        }
        if (exact != null && valid(exact.entry.lemma)) return exact.entry.lemma
        val words = selected.filter { it.kind == TokenKind.HAN }
        val rest = words.drop(1).joinToString(" ") { EnForms.key(it.text) }
        return choices(words.first().text).asSequence().filter { it.entry.isVerb }.map { it.entry.lemma + " " + rest }
            .firstOrNull { usefulPhrase(it) && valid(it) && dictionary.contains(it) && lexical(it).isNotEmpty() }
    }

    private fun usefulPhrase(word: String): Boolean {
        val words = EnForms.key(word).split(' ')
        if (words.size !in 2..6 || words.first() in NOUN_MARKERS || words.last() in NOUN_MARKERS) return false
        return EnForms.key(word) !in setOf("of the", "in the", "to the", "and the", "he is", "it is", "i am")
    }

    override fun phraseAt(tokens: List<RToken>, text: String, index: Int): IntRange? {
        if (tokens.getOrNull(index)?.kind != TokenKind.HAN) return null
        var best: IntRange? = null
        val left = generateSequence(index) { nextWord(tokens, it, -1) }.take(6).toList()
        for (a in left) {
            val range = phraseStarting(tokens, text, a) ?: continue
            if (index in range && (best == null || range.last - range.first > best!!.last - best!!.first)) best = range
        }
        return best
    }

    private fun phraseStarting(tokens: List<RToken>, text: String, start: Int): IntRange? {
        if (tokens.getOrNull(start)?.kind != TokenKind.HAN) return null
        var best: IntRange? = null
        for (end in generateSequence(start) { nextWord(tokens, it, 1) }.take(6).drop(1)) {
            if (phraseKey(tokens, text, start, end) != null) best = start..end
        }
        return best
    }

    private fun short(entry: LexEntry): String? = entry.ru.firstOrNull()?.let {
        it.substringBefore(';').substringBefore(',').take(48)
    } ?: entry.en.firstOrNull()?.take(48)

    private fun pick(word: String, reading: String?): LexEntry? {
        if (reading == IN_PHRASE) return null
        if (reading != null && ' ' in reading) return choices(reading).firstOrNull()?.entry
        val all = choices(word)
        return all.firstOrNull { it.entry.key == reading }?.entry ?: all.firstOrNull()?.entry
    }

    override fun glossShort(word: String, reading: String?): String? = pick(word, reading)?.let(::short)
    override fun cardTranslation(word: String, reading: String?): String? = pick(word, reading)?.let { it.ru.ifEmpty { it.en }.take(2).joinToString("; ").ifEmpty { null } }
    override fun lemma(word: String): String = choices(word).firstOrNull()?.entry?.lemma ?: EnForms.key(word)
    override fun reading(word: String): String? = choices(word).firstOrNull()?.entry?.pronunciation?.takeIf { it.isNotEmpty() }
    override fun studyable(word: String): Boolean = choices(word).firstOrNull()?.entry?.let { !it.isName && (it.ru.isNotEmpty() || it.en.isNotEmpty()) } == true
    override fun isName(word: String): Boolean {
        if (EnForms.key(word) == "i") return false
        val all = choices(word)
        return if (all.isEmpty()) word.firstOrNull()?.isUpperCase() == true else all.all { it.entry.isName }
    }
    override fun knownByParts(word: String, known: Set<String>): Boolean {
        if (lemma(word) in known || EnForms.key(word) in known) return true
        val expansions = EnForms.parse(word).expansions
        return expansions.isNotEmpty() && expansions.all { expansion -> expansion.words.all { lemma(it) in known || EnForms.key(it) in known } }
    }
    override fun searchForm(text: String): String = text.map { if (it in "’‘") '\'' else it.lowercaseChar() }.joinToString("")

    override fun analyze(bookId: Long, book: BookSource, onProgress: (Int, Int) -> Unit): BookAnalysis {
        val counts = LinkedHashMap<String, IntArray>()
        val names = HashSet<String>()
        var total = 0
        for (c in book.chapters) {
            val texts = book.blocks(c.index).mapNotNull { (it as? EpubBlock.Text)?.text }
            val prepare = chapter(texts, TextOptions())
            for (text in texts) for (token in prepare(text).tokens) {
                if (token.kind != TokenKind.HAN) continue
                total++
                if (token.pinyin == IN_PHRASE) continue
                val key = if (' ' in token.pinyin) token.pinyin else token.pinyin.substringBefore('/').ifEmpty { lemma(token.text) }
                if (isName(token.text)) names += key
                counts.getOrPut(key) { intArrayOf(0, c.index) }[0]++
            }
            onProgress(c.index + 1, book.chapters.size)
        }
        return BookAnalysis(counts.map { (word, count) -> BookWord(bookId, word, count[0], count[1], ignored = word in names) }, total)
    }

    override val levels: WordLevels = object : WordLevels {
        private val bands = listOf(500, 1500, 3000, 5000, 8000, 16000)
        private val names = listOf("A1", "A2", "B1", "B2", "C1", "C2")
        // Already read on the loader thread from a small table, never from JSON articles in Compose.
        private val ranked = dictionary.rankedWords
        private val knownByLevel = bands.map { band -> ranked.filterValues { it <= band }.keys.toSet() }
        override val name = ""
        override val scaleTitle = "уровней A1–C2 (приблизительно по частотности)"
        override val all = (1..6).toList()
        override fun title(level: Int) = names.getOrElse(level - 1) { "C2+" }
        override fun of(word: String): Int = choices(word).firstOrNull()?.entry?.rank?.takeIf { it > 0 }?.let { rank -> bands.indexOfFirst { rank <= it }.let { if (it < 0) 0 else it + 1 } } ?: 0
        override fun estimate(word: String) = of(word).takeIf { it > 0 } ?: 7
        override fun knownAt(level: Int): Set<String> = if (level <= 0) emptySet() else knownByLevel[level.coerceAtMost(6) - 1]
    }

    override fun formNote(tokens: List<RToken>, index: Int, analyses: List<LexAnalysis>): FormNote? {
        val a = analyses.firstOrNull() ?: return null
        return a.grammar.takeIf { it.isNotBlank() }?.let {
            FormNote(it, null, "Разбор словарной формы. Другие значения и варианты доступны в карточке.",
                construction = if (a.entry.pos == "сокр.") a.entry.en.joinToString(" / ") else null)
        }
    }

    override fun conjugationTable(lemma: String): Conjugation {
        val forms = dictionary.verbForms(lemma)
        fun row(title: String, match: (Set<String>) -> Boolean): String? = forms.filter { match(it.second) }.map { it.first }.distinct()
            .takeIf { it.isNotEmpty() }?.let { "$title: ${it.joinToString(" / ")}" }
        return Conjugation(listOfNotNull("Начальная форма: $lemma", row("Past Simple") { "past" in it && "participle" !in it },
            row("Past Participle") { "past" in it && "participle" in it }, row("-ing") { "present" in it && "participle" in it || "gerund" in it },
            row("3-е лицо ед. ч.") { "present" in it && "third-person" in it }), emptyList())
    }

    fun occurrences(example: String, word: String): List<IntRange> {
        val prepared = chapter(listOf(example), TextOptions())(example)
        return prepared.tokens.mapIndexedNotNull { i, token ->
            if (token.kind != TokenKind.HAN || token.pinyin.substringBefore('/') != word) null
            else if (' ' !in word) token.start until token.end
            else {
                var end = i
                while (true) {
                    val next = nextWord(prepared.tokens, end, 1) ?: break
                    if (prepared.tokens[next].pinyin != IN_PHRASE) break
                    end = next
                }
                token.start until prepared.tokens[end].end
            }
        }
    }

    companion object {
        private val LOW_PRIORITY = Regex("^(?:устар\\.|редк\\.|obsolete\\b|archaic\\b)",RegexOption.IGNORE_CASE)
        private val FINITE_AUX = setOf("am","is","are","was","were","has","had","does","did")
        private val MODALS = setOf("may","might","can","could","will","would","shall","should","must")
        private val BE_FORMS = setOf("am","is","are","was","were","be","been")
        private val OBJECTS = setOf("me","you","him","her","us","them","it")
        private val SUBJECTS = setOf("i", "you", "he", "she", "it", "we", "they", "who")
        private val UNCHANGED_PARTICIPLES = setOf("read", "cut", "put", "set", "hit", "hurt", "let", "shut", "split",
            "spread", "cost", "cast", "burst", "fit", "bet", "quit", "come", "become", "run")
        private val NOUN_MARKERS = setOf("a", "an", "the", "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those")
        private val PAST_FIRST = mapOf("went" to "go", "gone" to "go", "saw" to "see", "left" to "leave", "found" to "find",
            "was" to "be", "were" to "be", "is" to "be", "are" to "be", "am" to "be", "been" to "be", "did" to "do", "does" to "do", "has" to "have", "had" to "have")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var loading: CompletableDeferred<EnEngine>? = null
        fun release() { synchronized(this) { loading = null } }
        @OptIn(ExperimentalCoroutinesApi::class)
        fun ready(): EnEngine? = loading?.takeIf { it.isCompleted && !it.isCancelled }?.let { runCatching { it.getCompleted() }.getOrNull() }
        fun warmUp(context: Context): CompletableDeferred<EnEngine> = synchronized(this) {
            loading ?: CompletableDeferred<EnEngine>().also { deferred ->
                loading = deferred
                scope.launch {
                    runCatching { val start = System.nanoTime(); EnEngine(EnDictionary.load(context), (System.nanoTime() - start) / 1_000_000,
                        runCatching { context.assets.open("en/context.tsv").bufferedReader().useLines { ContextRules.parse(it) } }.getOrDefault(ContextRules.EMPTY)) }
                        .fold({ deferred.complete(it) }, { deferred.completeExceptionally(it) })
                }
            }
        }
    }
}
