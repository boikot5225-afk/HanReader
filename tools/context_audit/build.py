#!/usr/bin/env python3
"""Compile unmodified production Spanish/EPUB sources; only Android hosting is stubbed.
Requires Kotlin compiler, jsoup and org.json jars under .work/context-audit/toolchain.
No Android SDK, Gradle, network, or book content is needed to compile.
"""
from pathlib import Path
import subprocess, os, argparse
p=argparse.ArgumentParser();p.add_argument("--tests",action="store_true");p.add_argument("--revision");p.add_argument("--label",default="current");args=p.parse_args()
root=Path(__file__).resolve().parents[2]; os.chdir(root)
w=root/'.work/context-audit'; tc=w/'toolchain'; gen=w/'generated'; gen.mkdir(parents=True,exist_ok=True)
stubs={
'Android.kt':'''package android.content
open class Context {
 val applicationContext get() = this
 val packageName = "audit"
 val filesDir = java.io.File(".work/context-audit/android")
 val packageManager = android.content.pm.PackageManager()
 val assets = android.content.res.AssetManager()
 fun getSharedPreferences(name:String, mode:Int) = SharedPreferences()
 fun startActivity(intent:Intent) { error("Android UI unavailable in JVM audit") }
 companion object { const val MODE_PRIVATE=0 }
}
class SharedPreferences { fun getString(key:String, default:String?):String? = default; fun edit()=Editor() }
class Editor { fun putString(key:String,value:String)=this; fun commit()=true }
class Intent { fun addFlags(flags:Int)=this; companion object { const val FLAG_ACTIVITY_NEW_TASK=1; const val FLAG_ACTIVITY_CLEAR_TASK=2 } }
''',
'PackageManager.kt':'''package android.content.pm
class PackageManager { fun getPackageInfo(name:String, flags:Int)=PackageInfo(); fun getLaunchIntentForPackage(name:String):android.content.Intent? = null }
class PackageInfo { val lastUpdateTime=0L }
''',
'Assets.kt':'''package android.content.res
class AssetManager { fun open(name:String):java.io.InputStream = java.io.File("app/src/main/assets",name).inputStream() }
''',
'App.kt':'''package com.hanreader.app
enum class ScriptMode { AS_BOOK }
data class BookWord(val bookId:Long,val word:String,val count:Int,val firstChapter:Int,val ignored:Boolean=false)
object BuildConfig { const val LANGUAGES="es,zh" }
data class Appearance(val esVariant:com.hanreader.app.es.EsVariant=com.hanreader.app.es.EsVariant.ANY)
object AppearanceStore { val appearance=kotlinx.coroutines.flow.MutableStateFlow(Appearance()); fun update(f:(Appearance)->Appearance) { appearance.value=f(appearance.value) } }
''',
'Zh.kt':'''package com.hanreader.app.zh
enum class TokenKind { HAN, OTHER, SPACE, PUNCT }
fun Char.isHan() = Character.UnicodeScript.of(code)==Character.UnicodeScript.HAN
''',
'Reader.kt':'''package com.hanreader.app.reader
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.Deferred
object ZhEngine {
 fun warmUp(c:android.content.Context):Deferred<LanguageModule> = error("Chinese loader is not used in Spanish JVM audit")
 fun core(c:android.content.Context):Deferred<LanguageModule> = error("Chinese loader is not used in Spanish JVM audit")
 fun release() {}
}
data class BookAnalysis(val words:List<com.hanreader.app.BookWord>, val tokens:Int)
'''
}
src=root/'app/src/main/java/com/hanreader/app'
if args.revision:
    base=w/('source-'+args.revision)
    files=subprocess.check_output(['git','ls-tree','-r','--name-only',args.revision,'app/src/main/java'],text=True).splitlines()
    for name in files:
        if name.endswith('.kt'):
            target=base/name;target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(subprocess.check_output(['git','show',args.revision+':'+name]))
    src=base/'app/src/main/java/com/hanreader/app'

r=(src/'reader/ReaderModel.kt').read_text(); stubs['Reader.kt']+='\n'+r[r.index('data class RToken('):r.index('/** A footnote mark',r.index('data class RToken('))]
for name,txt in stubs.items(): (gen/name).write_text(txt)
sources=[*(gen/name for name in stubs),* (src/'es').glob('*.kt'),*(src/'en').glob('*.kt'),src/'lang/LanguageModule.kt',src/'lang/Alphabetic.kt',src/'lang/ContextRules.kt',*(src/'epub').glob('*.kt'),root/'tools/context_audit/Main.kt']
# Include new, pure presentation/boundary helpers as they appear.
for name in ['AlphabeticCard.kt','SentenceBoundary.kt']:
 p=src/'lang'/name
 if p.exists(): sources.append(p)
if args.tests:
    loader=(root/'app/src/test/java/com/hanreader/app/es/EsEngineTest.kt').read_text()
    helper=gen/'TestEngine.kt'
    helper.write_text('package com.hanreader.app.es\nimport java.io.File\nclass EsEngineTest {\n'+loader[loader.index('    companion object {'):])
    sources.append(helper)
    for name in ['es/EsGrammarTest.kt','es/EsHyphenatorTest.kt','es/SortedTsvTest.kt','es/EsManualFixesTest.kt','lang/ContextRulesTest.kt','es/EsContextRegressionTest.kt']:
        sources.append(root/'app/src/test/java/com/hanreader/app'/name)
cp=os.pathsep.join(map(str,[tc/'jsoup.jar',tc/'json.jar',tc/'junit.jar',tc/'hamcrest.jar',tc/'kotlinc/lib/kotlinx-coroutines-core-jvm.jar']))
subprocess.run(['java','-cp',str(tc/'kotlinc/lib/*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp+os.pathsep+str(tc/'kotlinc/lib/kotlin-stdlib.jar'),'-d',str(w/f'audit-{args.label}.jar'),*map(str,sources)],check=True)
(w/f'classpath-{args.label}').write_text(os.pathsep.join([str(w/f'audit-{args.label}.jar'),cp,str(tc/'kotlinc/lib/*')]))

if args.tests:
    subprocess.run(['java','-cp',(w/f'classpath-{args.label}').read_text(),'org.junit.runner.JUnitCore','com.hanreader.app.es.EsGrammarTest','com.hanreader.app.es.EsHyphenatorTest','com.hanreader.app.es.SortedTsvTest','com.hanreader.app.es.EsManualFixesTest','com.hanreader.app.lang.ContextRulesTest','com.hanreader.app.es.EsContextRegressionTest'],cwd=root/'app',check=True)
