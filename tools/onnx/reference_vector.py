"""Desktop side of the on-device self-test.

Generates the exact waveform ModelSelfTest.kt feeds the model, runs it through the
exported graph with desktop ONNX Runtime, and prints the same statistics the app
logs. Matching numbers mean ONNX Runtime for Android agrees with the desktop build
that M2 was verified against; a kernel difference would show up here as a
plausible-but-wrong embedding rather than a crash.

The arithmetic must stay in step with ModelSelfTest.referenceWaveform: accumulate
in float64, narrow to float32 once.

Usage:
    python tools/onnx/reference_vector.py
"""

from __future__ import annotations

import math
import time
from pathlib import Path

import numpy as np

MODEL_PATH = Path(__file__).resolve().parent / "build" / "ecapa_tdnn.onnx"
SAMPLE_RATE = 16_000
SECONDS = 3.0
HEAD_VALUES = 8


def reference_waveform() -> np.ndarray:
    total = int(SAMPLE_RATE * SECONDS)
    index = np.arange(total, dtype=np.float64)
    t = index / SAMPLE_RATE
    value = (
        0.6 * np.sin(2.0 * math.pi * 220.0 * t)
        + 0.3 * np.sin(2.0 * math.pi * 440.0 * t)
        + 0.1 * np.sin(2.0 * math.pi * 880.0 * t)
    )
    return (value * 0.9).astype(np.float32)[None, :]


def main() -> int:
    if not MODEL_PATH.exists():
        print("missing %s; run tools/onnx/export_ecapa.py first" % MODEL_PATH)
        return 2

    import onnxruntime as ort

    waveform = reference_waveform()
    session = ort.InferenceSession(str(MODEL_PATH), providers=["CPUExecutionProvider"])

    session.run(["embedding"], {"waveform": waveform})  # warm up
    start = time.perf_counter()
    embedding = session.run(["embedding"], {"waveform": waveform})[0]
    elapsed_ms = (time.perf_counter() - start) * 1000.0

    norm = float(np.linalg.norm(embedding.astype(np.float64)))
    head = embedding.ravel()[:HEAD_VALUES]

    print("expected on-device values")
    print("  %d samples, %.0f ms (desktop), norm %.4f" % (waveform.size, elapsed_ms, norm))
    print("  " + ", ".join("%.6f" % v for v in head))
    print()
    print("Compare against the app self-test. Norm should agree to ~3 decimals and")
    print("each head value to ~4; larger drift means the Android kernels differ.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
