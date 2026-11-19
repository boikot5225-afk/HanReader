package com.hanreader.app.reader

import com.hanreader.app.wiki.WikiArticle

data class WikiNoteTarget(val book: Long, val chapter: Int, val chapterTitle: String, val block: Int,
    val start: Int, val end: Int, val term: String, val sentence: String)

fun wikipediaNote(target: WikiNoteTarget, article: WikiArticle, definition: String): String {
    require(!article.ambiguous && article.language in com.hanreader.app.wiki.WikipediaRepository.languages)
    require(article.id > 0 && article.revision > 0 && definition.isNotBlank() && definition.length <= 12_000)
    return buildString {
        append(target.term).append("\n\n").append(definition.trim())
        append("\n\nКонтекст книги:\n").append(target.sentence)
        append("\n\nИсточник: Википедия — ").append(article.title).append("\n").append(article.revisionUrl)
        append("\nАвторы: участники Википедии; история правок: ").append(article.historyUrl)
        append("\nCC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/")
        append("\nПолучено: ").append(java.time.Instant.ofEpochMilli(article.fetchedAt).toString())
        if (definition.trim() != article.extract.trim()) append("\nОписание отредактировано читателем.")
    }
}
