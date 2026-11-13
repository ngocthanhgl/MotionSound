"""Compatibility shim exposing flatc-generated TFLite schema classes with the
older schema_py_generated API surface (Start<Name>/End<Name> aliases).

flatc >= 23 emits `Start(builder)`/`End(builder)` on every table, while
tensorflow.lite.python.schema_py_generated uses `Start<Table>`/`End<Table>`.
tools/fp16_weights_surgery.py was written against the latter.

Usage:
    from tflite_schema_compat import schema as tfl
"""

import importlib
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_GEN = os.path.join(_HERE, "schema_gen")
if _GEN not in sys.path:
    sys.path.insert(0, _GEN)

# Plain classes (enums) and tables that need Start/End aliases.
_ENUMS = [
    "BuiltinOperator",
    "BuiltinOptions",
    "TensorType",
    "ActivationFunctionType",
]

_TABLES = [
    "Model",
    "SubGraph",
    "Operator",
    "OperatorCode",
    "Tensor",
    "Buffer",
    "Conv2DOptions",
    "DepthwiseConv2DOptions",
    "TransposeConvOptions",
    "FullyConnectedOptions",
    "ReshapeOptions",
    "SoftmaxOptions",
    "ConcatenationOptions",
    "AddOptions",
    "MulOptions",
    "SubOptions",
    "DivOptions",
    "ReducerOptions",
    "StridedSliceOptions",    "SqueezeOptions",
    "LeakyReluOptions",
    "L2NormOptions",
    "GatherOptions",
    "SplitOptions",
    "BatchMatMulOptions",
    "Pool2DOptions",
    "TransposeOptions",
    "PadOptions",
    "PadV2Options",
    "AbsOptions",
    "LogisticOptions",
    "ReluOptions",
    "Relu6Options",
    "RsqrtOptions",
    "SqrtOptions",
    "SliceOptions",
    "NegOptions",
    "FloorDivOptions",
    "FloorModOptions",
    "MaximumMinimumOptions",
    "SelectV2Options",
    "EqualOptions",
    "NotEqualOptions",
    "GreaterOptions",
    "GreaterEqualOptions",
    "LessOptions",
    "LessEqualOptions",
    "ExpOptions",
    "RangeOptions",
    "DequantizeOptions",
]


def _build(name, mod, orig):
    if hasattr(mod, "Start") and hasattr(mod, "End"):
        # flatc-generated code keeps all builder helpers (Start/End/Add*/Start*Vector/
        # Create*) at module level; alias them onto the class so the older
        # schema_py_generated call style (tfl.Model.StartModel(b), etc.) works.
        attrs = {fn: staticmethod(getattr(mod, fn)) for fn in dir(mod)
                 if not fn.startswith("_") and callable(getattr(mod, fn))
                 and not fn.startswith("GetRootAs") and fn != "Init"}
        attrs["Start" + name] = staticmethod(mod.Start)
        attrs["End" + name] = staticmethod(mod.End)
        return type(name, (orig,), attrs)
    return orig


class _Schema:
    pass


def _load():
    import tflite as _tflpkg  # noqa: F401  (registers package path)
    for name in _ENUMS + _TABLES:
        try:
            mod = importlib.import_module("tflite." + name)
        except ModuleNotFoundError:
            continue  # some option structs don't exist in the current schema
        orig = getattr(mod, name)
        setattr(_Schema, name, _build(name, mod, orig))
    # schema_py_generated exposes MeanOptions/SumOptions as aliases of ReducerOptions
    try:
        reducer_mod = importlib.import_module("tflite.ReducerOptions")
        reducer_cls = getattr(_Schema, "ReducerOptions")
    except ModuleNotFoundError:
        pass
    else:
        for alias in ("MeanOptions", "SumOptions"):
            setattr(
                _Schema,
                alias,
                type(alias, (reducer_cls,), {
                    "Start" + alias: staticmethod(reducer_mod.Start),
                    "End" + alias: staticmethod(reducer_mod.End),
                }),
            )


_load()
schema = _Schema()
