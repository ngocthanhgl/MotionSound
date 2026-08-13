#!/usr/bin/env python3
"""Parse MotionSound sensor logs (motionsound_debug.log) into per-ride analysis.

Usage:
    python tools/parse_sdlog.py <logfile> [--csv outdir]
    python tools/parse_sdlog.py --selftest

Parses SD_SESSION, SD_RAW (10 Hz), SD_GPS fix, SD_LAYER (1 Hz), SD_MIX,
SD_FWD events, SD_GESTURE. Prints per-ride summary; --csv writes raw rows.
Pure stdlib.
"""

import argparse
import csv
import math
import re
import sys
from collections import defaultdict

LINE_RE = re.compile(r"^(\d{2}:\d{2}:\d{2}\.\d{3}) \[(.*?)\] (\w)/([^:]+): (.*)$")
DEG = 180.0 / math.pi
G = 9.81
BRAKE_G = 0.4  # longSigned threshold in G (matching SensorDriveMapper)
CORNER_T = 0.4  # cornerTotal threshold

STATE_NAMES = {0: "IDLE", 1: "ACCELERATING", 2: "DECELERATING", 3: "CORNERING", 4: "CRUISING"}


def norm_deg(d):
    """Map azimuth difference to [-90, 90) (unsigned axis comparison)."""
    d = (d + 90.0) % 180.0 - 90.0
    return d


def unwrap_deg(d):
    """Map to [-180, 180) (signed heading difference)."""
    d = (d + 180.0) % 360.0 - 180.0
    return d


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def parse_kv(msg):
    out = {}
    for tok in msg.split():
        if "=" not in tok:
            continue
        k, _, v = tok.partition("=")
        parts = v.split(",")
        try:
            out[k] = [float(p) for p in parts] if len(parts) > 1 else float(parts[0])
        except ValueError:
            out[k] = v
    return out


def parse_log(path):
    sessions = []
    cur = None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE_RE.match(line.rstrip("\n"))
            if not m:
                continue
            _, _, level, tag, msg = m.groups()
            if tag == "SD_SESSION":
                if "SESSION_START" in msg:
                    cur = {"start": None, "end": None, "raw": [], "gps": [], "layer": [],
                           "fwd": [], "gestures": [], "mix": []}
                    sessions.append(cur)
                elif "SESSION_END" in msg and cur is not None:
                    cur["end"] = True
                    cur = None
                continue
            if cur is None:
                cur = {"start": None, "end": None, "raw": [], "gps": [], "layer": [],
                       "fwd": [], "gestures": [], "mix": []}
                sessions.append(cur)
            if tag == "SD_RAW":
                kv = parse_kv(msg)
                if "t" in kv and "h" in kv:
                    cur["raw"].append(kv)
            elif tag == "SD_GPS" and msg.startswith("speed="):
                cur["gps"].append(parse_kv(msg))
            elif tag == "SD_LAYER":
                cur["layer"].append(parse_kv(msg))
            elif tag == "SD_MIX":
                cur["mix"].append(parse_kv(msg))
            elif tag == "SD_FWD":
                cur["fwd"].append(msg)
            elif tag == "SD_GESTURE":
                cur["gestures"].append(msg)
    return sessions


def linear_drift(ts, vals):
    """deg/min linear drift of vals over ts (seconds)."""
    n = len(vals)
    if n < 10:
        return 0.0
    mx = sum(ts) / n
    my = sum(vals) / n
    num = sum((ts[i] - mx) * (vals[i] - my) for i in range(n))
    den = sum((ts[i] - mx) ** 2 for i in range(n))
    if den == 0:
        return 0.0
    return num / den * 60.0


def analyze_session(s, idx):
    raw = s["raw"]
    gps = s["gps"]
    layer = s["layer"]
    if not raw:
        return None
    t0 = raw[0]["t"]
    t1 = raw[-1]["t"]
    dur = (t1 - t0) / 1000.0
    ts = [(r["t"] - t0) / 1000.0 for r in raw]

    # speed / gps
    speeds = [r["gps"][0] * 3.6 for r in raw if r["gps"][0] > 0]
    max_speed = max(speeds) if speeds else 0.0
    dist = 0.0
    drops = 0
    prev = None
    for g in gps:
        if "pos" in g:
            p = g["pos"]
            if prev:
                gap = g["gap"] if "gap" in g else 0.0
                if gap > 8.0:
                    drops += 1
                if gap < 30.0:
                    dist += haversine_m(prev[0], prev[1], p[0], p[1])
            prev = p

    # forward drift vs quat heading
    diffs = [norm_deg(r["h"] - r["fh"]) for r in raw if r.get("rot") == 1.0]
    fwd_drift = linear_drift(ts, diffs) if diffs else 0.0
    fwd_mean = sum(abs(d) for d in diffs) / len(diffs) if diffs else 0.0
    fwd_max = max(abs(d) for d in diffs) if diffs else 0.0

    # yawInt vs quat heading change (gyro integration quality)
    h0 = None
    yres = []
    for r in raw:
        if r.get("rot") != 1.0:
            continue
        if h0 is None:
            h0 = r["h"]
            y0 = r["yawInt"]
        else:
            yres.append(abs(unwrap_deg((r["h"] - h0) - (r["yawInt"] - y0) * DEG)))
    yaw_rms = math.sqrt(sum(y * y for y in yres) / len(yres)) if yres else 0.0

    # brake events (raw long < -0.4G)
    brake_events = []
    in_ev = None
    for r in raw:
        on = r["long"] < -BRAKE_G * G
        if on and in_ev is None:
            in_ev = [r["t"], r["t"], -r["long"]]
        elif on and in_ev is not None:
            in_ev[1] = r["t"]
            in_ev[2] = max(in_ev[2], -r["long"])
        elif not on and in_ev is not None:
            brake_events.append(in_ev)
            in_ev = None
    if in_ev:
        brake_events.append(in_ev)

    # brake events from SD_LAYER (brake intensity)
    layer_brakes = []
    in_ev = None
    for l in layer:
        on = l["brake"] > 0.05
        if on and in_ev is None:
            in_ev = [l["t"], l["t"], l["brake"]]
        elif on and in_ev is not None:
            in_ev[1] = l["t"]
            in_ev[2] = max(in_ev[2], l["brake"])
        elif not on and in_ev is not None:
            layer_brakes.append(in_ev)
            in_ev = None

    # corner events from SD_LAYER
    corner_events = []
    in_ev = None
    for i, l in enumerate(layer):
        on = l["corner"] > CORNER_T
        if on and in_ev is None:
            in_ev = {"s": l["t"], "e": l["t"], "corner": l["corner"], "yaw": abs(l["yaw"])}
        elif on and in_ev is not None:
            in_ev["e"] = l["t"]
            in_ev["corner"] = max(in_ev["corner"], l["corner"])
            in_ev["yaw"] = max(in_ev["yaw"], abs(l["yaw"]))
        elif not on and in_ev is not None:
            corner_events.append(in_ev)
            in_ev = None
    # peak latG per corner from raw rows inside window
    for ce in corner_events:
        latg = [r["latG"] for r in raw if ce["s"] <= r["t"] <= ce["e"]]
        ce["latG"] = max(latg) if latg else 0.0

    # PCA / calibration / escape state stats
    pca_ratios = [r["pca"][2] for r in raw if r["pca"][2] > 0]
    locked_frac = sum(1 for r in raw if r["pca"][0] == 1.0) / len(raw)
    corner_frac = sum(1 for r in raw if r["cal"][0] == 1.0) / len(raw)
    calib_max = max(r["cal"][2] for r in raw)
    esc_armed = sum(1 for r in raw if r["esc"][1] == 1.0)
    reverse_max = max(r["esc"][2] for r in raw)

    fwd_events = [f for f in s["fwd"]]
    flips = [f for f in fwd_events if "flipped" in f]
    locks = [f for f in fwd_events if "axis locked" in f]
    reanchors = [f for f in fwd_events if "bearing offset locked" in f]
    biases = [f for f in fwd_events if "bias=" in f and "gpsBearing" not in f]

    out = [
        f"--- Ride {idx}: {dur/60:.1f} min ({dur:.0f}s), {len(raw)} raw, {len(gps)} gps, {len(layer)} layer ---",
        f"  speed max={max_speed:.0f} km/h   GPS distance={dist/1000:.2f} km   dropout(>8s)={drops}",
        f"  forward axis: locked {locked_frac*100:.0f}%   |h-fh| mean={fwd_mean:.1f} deg max={fwd_max:.1f} deg   drift={fwd_drift:+.2f} deg/min",
        f"  gyro integration: yawInt vs quat-heading RMS error={yaw_rms:.1f} deg   PCA ratio mean={sum(pca_ratios)/len(pca_ratios):.1f}" if pca_ratios else "  no PCA ratio data",
        f"  calib: cornerActive {corner_frac*100:.0f}%   calibTime max={calib_max:.0f}s   recalPending=max={max(r['cal'][3] for r in raw):.0f}",
        f"  escape: armed samples={esc_armed}   reverseRun max={reverse_max:.1f}s",
        f"  events: PCA locks={len(locks)}  flips={len(flips)}  bearing re-anchors={len(reanchors)}  bias updates={len(biases)}",
        f"  gestures: {', '.join(s['gestures']) if s['gestures'] else 'none'}",
    ]
    if brake_events:
        out.append(f"  BRAKE events (raw long < -{BRAKE_G}G): {len(brake_events)}")
        for b in brake_events[:10]:
            out.append(f"    t={(b[0]-t0)/1000.0:6.1f}s  dur={(b[1]-b[0])/1000.0:4.1f}s  peak={b[2]/G:.2f}G")
    if layer_brakes:
        out.append(f"  BRAKE events (layer brake>0.05): {len(layer_brakes)}")
    if corner_events:
        out.append(f"  CORNER events (corner>{CORNER_T}): {len(corner_events)}")
        for c in corner_events[:10]:
            out.append(f"    t={(c['s']-t0)/1000.0:6.1f}s  dur={(c['e']-c['s'])/1000.0:4.1f}s  corner={c['corner']:.2f}  yaw={c['yaw']:.2f}  latG={c['latG']:.2f}")
    return out, {"raw": raw, "t0": t0}


def write_csv(session, outdir):
    raw = session["raw"]
    if not raw:
        return
    path = f"{outdir}/sd_raw.csv"
    fields = ["t", "a0", "a1", "a2", "g0", "g1", "g2", "rp0", "rp1", "h", "fwd0", "fwd1", "fh",
              "rot", "yaw", "yawInt", "long", "lat", "latG", "pcaLocked", "pcaSamples", "pcaRatio",
              "calCorner", "calVotes", "calTime", "calPending", "escSign", "escArmed", "escReverse",
              "gpsSpeed", "gpsBearing", "gpsAcc", "pred", "state", "press", "hill"]
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(fields)
        for r in raw:
            w.writerow([
                r["t"], r["a"][0], r["a"][1], r["a"][2], r["g"][0], r["g"][1], r["g"][2],
                r["rp"][0], r["rp"][1], r["h"], r["fwd"][0], r["fwd"][1], r["fh"], r["rot"],
                r["yaw"], r["yawInt"], r["long"], r["lat"], r["latG"],
                r["pca"][0], r["pca"][1], r["pca"][2],
                r["cal"][0], r["cal"][1], r["cal"][2], r["cal"][3],
                r["esc"][0], r["esc"][1], r["esc"][2],
                r["gps"][0], r["gps"][1], r["gps"][2], r["pred"],
                r["state"], r["press"], r["hill"],
            ])
    print(f"  wrote {path} ({len(raw)} rows)")


def selftest():
    """Generate a synthetic log and run the full pipeline."""
    import csv
    import os
    import tempfile

    lines = []
    t = 1750000000000
    epoch_ms = lambda: t
    h0, fh0 = 30.0, 30.0
    heading, fh, yaw, yawint = h0, fh0, 0.0, 0.0
    lon0, lat0 = 105.8, 21.02
    lat, lon = lat0, lon0
    speed = 0.0
    lines.append(f"12:00:00.000 [main] I/SD_SESSION: [SESSION_START] epoch={t}")

    def rawline(**kw):
        nonlocal t, yawint
        t += 100
        h = kw.get("h", heading)
        f = kw.get("fh", fh)
        a = kw.get("a", [0.1, 0.0, 9.8])
        g = kw.get("g", [0.0, 0.0, yaw])
        long_ = kw.get("long", 0.0)
        latg = kw.get("latg", 0.0)
        corner = kw.get("corner", 0)
        pca = kw.get("pca", [1, 300, 6.2])
        cal = kw.get("cal", [corner, 0, 0.0, 0])
        esc = kw.get("esc", [1, 0, 0.0])
        lines.append(
            f"12:00:00.100 [Sensor] I/SD_RAW: t={t} a={a[0]:.2f},{a[1]:.2f},{a[2]:.2f} "
            f"g={g[0]:.3f},{g[1]:.3f},{g[2]:.3f} rp={kw.get('roll', 0.0):.2f},{kw.get('pitch', 0.0):.2f} "
            f"h={h:.2f} fwd={fh*0.0174533:.4f},{fh*0.0174513:.4f} fh={fh:.2f} rot=1 yaw={yaw:.3f} "
            f"yawInt={yawint:.3f} long={long_:.3f} lat={kw.get('lat', 0.0):.3f} latG={latg:.3f} "
            f"pca={pca[0]},{pca[1]},{pca[2]} cal={cal[0]},{cal[1]},{cal[2]:.2f},{cal[3]} "
            f"esc={esc[0]},{esc[1]},{esc[2]:.2f} gps={speed:.2f},{kw.get('brg', 45.0):.1f},{kw.get('acc', 8.0):.1f} "
            f"pred={corner} state={kw.get('state', 4)} press={kw.get('press', 1013.0):.1f} hill={kw.get('hill', 0.0):.3f}"
        )

    # 10s straight launch
    for _ in range(100):
        speed = min(speed + 0.3, 11.0)
        rawline(long=0.5, a=[0.5, 0.0, 9.8])
    # 3s brake
    for _ in range(30):
        speed = max(speed - 1.0, 3.0)
        rawline(long=-5.0, a=[-5.0, 0.0, 9.8], esc=[-1, 1, 0.5])
    # 8s gentle 40-deg corner
    for i in range(80):
        heading += 0.5
        fh += 0.45
        yaw = 0.5
        yawint += 0.5
        speed = 10.0
        rawline(h=heading, fh=fh, yaw=yaw, lat=1.5, latg=0.15, corner=0.5, cal=[1, 0, 2.0, 0], g=[0.0, 0.0, 0.5])
    # 20s straight after corner
    for _ in range(200):
        speed = 11.0
        rawline(long=0.1, latg=0.0)
    lines.append(f"12:03:00.000 [main] I/SD_SESSION: [SESSION_END] epoch={t}")
    lines.append(f"12:03:00.001 [main] I/SD_FWD: axis locked (PCA) fwd=(0.5,0.8,0.0) ratio=6.4")
    lines.append(f"12:03:00.002 [main] I/SD_GESTURE: BRAKE_HIT")
    for g in range(40):
        lines.append(f"12:03:00.000 [main] I/SD_GPS: speed={speed:.2f} acc=0.1 accuracy=8.0 gap=1.0 bearing=45.0 pos={lat0 + g*0.0002},{lon0 + g*0.0002}")

    tmpdir = tempfile.mkdtemp()
    logp = os.path.join(tmpdir, "test.log")
    with open(logp, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"SELFTEST: parsed {len(lines)} synthetic lines")
    main([logp, "--csv", tmpdir])
    print("SELFTEST: OK")
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description="MotionSound sensor log analyzer")
    ap.add_argument("logfile", nargs="?")
    ap.add_argument("--csv", metavar="DIR", help="write sd_raw.csv per ride")
    args = ap.parse_args(argv)

    if not args.logfile:
        ap.error("logfile required (or --selftest)")
    sessions = parse_log(args.logfile)
    print(f"Log: {args.logfile}  rides={len(sessions)}")
    any_out = False
    for i, s in enumerate(sessions):
        res = analyze_session(s, i)
        if res is None:
            print(f"--- Ride {i}: no SD_RAW rows ---")
            continue
        any_out = True
        for line in res[0]:
            print(line)
        if args.csv:
            write_csv(res[1], args.csv)
    if not any_out:
        print("No SD_RAW data found. Is this a MotionSound debug log?")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    sys.exit(main(sys.argv[1:]))
