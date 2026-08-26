"""Export SpeechBrain's ECAPA-TDNN speaker encoder to ONNX.

The Fbank frontend is traced into the graph, so the exported model takes a raw
16 kHz mono waveform normalized to [-1, 1] and returns a 192-dim speaker
embedding. That keeps the mel-filterbank config out of Kotlin, where a silent
mismatch would degrade accuracy instead of failing.

Three SpeechBrain modules are swapped for equivalents that survive tracing.
Each one is a correctness fix, not an optimization, and verify_parity.py
measures the result against the unmodified pipeline:

  ConvStft                             neither exporter can lower torch.stft
  SentenceMeanNorm                     original freezes the frame count
  FullLengthAttentiveStatisticsPooling original freezes the mask length

All three replacements are exact for a single un-padded utterance, which is the
only thing the app ever submits.

Usage:
    python tools/onnx/export_ecapa.py

Writes tools/onnx/build/ecapa_tdnn.onnx. Model files are never committed.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

BUILD_DIR = Path(__file__).resolve().parent / "build"
SOURCE_MODEL = "speechbrain/spkrec-ecapa-voxceleb"
SAMPLE_RATE = 16_000
OPSET = 17

# Durations the smoke check exercises. The traced length is not enough: a frozen
# shape produces wrong numbers at other lengths rather than an error.
SMOKE_DURATIONS = (1.0, 2.0, 3.0, 5.0)
MIN_SMOKE_COSINE = 0.9999


class ConvStft(nn.Module):
    """Real-valued STFT as a strided conv1d, standing in for torch.stft.

    Neither ONNX exporter can lower SpeechBrain's STFT: the TorchScript
    exporter rejects complex types ("STFT does not currently support complex
    types") and torch.export has no meta kernel for aten._fft_r2c. Writing the
    DFT as a convolution keeps the frontend inside the graph using only Conv and
    MatMul, which also avoids depending on ONNX Runtime shipping an STFT kernel
    in the Android build.

    Equivalent to torch.stft up to float rounding; measured at ~1e-7 relative.
    """

    def __init__(
        self,
        n_fft: int,
        hop_length: int,
        window: torch.Tensor,
        center: bool,
        pad_mode: str,
        normalized: bool,
        onesided: bool,
    ) -> None:
        super().__init__()
        if not onesided:
            raise ValueError("only onesided STFT is supported")
        self.n_fft = int(n_fft)
        self.hop_length = int(hop_length)
        self.center = bool(center)
        self.pad_mode = pad_mode
        self.pad_amount = self.n_fft // 2
        self.n_freq = self.n_fft // 2 + 1

        # Centre the analysis window in an n_fft frame, matching torch.stft when
        # win_length < n_fft.
        window = window.detach().to(torch.float64)
        framed_window = torch.zeros(self.n_fft, dtype=torch.float64)
        offset = (self.n_fft - window.numel()) // 2
        framed_window[offset:offset + window.numel()] = window

        # Basis built in float64 and cast once, so rounding happens a single time.
        sample = torch.arange(self.n_fft, dtype=torch.float64)
        freq = torch.arange(self.n_freq, dtype=torch.float64).unsqueeze(1)
        angle = 2.0 * math.pi * freq * sample / self.n_fft
        scale = 1.0 / math.sqrt(self.n_fft) if normalized else 1.0
        real = torch.cos(angle) * framed_window * scale
        imag = -torch.sin(angle) * framed_window * scale

        weight = torch.cat([real, imag], dim=0).to(torch.float32).unsqueeze(1)
        self.register_buffer("weight", weight)  # [2 * n_freq, 1, n_fft]

    def forward(self, waveform: torch.Tensor) -> torch.Tensor:
        if waveform.dim() != 2:
            raise ValueError(f"expected [batch, samples], got {tuple(waveform.shape)}")
        x = waveform.unsqueeze(1)  # [B, 1, T]
        if self.center:
            x = F.pad(x, (self.pad_amount, self.pad_amount), mode=self.pad_mode)
        spectrum = F.conv1d(x, self.weight, stride=self.hop_length)  # [B, 2F, frames]
        real = spectrum[:, : self.n_freq]
        imag = spectrum[:, self.n_freq :]
        stft = torch.stack((real, imag), dim=-1)  # [B, F, frames, 2]
        return stft.transpose(1, 2)  # SpeechBrain yields [B, frames, F, 2]


class SentenceMeanNorm(nn.Module):
    """InputNormalization(norm_type="sentence", std_norm=False), traceable.

    The original computes actual_size = round(lengths * x.shape[1]) and slices
    the utterance with it. Under tracing that becomes a constant, so audio
    longer than the traced clip gets normalized using only its first traced-many
    frames. Since a single un-padded utterance always spans its whole tensor,
    subtracting the per-feature mean over time is exactly what the original
    computes, and it keeps the time axis dynamic.
    """

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x - x.mean(dim=1, keepdim=True)


class FullLengthAttentiveStatisticsPooling(nn.Module):
    """AttentiveStatisticsPooling with a dynamically shaped all-ones mask.

    The original builds its mask with length_to_mask(lengths * L, max_len=L),
    where L is a Python int during tracing, so the mask length is frozen into
    the graph and every other duration is pooled over the wrong span. This was
    worth 0.88 cosine instead of 1.0. With one un-padded utterance the mask is
    all ones, so deriving it from the tensor keeps the axis dynamic and the
    arithmetic identical. masked_fill is dropped as a no-op under an all-ones
    mask.

    lengths is accepted only to match the call site in ECAPA_TDNN.forward and is
    deliberately ignored, since the app never submits padded batches.
    """

    def __init__(self, pooling: nn.Module) -> None:
        super().__init__()
        self.tdnn = pooling.tdnn
        self.tanh = pooling.tanh
        self.conv = pooling.conv
        self.global_context = pooling.global_context
        self.eps = pooling.eps

    def _statistics(
        self, values: torch.Tensor, weights: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor]:
        mean = (weights * values).sum(dim=2)
        variance = (weights * (values - mean.unsqueeze(2)).pow(2)).sum(dim=2)
        return mean, torch.sqrt(variance.clamp(self.eps))

    def forward(self, x: torch.Tensor, lengths: torch.Tensor | None = None) -> torch.Tensor:
        mask = torch.ones_like(x[:, :1, :])  # [B, 1, frames], shape follows x

        if self.global_context:
            total = mask.sum(dim=2, keepdim=True)
            mean, std = self._statistics(x, mask / total)
            # expand_as rather than repeat(L): repeat would bake the frame count.
            attn = torch.cat(
                [x, mean.unsqueeze(2).expand_as(x), std.unsqueeze(2).expand_as(x)],
                dim=1,
            )
        else:
            attn = x

        attn = self.conv(self.tanh(self.tdnn(attn)))
        attn = F.softmax(attn, dim=2)
        mean, std = self._statistics(x, attn)
        return torch.cat((mean, std), dim=1).unsqueeze(2)


class EcapaEmbedder(nn.Module):
    """Fbank -> per-utterance norm -> ECAPA-TDNN, mirroring encode_batch.

    Batch is fixed at 1: SpeechBrain loops over the batch in Python, which
    tracing would freeze anyway, and the app verifies one utterance at a time.

    use_lengths keeps the reference path byte-identical to SpeechBrain. The
    export path passes no lengths so SEBlock takes its unmasked mean branch,
    which is the same value for un-padded input but stays dynamic.
    """

    def __init__(self, mods: nn.ModuleDict) -> None:
        super().__init__()
        self.compute_features = mods.compute_features
        self.mean_var_norm = mods.mean_var_norm
        self.embedding_model = mods.embedding_model
        self.use_lengths = True

    def forward(self, waveform: torch.Tensor) -> torch.Tensor:
        feats = self.compute_features(waveform)
        if self.use_lengths:
            lengths = torch.ones(waveform.shape[0], dtype=waveform.dtype)
            feats = self.mean_var_norm(feats, lengths)
            embedding = self.embedding_model(feats, lengths)
        else:
            feats = self.mean_var_norm(feats)
            embedding = self.embedding_model(feats)
        # Indexing rather than squeeze(1): TorchScript lowers squeeze(dim) into an
        # ONNX If that guards against the dim not being 1, which leaves dynamic
        # control flow at the output and makes the output shape symbolic instead
        # of [1, 192]. Indexing is unconditional.
        return embedding[:, 0, :]  # [1, 192]


def tracing_input(seconds: float) -> torch.Tensor:
    """Deterministic, non-degenerate waveform used to trace the graph.

    Tracing on silence yields a silently wrong model: the fbank output is
    constant for an all-zero waveform, so the per-utterance mean inside the
    normalization folds into the graph as a fixed constant and stops depending
    on the input. The export still succeeds and still returns 192-dim
    embeddings; they are simply wrong, measured at 0.03 cosine against the
    reference. Any input with real spectral content avoids this.
    """
    samples = int(SAMPLE_RATE * seconds)
    generator = torch.Generator().manual_seed(20260826)
    time = torch.arange(samples, dtype=torch.float32) / SAMPLE_RATE
    f0 = 130.0
    signal = sum(
        (1.0 / harmonic) * torch.sin(2 * math.pi * f0 * harmonic * time)
        for harmonic in range(1, 10)
    )
    signal = signal + 0.05 * torch.randn(samples, generator=generator)
    signal = signal / signal.abs().max().clamp(min=1e-9) * 0.9
    return signal.unsqueeze(0)


def patch_hf_download() -> None:
    """Bridge speechbrain 1.0.2 to huggingface-hub >= 1.0.

    speechbrain calls hf_hub_download(use_auth_token=...), which hub 1.x removed
    in favour of token=. It also probes for an optional custom.py and treats
    ValueError as "absent", while hub 1.x raises its own not-found error that
    speechbrain does not catch. Shimming both here avoids pinning either package
    to an old release.
    """
    import inspect

    import huggingface_hub
    from huggingface_hub import errors as hf_errors

    original = huggingface_hub.hf_hub_download
    if "use_auth_token" in inspect.signature(original).parameters:
        return  # Old enough not to need the shim.

    not_found = tuple(
        err
        for err in (
            getattr(hf_errors, "RemoteEntryNotFoundError", None),
            getattr(hf_errors, "EntryNotFoundError", None),
        )
        if err is not None
    )

    def shim(*args, **kwargs):
        legacy_token = kwargs.pop("use_auth_token", None)
        if legacy_token is not None and "token" not in kwargs:
            kwargs["token"] = legacy_token
        try:
            return original(*args, **kwargs)
        except not_found as exc:
            raise ValueError(str(exc)) from exc

    huggingface_hub.hf_hub_download = shim


def load_embedder() -> EcapaEmbedder:
    """The unmodified pipeline, used as the parity reference."""
    patch_hf_download()
    from speechbrain.inference.speaker import EncoderClassifier

    classifier = EncoderClassifier.from_hparams(
        source=SOURCE_MODEL,
        savedir=str(BUILD_DIR / "pretrained"),
        run_opts={"device": "cpu"},
    )
    embedder = EcapaEmbedder(classifier.mods).eval()
    for parameter in embedder.parameters():
        parameter.requires_grad_(False)
    return embedder


def make_onnx_ready(embedder: EcapaEmbedder) -> EcapaEmbedder:
    """Apply the three traceability substitutions described in the module docstring."""
    stft = embedder.compute_features.compute_STFT
    embedder.compute_features.compute_STFT = ConvStft(
        n_fft=stft.n_fft,
        hop_length=stft.hop_length,
        window=stft.window,
        center=stft.center,
        pad_mode=stft.pad_mode,
        normalized=stft.normalized_stft,
        onesided=stft.onesided,
    )
    embedder.mean_var_norm = SentenceMeanNorm()
    embedder.embedding_model.asp = FullLengthAttentiveStatisticsPooling(
        embedder.embedding_model.asp
    )
    embedder.use_lengths = False
    return embedder.eval()


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    left = a.astype(np.float64).ravel()
    right = b.astype(np.float64).ravel()
    return float(left.dot(right) / (np.linalg.norm(left) * np.linalg.norm(right)))


def smoke_check(embedder: EcapaEmbedder, output_path: Path) -> None:
    """Refuse to leave an unfaithful graph on disk.

    Catches the two failures already hit here: an output that ignores its input,
    and a graph that only works at the traced duration. verify_parity.py is the
    real gate; this just stops a broken 80 MB artifact from being written.
    """
    import onnxruntime as ort

    session = ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
    worst = 1.0
    for seconds in SMOKE_DURATIONS:
        probe = tracing_input(seconds) * 0.5
        with torch.no_grad():
            expected = embedder(probe).numpy()
        actual = session.run(["embedding"], {"waveform": probe.numpy()})[0]
        similarity = cosine(expected, actual)
        worst = min(worst, similarity)
        print(f"  smoke {seconds:>4.1f}s cosine {similarity:.8f}")
    if worst < MIN_SMOKE_COSINE:
        output_path.unlink(missing_ok=True)
        raise SystemExit(
            f"export is not faithful (worst cosine {worst:.6f}) - deleted {output_path.name}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=float, default=3.0,
                        help="Audio length used to trace the graph.")
    parser.add_argument("--exporter", choices=["torchscript", "dynamo"],
                        default="torchscript",
                        help="dynamo fails on a data-dependent shape in this model.")
    args = parser.parse_args()

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    embedder = make_onnx_ready(load_embedder())

    dummy = tracing_input(args.seconds)  # never silence: see tracing_input
    with torch.no_grad():
        reference = embedder(dummy)
    print(f"torch forward ok: input {tuple(dummy.shape)} -> {tuple(reference.shape)}")

    output_path = BUILD_DIR / "ecapa_tdnn.onnx"
    with torch.no_grad():
        if args.exporter == "dynamo":
            samples = torch.export.Dim("samples", min=1600, max=16 * SAMPLE_RATE)
            torch.onnx.export(
                embedder, (dummy,), str(output_path),
                input_names=["waveform"], output_names=["embedding"],
                dynamic_shapes={"waveform": {1: samples}},
                opset_version=OPSET, dynamo=True,
            )
        else:
            torch.onnx.export(
                embedder, (dummy,), str(output_path),
                input_names=["waveform"], output_names=["embedding"],
                # Only time is dynamic; see EcapaEmbedder for why batch is fixed.
                dynamic_axes={"waveform": {1: "samples"}},
                opset_version=OPSET, do_constant_folding=True,
            )

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"exported {output_path} ({size_mb:.1f} MB, opset {OPSET})")
    smoke_check(embedder, output_path)


if __name__ == "__main__":
    main()
