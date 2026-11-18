"""Validate required native playback components before distributing an APK."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

MACHINES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40)}
# nuvio-libass-release.aar statically links libass into libasskt.so (no separate libass.so).
REQUIRED = {
    "libdovi_bridge.so",
    "libffmpegJNI.so",
    "libasskt.so",
    "libdav1dJNI.so",
    "libnuvio_engine.so",
    "libnuviothumb.so",
    "libmpv.so",
    "libmediainfo.so",
    "libquickjs.so",
}


def verify(apk: Path, abis: list[str], allow_only_expected: bool = True) -> dict:
    expected_set = set(abis)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f"Corrupt ZIP entry: {corrupt}")
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        hashes = {}
        for abi in abis:
            elf_class, machine = MACHINES[abi]
            abi_libs = [n for n in libraries if n.startswith(f"lib/{abi}/")]
            missing = REQUIRED - {name.rsplit("/", 1)[-1] for name in abi_libs}
            if missing:
                raise ValueError(f"Missing native playback libraries for {abi}: {sorted(missing)}")
            for name in abi_libs:
                data = archive.read(name)
                if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != elf_class or data[5] != 1:
                    raise ValueError(f"Invalid ELF class/encoding: {name}")
                if struct.unpack_from("<H", data, 18)[0] != machine:
                    raise ValueError(f"Wrong native architecture: {name}")
                hashes[name] = hashlib.sha256(data).hexdigest()
            bridge = archive.read(f"lib/{abi}/libdovi_bridge.so")
            if b"dovi-bridge-libdovi-capi-0.2" not in bridge or b"dovi_convert_rpu_with_mode" not in bridge:
                raise ValueError(f"Dolby Vision bridge for {abi} lacks real libdovi linkage")
        if allow_only_expected:
            for name in libraries:
                parts = name.split("/")
                if len(parts) < 3 or parts[1] not in expected_set:
                    raise ValueError(f"Unexpected ABI in APK: {name}")
    return {"apk": str(apk), "abis": abis, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "nativeLibraries": hashes}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--abi", choices=MACHINES)
    group.add_argument("--universal", action="store_true", help="Verify both armeabi-v7a and arm64-v8a in one universal APK")
    args = parser.parse_args()
    target_abis = ["armeabi-v7a", "arm64-v8a"] if args.universal else [args.abi]
    try:
        print(json.dumps(verify(args.apk, target_abis), indent=2))
    except (ValueError, zipfile.BadZipFile, OSError) as error:
        parser.exit(1, f"Native APK validation failed: {error}\n")
