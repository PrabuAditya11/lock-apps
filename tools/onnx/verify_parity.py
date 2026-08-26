"""M2 gate: does the exported ONNX model match the Python original?

Compares the exported graph against the unmodified SpeechBrain pipeline (real
torch.stft, no conv substitution) on identical audio. Two things are checked at
once:

  1. that ONNX Runtime reproduces the PyTorch embeddings, and
  2. that ConvStft is genuinely equivalent to torch.stft.

Several durations are tested on purpose. Tracing emitted a TracerWarning about a
baked-in shape, and that failure mode gives wrong numbers rather than an error,
so a single tested duration would prove nothing.

Usage:
    python tools/onnx/verify_parity.py
    python tools/onnx/verify_parity.py --wav myvoice.wav
"""

from __future__ import annotations

import argparse
import importlib.util
import math
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
MODEL_PATH = HERE / "build" / "ecapa_tdnn.onnx"
SAMPLE_RATE = 16_000

# Cosine is what speaker verification actually consumes, so it carries the
# tighter bound; the relative bound catches gross numeric drift.
MIN_COSINE = 0.9999
MAX_RELATIVE_DIFF = 1e-3
MAX_SCORE_DELTA = 1e-4


def load_export_module():
    spec = importlib.util.spec_from_file_location(
        "export_ecapa", HERE / "export_ecapa.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def synth_signals() -> "dict[str, torch.Tensor]":
    """Deterministic, spectrally varied test inputs of differing lengths."""
    torch.manual_seed(20260826)
    signals = {}

    for seconds in (1.0, 2.0, 3.0, 5.0, 7.3):
        samples = int(SAMPLE_RATE * seconds)
        time = torch.arange(samples, dtype=torch.float32) / SAMPLE_RATE

        # Harmonic stack with amplitude modulation, so the log-mel input is not
        # the flat spectrum that white noise alone would give.
        f0 = 120.0
        speechlike = sum(
            (1.0 / harmonic) * torch.sin(2 * math.pi * f0 * harmonic * time)
            for harmonic in range(1, 12)
        )
        speechlike = speechlike * (0.6 + 0.4 * torch.sin(2 * math.pi * 3.0 * time))
        signal = speechlike + 0.02 * torch.randn(samples)
        signal = signal / signal.abs().max().clamp(min=1e-9) * 0.9
        signals["speechlike-%.1fs" % seconds] = signal.unsqueeze(0)

    noise_samples = int(SAMPLE_RATE * 2.5)
    signals["whitenoise-2.5s"] = (0.5 * torch.randn(noise_samples)).unsqueeze(0)

    time = torch.arange(int(SAMPLE_RATE * 4.0), dtype=torch.float32) / SAMPLE_RATE
    chirp = torch.sin(2 * math.pi * (80.0 + 900.0 * time) * time) * 0.9
    signals["chirp-4.0s"] = chirp.unsqueeze(0)
    return signals


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    a64 = a.astype(np.float64).ravel()
    b64 = b.astype(np.float64).ravel()
    denominator = np.linalg.norm(a64) * np.linalg.norm(b64)
    if denominator == 0.0:
        return float("nan")
    return float(np.dot(a64, b64) / denominator)


def check_stft(export_module, signals) -> bool:
    """ConvStft against torch.stft in isolation.

    Run separately so a frontend error cannot be masked by the encoder
    downstream of it.
    """
    print()
    print("-- ConvStft vs torch.stft --")
    reference = export_module.load_embedder()
    original = reference.compute_features.compute_STFT
    conv = export_module.ConvStft(
        n_fft=original.n_fft,
        hop_length=original.hop_length,
        window=original.window,
        center=original.center,
        pad_mode=original.pad_mode,
        normalized=original.normalized_stft,
        onesided=original.onesided,
    ).eval()

    ok = True
    with torch.no_grad():
        for name, wav in signals.items():
            expected = original(wav)
            actual = conv(wav)
            if expected.shape != actual.shape:
                print(
                    "  %-20s SHAPE MISMATCH %s vs %s"
                    % (name, tuple(expected.shape), tuple(actual.shape))
                )
                ok = False
                continue
            scale = expected.abs().max().item() or 1.0
            relative = (expected - actual).abs().max().item() / scale
            passed = relative <= MAX_RELATIVE_DIFF
            ok = ok and passed
            print(
                "  %-20s shape %-18s rel_max_diff %.3e  %s"
                % (name, str(tuple(actual.shape)), relative, "ok" if passed else "FAIL")
            )
    return ok


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--wav",
        type=Path,
        default=None,
        help="Optional real recording (16 kHz mono) to test alongside.",
    )
    args = parser.parse_args()

    if not MODEL_PATH.exists():
        print("missing %s; run tools/onnx/export_ecapa.py first" % MODEL_PATH)
        return 2

    import onnxruntime as ort

    export_module = load_export_module()
    signals = synth_signals()

    if args.wav is not None:
        import soundfile as sf

        audio, rate = sf.read(str(args.wav), dtype="float32", always_2d=True)
        if rate != SAMPLE_RATE:
            print("%s is %d Hz; needs %d Hz" % (args.wav, rate, SAMPLE_RATE))
            return 2
        mono = torch.from_numpy(audio.mean(axis=1))
        signals["wav:" + args.wav.name] = mono.unsqueeze(0)

    stft_ok = check_stft(export_module, signals)

    print()
    print("-- PyTorch original vs ONNX Runtime --")
    reference = export_module.load_embedder()  # untouched torch.stft pipeline
    session = ort.InferenceSession(
        str(MODEL_PATH), providers=["CPUExecutionProvider"]
    )

    embeddings_torch = {}
    embeddings_onnx = {}
    embedding_ok = True

    with torch.no_grad():
        for name, wav in signals.items():
            torch_embedding = reference(wav).numpy()
            onnx_embedding = session.run(
                ["embedding"], {"waveform": wav.numpy()}
            )[0]
            embeddings_torch[name] = torch_embedding
            embeddings_onnx[name] = onnx_embedding

            scale = float(np.abs(torch_embedding).max()) or 1.0
            relative = float(np.abs(torch_embedding - onnx_embedding).max()) / scale
            similarity = cosine(torch_embedding, onnx_embedding)
            passed = similarity >= MIN_COSINE and relative <= MAX_RELATIVE_DIFF
            embedding_ok = embedding_ok and passed
            print(
                "  %-20s cos %.8f  rel_max_diff %.3e  %s"
                % (name, similarity, relative, "ok" if passed else "FAIL")
            )

    # Verification consumes the cosine between two utterances, so the score
    # itself has to survive the port, not only each embedding in isolation.
    print()
    print("-- speaker score preservation --")
    names = list(signals)
    score_ok = True
    for left, right in zip(names, names[1:]):
        torch_score = cosine(embeddings_torch[left], embeddings_torch[right])
        onnx_score = cosine(embeddings_onnx[left], embeddings_onnx[right])
        delta = abs(torch_score - onnx_score)
        passed = delta <= MAX_SCORE_DELTA
        score_ok = score_ok and passed
        print(
            "  %-20s vs %-20s torch %+.6f  onnx %+.6f  delta %.2e  %s"
            % (left, right, torch_score, onnx_score, delta, "ok" if passed else "FAIL")
        )

    all_ok = stft_ok and embedding_ok and score_ok
    print()
    print("M2 parity:", "PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
