import os
import re
import sys

OUT_DEFAULT = r"app/src/main/java/com/motionsound/ui/theme/ComicIcons.kt"

PROP_MAP = {
    "play": "PlayArrow",
    "pause": "Pause",
    "skip-back": "SkipPrevious",
    "skip-forward": "SkipNext",
    "shuffle": "Shuffle",
    "repeat": "Repeat",
    "music-2": "MusicNote",
    "play-list": "QueueMusic",
    "play-list-add": "PlaylistAdd",
    "add": "Add",
    "check": "Check",
    "checkbox-circle": "CheckCircle",
    "delete-bin": "Delete",
    "close": "Clear",
    "search": "Search",
    "refresh": "Refresh",
    "download": "Download",
    "arrow-left": "ArrowBack",
    "arrow-right": "KeyboardArrowRight",
    "settings-3": "Settings",
    "dashboard-2": "Speed",
    "cpu": "Memory",
    "bug": "BugReport",
    "map-pin": "LocationOn",
    "battery-saver": "BatterySaver",
    "smartphone": "PhoneAndroid",
    "information": "Info",
    "code": "Code",
    "notification": "Notifications",
}

COMMAND_RE = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]")
NUM_RE = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def tokenize(d):
    toks = []
    for m in COMMAND_RE.finditer(d):
        cmd = m.group(0)
        body = d[m.end():]
        nm = NUM_RE.match(body)
        if nm is None:
            toks.append((cmd, []))
            continue
        nums = []
        pos = m.end()
        while True:
            while pos < len(d) and d[pos].isspace():
                pos += 1
            nm = NUM_RE.match(d, pos)
            if nm is None:
                break
            nums.append(float(nm.group(0)))
            pos = nm.end()
        toks.append((cmd, nums))
    return toks


def path_nodes(d):
    toks = tokenize(d)
    nodes = []
    cx = cy = 0.0
    sx = sy = 0.0
    i = 0
    n = len(toks)
    while i < n:
        cmd, args = toks[i]
        j = 0
        while True:
            if cmd in ("Z", "z"):
                nodes.append(("close",))
                cx, cy = sx, sy
                j = 1
            elif cmd in ("M", "m"):
                if j + 1 >= len(args):
                    break
                x = args[j] + (cx if cmd == "m" else 0)
                y = args[j + 1] + (cy if cmd == "m" else 0)
                nodes.append(("moveTo", x, y))
                cx, cy = x, y
                if i == 0 or (i > 0 and toks[i - 1][0] in ("Z", "z", "M", "m")):
                    sx, sy = x, y
                j += 2
                if cmd in ("M", "m"):
                    break
                continue
            elif cmd in ("L", "l"):
                if j + 1 >= len(args):
                    break
                x = args[j] + (cx if cmd == "l" else 0)
                y = args[j + 1] + (cy if cmd == "l" else 0)
                nodes.append(("lineTo", x, y))
                cx, cy = x, y
                j += 2
            elif cmd in ("H", "h"):
                if j >= len(args):
                    break
                x = args[j] + (cx if cmd == "h" else 0)
                nodes.append(("lineTo", x, cy))
                cx = x
                j += 1
            elif cmd in ("V", "v"):
                if j >= len(args):
                    break
                y = args[j] + (cy if cmd == "v" else 0)
                nodes.append(("lineTo", cx, y))
                cy = y
                j += 1
            elif cmd in ("C", "c"):
                if j + 5 >= len(args):
                    break
                x1 = args[j] + (cx if cmd == "c" else 0)
                y1 = args[j + 1] + (cy if cmd == "c" else 0)
                x2 = args[j + 2] + (cx if cmd == "c" else 0)
                y2 = args[j + 3] + (cy if cmd == "c" else 0)
                x3 = args[j + 4] + (cx if cmd == "c" else 0)
                y3 = args[j + 5] + (cy if cmd == "c" else 0)
                nodes.append(("curveTo", x1, y1, x2, y2, x3, y3))
                cx, cy = x3, y3
                j += 6
            elif cmd in ("S", "s"):
                if j + 3 >= len(args):
                    break
                x1 = args[j] + (cx if cmd == "s" else 0)
                y1 = args[j + 1] + (cy if cmd == "s" else 0)
                x2 = args[j + 2] + (cx if cmd == "s" else 0)
                y2 = args[j + 3] + (cy if cmd == "s" else 0)
                nodes.append(("reflectiveCurveTo", x1, y1, x2, y2))
                cx, cy = x2, y2
                j += 4
            elif cmd in ("Q", "q"):
                if j + 3 >= len(args):
                    break
                x1 = args[j] + (cx if cmd == "q" else 0)
                y1 = args[j + 1] + (cy if cmd == "q" else 0)
                x2 = args[j + 2] + (cx if cmd == "q" else 0)
                y2 = args[j + 3] + (cy if cmd == "q" else 0)
                nodes.append(("quadraticTo", x1, y1, x2, y2))
                cx, cy = x2, y2
                j += 4
            elif cmd in ("T", "t"):
                if j + 1 >= len(args):
                    break
                x = args[j] + (cx if cmd == "t" else 0)
                y = args[j + 1] + (cy if cmd == "t" else 0)
                nodes.append(("reflectiveQuadraticTo", x, y))
                cx, cy = x, y
                j += 2
            elif cmd in ("A", "a"):
                if j + 6 >= len(args):
                    break
                rx = args[j]
                ry = args[j + 1]
                theta = args[j + 2]
                large = args[j + 3] != 0
                sweep = args[j + 4] != 0
                x = args[j + 5] + (cx if cmd == "a" else 0)
                y = args[j + 6] + (cy if cmd == "a" else 0)
                nodes.append(("arcTo", rx, ry, theta, large, sweep, x, y))
                cx, cy = x, y
                j += 7
            else:
                j = len(args)
            if j >= len(args):
                break
        i += 1
    return nodes


def fmt(v):
    s = "%g" % v
    if "e" in s.lower():
        s = "%.6f" % v
    return s + "f"


def emit_path(nodes, evenodd):
    lines = []
    for nd in nodes:
        op = nd[0]
        if op == "close":
            lines.append("        close()")
        elif op == "moveTo":
            lines.append("        moveTo(%s, %s)" % (fmt(nd[1]), fmt(nd[2])))
        elif op == "lineTo":
            lines.append("        lineTo(%s, %s)" % (fmt(nd[1]), fmt(nd[2])))
        elif op == "curveTo":
            lines.append(
                "        curveTo(%s, %s, %s, %s, %s, %s)"
                % (fmt(nd[1]), fmt(nd[2]), fmt(nd[3]), fmt(nd[4]), fmt(nd[5]), fmt(nd[6]))
            )
        elif op == "reflectiveCurveTo":
            lines.append(
                "        reflectiveCurveTo(%s, %s, %s, %s)"
                % (fmt(nd[1]), fmt(nd[2]), fmt(nd[3]), fmt(nd[4]))
            )
        elif op == "quadraticTo":
            lines.append("        quadraticTo(%s, %s, %s, %s)" % (fmt(nd[1]), fmt(nd[2]), fmt(nd[3]), fmt(nd[4])))
        elif op == "reflectiveQuadraticTo":
            lines.append("        reflectiveQuadraticTo(%s, %s)" % (fmt(nd[1]), fmt(nd[2])))
        elif op == "arcTo":
            lines.append(
                "        arcTo(%s, %s, %s, %s, %s, %s, %s)"
                % (fmt(nd[1]), fmt(nd[2]), fmt(nd[3]), "true" if nd[4] else "false", "true" if nd[5] else "false", fmt(nd[6]), fmt(nd[7]))
            )
    head = "path(fill = ink) {" if not evenodd else "path(pathFillType = PathFillType.EvenOdd, fill = ink) {"
    return "            { %s\n%s\n            } }" % (head, "\n".join(lines))


def parse_svg(content):
    paths = []
    for m in re.finditer(r"<path\b[^>]*>", content):
        tag = m.group(0)
        dm = re.search(r'd="([^"]*)"', tag)
        if not dm:
            continue
        d = dm.group(1)
        evenodd = 'fill-rule="evenodd"' in tag
        paths.append((path_nodes(d), evenodd))
    return paths


def gen(icons_dir, out_path):
    props = sorted(os.listdir(icons_dir))
    blocks = []
    for fn in props:
        if not fn.endswith(".svg"):
            continue
        prop = PROP_MAP.get(os.path.splitext(fn)[0], os.path.splitext(fn)[0])
        with open(os.path.join(icons_dir, fn), encoding="utf-8") as f:
            svg = f.read()
        paths = parse_svg(svg)
        body = []
        for nodes, evenodd in paths:
            body.append(emit_path(nodes, evenodd))
        blocks.append(
            "    val %s by lazy { build(\n%s\n    ) }\n"
            % (prop, ",\n".join(body))
        )
    header = """// Remix Icon - https://remixicon.com
// MIT License - Copyright (c) 2020 Remix Design
// https://github.com/Remix-Design/RemixIcon/blob/master/License
// Generated by tools/gen_remix_icons.py - do not edit manually

package com.motionsound.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ComicIcons {
    private val ink = SolidColor(Color.Black)

    private fun build(vararg blocks: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "ComicIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            for (b in blocks) b()
        }.build()

"""
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header)
        f.write("\n".join(blocks))
    print("wrote %s (%d icons)" % (out_path, len(blocks)))


if __name__ == "__main__":
    icons_dir = sys.argv[1] if len(sys.argv) > 1 else "remix_icons"
    out_path = sys.argv[2] if len(sys.argv) > 2 else OUT_DEFAULT
    gen(icons_dir, out_path)