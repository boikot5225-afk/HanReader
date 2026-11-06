#!/usr/bin/env python3
"""Build app/src/main/assets/zh/names.tsv: proper names, so they are not taught as vocabulary.

Input: Jieba 0.42.1 dict.txt (MIT), its part-of-speech tags nr/nrt/nrfg (people), ns (places),
nt (organisations); CC-CEDICT compact map and the HSK list to leave ordinary words out.

Jieba's tags are noisy (many idioms are tagged nr), so an entry is kept only if it also looks like
a name:
  people  - 2-4 characters starting with a common surname, or made only of transliteration characters;
  places  - a place suffix (省 市 县 山 河 岛 国 …) or transliteration characters;
  orgs    - an organisation suffix (公司 大学 银行 集团 委员会 …).
Words in the HSK lists or defined by CC-CEDICT as ordinary words (lower-case reading) are skipped.

Output, sorted:  word \\t p|l|o
Usage: python3 tools/build_names.py <jieba dict.txt> <zh_dict_core.json> <hsk.tsv> <out.tsv>
"""
import json
import sys

SURNAMES = set(
    "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤常温康施文牛樊葛邢安齐易乔伍庞颜倪庄聂章鲁岳翟殷詹申欧耿关兰焦俞左柳甘祝包宁尚符舒阮柯纪梅童凌毕单季裴霍涂成苗谷盛曲翁冉骆蓝路游辛靳管柴蒙鲍华喻祁蒲房滕屈饶解牟艾尤阳时穆农司卓古吉缪简车项连芦麦褚娄窦戚岑景党宫费卜冷晏席卫米柏宗瞿桂全佟应臧闵苟邬边卞姬师和仇栾隋商刁沙荣巫寇桑郎甄丛仲虞敖巩明佘池查麻苑迟邝官封谈匡鞠惠荆乐冀郁胥南班储原栗燕楚鄢劳谌奚皮粟冼蔺楼盘满闻位厉伊仝区郜海阚花权强帅屠豆朴盖练廉禹井祖漆巴丰支卿国狄平计索宣晋相初门云容敬来扈晁芮都普阙浦戈伏鹿薄邸雍辜羊阿乌母裘亓修邰赫杭况那宿鲜印逯隆茹诸战慕危玉银亢嵇公哈湛宾戎勾茅利於呼居揭干但尉冶斯元束檀衣信展阴昝智幸奉植衡富尧闭由"
)
TRANSLIT = set(
    "阿埃艾爱安昂奥巴拜班邦保鲍贝本比彼毕庇波伯博布查达戴丹道德登迪狄蒂丁东杜敦多厄恩尔法范菲费芬丰"
    "弗福盖甘冈高戈哥格根古瓜哈海亥汉豪赫黑亨洪胡华霍基吉加嘉贾杰金卡凯坎康考柯科克肯库夸奎拉莱兰朗劳"
    "乐勒雷里利莉丽莲林琳留卢鲁路伦罗洛马玛迈麦曼梅美门蒙米敏缪莫默姆穆纳娜奈南内尼妮宁纽努诺欧帕派潘"
    "庞佩彭皮珀普奇齐恰乔琴琼丘萨塞赛桑瑟森沙莎尚舍什史士斯松苏索塔泰坦汤唐特提廷托瓦旺威韦维温文沃乌"
    "西希锡谢辛休许雅亚扬耶伊因尤约泽扎詹珍兹佐"
)
PLACE_SUFFIX = tuple("省市县区镇乡村州郡岛山峰岭河江湖海湾港洲国城府街路巷寺庙宫")
ORG_SUFFIX = ("公司", "集团", "大学", "学院", "中学", "小学", "银行", "医院", "委员会", "协会", "学会",
              "研究所", "研究院", "出版社", "报社", "电视台", "电台", "工厂", "基金会", "联合会", "组织", "政府", "法院")
TYPES = {"nr": "p", "nrt": "p", "nrfg": "p", "ns": "l", "nt": "o"}


def han(w): return all("一" <= c <= "鿿" for c in w)


def looks_like(word, kind):
    translit = all(c in TRANSLIT for c in word)
    if kind == "p":
        return (2 <= len(word) <= 4 and word[0] in SURNAMES) or (len(word) >= 2 and translit)
    if kind == "l":
        return word.endswith(PLACE_SUFFIX) or (len(word) >= 2 and translit)
    if kind == "o":
        return word.endswith(ORG_SUFFIX)
    return False


def main(jieba, cedict, hsk_path, out):
    ced = json.load(open(cedict, encoding="utf-8"))["map"]
    hsk = {l.split("\t")[0] for l in open(hsk_path, encoding="utf-8")}
    rows = {}
    for line in open(jieba, encoding="utf-8"):
        p = line.split()
        if len(p) < 3 or p[2] not in TYPES:
            continue
        w, kind = p[0], TYPES[p[2]]
        if len(w) < 2 or not han(w) or w in hsk:
            continue
        e = ced.get(w)
        if e and e[0] and not e[0][:1].isupper():
            continue  # an ordinary word in CC-CEDICT
        if kind == "o":
            # "秘密组织" is a phrase, "中国科学院" a name: the part before the suffix must not be an ordinary word.
            head = next((w[: -len(x)] for x in ORG_SUFFIX if w.endswith(x)), "")
            h = ced.get(head)
            if head and h and h[0] and not h[0][:1].isupper():
                continue
        if looks_like(w, kind):
            rows.setdefault(w, kind)
    with open(out, "w", encoding="utf-8") as f:
        for w in sorted(rows):
            f.write(f"{w}\t{rows[w]}\n")
    by = {k: sum(1 for v in rows.values() if v == k) for k in "plo"}
    print(f"{len(rows)} names {by} -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
