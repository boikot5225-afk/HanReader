package zhaudit
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import com.hanreader.app.epub.*
import com.hanreader.app.reader.*
import com.hanreader.app.zh.*
import com.hanreader.app.lang.*

fun load(dir:File):ZhEngine {
    ZhRules.current=ZhRules.load(File(dir,"context.tsv"))
    fun lines(name:String)=File(dir,name).readLines().asSequence()
    return ZhEngine(ZhDictionary.map(File(dir,"lexicon.bin")),0,HskLevels.parse(lines("hsk.tsv")),
        ZhEngine.parseRussian(lines("ru.tsv")),ZhEngine.parseRussian(lines("ru_ai.tsv")),
        ZhEngine.parseNames(lines("names.tsv")),File(dir,"script.tsv").inputStream().use { ZhScript.parse(it) },
        ZhEngine.parseRussianChars(lines("ru_chars.tsv")),ZhEngine.parseMeasures(lines("measure.tsv")))
}
fun main(args:Array<String>) {
    Languages.current=Languages.CHINESE
    if(args[0]=="extract") {
        EpubBook(File(args[1])).use { book -> File(args[2]).bufferedWriter().use { out ->
            for(c in book.chapters) {
                val blocks=book.blocks(c.index)
                println("${c.index}\t${c.path}\t${c.title}\t${blocks.filterIsInstance<EpubBlock.Text>().size}")
                for((b,block) in blocks.withIndex()) if(block is EpubBlock.Text) out.appendLine(JSONObject().put("chapter",c.index).put("path",c.path).put("title",c.title).put("block",b).put("heading",block.heading).put("text",block.text).toString())
            }
        } };return
    }
    val engine=load(File(args[1]))
    if(args[0]=="names") {
        EpubBook(File(args[2])).use { book ->
            val names=engine.analyze(1,book) { _,_-> }.words.filter { it.ignored }.map { it.word }.toSet()
            File(args[3]).writeText(JSONArray(names.sorted()).toString())
        };return
    }
    if(args[0]=="book") {
        val file=File(args[2]);val out=File(args[3]);val book=EpubBook(file)
        val names=engine.analyze(1,book) { _,_-> }.words.filter { it.ignored }.map { it.word }.toSet()
        File(out.path+".names.json").writeText(JSONArray(names.sorted()).toString())
        out.bufferedWriter().use { writer -> for(c in book.chapters) {
            val blocks=book.blocks(c.index);val prep=engine.chapter(blocks.filterIsInstance<EpubBlock.Text>().map { it.text },TextOptions(extra=names))
            for((b,block) in blocks.withIndex()) if(block is EpubBlock.Text) {
                val p=prep(block.text)
                writer.appendLine(JSONObject().put("chapter",c.index).put("path",c.path).put("title",c.title).put("block",b).put("text",p.text)
                    .put("tokens",JSONArray(p.tokens.filter { it.kind==TokenKind.HAN }.map { t -> JSONObject().put("word",t.text).put("start",t.start).put("end",t.end).put("pinyin",t.pinyin).put("hint",engine.glossShort(t).orEmpty()).put("ru",JSONArray(engine.russianSenses(t.text,t.pinyin,t.contextMeaning))) })).toString())
            }
            println("chapter ${c.index+1}/${book.chapters.size}")
        } };book.close();return
    }
    val namesFile=File(args[4]);val names=if(namesFile.exists()) JSONArray(namesFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } else emptySet()
    File(args[3]).bufferedWriter().use { out ->File(args[2]).forEachLine { line ->
        val row=JSONObject(line);val raw=row.getString("text");val start=row.getInt("start");val end=row.getInt("end")
        val p=engine.chapter(listOf(raw),TextOptions(extra=names))(raw)
        val selected=p.tokens.filter { it.start>=start && it.end<=end && it.kind==TokenKind.HAN }
        val exact=selected.size==1 && selected.first().start==start && selected.first().end==end
        val word=p.text.substring(start,end);var reading=if(exact) selected.first().pinyin else selected.joinToString("") { it.pinyin }
        val containing=p.tokens.firstOrNull { it.kind==TokenKind.HAN && it.start<=start && it.end>=end }
        val breakdown=word.length==1 && !exact && containing!=null
        if(!exact && containing!=null) {
            val offset=start-containing.start
            reading=containing.syllables.drop(offset).take(end-start).joinToString("")
        }
        val phoneticOnly=!exact && word.length>1 && containing!=null
        // Mirror WordSheet: a single selected token keeps its contextual reading and meaning.
        val pageReading=if(exact || word.length==1) reading else null
        val meaning=if(exact) selected.first().contextMeaning else null
        val senses=if(breakdown) engine.charGloss(word,pageReading)?.split("; ").orEmpty() else engine.russianSenses(word,pageReading,meaning)
        val markedName=word in names || engine.nameKind(word)!=null
        val name=if(senses.isEmpty() && markedName) {
            val syllables=selected.flatMap { it.syllables.ifEmpty { it.pinyin.split(' ') } }
            Palladius.name(syllables,word.firstOrNull()?.let { ChineseNames.isSurname(it) && word.length in 2..4 }==true)
        } else null
        out.appendLine(JSONObject().put("id",row.getString("id")).put("word",word).put("exact",exact).put("tokens",JSONArray(selected.map { JSONObject().put("text",it.text).put("reading",it.pinyin) }))
            .put("phoneticOnly",phoneticOnly).put("characterBreakdown",breakdown).put("reading",reading).put("russian",JSONArray(senses)).put("hint",if(exact) engine.glossShort(selected.first()).orEmpty() else "")
            .put("card",name ?: engine.cardTranslation(word,pageReading,meaning).orEmpty()).put("contextMeaning",meaning.orEmpty()).put("source",if(meaning!=null) "локальное контекстное правило" else engine.russianSource(word)?.label.orEmpty()).put("name",name.orEmpty())
            .put("english",engine.lookup(word)?.gloss.orEmpty()).put("ai",false).toString())
    } }
}
