"""Convert official htdemucs weights to TFLite via ai-edge-torch (Linux only).

Runs on ubuntu CI runner. Produces float32 and float16 tflite plus numeric
verification against the PyTorch reference using the LiteRT interpreter.
"""
import os
import sys

import numpy as np
import torch
import types

import ai_edge_torch
from demucs.pretrained import get_model


def resolve_convert():
    fn = getattr(ai_edge_torch, "convert", None)
    if fn is not None:
        return fn
    import importlib
    for modname in ("ai_edge_torch.litert", "litert_torch"):
        try:
            m = importlib.import_module(modname)
            f = getattr(m, "convert", None)
            if f is not None:
                print(f"using convert from {modname}")
                return f
        except Exception as e:
            print(f"probe {modname}: {e}")
    print("module attrs:", [a for a in dir(ai_edge_torch) if not a.startswith("_")])
    raise RuntimeError("no convert entrypoint found")

OUT_DIR = os.environ.get("CONVERT_OUT", "convert_out")
L = int(os.environ.get("CONVERT_LENGTH", "343980"))
SEED = 20260826


class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        out = self.m(x)
        if isinstance(out, (list, tuple)):
            out = out[0]
        return out


def verify(path, ref):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except Exception:
        from tensorflow.lite.python.interpreter import Interpreter  # type: ignore

    itp = Interpreter(model_path=path, num_threads=4)
    itp.allocate_tensors()
    ind = itp.get_input_details()[0]
    outd = sorted(itp.get_output_details(), key=lambda d: d["index"])
    x = np.ascontiguousarray(ref_input.astype(ind["dtype"]))
    itp.set_tensor(ind["index"], x)
    itp.invoke()
    got = itp.get_tensor(outd[0]["index"]).astype(np.float64)
    r = ref.astype(np.float64)
    ok = True
    lines = []
    n_stem = min(got.shape[-3], r.shape[-3])
    for s in range(n_stem):
        g = got[..., s, :, :].ravel()
        rr = r[..., s, :, :].ravel()
        cos = float(np.dot(g, rr) / max(np.linalg.norm(g) * np.linalg.norm(rr), 1e-12))
        mad = float(np.abs(g - rr).max())
        flag = "OK" if cos >= 0.9995 else "FAIL"
        if cos < 0.9995:
            ok = False
        lines.append(f"stem{s} cosine={cos:.6f} maxabsdiff={mad:.6f} {flag}")
    print(f"[verify {os.path.basename(path)}] shape={got.shape} ref_shape={ref.shape}")
    for ln in lines:
        print("  " + ln)
    return ok


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    torch.manual_seed(SEED)
    np.random.seed(SEED)

    model = get_model("htdemucs")
    if hasattr(model, "models"):
        model = model.models[0]
    if hasattr(model, "use_train_segment"):
        model.use_train_segment = False
    if hasattr(model, "crosstransformer") and model.crosstransformer is not None:
        ct = model.crosstransformer
        from demucs.transformer import create_sin_embedding

        def _fixed_pos(self, T, B, C, device):
            return create_sin_embedding(T, C, shift=0, device=device, max_period=self.max_period)

        ct._get_pos_embedding = types.MethodType(_fixed_pos, ct)
    model.eval().to("cpu")
    for p in model.parameters():
        p.requires_grad_(False)

    wrap = Wrap(model).eval()
    global ref_input
    ref_input = (torch.randn(1, 2, L) * 0.1)

    with torch.no_grad():
        ref = wrap(ref_input.clone()).numpy()
    print(f"reference out shape={ref.shape} rms={float(np.sqrt((ref ** 2).mean())):.6f}")

    conv = resolve_convert()
    results = {}
    for tag, qmode in (("float32", None), ("float16", "float16")):
        path = os.path.join(OUT_DIR, f"htdemucs_{tag}.tflite")
        print(f"converting {tag} ...", flush=True)
        em = conv(wrap, (ref_input,)) if qmode is None else conv(wrap, (ref_input,), quantize_mode=qmode)
        try:
            em.export(path)
        except AttributeError:
            with open(path, "wb") as f:
                f.write(bytes(em))
        size = os.path.getsize(path)
        print(f"wrote {path} bytes={size}")
        results[tag] = (path, size)

    all_ok = True
    for tag, (path, _) in results.items():
        try:
            if not verify(path, ref):
                all_ok = False
        except Exception as e:
            print(f"verify {tag} ERROR: {e}")
            all_ok = False

    print("VERIFY_RESULT:", "PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
