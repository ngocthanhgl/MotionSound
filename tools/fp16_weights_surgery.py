"""
Weight-only fp16 surgery on a float32 TFLite flatbuffer.

Converts every large CONSTANT float32 tensor into fp16 storage + inserts a
builtin DEQUANTIZE operator producing an fp32 tensor that consumers read.
This replicates tf.lite.TFLiteConverter's supported_types=[float16] semantics
(fp16 weights, fp32 activations/IO) while starting from onnx2tf's float32
direct output — avoiding its fp16-everything variant whose fp16-typed
activations cannot run on CPU kernels.

Modes:
  --scan             print opcode/option census + candidate stats, no writes
  --apply            perform surgery -> tools/htdemucs_fp16.tflite

Usage:
  tools/tflite_env/Scripts/python.exe tools/fp16_weights_surgery.py --scan
"""

import argparse
import os
import struct
import sys

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

import numpy as np  # noqa: E402
import flatbuffers  # noqa: E402

try:
    from tensorflow.lite.python import schema_py_generated as tfl  # noqa: E402
except ImportError:
    import importlib.util  # noqa: E402
    _sg = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "sm", "schema_generated.py")
    if not os.path.isfile(_sg):
        raise SystemExit("schema not found: tensorflow.lite unavailable and %s missing" % _sg)
    _spec = importlib.util.spec_from_file_location("tfl_schema_gen", _sg)
    tfl = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(tfl)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "_float32_ref.tflite")
DST = os.path.join(ROOT, "tools", "htdemucs_fp16.tflite")
MIN_BYTES = 16384  # only weights >= 16KB become fp16 (skips biases/gains)


def load_model(path):
    buf = open(path, "rb").read()
    m = tfl.Model.GetRootAsModel(buf, 0)
    return buf, m


def tensor_shape(t):
    return [t.Shape(i) for i in range(t.ShapeLength())]


def census(m):
    g = m.Subgraphs(0)
    codes = []
    custom_ops = 0
    opt_types = {}
    for i in range(m.OperatorCodesLength()):
        oc = m.OperatorCodes(i)
        dep = oc.DeprecatedBuiltinCode()
        code = dep if dep not in (0, 127) else oc.BuiltinCode()
        codes.append((code, oc.CustomCode().decode() if oc.CustomCode() else None))
    for i in range(g.OperatorsLength()):
        op = g.Operators(i)
        code, cc = codes[op.OpcodeIndex()]
        if code == 0 or code == 32:  # CUSTOM legacy/new enums
            custom_ops += 1
        ot = op.BuiltinOptionsType()
        opt_types[ot] = opt_types.get(ot, 0) + 1
    usage = {}
    for i in range(g.OperatorsLength()):
        op = g.Operators(i)
        for j in range(op.InputsLength()):
            ti = op.Inputs(j)
            if ti >= 0:
                usage[ti] = usage.get(ti, 0) + 1
    cands = []
    total_weight_bytes = 0
    for i in range(g.TensorsLength()):
        t = g.Tensors(i)
        if t.Type() != 0 or t.Buffer() == 0:
            continue
        blen = m.Buffers(t.Buffer()).DataLength()
        if blen >= MIN_BYTES and usage.get(i, 0) > 0:
            cands.append(i)
            total_weight_bytes += blen
    return {
        "codes": codes,
        "custom_ops": custom_ops,
        "opt_types": opt_types,
        "candidates": cands,
        "cand_bytes": total_weight_bytes,
    }


# ---------------- option copiers ----------------

def _act(o, name):
    try:
        return getattr(o, name)()
    except Exception:
        return 0


def cp_none(b, o):
    return 0


def cp_conv2d(b, o):
    tfl.Conv2DOptions.StartConv2DOptions(b)
    tfl.Conv2DOptions.AddPadding(b, o.Padding())
    tfl.Conv2DOptions.AddStrideW(b, o.StrideW())
    tfl.Conv2DOptions.AddStrideH(b, o.StrideH())
    tfl.Conv2DOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    tfl.Conv2DOptions.AddDilationW(b, o.DilationW())
    tfl.Conv2DOptions.AddDilationH(b, o.DilationH())
    return tfl.Conv2DOptions.EndConv2DOptions(b)


def cp_depthwise(b, o):
    tfl.DepthwiseConv2DOptions.StartDepthwiseConv2DOptions(b)
    tfl.DepthwiseConv2DOptions.AddPadding(b, o.Padding())
    tfl.DepthwiseConv2DOptions.AddStrideW(b, o.StrideW())
    tfl.DepthwiseConv2DOptions.AddStrideH(b, o.StrideH())
    tfl.DepthwiseConv2DOptions.AddDepthMultiplier(b, o.DepthMultiplier())
    tfl.DepthwiseConv2DOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    tfl.DepthwiseConv2DOptions.AddDilationW(b, o.DilationW())
    tfl.DepthwiseConv2DOptions.AddDilationH(b, o.DilationH())
    return tfl.DepthwiseConv2DOptions.EndDepthwiseConv2DOptions(b)


def cp_transpose_conv(b, o):
    tfl.TransposeConvOptions.StartTransposeConvOptions(b)
    tfl.TransposeConvOptions.AddPadding(b, o.Padding())
    tfl.TransposeConvOptions.AddStrideW(b, o.StrideW())
    tfl.TransposeConvOptions.AddStrideH(b, o.StrideH())
    return tfl.TransposeConvOptions.EndTransposeConvOptions(b)


def cp_fully_connected(b, o):
    tfl.FullyConnectedOptions.StartFullyConnectedOptions(b)
    tfl.FullyConnectedOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    tfl.FullyConnectedOptions.AddWeightsFormat(b, o.WeightsFormat())
    tfl.FullyConnectedOptions.AddKeepNumDims(b, o.KeepNumDims())
    tfl.FullyConnectedOptions.AddAsymmetricQuantizeInputs(b, o.AsymmetricQuantizeInputs())
    return tfl.FullyConnectedOptions.EndFullyConnectedOptions(b)


def cp_reshape(b, o):
    n = o.NewShapeLength()
    tfl.ReshapeOptions.StartNewShapeVector(b, n)
    for j in reversed(range(n)):
        b.PrependInt64(o.NewShape(j))
    vec = b.EndVector()
    tfl.ReshapeOptions.StartReshapeOptions(b)
    tfl.ReshapeOptions.AddNewShape(b, vec)
    return tfl.ReshapeOptions.EndReshapeOptions(b)


def cp_softmax(b, o):
    tfl.SoftmaxOptions.StartSoftmaxOptions(b)
    tfl.SoftmaxOptions.AddBeta(b, o.Beta())
    return tfl.SoftmaxOptions.EndSoftmaxOptions(b)


def cp_concat(b, o):
    tfl.ConcatenationOptions.StartConcatenationOptions(b)
    tfl.ConcatenationOptions.AddAxis(b, o.Axis())
    tfl.ConcatenationOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    return tfl.ConcatenationOptions.EndConcatenationOptions(b)


def _cp_activation_only(b, o, cls):
    cls.Start(b)
    cls.AddFusedActivationFunction(b, o.FusedActivationFunction())
    return cls.End(b)


def cp_add(b, o):
    return _cp_activation_only(b, o, tfl.AddOptions)


def cp_mul(b, o):
    tfl.MulOptions.StartMulOptions(b)
    tfl.MulOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    tfl.MulOptions.AddPotScaleInt8(b, o.PotScaleInt8())
    return tfl.MulOptions.EndMulOptions(b)


def cp_sub(b, o):
    return _cp_activation_only(b, o, tfl.SubOptions)


def cp_div(b, o):
    return _cp_activation_only(b, o, tfl.DivOptions)


def cp_mean(b, o):
    tfl.MeanOptions.StartMeanOptions(b)
    tfl.MeanOptions.AddKeepDims(b, o.KeepDims())
    return tfl.MeanOptions.EndMeanOptions(b)


def cp_sum(b, o):
    tfl.SumOptions.StartSumOptions(b)
    tfl.SumOptions.AddKeepDims(b, o.KeepDims())
    return tfl.SumOptions.EndSumOptions(b)


def cp_strided_slice(b, o):
    tfl.StridedSliceOptions.StartStridedSliceOptions(b)
    tfl.StridedSliceOptions.AddBeginMask(b, o.BeginMask())
    tfl.StridedSliceOptions.AddEndMask(b, o.EndMask())
    tfl.StridedSliceOptions.AddEllipsisMask(b, o.EllipsisMask())
    tfl.StridedSliceOptions.AddNewAxisMask(b, o.NewAxisMask())
    tfl.StridedSliceOptions.AddShrinkAxisMask(b, o.ShrinkAxisMask())
    tfl.StridedSliceOptions.AddOffset(b, o.Offset())
    return tfl.StridedSliceOptions.EndStridedSliceOptions(b)


def cp_squeeze(b, o):
    n = o.SqueezeDimsLength()
    tfl.SqueezeOptions.StartSqueezeDimsVector(b, n)
    for j in reversed(range(n)):
        b.PrependInt32(o.SqueezeDims(j))
    vec = b.EndVector()
    tfl.SqueezeOptions.StartSqueezeOptions(b)
    tfl.SqueezeOptions.AddSqueezeDims(b, vec)
    return tfl.SqueezeOptions.EndSqueezeOptions(b)


def cp_leaky_relu(b, o):
    tfl.LeakyReluOptions.StartLeakyReluOptions(b)
    tfl.LeakyReluOptions.AddAlpha(b, o.Alpha())
    return tfl.LeakyReluOptions.EndLeakyReluOptions(b)


def cp_l2norm(b, o):
    tfl.L2NormOptions.StartL2NormOptions(b)
    tfl.L2NormOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    tfl.L2NormOptions.AddSymmetric(b, o.Symmetric())
    return tfl.L2NormOptions.EndL2NormOptions(b)


def cp_gather(b, o):
    tfl.GatherOptions.StartGatherOptions(b)
    tfl.GatherOptions.AddAxis(b, o.Axis())
    tfl.GatherOptions.AddBatchDims(b, o.BatchDims())
    return tfl.GatherOptions.EndGatherOptions(b)


def cp_split(b, o):
    tfl.SplitOptions.StartSplitOptions(b)
    tfl.SplitOptions.AddNumSplits(b, o.NumSplits())
    return tfl.SplitOptions.EndSplitOptions(b)


def cp_batch_matmul(b, o):
    tfl.BatchMatMulOptions.StartBatchMatMulOptions(b)
    tfl.BatchMatMulOptions.AddAdjX(b, o.AdjX())
    tfl.BatchMatMulOptions.AddAdjY(b, o.AdjY())
    tfl.BatchMatMulOptions.AddAsymmetricQuantizeInputs(b, o.AsymmetricQuantizeInputs())
    return tfl.BatchMatMulOptions.EndBatchMatMulOptions(b)


def cp_pool2d(b, o):
    tfl.Pool2DOptions.StartPool2DOptions(b)
    tfl.Pool2DOptions.AddPadding(b, o.Padding())
    tfl.Pool2DOptions.AddStrideW(b, o.StrideW())
    tfl.Pool2DOptions.AddStrideH(b, o.StrideH())
    tfl.Pool2DOptions.AddPoolWidth(b, o.PoolWidth())
    tfl.Pool2DOptions.AddPoolHeight(b, o.PoolHeight())
    tfl.Pool2DOptions.AddFusedActivationFunction(b, o.FusedActivationFunction())
    return tfl.Pool2DOptions.EndPool2DOptions(b)


OPTION_COPIERS = {}
OPT_CLASS = {}

_OPTION_SPECS = [
    ("Conv2DOptions", "AsConv2DOptions", cp_conv2d),
    ("DepthwiseConv2DOptions", "AsDepthwiseConv2DOptions", cp_depthwise),
    ("TransposeConvOptions", "AsTransposeConvOptions", cp_transpose_conv),
    ("FullyConnectedOptions", "AsFullyConnectedOptions", cp_fully_connected),
    ("ReshapeOptions", "AsReshapeOptions", cp_reshape),
    ("SoftmaxOptions", "AsSoftmaxOptions", cp_softmax),
    ("ConcatenationOptions", "AsConcatenationOptions", cp_concat),
    ("AddOptions", "AsAddOptions", cp_add),
    ("MulOptions", "AsMulOptions", cp_mul),
    ("SubOptions", "AsSubOptions", cp_sub),
    ("DivOptions", "AsDivOptions", cp_div),
    ("MeanOptions", "AsMeanOptions", cp_mean),
    ("SumOptions", "AsSumOptions", cp_sum),
    ("ReducerOptions", "AsReducerOptions", cp_mean),
    ("StridedSliceOptions", "AsStridedSliceOptions", cp_strided_slice),
    ("SqueezeOptions", "AsSqueezeOptions", cp_squeeze),
    ("LeakyReluOptions", "AsLeakyReluOptions", cp_leaky_relu),
    ("L2NormOptions", "AsL2NormOptions", cp_l2norm),
    ("GatherOptions", "AsGatherOptions", cp_gather),
    ("SplitOptions", "AsSplitOptions", cp_split),
    ("BatchMatMulOptions", "AsBatchMatMulOptions", cp_batch_matmul),
    ("Pool2DOptions", "AsPool2DOptions", cp_pool2d),
]

_NONE_OPTION_NAMES = [
    "TransposeOptions", "PadOptions", "PadV2Options", "AbsOptions",
    "LogisticOptions", "ReluOptions", "Relu6Options", "RsqrtOptions",
    "SqrtOptions", "SliceOptions", "NegOptions", "FloorDivOptions",
    "FloorModOptions", "MaximumMinimumOptions", "SelectV2Options",
    "EqualOptions", "NotEqualOptions", "GreaterOptions", "GreaterEqualOptions",
    "LessOptions", "LessEqualOptions", "ExpOptions", "RangeOptions",
]


def _register():
    for name, _acc, fn in _OPTION_SPECS:
        val = getattr(tfl.BuiltinOptions, name, None)
        cls = getattr(tfl, name, None)
        if val is not None and cls is not None:
            OPTION_COPIERS[val] = fn
            OPT_CLASS[val] = cls
    for name in _NONE_OPTION_NAMES:
        val = getattr(tfl.BuiltinOptions, name, None)
        if val is not None:
            OPTION_COPIERS[val] = cp_none


_register()


def rebuild_options(b, op):
    ot = op.BuiltinOptionsType()
    if ot == 0:
        return 0
    if ot not in OPTION_COPIERS:
        raise RuntimeError(f"unsupported BuiltinOptionsType {ot} — extend copiers")
    raw = op.BuiltinOptions()
    if raw is None:
        return 0
    cls = OPT_CLASS.get(ot)
    if cls is None:  # none-option type
        return 0
    obj = OPT_CLASS[ot]()
    obj.Init(raw.Bytes, raw.Pos)
    return OPTION_COPIERS[ot](b, obj)


def rebuild_tensor(b, t, new_type=None, new_buffer=None, suffix=None):
    shape_off = 0
    n = t.ShapeLength()
    tfl.Tensor.StartShapeVector(b, n)
    for j in reversed(range(n)):
        b.PrependInt32(t.Shape(j))
    shape_off = b.EndVector()
    name = t.Name().decode() if t.Name() is not None else ""
    if suffix:
        name = name + suffix
    name_off = b.CreateString(name)
    typ = new_type if new_type is not None else t.Type()
    buf = new_buffer if new_buffer is not None else t.Buffer()
    tfl.Tensor.StartTensor(b)
    tfl.Tensor.AddShape(b, shape_off)
    tfl.Tensor.AddType(b, typ)
    tfl.Tensor.AddBuffer(b, buf)
    tfl.Tensor.AddName(b, name_off)
    tfl.Tensor.AddIsVariable(b, bool(t.IsVariable()) if t.IsVariable() is not None else False)
    return tfl.Tensor.EndTensor(b)


def apply():
    buf, m = load_model(SRC)
    g = m.Subgraphs(0)

    info = census(m)
    if info["custom_ops"] > 0:
        print(f"ABORT: {info['custom_ops']} custom ops present")
        return 1
    cands = set(info["candidates"])
    ntensors = g.TensorsLength()
    nops = g.OperatorsLength()

    # input usage positions: first consumer position per candidate
    first_use = {}
    for p in range(nops):
        op = g.Operators(p)
        for j in range(op.InputsLength()):
            ti = op.Inputs(j)
            if ti in cands and ti not in first_use:
                first_use[ti] = p

    # storage tensor ids appended after all original tensors,
    # then alias tensors
    storage_id = {t: ntensors + k for k, t in enumerate(sorted(cands))}
    alias_id = {t: ntensors + len(cands) + k for k, t in enumerate(sorted(cands))}

    # dequant insertions: schedule at first consumer pos
    inserts_at = {}
    for tid, p in first_use.items():
        inserts_at.setdefault(p, []).append(tid)

    b = flatbuffers.Builder(256 * 1024 * 1024)

    # --- buffers ---
    orig_buf_offsets = []
    for i in range(m.BuffersLength()):
        bf = m.Buffers(i)
        dl = bf.DataLength()
        data_off = 0
        if dl > 0:
            data_off = b.CreateByteVector(bytes(bf.DataAsNumpy().tobytes()))
        tfl.Buffer.StartBuffer(b)
        if data_off:
            tfl.Buffer.AddData(b, data_off)
        orig_buf_offsets.append(tfl.Buffer.EndBuffer(b))
    # new fp16 buffers for candidates
    new_buf_id = {}
    for k, tid in enumerate(sorted(cands)):
        t = g.Tensors(tid)
        src = m.Buffers(t.Buffer())
        arr = np.frombuffer(src.DataAsNumpy().tobytes(), dtype="<f4").astype("<f2")
        data_off = b.CreateByteVector(arr.tobytes())
        tfl.Buffer.StartBuffer(b)
        tfl.Buffer.AddData(b, data_off)
        new_buf_id[tid] = m.BuffersLength() + k
        orig_buf_offsets.append(tfl.Buffer.EndBuffer(b))

    bufs_vec = b.StartVector(4, len(orig_buf_offsets), 4)
    for off in reversed(orig_buf_offsets):
        b.PrependUOffsetTRelative(off)
    bufs_vec = b.EndVector()

    # --- operator codes ---
    code_offsets = []
    deq_code_idx = None
    have_deq = any(
        (oc.DeprecatedBuiltinCode() not in (0, 127) and oc.DeprecatedBuiltinCode() == tfl.BuiltinOperator.DEQUANTIZE)
        or oc.BuiltinCode() == tfl.BuiltinOperator.DEQUANTIZE
        for oc in (m.OperatorCodes(i) for i in range(m.OperatorCodesLength()))
    )
    if not have_deq:
        deq_code_idx = m.OperatorCodesLength()
    for i in range(m.OperatorCodesLength()):
        oc = m.OperatorCodes(i)
        dep = oc.DeprecatedBuiltinCode()
        code = oc.BuiltinCode()
        ver = oc.Version()
        cc = oc.CustomCode()
        cc_off = b.CreateString(cc.decode()) if cc else 0
        tfl.OperatorCode.StartOperatorCode(b)
        if dep not in (0,) and dep is not None:
            tfl.OperatorCode.AddDeprecatedBuiltinCode(b, dep)
        tfl.OperatorCode.AddBuiltinCode(b, code)
        if cc_off:
            tfl.OperatorCode.AddCustomCode(b, cc_off)
        tfl.OperatorCode.AddVersion(b, ver if ver else 1)
        code_offsets.append(tfl.OperatorCode.EndOperatorCode(b))
    if deq_code_idx is not None:
        tfl.OperatorCode.StartOperatorCode(b)
        tfl.OperatorCode.AddDeprecatedBuiltinCode(b, tfl.BuiltinOperator.DEQUANTIZE)
        tfl.OperatorCode.AddVersion(b, 1)
        code_offsets.append(tfl.OperatorCode.EndOperatorCode(b))

    codes_vec = b.StartVector(4, len(code_offsets), 4)
    for off in reversed(code_offsets):
        b.PrependUOffsetTRelative(off)
    codes_vec = b.EndVector()

    # --- tensors ---
    tensor_offsets = []
    for i in range(ntensors):
        t = g.Tensors(i)
        if i in cands:
            st = rebuild_tensor(b, t, new_type=tfl.TensorType.FLOAT16,
                                new_buffer=new_buf_id[i], suffix="_w16")
            al = rebuild_tensor(b, t, new_type=tfl.TensorType.FLOAT32,
                                new_buffer=0, suffix="_w16_deq")
            tensor_offsets.append(st)
            tensor_offsets.append(al)
        else:
            tensor_offsets.append(rebuild_tensor(b, t))
    tensors_vec = b.StartVector(4, len(tensor_offsets), 4)
    for off in reversed(tensor_offsets):
        b.PrependUOffsetTRelative(off)
    tensors_vec = b.EndVector()

    # --- operators ---
    def build_dequant(storage_tid, alias_tid):
        io_in = b.StartVector(4, 1, 4)
        b.PrependInt32(storage_tid)
        vin = b.EndVector()
        io_out = b.StartVector(4, 1, 4)
        b.PrependInt32(alias_tid)
        vout = b.EndVector()
        tfl.DequantizeOptions.StartDequantizeOptions(b)
        tfl.DequantizeOptions.AddType(b, tfl.TensorType.FLOAT32)
        opts = tfl.DequantizeOptions.EndDequantizeOptions(b)
        tfl.Operator.StartOperator(b)
        idx = deq_code_idx if deq_code_idx is not None else next(
            i for i in range(m.OperatorCodesLength())
            if (m.OperatorCodes(i).DeprecatedBuiltinCode() == tfl.BuiltinOperator.DEQUANTIZE
                or m.OperatorCodes(i).BuiltinCode() == tfl.BuiltinOperator.DEQUANTIZE)
        )
        tfl.Operator.AddOpcodeIndex(b, idx)
        tfl.Operator.AddInputs(b, vin)
        tfl.Operator.AddOutputs(b, vout)
        tfl.Operator.AddBuiltinOptionsType(b, tfl.BuiltinOptions.DequantizeOptions)
        tfl.Operator.AddBuiltinOptions(b, opts)
        return tfl.Operator.EndOperator(b)

    def rewire(op):
        ins = [op.Inputs(j) for j in range(op.InputsLength())]
        outs = [op.Outputs(j) for j in range(op.OutputsLength())]
        ins = [alias_id.get(x, x) for x in ins]
        vin = b.StartVector(4, max(1, len(ins)), 4)
        for x in reversed(ins):
            b.PrependInt32(x if x >= 0 else -1)
        vin = b.EndVector()
        vout = b.StartVector(4, max(1, len(outs)), 4)
        for x in reversed(outs):
            b.PrependInt32(x if x >= 0 else -1)
        vout = b.EndVector()
        opts = rebuild_options(b, op)
        tfl.Operator.StartOperator(b)
        tfl.Operator.AddOpcodeIndex(b, op.OpcodeIndex())
        tfl.Operator.AddInputs(b, vin)
        tfl.Operator.AddOutputs(b, vout)
        if opts:
            tfl.Operator.AddBuiltinOptionsType(b, op.BuiltinOptionsType())
            tfl.Operator.AddBuiltinOptions(b, opts)
        return tfl.Operator.EndOperator(b)

    op_offsets = []
    pending = dict(inserts_at)
    for p in range(nops):
        for tid in sorted(pending.pop(p, [])):
            op_offsets.append(build_dequant(storage_id[tid], alias_id[tid]))
        op_offsets.append(rewire(g.Operators(p)))
    for p in sorted(pending.keys()):  # candidates never consumed (shouldn't happen)
        for tid in pending[p]:
            op_offsets.append(build_dequant(storage_id[tid], alias_id[tid]))

    ops_vec = b.StartVector(4, len(op_offsets), 4)
    for off in reversed(op_offsets):
        b.PrependUOffsetTRelative(off)
    ops_vec = b.EndVector()

    # --- subgraph ---
    name_off = b.CreateString("main")
    in_n = g.InputsLength()
    tfl.SubGraph.StartInputsVector(b, in_n)
    for j in reversed(range(in_n)):
        b.PrependInt32(g.Inputs(j))
    inputs_vec = b.EndVector()
    out_n = g.OutputsLength()
    tfl.SubGraph.StartOutputsVector(b, out_n)
    for j in reversed(range(out_n)):
        b.PrependInt32(g.Outputs(j))
    outputs_vec = b.EndVector()

    tfl.SubGraph.StartSubGraph(b)
    tfl.SubGraph.AddName(b, name_off)
    tfl.SubGraph.AddTensors(b, tensors_vec)
    tfl.SubGraph.AddInputs(b, inputs_vec)
    tfl.SubGraph.AddOutputs(b, outputs_vec)
    tfl.SubGraph.AddOperators(b, ops_vec)
    sg = tfl.SubGraph.EndSubGraph(b)

    subgraphs_vec = b.StartVector(4, 1, 4)
    b.PrependUOffsetTRelative(sg)
    subgraphs_vec = b.EndVector()

    desc_off = 0
    if m.Description() is not None:
        desc_off = b.CreateString(m.Description().decode())

    tfl.Model.StartModel(b)
    tfl.Model.AddVersion(b, m.Version())
    tfl.Model.AddOperatorCodes(b, codes_vec)
    tfl.Model.AddSubgraphs(b, subgraphs_vec)
    tfl.Model.AddDescription(b, desc_off) if desc_off else None
    tfl.Model.AddBuffers(b, bufs_vec)
    root = tfl.Model.EndModel(b)
    b.finish(root)

    out = b.Output()
    open(DST, "wb").write(out)
    print(f"surgery done: {len(cands)} weights -> fp16, {nops}+{len(op_offsets)-nops} ops "
          f"({len(out)/(1024*1024):.1f} MB) -> {DST}")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", action="store_true")
    args = ap.parse_args()
    if not os.path.isfile(SRC):
        print("missing", SRC)
        return 2
    _, m = load_model(SRC)
    info = census(m)
    print("opcodes:", [(c, cc) for c, cc in info["codes"]])
    print("custom_ops:", info["custom_ops"])
    bo_names = {v: k for k, v in vars(tfl.BuiltinOptions).items() if isinstance(v, int)}
    print("option types:", {bo_names.get(k, k): v for k, v in sorted(info["opt_types"].items())})
    print(f"candidates: {len(info['candidates'])} tensors, {info['cand_bytes']/(1024*1024):.1f} MB")
    if args.scan:
        return 0
    return apply()


if __name__ == "__main__":
    sys.exit(main())
