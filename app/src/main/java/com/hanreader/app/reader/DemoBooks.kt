package com.hanreader.app.reader

/** Original, offline reading samples. Stable names also identify copies restored from a backup. */
data class DemoBook(val tag: String, val title: String, val russianTitle: String) {
    val fileName: String get() = "demo-${tag.substringBefore('-')}-v1.epub"
    val asset: String get() = "demo/${tag.substringBefore('-')}-v1.epub"
}

object DemoBooks {
    internal val importMutex = kotlinx.coroutines.sync.Mutex()
    val all = listOf(
        DemoBook("es-ES", "La llave del jardín", "Ключ от сада"),
        DemoBook("en-US", "A light at the station", "Огонёк на станции"),
        DemoBook("zh-CN", "雨后的信", "Письмо после дождя")
    )
    fun forLanguage(tag: String): DemoBook? = all.firstOrNull { it.tag == tag }
}
