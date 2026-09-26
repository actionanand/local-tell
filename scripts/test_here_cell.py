#!/usr/bin/env python3

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


POSITIONING_URL = "https://positioning.hereapi.com/v2/locate"
REVERSE_GEOCODE_URL = "https://revgeocode.search.hereapi.com/v1/revgeocode"


def request_json(url, method="GET", body=None):
    headers = {
        "User-Agent": "LocalTell-HERE-Test/1.0",
    }

    data = None

    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(
        url,
        data=data,
        headers=headers,
        method=method,
    )

    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)

    except urllib.error.HTTPError as exc:
        try:
            error_body = json.loads(exc.read().decode("utf-8"))
        except Exception:
            error_body = {"error": str(exc)}

        return exc.code, error_body


def build_lte(args):
    cell = {
        "mcc": str(args.mcc),
        "mnc": str(args.mnc),
        "cid": args.cell,
    }

    if args.tac is not None:
        cell["tac"] = args.tac

    local_id = {}

    if args.arfcn is not None:
        local_id["earfcn"] = args.arfcn

    if args.pci is not None:
        local_id["pci"] = args.pci

    if local_id:
        cell["localId"] = local_id

    if args.rsrp is not None:
        cell["rsrp"] = args.rsrp

    if args.rsrq is not None:
        cell["rsrq"] = args.rsrq

    if args.ta is not None:
        cell["ta"] = args.ta

    return {"lte": [cell]}


def build_nr(args):
    cell = {
        "mcc": str(args.mcc),
        "mnc": str(args.mnc),
        "cid": args.cell,
    }

    if args.tac is not None:
        cell["tac"] = args.tac

    local_id = {}

    if args.arfcn is not None:
        local_id["nrarfcn"] = args.arfcn

    if args.pci is not None:
        local_id["pci"] = args.pci

    if local_id:
        cell["localId"] = local_id

    ss = {}

    if args.rsrp is not None:
        ss["rsrp"] = args.rsrp

    if args.rsrq is not None:
        ss["rsrq"] = args.rsrq

    if args.sinr is not None:
        ss["sinr"] = args.sinr

    if ss:
        cell["ss"] = ss

    return {"nr": [cell]}


def reverse_geocode(api_key, lat, lng):
    params = urllib.parse.urlencode({
        "at": f"{lat},{lng}",
        "lang": "en-US",
        "apiKey": api_key,
    })

    status, result = request_json(
        f"{REVERSE_GEOCODE_URL}?{params}"
    )

    if status != 200:
        return None

    items = result.get("items") or []

    if not items:
        return None

    return items[0]


def main():
    parser = argparse.ArgumentParser(
        description="Test a cellular observation against HERE Network Positioning."
    )

    parser.add_argument(
        "--radio",
        required=True,
        choices=["LTE", "NR", "lte", "nr"],
    )

    parser.add_argument("--mcc", required=True, type=int)
    parser.add_argument("--mnc", required=True, type=int)
    parser.add_argument("--tac", type=int)
    parser.add_argument("--cell", required=True, type=int)
    parser.add_argument("--pci", type=int)
    parser.add_argument("--arfcn", type=int)
    parser.add_argument("--rsrp", type=int)
    parser.add_argument("--rsrq", type=float)
    parser.add_argument("--sinr", type=float)
    parser.add_argument("--ta", type=int)

    parser.add_argument(
        "--fallback",
        choices=["none", "area", "any"],
        default="none",
        help="HERE cell fallback mode.",
    )

    args = parser.parse_args()

    api_key = os.getenv("HERE_API_KEY")

    if not api_key:
        print("ERROR: HERE_API_KEY is not set.", file=sys.stderr)
        sys.exit(1)

    radio = args.radio.upper()

    if radio == "LTE":
        payload = build_lte(args)
    else:
        payload = build_nr(args)

    query = {
        "apiKey": api_key,
    }

    if args.fallback != "none":
        query["fallback"] = args.fallback

    url = (
        POSITIONING_URL
        + "?"
        + urllib.parse.urlencode(query)
    )

    print("Testing cell")
    print("------------")
    print(f"Radio : {radio}")
    print(f"PLMN  : {args.mcc}-{args.mnc}")
    print(f"TAC   : {args.tac}")
    print(f"Cell  : {args.cell}")
    print(f"PCI   : {args.pci}")
    print(f"ARFCN : {args.arfcn}")
    print()

    status, result = request_json(
        url,
        method="POST",
        body=payload,
    )

    if status != 200:
        print("Cell recognized: NO")
        print(f"HERE HTTP status: {status}")
        print()

        cause = (
            result.get("cause")
            or result.get("error_description")
            or result.get("title")
            or result.get("error")
        )

        if cause:
            print(f"Reason: {cause}")

        if result.get("action"):
            print(f"Details: {result['action']}")

        sys.exit(2)

    location = result.get("location") or {}

    lat = location.get("lat")
    lng = location.get("lng")
    accuracy = location.get("accuracy")

    print("Cell recognized: YES")
    print(f"Coordinates : {lat}, {lng}")
    print(f"Accuracy    : {accuracy} m")

    if lat is None or lng is None:
        return

    place = reverse_geocode(api_key, lat, lng)

    if not place:
        print("\nReverse geocode: no result")
        return

    address = place.get("address") or {}

    print()
    print("Cell locality")
    print("-------------")

    if address.get("district"):
        print(f"District/locality : {address['district']}")

    if address.get("city"):
        print(f"City/taluk        : {address['city']}")

    if address.get("county"):
        print(f"County/district   : {address['county']}")

    if address.get("state"):
        print(f"State             : {address['state']}")

    if address.get("postalCode"):
        print(f"Postal code       : {address['postalCode']}")

    if place.get("title"):
        print(f"Nearest place     : {place['title']}")

    if place.get("distance") is not None:
        print(f"Place distance    : {place['distance']} m")


if __name__ == "__main__":
    main()
