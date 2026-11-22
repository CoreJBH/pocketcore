#!/usr/bin/env python3
"""Exercises the exact RPCs POCKETCORE issues against a real Bitcoin Core 32.1.

Runs on regtest with a wallet so transactions, descriptors and raw broadcasts
can be produced, and prints the JSON shapes the Kotlin parsers consume.
"""

from __future__ import annotations

import json
import pathlib
import shutil
import subprocess
import sys
import time

BIN = pathlib.Path("/workspace/tmp/core/bitcoin-32.1/bin")
DIR = pathlib.Path("/workspace/tmp/pcrpc")
XPUB = (
    "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjq"
    "JoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
)


def cli(*args: str, check: bool = True):
    result = subprocess.run(
        [str(BIN / "bitcoin-cli"), f"-datadir={DIR}", *args],
        capture_output=True,
        text=True,
    )
    if check and result.returncode != 0:
        raise SystemExit(f"bitcoin-cli {' '.join(args)} failed: {result.stderr.strip()}")
    return result


def as_json(*args: str):
    return json.loads(cli(*args).stdout)


def main() -> int:
    shutil.rmtree(DIR, ignore_errors=True)
    DIR.mkdir(parents=True)
    (DIR / "bitcoin.conf").write_text(
        "regtest=1\nserver=1\nrpcuser=pocketcore\nrpcpassword=pocketcore\n"
        "fallbackfee=0.0002\ntxindex=0\n"
    )
    subprocess.run([str(BIN / "bitcoind"), f"-datadir={DIR}", "-daemon"], check=True,
                   capture_output=True)
    time.sleep(4)

    cli("createwallet", "test")
    address = cli("getnewaddress").stdout.strip()
    cli("generatetoaddress", "101", address)

    height = int(cli("getblockcount").stdout.strip())
    block_hash = cli("getblockhash", str(height)).stdout.strip()
    print("== getblockhash:", block_hash)

    header = as_json("getblockheader", block_hash, "true")
    print("== getblockheader keys:", sorted(header))

    block = as_json("getblock", block_hash, "2")
    print("== getblock 2 keys:", sorted(block))
    print("   tx count:", len(block["tx"]), " tx keys:", sorted(block["tx"][0]))

    txid = cli("sendtoaddress", address, "0.5").stdout.strip()
    cli("generatetoaddress", "1", address)
    containing = cli("getblockhash", cli("getblockcount").stdout.strip()).stdout.strip()

    tx = as_json("getrawtransaction", txid, "true", containing)
    print("== getrawtransaction keys:", sorted(tx))
    print("   vin[0]:", json.dumps(tx["vin"][0])[:200])
    print("   vout[0]:", json.dumps(tx["vout"][0])[:260])

    missing = cli("getrawtransaction", txid, "true", check=False)
    print("== getrawtransaction without block hash ->", missing.returncode,
          missing.stderr.strip().splitlines()[:1])

    unknown = cli("getrawtransaction", "0" * 63 + "1", "true", check=False)
    print("== getrawtransaction unknown ->", unknown.stderr.strip().splitlines()[:1])

    unspent = as_json("listunspent", "1")[0]
    raw = cli(
        "createrawtransaction",
        json.dumps([{"txid": unspent["txid"], "vout": unspent["vout"]}]),
        json.dumps({address: round(unspent["amount"] - 0.01, 8)}),
    ).stdout.strip()
    signed = as_json("signrawtransactionwithwallet", raw)["hex"]
    sent = cli("sendrawtransaction", signed).stdout.strip()
    print("== sendrawtransaction ->", sent)

    broken = cli("sendrawtransaction", "02000000ff", check=False)
    print("== sendrawtransaction broken ->", broken.stderr.strip().splitlines()[:2])

    import re

    account = next(
        d["desc"] for d in as_json("listdescriptors")["descriptors"]
        if d["desc"].startswith("wpkh(") and "tpub" in d["desc"]
    )
    tpub = re.search(r"(tpub[1-9A-HJ-NP-Za-km-z]+)", account).group(1)
    print("== wallet account key:", account)
    wrapped = as_json("getdescriptorinfo", f"wpkh({tpub}/0/*)")
    print("== getdescriptorinfo wrapped tpub:", json.dumps(wrapped))
    bad_key = cli("getdescriptorinfo", f"wpkh({XPUB})", check=False)
    print("== getdescriptorinfo master key (expected invalid):", bad_key.stderr.strip().splitlines()[-1][:120])
    private = cli("getdescriptorinfo", "wpkh(cVpZ2xNBqLw8VHRYcqQ8f3Z5YBGGqjXoYYpE6uNsyXkBTRbXfDSJ/0/*)", check=False)
    print("== getdescriptorinfo private (expected error):", private.stderr.strip().splitlines()[-1][:160])

    address_desc = as_json("getaddressinfo", address)["desc"]
    print("== address descriptor:", address_desc)
    scan = as_json("scantxoutset", "start", json.dumps([{"desc": address_desc}]))
    print("== scantxoutset address:", json.dumps(scan)[:300])

    wallet_desc = next(
        d["desc"] for d in as_json("listdescriptors")["descriptors"] if d["desc"].startswith("wpkh(")
    )
    print("== wallet descriptor:", wallet_desc)
    scan_range = as_json(
        "scantxoutset", "start", json.dumps([{"desc": wallet_desc, "range": [0, 20]}])
    )
    print("== scantxoutset ranged: txouts", scan_range["txouts"], "total", scan_range["total_amount"],
          "utxos", len(scan_range["unspents"]))
    if scan_range["unspents"]:
        print("   unspent[0] keys:", sorted(scan_range["unspents"][0]))
        print("   unspent[0]:", json.dumps(scan_range["unspents"][0]))
    print("== scantxoutset invalid full:", cli("scantxoutset", "start", json.dumps([{"desc": "wpkh(not-a-descriptor)"}]), check=False).stderr.strip().replace("\n", " ")[:300])
    print("== scantxoutset status:", cli("scantxoutset", "status").stdout.strip()[:120])

    scan_bad = cli("scantxoutset", "start", json.dumps([{"desc": "wpkh(not-a-descriptor)"}]), check=False)
    print("== scantxoutset invalid ->", scan_bad.stderr.strip().splitlines()[:2])

    print("== setban:", cli("setban", "203.0.113.7", "add", "3600", check=False).returncode)
    print("== listbanned:", cli("listbanned").stdout.replace("\n", " ")[:120])
    print("== remove ban:", cli("setban", "203.0.113.7", "remove", check=False).returncode)
    print("== disconnectnode:", cli("disconnectnode", "", "203.0.113.7:8333", check=False).stderr.strip()[:120])

    print("== getpeerinfo:", cli("getpeerinfo").stdout.strip()[:120])

    # dumptxoutset: the app writes the UTXO set a loadtxoutset can consume, and
    # the shapes below are what SnapshotModels.kt parses. The absolute path is
    # what the app passes (its own cache directory), and the type is explicit
    # because Core 32.1 refuses an empty one.
    export_path = DIR / "exported-utxo.dat"
    dump = as_json("dumptxoutset", str(export_path), "latest")
    print("== dumptxoutset latest keys:", sorted(dump))
    print("   ", json.dumps(dump))
    print("   file:", export_path.exists(), export_path.stat().st_size, "bytes")

    no_type = cli("dumptxoutset", str(DIR / "no-type.dat"), check=False)
    print("== dumptxoutset without type ->", no_type.returncode, no_type.stderr.strip().splitlines()[-1][:120])

    rollback = as_json("dumptxoutset", str(DIR / "rolled-back.dat"), "", json.dumps({"rollback": 100}))
    print("== dumptxoutset rollback 100 -> base_height", rollback["base_height"], "coins", rollback["coins_written"])

    for leftover in DIR.glob("*.dat"):
        leftover.unlink(missing_ok=True)

    cli("stop", check=False)
    time.sleep(2)
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
