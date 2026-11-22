# Mobile architecture: what a phone node costs, and how this app keeps it light

PocketCore runs the real Bitcoin Core daemon on the phone, so the costs are the
daemon's costs: storage, CPU for validation, a socket for each peer, and a CPU
that keeps running while the screen is off. This page writes down the three
places where the current defaults are heavier than a phone needs, and the
concrete alternatives for each. It is a design note: the numbers are measured
or quoted from the sources named beside them, and every proposal names the file
it would touch, so a change is a patch and not a rewrite.

The three findings:

1. `listen=1` is the default, which on a mobile network almost never produces an
   inbound peer.
2. `scantxoutset` walks the whole UTXO set every time it is asked, and the watch-
   only card can ask again for the same descriptor.
3. The foreground service holds an unbounded `PARTIAL_WAKE_LOCK` for as long as
   the node is STARTING or RUNNING, and polls RPC every 2.2 seconds.

## 1. Storage: what a node actually needs

The old README said "about 1 GB free storage", which is the size of a pruned
block set and not of the node. Measured figures:

| Item | Size | Source |
| --- | --- | --- |
| Mainnet UTXO set (chainstate) at block 935,000 | 13,290,295,029 bytes (`disk_size`), 164,241,311 coins | Bitcoin Core `gettxoutsetinfo` in bitcoin/bitcoin#34677 (2026-02-26) |
| Mainnet UTXO set at the tip (block 977,085) | 11.52 GB | bitcoinstats.io/chainstate/utxo (2026-11-22) |
| Mainnet snapshot file at 965,000 | 8.8 GiB | utxo.download (`mainnet-965000-utxos.dat`) |
| Signet snapshot at 320,000 | 3.0 GiB | utxo.download |
| Testnet4 snapshot at 150,000 | 829 MiB | utxo.download |
| Full chain, every block (`prune=0`) at 977,085 | 894.93 GB | bitcoinstats.io |

So the honest numbers for the app:

- A **pruned** mainnet node (`prune=550`, the default) is not 550 MB: it is
  550 MB of blocks **plus the chainstate**, which is the UTXO set in Core's
  leveldb layout - about 12-13 GB on mainnet today. Plan on **~14 GB free** for a
  pruned node, and that space has to be free while the chain is verified, not
  only afterwards.
- A **full** node (`prune=0`) needs **~890-900 GB** on mainnet, not 750-800 GB.
- **Importing** a snapshot copies the file into the app's own storage
  (`<app files>/node/snapshots/`), so it needs the file's size **plus** the free
  space the copy itself takes: 8.8 GiB of file means ~9 GB free before the picker
  is even worth opening.
- **Loading** a snapshot (`loadtxoutset`) is the expensive moment: Core writes a
  whole new chainstate (`chainstate_snapshot/`) and keeps the existing one until
  background validation catches up, so the phone has to hold **both**. The load
  check therefore asks for a chainstate, not for the file that is already on disk
  (`StorageBudget.requiredLoadBytes`, versionCode 21): the larger of the
  snapshot's size and the chainstate already on the phone, plus the 64 MB
  reserve. On a mainnet phone that is ~13 GB free at the instant LOAD is pressed,
  and after it the phone carries about 35 GB of node data (the 8.8 GB file, the
  13 GB chainstate that was already there, and the 13 GB snapshot chainstate)
  until the background validation has caught up and the file is deleted.

What the app does with that: `SYNC -> LOAD SNAPSHOT` refuses up front with the
numbers, the load's own message says "a second chainstate of about 13.0 GB
beside the 13.0 GB chainstate already on the phone", and an import deletes the
previous `.dat` so the folder never keeps every snapshot ever imported.

Possible follow-ups, in order of value:

| Change | Touchpoint | Gain | Cost / risk |
| --- | --- | --- | --- |
| Show the chainstate size on the STORAGE card, from `gettxoutsetinfo.disk_size` (already a real RPC, ~seconds while not syncing) | `ExplorerController`, `SettingsScreen` | the card stops describing the block set alone | one slow RPC; cache the last answer |
| Offer "delete the snapshot file after a successful load" as the default | `NodeController.loadSnapshot` | frees ~9 GB when the snapshot chainstate is the one being served | the file is needed again after a wipe or a reindex |
| Estimate a pruned node's real footprint as `prune + chainstate` on the storage card | `SettingsScreen`, `StorageBudget` | the 550 MB setting stops looking like 550 MB of node | the chainstate figure needs the RPC above |

## 2. Inbound peers: `listen=1` on a mobile network

`NodeConfig` writes `listen=1` unless Tor routing is on. The daemon then binds
the P2P port, advertises it to every peer it meets, and waits for someone to
connect back. On a phone that almost never happens:

- **Carrier NAT and CGNAT** do not forward an unsolicited inbound connection.
  The node's address is not routable, the mapping expires, and Core ends up with
  the same outbound peers it would have had with `listen=0`.
- **Wi-Fi behind a home router** is the same unless the owner sets a port
  forward, and the phone's address changes with the network.
- What the bound socket does buy: a little "am I reachable" state in
  `getnetworkinfo`, and an inbound path that works exactly in the rare case of a
  forwarded port.

Alternatives, all of which the daemon already supports:

**A. Default `listen=0`, keep outbound connections (recommended).** Write
`listen=0` alongside `discover=0` (already written) and let the node run with its
8 outbound peers (`maxconnections=8`). `NodeConfig.content()` is the only place
that writes the line, and `NodeConfigTest` already asserts both states for all
three networks, so the change is one condition plus its test. Core does not
require a listener: a node with `listen=0` still relays, still validates, and
still downloads blocks.

**B. A SETTINGS switch, "ACCEPT INBOUND PEERS", default off.** The same line
becomes `listen=<0|1>` from a setting, and the text beside it says what makes it
work: a Wi-Fi network whose router forwards the P2P port to the phone, or Tor
onion service mode. `PortResolver` already has the "no P2P listener" path
(`p2pListener = false` when Tor is on) that skips probing, moving and reserving
the P2P port; the switch feeds the same flag, so a `listen=0` node stops
reserving a port it will not bind.

**C. Make Tor the real "reachable" mode.** An Orbot hidden service
(`HiddenServicePort 8333 127.0.0.1:8333`) gives the node inbound peers that can
actually reach it, without exposing the phone's address. The app already writes
`proxy=` when Tor routing is on; the missing piece is the settings text that
tells the user how to add the hidden service in Orbot, and `listen=1` only for
the loopback side.

Trade-offs to state in the UI: an inbound-capable node helps the network (more
reachable peers), costs battery for sockets that are mostly idle, and on a phone
that is offline half the day contributes little. Outbound-only is the honest
default for a device behind NAT.

## 3. The watch-only scan: `scantxoutset` is a full walk

`DescriptorScanController.scan()` calls `scantxoutset start` for the descriptor,
then polls `scantxoutset status` every 1.5 s for progress. Each scan walks every
unspent output on the phone - 164 million of them on mainnet today - which is
minutes of one core at full tilt, a thermal load the phone feels, and a battery
cost that repeats for every press. Core keeps no index for this: without
`-txindex` or a wallet, `scantxoutset` is the only watch-only path, and running
it again for the same descriptor is running the same walk again.

What the app already does right: a scan is only started by the SCAN button, ABORT
cancels the job before it asks the daemon, a reply that arrives after a cancel is
dropped, and the per-second RPCs are `scanStatus` (cheap) rather than repeats of
the scan itself. The scan is gated like every other chainstate request
(versionCode 21), so it cannot run against a node the user has stopped.

Alternatives, from cheapest to most structural:

**A. Remember the last scan.** Keep the descriptor's checksum, the scan height
and the result. When the SCAN button is pressed for the same descriptor at the
same height, answer from the stored result and say so ("scanned at block N, no
new blocks since"). The state already carries `scanHeight` and `finishedAt`
(`DescriptorState`), so the rule is a pure function beside it, and its own unit
test.

**B. Make a rescan a deliberate act after the chain moved.** Show "the chain is
M blocks past the last scan" on the card, and only offer RESCAN when M is large
enough to matter (say 6 confirmations). No timer, no automatic rescan.

**C. Poll more slowly, and stop polling a scan that is not moving.** 1.5 s is
finer than a 165-million-output walk needs; 3-5 s is the same information. If
`progress` does not move for 60 s, stop polling and show the last percentage.

**D. BIP158 compact block filters for incremental scans.** With
`blockfilterindex=1` (written as `0` today) Core maintains a filter per block, and
`getblockfilter` answers "does this block mention any of my scriptPubKeys" in
milliseconds. A watch-only scan becomes: one `scantxoutset` at the base height,
then filters for the blocks after it, with the UTXO set asked about only the
outputs those filters matched. The cost moves to the index: it is built from
genesis on first start (hours), takes disk in `<datadir>/indexes/blockfilter`
(a few GB on mainnet), and duplicates work the node already did. The gain: a
repeat scan of the same descriptor costs seconds instead of minutes, and new
blocks can be followed without walking the set.

**E. Not doing: a server.** An Electrum or Esplora backend would answer the same
question in milliseconds, and would hand a third party the user's addresses -
exactly what this app exists to avoid. A wallet database is the other shortcut;
the daemon is built with `ENABLE_WALLET=OFF` and stays that way.

## 4. The wake lock: held for the whole run

`NodeService` takes a `PARTIAL_WAKE_LOCK` in every state but WAITING and
STOPPED and holds it with no timeout (`lock.acquire()`), so the CPU stays awake
while the daemon is STARTING or RUNNING - including hours after the chain is
caught up and the node is only waiting for the next block. The RPC poll runs
every 2.2 s for as long as the service lives.

That is the right trade for the initial block download (days of validation) and
the wrong one for a synced node sitting in a pocket, where it costs a few percent
of battery a day for a block every ten minutes.

Alternatives:

**A. A bounded lock that progress renews.** `acquire(10 * 60 * 1000)` and take it
again only from a poll pass that saw the chain or the peer set move (blocks,
headers, `verificationprogress`, peer count). A node that is caught up stops
renewing and lets the CPU sleep between passes; a node that is downloading keeps
the lock without a special case. Touchpoint: `NodeService.acquireWakeLock` and
`NodeController.refreshPass` (which already knows the progress it just read).

**B. A slower poll when the screen is off and nothing is moving.** 2.2 s is for a
screen that shows a height climbing; with the screen off and `blocks == headers`,
15-30 s is the same information, and the notification only has to change when
its text does (`NotificationCard.of` already produces a value that can be
compared). Touchpoint: the poll delay in `NodeController.startPolling` and the
`delay(900)` / `delay(2200)` pair.

**C. Sleep through Doze, and wake for the next block.** When the node is caught
up, release the wake lock and schedule a periodic wake-up (`AlarmManager`
`setAndAllowWhileIdle`, or a `WorkManager` periodic job) that refreshes the poll
and the notification. Doze does not stop a foreground service, but it does stop
the CPU and the network; a node that is not validating anything has nothing to
miss, and the peers reconnect on the next wake-up.

**D. A "charging only" default for the initial download.** The Wi-Fi / charging
policy already pauses and resumes the node (`NodeController.evaluatePolicy`);
turning "Charging only" on by default for the first sync is a settings default,
not a code path, and it turns an eight-hour battery drain into an overnight
charge.

**E. Do not switch the foreground service type.** `specialUse` (with the subtype
`Bitcoin P2P full node: continuous block download and validation`) is the honest
declaration and carries no time limit on Android 15. `dataSync` would fit the
"download" part and carries the 6-hour-per-24-hour cap Android 15 puts on it -
a synced node would be stopped by the system, not by the user.

## What is not negotiable

Whatever changes above, these stay:

- validation is Core's own code, on the phone (no light client, no server);
- RPC is loopback-only, with a generated pair sealed in the Android Keystore,
  and only ever sent to a port proved to be this app's daemon;
- no private key and no wallet database in the app (watch-only by construction);
- the STORAGE figure the app shows is a real figure, never a rounding of the
  block set pretending to be the node.

## What versionCode 21 changed, and what stays a proposal

In code: the snapshot header check (`SnapshotHeader`), the load's space check
for two coexisting chainstates (`StorageBudget.requiredLoadBytes`) and the
sensitive-RPC gate that re-reads the node before and after `loadtxoutset`,
`dumptxoutset` and `scantxoutset` (`SensitiveRpc`). The sections above that
still describe the current behaviour - `listen=1`, the full `scantxoutset` walk
and the unbounded wake lock - are proposals with their touchpoints, and the
README says so where it points here.
