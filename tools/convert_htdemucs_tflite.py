"""
Convert htdemucs_fp16weights.onnx -> htdemucs_fp16.tflite (float16) + numerical verification.

Pipeline:
  A. onnx2tf converts the EXACT shipped ONNX into a SavedModel (perfect provenance).
  B. tf.lite.TFLiteConverter turns the SavedModel into a float16-weight TFLite flatbuffer.
  C. Verification gate: same chunks through (ORT x64 on the original ONNX) and
     (TFLite CPU interpreter) must agree per-stem with cosine >= COSINE_GATE.

Usage:
  tools\\tflite_env\\Scripts\\python.exe tools\\convert_htdemucs_tflite.py

Outputs:
  tools/htdemucs_fp16.tflite      (goes to app/src/main/assets/models/ when PASS)
  tools/verify_report.txt         (numbers behind the verdict)

Exit code 0 only if the cosine gate passes.
"""

import os
import sys
import shutil
import subprocess
import math

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ONNX_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "models", "htdemucs_fp16weights.onnx")
WORK_DIR = os.path.join(ROOT, "tools", "_onnx2tf_out")
TFLITE_OUT = os.path.join(ROOT, "tools", "htdemufs_fp16.tflite")  # fixed below if typo-safe
TFLITE_OUT = os.path.join(ROOT, "tools", "htdemucs_fp16.tflite")
REPORT_PATH = os.path.join(ROOT, "tools", "verify_report.txt")

CHUNK = 343980
NUM_STEMS = 4
NUM_CHANNELS = 2
COSINE_GATE = 0.9995


def log(msg: str) -> None:
    print(msg, flush=True)


def step_a_onnx_to_savedmodel() -> str:
    if os.path.isdir(WORK_DIR):
        shutil.rmtree(WORK_DIR)
    os.makedirs(WORK_DIR, exist_ok=True)
    exe = os.path.join(sys.prefix, "Scripts", "onnx2tf.exe")
    cmd = ([exe] if os.path.isfile(exe) else [sys.executable, "-m", "onnx2tf"]) + [
        "-i", ONNX_PATH,
        "-o", "sm",
        "-fdosm",
    ]
    if os.environ.get("GPU_OPT", "0") == "1":
        cmd += ["--optimization_for_gpu_delegate"]
    log("[A] onnx2tf: " + " ".join(cmd))
    with open(os.path.join(ROOT, "tools", "_onnx2tf.log"), "w", encoding="utf-8", errors="replace") as lf:
        proc = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)
        lf.write(proc.stdout or "")
        lf.write("\n---STDERR---\n")
        lf.write(proc.stderr or "")
    txt = (proc.stdout or "") + "\n" + (proc.stderr or "")
    log(f"[A] onnx2tf rc={proc.returncode}, full log: tools/_onnx2tf.log")
    # locate saved_model dir emitted by -osm (under <cwd>/sm)
    out_dir = os.path.join(ROOT, "sm")
    sm_dir = None
    if os.path.isdir(out_dir):
        for base, dirs, files in os.walk(out_dir):
            if "saved_model.pb" in files:
                sm_dir = base
                break
            for d in dirs:
                cand = os.path.join(base, d)
                if os.path.isfile(os.path.join(cand, "saved_model.pb")):
                    sm_dir = cand
                    break
        if not sm_dir:
            # onnx2tf may nest: sm/<model>/saved_model or similar
            listing = []
            for base, dirs, files in os.walk(out_dir):
                rel = os.path.relpath(base, ROOT)
                listing.append(rel + " :: " + ",".join(files[:6]))
            log("[A] no saved_model.pb; tree:\n" + "\n".join(listing[:40]))
    if not sm_dir:
        raise RuntimeError("saved_model.pb not found after onnx2tf (-osm)")
    log(f"[A] SavedModel at {sm_dir}")
    return sm_dir
    # locate saved_model dir
    sm_dir = None
    for base, dirs, files in os.walk(WORK_DIR):
        if "saved_model.pb" in files:
            sm_dir = base
            break
    if not sm_dir:
        raise RuntimeError("saved_model.pb not found after onnx2tf")
    log(f"[A] SavedModel at {sm_dir}")
    return sm_dir


def step_b_savedmodel_to_tflite(sm_dir: str) -> str:
    import tensorflow as tf
    converter = tf.lite.TFLiteConverter.from_saved_model(sm_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    log("[B] SavedModel -> tflite float16 weights ...")
    model_bytes = converter.convert()
    with open(TFLITE_OUT, "wb") as f:
        f.write(model_bytes)
    log(f"[B] wrote {TFLITE_OUT} ({len(model_bytes)/(1024*1024):.1f} MB)")
    return TFLITE_OUT


def make_chunks():
    import numpy as np
    rng = np.random.default_rng(20260826)
    chunks = []
    for i in range(3):
        chunks.append((f"random_{i}", rng.uniform(-1.0, 1.0, size=(1, NUM_CHANNELS, CHUNK)).astype(np.float32)))
    t = np.arange(CHUNK, dtype=np.float32) / 44100.0
    synth = (
        0.5 * np.sin(2 * math.pi * 220.0 * t)[None, None, :]
        + 0.25 * np.sin(2 * math.pi * 587.33 * t)[None, None, :]
        + 0.15 * rng.uniform(-1, 1, size=(1, 1, CHUNK))
    )
    synth = np.repeat(synth, NUM_CHANNELS, axis=1).astype(np.float32)
    chunks.append(("synthetic_music", synth))
    return chunks


def step_c_verify(tflite_path: str) -> bool:
    import numpy as np
    import onnxruntime as ort
    import tensorflow as tf

    log("[C] verifying ...")
    sess_opt = ort.SessionOptions()
    sess_opt.intra_op_num_threads = max(4, os.cpu_count() or 4)
    ort_sess = ort.InferenceSession(ONNX_PATH, sess_opt, providers=["CPUExecutionProvider"])
    ort_in_name = ort_sess.get_inputs()[0].name
    ort_out_name = ort_sess.get_outputs()[0].name

    interp = tf.lite.Interpreter(model_path=tflite_path, num_threads=max(4, os.cpu_count() or 4))
    interp.allocate_tensors()
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    log(f"[C] tflite input {in_det['shape']} dtype={in_det['dtype'].__name__}, "
        f"output {out_det['shape']} dtype={out_det['dtype'].__name__}")
    assert list(in_det["shape"]) == [1, NUM_CHANNELS, CHUNK], f"unexpected input shape {in_det['shape']}"
    assert list(out_det["shape"]) == [1, NUM_STEMS, NUM_CHANNELS, CHUNK], f"unexpected output shape {out_det['shape']}"

    stem_names = ["drums", "bass", "other", "vocals"]
    rows = []
    worst = 1.0
    for name, x in make_chunks():
        ort_out = ort_sess.run([ort_out_name], {ort_in_name: x})[0]  # [1,4,2,S]
        inp_idx = in_det["index"]
        interp.set_tensor(inp_idx, x)
        interp.invoke()
        tl_out = interp.get_tensor(out_det["index"])  # [1,4,2,S]

        line = [name]
        for s in range(NUM_STEMS):
            a = tl_out[0, s].reshape(-1).astype(np.float64)
            b = ort_out[0, s].reshape(-1).astype(np.float64)
            denom = (np.linalg.norm(a) * np.linalg.norm(b))
            cos = float(np.dot(a, b) / denom) if denom > 0 else 0.0
            mad = float(np.max(np.abs(a - b))) if a.size else 0.0
            worst = min(worst, cos)
            line.append(f"{stem_names[s]} cos={cos:.6f} maxdiff={mad:.5f}")
        rows.append(line)
        log("    " + " | ".join(line))

    passed = worst >= COSINE_GATE
    report = [
        "htdemucs tflite conversion verification",
        f"gate: min per-stem cosine >= {COSINE_GATE}",
        f"worst cosine: {worst:.6f}",
        "",
    ]
    for line in rows:
        report.append(" | ".join(line))
    report.append("")
    report.append("VERDICT: " + ("PASS" if passed else "FAIL"))
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(report) + "\n")
    log(f"[C] worst cosine {worst:.6f} -> {'PASS' if passed else 'FAIL'} (report: {REPORT_PATH})")
    return passed


def main() -> int:
    if not os.path.isfile(ONNX_PATH):
        log(f"missing ONNX: {ONNX_PATH}")
        return 2
    try:
        if os.environ.get("VERIFY_ONLY", "0") == "1" and os.path.isfile(TFLITE_OUT):
            log("[A] VERIFY_ONLY: reusing existing " + TFLITE_OUT)
            tflite_path = TFLITE_OUT
        else:
            sm_dir = step_a_onnx_to_savedmodel()
            tflite_path = step_b_savedmodel_to_tflite(sm_dir)
            if os.environ.get("KEEP_OUT", "0") != "1":
                for cleanup in (os.path.join(ROOT, "sm"), WORK_DIR):
                    if os.path.isdir(cleanup):
                        shutil.rmtree(cleanup, ignore_errors=True)
        ok = step_c_verify(tflite_path)
    finally:
        pass
    if ok:
        log("ALL GOOD. Copy tools/htdemucs_fp16.tflite over app assets next.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
