package audit

import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import com.hanreader.app.es.EsEngine
import com.hanreader.app.es.SortedTsv
import com.hanreader.app.epub.EpubBook
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.lang.ContextRules

fun main(args:Array<String>) {
    if (args[0] == "extract") {
        val output=File(args[2]).bufferedWriter()
        EpubBook(File(args[1])).use { book ->
            for(c in book.chapters) {
                val blocks=book.blocks(c.index)
                println("${c.index}\t${c.path}\t${c.title}\t${blocks.filterIsInstance<EpubBlock.Text>().size}")
                for((b,block) in blocks.withIndex()) if(block is EpubBlock.Text) output.appendLine(JSONObject()
                    .put("chapter",c.index).put("path",c.path).put("title",c.title).put("block",b)
                    .put("heading",block.heading).put("text",block.text).put("notes",JSONArray(block.notes.map { JSONObject().put("start",it.start).put("end",it.end) })).toString())
            }
        }
        output.close(); return
    }
    val assets=File(args[1])
    val engine=EsEngine.load(File(assets,"dict.tsv").inputStream(),File(assets,"forms.tsv").inputStream(),File(assets,"grammar.tsv").inputStream(),File(assets,"context.tsv").inputStream())
    val sourceIndex=SortedTsv.read(File(assets,"dict.tsv").inputStream())
    File(args[3]).bufferedWriter().use { out ->
        File(args[2]).forEachLine { line ->
            val row=JSONObject(line); val text=row.getString("text"); val start=row.getInt("start"); val end=row.getInt("end")
            val p=engine.chapter(listOf(text),TextOptions())(text)
            val a=p.tokens.indexOfFirst { it.start==start }; val b=p.tokens.indexOfLast { it.end==end }
            val result=JSONObject().put("id",row.getString("id")).put("ai",false)
            if(a<0 || b<a) result.put("error","selection not token-aligned") else {
                val t=p.tokens[a]; val analyses=engine.cardAnalyses(p.tokens,text,a,b)
                val e=analyses.firstOrNull()?.entry
                val reading=if(a==b && '#' in t.pinyin) t.pinyin else e?.key
                val senses=e?.let { engine.senses(it) }.orEmpty()
                val r=engine.phraseAt(p.tokens,text,a)
                result.put("word",text.substring(start,end)).put("reading",t.pinyin).put("lemma",e?.lemma.orEmpty()).put("pos",e?.pos.orEmpty()).put("cardLemma",engine.cardLemma(analyses).orEmpty())
                    .put("grammar",analyses.firstOrNull()?.grammar.orEmpty()).put("senses",JSONArray(senses)).put("english",JSONArray(e?.en.orEmpty()))
                    .put("hint",engine.glossShort(t.text,t.pinyin).orEmpty()).put("translation",engine.cardTranslation(text.substring(start,end),reading).orEmpty())
                    .put("phrase",r?.let { engine.phraseKey(p.tokens,text,it.first,it.last) }.orEmpty())
                    .put("phraseTranslation",r?.let { engine.phraseKey(p.tokens,text,it.first,it.last) }?.let { engine.cardTranslation(it) }.orEmpty())
                    .put("dictionarySourceCode",e?.let { sourceIndex.get(it.lemma)?.getOrNull(3) }.orEmpty())
                    .put("allAnalyses",JSONArray(analyses.map { an -> JSONObject().put("lemma",an.entry.lemma).put("key",an.entry.key).put("pos",an.entry.pos).put("grammar",an.grammar).put("russian",JSONArray(engine.senses(an.entry))).put("english",JSONArray(an.entry.en)) }))
                    .put("fallback",e!=null && senses.isEmpty() && e.en.isNotEmpty())
                    .put("source",if(e==null) "none" else if(e.machine) "machine-wiktionary" else "wiktionary-or-manual")
            }
            out.appendLine(result.toString())
        }
    }
}
