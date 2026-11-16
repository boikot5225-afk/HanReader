package enaudit

import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import com.hanreader.app.en.*
import com.hanreader.app.lang.*
import com.hanreader.app.zh.TokenKind

fun main(args:Array<String>) {
    Languages.current=Languages.ENGLISH
    val dir=File(args[1])
    val rules=File(dir,"context.tsv").takeIf { it.exists() }?.useLines { ContextRules.parse(it) } ?: ContextRules.EMPTY
    val dictionary=if(System.getenv("ENAUDIT_MMAP")=="1") {
        require(dir.canonicalFile==File("app/src/main/assets/en").canonicalFile) { "mmap audit uses current application assets" }
        EnDictionary.load(android.content.Context())
    } else EnDictionary.read(File(dir,"dict.tsv").inputStream(),File(dir,"forms.tsv").inputStream(),File(dir,"ranks.tsv").inputStream(),File(dir,"manual.tsv").takeIf { it.exists() }?.inputStream())
    val engine=EnEngine(dictionary,rules=rules)
    val started=System.nanoTime()
    var blocks=0L;var words=0L;var defined=0L;var phraseHeads=0L;var rulesUsed=0L;var hints=0L;var russianHints=0L
    File(args[3]).bufferedWriter().use { out -> File(args[2]).forEachLine { line ->
        val row=JSONObject(line);val text=row.getString("text")
        val p=engine.chapter(listOf(text),TextOptions())(text)
        check(p.tokens.joinToString("") { it.text } == text)
        p.tokens.forEach { check(text.substring(it.start,it.end)==it.text) }
        if(args[0]=="book") {
            blocks++
            for(t in p.tokens) if(t.kind==TokenKind.HAN) {
                words++;if(t.defined) defined++
                if(' ' in t.pinyin) phraseHeads++
                if(t.contextMeaning!=null) rulesUsed++
                val h=engine.glossShort(t).orEmpty()
                if(h.isNotEmpty()) { hints++;if(h.any { it in 'А'..'я' || it=='ё' || it=='Ё' }) russianHints++ }
            }
        } else if(args[0]=="tokens") {
            out.appendLine(JSONObject().put("id",row.getString("id")).put("tokens",JSONArray(p.tokens.filter { it.kind==TokenKind.HAN }.map { t -> JSONObject().put("word",t.text).put("start",t.start).put("end",t.end).put("reading",t.pinyin).put("defined",t.defined) })).toString())
        } else {
            val start=row.getInt("start");val end=row.getInt("end")
            val a=p.tokens.indexOfFirst { it.start==start };val b=p.tokens.indexOfLast { it.end==end }
            val result=JSONObject().put("id",row.getString("id")).put("ai",false)
            if(a<0 || b<a) result.put("error","not token aligned") else {
                val t=p.tokens[a];val word=text.substring(start,end)
                val analyses=engine.cardAnalyses(p.tokens,text,a,b);val entry=analyses.firstOrNull()?.entry
                val ru=entry?.let { engine.senses(it) }.orEmpty()
                val reading=t.pinyin.takeIf { '#' in it && (a==b || ContextRules.key(it)==entry?.key) } ?: entry?.key
                val fallback=engine.cardTranslation(word,reading).orEmpty()
                val primary=ru.firstOrNull() ?: fallback.ifEmpty { "нет в словаре" }
                val phrase=engine.phraseAt(p.tokens,text,a)
                result.put("word",word).put("lemma",entry?.lemma.orEmpty()).put("pos",entry?.pos.orEmpty()).put("key",entry?.key.orEmpty())
                    .put("found",entry!=null).put("russian",JSONArray(ru)).put("english",JSONArray(entry?.en.orEmpty()))
                    .put("primary",primary).put("saved",primary).put("hint",engine.glossShort(t).orEmpty())
                    .put("reading",t.pinyin).put("grammar",analyses.firstOrNull()?.grammar.orEmpty()).put("source",entry?.source.orEmpty())
                    .put("contextMeaning",entry?.contextMeaning ?: t.contextMeaning.orEmpty())
                    .put("phrase",phrase?.let { engine.phraseKey(p.tokens,text,it.first,it.last) }.orEmpty())
                    .put("all",JSONArray(analyses.map { x -> JSONObject().put("lemma",x.entry.lemma).put("pos",x.entry.pos).put("russian",JSONArray(engine.senses(x.entry))).put("english",JSONArray(x.entry.en)).put("grammar",x.grammar) }))
                    .put("tokens",JSONArray(p.tokens.filter { it.kind==TokenKind.HAN && it.end>start && it.start<end }.map { it.text }))
            }
            out.appendLine(result.toString())
        }
    }
    if(args[0]=="book") out.appendLine(JSONObject().put("blocks",blocks).put("word_occurrences",words).put("defined_occurrences",defined)
        .put("phrase_heads",phraseHeads).put("context_rules",rulesUsed).put("visible_hints",hints).put("hints_with_cyrillic",russianHints)
        .put("milliseconds",(System.nanoTime()-started)/1_000_000).put("note","All extracted blocks, including front matter; hint availability is not sense accuracy. Desktop JVM, not a phone.").toString())
    }
}
