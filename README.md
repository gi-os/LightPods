# LightPods

AirPods battery and status on the Light Phone III. Sideloaded APK, launcher label
**Earbuds**, package `com.gios.lightpods`. Current release: **v1.0.7**.

Shows left, right and case charge, plus a best-effort Connect button and media
controls once attached — all without root, without Google Play Services, and without
the phone ever pretending to be an iPhone. Built from the
[BrightPasses](https://github.com/gi-os/BrightPasses) skeleton, since the Light SDK's
dependency allowlist has no Bluetooth permission at all (see
[light-sdk-sandbox-limits](https://github.com/gi-os/BrightPasses) background) and blocks
`getSystemService`, `Context`, `registerReceiver`, `bindService` and reflection — a
Bluetooth tool cannot be built as an SDK tool, only as a plain APK.

## What it can and cannot do

AirPods speak two protocols; LightPods uses only the one that is reachable.

- **Reachable — BLE proximity advertisements.** A 25-byte manufacturer payload (Apple
  company ID `0x004C`, type `0x07`), broadcast continuously and in the clear, carrying
  battery deciles for both buds and the case plus charging flags. Needs only
  `BLUETOOTH_SCAN`; the decode in `bt/ProximityPayload.kt` is a port of
  [LibrePods](https://github.com/kavishdevar/librepods)' `BLEManager`, verified against
  it over 1.5M synthetic payloads with zero mismatches.
- **Not reachable — AAP over L2CAP (PSM `0x1001`).** Noise control, transparency,
  gesture remapping, real ear-detection toggles and conversational awareness live behind
  this channel. Android's stack refused third-party L2CAP sockets until the fix in
  Android 16 QPR3 (March 2026); LightOS on the LP3 is Android 14 with a locked
  bootloader, and the Magisk `btl2capfix` workaround other projects use refuses to
  install against Qualcomm's `libbluetooth_qti.so` anyway. Three separate walls, so
  LightPods does not attempt it. The debug screen has a one-tap AAP probe that opens
  L2CAP 0x1001 and reports exactly how it's refused, worth running once against real
  hardware rather than taking this paragraph on trust.

As of the current release, the payload's apparent in-ear/lid bits are decoded but
**nothing user-facing reads them** — in practice they read "in ear" with the buds shut
in a closed case, and LibrePods doesn't trust them either (its UI takes ear detection
from the AAP channel, not the advertisement). Both bits, and the raw status byte, are on
the debug screen for working out against real hardware. Recent firmware also
AES-encrypts a second copy of the battery bytes; the key only comes out of the AAP
handshake, so LightPods reads the legacy plaintext nibbles and shows `--` where
firmware has stopped filling those in.

## Quick start

```sh
git clone https://github.com/gi-os/LightPods.git
cd LightPods
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

```sh
# Runtime permissions, since LightOS may not surface its own dialog for a sideloaded app
adb shell pm grant com.gios.lightpods android.permission.BLUETOOTH_SCAN
adb shell pm grant com.gios.lightpods android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.gios.lightpods android.permission.POST_NOTIFICATIONS
```

The buds must already be paired through LightOS's own Bluetooth settings — LightPods
reads and connects, it does not pair. Once granted, the app immediately shows battery
for any Apple proximity advertisement in range; no further configuration exists.

## Usage notes

- **Picking your earbuds out of the crowd.** Every nearby pair of AirPods broadcasts the
  same kind of message, and there is no way to cryptographically confirm which is yours.
  `PodsTracker` ranks candidates (a pair reporting itself in use with some phone beats an
  idle one, then nearest wins) and the choice is sticky, since RSSI swings several dB
  between advertisements and a naive maximum flaps.
- **Merging split readings.** A single advertisement usually carries only the
  broadcasting bud's charge (the other nibble reads `0xF`), and which bud broadcasts
  alternates. Each side keeps its last real figure for 20 seconds — long enough to
  bridge the alternation, short enough that a bud you've put away stops claiming a
  stale charge — aged out by a 5-second timer in the foreground service rather than
  observed, since a bud going silent produces no event to react to. The screen goes
  blank (not stale) once the pair has been silent for 12 seconds; a candidate drops out
  of the running after 15.
- **Connect button.** No public Android API exists for "connect my earbuds" —
  `BluetoothA2dp.connect()` is hidden and needs `BLUETOOTH_PRIVILEGED`, unavailable to a
  sideloaded app. `PodsConnector` tries, in order: reflective A2DP connect, reflective
  HFP connect, then an RFCOMM socket to the handsfree UUID to force the ACL link up so
  the buds finish the connection themselves. Falls back to the system Bluetooth page if
  all three fail. Expect the RFCOMM route to be the one that fires on LightOS. Once
  attached, the Connect button is replaced by transport/volume controls via
  `AudioManager.dispatchMediaKeyEvent`, which need no special permission.
- **Debug screen.** Long-press the model name: raw advertisement bytes, model id, RSSI,
  every pair currently in range, the raw status byte, and the AAP probe. Fastest way to
  tell a parsing bug from a neighbour's earbuds.
- **The wheel** scrolls the debug screen only — nothing else in the app is longer than
  the panel. It arrives as an ordinary key event (LightOS relabels the optical sensor's
  scancodes `WHEEL_CCW`/`WHEEL_CW`); [BrightControl](https://github.com/gi-os/BrightControl)
  is the separate, optional app that owns the wheel click and camera button phone-wide
  and passes bare turns through to `com.gios.*`.
- **Battery cost.** The scan filter runs in the Bluetooth controller, not the app, so
  the radio only wakes the process for Apple proximity traffic. The service drops to
  `SCAN_MODE_LOW_POWER` with 4-second batching whenever the UI is off screen, and stops
  entirely when Bluetooth is off.

## Build and test

```sh
./gradlew :app:testDebugUnitTest   # ProximityPayload decode, pinned against LibrePods
./gradlew :app:assembleRelease
python3 scripts/generate_icon.py   # regenerate the launcher icon
```

`distinctUntilChanged()` on a `StateFlow` is a compile **error** under Kotlin 2 (not a
warning) — it cost the first CI run and is worth knowing before you add one back.
`startScan()` reports failure asynchronously via `onScanFailed`; a scanner that doesn't
reset there latches into a fake "scanning" state forever. Permissions granted over ADB
never reach the result callback, so they're re-read in `onStart`.

## Signing and releases

Every push to `main` runs the unit tests, assembles, verifies the signing certificate
against `signing-fingerprint.txt`, verifies a launcher icon is present, and publishes a
GitHub Release — **a push is a release trigger, not a cosmetic action.**
`versionCode`/`versionName` in the committed `app/build.gradle.kts` are a `1.0.0`
placeholder; CI overwrites them with `versionCode = <run number>` and
`versionName = 1.0.<run number>` on every run and tags `v${versionName}`, so pushing
anything — including a docs-only change — cuts a new numbered release.

```sh
adb install -r LightPods-v1.0.x.apk
```

## Layout

```
bt/ProximityPayload.kt   pure decoder, no Android imports, unit tested
bt/AirPodsScanner.kt     controller-side scan filter, two duty cycles
bt/PodsConnector.kt      the three-step connect chain
data/PodsRepository.kt   process-wide StateFlow shared by service and UI
data/PodsTracker.kt      candidate ranking + reading expiry
service/PodsService.kt   foreground service, ongoing status notification
hw/                      the brightness wheel, debug-screen scrolling only
ui/                      Compose, greyscale, Akkurat pulled from LightOS
```

## Contributing

Pull requests welcome. Anything touching the proximity decode should stay checked
against LibrePods' `BLEManager` rather than drifting from it, since that's the reference
this project's GPL obligation is tied to. If LightOS ever moves to Android 16 or newer,
`AirPodsScanner`'s interface is meant to be where a real AAP/L2CAP client slots in rather
than a rewrite.

## Version history

| Version | Commit | Change |
| --- | --- | --- |
| v1.0.7 | `5cb3ae7` | Say that wheel scrolling needs nothing but this app (README) |
| v1.0.6 | `b5ec4cd` | Scroll the debug screen with the brightness wheel |
| v1.0.5 | `7dbc4ac` | docs: add the collection table (re-tagged, same commit as v1.0.4) |
| v1.0.4 | `7dbc4ac` | docs: add the collection table |
| v1.0.3 | `77d2fd6` | Track one pair, merge both buds, and swap Connect for controls once attached |
| v1.0.2 | `886f223` | Drop `distinctUntilChanged()` on a StateFlow (Kotlin 2 compile error) |

`ee06a4f` ("Stop claiming in-ear, and age readings out on a timer" — the in-ear/lid bits
stopped being surfaced as fact, and battery readings started expiring on a wall-clock
timer instead of sitting stale) landed between v1.0.3 and v1.0.4/v1.0.5 and shipped as
part of that release rather than its own tag. There is no `v1.0.1` tag; the earliest
release is `v1.0.2`.

## License

GPL-3.0-or-later. The proximity decode in `bt/ProximityPayload.kt` is a port of
[LibrePods](https://github.com/kavishdevar/librepods)' `BLEManager`, which is
GPL-3.0-or-later, so this project inherits it — the only GPL app in this collection,
whose siblings are otherwise MIT.
