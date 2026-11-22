# Build record

Recorded on 2026-11-21 while producing `pocketcore-1.0.0-debug.apk` (versionCode 9).

## versionCode 21 — the completeness review

Built on 2026-11-23 from `main` at `078820b`. Five findings plus two documents:
the daemon is unchanged, byte for byte. This is the first build whose published
artifact is **only** the release-signed `com.corejbh.pocketcore`; no debug-key
APK is produced or distributed, and the `com.jbitmstr.pocketcore` upgrade line
ends with the versionCode 21 build recorded under versionCode 20.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 21) |
| Release APK | `pocketcore-1.0.0-release-v21.apk`, 8,499,267 bytes, SHA-256 `09cd37caafa58bbd3ba65d597b1e3a1d0df485d34dfccb3ce8b987bbecfaf003` |
| Signing | `keystore/pocketcore-release.jks`, alias `pocketcore`; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` (unchanged; `apksigner verify` reports DN `CN=CoreJBH, OU=POCKETCORE, O=CoreJBH, L=Gumi, ST=Gyeongsangbuk-do, C=KR`) |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors, 16 warnings, 3 information in `lintDebug` and in `lintRelease` (`abortOnError = true`) |
| Unit tests | 292 passing in `testDebugUnitTest` (272 before this build) |
| zipalign | `zipalign -c -p 4` passes |
| Debug APK | none produced or published for this version |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleRelease`, all green. The absent step is `:app:assembleDebug`: this
build is the one that stops shipping it.

### The findings

**The RPC user sat in the clear.** `NodeSettings` sealed the password with
`SecretStore` since versionCode 9 and kept the user half of the same Basic Auth
pair in plain `SharedPreferences`. The pair is now sealed together
(`rpc_user_enc`), a plaintext `rpc_user` written by an earlier build is read once
and re-encrypted in place by `SecretStore.read`, and `rotateCredentials()` writes
the user through the store as well - so an upgraded install keeps the credential
it was started with. A credential is generated only when neither a ciphertext nor
a legacy plaintext value is stored (`secretMissing`); an entry that exists but
cannot be opened right now is left alone, the same rule the password has had
since versionCode 20.

**The listener proof was a one-time fact.** `clientAllowed()` asks for a RUNNING
node, a verified listener and a live process - all three true at the moment the
start proved the port. `loadtxoutset`, `dumptxoutset` and `scantxoutset` then ran
for minutes on that memory alone, while the user could stop the node, Android
could kill the daemon, another app could take the port, and the reply would still
be written back as this node's state. `SensitiveRpc` reads the facts fresh before
the request and again after the reply: status, listener, process, `shouldRun`, the
lifecycle generation and the port's **current** owner
(`BitcoindNode.listenerOwner`, not the cached flag). A refusal says which fact
failed; a reply that loses its facts is reported as a lost result instead of a
finished one. The scan goes through the same gate
(`DescriptorScanController.scan`), and its own cancel path still cancels the job
before it asks the daemon.

**A snapshot file was only checked by its size.** `importSnapshot` refused
anything under 1 KB and nothing else, so a screenshot, an archive or a snapshot
of another network was copied - gigabytes - and only `loadtxoutset` could say it
was wrong. `SnapshotHeader` now decodes the header Core v32.1 writes
(`src/node/utxo_snapshot.h`: magic `utxo` 0xFF, little-endian version 2, the
network's `pchMessageStart`, the 32-byte base block hash and the 8-byte coin
count) and refuses a wrong magic, an unsupported version, another network, a
zero base hash or zero coins - before the copy starts, and again on the copied
file. `loadSnapshot` checks the stored file's header before it calls the daemon.

**The load check measured the wrong file.** `loadtxoutset` writes a second
chainstate and keeps the existing one, and the old check compared free space with
the snapshot file, which is already on disk. `StorageBudget.requiredLoadBytes`
budgets the larger of the snapshot's size and the chainstate already on the phone
(`NodePaths.chainstateBytes`), plus the 64 MB reserve, and the card names both
chainstates and what to free. The measurements behind the numbers are in
`docs/mobile-architecture.md`: `gettxoutsetinfo.disk_size` 13,290,295,029 bytes at
block 935,000, an 8.8 GiB snapshot file at 965,000, and 894.93 GB for the full
chain at 977,085.

**The published app had two identities.** GitHub Releases shipped a debug-signed
`com.jbitmstr.pocketcore` upgrade APK while Play shipped the release-signed
`com.corejbh.pocketcore`, and the README explained both. The debug line is over:
`README.md` documents one signature (`com.corejbh.pocketcore`, the release
keystore), the install path is `:app:assembleRelease`, and the new release
pipeline in `docs/ci/build-release.yml` builds, verifies and publishes only that
artifact, with every action pinned to a commit SHA (checkout `11bd719`,
setup-java `7a6d8a8`, upload-artifact `65c4c4a`, download-artifact `d3f86a1`)
and a step that fails the job if the APK is `com.jbitmstr` or carries the
Android debug key. It lives in `docs/ci/` rather than `.github/workflows/`
because a GitHub App token without the `workflows` scope cannot push a workflow
file (the push of the version under `.github/workflows/` was rejected with
"refusing to allow a GitHub App to create or update workflow"); the owner
installs it there once.
A phone that still runs the debug build reaches the release app by exporting a
snapshot and loading it after the reinstall; the README says so.

### The two documents

`README.md` loses the "about 1 GB free storage" line (the prune target is not the
node: the chainstate is 12-13 GB more), the debug-key installation text and the
750-800 GB figure (a full mainnet chain is ~890-900 GB today), and gains the
snapshot header and load-space wording. `docs/mobile-architecture.md` is new: the
storage measurements with their sources, `listen=1` on a mobile NAT and what to
write instead (`listen=0` by default, a forwarded-port switch, or Tor onion
service inbound), the full-UTXO `scantxoutset` walk and the cheaper ways to
answer the same question (remember the last scan, rescan only after the chain
moved, BIP158 filters, no server), and the unbounded `PARTIAL_WAKE_LOCK` with a
bounded lock renewed by progress, a slower poll while caught up and a Doze-
friendly wake-up. Those three are proposals with their touchpoints; this build
changes the snapshot header, the load budget and the sensitive-RPC gate.

## versionCode 20 — tenth review

Built on 2026-11-23 from `main` at `30a32a8`. Ten review findings, all in the
app: the daemon is unchanged, byte for byte. The export of a snapshot was also
changed, so a file this phone writes can be loaded by another one.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 20) |
| Release APK | `app-release.apk`, 8,495,651 bytes, SHA-256 `0aca771cd05f2cf6ea9582e9ef1b627e60015c3eab20e90c926eec549c0bda8a` |
| Debug APK | `app-debug.apk`, 17,149,574 bytes, SHA-256 `bce9d1c14ee6c6b4639fa27c88d4bfa3d3689146c05713c12a04b91a3e5e39f6` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` (unchanged) |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors; 16 warnings in `lintDebug`, 16 in `lintRelease` (`abortOnError = true`) |
| Unit tests | 272 passing in `testDebugUnitTest` (243 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |
| In-place upgrade build | `com.jbitmstr.pocketcore`, versionCode 21, 17,149,638 bytes, SHA-256 `a9b282875e2bee92b6df847b3c2caba3b23ce77814e49a2a66ff8455cb43e4ba`, debug key `F5:07:48:15:C3:5C:6B:87:B2:5C:39:32:DF:22:83:48:86:8A:CB:B3:18:70:E2:01:18:35:66:EA:2A:A9:C0:11` (the key of versionCode 9-19) |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease`, all green. As in the previous build,
the upgrade APK is built in a tree outside the repository (`git archive` of
`HEAD`, then the `applicationId` and `versionCode` patched in that copy), so
`com.jbitmstr` and versionCode 21 are never part of this repository's tree. The
upgrade keeps the installed app's identity, data directory included, so the
chain at `<app files>/node/data` - `blocks/`, `chainstate/` and an imported
snapshot - is carried over untouched.

### The ten findings

**ABORT could lose to the reply it caused.** `scanAbort()` makes the running
`scantxoutset` return at once, with an error rather than a result, and the abort
asked the daemon *before* it cancelled the scan job: the job then caught that
error and wrote it over the state ABORT published. The job is cancelled first
now, the cancelled state is published before the request goes out, and the scan
coroutine checks that it is still wanted after the blocking scan call returns
(`answerIfStillWanted`), so a reply that arrives after a cancel ends the
coroutine instead of becoming a "scan failed" line. `abortInOrder` keeps the
order, and both are unit tested (`DescriptorAbortTest`).

**A stopped node could be flipped to ERROR.** `handleForeignPort` and
`handleUnexpectedExit` cancel the poll loop, take the node mutex and then restart
or fail - without asking whether the start that saw the problem was still the
current one. A STOP that ran while the reply was in flight had already published
STOPPED and cleared the state, and the handler wrote ERROR over it; the retry
branch could also start a node the user had just stopped. Both now take the
start's `generation` and return inside the mutex when `!isCurrent(generation)` or
`!shouldRun`.

**A valid xpub could be refused as a private key.** The private-key check
lowercased the whole input and searched for `xprv`/`zprv` and friends anywhere in
it. Base58 is case-sensitive, so an xpub whose payload happens to carry `XPRV` -
or the lowercase letters by chance - was read as a private key and refused. The
search is case-sensitive and anchored on a token, and an extended key is also
decoded: a 78-byte Base58Check payload with private version bytes is refused
however it is written (`DescriptorsTest` now accepts three such public keys).

**Settings were written on the main thread.** Every setter ended in
`SharedPreferences.commit()` and a full re-read of the store, called straight from
the Compose callbacks - a synchronous disk write, and a Keystore read for the
secrets, on the frame that drew the switch the user had just flipped. The setters
are `suspend` and run on `Dispatchers.IO` behind one write lock; the UI starts a
coroutine, and the action that follows a change (a restart) runs after the write
has landed.

**A Keystore hiccup replaced the RPC password.** `read()` generated a new
password whenever the stored one could not be read, and wrote it over the
ciphertext - which changed the pair a running daemon authenticates with while the
value that would have decrypted was still there. A password is generated only
when nothing is stored (`rpc_password_enc`, or the plaintext key of a build before
versionCode 9, is absent); an entry that cannot be opened right now reports an
empty pair that nothing sends - a start rotates the pair before it writes the
conf, and the orphan stop skips a blank password.

**The service decided before the settings were read.** `onStartCommand` read
`settings.state.value.shouldRun`, which holds the defaults until `load()` has
finished: a service recreated after a process death saw `false` and never started
the node it was meant to resume. The check is gone; the command asks
`NodeController.resumeAfterRecreate()`, which waits for the stored settings and
then starts the node through the same gate every other start uses.

**Snapshots outlived their use.** A new import left the previous `.dat` (and any
`.part` a killed import wrote) in the folder, and a full wipe removed the chain
but kept the snapshot files and the path the settings pointed at. An import
now deletes the files it replaced (`NodePaths.staleSnapshotFiles`), and a full
wipe takes the snapshots folder and forgets the stored path; a network-only wipe
leaves them, because the file does not belong to the chain being deleted.

**A busy port could be reported as free, and a failed search was silent.** The
probe claimed 127.0.0.1 only, so a listener on every address (`0.0.0.0`) could
pass; it now claims the wildcard address as well. `findFree` and
`findFreeListenPair` returned the preferred port when nothing in the window was
free, which handed bitcoind a port another app held: they return null now, and
`PortResolver` answers with a reason the screen shows instead of a start that
fails in Core. A busy P2P port with no free pair is not fatal - the node runs
without inbound peers and says so.

**The daemon's checksum was cached on disk.** `BinaryIntegrity` recorded its
verdict in a stamp for the build and answered from it later, so a cold start
after an update or a replaced file could pass without looking at the file it was
about. The file is hashed at every check now; 10.7 MB is well under a second.

**A transaction's fee had nothing to come from.** `getrawtransaction` was called
with the boolean verbosity, which leaves each input without its `prevout`, so the
input total was unknown and the fee could not be shown. It asks for verbosity 2,
and the parameters are unit tested (`TransactionLookupTest`).

The saved watch-only descriptor is loaded once from `NodeController.startup`
rather than from a `LaunchedEffect` in SETTINGS that ran on every visit, which
put a descriptor the user had just cleared back into the field.

### The snapshot another phone can load

`loadtxoutset` accepts a `.dat` only when the height, the block hash and the
serialized UTXO set hash match a checkpoint compiled into the daemon (`m_assumeutxo_data`
in `src/kernel/chainparams.cpp`). Core 32.1 carries mainnet 840,000 / 880,000 /
910,000 / 935,000 / 965,000, testnet4 90,000 / 120,000 / 150,000 and signet
160,000 / 290,000 / 320,000; `AssumeUtxo` holds those values, and `latest()`
names the newest of each network. A dump at the node's own tip (`latest`) matches
none of them, so it loads on the phone that made it and nowhere else.

EXPORT SNAPSHOT therefore calls `dumptxoutset` with the newest checkpoint of the
selected network as its rollback height. Before the dump it refuses a node that
has not synced that far and a pruned node whose keep horizon is already above the
checkpoint, each with the height and the fix (**STORAGE -> Full (prune=0)** for
the pruned case); it compares the checkpoint's block hash with the node's own
block at that height, and after the dump it compares the reply's `txoutset_hash`
with the checkpoint, which is exactly what another phone's `loadtxoutset`
compares. The card states the result: verified files load on another phone,
anything else is reported as this phone's file only. The decisions are unit
tested (`AssumeUtxoTest`), and the LOAD note names the checkpoints a file from
another phone has to be at.

## versionCode 19 — ninth review

Built on 2026-11-22 from `main` at `9e13c08`. Four review findings, all in the
app: the daemon is unchanged, byte for byte.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 19) |
| Release APK | `app-release.apk`, 8,487,559 bytes, SHA-256 `69ff4838fbe2535ab0f179cc0e586b44b24c793d6fd83212e7170c51f05a05a9` |
| Debug APK | `app-debug.apk`, 17,131,426 bytes, SHA-256 `cfdb987d00a3662e86970b03539de7bf3a70e9d9d0aa4bfe94143d6ed4319844` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` (unchanged) |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors; 16 warnings in `lintDebug`, 16 in `lintRelease` (`abortOnError = true`) |
| Unit tests | 243 passing in `testDebugUnitTest` (225 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |
| In-place upgrade build | `com.jbitmstr.pocketcore`, versionCode 20, 17,131,074 bytes, SHA-256 `2c2a461f4d227a6c947a0f28dd14a2a418247486b86444a746bcc9715ce2b1cf`, debug key `F5:07:48:15:C3:5C:6B:87:B2:5C:39:32:DF:22:83:48:86:8A:CB:B3:18:70:E2:01:18:35:66:EA:2A:A9:C0:11` (the key of versionCode 9-18) |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease`, all green. As in the previous build,
the upgrade APK is built in a tree outside the repository (`git archive` of
`HEAD`, then the `applicationId` and `versionCode` patched in that copy), so
`com.jbitmstr` and versionCode 20 are never part of this repository's tree.

### The four findings

**The version lookup ran the daemon before checking it.** `start()` has gone
through `prepareBinary()` - existence, the 1 MB floor, the APK's own SHA-256 and
the executable bit - since versionCode 12, but `version()` executed the same
extracted `libbitcoind.so` with `-version` on every app start without asking
anything. A file replaced in `nativeLibraryDir` (the one place an app may run
its own native code, and the reason the checksum exists) was refused a start and
executed anyway, once per launch, from the startup path that then reports the
node ready. The lookup now lives in `BinaryVersion`, which calls `prepareBinary`
first and returns no version at all when it reports a problem - nothing is
executed, and the start path shows the same message it would have shown for a
start. The cached stamp (versionCode plus the expected hash) is still read first,
because a cached string runs nothing; a stamp from another build or one holding
an error line is discarded and re-probed, and the order check-then-run is unit
tested (`BinaryVersionTest`).

**One Keystore hiccup cost every secret.** `SecretStore` gives the RPC password,
the watch-only descriptor and the scan result one AES key from the Android
Keystore. `usableKey()` replaced that key on *any* exception from `load()`: a
provider that failed mid-call, an I/O error, an `IllegalStateException` all
deleted the entry and generated a new one, and everything sealed under the old
key - three secrets that were perfectly readable - became unreadable. The
replacement is now only for a key the Keystore reports as gone
(`InvalidKeyException`, which includes `KeyPermanentlyInvalidatedException`, and
`UnrecoverableKeyException`); any other failure returns null and leaves the entry
alone, so the next call asks the same key again. A read whose ciphertext cannot
be opened now says *why* it could not: a damaged value is dropped (as before),
while a Keystore that did not answer leaves the ciphertext stored for the next
attempt instead of deleting a descriptor it could not even look at. The decision
is `KeyLadder` over an injectable `KeySource`, so transient and permanent
failures are unit tested (`KeyLadderTest`).

**Tor mode probed a P2P port it does not use.** With Tor routing on, the conf
written for the start carries `listen=0`: the daemon binds no P2P socket at all.
The port resolver still opened two sockets on the default P2P port and the
loopback port one above it, and could move the pair to a "free" port the node
would never listen on - a note on the screen about an endpoint that does not
exist. `resolve()` takes `p2pListener`, false when `torProxy` is set, and skips
that step entirely; the RPC endpoint, which the credentials go to, is checked
exactly as before, and the P2P reservation is not taken either.

**Every capability callback ran a policy pass.** `registerDefaultNetworkCallback`
delivers `onCapabilitiesChanged` for signal strength, bandwidth and captive
portal probes as well as for a transport appearing or disappearing, and each one
refreshed the label and launched `evaluatePolicy()` - which takes the node mutex
and can stop or start the daemon. `EnvironmentGate` admits one pass per
distinct connectivity signature per 2 s window: the signature is built from the
transports and validation the policy and the label actually read
(`NetworkSignature`), so a repeat is dropped, while a real change - Wi-Fi gone -
still passes at once, and a repeat after the window passes again so a state that
drifted is re-read. The power receiver's broadcast and `onAvailable` / `onLost`
pass no signature and are never dropped. The window and the signature are unit
tested (`EnvironmentGateTest`), as is the skipped P2P probe (`PortResolverTest`).

## versionCode 18 — eighth review

Built on 2026-11-22 from `main` at `3031c2d`. Three review findings, all in the
app: the daemon is unchanged, byte for byte.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 18) |
| Release APK | `app-release.apk`, 8,486,335 bytes, SHA-256 `27277d0c339bfd4cd21d4d3e45d1dd9ec415b4bc75225aeea6f28f8eaa8315f6` |
| Debug APK | `app-debug.apk`, 17,126,286 bytes, SHA-256 `de584b09e9b25eae851ca9feb39e717ad673f273a839fa3761cb41d452198ebe` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` (unchanged) |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors; 11 warnings in `lintDebug`, 16 in `lintRelease` (`abortOnError = true`) |
| Unit tests | 225 passing in `testDebugUnitTest` (205 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |
| In-place upgrade build | `com.jbitmstr.pocketcore`, versionCode 19, 17,125,814 bytes, SHA-256 `469d7cdad20a591c299d3645158b909969ffc8f0f96c5ce5bb6cfba17953c40c`, debug key `F5:07:48:15:C3:5C:6B:87:B2:5C:39:32:DF:22:83:48:86:8A:CB:B3:18:70:E2:01:18:35:66:EA:2A:A9:C0:11` (the key of versionCode 9-16) |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease`, all green. As in the previous build,
the upgrade APK is built in a tree outside the repository (`git archive` of
`HEAD`, then the `applicationId` and `versionCode` patched in that copy), so
`com.jbitmstr` and versionCode 19 are never part of this repository's tree.

### The three findings

**An orphan's port had no proof, and the password went out anyway.** On Android
10 and above `/proc/<pid>/net/tcp` is unreadable, so versionCode 16's orphan stop
fell back to three facts: a daemon of this UID is alive, the conf this app wrote
records the port, and there is a credential. All three can hold while the port
belongs to another app - the daemon may be alive on a port the conf no longer
names, or a second node app may have taken it after our daemon exited - and the
stop request is HTTP Basic Auth to that port. `OrphanStop.maySend` now also
requires the daemon's own `debug.log` to show it binding that loopback port
after its process started: the start time is `/proc/<pid>/stat` field 22 turned
into wall-clock millis against the phone's uptime (`ProcScan.statStartTicks`,
`processStartMillis`, `BitcoindNode.processStartedAt`), and the bind line has to
be stamped at or after it. Without that proof nothing is sent: the daemon of
this app is ended with `SIGTERM` (`BitcoindNode.terminateGracefully`), which Core
handles as the same clean shutdown as the `stop` RPC, carries no credential, and
can only reach a process of this app's UID running the bundled daemon.

**A shrunken log could still vouch with the previous run's bind line.**
versionCode 17 made the read start at 0 when `debug.log` was shorter than the
start's offset, so the bind line of the current start would not be missed. The
kept tail also holds the previous run's own `Binding RPC` line on the same port,
though, and that line alone passed the check. The read now reports that it had to
start at the file's beginning (`NodePaths.LogWindow.fromStartAfterShrink`), and
`ListenerFallback.rpcBindConfirmed` takes the wall-clock start of the start being
judged: in such a window a bind line counts only when its Core timestamp is at or
after that start, compared at whole seconds because that is Core's stamp
resolution. A line that cannot be dated is not evidence for a start. An ordinary
window (read from its offset) needs no stamp check, because every line in it was
written after the offset.

**Durable writes, and startup off the main thread.** The RPC pair, the
descriptor and the settings were written with `apply()`, which returns before the
disk write: a start that rotated the pair and launched the daemon could be
followed by a process death that left no password on disk for the next start to
read the orphan with, and a settings change could be lost with the process. Both
stores now `commit()` and say why (`@SuppressLint("ApplySharedPref")`);
`SecretStore` also keeps what it decrypted for the life of the process, so the
Keystore is touched once per secret rather than once per settings read.
`NodeSettings` reads nothing in its constructor any more, and `NodeController.init`
- called on the main thread from the activity and the service - only builds the
objects and publishes a first state. A `startup()` coroutine on `Dispatchers.IO`
loads the settings (Keystore), the snapshot file's size, the daemon's version and
the peer country table, and resumes a node that was running; every action that
reads the settings awaits `settingsReady` first, so no start is built from the
defaults.

### Also in this build

`wipeNodeData()` is a snapshot operation now: it holds `SnapshotGate` before the
node mutex, in the same order as import / export / load / clear, so a wipe cannot
delete the chainstate a `loadtxoutset` is writing or the blocks a `dumptxoutset`
is walking.

Importing or loading a snapshot checks the free space before anything is opened
(`StorageBudget`): the source size from the document provider (import) or the
file's length (load) plus a 64 MB reserve has to fit in
`NodePaths.freeSpaceBytes`, and the card names what the snapshot needs, what the
phone has free and how much to free. An unknown free space or an unknown source
size never blocks - a check that cannot be made is not a refusal, and the copy
still fails cleanly on a full volume.

## versionCode 17 — seventh review

Built on 2026-11-22 from `main` at `87256ad`. Three review findings, all in the
app: the daemon is unchanged, byte for byte.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 17) |
| Release APK | `app-release.apk`, 8,484,147 bytes, SHA-256 `62e7ad3036ae4d9835ba4c7e2b9b2b1e228c152afb3020abd28a7360c96f9aa7` |
| Debug APK | `app-debug.apk`, 17,120,602 bytes, SHA-256 `70d937c31b35972179ca1440e456f77f161a8e8b2bc8b65c28ab4dd5ab00b38f` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` (unchanged) |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors, 16 warnings in `lintDebug` and `lintRelease` (`abortOnError = true`) |
| Unit tests | 205 passing in `testDebugUnitTest` (186 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |
| In-place upgrade build | `com.jbitmstr.pocketcore`, versionCode 18, 17,120,614 bytes, SHA-256 `153e3bfd71d30e75a281c7700289163cb0ae5925976ed1b0bf1dc9d121ffb45b`, debug key `F5:07:48:15:C3:5C:6B:87:B2:5C:39:32:DF:22:83:48:86:8A:CB:B3:18:70:E2:01:18:35:66:EA:2A:A9:C0:11` (the key of versionCode 9-16) |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease`, all green. The upgrade APK is built
in a tree outside the repository (`git archive` of `HEAD`, then the
`applicationId` and `versionCode` patched in that copy), so `com.jbitmstr` and
versionCode 18 are never part of this repository's tree.

### The three findings

**A shrunken debug.log made a healthy node look foreign.** Core rewrites
`debug.log` at startup when `-shrinkdebugfile=1` keeps it: it keeps the recent
tail and drops the rest, so the byte offset a start recorded can end up past the
end of the file. `NodePaths.readFrom` clamped the start to the file's length,
which for a truncated log is the end - an empty window. `ListenerFallback` then
found no `Binding RPC` line for this start, reported the port as
`SocketOwner.FOREIGN`, and the node that had just bound it was killed and
reported as another app's. The read now starts at 0 when the file is shorter
than the offset (the 1 MiB cap still applies), so the shrink cannot hide this
start's own bind line.

**The snapshot operations could run into each other.** Import, export, load and
clear all work on the same snapshot path and the same one chainstate walk, and
each wrote the settings and the state on its own coroutine: an import that was
still copying published its path after a CLEAR had already deleted the file, a
LOAD could read a file CLEAR was deleting, and a dump could run while a load was
rewriting the chainstate. The four now run one at a time behind `SnapshotGate`,
and every step that writes the settings or `state` takes the node mutex as well.
The gate is held for a whole copy or dump while the node mutex is held only
briefly, so STOP and the Wi-Fi / charger policy are not blocked by a snapshot;
the lock order (snapshot gate first, node mutex second, never the reverse) is
documented on `SnapshotGate` and covered by a test.

**Two RPC poll passes could overlap.** The poll loop, `refreshOnceNow()` after a
peer action, and a snapshot action's refresh all asked for a pass without
waiting for each other, and two passes read the same state and wrote it back in
whatever order their replies arrived - an older reply landing after a newer one,
or a stop described by a reply about the daemon before it. `RefreshGate` now
lets one pass run and drops a request that arrives while it is in flight (the
running pass is asking the same node a moment later), and releases itself in a
`finally` so a failed pass cannot block refreshes for the life of the process.

### Also in this build

Explorer amounts and fees are integer satoshi arithmetic. Amounts are parsed
once from their decimal text into satoshi `Long`s (`BtcAmount`), the outputs are
summed as `Long`s and the fee is the inputs' total minus that sum, so a
transaction whose prevouts cover its outputs exactly reports 0 sat instead of
`-5e-17` BTC, and a thousand one-satoshi outputs add up to exactly 1,000 sat.
The `Double` fields the screen reads are derived from those satoshis, and the
explorer card formats BTC and satoshis straight from the integers.

## versionCode 16 — sixth security and state review

Built on 2026-11-22 from `main` at `46f5186`. Six review findings, all in the
app: the daemon is unchanged, byte for byte.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 16) |
| Release APK | `app-release.apk`, 8,481,495 bytes, SHA-256 `1196f9f4704bae12cf57ec6b894186a8ef5009a0bc04c43fdcef15c999e4c61b` |
| Debug APK | `app-debug.apk`, 17,112,522 bytes, SHA-256 `9af51d1928e839df18c3af058ef0350e628e5447ee962294b8ef5c3aeda281d2` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` (unchanged) |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors, 16 warnings in `lintDebug` and `lintRelease` (`abortOnError = true`) |
| Unit tests | 186 passing in `testDebugUnitTest` (159 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease`, all green.

### The six findings

**A snapshot export or load could go to a port the gate had closed.**
`exportSnapshot` and `loadSnapshot` took the client straight from the internal
`rpc` field, skipping the `client()` gate that every other caller goes through,
so a dump could be sent to a port whose listener this app had not proved is its
own node (and, with the node stopped, the export card switched to "busy" with
nothing that could ever advance). Both now ask `client()`, like the explorer,
the scan and the peer actions; a stopped node gets the refusal in the card
instead.

**A debug.log of hundreds of megabytes could exhaust the heap.**
`NodePaths.readFrom` allocated everything from the offset to the end of the
file in one `ByteArray` - an `OutOfMemoryError` on a synced node, and past 2 GB
also an `Int` byte-count overflow - and `BitcoindNode.outputTail` used
`File.readLines()`, which loads the whole process log to show its last forty
lines. The read now stops at `MAX_LOG_READ_BYTES` (1 MiB) and starts **at the
offset** rather than at the end of the file, because the line it looks for - the
`Binding RPC on address ...` a start writes first - sits at the beginning of its
own window; the console tail goes through the 256 KB window of
`NodePaths.readTail` that `debug.logTail` already used.

**An orphan daemon was always killed, never stopped.** On Android 10+ SELinux
keeps `/proc/<pid>/net/tcp` unreadable, so the socket check that proves the
listener could not answer, the credentials were withheld (correctly), and the
only route left was `killProcess` - a SIGKILL that can leave the chainstate
needing a reindex. The new `OrphanStop` requires three facts about this app's
own daemon instead: a process of this app's UID runs the bundled bitcoind (read
from `/proc/<pid>/status` and `cmdline`, which stay readable), the port is the
`rpcport` recorded in the `bitcoin.conf` this app wrote for that start (Core
exits when it cannot bind it, so a live daemon from that file listens nowhere
else), and there is a credential to present - the data directory's `.cookie`
when one exists, otherwise the stored pair its `rpcauth` line was built from.
The stop RPC is sent to loopback, and the app then waits up to 12 seconds for
the process to exit (Core answers the stop and writes its state out afterwards,
so the socket being free is not the same news). Only a stop that is refused,
unanswered or still unfinished after that wait falls back to the kill it used
before.

**ABORT did nothing when the node was off.** `DescriptorScanController.abort()`
returned early when no client existed, so a scan that was running when the node
stopped (or that the daemon had already finished) left the card busy with a
progress bar and a button that did nothing. The daemon is asked to abort only
when there is a client; the state is reset either way, and only a scan that was
actually running reports "Scan cancelled".

**START NOW disabled the Wi-Fi / charger gate for the rest of the run.**
`forceOverride` was set by `start(force = true)` and cleared only by STOP, so a
manual start off Wi-Fi kept the gate off afterwards. It is now dropped as soon
as the real condition is met: the next gate evaluation sees an open gate, clears
the flag, and a later closure pauses the node the way the settings say.

**One slow poll walked a RUNNING node back to STARTING.** A transport timeout
(or Core's `-28`) inside the two-minute startup grace was reported as "Warming
up the node" whatever the state had already reached, so a node that had been
RUNNING for a minute dropped to STARTING on a single missed answer. The status
is now left at RUNNING for a transient failure and only the detail line changes
("RPC did not answer ... Retrying."); the next pass clears it. A daemon that is
gone is still reported (the poll checks the process first), and a port that no
longer listens is still the warm-up or the stuck-start message.

### In-place upgrade APK for the installed app

| Item | Value |
| --- | --- |
| Package | `com.jbitmstr.pocketcore` |
| Version | 1.0.0 (versionCode 17) |
| Build | `:app:assembleDebug` of the same source (`46f5186`) in a temporary copy of the tree, with `applicationId` and `versionCode` changed there only - nothing of this build is committed |
| APK | `pocketcore-update-existing.apk`, 17,113,010 bytes, SHA-256 `ed17558f0907772ca082d32e9171e50188bb21b13c0c83adbf657a44db69764a` |
| Signing | the Android debug key, certificate SHA-256 `f5:07:48:15:c3:5c:6b:87:b2:5c:39:32:df:22:83:48:86:8a:cb:b3:18:70:e2:01:18:35:66:ea:2a:a9:c0:11` - the same certificate as versionCode 9-16, so it updates in place and keeps the synced chain |
| Daemon | identical bytes to the release build above (checked inside the APK) |

### Build environment

Same as versionCode 14-15: `ANDROID_USER_HOME=/workspace/.android` for the debug
keystore, `--no-parallel --max-workers=1`, `ActiveProcessorCount=1`, SerialGC
and `kotlin.compiler.execution.strategy=in-process`. The container's per-UID
thread limit (256) was nearly exhausted by other builds during the day, so a few
Gradle invocations started while other work was running failed with "unable to
create native thread" before any task ran; every result above comes from a run
that completed, and the checks were repeated as single tasks rather than one
giant invocation. The flags live in the build command, not in the repository.

## versionCode 15 — fifth security review

Built on 2026-11-22 from `main` at `009e380`. Five review findings, all in the
app: the daemon is unchanged, byte for byte.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 15) |
| Release APK | `app-release.apk`, 8,480,007 bytes, SHA-256 `debbc5b1bf4301b4cbaefc1f251cd46dac86276736f0b3569b0422db0d00cc65` |
| Debug APK | `app-debug.apk`, 17,107,706 bytes, SHA-256 `01e52646be486ddbe0414cf173b0e2e0537e299e13ea6eef37e4fde5a906305e` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; v1 (JAR) signature off, v2 on; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors, 16 warnings in `lintDebug` and `lintRelease` (`abortOnError = true`) |
| Unit tests | 159 passing in `testDebugUnitTest` (135 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease`, all green.

### The five findings

**A started node could never leave STARTING on Android 10+.** Since Android 10,
SELinux keeps every app from reading `/proc/<pid>/net/tcp`, so the check that
proves the RPC port belongs to the daemon just launched could never read the
socket tables: the verdict was UNKNOWN, and UNKNOWN is a refusal - the
credentials were withheld forever and the node sat in STARTING while perfectly
healthy. When the tables cannot be read, the owner is now established from
three facts instead: the daemon process is alive (which also rules out a bind
failure, since Core exits when it cannot bind), the RPC port answers on
loopback, and the daemon's own debug.log shows Core binding the RPC endpoint -
read only from the byte offset this start began at, so a bind line from an
earlier run of the same port can never vouch for this one. A port that answers
without that evidence is still FOREIGN and refused, and a verdict that survives
all three is still UNKNOWN and refused. (An orphan daemon left by an earlier
app process has no bind evidence in the new process's hands, so it keeps the
previous treatment: refused, then killed ungracefully.)

**The one-minute clipboard clear silently never ran on Android 10-12.** The
clear compared the clipboard against the copy before deleting - but a
background app's `getPrimaryClip()` answers null on Android 10 to 12, so the
comparison always aborted and a sensitive copy stayed on the clipboard past
its minute. An unreadable clipboard now clears without the comparison; where
the clipboard is readable, a newer copy is still left alone.

**Enabling Tor no longer trusts an Orbot nobody checked.** The switch now
probes 127.0.0.1:9050 before turning the setting on: nothing listening means a
dialog that says so, with an enable-anyway exit for leaving the setting on
while Orbot starts. The settings text also documents Core's `onlynet=onion`
and why PocketCore does not write it - the clearnet-addressed peers the node
reaches through the proxy still hide this phone, and onion-only would drop
them.

**Editing the settings no longer flips a stopped node to WAITING.** The policy
evaluation treated WAITING as the resting state of any node without a running
daemon; the state now moves to WAITING only when the node is running or meant
to run (`shouldRun`), so a node the user stopped stays STOPPED while the
settings are edited.

**zpub/ypub/vpub/upub keys are converted before they reach the node.** Core's
descriptor language accepts the standard xpub/tpub prefixes only, so a watch-
only scan with a BIP 84/49 key failed inside the node with "is not a valid
descriptor". The keys are now rebased to xpub/tpub - the same BIP 32 key,
standard version bytes, checksum recomputed - bare keys and keys inside a full
descriptor alike, and vpub is wrapped as native segwit (wpkh) rather than
taproot, which scanned the wrong scripts. A key that does not decode cleanly
is refused with a check-the-paste message instead of sent to the node.

**A Full (prune=0) rebuild no longer wipes the other networks.** The rebuild
deletes only the selected network's chain data - mainnet's files at the data
directory root, or the network's own subdirectory - and leaves the other
networks' chains in place; the dialog says which network it deletes.

### In-place upgrade APK for the installed app

| Item | Value |
| --- | --- |
| Package | `com.jbitmstr.pocketcore` |
| Version | 1.0.0 (versionCode 16) |
| Build | `:app:assembleDebug` of the same source (`009e380`) in a temporary copy of the tree, with `applicationId` and `versionCode` changed there only - nothing of this build is committed |
| APK | `pocketcore-update-existing.apk`, 17,107,710 bytes, SHA-256 `7273424a4023a8679be2e6c00ee9a4ef0c2c1d735bbed4496e49e62171f0d3ea` |
| Signing | the Android debug key, certificate SHA-256 `f5:07:48:15:c3:5c:6b:87:b2:5c:39:32:df:22:83:48:86:8a:cb:b3:18:70:e2:01:18:35:66:ea:2a:a9:c0:11` - the same certificate as versionCode 9-15, so it updates in place and keeps the synced chain |
| Daemon | identical bytes to the release build above (checked inside the APK) |

### Build environment

The container's passwd entry gives the build JVM `user.home=?`, so the Android
tooling resolved the debug keystore against a relative `?` and failed
`packageDebug`. The builds above ran with `ANDROID_USER_HOME=/workspace/.android`
(where the debug keystore lives) and, for the same process cap as versionCode
12-13, `--no-parallel --max-workers=1`, `ActiveProcessorCount=1`, SerialGC and
`kotlin.compiler.execution.strategy=in-process`. The flags live in the build
command, not in the repository; no source or build script behaviour differs
because of them.

## versionCode 14 — fourth security review

Built on 2026-11-22 from `main` at `d440215`. Four review findings, all in the
app: the daemon is unchanged, byte for byte.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 14) |
| Release APK | `app-release.apk`, 8,476,271 bytes, SHA-256 `720a3010a7654e384c2d0b47d8ab66071d5de6792582b659d4c644180a3f5ec3` |
| Debug APK | `app-debug.apk`, 17,097,366 bytes, SHA-256 `3e33e7192669c6af94110d88a386f10e0437cdcf813c790843d67b6ea3beac87` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; v1 (JAR) signature off, v2 on; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Lint | 0 errors, 16 warnings in `lintDebug` and `lintRelease` (`abortOnError = true`) |
| Unit tests | 135 passing in `testDebugUnitTest` (121 before this build) |
| zipalign | `zipalign -c -p 4` passes for both APKs |

The release checklist was `:app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease`, all green.

### The four findings

**No client before the listener is verified.** The credentials were only sent to
a socket proved to be this app's daemon, but the rest of the app asked
`NodeController.client()` for a client as soon as the process was alive - which
is before that proof exists, since the free-port check ends before bitcoind
binds the port. `client()` now returns null unless all three hold: the node is
`RUNNING` (the state the poll loop only reaches after the proof), this start's
listener is verified, and the daemon is alive. The rule is a named function
(`clientAllowed`) with a unit test over every status, and EXPLORE's block and
transaction lookups and its broadcast are enabled only while `RUNNING`, so the
buttons say why instead of failing behind a null client.

**A stop is not undone by a reply in flight.** A `refresh()` that was waiting on
an RPC answer when STOP was pressed wrote its result back and turned the node
`RUNNING` again. Every start (`beginStartLocked`) and every stop (`stopProcess`)
now takes the next number from a `LifecycleGeneration` counter, `refresh()` keeps
the number it began with, and every write it makes - the RUNNING state, the
warm-up lines, the error lines - goes through `updateIfCurrent`, which drops it
once the counter has moved. A counter rather than a boolean: a stop followed by
a quick start must not make an older reply look current again.

**One key, many secrets.** One Keystore key serves the RPC password and the
watch-only descriptor, and `SecretStore` deleted that key on any failure - so a
single entry whose AES-GCM tag did not match wiped the other secrets on the way
out. A failure is now read at its own scope: `AEADBadTagException` means that one
stored value is unusable, so that entry alone is discarded and the key stays;
`InvalidKeyException` (which includes `KeyPermanentlyInvalidatedException`) and
`UnrecoverableKeyException` mean the key itself is gone, and only then is it
replaced - it decrypts nothing that was still readable. A value that cannot be
encrypted at all stays in memory for the process, as before. The store is also
locked, since the UI thread, the start path and the descriptor scan all reach
the same key and map, and `NodeSettings`' writers are `@Synchronized` so two
unlocked `_state.value = load()` calls cannot publish a half-applied pair.

**Tor that does not leak.** The switch wrote `proxy=127.0.0.1:9050` and left
`listen=1` and `dnsseed=1` in place: an inbound socket advertises the phone's
own address to every peer, and the DNS seeds are resolved by hostname in the
clear before any connection is proxied. With Tor on, `bitcoin.conf` now carries
`listen=0` and `dnsseed=0` as well, so the node keeps no clearnet listener and
resolves no seed; it reaches the addresses Core already carries (fixed seeds,
`peers.dat`) through the proxy. Turned off, both return to `1`. `NodeConfigTest`
asserts both states for all three networks, and the SETTINGS text says what the
switch now does.

### In-place upgrade APK for the installed app

| Item | Value |
| --- | --- |
| Package | `com.jbitmstr.pocketcore` |
| Version | 1.0.0 (versionCode 15) |
| Build | `:app:assembleDebug` of the same source (`d440215`) in a temporary copy of the tree, with `applicationId` and `versionCode` changed there only - nothing of this build is committed |
| APK | `pocketcore-update-existing.apk`, 17,097,202 bytes, SHA-256 `f626775849581c49f3be4aa79f71574ea983ad02d8ddbc21ad6a6cf7e443260f` |
| Signing | the Android debug key, certificate SHA-256 `f5:07:48:15:c3:5c:6b:87:b2:5c:39:32:df:22:83:48:86:8a:cb:b3:18:70:e2:01:18:35:66:ea:2a:a9:c0:11` - the same certificate as versionCode 9-13, so it updates in place and keeps the synced chain |
| Daemon | identical bytes to the release build above (checked inside the APK) |

### Build environment

The container's passwd entry gives the build JVM `user.home=?`, so the Android
tooling resolved the debug keystore against a relative `?` and failed
`packageDebug`. The builds above ran with `ANDROID_USER_HOME=/workspace/.android`
(where the debug keystore lives) and, for the same process cap as versionCode
12-13, `--no-parallel --max-workers=1`, `ActiveProcessorCount=1`, SerialGC and
`kotlin.compiler.execution.strategy=in-process`. The flags live in the build
command, not in the repository; no source or build script behaviour differs
because of them.

## versionCode 12 — second security review, and the all-peers list

Built on 2026-11-22 from `main` at `6ab9cc6`. This table is the `docs/`-only
commit that follows it, so the APKs were produced before the section existed and
no build output is part of the commit. The daemon is unchanged from versionCode
10: same source, same NDK, same bytes, now checked at run time against a hash
recorded at build time.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` |
| Version | 1.0.0 (versionCode 12) |
| Release APK | `app-release.apk`, 8,467,755 bytes, SHA-256 `1cc206d7a3d36579afe57284028cdd3a8c2a750f14e19fef67a4d36f5cf164bd` |
| Debug APK | `app-debug.apk`, 17,078,530 bytes, SHA-256 `aff328bffa6badfc285a9c3123a32b27635a6711f3b9fc5680f200322185516a` |
| Signing (release) | `keystore/pocketcore-release.jks`, alias `pocketcore`; v1 (JAR) signature off, v2 on; certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A` |
| Daemon in the APK | `lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, SHA-256 `6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02` (unchanged) |
| Daemon hash in the app | `BuildConfig.BITCOIND_SHA256`, computed by Gradle from that file; `BitcoindNode.prepareBinary()` verifies the extracted copy before executing it |
| Lint | 0 errors, 16 warnings (`abortOnError = true` since this build) |
| Unit tests | 107 passing in `testDebugUnitTest` |

The release checklist that produced this build was
`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`,
all green.

### In-place upgrade APK for the installed app

The app installed from the versionCode 9-11 debug APKs keeps the package
`com.jbitmstr.pocketcore`, so those installs need a build with that package name:

| Item | Value |
| --- | --- |
| Package | `com.jbitmstr.pocketcore` |
| Version | 1.0.0 (versionCode 13) |
| Build | `:app:assembleDebug` of the same source in a temporary copy of the tree, with `applicationId` and `versionCode` changed there only - nothing of this build is committed |
| APK | `pocketcore-update-existing.apk`, 17,078,314 bytes, SHA-256 `77e260ea1bf280f6daeec51b484d7342e7b04cfbe1b65a7591144362bb0d451a` |
| Signing | the Android debug key, certificate SHA-256 `f5:07:48:15:c3:5c:6b:87:b2:5c:39:32:df:22:83:48:86:8a:cb:b3:18:70:e2:01:18:35:66:ea:2a:a9:c0:11` - the same certificate as versionCode 9-11, so it updates in place and keeps the synced chain |
| Daemon | identical bytes to the release build above (checked inside the APK) |

## versionCode 10 — the Google Play build

Built on 2026-11-21 as the first bundle for Google Play, after the Android
package moved and the build switched from the debug key to a release keystore.

| Item | Value |
| --- | --- |
| Package | `com.corejbh.pocketcore` (`com.jbitmstr.pocketcore` up to versionCode 9) |
| Version | 1.0.0 (versionCode 10) |
| Bundle | `pocketcore-1.0.0-release.aab` |
| Bundle size | 13,335,360 bytes |
| Bundle SHA-256 | `831516f970a5331d068ddad6dbde3dee5f87f59883fee0f660ad80b5a1bc5527` |
| Signing | `keystore/pocketcore-release.jks`, alias `pocketcore`, 2048-bit RSA, SHA256withRSA, valid 10,000 days (to 2054-04-08), certificate SHA-256 `92:86:76:E8:A8:CA:DE:19:D9:F9:C7:68:4A:4E:32:44:C4:45:D3:4B:0A:9D:29:9E:AB:07:42:C4:F6:13:E3:9A`; `jarsigner -verify` reports `jar verified` (self-signed upload key, no timestamp) |
| Native code in bundle | `base/lib/arm64-v8a/libbitcoind.so`, 10,729,904 bytes, still the stripped daemon `6becfe26...a8c74f02` |
| Lint | 0 errors, 16 warnings |
| Unit tests | 93 passing in both `testDebugUnitTest` and `testReleaseUnitTest` |

Because `applicationId` changed, this build does not update an installed debug
APK: it installs beside it with its own data directory. The keystore directory
and `keystore.properties` are git-ignored; a clone without them still builds
every variant, and only the release bundle is left unsigned.


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
| Version | 1.0.0 (versionCode 9) |
| minSdk / targetSdk | 26 / 35 |
| APK | `pocketcore-1.0.0-debug.apk` |
| APK size | 17,216,155 bytes |
| APK SHA-256 | `872b8a6c406574ce15852ce0b1d895ffa08cb4b08f65f9e8f80b153379daabce` |
| Signing | debug key `f5074815...2aa9c011`, APK Signature Scheme v2 verified, the same key as versionCode 2 to 8 (installs as an update) |
| Native code in APK | `lib/arm64-v8a/libbitcoind.so`, byte-identical to the stripped daemon (`6becfe26...a8c74f02`) |
| Offline GeoIP asset | `assets/ipv4_country.bin`, 1,104,026 bytes (559,749 inside the APK), 245 countries, 362,998 ranges |
| Lint | 0 errors, 16 warnings, 2 information |
| Unit tests | 93 passing (`NodeModelsTest` 8, `FormatTest` 5, `ExplorerModelsTest` 8, `DescriptorsTest` 7, `RawTransactionTest` 6, `IpCountryIndexTest` 7, `AddressCodecTest` 5, `PortProbeTest` 4, `HeaderSyncTest` 7, `NotificationCardTest` 11, `PortResolverTest` 7, `ProcScanTest` 6, `RpcAuthTest` 4, `VersionProbeTest` 3, `NodeConfigTest` 5) |
| zipalign | `zipalign -c -p 4` passes |

### versionCode 9 - the security review: no credentials to a stranger, no credentials on screen

A code review of the whole app produced this build. Nine of the findings were
security or stability defects in the daemon plumbing; the rest of the work came
from the phone, where the NODE and SETTINGS screens turned out to carry RPC
credentials nobody wanted to see, the `bitcoind` row showed a filesystem error
instead of a version, and the storage card offered no way to keep the full chain.

**The RPC credential leak (the reason this build exists).** `resolvePorts()` used
to send `stop` - an HTTP Basic Auth call carrying `rpcUser` and `rpcPassword` - to
whatever process held the default RPC port, before it knew whose process that was.
A second node app holding 8332 would have received this app's RPC credentials.

- `ProcScan.kt` reads the facts the decision needs out of `/proc`: the real UID of
  a process (whole tokens - the old `uidLine.contains(uid)` matched UID 101234 for
  UID 10123) and whether one of its argv entries is `libbitcoind.so`. Android hides
  other UIDs' processes, so anything this scan can see belongs to this app.
- `PortResolver.kt` now holds the whole decision, with the probes injected so it is
  unit tested. Credentials may only go to the port recorded in the `bitcoin.conf`
  this app wrote for its last start, and only while a daemon of this UID is
  running; that is how an orphan of a killed app process is still stopped
  gracefully. A daemon found without a recorded endpoint is killed rather than
  asked. A port held by anyone else is never contacted: the node moves to a free
  port and the credential pair is regenerated for that start (Core's stop request
  is the only place a password is transmitted at all, and now it can only reach
  our own process).
- One more hole in the same place: our daemon can sit on a moved port while
  another app holds the default one, so "a daemon of ours is running" was not
  proof that the default port was ours. The recorded port closes that.
- `bitcoind.conf` no longer carries the password. The daemon is given
  `rpcauth=<user>:<salt>$<hmac>`: a fresh random salt per start and
  HMAC-SHA256(salt, password), which Core's `CheckUserAuthorized()`
  (`src/httprpc.cpp` v32.1) recomputes from the plain pair the app sends. The
  client side is unchanged; the file on disk is a hash.
- Verified against a real Bitcoin Core 32.1, not just in theory:
  `tools/verify-rpcauth.py` writes the conf this app generates (one `rpcauth`
  entry, no `rpcuser`/`rpcpassword`) on regtest, starts the daemon and talks to it
  with the plain pair - `HTTP 200 chain = regtest` - and gets `HTTP 401` for a
  wrong password. Core's log confirms `Using rpcauth authentication.`
- The password itself is now stored by `SecretStore`: AES-256-GCM under a
  Keystore key (`pocketcore-rpc-credentials`), a fresh IV per write, in
  `SharedPreferences` under `rpc_password_enc`. The plaintext key of earlier builds
  is migrated on first read, so an upgrade keeps the same password and the same
  running daemon. If a device has no usable Keystore the value is written in the
  clear rather than lost.

**Nothing on screen to copy.** The `LOCAL JSON-RPC` card on NODE and the
`RPC ACCESS` card on SETTINGS both showed the user, revealed the password on
request and copied either - and `COPY BITCOIN.CONF` copied the pair too. All of
it is gone: `NodeScreen` no longer imports `NodeConfig`, `SettingsScreen` no
longer offers a rotate or copy control, and `NodeConfig.clientConf()` and
`rpcSnippet()` are removed with them, so no code path in the app can put
`rpcuser`/`rpcpassword` on the clipboard. Inside the APK that is checkable:
`classes*.dex` contains `rpcauth=` and no `rpcpassword=`/`rpcuser=` string at
all. The `copyToClipboard(isSensitive)` flag stays for future sensitive copies
(Android 13's `EXTRA_IS_SENSITIVE`); the descriptor and debug-log copies still use
the plain path.

**The NODE screen, cut down to what it is for.** Top to bottom: the block-height
card, START and STOP (RESTART and the SYNC shortcut are gone; one of the two is
enabled depending on the state), the peer list, and the node card. The four stat
tiles (peers, mempool, chain data, uptime) and the RPC card were removed.

**`bitcoind` version.** The node card showed
`Error: filesystem error: in create_directories: Read-only file system ["/.bitcoin"]`.
`libbitcoind.so -version` resolves its data directory from `$HOME` before it prints
anything, and an app process has no writable home. `VersionProbe` now hands the
process `HOME` = the app's files directory, `TMPDIR` = its cache directory and an
explicit `-datadir`, and reads the version line out of the output instead of the
first line. Reproduced on the host: with `HOME=/` the daemon prints the error and
no version; with the probe's environment it prints
`Bitcoin Core daemon version v32.1.0 bitcoind`, i.e. `v32.1.0` on screen. A cached
stamp that holds an error rather than a version is discarded.

**Full chain storage.** The storage card gained `Full` (`prune=0`, every block
kept) next to 550 MB, 2 GB and 5 GB, with the mainnet requirement spelled out
(about 750-800 GB, and it has to stay free while the chain is verified) and a
warning banner when the phone's free space is below it.

Going the other way is not free. Core records that a block file was deleted
(`m_have_pruned`) once it prunes, and refuses to open such a database unpruned
(`src/node/chainstate.cpp` v32.1: `m_have_pruned && !options.prune` ->
"You need to rebuild the database using -reindex to go back to unpruned mode.
This will redownload the entire blockchain"). A pruned datadir that never grew
past its target carries no such flag and does switch - checked on a real 32.1,
where `prune=550` -> `prune=0` on a small regtest chain came up with
`Setting NODE_NETWORK in non-prune mode`. So the app asks only when the chain is
already bigger than its prune target: tapping Full then deletes the chain data
(`NodeController.rebuildChainAsFull()`), which is what `-reindex` would do
anyway, and the next start downloads the chain from genesis. `NodeConfig.content()` is
now a pure function so `NodeConfigTest` can assert `prune=0`, `prune=550`,
`prune=5120`, the `rpcauth` line, the absence of the password and where
network-only options sit.

**The rest of the review.**

- `evaluatePolicy()` runs entirely under the controller mutex, so `policyPaused`
  and the daemon can no longer disagree after a gate change races a start.
- `NodeService` holds its `PARTIAL_WAKE_LOCK` only in STARTING and RUNNING; a node
  that waits for Wi-Fi or a charger no longer keeps the CPU awake.
- `ExplorerController` cancels the previous lookup job when a new block,
  transaction or broadcast starts, and `isPruned()` spells out its parentheses
  (`(code == -1 && pruned) || "Block not available"`).
- `IpCountryIndex.parse()` checks the cursor and the varint shift at every step
  (a truncated table, a varint past 32 bits, a range beyond the IPv4 space and a
  missing country byte all throw, and the loader falls back to an empty index).
- `Descriptors` splits a descriptor into tokens and refuses a WIF key inside
  `pk(...)`, `wpkh(...)`, `tr(...)`, `sh(wpkh(...))` and friends, while an xpub
  that happens to carry a `K` is still accepted (an xpub is not split).
- `BitcoindNode.killStrayProcesses()` compares the real UID as a token, so another
  app's daemon is never killed, and `ProcScan.runsDaemon()` keeps the app's own
  `-version` probe out of the stray list (it runs the same binary for a moment at
  launch, and killing it would cost the version line of that launch).
- `NodeConfig`'s comment no longer claims the ports are passed on the command line
  (they are not: the file's network section is the only place), and `[testnet4]`
  is written correctly in it.
- EXPLORE placeholders show real shapes: `840000 · 000000000019d668...`,
  `4a5e1e4baab89f3a... (64-char txid)`, `020000000001... (raw signed tx hex)`.

**Tests added** (65 -> 93): `PortResolverTest` (7: the foreign-port bypass is
asserted to send no stop request, our own daemon is stopped on the port the conf
records, a stop request that is not answered still clears the stray, P2P pairs,
the RPC/P2P collision branch), `ProcScanTest` (5: exact UID tokens, argv matching,
only this UID's daemons, unreadable entries), `RpcAuthTest` (4: the HMAC vectors
`share/rpcauth/rpcauth.py` produces, the `user:salt$hash` shape, salt characters,
a fresh salt per start), `VersionProbeTest` (3: version out of a noisy output, a
cached error rejected, the writable HOME/-datadir command), `NodeConfigTest` (5:
prune 0/550/5120, no password in the file, ports inside the network section),
plus `DescriptorsTest` and `IpCountryIndexTest` cases for the new checks.

**Build.** versionCode 9, versionName 1.0.0, same debug key
`f5074815...2aa9c011` as versionCode 2 to 8 (`apksigner` v2 verified),
17,216,155 bytes, SHA-256
`872b8a6c406574ce15852ce0b1d895ffa08cb4b08f65f9e8f80b153379daabce`, 93 unit tests
passing, lint 0 errors / 16 warnings, `zipalign -c -p 4` passing,
`libbitcoind.so` inside the APK still `6becfe26...a8c74f02`. `docs/preview.png`
still shows the versionCode 8 NODE tab (stat tiles, RPC card); it is replaced from
a device capture, which this build cannot take.

### versionCode 8 - the launch halo that is no longer cut, no pulse, and a card signboard

Reported from the phone: the cyan halo behind the launch tile looked cut off in
a straight line above and below the tile, and the mark kept breathing in and out
while the splash was up.

**Why the halo was cut.** `SplashScreen.kt` drew the halo with `drawBehind` on
the tile's 120dp `Box`, at `radius = size.minDimension * 0.82f`, i.e. 38dp past
the box on every side. The box sits inside the `Column` that carries the fade,
`Modifier.graphicsLayer { alpha = alpha.value }`, and a Compose layer is clipped
to the bounds of the composable that owns it - so the part of the glow above and
below the box was cut exactly where the column began. (The screenshot shows it:
the halo is wider than the tile but its top and bottom are straight.)

**The fix.** The halo is no longer drawn by the tile. The overlay's own
`Box` - the full-window node whose layer *is* the screen - draws one
`Brush.radialGradient` centred on the tile, with the tile's centre resolved in
the draw phase from `LayoutCoordinates` (`overlay.localPositionOf(tile, ...)`,
so the callback order between child and parent cannot matter; until the first
placement has been reported the halo falls back to the window's centre rather
than disappearing). The gradient
reaches full transparency 150dp from the centre, 90dp clear of the tile's edge,
and its stops are 0.20 / 0.175 / 0.108 / 0.046 / 0.012 / 0 at 0.00 / 0.30 /
0.46 / 0.62 / 0.82 / 1.00. Its alphas are multiplied by the overlay's current
fade, so the halo appears and disappears with the rest of the splash, and it
meets no straight edge on a 360dp-wide phone.

**No more pulse.** `rememberInfiniteTransition` with `RepeatMode.Reverse` and a
1.00 <-> 1.035 scale is gone. The mark now eases from 95% to its final size once
over 620 ms (`LinearOutSlowInEasing`), holds still, and the overlay fades out over
260 ms - the same 1.1 s in total, without anything moving under the eye after the
entrance.

**README signboard, redrawn.** `docs/hero-splash.png` is now a small RGBA card
instead of a 1600 x 860 rectangle: `#050912` fill, 32px rounded corners, a
`#1E293B` hairline, and a transparent surround, so GitHub's dark (`#0d1117`) and
light (`#ffffff`) themes both show a deliberate shape rather than a black
rectangle floating on the page. The first attempt drew a 900 x 760 card and left
a wide empty band above the tile and below the description, because the layout
was measured from `getbbox()` of a layer that still held the halo, so glow pixels
with alpha 1-2 set the bounds. The card is now cut from the artwork: the tile,
`POCKETCORE` and the one-line description are drawn on their own layer, measured
by their visible footprint - alpha above the field's own noise, so neither the
rounded corners' near-transparent pixels nor the glow can enlarge or shift it -
and the card is that footprint plus one even 56px band on all four sides, which
lands at 559 x 548 with the name 447px wide. The halo is drawn afterwards, one
notch quieter (peak alpha 0.19), with its own steeper curve spread over 0.97 of
the clear space from the tile's centre to the nearest card edge, so it still
reads beside the tile and reaches zero strictly inside the card instead of being
cut by the edge. `README.md` now opens with the card at `width="280"`, aligned
left like the rest of the page rather than centred, with the lede sentence and
the badge row following it flush left.

**README text.** The body now leads with what the phone verifies rather than with
the UI: a table of SHA-256d, proof of work, `libsecp256k1` (ECDSA and BIP340
Schnorr), the script interpreter and BIP324 v2 transport, each with the file it
comes from and the decision it settles, plus the watch-only `scantxoutset` path
that keeps every private key out of the app. The install text no longer claims a
newer build needs an uninstall: every build since versionCode 2 shares the debug
key `f5074815...2aa9c011`, so a newer APK installs over the old one as an update,
and uninstalling is what would delete the synced chain.

**Repository.** The GitHub account was renamed `JBITmstr` -> `CoreJBH`; the
remote, every Releases link and the contributor line follow it. The Android
package stays `com.jbitmstr.pocketcore`, because changing `applicationId` would
install a second app alongside the first and orphan the node data of everyone who
already has it.

**Build.** versionCode 8, versionName 1.0.0, debug key `f5074815...2aa9c011`,
APK Signature Scheme v2 verified, 17,061,607 bytes,
SHA-256 `da080f626091983f34f3b3bfb3e02039b0ea9e44d9e47904ee02d0a18bd2bffc`.
65 unit tests pass, lint reports 0 errors / 16 warnings, `zipalign -c -p 4`
passes. `libbitcoind.so` inside the APK is still `6becfe26...a8c74f02`, the
daemon untouched.

### versionCode 7 - a larger launch screen and the README signboard

Reported from the phone: the launch screen's tile carried so much inner padding
that the tesseract looked small inside it. The mark was 48.6% of the tile, and
the tile itself was drawn in a 96dp box, so on a 360dp-wide phone the mark read
at about 47dp.

**Tile.** `tools/generate-icons.py` now has a separate launch-screen geometry
from the launcher icon. `splash_icon.png` is 768 px (was 512) and draws the mark
at **72.9%** of the tile instead of 48.6%: in the file the mark measures
610 x 560 px at 149, 105 - 619, 664, i.e. 61.3% wide, 72.9% tall, with a 13.5%
margin to every edge. The corner radius is 32/120, still the ratio
`SplashScreen.kt` clips with, and the tile is 3x the 480 px a 120dp box needs at
xxxhdpi so the enlarged mark stays sharp. The launcher icons (`ic_launcher_*`),
the themed-icon layer and `docs/icon.png` are unchanged: they keep the 50%-of-
canvas safe-zone geometry from versionCode 6, and the regenerate run reproduced
them byte for byte.

**Splash.** `ui/SplashScreen.kt` draws the tile in a 120dp box (was 96dp) and
clips it at `RoundedCornerShape(32.dp)` (was 26dp). Together with the tile change
the mark goes from ~47dp to ~88dp on a 360dp phone; the cyan halo keeps its
0.82 x box radius and 0.22 alpha, so it grows with the tile instead of turning
into a ring. Nothing else on the launch screen moved: same 1.1 s hold, same fade
and pulse, POCKETCORE at 24sp with 5sp tracking, the one-line description below.
`docs/splash-preview.png` is redrawn at the new size (360dp x 780dp, 3x).

**README signboard.** `docs/hero-splash.png` (1600 x 860) is the launch-screen
composition drawn wide: the halo, the enlarged tile, POCKETCORE and
`Your mobile Bitcoin full node` on the `#050912` canvas, rendered at 2x and
reduced with LANCZOS by `hero_banner()` in the same generator, so the signboard
is reproducible from the artwork rather than a one-off export. `README.md` opens
with it in place of the small logo and the duplicated phone screenshot;
`docs/preview.png` stays in the Screens table next to SYNC, EXPLORE and SETTINGS,
with the notification card below them.

**Build.** versionCode 7, versionName 1.0.0, same debug key as versionCode 2 to 6
(v2 scheme verified with `apksigner verify`, certificate
`f5074815...2aa9c011`), 65 unit tests passing, lint
0 errors / 16 warnings, `zipalign -c -p 4` passing. The APK grew from 16,904,831
to 17,060,531 bytes, all of it the larger, less compressible splash tile;
`libbitcoind.so` inside the APK is still `6becfe26...a8c74f02`, and the
`splash_icon.png` in the APK is byte-identical to the file in `res/`.

### versionCode 6 - tesseract launcher icon

After the versionCode 5 build the icon was replaced for the fourth time; the
final pick is `downloads/pocketcore-icon-tesseract2.png` - an isometric
tesseract (a cyan cube frame around a smaller cube with a glowing blue core) on
the deep navy field `#030B27`, with no coin glyph anywhere.

**Launcher.** `tools/generate-icons.py` now reads that file. The mark measures
510 x 609 px at 372, 313 - 881, 921 in the 1254 px artwork, is cropped to a
621 px square around it (ratio 0.9807) and drawn at exactly 50% of the 108dp
canvas height, i.e. 54dp. The adaptive foreground is a real alpha layer: each
pixel keeps its artwork colour and gets the coverage that recreates it over the
field (solved against a white ceiling, so nothing is clipped or flattened onto
one ink colour). Compositing the saved layer back over `#030B27` reproduces the
crop with a mean error of 0.50/255, p99.9 of 2.0 and no channel above 5/255.
`ic_launcher_background` moved from `#010817` to `#030B27` so the glow fades
into the same navy the artwork was drawn on instead of a slightly different
square. The legacy `ic_launcher.png` / `ic_launcher_round.png` keep the opaque
field. On mdpi only, the alpha is lifted with a 0.85 gamma, because a 48dp icon
on a 160dpi screen gives the ~1.5dp frame lines barely half a pixel each; the
mark's shape and box are unchanged.

`tools/check-icon-safezone.py` reproduces the launcher for every density under
the circle, squircle and rounded-square masks, with and without a 1.08 parallax
zoom, and reports **0 clipped mark pixels**.

| Density | Canvas | Mark box (visible 72dp window) | Margin to the window edge |
| --- | --- | --- | --- |
| mdpi | 108 | 13, 9 - 57, 61 | 13.0 / 9.0 / 14.0 / 10.0 dp |
| hdpi | 162 | 20, 13 - 87, 93 | 13.3 / 8.7 / 13.3 / 9.3 dp |
| xhdpi | 216 | 27, 18 - 116, 125 | 13.5 / 9.0 / 13.5 / 9.0 dp |
| xxhdpi | 324 | 40, 27 - 174, 188 | 13.3 / 9.0 / 13.7 / 9.0 dp |
| xxxhdpi | 432 | 54, 37 - 233, 251 | 13.5 / 9.3 / 13.5 / 9.0 dp |

**Themed icon.** `drawable-nodpi/ic_launcher_monochrome.png` is the same alpha
layer in white, so Android 13+ tints the tesseract itself instead of a pasted
square (no glyph to remove: the artwork never had one).

**Splash.** `drawable-nodpi/splash_icon.png` is a 512 px raster tile: the
`#030B27` field with the mark at 48.6% of the tile, corners rounded at 26/96 -
the same radius `SplashScreen.kt` clips the 96dp box with, so the raster edge
and the Compose clip land on the same curve. It is still a plain PNG; nothing
references `R.mipmap.ic_launcher` from the splash.

**Notification.** `drawable/ic_stat_node.xml` was redrawn as the tesseract
silhouette: the outer cube frame (1.25dp), the inner cube frame (1.15dp) and the
six edges joining their corners (1.0dp, round caps). The core glow cannot exist
in a tinted status-bar glyph, so the middle stays open. App code did not change:
`NodeService` still calls `setSmallIcon(R.drawable.ic_stat_node)`, and
`SplashScreen`, the theme, the insets and every feature are untouched.

**Build.** `tools/build.sh` now passes `-Duser.home=/workspace` to Gradle. This
container has no passwd entry for the build user, so the JVM reported
`user.home` as `?` and the debug signing config looked for
`app/?/.android/debug.keystore`; pinning it keeps the build on the existing
`~/.android/debug.keystore` and therefore the same signing key. Verified on this
build: 65 unit tests passing, lint 0 errors / 16 warnings, `assembleDebug`,
`apksigner verify` - v2 scheme true, certificate `f5074815...2aa9c011`,
`zipalign -c -p 4` passing. The APK shrank from 17,151,385 to 16,904,831 bytes
because the new icon assets compress better; `libbitcoind.so` inside the APK is
still `6becfe26...a8c74f02`, and the icons inside the APK are pixel-identical to
the files in `res/`.

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

## Rebuilding the daemon from source

`scripts/build-bitcoind-android.sh` is the whole cross build, written down. It
takes Bitcoin Core v32.1 from the official `bitcoincore.org` tarball (SHA-256
`a5f2f61e...7813f9`), Boost 1.86.0 headers from `archives.boost.io` (SHA-256
`2575e74f...1ca01f`), and NDK r30, configures the same aarch64-linux-android26
Release build described above with wallet, GUI, IPC, external signer, ZMQ,
USDT, tests, bench and the `bitcoin`/`bitcoin-cli` executables off, builds
`bitcoind`, runs `llvm-strip --strip-all` and writes
`app/src/main/jniLibs/arm64-v8a/libbitcoind.so`.

For a build machine without network, pass the inputs instead of downloading:
`BITCOIN_SRC=<checkout of v32.1> BOOST_SRC=/usr/include ANDROID_NDK_HOME=<ndk>`.
That is how the F-Droid recipe calls it (`docs/fdroid/`).

Reproducibility, measured rather than assumed:

- Relinking the object files of the 2026-11-21 build in its own build directory
  reproduces `bitcoind` exactly: stripped SHA-256
  `6becfe26...a8c74f02`, 10,729,904 bytes.
- The same source, NDK and flags built in a different build directory produce a
  file of the same size that differs in 18 bytes: the 20-byte
  `.note.gnu.build-id`, which the linker derives from the linked output and the
  build path inside it. Everything else - the ELF layout, `.text`, `.rodata`,
  the interpreter and the Android ident note - is identical.

A fresh checkout therefore reproduces the daemon except for that one note; the
script prints the comparison and only fails on a difference when
`STRICT_HASH=1` is set.

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
