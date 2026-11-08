#!/usr/bin/env python3
"""docs/privacy.html from PRIVACY.md (the same text as in the app), for the GitHub Pages site.
Usage: python3 tools/build_site.py   (from the repository root)"""
import html
import re

STYLE = """
:root { --bg:#0e0e0e; --surface:#181818; --border:#2a2a2a; --text:#f2ede4; --muted:#a39d93; --accent:#e8564a; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:17px/1.6 -apple-system,"Segoe UI",Roboto,Arial,sans-serif; }
.wrap { max-width:760px; margin:0 auto; padding:24px 16px 56px; }
a { color:var(--accent); }
h1 { font-size:30px; line-height:1.2; } h2 { font-size:21px; margin-top:32px; }
li { margin:6px 0; } .back { font-size:15px; }
"""


def inline(t: str) -> str:
    t = html.escape(t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(https?://[^\s)]+)", r'<a href="\1">\1</a>', t)
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
            out.append("<li>" + inline(line[2:]))
            continue
        if line.startswith("  ") and in_list and line.strip():
            out[-1] += " " + inline(line.strip())
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
<title>Политика конфиденциальности HanReader</title><link rel="icon" href="img/favicon.png"><style>{STYLE}</style></head>
<body><div class="wrap"><p class="back"><a href="./">← HanReader</a></p>
{chr(10).join(out)}
</div></body></html>
"""
    open("docs/privacy.html", "w", encoding="utf-8").write(page)


if __name__ == "__main__":
    main()
