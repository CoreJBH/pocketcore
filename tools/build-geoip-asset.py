#!/usr/bin/env python3
"""Build the offline IPv4 country asset used by the peer list.

Input : a DB-IP "IP to Country Lite" CSV (start_ip,end_ip,CC), one range per line.
Output: app/src/main/assets/ipv4_country.bin

The app never calls an external GeoIP service. The whole IPv4 space is stored as
one contiguous, sorted list of ranges so a lookup is an exact binary search.

File layout, before gzip:

    "PCG3"                      4 bytes  magic
    country count               uint16   little endian
    "AU" "CN" ...               2 bytes per country; index n means position n-1
    ranges                      varint(start delta) + uint8(country index)

Index 0 means "unknown", so unallocated space is just another range. A range
ends where the next one starts (the last one ends at 255.255.255.255).

Free IP to Country Lite data by DB-IP (https://db-ip.com), CC BY 4.0.

Usage:
    python3 tools/build-geoip-asset.py dbip-country-ipv4.csv
"""

from __future__ import annotations

import gzip
import ipaddress
import struct
import sys
from pathlib import Path

LAST_ADDRESS = 0xFFFFFFFF


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        chunk = value & 0x7F
        value >>= 7
        if value:
            out.append(chunk | 0x80)
        else:
            out.append(chunk)
            return bytes(out)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    source = Path(sys.argv[1])
    target = Path(__file__).resolve().parent.parent / "app/src/main/assets/ipv4_country.bin"

    ranges: list[tuple[int, int, str]] = []
    with source.open("r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            parts = line.split(",")
            if len(parts) != 3:
                raise SystemExit(f"{source}:{number}: unexpected line {line!r}")
            start_raw, end_raw, country = parts
            start = int(ipaddress.IPv4Address(start_raw))
            end = int(ipaddress.IPv4Address(end_raw))
            if end < start:
                raise SystemExit(f"{source}:{number}: range reversed")
            if len(country) != 2:
                continue
            ranges.append((start, end, country))
    ranges.sort()

    # One contiguous partition of 0.0.0.0 - 255.255.255.255, gaps marked unknown.
    partition: list[tuple[int, str]] = []
    cursor = 0
    for start, end, country in ranges:
        if start < cursor:
            # Overlapping rows in the source: keep the first allocation.
            start = cursor
            if end < start:
                continue
        if start > cursor:
            partition.append((cursor, ""))
        partition.append((start, country))
        cursor = end + 1
    if cursor <= LAST_ADDRESS:
        partition.append((cursor, ""))

    countries = sorted({code for _, code in partition if code})
    index_of = {code: position + 1 for position, code in enumerate(countries)}

    payload = bytearray()
    payload += b"PCG3"
    payload += struct.pack("<H", len(countries))
    for code in countries:
        payload += code.encode("ascii")
    previous = 0
    for start, code in partition:
        payload += varint(start - previous)
        payload.append(index_of.get(code, 0))
        previous = start

    # Written uncompressed: the APK deflates assets itself, and aapt2 would
    # unpack a .gz file back to this exact payload anyway. Keeping one format
    # removes a whole class of "asset missing at runtime" failures.
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(bytes(payload))
    compressed = gzip.compress(bytes(payload), 9)

    starts = [start for start, _ in partition]

    def lookup(address: str) -> str:
        value = int(ipaddress.IPv4Address(address))
        low, high = 0, len(starts) - 1
        while low < high:
            middle = (low + high + 1) // 2
            if starts[middle] <= value:
                low = middle
            else:
                high = middle - 1
        code = partition[low][1]
        return code or "--"

    print(f"countries: {len(countries)}")
    print(f"ranges: {len(partition)}")
    print(f"payload: {len(payload)} bytes (about {len(compressed)} bytes inside the APK)")
    print(f"written: {target}")
    for probe in (
        "47.158.166.202",
        "172.81.132.46",
        "38.102.125.74",
        "74.206.131.251",
        "87.62.101.114",
        "91.98.226.36",
        "24.193.1.236",
        "1.1.1.1",
        "8.8.8.8",
        "121.130.0.1",
        "2a00::1",
    ):
        if ":" in probe:
            continue
        print(f"  {probe:16} -> {lookup(probe)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
