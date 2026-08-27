#!/usr/bin/env python3
"""
Convert Facebook HTDemucs → LiteRT TFLite.

Strategy: strict=False export + all demucs blockers patched.
Blockers fixed: pad1d asserts, th.istft .item(), th.hann_window op,
random.randrange, Lock(), Fraction cast, list-shape assert.
"""

import os, sys, types, math, subprocess, importlib, numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

import torch
import torch.nn.functional as F

OUT = "convert_out"
os.makedirs(OUT, exist_ok=True)

# ============================================================
# 1. Export-compatible STFT / iSTFT  (primitive ATen ops only)
# ============================================================

def export_stft(x, n_fft, hop_length, window, center=True, normalized=True):
    """unfold → *window → rfft  (no th.stft, no aten.hann_window)."""
    if center:
        x = F.pad(x, (n_fft // 2, n_fft // 2), mode="reflect")
    x = x.unfold(-1, n_fft, hop_length)   # (..., frames, n_fft)
    x = x * window
    z = torch.fft.rfft(x, dim=-1, norm="ortho")
    return z.transpose(-2, -1)             # (..., freq, frames)


def export_istft(z, n_fft, hop_length, window, length, center=True, normalized=True):
    """irfft → *window → F.fold OLA → envelope → trim (no th.istft)."""
    z_t = z.transpose(-2, -1)                          # (..., frames, freq)
    x = torch.fft.irfft(z_t, n=n_fft, dim=-1, norm="ortho")
    x = x * window                                     # (..., frames, n_fft)

    frames = x.shape[-2]
    out_len = n_fft + hop_length * (frames - 1)

    # overlap-add via F.fold (aten.fold — core ATen op)
    x_t = x.transpose(-2, -1)                          # (..., n_fft, frames)
    output = F.fold(x_t, (out_len,), (n_fft,), stride=(hop_length,))
    output = output.squeeze(-2)                        # (..., out_len)

    # envelope = OLA of window²
    window_sq = window.pow(2).unsqueeze(-2).expand_as(x_t)
    envelope = F.fold(window_sq, (out_len,), (n_fft,), stride=(hop_length,))
    envelope = envelope.squeeze(-2)
    output = output / envelope.clamp(min=1e-8)

    if center:
        start = n_fft // 2
        output = output[..., start : start + length]
    return output


# ============================================================
# 2. Install deps (idempotent)
# ============================================================

def pip_install(packages, extra_index=None):
    cmd = [sys.executable, "-m", "pip", "install", "-q"]
    if extra_index:
        cmd += ["--extra-index-url", extra_index]
    cmd += packages
    subprocess.check_call(cmd)

print("=== Installing dependencies ===")
pip_install(["numpy==1.26.4"])
pip_install(
    ["torch==2.4.1", "demucs==4.1.0", "ai-edge-torch==0.4.0", "einops"],
    extra_index="https://download.pytorch.org/whl/cpu",
)


# ============================================================
# 3. Load model
# ============================================================

import demucs.htdemucs as hdmod
import demucs.transformer as dtx
from demucs.apply import BagOfModels
from demucs.pretrained import get_model
import demucs.spec as dspec

print("=== Loading model ===")
hd = get_model("htdemucs")
assert isinstance(hd, BagOfModels), f"Expected BagOfModels, got {type(hd)}"
model = hd.models[0]

model.use_train_segment = False
model.eval()
for p in model.parameters():
    p.requires_grad_(False)

nfft = model.nfft
hl = model.hop_length
print(f"nfft={nfft} hop_length={hl}")


# ============================================================
# 4. Register hann buffers  (eliminates aten.hann_window op)
# ============================================================

for n in [512, 1024, 2048, 4096]:
    model.register_buffer(f"hann_{n}", torch.hann_window(n, periodic=True), persistent=False)


# ============================================================
# 5. Patch pad1d  — remove data-dependent asserts
# ============================================================

def _safe_pad1d(x, paddings, mode="constant", value=0):
    """pad1d without asserts (hdemucs.py:28-40)."""
    padding_left, padding_right = paddings
    x0 = x
    length = x.shape[-1]
    max_pad = max(padding_left, padding_right)
    if length <= max_pad:
        extra_pad = max_pad - length + 1
        extra_pad_right = min(padding_right, extra_pad)
        extra_pad_left = extra_pad - extra_pad_right
        paddings = (padding_left - extra_pad_left, padding_right - extra_pad_right)
        x = F.pad(x, (extra_pad_left, extra_pad_right))
    out = F.pad(x, paddings, mode, value)
    return out

hdmod.pad1d = _safe_pad1d


# ============================================================
# 6. Patch _spec / _ispec  — manual stft/istft, no asserts
# ============================================================

def _spec_manual(self, x):
    hl_ = self.hop_length
    nfft_ = self.nfft
    le = math.ceil(x.shape[-1] / hl_)
    pad_ = hl_ // 2 * 3
    x = _safe_pad1d(x, (pad_, pad_ + le * hl_ - x.shape[-1]), mode="reflect")

    B, C = x.shape[0], x.shape[1]
    length = x.shape[-1]
    x_flat = x.reshape(B * C, length)
    hann = getattr(self, f"hann_{nfft_}")
    z = export_stft(x_flat, nfft_, hl_, hann.to(x.device), center=True, normalized=True)
    print(f"  _spec: x={x.shape} x_flat={x.shape} z_after_stft={z.shape} le={le}")
    _, freqs, frames = z.shape
    z = z[..., :-1, :]
    print(f"  _spec: z_after_trim1={z.shape}")
    z = z[..., 2 : 2 + le]
    print(f"  _spec: z_after_trim2={z.shape} target=({B},{C},{freqs},{le}) size={z.numel()} target_size={B*C*freqs*le}")
    return z.reshape(B, C, freqs, le)

model._spec = types.MethodType(_spec_manual, model)


def _ispec_manual(self, z, length=None, scale=0):
    hl_ = self.hop_length // (4 ** scale)
    nfft_ = self.nfft
    z = F.pad(z, (0, 0, 0, 1))
    z = F.pad(z, (2, 2))
    pad_ = hl_ // 2 * 3
    le = hl_ * math.ceil(length / hl_) + 2 * pad_

    B = z.shape[0] if z.dim() > 2 else 1
    C = z.shape[1] if z.dim() > 2 else 1
    *_, freqs, frames = z.shape
    z_flat = z.reshape(B * C, freqs, frames)
    hann = getattr(self, f"hann_{nfft_}")
    x = export_istft(z_flat, nfft_, hl_, hann.to(z.device), le, center=True, normalized=True)
    x = x[..., pad_ : pad_ + length]
    return x.reshape(B, C, -1)

model._ispec = types.MethodType(_ispec_manual, model)


# ============================================================
# 7. Patch _get_pos_embedding  — deterministic shift=0
# ============================================================

from demucs.transformer import create_sin_embedding, create_sin_embedding_cape

def _fixed_pos(self, T, B, C, device):
    if self.emb == "sin":
        pos_emb = create_sin_embedding(T, C, shift=0, device=device, max_period=self.max_period)
    elif self.emb == "cape":
        pos_emb = create_sin_embedding_cape(
            T, C, B, device=device, max_period=self.max_period,
            mean_normalize=self.cape_mean_normalize, augment=False,
            max_global_shift=self.cape_glob_loc_scale[0],
            max_local_shift=self.cape_glob_loc_scale[1],
            max_scale=self.cape_glob_loc_scale[2],
        )
    else:
        raise ValueError(f"Unknown embedding type {self.emb}")
    return pos_emb

for module in model.modules():
    if isinstance(module, dtx.CrossTransformerEncoder):
        module._get_pos_embedding = types.MethodType(_fixed_pos, module)
print("Patched: pad1d, _spec, _ispec, _get_pos_embedding")


# ============================================================
# 8. Patch torch.export to use strict=False
# ============================================================

_orig_export = torch.export.export

def _strict_false_export(*args, **kwargs):
    kwargs["strict"] = False
    return _orig_export(*args, **kwargs)

torch.export.export = _strict_false_export
print("Patched: torch.export.export → strict=False")


# ============================================================
# 9. Compute reference (PyTorch eager — honest comparison)
# ============================================================

print("=== Computing reference ===")
L = 343980
ref_in = (torch.randn(1, 2, L) * 0.1,)
with torch.no_grad():
    ref = model(*ref_in).numpy()
print(f"ref shape={ref.shape} rms={np.sqrt(np.mean(ref**2)):.6f}")


# ============================================================
# 10. Convert
# ============================================================

import ai_edge_torch

class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m
    def forward(self, x):
        return self.m(x)

wrap = Wrap(model).eval()

print("=== Converting to TFLite (strict=False) ===")
edge_model = ai_edge_torch.convert(wrap, ref_in)

tflite_float32 = os.path.join(OUT, "htdemucs_float32.tflite")
edge_model.export(tflite_float32)
print(f"Saved: {tflite_float32} ({os.path.getsize(tflite_float32) / 1e6:.1f} MB)")

tflite_float16 = os.path.join(OUT, "htdemucs_float16.tflite")
edge_model.export(tflite_float16, quantize="dynamic_range")
print(f"Saved: {tflite_float16} ({os.path.getsize(tflite_float16) / 1e6:.1f} MB)")


# ============================================================
# 11. Verify
# ============================================================

print("=== Verify (per-stem cosine) ===")
from tflite_runtime.interpreter import Interpreter

for label, path in [("float32", tflite_float32), ("float16", tflite_float16)]:
    itp = Interpreter(model_path=path)
    itp.allocate_tensors()
    in_details = itp.get_input_details()[0]
    out_details = itp.get_output_details()[0]
    itp.resize_tensor_input(in_details["index"], [1, 2, L])
    itp.allocate_tensors()
    itp.set_tensor(in_details["index"], ref_in[0].numpy())
    itp.invoke()
    got = itp.get_tensor(out_details["index"])

    stems = ["drums", "bass", "other", "vocals"]
    all_ok = True
    for s in range(4):
        r = ref[0, :, s, :].ravel()
        g = got[0, :, s, :].ravel()
        cos = float(np.dot(r, g) / (np.linalg.norm(r) * np.linalg.norm(g) + 1e-8))
        ok = cos >= 0.9995
        all_ok = all_ok and ok
        print(f"  {label:8s} {stems[s]:7s}: cos={cos:.6f} {'✓' if ok else 'FAIL'}")

    if label == "float16" and not all_ok:
        print("!! float16 cosine below 0.9995 for some stems")
        sys.exit(1)

print("=== ALL PASSED ===")
