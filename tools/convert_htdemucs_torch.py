"""Convert HTDemucs to TFLite via ai-edge-torch.

Following official docs:
  - litert_torch.convert / ai_edge_torch.convert needs torch.export compliance
  - torch.istft has .item() calls that break export → replace with unfold_backward impl
  - Use strict=False (docs recommend it) via monkey-patch on torch.export.export
"""
import os
import sys
import math

import numpy as np
import torch


OUT_DIR = os.environ.get("CONVERT_OUT", "convert_out")
L = int(os.environ.get("CONVERT_LENGTH", "343980"))
SEED = 20260826


# ---------------------------------------------------------------------------
# Export-compatible istft (no .item() calls, pure ATen ops)
# ---------------------------------------------------------------------------
def _export_istft(input, n_fft, hop_length=None, win_length=None, window=None,
                  center=True, normalized=False, onesided=None, length=None,
                  return_complex=False):
    """Replace torch.istft for torch.export — uses unfold_backward (ATen op)."""
    if return_complex:
        raise NotImplementedError("return_complex=True not supported in export istft")
    hop = hop_length if hop_length is not None else n_fft // 4
    nf = input.size(-1)
    onesided_ = (input.size(-2) != n_fft) if onesided is None else onesided
    inp = input.transpose(1, 2)                         # (N, T, F)
    norm = "ortho" if normalized else None
    if not onesided_:
        inp = inp.narrow(-1, 0, n_fft // 2 + 1)
    inp = torch.fft.irfft(inp, dim=-1, norm=norm)      # (N, T, n_fft)
    if window is None:
        window = torch.ones(win_length or n_fft, dtype=inp.dtype, device=inp.device)
    wl = window.numel()
    if wl != n_fft:
        left = (n_fft - wl) // 2
        window = torch.nn.functional.pad(window, [left, n_fft - wl - left])
    expected = n_fft + hop * (nf - 1)
    y_tmp = inp * window.view(1, 1, -1)
    sizes = [y_tmp.size(0), expected]
    y = torch.ops.aten.unfold_backward(y_tmp, input_sizes=sizes,
                                        dim=1, size=n_fft, step=hop)
    env = torch.ops.aten.unfold_backward(
        window.pow(2).expand(y_tmp.size(0), nf, n_fft),
        input_sizes=sizes, dim=1, size=n_fft, step=hop)
    start = n_fft // 2 if center else 0
    end = (start + length) if length is not None else (
        expected - n_fft // 2 if center else expected)
    y = y.narrow(1, start, end - start)
    env = env.narrow(1, start, end - start)
    y = y / env
    if end > expected:
        y = torch.nn.functional.pad(y, [0, end - expected])
    return y


# ---------------------------------------------------------------------------
# Verify converted model against PyTorch reference
# ---------------------------------------------------------------------------
def _verify(tflite_path, ref, sample_input):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except Exception:
        from tensorflow.lite.python.interpreter import Interpreter

    itp = Interpreter(model_path=tflite_path, num_threads=4)
    itp.allocate_tensors()
    ind = itp.get_input_details()[0]
    outd = sorted(itp.get_output_details(), key=lambda d: d["index"])

    x = np.ascontiguousarray(sample_input.numpy().astype(ind["dtype"]))
    itp.set_tensor(ind["index"], x)
    itp.invoke()
    got = itp.get_tensor(outd[0]["index"]).astype(np.float64)
    r = ref.astype(np.float64)

    ok = True
    n_stem = min(got.shape[-3], r.shape[-3])
    for s in range(n_stem):
        g = got[..., s, :, :].ravel()
        rr = r[..., s, :, :].ravel()
        cos = float(np.dot(g, rr) / max(np.linalg.norm(g) * np.linalg.norm(rr), 1e-12))
        flag = "OK" if cos >= 0.9995 else "FAIL"
        if cos < 0.9995:
            ok = False
        print(f"  stem{s} cosine={cos:.6f} {flag}")
    return ok


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    torch.manual_seed(SEED)
    np.random.seed(SEED)

    # --- 1. Patch torch.istft globally (export-incompatible due to .item()) ---
    torch.istft = _export_istft

    # --- 2. Load model ---
    from demucs.pretrained import get_model
    model = get_model("htdemucs")
    if hasattr(model, "models"):
        model = model.models[0]
    if hasattr(model, "use_train_segment"):
        model.use_train_segment = False
    model.eval()
    for p in model.parameters():
        p.requires_grad_(False)

    # --- 3. Compute PyTorch reference ---
    sample_input = (torch.randn(1, 2, L) * 0.1,)
    with torch.no_grad():
        ref = model(*sample_input).numpy()
    print(f"reference shape={ref.shape} rms={float(np.sqrt((ref ** 2).mean())):.6f}")

    # --- 4. Monkey-patch torch.export.export to use strict=False ---
    # Docs: "we strongly recommend using non-strict"
    # ai_edge_torch hardcodes strict=True → override it
    _orig_export = torch.export.export

    def _strict_false_export(*args, **kwargs):
        kwargs.pop("strict", None)
        kwargs["strict"] = False
        return _orig_export(*args, **kwargs)

    torch.export.export = _strict_false_export

    # --- 5. Convert ---
    import ai_edge_torch
    print("converting with strict=False ...", flush=True)
    edge_model = ai_edge_torch.convert(model.eval(), sample_input)

    # Restore
    torch.export.export = _orig_export

    # --- 6. Export .tflite ---
    tflite_path = os.path.join(OUT_DIR, "htdemucs.tflite")
    edge_model.export(tflite_path)
    size_mb = os.path.getsize(tflite_path) / (1024 * 1024)
    print(f"exported: {tflite_path} ({size_mb:.1f} MB)")

    # --- 7. Verify ---
    print("verifying ...")
    ok = _verify(tflite_path, ref, sample_input[0])
    if ok:
        print("VERIFY PASS")
    else:
        print("VERIFY FAIL")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
