package com.hanreader.app.ai

import com.hanreader.app.reader.ChapterContent
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.reader.RToken
import com.hanreader.app.zh.Palladius
import com.hanreader.app.zh.isHan
import java.text.Normalizer

internal object AiPinyin {
    private val syllable = Regex("[a-zA-ZüÜāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜĀÁǍÀĒÉĚÈĪÍǏÌŌÓǑÒŪÚǓÙǕǗǙǛ]{1,7}")
    private val tones = setOf('\u0304', '\u0301', '\u030c', '\u0300')

    fun parse(text: String, original: String): List<String>? {
        if (text.length > 100 || original.isEmpty() || !original.all { it.isHan() }) return null
        val parts = Normalizer.normalize(text.trim(), Normalizer.Form.NFC).split(Regex(" +"))
        return parts.takeIf { it.size == original.length && it.all { part ->
            syllable.matches(part) && Palladius.syllable(part) != null &&
                Normalizer.normalize(part, Normalizer.Form.NFD).count { mark -> mark in tones } <= 1
        } }
    }

    /** [token] with the reading of [row] when the row covers it; a construction gives each word its syllables. */
    fun apply(token: RToken, row: AiCheck?): RToken {
        if (row?.state != "accepted" || row.start > token.start || row.end < token.end || !token.text.all { it.isHan() }) return token
        val all = row.pinyin?.let { parse(it, row.original) } ?: return token
        val from = token.start - row.start
        if (row.original.length != row.end - row.start || from + token.text.length > all.size) return token
        val parts = all.subList(from, from + token.text.length)
        if (parts.joinToString("") == token.pinyin) return token
        return token.copy(pinyin = parts.joinToString(""), syllables = parts, glossReading = token.glossReading ?: token.pinyin)
    }

    fun chapter(chapter: ChapterContent, rows: List<AiCheck>, sourceBlock: (Int) -> Int = { it }): ChapterContent {
        val corrected = rows.filter { it.state == "accepted" && it.pinyin != null }.groupBy { it.block }
        if (corrected.isEmpty()) return chapter
        return chapter.copy(blocks = chapter.blocks.mapIndexed { index, block ->
            if (block !is ReaderBlock.Text) return@mapIndexed block
            val here = corrected[sourceBlock(index)] ?: return@mapIndexed block
            block.copy(tokens = block.tokens.map { token -> apply(token, here.firstOrNull { it.start <= token.start && it.end >= token.end }) })
        })
    }
}
