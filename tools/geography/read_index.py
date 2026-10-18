"""Independent host reader/query of the generated asset. Mirrors runtime ring rules.

This supplements JVM tests of BoundaryIndex; it does not execute Android/Kotlin.
"""
import gzip
import io
import math
import struct


class Index:
    def __init__(self, path):
        data = io.BytesIO(gzip.decompress(path.read_bytes()))
        def integer(): return struct.unpack(">i", data.read(4))[0]
        def text(): return data.read(struct.unpack(">H", data.read(2))[0]).decode("utf-8")
        def delta():
            value = shift = 0
            while True:
                b = data.read(1)[0]
                value |= (b & 127) << shift
                if not b & 128: return (value >> 1) ^ -(value & 1)
                shift += 7
        assert data.read(4) == b"LTG1"
        self.polygons = []
        for _ in range(integer()):
            kind = data.read(1)[0]
            code, name, error = text(), text(), integer()
            bounds = tuple(integer() / 1e6 for _ in range(4))
            rings = []
            for _ in range(integer()):
                x = y = 0
                ring = []
                for _ in range(integer()):
                    x += delta(); y += delta()
                    ring.append((x / 1e6, y / 1e6))
                rings.append(ring)
            self.polygons.append((kind, code, name, error, bounds, rings))
        self.grid = {}
        for _ in range(integer()):
            key, count = integer(), integer()
            self.grid[key] = [integer() for _ in range(count)]
        assert not data.read()

    @staticmethod
    def longitude(polygon, x):
        bounds = polygon[4]
        return x + round(((bounds[2] + bounds[3]) / 2 - x) / 360) * 360

    @classmethod
    def contains(cls, polygon, lat, lng):
        x = cls.longitude(polygon, lng)
        lo_y, hi_y, lo_x, hi_x = polygon[4]
        if not lo_y <= lat <= hi_y or not lo_x <= x <= hi_x: return False
        def inside(ring):
            result = False
            ax, ay = ring[-1]
            for bx, by in ring:
                if (ay > lat) != (by > lat) and x < (bx - ax) * (lat - ay) / (by - ay) + ax:
                    result = not result
                ax, ay = bx, by
            return result
        return inside(polygon[5][0]) and not any(inside(r) for r in polygon[5][1:])

    @classmethod
    def near(cls, p, lat, lng, radius):
        x = cls.longitude(p, lng)
        dy = (radius + 2) / 110000
        dx = dy / max(.08, math.cos(math.radians(lat)))
        for ring in p[5]:
            ax, ay = ring[-1]
            for bx, by in ring:
                if (abs(ax - bx) >= 1e-8 or abs((ax + 180) % 360) >= 1e-8) and (
                    min(ay, by) - dy <= lat <= max(ay, by) + dy and min(ax, bx) - dx <= x <= max(ax, bx) + dx
                ) and segment_distance(lat, x, ay, ax, by, bx) <= radius + 2:
                    return True
                ax, ay = bx, by
        return False

    def identify(self, lat, lng, accuracy=20):
        if not -85 <= lat <= 85 or not -180 <= lng <= 180 or accuracy is None or not 0 <= accuracy <= 25000:
            return None
        if lng == 180: lng = -180
        candidates = [self.polygons[i] for i in self.grid.get(math.floor((lat + 90) / 2) * 180 + math.floor((lng + 180) / 2), [])]
        if any(p[0] == 2 and (self.contains(p, lat, lng) or self.near(p, lat, lng, accuracy + p[3])) for p in candidates):
            return None
        countries = [p for p in candidates if p[0] == 0 and self.contains(p, lat, lng)]
        states = [p for p in candidates if p[0] == 1 and self.contains(p, lat, lng)]
        state = states[0] if len(states) == 1 and not self.near(states[0], lat, lng, accuracy + states[0][3]) else None
        codes = {p[1] for p in countries}
        admin_country = state[1].split("-")[0] if state else None
        if len(codes) > 1 or (state and codes - {admin_country}): return None
        if state: return admin_country, state[1]
        country = next((p for p in countries if not self.near(p, lat, lng, accuracy + p[3])), None)
        return (country[1], None) if country else None


def segment_distance(lat, lon, ay, ax, by, bx):
    def angle(a, b, c, d):
        h = math.sin(math.radians(c-a)/2)**2 + math.cos(math.radians(a))*math.cos(math.radians(c))*math.sin(math.radians(d-b)/2)**2
        return 2 * math.asin(math.sqrt(min(1, max(0, h))))
    def bearing(a, b, c, d):
        p, q, x = math.radians(a), math.radians(c), math.radians(d-b)
        return math.atan2(math.sin(x)*math.cos(q), math.cos(p)*math.sin(q)-math.sin(p)*math.cos(q)*math.cos(x))
    length, to_point = angle(ay, ax, by, bx), angle(ay, ax, lat, lon)
    if length < 1e-12: return to_point * 6371000
    diff = bearing(ay, ax, lat, lon)-bearing(ay, ax, by, bx)
    along = math.atan2(math.sin(to_point)*math.cos(diff), math.cos(to_point))
    return 6371000 * (min(to_point, angle(by,bx,lat,lon)) if along < 0 or along > length else abs(math.asin(max(-1,min(1,math.sin(to_point)*math.sin(diff))))))
