#!/usr/bin/env python3
"""Build the bundled, original demo stories without network access or extra dependencies."""
import json
from html import escape
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/demo"


def build(code):
    data = json.loads((ROOT / f"data/demo_books/{code}.json").read_text())
    title = escape(data["title"])
    language = escape(data["language"])
    identifier = f"urn:amauta:demo:{code}:v1"
    chapters = data["chapters"]
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{code}-v1.epub"
    with ZipFile(path, "w") as archive:
        def put(name, content, compression=ZIP_DEFLATED):
            info = ZipInfo(name, date_time=(2000, 1, 1, 0, 0, 0))
            info.compress_type = compression
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content.encode("utf-8"))

        put("mimetype", "application/epub+zip", ZIP_STORED)
        put("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        manifest = ''.join(f'<item id="c{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters)))
        spine = ''.join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
        put("OEBPS/book.opf", f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="book-id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">{identifier}</dc:identifier><dc:title>{title}</dc:title><dc:creator>Amauta Reader</dc:creator><dc:language>{language}</dc:language><dc:rights>CC0 1.0</dc:rights></metadata>
<manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="css" href="style.css" media-type="text/css"/>{manifest}</manifest><spine toc="ncx">{spine}</spine></package>''')
        points = ''.join(f'<navPoint id="c{i}" playOrder="{i+1}"><navLabel><text>{escape(c["title"])}</text></navLabel><content src="chapter{i}.xhtml"/></navPoint>' for i, c in enumerate(chapters))
        put("OEBPS/toc.ncx", f'''<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="{identifier}"/><meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head><docTitle><text>{title}</text></docTitle><navMap>{points}</navMap></ncx>''')
        put("OEBPS/style.css", "body { margin: 0; } p { margin: 0 0 0.5em; text-indent: 1.5em; } h1 { text-align: center; margin: 1em 0; }")
        for i, chapter in enumerate(chapters):
            paragraphs = ''.join(f'<p>{escape(p)}</p>' for p in chapter["paragraphs"])
            put(f"OEBPS/chapter{i}.xhtml", f'''<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="{language}"><head><title>{escape(chapter["title"])}</title><link rel="stylesheet" type="text/css" href="style.css"/></head><body><h1>{escape(chapter["title"])}</h1>{paragraphs}</body></html>''')
    print(f"{path.relative_to(ROOT)}: {path.stat().st_size} bytes, {len(chapters)} chapters")


if __name__ == "__main__":
    for code in ("es", "en", "zh"):
        build(code)
