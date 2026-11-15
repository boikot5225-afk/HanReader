#!/usr/bin/env python3
"""Compile the real Chinese reader, analyzer, segmenter and EPUB parser on the JVM.
Only Android hosting is stubbed; no Chinese translation/segmentation logic is replaced.
Uses the durable Kotlin toolchain prepared by tools/context_audit/setup.py.
"""
from pathlib import Path
import subprocess,os,argparse
p=argparse.ArgumentParser();p.add_argument('--label',default='current');p.add_argument('--tests',action='store_true');args=p.parse_args()
root=Path(__file__).resolve().parents[2];os.chdir(root);w=root/'.work/zh-context-audit';gen=w/'generated';gen.mkdir(parents=True,exist_ok=True)
tc=root/'.work/context-audit/toolchain';src=root/'app/src/main/java/com/hanreader/app'
# These host adapters contain no language logic. Keep all production reader types intact.
(gen/'Android.kt').write_text('''package android.content
open class Context {
 val applicationContext get()=this
 val packageName="audit"
 val filesDir=java.io.File(".work/zh-context-audit/android")
 val packageManager=android.content.pm.PackageManager()
 val assets=android.content.res.AssetManager()
 fun getSharedPreferences(name:String,mode:Int)=SharedPreferences()
 fun startActivity(intent:Intent) { error("Android UI unavailable in JVM audit") }
 companion object { const val MODE_PRIVATE=0 }
}
class SharedPreferences { fun getString(key:String,default:String?):String?=default; fun edit()=Editor() }
class Editor { fun putString(key:String,value:String)=this; fun commit()=true }
class Intent { fun addFlags(flags:Int)=this; companion object { const val FLAG_ACTIVITY_NEW_TASK=1; const val FLAG_ACTIVITY_CLEAR_TASK=2 } }
''')
(gen/'PackageManager.kt').write_text('''package android.content.pm
class PackageManager { fun getPackageInfo(name:String,flags:Int)=PackageInfo(); fun getLaunchIntentForPackage(name:String):android.content.Intent?=null }
class PackageInfo { val lastUpdateTime=0L }
''')
(gen/'Assets.kt').write_text('''package android.content.res
class AssetManager { fun open(name:String):java.io.InputStream=java.io.File("app/src/main/assets",name).inputStream() }
''')
(gen/'App.kt').write_text('''package com.hanreader.app
enum class ScriptMode { AS_BOOK, SIMPLIFIED, TRADITIONAL }
data class BookWord(val bookId:Long,val word:String,val count:Int,val firstChapter:Int,val ignored:Boolean=false)
object BuildConfig { const val LANGUAGES="es,zh" }
data class Appearance(val esVariant:com.hanreader.app.es.EsVariant=com.hanreader.app.es.EsVariant.ANY)
object AppearanceStore { val appearance=kotlinx.coroutines.flow.MutableStateFlow(Appearance()); fun update(f:(Appearance)->Appearance) { appearance.value=f(appearance.value) } }
''')
(gen/'Sentences.kt').write_text('''package com.hanreader.app.reader
object Sentences { fun ends(text:String,i:Int)=com.hanreader.app.lang.SentenceBoundary.ends(text,i) }
''')
sources=[*gen.glob('*.kt'),*(src/'zh').glob('*.kt'),*(src/'es').glob('*.kt'),*(src/'en').glob('*.kt'),*(src/'epub').glob('*.kt'),*(src/'lang').glob('*.kt'),*(src/'reader'/n for n in ['ReaderModel.kt','BookVocabulary.kt','BookSearch.kt']),root/'tools/zh_context_audit/Main.kt']
# Other lang sources currently require Android Activity/Application; include only pure contracts.
sources=[p for p in sources if p.parent!=src/'lang' or p.name in ['LanguageModule.kt','Alphabetic.kt','ContextRules.kt','SentenceBoundary.kt']]
if args.tests:
 for name in ['ZhContextRegressionTest.kt']:
  p=root/'app/src/test/java/com/hanreader/app/zh'/name
  if p.exists():sources.append(p)
cp=os.pathsep.join(str(p) for p in [tc/'jsoup.jar',tc/'json.jar',tc/'junit.jar',tc/'hamcrest.jar',tc/'kotlinc/lib/kotlinx-coroutines-core-jvm.jar',tc/'kotlinc/lib/kotlin-stdlib.jar'])
subprocess.run(['java','-cp',str(tc/'kotlinc/lib/*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(w/f'audit-{args.label}.jar'),*map(str,sources)],check=True)
(w/f'classpath-{args.label}').write_text(os.pathsep.join([str(w/f'audit-{args.label}.jar'),cp,str(tc/'kotlinc/lib/*')]))

if args.tests:
    subprocess.run(['java','-Xmx1g','-cp',(w/f'classpath-{args.label}').read_text(),'org.junit.runner.JUnitCore','com.hanreader.app.zh.ZhContextRegressionTest'],cwd=root/'app',check=True)
