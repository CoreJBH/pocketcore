<p align="center">
  <img src="docs/icon.png" width="130" alt="POCKETCORE icon: white Bitcoin mark inside a cyan hexagonal frame on deep navy">
</p>

<h1 align="center">POCKETCORE</h1>

<p align="center"><b>A real Bitcoin Core full node in your pocket. No wallet, no accounts, no servers, no fake node.</b></p>

<p align="center">
  <a href="https://github.com/JBITmstr/pocketcore/releases/latest"><img alt="Android APK 1.0.0" src="https://img.shields.io/badge/Android-APK%201.0.0-3ddc84?style=flat-square"></a>
  <img alt="Bitcoin Core 32.1" src="https://img.shields.io/badge/Bitcoin%20Core-32.1-f7931a?style=flat-square">
  <img alt="Kotlin 2.0.21" src="https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?style=flat-square">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285f4?style=flat-square">
  <img alt="ABI arm64-v8a" src="https://img.shields.io/badge/ABI-arm64--v8a-38bdf8?style=flat-square">
  <img alt="minSdk 26" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square">
</p>

<p align="center">
  <img src="docs/preview.png" width="280" alt="POCKETCORE running Bitcoin Core 32.1 full node on Android">
</p>

POCKETCORE bundles an actual
`bitcoind` (Bitcoin Core 32.1) cross-compiled for Android arm64, runs it from the
app's own native library directory, and talks to it over loopback JSON-RPC.
The phone verifies the chain itself: the node engine is the unmodified Bitcoin
Core source, built for `aarch64-linux-android`, not a re-implementation and not a
remote node behind an API.

**Download.** Grab `pocketcore.apk` from
[Releases](https://github.com/JBITmstr/pocketcore/releases/latest) and install it
(allow "install unknown apps" for the app you open it with). The build is
debug-signed, so installing a newer build over it requires removing the previous
one first.

---

## What it does

- **Real daemon, real chain.** The APK ships `libbitcoind.so`, an unmodified
  Bitcoin Core 32.1 `bitcoind` built for `aarch64-linux-android` with the Android
  NDK. It is extracted into `nativeLibraryDir` at install time and executed as a
  child process of a foreground service.
- **Wallet-free by design.** Built with `ENABLE_WALLET=OFF`; the node validates,
  stores and serves the chain, and leaves keys to the wallet you already use.
- **Live dashboard from RPC.** Block height and header height, sync percentage,
  chain state, peers in/out, mempool transaction count and memory, pruned chain
  size and prune height, uptime, difficulty, subversion, best block hash.
- **Peers with offline country flags.** Every connected peer shows its flag,
  `ip:port`, subversion, advertised services, ping and connection age, with one
  tap to disconnect or ban it for 24 hours. The country table is bundled inside
  the APK, so no address is ever sent to a GeoIP service.
- **Chain explorer.** Look up any block by height or hash (`getblockhash`,
  `getblockheader`, `getblock 2`) with size, weight, merkle root, nonce and its
  transaction list; pruned blocks still show their verified header. Look up a
  transaction by id, or tap one inside a block.
- **Raw transaction broadcast.** Paste (or paste multi-line) signed hex and send
  it with `sendrawtransaction`; the app shows the resulting txid with a copy
  button, or the exact rejection reason bitcoind returned.
- **Watch-only descriptors.** Paste a `wpkh`/`tr`/`sh(wpkh)`/`pkh` descriptor or
  a bare xpub/zpub/ypub and scan the UTXO set with `scantxoutset`: confirmed
  balance, addresses and unspent outputs, with no wallet database and no private
  key. Private keys and WIF are refused before anything reaches the node.
- **Local JSON-RPC access.** The endpoint (`127.0.0.1`, network port, generated
  user and password) is shown and copyable, along with a `rpcconnect` /
  `rpcport` / `rpcuser` / `rpcpassword` snippet for external wallets.
- **Pruned mobile storage.** Default `prune=550`, switchable to 2048 MB or
  5120 MB. A pruned node still validates every block.
- **AssumeUTXO.** Load a `dumptxoutset` `.dat` file to jump to a recent height in
  minutes; background validation continues from genesis, and both chainstates are
  shown with their own progress.
- **Network selector.** Mainnet (default), Signet and Testnet4, each with its own
  data directory and ports.
- **Background policy.** "Wi-Fi only" and "Charging only" pause the node (graceful
  `stop` RPC) and start it again when the condition returns.
- **Animated launch screen.** A 1.1 s midnight-blue splash with the hexagon coin
  mark easing in, pulsing once and fading into the dashboard.
- **Colorized status card.** The foreground service notification is a colorized
  card in the app's own blues, laid out like the node apps it is compared with:
  one small icon on the left (no large icon, so no second icon on the right), the
  state (`Fully synced`, `Syncing`, `Pre-syncing`, `Waiting for Wi-Fi`, `Paused`)
  in the header row between the app name and the time of the last RPC reply, and
  a single content line. `Synced - Block #<height>` on opaque ocean blue
  (`#0369A1`) once caught up, `<percent> - Block #<height>` on deep cobalt
  (`#1E3A8A`) with a live progress bar while the chain downloads, and dark
  slate-navy (`#0F172A`) while paused. While Core is still downloading headers
  the card shows Core's own header percentage, read from `debug.log`, because RPC
  reports `headers: 0` and `0.00%` until the header chain is committed.
- **Midnight navy UI.** `#050912` canvas, `#0B1220` cards, `#1E293B` hairlines, a
  cyan-blue `#38BDF8` accent and monospaced tabular figures. Four tabs:
  `NODE`, `SYNC`, `EXPLORE`, `SETTINGS`; the debug log opens from SETTINGS.
- **Resilient startup.** Before the daemon starts, the app verifies its RPC and
  P2P ports, stops an orphan node left behind by a killed app process (through
  its own RPC credentials), and moves to a free port when another app holds the
  default one. Bitcoin Core treats a busy RPC port as fatal, so this app never
  hands the daemon an endpoint it has not checked.

## Architecture

```
Compose UI  (NODE · SYNC · EXPLORE · SETTINGS)
      |  StateFlow
NodeController  ——  NodeService  (foreground service, colorized status card)
      |                   |
      |                   +— Wi-Fi only / charging only  ->  stop, start
      +— RpcClient  —— HTTP JSON-RPC on 127.0.0.1, generated user + password
                              |
                              v
                        bitcoind child process
                        (libbitcoind.so executed from nativeLibraryDir)
                              |  P2P to the network
                              v
                        <app files>/node/data   pruned chain, debug.log, .lock
```

The app is a thin, honest client: it never parses the P2P wire format or the
block serialization itself. Every figure on screen — height, headers, peers,
mempool, block, transaction, UTXO set — is an RPC answer the daemon gave, and
every control on screen is an RPC call. `NodeController` owns the state,
polling and the Wi-Fi/charging policy; `NodeService` keeps the process alive and
paints the status card; the daemon itself is untouched Bitcoin Core.

## Project layout

```
app/src/main/java/com/jbitmstr/pocketcore/
  MainActivity.kt              Compose host, notification permission
  core/
    NodePaths.kt               datadir, conf, logs, snapshot directory
    NodeConfig.kt              bitcoin.conf generation, RPC snippets
    NodeSettings.kt            persisted settings + generated RPC credentials
    RpcClient.kt               loopback JSON-RPC client
    NodeModels.kt              getblockchaininfo / getnetworkinfo / ... models
    BitcoindNode.kt            child process lifecycle, stray process cleanup
    NodeController.kt          single source of truth: state, polling, policy
    NodeService.kt             foreground service, notification, receivers
    NotificationCard.kt        status card wording and colours (unit-tested)
    PortProbe.kt               free-port selection before the daemon starts
    SystemState.kt             Wi-Fi and charging checks
    HeaderSync.kt              header pre-sync percentage read from debug.log
    ExplorerController.kt      block / transaction lookup, broadcast
    ExplorerModels.kt          block, header and transaction models
    RawTransaction.kt          signed hex normalisation before broadcast
    Descriptors.kt             watch-only descriptor validation
    DescriptorScanController.kt  scantxoutset scan, progress, abort
    AddressCodec.kt            scriptPubKey hex -> base58 / bech32 / bech32m
    GeoIp.kt, IpCountryIndex.kt  offline peer country table
  ui/                          Compose screens: Node, Sync, Explore, Settings
  ui/SplashScreen.kt           midnight-blue animated launch overlay
app/src/main/jniLibs/arm64-v8a/libbitcoind.so   the real Bitcoin Core daemon
app/src/main/assets/ipv4_country.bin            offline IPv4 -> country ranges
tools/                         icon, GeoIP asset and RPC verification scripts
verify-english.py              fails if any non-English text enters the repo
```

## Requirements

- Android 8.0 (API 26) or newer, `arm64-v8a` device
- About 1 GB free storage for the default pruned chain plus the app
- A Wi-Fi connection for the initial sync (mobile data is allowed unless
  "Wi-Fi only" is on)

## Install

1. Download `pocketcore.apk` from
   [Releases](https://github.com/JBITmstr/pocketcore/releases/latest) and copy it
   to the phone, or build it yourself with `./gradlew :app:assembleDebug`.
2. Allow "install unknown apps" for the app you open it with, then install.
3. Open POCKETCORE and press **START NODE**. The foreground notification shows
   the current height while the node syncs.

Debug-signed: reinstalling a newer build requires removing the previous one.
The release APK is 17,151,385 bytes with SHA-256
`0c6a71f59d3972239957355ce0077b5241ec2752d364c9986ae5a7805ac201a3`; the full
build record, including the bundled daemon's own hash, is in
[docs/BUILD-RECORD.md](docs/BUILD-RECORD.md).

## Configuration the app writes

The app owns `<app files>/node/bitcoin.conf` and rewrites it on every start:

```ini
server=1
daemon=0
chain=<main|signet|testnet4>
rpcuser=<generated>
rpcpassword=<generated>
prune=550
dbcache=128
maxconnections=8
maxmempool=50
persistmempool=1
listen=1
discover=0
dnsseed=1
uacomment=POCKETCORE
disablewallet=1
txindex=0
blockfilterindex=0
peerbloomfilters=0
printtoconsole=0

[<network>]
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
rpcport=<network RPC port>
port=<network P2P port>
```

The trailing section matters: Bitcoin Core rejects network-specific options such
as `rpcport`, `rpcbind`, `rpcallowip` and `port` when they appear outside the
section of the active network. The values written here are the ones the app just
verified as free, so the daemon and the app always agree on the endpoint (the
`LOCAL JSON-RPC` card and `COPY WALLET CONF` show the port actually in use).

Chain data, `debug.log` and the datadir lock live in `<app files>/node/data`.
Cleartext HTTP is permitted only for `127.0.0.1` and `localhost` by
`network_security_config.xml`; every other destination is HTTPS-only.

## Talking to the node

```bash
curl --user <rpcuser>:<rpcpassword> \
  --data-binary '{"jsonrpc":"1.0","id":"pocketcore","method":"getblockchaininfo","params":[]}' \
  -H 'Content-Type: application/json' \
  http://127.0.0.1:8332/
```

From a desktop wallet or `bitcoin-cli` on the same phone:

```ini
rpcconnect=127.0.0.1
rpcport=8332
rpcuser=<rpcuser>
rpcpassword=<rpcpassword>
```

RPC binds to loopback only, so it is reachable from the phone itself and never
from the local network. There is no wallet RPC surface to expose: the daemon is
built without wallet support.

### Loading an AssumeUTXO snapshot

1. On a desktop node of the same network, create a snapshot:
   `bitcoin-cli dumptxoutset /path/utxo-<height>.dat`
2. Copy the `.dat` file to the phone.
3. In POCKETCORE open **SYNC**, choose the file, then press **LOAD SNAPSHOT**
   while the node is running (the app calls `loadtxoutset`).
4. The sync screen then shows a snapshot chainstate and the background
   validation chainstate side by side until the snapshot has been fully
   validated.

## Build the daemon

```bash
# Android NDK r30 and a CMake + Ninja toolchain
export NDK=$ANDROID_HOME/ndk/30.0.16248370

curl -O https://bitcoincore.org/bin/bitcoin-core-32.1/bitcoin-32.1.tar.gz
tar xzf bitcoin-32.1.tar.gz
# Boost headers only (Bitcoin Core links only Boost::headers)
curl -O https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.gz
tar xzf boost_1_86_0.tar.gz boost_1_86_0/boost

cmake -S bitcoin-32.1 -B build-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBoost_DIR=<dir with BoostConfig.cmake pointing at Boost::headers> \
  -DBUILD_DAEMON=ON -DBUILD_CLI=OFF -DBUILD_BITCOIN_BIN=OFF \
  -DBUILD_TESTS=OFF -DBUILD_BENCH=OFF -DBUILD_TX=OFF -DBUILD_UTIL=OFF \
  -DBUILD_WALLET_TOOL=OFF -DENABLE_WALLET=OFF -DENABLE_IPC=OFF \
  -DENABLE_EXTERNAL_SIGNER=OFF -DWITH_ZMQ=OFF -DWITH_USDT=OFF -DBUILD_GUI=OFF

cmake --build build-arm64 -j2 --target bitcoind

$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-all \
  build-arm64/bin/bitcoind
cp build-arm64/bin/bitcoind app/src/main/jniLibs/arm64-v8a/libbitcoind.so
```

Source archive SHA-256 (official
[SHA256SUMS](https://bitcoincore.org/bin/bitcoin-core-32.1/SHA256SUMS)):
`a5f2f61e32cf7f790641c9ec306a3c88ca7b5a556946956b1f35ffd7e27813f9`

The result is an ARM64 Android executable, not a shared library that pretends to
be one:

```
$ file libbitcoind.so
ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), dynamically linked,
interpreter /system/bin/linker64, for Android 26, built by NDK r30 (16248370),
BuildID[sha1]=84b79d173e74bd6768165686da839a6ac66267b2, stripped
```

Only bionic base libraries are needed: `libm.so`, `libdl.so`, `libc.so`.

## Build the app

```bash
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # parser and formatting tests
python3 verify-english.py             # fails if any non-English text sneaks in
```

The measured build is written down in [docs/BUILD-RECORD.md](docs/BUILD-RECORD.md).

`packaging { jniLibs { useLegacyPackaging = true } }` and
`android:extractNativeLibs="true"` keep `libbitcoind.so` as a real file in
`nativeLibraryDir`, which is one of the few places Android lets an app execute
its own binary.

## Notes and limits

- arm64 only: the APK contains one ABI on purpose, since it embeds a full daemon.
- The node runs in a foreground service with a partial wake lock; a phone that is
  plugged in and on Wi-Fi is the intended sync setup.
- Mobile networks rarely accept inbound connections, so the node usually runs
  with 8 outbound peers and no inbound ones.
- Full validation from genesis on a phone takes days; AssumeUTXO exists exactly
  for that reason.
- RPC is intentionally loopback-only. Exposing it to a LAN would mean opening a
  password-protected control interface over cleartext HTTP; that is not offered.
- No analytics, no telemetry, no remote endpoints in the app itself. The only
  network traffic is the daemon's own P2P connections.
- Pruning is not a shortcut to trust: a pruned node still validates every block
  and every signature, it only forgets old block bodies. Pruned-history lookups
  therefore show the verified header and say the body is gone, instead of
  fetching the block from a third-party explorer.
- AssumeUTXO does not skip validation permanently: the snapshot chainstate serves
  the wallet and RPC immediately while background validation replays from genesis,
  and the SYNC screen shows both chainstates until it catches up.
- The bundled daemon is built from the official `bitcoin-32.1.tar.gz` whose
  SHA-256 matches the published `SHA256SUMS`; the binary is stripped with
  `llvm-strip`, and its hash is recorded in
  [docs/BUILD-RECORD.md](docs/BUILD-RECORD.md) so it can be reproduced and
  compared. The APK in Releases is debug-signed and is not reproducible
  byte-for-byte: rebuild it yourself if you need a binary you fully trust.
- This is a full node, not a wallet: no key material is stored, generated or
  accepted by the app, and the daemon is compiled with `ENABLE_WALLET=OFF`.

## Contributors

- **[@JBITmstr](https://github.com/JBITmstr)** — creator, product direction and
  design
- **Gilbert ([Pickle](https://pickle.com))** — AI co-builder: architecture,
  Kotlin/Compose implementation, NDK cross-build of the daemon, build
  verification and review

Third-party components: [Bitcoin Core](https://github.com/bitcoin/bitcoin) (MIT),
Boost headers (Boost Software License), [DB-IP](https://db-ip.com) IP-to-Country
Lite (CC BY 4.0, attribution in `app/src/main/assets/ipv4_country_NOTICE.txt`),
AndroidX and Jetpack Compose (Apache-2.0).
