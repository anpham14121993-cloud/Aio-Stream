#!/usr/bin/env python3
"""
Generates compact genuine AAC (.m4a) voice preview samples for all 17 Piper ONNX
voices in `TtsVoiceCatalog` so users can press "Nghe thử" BEFORE downloading
the 63.5 MB voice model — works offline right out of the APK.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.request
import wave
from pathlib import Path

SAMPLE_TEXT = "Xin chào, đây là giọng đọc thử của tôi."
HF_BASE_URL = "https://huggingface.co/dinhphuongvn/ttsvn/resolve/main"
VOICES = [
    "ngoc_huyen_new",
    "ban_mai",
    "lac_phi",
    "mai_phuong",
    "my_tam",
    "phuong_trang",
    "tai_an",
    "thanh_phuong",
    "thien_tam",
    "minh_quang",
    "duy_oryx",
    "chieu_thanh",
    "manh_dung",
    "minh_khang",
    "ngoc_ngan",
    "tran_thanh",
    "viet_thao",
]


def download(url: str, dest: Path, retries: int = 3) -> None:
    if dest.exists() and dest.stat().st_size > 1024:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    last_err: Exception | None = None
    for attempt in range(retries):
        tmp = dest.with_suffix(dest.suffix + ".tmp")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "AioStreamPreviewGen/1.0"})
            with urllib.request.urlopen(req, timeout=90) as resp, tmp.open("wb") as out:
                shutil.copyfileobj(resp, out, length=64 * 1024)
            tmp.replace(dest)
            return
        except Exception as exc:
            last_err = exc
            tmp.unlink(missing_ok=True)
            if attempt + 1 < retries:
                time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Failed to download {url}: {last_err}")


def espeak_ipa(text: str) -> str:
    proc = subprocess.run(
        ["espeak-ng", "-q", "--ipa=3", "-v", "vi", text],
        check=True,
        capture_output=True,
        text=True,
    )
    return " ".join(proc.stdout.replace("\u200d", "").split())


def phonemes_to_ids(ipa: str, phoneme_id_map: dict[str, list[int]]) -> list[int]:
    nfd = unicodedata.normalize("NFD", ipa)
    pad = phoneme_id_map.get("_", [0])
    out: list[int] = []
    out.extend(phoneme_id_map.get("^", [1]))
    out.extend(pad)
    for ch in nfd:
        ids = phoneme_id_map.get(ch)
        if ids:
            out.extend(ids)
            out.extend(pad)
    out.extend(phoneme_id_map.get("$", [2]))
    return out


def trim_and_write_wav(wav_path: Path, samples, sample_rate: int) -> None:
    import numpy as np

    arr = np.asarray(samples, dtype=np.float32).reshape(-1)
    if arr.size == 0:
        raise RuntimeError("Empty audio output from ONNX")

    # Trim leading/trailing silence smoothly, matching TtsAudioPostProcessor.trimSilence
    win = max((sample_rate * 10) // 1000, 64)
    if arr.size > win * 2:
        active = np.where(np.abs(arr) > 0.0052)[0]
        if active.size > 0:
            pad = (sample_rate * 45) // 1000
            start = max(0, int(active[0]) - pad)
            end = min(arr.size, int(active[-1]) + pad + 1)
            arr = arr[start:end]

    peak = float(np.max(np.abs(arr)))
    if peak > 1e-4:
        arr = arr * min(0.88 / peak, 3.0)

    pcm = np.clip(arr * 32767.0, -32768, 32767).astype("<i2")
    with wave.open(str(wav_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm.tobytes())


def main() -> int:
    import numpy as np
    import onnxruntime as ort

    repo_root = Path(__file__).resolve().parent.parent
    out_dir = repo_root / "app" / "src" / "main" / "assets" / "tts_samples"
    out_dir.mkdir(parents=True, exist_ok=True)

    missing = [
        v for v in VOICES
        if not (out_dir / f"{v}.m4a").exists() or (out_dir / f"{v}.m4a").stat().st_size < 4096
    ]
    if not missing:
        print(f"All {len(VOICES)} TTS voice preview samples already cached in {out_dir}")
        return 0

    ipa = espeak_ipa(SAMPLE_TEXT)
    print(f"Synthesizing {len(missing)} Piper previews using IPA={ipa!r}")

    cache_root = Path(os.environ.get("PIPER_PREVIEW_CACHE", tempfile.gettempdir())) / "piper_preview_models"
    cache_root.mkdir(parents=True, exist_ok=True)

    for voice_id in missing:
        m4a_out = out_dir / f"{voice_id}.m4a"
        cfg_path = cache_root / f"{voice_id}.onnx.json"
        onnx_path = cache_root / f"{voice_id}.onnx"
        download(f"{HF_BASE_URL}/{voice_id}.onnx.json", cfg_path)
        download(f"{HF_BASE_URL}/{voice_id}.onnx", onnx_path)

        cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        sample_rate = int(cfg.get("audio", {}).get("sample_rate", 22050))
        inf = cfg.get("inference", {})
        noise_scale = float(inf.get("noise_scale", 0.667))
        length_scale = float(inf.get("length_scale", 1.0))
        noise_w = float(inf.get("noise_w", 0.8))

        ids = phonemes_to_ids(ipa, cfg["phoneme_id_map"])
        if len(ids) <= 3:
            raise RuntimeError(f"Empty phoneme IDs for {voice_id}")

        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        inputs = {
            "input": np.array([ids], dtype=np.int64),
            "input_lengths": np.array([len(ids)], dtype=np.int64),
            "scales": np.array([noise_scale, length_scale, noise_w], dtype=np.float32),
        }
        if any(inp.name == "sid" for inp in sess.get_inputs()):
            inputs["sid"] = np.array([0], dtype=np.int64)

        audio = sess.run(None, inputs)[0]
        wav_tmp = cache_root / f"{voice_id}.wav"
        m4a_tmp = m4a_out.with_suffix(".tmp.m4a")
        trim_and_write_wav(wav_tmp, audio, sample_rate)

        subprocess.run(
            [
                "ffmpeg", "-y", "-v", "error",
                "-i", str(wav_tmp),
                "-c:a", "aac", "-b:a", "36k", "-ar", str(sample_rate), "-ac", "1",
                "-movflags", "+faststart",
                str(m4a_tmp),
            ],
            check=True,
        )
        if m4a_tmp.stat().st_size < 2048:
            raise RuntimeError(f"Preview audio too small for {voice_id}: {m4a_tmp.stat().st_size} bytes")
        m4a_tmp.replace(m4a_out)
        wav_tmp.unlink(missing_ok=True)
        # Keep runner disk lean by removing 63 MB .onnx after synthesis
        onnx_path.unlink(missing_ok=True)
        print(f"  Generated {m4a_out.name} ({m4a_out.stat().st_size} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
