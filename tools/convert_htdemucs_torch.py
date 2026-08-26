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
from demucs import transformer as dtx


def _flatten_attention_layers(model):
    """Replace _sa_block/_ff_block/_ca_block method calls with inlined ops
    so that torch.export(strict=True) can trace them."""

    def flat_self_fwd(self, src, src_mask=None, src_key_padding_mask=None):
        device = src.device
        x = src
        T = x.shape[0]
        if self.sparse and not self.auto_sparsity:
            assert src_mask is None
            src_mask = self.src_mask
            if src_mask.shape[-1] != T:
                src_mask = dtx.get_mask(T, T, self.mask_type,
                                        self.sparse_attn_window,
                                        self.global_window,
                                        self.mask_random_seed,
                                        self.sparsity, device)
                object.__setattr__(self, "src_mask", src_mask)
        if self.norm_first:
            h = self.norm1(x)
            sa = self.self_attn(h, h, h,
                                attn_mask=src_mask,
                                key_padding_mask=src_key_padding_mask,
                                need_weights=False)[0]
            x = x + self.gamma_1(self.dropout1(sa))
            f = self.linear2(self.dropout(self.activation(self.linear1(self.norm2(x)))))
            x = x + self.gamma_2(self.dropout2(f))
            if self.norm_out:
                x = self.norm_out(x)
        else:
            sa = self.self_attn(x, x, x, attn_mask=src_mask,
                                key_padding_mask=src_key_padding_mask,
                                need_weights=False)[0]
            x = self.norm1(x + self.gamma_1(self.dropout1(sa)))
            f = self.linear2(self.dropout(self.activation(self.linear1(x))))
            x = self.norm2(x + self.gamma_2(self.dropout2(f)))
        return x

    def flat_cross_fwd(self, q, k, mask=None):
        device = q.device
        T = q.shape[0]
        if self.sparse and not self.auto_sparsity:
            assert mask is None
            mask = self.mask
            S = k.shape[0]
            if mask.shape[-1] != S or mask.shape[-2] != T:
                mask = dtx.get_mask(S, T, self.mask_type,
                                    self.sparse_attn_window,
                                    self.global_window,
                                    self.mask_random_seed,
                                    self.sparsity, device)
                object.__setattr__(self, "mask", mask)
        if self.norm_first:
            ca = self.cross_attn(self.norm1(q), self.norm2(k), self.norm2(k),
                                 attn_mask=mask, need_weights=False)[0]
            x = q + self.gamma_1(self.dropout1(ca))
            f = self.linear2(self.dropout(self.activation(self.linear1(self.norm3(x)))))
            x = x + self.gamma_2(self.dropout2(f))
            if self.norm_out:
                x = self.norm_out(x)
        else:
            ca = self.cross_attn(q, k, k, attn_mask=mask, need_weights=False)[0]
            x = self.norm1(q + self.gamma_1(self.dropout1(ca)))
            f = self.linear2(self.dropout(self.activation(self.linear1(x))))
            x = self.norm2(x + self.gamma_2(self.dropout2(f)))
        return x

    dtx.MyTransformerEncoderLayer.forward = flat_self_fwd
    dtx.CrossTransformerEncoderLayer.forward = flat_cross_fwd


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
        if x.shape[0] != 1 or x.shape[1] != 2 or x.shape[-1] != L:
            raise ValueError(f"model is exported for fixed input (1,2,{L})")
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
    _flatten_attention_layers(model)

    import inspect
    import textwrap
    import demucs.htdemucs as hdmod

    _src = inspect.getsource(hdmod.HTDemucs.forward)
    if "length = mix.shape[-1]" not in _src:
        raise RuntimeError("HTDemucs.forward source drifted; patch anchor missing")
    _src = _src.replace("length = mix.shape[-1]", "length = 343980")
    _ns = dict(hdmod.__dict__)
    exec(textwrap.dedent(_src), _ns)
    hdmod.HTDemucs.forward = _ns["forward"]

    model.eval().to("cpu")
    for p in model.parameters():
        p.requires_grad_(False)

    wrap = Wrap(model).eval()
    global ref_input
    ref_input = (torch.randn(1, 2, L) * 0.1)

    with torch.no_grad():
        ref = wrap(ref_input.clone()).numpy()
    print(f"reference out shape={ref.shape} rms={float(np.sqrt((ref ** 2).mean())):.6f}")

    for _n0 in (4096, 2048, 1024, 512):
        bname = f"hann_{_n0}"
        if not hasattr(model, bname):
            model.register_buffer(bname, torch.hann_window(_n0), persistent=False)

    def _spec_buf(self, x):
        hl = self.hop_length
        nfft = self.n_fft
        w = getattr(self, "hann_%d" % nfft)
        le = int(math.ceil(x.shape[-1] / hl))
        pd = hl // 2 * 3
        x = torch.nn.functional.pad(x, [pd, pd + le * hl - x.shape[-1]], mode="reflect")
        xc = torch.nn.functional.pad(x, [nfft // 2, nfft // 2], mode="reflect")
        frames = xc.unfold(dimension=-1, size=nfft, step=hl)
        z = torch.fft.rfft(frames * w, dim=-1, norm="ortho").transpose(-2, -1)
        return z[..., :-1, :][..., 2: 2 + le]

    def _ispec_buf(self, z, length=None, scale=0):
        hl = self.hop_length // (4 ** scale)
        z = torch.nn.functional.pad(z, [0, 0, 0, 1])
        z = torch.nn.functional.pad(z, [2, 2])
        freqs = z.size(-2)
        frames = z.size(-1)
        n_fft = 2 * freqs - 2
        w = getattr(self, "hann_%d" % n_fft)
        pd = hl // 2 * 3
        le = hl * int(math.ceil(length / hl)) + 2 * pd
        other = list(z.shape[:-2])
        zin = z.reshape(-1, freqs, frames).transpose(-2, -1)
        wl = w.numel()
        if wl < n_fft:
            lft = (n_fft - wl) // 2
            w = torch.nn.functional.pad(w, [lft, n_fft - wl - lft])
        ir = torch.fft.irfft(zin, dim=-1, norm="ortho")
        expected = n_fft + hl * (frames - 1)
        y_tmp = ir * w
        sizes = [ir.size(0), expected]
        y = torch.ops.aten.unfold_backward(y_tmp, input_sizes=sizes, dim=1, size=n_fft, step=hl)
        env = torch.ops.aten.unfold_backward(
            w.pow(2).expand(ir.size(0), frames, n_fft),
            input_sizes=sizes, dim=1, size=n_fft, step=hl)
        start = n_fft // 2
        end = start + le
        y = y.narrow(1, start, end - start) / env.narrow(1, start, end - start)
        ln = y.size(1)
        return y.view(*other, ln)[..., pd: pd + length]

    hdmod.HTDemucs._spec = _spec_buf
    hdmod.HTDemucs._ispec = _ispec_buf

    def _strip_assert_lowering():
        import inspect as _insp
        import ai_edge_torch.odml_torch.export as _oe

        for name in dir(_oe):
            obj = getattr(_oe, name)
            if not isinstance(obj, type):
                continue
            cf = getattr(obj, "call_function", None)
            if cf is None:
                continue
            try:
                src = _insp.getsource(cf)
            except Exception:
                continue
            if "Lowering not found" not in src:
                continue

            def patched(self, target, args, kwargs, _orig=cf):
                if "assert" in str(target).lower():
                    return None
                return _orig(self, target, args, kwargs)

            obj.call_function = patched
            print(f"patched assert-skip lowering on {name}")

    _strip_assert_lowering()

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
