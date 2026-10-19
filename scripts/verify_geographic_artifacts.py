"""Verify real built APK/AAB assets; never substitute a source-file-only check.

Usage: python -B scripts/verify_geographic_artifacts.py path/to/app.apk path/to/app.aab
No artifacts supplied, missing files, or an incorrect packaged asset fail the command.
"""
import argparse
import gzip
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/assets/geography/boundaries.ltgz"
EXPECTED_SHA256 = "e34ce12b0ad1d5a289704a30848fcb021f6a09ed70babf421e90136df1ebb4e0"
EXPECTED_SIZE = 6_032_996


def verify_artifact(artifact: Path) -> str:
    if artifact.suffix.lower() not in (".apk", ".aab"):
        raise ValueError(f"Expected APK or AAB: {artifact}")
    prefix = "base/" if artifact.suffix.lower() == ".aab" else ""
    expected_path = prefix + "assets/geography/boundaries.ltgz"
    source = SOURCE.read_bytes()
    if len(source) != EXPECTED_SIZE or hashlib.sha256(source).hexdigest() != EXPECTED_SHA256:
        raise ValueError("Source index differs from the original pinned gzip bytes")
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        if names.count(expected_path) != 1:
            raise ValueError(f"{artifact}: expected exactly one {expected_path}")
        if any(prefix + "assets/geography/" + name in names
               for name in ("boundaries.ltg", "boundaries.ltg.gz")):
            raise ValueError(f"{artifact}: legacy geographic asset path still packaged")
        data = archive.read(expected_path)
    if not data.startswith(b"\x1f\x8b\x08"):
        raise ValueError(f"{artifact}: packaged index is not gzip data")
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != EXPECTED_SIZE or digest != EXPECTED_SHA256 or data != source:
        raise ValueError(f"{artifact}: packaged gzip bytes differ from the source index")
    if not gzip.decompress(data).startswith(b"LTG1"):
        raise ValueError(f"{artifact}: invalid geographic binary header")
    return f"{artifact}: {expected_path}, {len(data)} bytes, SHA-256 {digest}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="+", type=Path)
    args = parser.parse_args()
    for artifact in args.artifacts:
        print(verify_artifact(artifact))
