"""Generate LocalTell's bundled polygon index. Python standard library only; no network.

Inputs are pinned in sources.json. Preserve rings/holes/islands; quantize to 1e-6
degree (under 0.2m), delta-varint encode and gzip. No rectangular classification
or centre-point approximation. Bounding boxes/grid cells only select candidates.
"""
import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
SCALE = 1_000_000
# ISO updates relative to the pinned source; same current codes as published packs.
ISO_UPDATES = {"CT": "CG", "OR": "OD", "TG": "TS", "UT": "UK"}
INDIA_NAMES = dict(zip(
    "AN AP AR AS BR CG CH DH DL GA GJ HR HP JH JK KA KL LA LD MH ML MN MP MZ NL OD PB PY RJ SK TN TS TR UK UP WB".split(),
    ["Andaman and Nicobar Islands", "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh",
     "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Goa", "Gujarat", "Haryana",
     "Himachal Pradesh", "Jharkhand", "Jammu and Kashmir", "Karnataka", "Kerala", "Ladakh", "Lakshadweep",
     "Maharashtra", "Meghalaya", "Manipur", "Madhya Pradesh", "Mizoram", "Nagaland", "Odisha", "Punjab",
     "Puducherry", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura", "Uttarakhand", "Uttar Pradesh", "West Bengal"]))


def rings_for(polygon):
    rings = []
    centre = None
    for ring_index, source in enumerate(polygon):
        ring = []
        previous = None
        for point in source:
            x, y = point[:2]
            assert math.isfinite(x) and math.isfinite(y) and -90 <= y <= 90
            if previous is not None:
                while x - previous > 180:
                    x -= 360
                while x - previous < -180:
                    x += 360
            previous = x
            pair = round(x * SCALE), round(y * SCALE)
            if not ring or pair != ring[-1]:
                ring.append(pair)
        if len(ring) < 4:
            if ring_index == 0:
                return []
            continue
        assert ring[0] == ring[-1], "Input ring is not closed"
        local_centre = (min(p[0] for p in ring) + max(p[0] for p in ring)) / (2 * SCALE)
        if centre is None:
            centre = local_centre
        shift = round((centre - local_centre) / 360) * 360 * SCALE
        rings.append([(x + shift, y) for x, y in ring])
    return rings


def build(source_dir):
    provenance = json.loads((Path(__file__).with_name("sources.json")).read_text(encoding="utf-8"))
    polygons = []
    seen_india = set()
    for source in provenance["sources"]:
        raw = (source_dir / source["filename"]).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == source["sha256"], f"Unexpected input: {source['filename']}"
        for feature in json.loads(raw)["features"]:
            p = feature["properties"]
            kind = source["kind"]
            if kind == 0:
                code = p.get("ISO_A2_EH")
                if not code or len(code) != 2 or code == "AQ":
                    continue  # Antarctica has no single country; unmapped codes stay unknown.
                name = p.get("NAME_EN") or p["ADMIN"]
            elif kind == 1:
                suffix = p["shapeISO"].split("-")[-1]
                suffix = ISO_UPDATES.get(suffix, suffix)
                assert suffix in INDIA_NAMES
                code, name = "IN-" + suffix, INDIA_NAMES[suffix]
                seen_india.add(suffix)
            else:
                code, name = "", "Disputed or indeterminate area"
            geometry = feature.get("geometry")
            if not geometry:
                continue
            assert geometry["type"] in ("Polygon", "MultiPolygon")
            parts = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
            for part in parts:
                rings = rings_for(part)
                if not rings:
                    continue
                outer = rings[0]
                bounds = (min(y for x, y in outer), max(y for x, y in outer),
                          min(x for x, y in outer), max(x for x, y in outer))
                polygons.append((kind, code, name, source["uncertaintyMetres"], bounds, rings))
    assert seen_india == set(INDIA_NAMES), "Incomplete India administrative coverage"
    polygons.sort(key=lambda p: (p[0], p[1], p[4]))
    grid = {}
    for index, (kind, code, name, error, bounds, rings) in enumerate(polygons):
        lo_y, hi_y, lo_x, hi_x = [x / SCALE for x in bounds]
        # Dispute masks also guard nearby points within maximum accepted accuracy.
        pad_x, pad_y = (4, .3) if kind == 2 else (0, 0)
        for y in range(max(0, math.floor((lo_y - pad_y + 90) / 2)), min(89, math.floor((hi_y + pad_y + 90) / 2)) + 1):
            for x in range(math.floor((lo_x - pad_x + 180) / 2), math.floor((hi_x + pad_x + 180) / 2) + 1):
                grid.setdefault(y * 180 + x % 180, set()).add(index)
    output = io.BytesIO()
    output.write(b"LTG1")
    def integer(n): output.write(struct.pack(">i", n))
    def text(s):
        b = s.encode("utf-8")
        output.write(struct.pack(">H", len(b)))
        output.write(b)
    def delta(n):
        n = (n << 1) ^ (n >> 31)
        while n >= 128:
            output.write(bytes([(n & 127) | 128]))
            n >>= 7
        output.write(bytes([n]))
    integer(len(polygons))
    for kind, code, name, error, bounds, rings in polygons:
        output.write(bytes([kind])); text(code); text(name); integer(error)
        for b in bounds: integer(b)
        integer(len(rings))
        for ring in rings:
            integer(len(ring))
            x0 = y0 = 0
            for x, y in ring:
                delta(x - x0); delta(y - y0)
                x0, y0 = x, y
    integer(len(grid))
    for key, ids in sorted(grid.items()):
        integer(key); integer(len(ids))
        for index in sorted(ids): integer(index)
    compressed = bytearray(gzip.compress(output.getvalue(), compresslevel=9, mtime=0))
    compressed[9] = 255  # No platform-specific gzip OS header.
    result = bytes(compressed)
    provenance["generated"] = dict(format="LTG1", quantizationDegrees=1e-6,
        polygons=len(polygons), vertices=sum(sum(len(r) for r in p[5]) for p in polygons),
        countryCodes=sorted({p[1] for p in polygons if p[0] == 0}),
        indiaAdminCodes=["IN-" + code for code in sorted(seen_india)],
        bytes=len(result), sha256=hashlib.sha256(result).hexdigest())
    return result, provenance


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/geography")
    args = parser.parse_args()
    binary, metadata = build(args.source_dir)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "boundaries.ltgz").write_bytes(binary)
    (args.output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata["generated"], indent=2))
