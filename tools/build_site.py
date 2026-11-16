#!/usr/bin/env python3
"""docs/privacy.html from PRIVACY.md (the same text as in the app), for the GitHub Pages site.
Usage: python3 tools/build_site.py   (from the repository root)"""
import html
import re
from pathlib import Path

STYLE = """
:root { --bg:#0e0e0e; --surface:#181818; --border:#2a2a2a; --text:#f2ede4; --muted:#a39d93; --accent:#e8564a; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:17px/1.6 -apple-system,"Segoe UI",Roboto,Arial,sans-serif; overflow-wrap:anywhere; }
.wrap { max-width:760px; margin:0 auto; padding:24px 16px 56px; }
a { color:var(--accent); }
h1 { font-size:clamp(26px,6vw,30px); line-height:1.2; } h2 { font-size:21px; margin-top:32px; }
li { margin:6px 0; } .back { font-size:15px; }
"""


def inline(t: str) -> str:
    t = html.escape(t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    def link(match):
        raw = match.group(1)
        url = raw.rstrip(".,!?")
        return f'<a href="{url}">{url}</a>' + raw[len(url):]
    t = re.sub(r"(https?://[^\s)]+)", link, t)
    return t


def main():
    out, in_list, para = [], False, []

    def flush():
        nonlocal para
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para = []

    for raw in open("PRIVACY.md", encoding="utf-8"):
        line = raw.rstrip("\n")
        if line.startswith("- "):
            flush()
            if not in_list:
                out.append("<ul>"); in_list = True
            out.append("<li>" + inline(line[2:]) + "</li>")
            continue
        if line.startswith("  ") and in_list and line.strip():
            out[-1] = out[-1][:-5] + " " + inline(line.strip()) + "</li>"
            continue
        if in_list:
            out.append("</ul>"); in_list = False
        if line.startswith("# "):
            flush(); out.append("<h1>" + inline(line[2:]) + "</h1>")
        elif line.startswith("## "):
            flush(); out.append("<h2>" + inline(line[3:]) + "</h2>")
        elif not line.strip():
            flush()
        else:
            para.append(line.strip())
    flush()
    if in_list:
        out.append("</ul>")
    page = f"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Конфиденциальность — Amauta Reader и HanReader</title><link rel="icon" href="img/amauta-icon.png"><style>{STYLE}</style></head>
<body><div class="wrap"><p class="back"><a href="./">← Amauta Reader</a></p>
{chr(10).join(out)}
</div></body></html>
"""
    Path("docs/privacy.html").write_text(page, encoding="utf-8")
    Path("app/src/main/assets/privacy_ru.md").write_text(Path("PRIVACY.md").read_text(encoding="utf-8"), encoding="utf-8")


if __name__ == "__main__":
    main()
