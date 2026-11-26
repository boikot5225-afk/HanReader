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

    fun apply(token: RToken, row: AiCheck?): RToken {
        if (row?.state != "accepted" || row.start != token.start || row.end != token.end) return token
        val parts = row.pinyin?.let { parse(it, token.text) } ?: return token
        return token.copy(pinyin = parts.joinToString(""), syllables = parts, glossReading = token.glossReading ?: token.pinyin)
    }

    fun chapter(chapter: ChapterContent, rows: List<AiCheck>, sourceBlock: (Int) -> Int = { it }): ChapterContent {
        val corrected = rows.filter { it.state == "accepted" && it.pinyin != null }.groupBy { it.block }
        if (corrected.isEmpty()) return chapter
        return chapter.copy(blocks = chapter.blocks.mapIndexed { index, block ->
            if (block !is ReaderBlock.Text) return@mapIndexed block
            val byStart = corrected[sourceBlock(index)]?.associateBy { it.start } ?: return@mapIndexed block
            block.copy(tokens = block.tokens.map { apply(it, byStart[it.start]) })
        })
    }
}
