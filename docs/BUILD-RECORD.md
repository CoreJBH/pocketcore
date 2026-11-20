# Build record

Recorded on 2026-11-20 while producing `pocketcore-1.0.0-debug.apk`.

## Daemon

| Item | Value |
| --- | --- |
| Source | `bitcoin-32.1.tar.gz` from bitcoincore.org |
| Source SHA-256 | `a5f2f61e32cf7f790641c9ec306a3c88ca7b5a556946956b1f35ffd7e27813f9` (matches the official `SHA256SUMS`) |
| Version reported by the binary | Bitcoin Core daemon v32.1.0 |
| NDK | r30 (`30.0.16248370`), clang 21.0.0, target `aarch64-none-linux-android26` |
| Boost | 1.86.0, headers only (`Boost::headers`); Bitcoin Core 32.1 needs no compiled Boost library and no libevent |
| Build type | Release, `-O2 -g0`, static libc++, single ABI `arm64-v8a` |
| Disabled | wallet, GUI, IPC, external signer, ZMQ, USDT, tests, bench, tx, util, bitcoin-wallet |
| Binary size | 10,729,904 bytes after `llvm-strip --strip-all` |
| Binary SHA-256 | `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` |
| Dynamic dependencies | `libm.so`, `libdl.so`, `libc.so` only |
| ELF | PIE executable, interpreter `/system/bin/linker64`, `LOAD` segments aligned `0x4000` (16 KB page compatible) |

`file` output:

```
ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), dynamically linked,
interpreter /system/bin/linker64, for Android 26, built by NDK r30 (16248370),
BuildID[sha1]=84b79d173e74bd6768165686da839a6ac66267b2, stripped
```

## App

| Item | Value |
| --- | --- |
| Package | `com.jbitmstr.pocketcore` |
| Version | 1.0.0 (versionCode 5) |
| minSdk / targetSdk | 26 / 35 |
| APK | `pocketcore-1.0.0-debug.apk` |
| APK size | 17,151,385 bytes |
| APK SHA-256 | `0c6a71f59d3972239957355ce0077b5241ec2752d364c9986ae5a7805ac201a3` |
| Signing | debug key `f5074815...2aa9c011`, APK Signature Scheme v2 verified, the same key as versionCode 2, 3 and 4 (installs as an update) |
| Native code in APK | `lib/arm64-v8a/libbitcoind.so`, byte-identical to the stripped daemon (`6becfe26...a8c74f02`) |
| Offline GeoIP asset | `assets/ipv4_country.bin`, 1,104,026 bytes (559,749 inside the APK), 245 countries, 362,998 ranges |
| Lint | 0 errors, 16 warnings, 2 information |
| Unit tests | 65 passing (`NodeModelsTest`, `FormatTest`, `ExplorerModelsTest`, `DescriptorsTest`, `RawTransactionTest`, `IpCountryIndexTest`, `AddressCodecTest`, `PortProbeTest`, `HeaderSyncTest`, `NotificationCardTest`) |
| zipalign | `zipalign -c -p 4` passes |

### versionCode 5 - notification card layout

Reported from the phone with a screenshot of the shade next to Mandacaru: the
POCKETCORE card carried the app icon twice (small icon on the left, large icon on
the right), a progress bar with no readable percentage, no state word and no
time, while the reference card read `Mandacaru  Fully synced  8:33 PM` over
`Synced - Block #976,724` on a solid green field. The user asked for that layout
in the app's blues, with opaque ocean blue when fully synced.

**Card.** `core/NotificationCard.kt` now owns the wording and colour of the card
(pure Kotlin, so the exact strings are unit-tested), and `core/NodeService.kt`
only builds the notification:

- `setLargeIcon` and the icon bitmap cache are gone. The small icon
  (`ic_stat_node`) is the only icon, so One UI no longer paints a second one on
  the right.
- `setSubText` carries the state (`Fully synced`, `Syncing`, `Pre-syncing`,
  `Waiting for Wi-Fi` from the policy gate, `Paused`, `Starting`, `Stopping`,
  `Error`), which One UI renders between the app name and the time.
- `setShowWhen(true)` with `setWhen(lastUpdated)` puts the time of the last RPC
  reply in the header; `setOnlyAlertOnce(true)` keeps every refresh silent.
- One content line, no second line: `Synced - Block #976,724` when caught up,
  `12.34% - Block #123,456` while downloading (the percentage has to live in the
  title because the progress bar replaces the content text on One UI),
  `42.10% - Header #812,345` during header sync.
- Colours: opaque ocean blue `#0369A1` when caught up, opaque deep cobalt
  `#1E3A8A` while downloading, dark slate-navy `#0F172A` when idle, all
  `setColorized(true)`. `setProgress(0, 0, false)` hides the bar once caught up.
- Details moved to the expanded view (`BigTextStyle`): blocks verified, peers,
  network, and the block/header target.

**Header pre-sync.** While Core pre-synchronizes block headers it reports
`headers: 0` and `verificationprogress: 0`, so the card used to sit at `0.00%`
with an empty bar. `core/HeaderSync.kt` reads the line Core writes instead -
`Pre-synchronizing blockheaders, height: 812345 (~42.10%)` (or
`Synchronizing blockheaders, ...` once the header chain is committed) - from the
last 80 lines of `debug.log`, at most every 4 seconds, on the polling IO thread.
`NodeController` exposes it as `NodeUiState.headerSync` and clears it when
`headers > 0`. Before the first such line the card says `Downloading block
headers` rather than a fake percentage.

**Verification.** `HeaderSyncTest` (7 cases: Core's format, newest line wins,
committed header chain, no line, malformed input, clamping, CRLF) and
`NotificationCardTest` (11 cases: synced wording and colour, download wording
and bar position, pre-sync from the log, header download without a log line,
paused with and without a chain, every other state's header word, and that no
title repeats the app name). In the built APK, `classes4.dex` (the app code)
contains `setSubText`, `setShowWhen`, `Synced - Block #`, `Fully synced`,
`Pre-syncing` and `Downloading block headers` and no `setLargeIcon` reference -
the only `setLargeIcon` left in the APK is inside the AndroidX library dex.
`libbitcoind.so` is still byte-identical to the stripped daemon
(`6becfe26...a8c74f02`).

`docs/notification-preview.png` draws the five cards (synced, syncing,
pre-syncing, waiting for Wi-Fi, starting) in the shade layout with
`tools/render-notification-preview.py`; it is a drawing of the strings and
colours, not a device capture.

**Left to the device.** The real shade rendering (One UI time format, bar
position, colour depth) can only be seen on the phone.

### versionCode 4 - RPC port collision, explorer, flags, broadcast, watch-only

Reported from the phone: Build 3 started the daemon but the dashboard showed
`Error: Unable to start HTTP server. See debug log for details.`.

**Root cause.** The `LOG` tab of the installed build carried the exact line:

```
2026-11-20T10:42:50Z Binding RPC on address 127.0.0.1 port 8332
2026-11-20T10:42:50Z [warning] Binding RPC on address 127.0.0.1:8332 failed:
  Unable to bind to 127.0.0.1:8332 on this computer. Bitcoin Core is probably already running.
2026-11-20T10:42:50Z [error] Unable to bind any endpoint for RPC server
2026-11-20T10:42:50Z [error] Unable to start HTTP server. See debug log for details.
```

The configuration was read correctly (`Config file arg: [main] rpcport="8332"`),
so the daemon was fine; the port was already taken. The app caused that itself:
`BitcoindNode.killStrayProcesses()` deleted `<datadir>/.lock` on every start.
Core's data directory lock is a POSIX record lock, so deleting the *name* while a
previous daemon still held the *inode* let a second daemon lock a fresh file,
start, and then die on the RPC bind - and the orphan kept running, so every
later START NODE failed the same way.

**Fix.**

- The `.lock` file is never deleted. A leftover lock file is harmless, and
  removing it was what allowed two daemons on one data directory.
- `resolvePorts()` runs before the daemon does:
  1. if the RPC port is busy, send `stop` with the stored credentials
     (`RpcClient.stopQuick`) so an orphan of this app shuts down cleanly;
  2. if it is still busy, kill stray `libbitcoind.so` processes of this UID;
  3. if another app owns the port, pick the next free one and write it into
     `bitcoin.conf`;
  4. the P2P port is checked together with the loopback port Core binds one
     above it (`127.0.0.1:<port>=onion` default), and moved if either is busy.
- `BitcoindNode.start()` passes only `-datadir`, `-conf` and `-printtoconsole`;
  the endpoint lives in the generated `bitcoin.conf`, which the app has just
  verified. Repeating `-rpcbind` on the command line would merge with the file's
  value and make Core bind the same endpoint twice (and log the same "already
  running" warning).
- If the daemon still dies on a bind, `handleUnexpectedExit()` restarts it once
  on a freshly probed port, then reports the exact Core line in the banner.
- The active port is shown in `LOCAL JSON-RPC` (with the default in brackets when
  it moved) and in `COPY CONNECTION` / `COPY WALLET CONF`.
- The `LOG` tab is gone. `SETTINGS -> VIEW DEBUG LOG` opens the same
  `debug.log` tail (400 lines, copy button); the tab bar is now
  `NODE - SYNC - EXPLORE - SETTINGS`.

The busiest failure mode is also the one that was verified end to end:

```
chosen rpc 8333 p2p 8334          (8332 held by a squatter)
Binding RPC on address 127.0.0.1 port 8333   -> getblockchaininfo answered "main"
Bound to 0.0.0.0:8334
```

**EXPLORE tab.** Block lookup by height or hash (`getblockhash`,
`getblockheader`, `getblock <hash> 2`) with height, confirmations, time, version,
nonce, bits, difficulty, size, weight, merkle root, previous/next hash and the
transaction list (tap a transaction to open it); a pruned block falls back to its
verified header with `Block body pruned (header verified)`. Transaction lookup
uses `getrawtransaction <txid> true (blockhash)`; without a transaction index a
confirmed transaction is opened through its block, and the app explains that
instead of showing a raw error. Broadcast uses `sendrawtransaction` with
whitespace/`0x`/quote-stripped hex (`RawTransaction`) and shows either the txid
with a copy button or bitcoind's exact rejection message.

**Watch-only descriptors (SETTINGS, summarised in EXPLORE).** Paste a descriptor
or a bare xpub/zpub/ypub; `Descriptors` refuses `xprv`/`tprv`/`uprv`/`vprv`/
`yprv`/`zprv`/WIF before anything is sent, wraps a bare key as `wpkh`/`tr`/
`sh(wpkh)` on the `0/*` branch, and `getdescriptorinfo` adds the checksum (and
double-checks `hasprivatekeys`). The scan runs `scantxoutset start` with range
`0..999` while polling `scantxoutset status` for progress, and can be cancelled
with `scantxoutset abort`. Results show the confirmed balance, the scan height and
every unspent output; the scriptPubKey hex is rendered into an address by
`AddressCodec` (base58check, bech32, bech32m - tested against the BIP173/BIP350
vectors).

**Peer flags.** `assets/ipv4_country.bin` is generated by
`tools/build-geoip-asset.py` from the free DB-IP IP-to-Country Lite database
(CC BY 4.0, attribution in `assets/ipv4_country_NOTICE.txt` and in SETTINGS).
Every IPv4 range is kept (362,998 ranges, 245 countries) as a delta-varint
record, so lookups are exact and need no network call. The table is parsed once
on an IO thread and searched with unsigned comparisons (addresses above
127.255.255.255 set the sign bit). The seven peers visible in the reference
screenshot resolve to US, US, CA, CA, DK, DE, US, matching DB-IP.
Each peer row shows the flag, `ip:port`, subversion, ping and in/out, and expands
to services, version, bytes, connection age, `DISCONNECT` (`disconnectnode`) and
`BAN 24H` (`setban`, confirmed first).

**Verification.** `tools/test-rpc.py` drives a real Bitcoin Core 32.1 regtest
node and checks every RPC the app issues: `getblockhash`, `getblockheader`,
`getblock 2`, `getrawtransaction` (with and without a block hash, plus the
`-5 No such mempool transaction` case), `sendrawtransaction` (round trip and
rejection), `getdescriptorinfo` (bare tpub wrapping, private key rejection),
`scantxoutset` (address descriptor, ranged descriptor, invalid descriptor),
`setban`/`listbanned` and `disconnectnode`. Two facts came out of it and changed
the code: 32.1 reports a scanned output's `scriptPubKey` as hex rather than an
object (hence `AddressCodec`), and `disconnectnode` takes the address as its
first argument. The port recovery was reproduced by holding 127.0.0.1:8332 with
a squatter and starting the daemon with the config the app generates.

### versionCode 3 - launch crash and launcher icon

Two defects were reported from the phone: versionCode 2 closed immediately on
launch, and the launcher clipped the cyan hexagon on all four sides.

**Launch crash.** `ui/SplashScreen.kt` drew the app mark with
`painterResource(R.mipmap.ic_launcher)`. From API 26 up that name resolves to
`mipmap-anydpi-v26/ic_launcher.xml`, an `<adaptive-icon>`, and Compose cannot
decode an XML drawable into a bitmap: `painterResource` falls through to
`ImageBitmap.imageResource`, `BitmapFactory` returns null for the adaptive-icon
XML, and the composition throws before the first frame. VersionCode 1 had no
splash, which is why only versionCode 2 crashed.

- New `drawable-nodpi/splash_icon.png` (512 x 512, the artwork as a rounded
  tile) is raster, and the splash now asks for `R.drawable.splash_icon`.
- The rest of the versionCode 1 -> 2 delta was re-audited: `enableEdgeToEdge`
  with `SystemBarStyle`, `WindowInsets.safeDrawing`, `Scaffold`
  `contentWindowInsets`, `Animatable` / `rememberInfiniteTransition`,
  `rememberSaveable` (switched to plain `mutableStateOf` so no primitive state
  object is ever handed to the saver), the colorized notification
  (`setColorized`, `PROGRESS_SCALE`, `addAction(0, ...)`, `ic_stat_node` vector)
  and the manifest/theme resources. No further launch-time hazard remains; the
  compiled call site now reads `R$drawable.splash_icon` (`0x7f040015`, a PNG in
  `drawable-nodpi`).

**Launcher clipping.** The foreground art filled the whole 108dp adaptive-icon
canvas, so the mark's height (74% of the artwork) landed at 78% of the canvas -
far outside the 66dp safe circle that every mask (squircle, circle, rounded
square) is guaranteed to keep. `tools/generate-icons.py` now crops the artwork
around the mark and scales the mark itself to **50% of the canvas** (54dp of
108dp), which is 27dp from the centre: 6dp inside the 66dp safe circle and 9dp
inside the visible 72dp tile. `tools/check-icon-safezone.py` reproduces the
launcher for every density under all three masks, with and without a 1.08
parallax zoom, and reports **0 clipped mark pixels**.

| Density | Canvas | Mark box (visible 72dp window) | Margin to the window edge |
| --- | --- | --- | --- |
| mdpi | 108 | 12, 8 - 58, 62 | 12.0 / 8.0 / 13.0 / 9.0 dp |
| hdpi | 162 | 19, 13 - 87, 93 | 12.7 / 8.7 / 13.3 / 9.3 dp |
| xhdpi | 216 | 26, 18 - 117, 125 | 13.0 / 9.0 / 13.0 / 9.0 dp |
| xxhdpi | 324 | 39, 26 - 175, 188 | 13.0 / 8.7 / 13.3 / 9.0 dp |
| xxxhdpi | 432 | 53, 36 - 234, 251 | 13.3 / 9.0 / 13.3 / 9.0 dp |

`ic_launcher.png` and `ic_launcher_round.png` were regenerated with the same
padding on the `#010817` field, and the adaptive icon's themed-icon layer now
points at a new alpha-only `drawable-nodpi/ic_launcher_monochrome.png`; the old
opaque foreground would have painted a solid tinted square on Android 13+.
`docs/icon-preview.png` shows the mark under the circle, squircle and rounded
square masks, `docs/splash-preview.png` shows the launch screen as it will now
appear on a 360dp-wide phone, and both regenerating tools live in `tools/`
(`generate-icons.py`, `check-icon-safezone.py`).

### UI pass in versionCode 2

- Edge to edge: the bottom tab bar applies
  `WindowInsets.safeDrawing.only(Horizontal + Bottom)` inside its own background,
  and the Scaffold uses `WindowInsets.safeDrawing`, so a three-button navigation
  bar (portrait or landscape), the gesture handle, the status bar and the display
  cutout never overlap app UI.
- Launch splash: `ui/SplashScreen.kt` fades in and pulses the blue coin mark for
  1.1 s over the `#050912` canvas.
- Status card: colorized foreground-service notification, cobalt `#1E3A8A` with a
  10,000-step progress bar while syncing, ocean `#0C4A6E` with
  `Synced - Block #<height>` when caught up, slate-navy `#0F172A` while paused.
- Palette: `#050912` canvas, `#0B1220` cards, `#111A2E` elevated, `#1E293B`
  hairlines, `#38BDF8` accent.
- Launcher art: `pocketcore-icon-blue1.png` rendered into every mipmap density,
  the adaptive icon foreground and `docs/icon.png` (field color `#010817`).

## Behavioural verification without a phone


The ARM64 daemon cannot execute in this x86_64 container, so the daemon was
validated two ways:

1. Static: `file`, `llvm-readelf` (interpreter, page alignment, dynamic
   dependencies) and strings showing the RPC handlers used by the app
   (`getblockchaininfo`, `getnetworkinfo`, `getpeerinfo`, `getmempoolinfo`,
   `uptime`, `getchainstates`, `loadtxoutset`).
2. Configuration and RPC: the official x86_64 Bitcoin Core 32.1.0 binary was
   started with exactly the `bitcoin.conf` this app generates for mainnet,
   signet and testnet4. All three networks reached RPC in about 2 seconds, and
   `getblockchaininfo`, `getnetworkinfo`, `getmempoolinfo`, `getpeerinfo`,
   `getchainstates` and `stop` returned the expected shapes (``prune=550``
   reported `prune_target_size` 576,716,800 bytes, `subversion`
   `/Satoshi:32.1.0(POCKETCORE)/`).

Unit tests replay those captured JSON payloads through the Kotlin parsers.

What is left to a real device: installing the APK, executing the bundled
daemon from `nativeLibraryDir`, and the actual chain download.
