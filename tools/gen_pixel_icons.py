import re
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/halfmage/pixelarticons/master/svg/{}.svg"

ICONS = [
    ("Shuffle", "shuffle-sharp"),
    ("Repeat", "repeat-sharp"),
    ("MusicNote", "music-sharp"),
    ("QueueMusic", "notes-sharp"),
    ("PlaylistAdd", "section-plus"),
    ("Add", "plus"),
    ("Check", "check"),
    ("CheckCircle", "checkbox-on"),
    ("Delete", "trash-sharp"),
    ("Clear", "close"),
    ("Search", "search"),
    ("Refresh", "reload-sharp"),
    ("Download", "download-sharp"),
    ("ArrowBack", "arrow-left"),
    ("KeyboardArrowRight", "chevron-right"),
    ("Settings", "settings-cog"),
    ("Speed", "speed-fast"),
    ("Memory", "memory-stick-sharp"),
    ("BugReport", "bug-sharp"),
    ("LocationOn", "map-pin"),
    ("BatterySaver", "battery-full-sharp"),
    ("PhoneAndroid", "smartphone-sharp"),
    ("Info", "info-box-sharp"),
    ("Code", "brackets-content-sharp"),
    ("Notifications", "bell-sharp"),
]

NUM = re.compile(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?")

def tokenize(d):
    tokens = []
    i = 0
    while i < len(d):
        c = d[i]
        if c in "MmLlHhVvZzQqCcSsTtAa":
            tokens.append(c)
            i += 1
        elif c.isspace() or c == ",":
            i += 1
        else:
            m = NUM.match(d, i)
            if not m:
                raise ValueError("bad token at %d: %r" % (i, d[i:i+10]))
            tokens.append(float(m.group()))
            i = m.end()
    return tokens

def parse_d(d):
    """Return list of ('M'|'L'|'H'|'V'|'C'|'Q', (coords...)) with absolute coords."""
    tokens = tokenize(d)
    ops = []
    i = 0
    cur = [0.0, 0.0]
    pending = None  # (cmd, arity) repeat for extra pairs
    while i < len(tokens):
        t = tokens[i]
        if isinstance(t, str):
            cmd = t
            i += 1
            if cmd in "Zz":
                ops.append(("Z", ()))
                pending = None
                continue
            if cmd in "SsTtAa":
                raise ValueError("unsupported command %r" % cmd)
        else:
            if pending is None:
                raise ValueError("number without command")
            cmd, arity = pending
        if cmd in "Mm":
            arity = 2
            if isinstance(tokens[i], str):
                raise ValueError("expected number after %s" % cmd)
            x, y = tokens[i], tokens[i+1]
            if cmd == "m":
                x += cur[0]
                y += cur[1]
            cur = [x, y]
            ops.append(("M", (x, y)))
            i += 2
            pending = ("L", 2) if cmd == "M" else ("l", 2)
        elif cmd in "Ll":
            arity = 2
            x, y = tokens[i], tokens[i+1]
            if cmd == "l":
                x += cur[0]
                y += cur[1]
            cur = [x, y]
            ops.append(("L", (x, y)))
            i += 2
            pending = (cmd, 2)
        elif cmd in "Hh":
            x = tokens[i]
            if cmd == "h":
                x += cur[0]
            cur[0] = x
            ops.append(("H", (x,)))
            i += 1
            pending = (cmd, 1)
        elif cmd in "Vv":
            y = tokens[i]
            if cmd == "v":
                y += cur[1]
            cur[1] = y
            ops.append(("V", (y,)))
            i += 1
            pending = (cmd, 1)
        elif cmd in "Qq":
            x1, y1, x2, y2 = tokens[i:i+4]
            if cmd == "q":
                x1 += cur[0]; y1 += cur[1]; x2 += cur[0]; y2 += cur[1]
            cur = [x2, y2]
            ops.append(("Q", (x1, y1, x2, y2)))
            i += 4
            pending = (cmd, 4)
        elif cmd in "Cc":
            x1, y1, x2, y2, x3, y3 = tokens[i:i+6]
            if cmd == "c":
                x1 += cur[0]; y1 += cur[1]; x2 += cur[0]; y2 += cur[1]; x3 += cur[0]; y3 += cur[1]
            cur = [x3, y3]
            ops.append(("C", (x1, y1, x2, y2, x3, y3)))
            i += 6
            pending = (cmd, 6)
        else:
            raise ValueError("unknown cmd %r" % cmd)
    return ops

def fmt(v):
    if abs(v - round(v)) < 1e-9:
        v = float(round(v))
    return "%g" % v

def ops_to_kotlin(ops, indent):
    pad = " " * indent
    lines = []
    for cmd, args in ops:
        if cmd == "M":
            lines.append(pad + "moveTo(%sf, %sf)" % (fmt(args[0]), fmt(args[1])))
        elif cmd == "L":
            lines.append(pad + "lineTo(%sf, %sf)" % (fmt(args[0]), fmt(args[1])))
        elif cmd == "H":
            lines.append(pad + "horizontalLineTo(%sf)" % fmt(args[0]))
        elif cmd == "V":
            lines.append(pad + "verticalLineTo(%sf)" % fmt(args[0]))
        elif cmd == "Q":
            lines.append(pad + "quadraticTo(%sf, %sf, %sf, %sf)" % tuple(fmt(a) for a in args))
        elif cmd == "C":
            lines.append(pad + "cubicTo(%sf, %sf, %sf, %sf, %sf, %sf)" % tuple(fmt(a) for a in args))
        elif cmd == "Z":
            lines.append(pad + "close()")
    return "\n".join(lines)

def fetch(name):
    url = BASE.format(name)
    req = urllib.request.Request(url, headers={"User-Agent": "gen_pixel_icons"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")

def extract_paths(svg):
    paths = []
    for m in re.finditer(r"<path\b([^>]*)/>", svg):
        attrs = m.group(1)
        dm = re.search(r'\bd="([^"]*)"', attrs)
        if not dm:
            continue
        fr = re.search(r'fill-rule="([^"]*)"', attrs)
        paths.append((dm.group(1), fr.group(1) if fr else "nonzero"))
    return paths

def gen():
    out = []
    out.append("package com.motionsound.ui.theme\n")
    out.append("import androidx.compose.ui.graphics.Color")
    out.append("import androidx.compose.ui.graphics.PathFillType")
    out.append("import androidx.compose.ui.graphics.SolidColor")
    out.append("import androidx.compose.ui.graphics.vector.ImageVector")
    out.append("import androidx.compose.ui.graphics.vector.path")
    out.append("import androidx.compose.ui.unit.dp\n")
    out.append("// Pixel icons from Pixelarticons (https://pixelarticons.com) by Gerrit Halfmann.")
    out.append("// MIT License - free for personal and commercial use, no attribution required.")
    out.append("// https://github.com/halfmage/pixelarticons\n")
    out.append("object ComicIcons {")
    out.append("    private val ink = SolidColor(Color.Black)\n")
    out.append("    private fun build(vararg ops: ImageVector.Builder.() -> Unit): ImageVector =")
    out.append("        ImageVector.Builder(")
    out.append("            name = \"ComicIcon\",")
    out.append("            defaultWidth = 24.dp,")
    out.append("            defaultHeight = 24.dp,")
    out.append("            viewportWidth = 24f,")
    out.append("            viewportHeight = 24f")
    out.append("        ).apply {")
    out.append("            for (op in ops) op()")
    out.append("        }.build()\n")
    for prop, fname in ICONS:
        svg = fetch(fname)
        paths = extract_paths(svg)
        if not paths:
            print("WARN: no paths in %s" % fname, file=sys.stderr)
            continue
        blocks = []
        for d, fr in paths:
            ops = parse_d(d)
            body = ops_to_kotlin(ops, 20)
            if fr == "evenodd":
                blocks.append(
                    "        {\n            path(pathFillType = PathFillType.EvenOdd, fill = ink) {\n"
                    + body + "\n            }\n        }"
                )
            else:
                blocks.append("        {\n            path(fill = ink) {\n" + body + "\n            }\n        }")
        out.append("    val %s by lazy {\n        build(\n%s\n        )\n    }\n" % (prop, ",\n".join(blocks)))
    out.append("}")
    return "\n".join(out)

if __name__ == "__main__":
    out_path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java/com/motionsound/ui/theme/ComicIcons.kt"
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(gen())
    print("generated", out_path)