# PocketCore Privacy Policy

**App:** PocketCore — Bitcoin Core full node for Android
**Package:** `com.corejbh.pocketcore`
**Developer:** CoreJBH
**Effective date:** 21 November 2026

## The short version

PocketCore collects **no personal data**. There is no account, no analytics, no
crash reporting, no advertising, and no third-party tracking SDK of any kind.
Everything the app knows stays on your phone.

## What the app does not collect

- **No personal information.** PocketCore never asks for, reads or transmits your
  name, email address, phone number, contacts, calendar, photos, files, precise
  location, advertising ID or any other identifier.
- **No analytics or telemetry.** There is no usage tracking, no event logging, no
  session recording and no A/B experimentation library in the app.
- **No crash reports.** If the app or the node stops, nothing is uploaded. Logs
  and `debug.log` remain in the app's private storage on the device.
- **No advertising and no tracking SDKs.** The app contains no ad network, no
  attribution SDK, no social login SDK and no push notification service. It links
  to no third-party service at runtime other than the Bitcoin peer-to-peer
  network described below.
- **No wallet and no keys.** PocketCore is a node, not a wallet. It does not
  create, store or accept private keys, seed phrases or WIF strings, and the
  bundled daemon is built with `ENABLE_WALLET=OFF`.

## Network connections the app makes

PocketCore makes exactly two kinds of connection, both to do the one job the app
has — validating the Bitcoin blockchain itself.

1. **Bitcoin peer-to-peer.** The bundled Bitcoin Core daemon connects directly to
   other Bitcoin nodes to download block headers and blocks and to relay
   transactions, using Bitcoin's encrypted BIP324 v2 transport where the peer
   supports it. This is the same traffic any Bitcoin node produces: IP addresses
   of peers, protocol messages, and the public blockchain data itself. It is not
   sent to the developer or to any server the developer controls, and the app
   sends no device identifiers along with it.
2. **Loopback RPC.** The app talks to its own daemon over `127.0.0.1` with a
   randomly generated username and password that never leave the device. The RPC
   port is bound to loopback only and is not reachable from the LAN or the
   internet.

The app makes **no HTTP requests of its own**. Android's network security
configuration allows cleartext traffic to `127.0.0.1` and `localhost` only; every
other destination would have to be HTTPS.

## What is stored on your device

All data stays in the app's private storage, readable only by PocketCore:

- the validated blockchain data (headers, blocks, chainstate),
- `bitcoin.conf` and the daemon's logs,
- your app settings (network, prune size, watch-only descriptor) in app-private
  `SharedPreferences`,
- the generated RPC password, sealed with an Android Keystore key.

None of it is backed up or synchronized anywhere. `android:allowBackup="false"`
keeps it out of Android's cloud backup.

A watch-only descriptor is used only to derive addresses locally so the node can
search its own UTXO set for them; it is never transmitted to a third party.

## Permissions and why they are needed

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | connect to Bitcoin peers |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | keep the node running while the screen is off |
| `POST_NOTIFICATIONS` | show the node status card (height, sync progress) |
| `WAKE_LOCK` | let the CPU finish block validation with the screen off |

No permission is used to read your personal data, and none is requested for
contacts, storage, location, camera or microphone.

## Children

PocketCore is a technical tool for running a Bitcoin node. It is not directed at
children and collects no data from anyone, including children under 13.

## Data deletion

Because nothing is collected, there is nothing for the developer to delete. To
remove all data the app has created, uninstall it; Android deletes the app's
private storage, including the synced chain, with it.

## Changes to this policy

If the app's behaviour ever changes, this document will be updated in the same
repository before the change ships, with the effective date above.

## Contact

Questions about this policy or the app: open an issue at
<https://github.com/CoreJBH/pocketcore/issues>, or use the developer contact
address shown on the PocketCore listing in Google Play.

---

## 한국어 요약

PocketCore는 **개인정보를 전혀 수집하지 않습니다.** 계정도, 분석 도구도, 오류 수집
(crash reporting)도, 광고도, 제3자 추적 SDK도 없습니다. 앱이 하는 통신은 비트코인
P2P 네트워크 접속과 기기 안의 자기 노드와의 로컬 연결(`127.0.0.1`) 두 가지뿐이며,
블록체인 데이터와 설정·로그는 모두 기기 내부 앱 전용 저장소에만 남습니다. 지갑이
아니므로 개인키·시드 문구를 만들거나 저장하지 않습니다. 앱을 삭제하면 저장된
데이터도 함께 삭제됩니다. 문의: <https://github.com/CoreJBH/pocketcore/issues>
