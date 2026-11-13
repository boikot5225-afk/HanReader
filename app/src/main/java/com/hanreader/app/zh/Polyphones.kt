package com.hanreader.app.zh

/**
 * Readings of polyphones that stand alone as a token, chosen from their neighbours. The
 * dictionary gives one reading per character; in running text the particles 地 and 得 alone
 * occur thousands of times per novel and read "de", not dì / dé.
 *
 * Each rule looks only at the neighbouring tokens; when they do not decide, the dictionary's
 * reading stays (null). Only standalone single-character tokens are decided here: inside words the dictionary's
 * reading is right (地方 dìfang, 得到 dédào, 长大 zhǎngdà).
 */
object Polyphones {
    private val LOCAL_NOUNS = setOf("政府", "官员", "干部", "法院", "法规", "经济", "财政", "部队", "保护", "主义", "戏", "志", "性", "政权", "当局", "领导", "选举", "特色", "口音", "风味", "小吃")
    /** The reading of token [i] if its context decides it; null keeps the dictionary's. */
    fun reading(tokens: List<ZhToken>, i: Int): String? {
        val t = tokens[i]
        if (t.kind != TokenKind.HAN) return null
        val prev = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val next = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        // 重重地坐下 zhòngzhòng «тяжело»; elsewhere chóngchóng «слой за слоем» (困难重重).
        if (t.text == "重重") return if (next == "地") "zhòng zhòng" else null
        // 地方 dìfang «место»; dìfāng «местный» before an institution (地方政府, 地方官员).
        if (t.text == "地方") return if (next != null && next in LOCAL_NOUNS) "dì fāng" else null
        if (t.text.length != 1) return null
        // 把他当朋友, 拿书当枕头: «принимать за», dàng — decided by a 把 / 拿 earlier in the clause.
        if (t.text == "当" && earlierInClause(tokens, i, setOf("把", "拿", "将"))) return "dàng"
        return rule(t.text[0], prev, next)
    }

    /** One of [words] is among the few tokens before [i] in the same clause. */
    private fun earlierInClause(tokens: List<ZhToken>, i: Int, words: Set<String>): Boolean {
        var k = i - 1
        while (k >= 0 && k >= i - 6) {
            val t = tokens[k]
            if (t.kind == TokenKind.PUNCT) return false
            if (t.text in words) return true
            k--
        }
        return false
    }

    /**
     * Readings of the characters of token [i], a combination the dictionary gives no reading
     * (两行, 数一数, 重写): each character decided by the rest of the word and the neighbours, null
     * where nothing decides. Null for single characters and dictionary words.
     */
    fun readingsInside(tokens: List<ZhToken>, i: Int): List<String?>? {
        val t = tokens[i]
        if (t.kind != TokenKind.HAN || t.text.length < 2 || t.entry?.pinyin?.isNotEmpty() == true) return null
        val before = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val after = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val w = t.text
        val out = w.indices.map { k ->
            when {
                w[k].isSurrogate() -> null
                // Inside a combination 得 is mostly dé (易得, 所得者) and 地 the earth (青草地), except
                // after a doubled description: 默默地, 悄悄地.
                w[k] == '得' -> null
                w[k] == '地' -> if (k == w.lastIndex && k >= 2 && w[k - 1] == w[k - 2]) "de" else null
                else -> rule(w[k], if (k == 0) before else w.substring(0, k), if (k == w.lastIndex) after else w.substring(k + 1))
            }
        }
        return out.takeIf { r -> r.any { it != null } }
    }

    private fun rule(ch: Char, prev: String?, next: String?): String? {
        return when (ch) {
            '地' -> di(prev, next)
            '得' -> de(prev, next)
            '长' -> chang(prev, next)
            '只' -> zhi(prev, next)
            '还' -> if (next != null && next[0] in "给钱债清" ) "huán" else null
            '行' -> if (prev != null && next != null && prev.last() in COUNTERS && next[0] !in "了吗吧啊呢不") "háng" else null
            '重' -> chong(prev, next)
            '种' -> zhong(prev, next)
            '数' -> if (prev?.endsWith("一") == true || next != null && next[0] in "一了着过钱星" && (prev == null || prev.last() !in COUNTERS)) "shǔ" else null
            '教' -> jiao(prev, next)
            '觉' -> if (prev != null && (prev.contains('睡') || prev.last() in "个一午好大懒")) "jiào" else null
            '空' -> if ((prev != null && prev.last() in "有没抽得") || next == "儿") "kòng" else null
            '干' -> if (prev != null && prev.last() in "很太晒烤吹擦喝流哭榨洗烘变挺风晾抹舔蒸烧") "gān" else null
            '处' -> chu(prev, next)
            '倒' -> if (next != null && next[0] in "在地下") "dǎo" else null
            '背' -> if (next != null && next[0] in "起包走回") "bēi" else null
            '量' -> if (prev?.endsWith("一") == true || next != null && (next[0] in "一了体血身尺" || next == "量")) "liáng" else null
            '假' -> if (prev != null && (prev.last() in "放请休度暑寒病婚产" || prev == "天" || prev.length >= 2 && prev.last() == '天' && prev[0] in COUNTERS)) "jià" else null
            '着' -> if (prev != null && next != null && prev.last() in "猜点烧摸够睡找" && next[0] == '了') "zháo" else null
            '了' -> if (prev == "不") "liǎo" else null
            '削' -> "xiāo"
            '似' -> if (next != null && next[0] == '的') null else "sì"
            '弹' -> if (next != null && next[0] in "琴钢吉奏起了着过一") "tán" else null
            '系' -> if (next != null && next[0] in "上好着紧鞋领带住") "jì" else null
            '盛' -> if (next != null && next[0] in "饭汤水满粥菜了") "chéng" else null
            '扇' -> if (next != null && next[0] in "了着风他她我你" && (prev == null || prev.last() !in COUNTERS)) "shān" else null
            '担' -> if (prev != null && prev.last() in COUNTERS) "dàn" else null
            // 为 wéi after «выбрать, назвать, стать» (选为, 称为); 中 zhòng after «попасть» (打中); 冲着 chòng;
            // 涨红 zhàng; 卷 juàn after a number; 缝 féng «шить» / fèng «щель»; 铺 pū «стелить» / pù after
            // a number; 看孩子 kān; 转圈 zhuàn.
            '为' -> if (prev != null && prev.last() in "选称视定改变化作成分认列评封拜奉尊推任命转划译立奉誉") "wéi" else null
            // 传 zhuàn «жизнеописание» after «написать, составить» and «это, большое, внутреннее» (做传, 立本传,
            // 大传, 内传) and as the topic (传的名目, 的传的); chuán «передавать» elsewhere (人以文传, 传进).
            '传' -> if (prev != null && prev.last() in "做作立写列为本大内" || (prev == null || prev == "的") && next == "的") "zhuàn" else null
            // 处 chù «место» after a number or «этот, каждый» (几处, 一处, 各处); elsewhere the dictionary's.
            '处' -> if (prev != null && prev.last() in "几一两三四五六七八九十百千多各此某何别") "chù" else null
            // 切 qiē «резать» (切细, 切开); qiè «ни в коем случае» only before a prohibition (切勿, 切莫, 切忌).
            '切' -> if (next != null && next[0] in "勿莫忌记") "qiè" else "qiē"
            // 夫 fú, the bookish opening particle «итак, ведь», at the start of a clause (夫文童者…); else fū «муж».
            '夫' -> if (prev == null && next != null) "fú" else null
            // 待 dāi «оставаться, пробыть» before 在, 着, 了 or a stretch of time (待在家里, 待一会儿); else dài «ждать».
            '待' -> if (next != null && (next[0] in "在着了" || next.startsWith("一会") || next.startsWith("会儿"))) "dāi" else null
            '中' -> if (prev != null && prev.last() in "打射猜考选击投砸押" && (next == null || next[0] !in "间国央心学部文午")) "zhòng" else null
            // 冲 chòng «к, в сторону» before 着 or a person (冲我笑).
            '冲' -> if (next == "着" || next != null && next in setOf("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱们", "大家")) "chòng" else null
            // 涨红, 脸涨得通红: zhàng «наливаться кровью».
            '涨' -> if (next != null && next[0] in "红紫" || prev != null && prev.last() == '脸') "zhàng" else null
            '卷' -> if (prev != null && prev.last() in COUNTERS + "第") "juàn" else null
            '缝' -> if (prev != null && prev.last() in COUNTERS + "道条") "fèng" else if (next != null && next[0] in "儿隙") "fèng" else "féng"
            '铺' -> if (prev != null && prev.last() in COUNTERS) "pù" else "pū"
            '看' -> if (next != null && (next.startsWith("孩") || next == "小孩" || next == "家" || next == "守")) "kān" else null
            '转' -> if (next != null && next[0] == '圈') "zhuàn" else null
            else -> null
        }
    }

    /** 重: again (重写, 重来) or a layer after a number (两重门), else heavy (很重). */
    private fun chong(prev: String?, next: String?): String? {
        if (next != null && next[0] in "写做来读说画打选装修建返演播温提排印组") return "chóng"
        if (prev != null && next != null && prev.last() in "一两几双三四五六七八九十千万" && next[0] !in "了的") return "chóng"
        return null
    }

    /** 教: to teach (教我写字), but a teaching or religion (教育, 宗教, 天主教堂) stays jiào. */
    private fun jiao(prev: String?, next: String?): String? {
        if (next != null && next[0] in "育室授师堂会徒练导材科学派条义士主皇区案务养训规化坊习") return null
        if (prev != null && prev.last() in "宗道佛回儒督主邪国传礼家请说") return null
        return "jiāo"
    }

    /** 种: to plant (种树, 我种了) or a kind (这种, 一种). */
    private fun zhong(prev: String?, next: String?): String? {
        if (next == null) return null
        if (next[0] in "树花菜地田稻麦豆瓜草粮果庄") return "zhòng"
        if (next[0] in "了着过" && (prev == null || prev.last() !in COUNTERS && prev.last() !in "各某此该同多")) return "zhòng"
        return null
    }

    /** 处: a place (到处, 此处) or to get along / be in (处得来, 处在). */
    private fun chu(prev: String?, next: String?): String? {
        if (prev != null && prev.last() in "到各此何住去好一深远近高低某别四") return null
        if (next != null && next[0] in "得不在于理置事罚境") return "chǔ"
        return null
    }

    /** 地: the adverbial particle after a description (慢慢地说, 不耐烦地问), else the earth (在地上). */
    private fun di(prev: String?, next: String?): String {
        if (prev == null || next == null) return "dì"
        if (next[0] in "上下里面板毯底方区点图球址位带铁窖狱震洞道形步主") return "dì"
        if (prev.length >= 2) return "de"
        return if (prev[0] in ADVERB_CHARS) "de" else "dì"
    }

    /** 得: the complement particle (长得, 说得很快) or děi "must" after a subject (我得走了). */
    private fun de(prev: String?, next: String?): String {
        if (prev != null && next != null && prev in SUBJECTS && next !in DEGREE && next[0] !in "很更多太像好对大快慢真不了比") return "děi"
        if (prev == null && next != null && next !in DEGREE) return "děi"
        return "de"
    }

    /** 长: long (很长, 太长) or grow / chief (长得, 长出, 级长). */
    private fun chang(prev: String?, next: String?): String {
        if (next != null && next[0] in "得出大高满着了成") return "zhǎng"
        if (prev != null && prev.last() in "级部院校队班首家族局市省州船村团社组厂处科县乡镇会") return "zhǎng"
        return "cháng"
    }

    /** 只: the measure word after a demonstrative or number (那只猫), else "only" (这只是). */
    private fun zhi(prev: String?, next: String?): String? {
        if (prev == null || prev.last() !in "这那哪每几一两半二三四五六七八九十百") return null
        if (next == null || next in ONLY_BEFORE || next[0] in "是有能会要想好在不听见剩用") return null
        return "zhī"
    }

    /** Numbers and demonstratives before a measure word: 一行字, 这行, 两担水. */
    private const val COUNTERS = "这那哪每几一两半二三四五六七八九十百千"
    private const val ADVERB_CHARS = "狠猛轻静快慢悄默渐微深重很暗偷乖死紧怒冷急"
    private val SUBJECTS = setOf("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱们", "大家", "就", "还", "总", "也", "都", "必", "只", "可", "非", "又", "现在", "那", "这")
    private val DEGREE = setOf("很", "更", "多", "太", "像", "要命", "了", "比", "好", "对", "非常", "特别", "那么", "这么", "不", "清楚", "到", "厉害", "多么", "可怕")
    private val ONLY_BEFORE = setOf("剩下", "知道", "希望", "觉得", "不过", "因为", "是", "有", "能")
}
