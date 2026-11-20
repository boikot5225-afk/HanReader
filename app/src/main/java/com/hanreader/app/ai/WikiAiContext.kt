package com.hanreader.app.ai

import com.hanreader.app.Appearance
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.reader.*

/** Source paragraph addresses are independent of pictures, pages and font metrics. */
internal object WikiAiContext {
    fun build(reading: WikiReading, block: Int, engine: LanguageModule, meanings: Map<String, String>, appearance: Appearance,
        glosses: Map<String, String>? = null, range: IntRange? = null, includeMissing: Boolean = false): AiFragment? {
        if (engine.info.tag.substringBefore('-') != reading.document.article.language) return null
        val key = reading.document.key
        return try {
            AiContext.build(0, reading.textChapter, block, "wikipedia:$key:${appearance.script.name}", engine,
                meanings.filterKeys { !ContextMeanings.isScoped(it) }, appearance.esVariant.name, glosses, range, includeMissing)
                ?.copy(articleKey = key)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null } // Unsupported source transformations must not crash ordinary reading.
    }
    fun automatic(reading: WikiReading, block: Int, engine: LanguageModule, meanings: Map<String, String>, appearance: Appearance,
        glosses: Map<String, String>, visible: IntRange? = null): AiFragment? {
        val fragment = build(reading, block, engine, meanings, appearance, glosses) ?: return null
        // One bounded packet per visible paragraph, with expressions ahead of ordinary words.
        val candidates = fragment.terms.filter { visible == null || it.start <= visible.last && it.end > visible.first }
        if (candidates.isEmpty()) return null
        return fragment.copy(terms = candidates.sortedByDescending { if (' ' in it.lemma) 1 else 0 }.take(AiContext.BATCH))
    }
    fun applicable(reading: WikiReading, engine: LanguageModule, meanings: Map<String, String>, appearance: Appearance,
        rows: List<AiCheck>, model: String): List<AiCheck> = rows.groupBy { it.block }.flatMap { (block, checks) ->
        val fragment = build(reading, block, engine, meanings, appearance, includeMissing = true)
        if (fragment == null) emptyList() else AiContext.applicable(fragment, checks, model).filter { row ->
            fragment.protected.none { it.first < row.end && it.last >= row.start }
        }
    }
}
