# LightPods

AirPods battery and status on the Light Phone III. Sideloaded APK, launcher label
**Earbuds**.

Shows left, right and case charge, and offers a best-effort Connect button plus media
controls — all without root, without Google Play Services, and without the phone ever
pretending to be an iPhone.

<!-- screenshots go in docs/screenshots once it is running on hardware -->

## What it can and cannot do

AirPods speak two protocols. LightPods uses the one that is reachable.

**Reachable — BLE proximity advertisements.** The buds broadcast a 25-byte
manufacturer payload (Apple company ID `0x004C`, type `0x07`) continuously and in the
clear. It carries battery deciles for both buds and the case plus charging flags.
Reading it needs nothing but a scan permission.

The same payload has bits that look like in-ear and lid state, and they are decoded,
but nothing user-facing reads them: in practice they report "in ear" with the buds shut
in their case. LibrePods does not trust them either — its UI takes ear detection from
the AAP channel. Both are shown on the debug screen so the bits can be worked out
against real hardware rather than guessed at.

**Not reachable — AAP over L2CAP (PSM `0x1001`).** Noise-control modes, transparency,
gesture remapping, ear-detection toggles and conversational awareness all live behind
this channel. Android's Bluetooth stack refused third-party L2CAP sockets until the
fix that landed in Android 16 QPR3; LightOS on the Light Phone III is Android 14, its
bootloader is locked, and the Magisk workaround that other projects use refuses to
install against Qualcomm's `libbluetooth_qti.so` anyway. Three separate walls, so
LightPods does not attempt it. If LightOS ever moves to Android 16 or newer, the
scanner sits behind an interface that a real AAP client can slot into.

One consequence worth knowing: recent AirPods firmware also ships an AES-encrypted
copy of the battery figures in the same advertisement, and the key only comes out of
the AAP handshake. LightPods reads the legacy plaintext nibbles. Where firmware has
stopped filling those in, the app shows `--` rather than inventing a number.

## Picking your earbuds out of the crowd

Every pair of AirPods in radio range broadcasts the same message, and without the
identity resolving key there is no cryptographic way to tell which pair is yours. An
app that simply renders whatever arrived last will show a stranger's model name and
battery figures that jump — on a train it is unusable.

`PodsTracker` ranks the candidates instead. A pair that reports itself in use with
some phone beats an idle one; after that the nearest wins. The choice is sticky, since
signal strength swings several dB between advertisements and a naive maximum flaps.

It also merges readings. A single advertisement often carries only the broadcasting
bud's charge, with the other nibble reading 0xF, and which bud broadcasts alternates —
so rendering one advertisement at a time shows one bud at a time. Each side keeps its
last real figure for 20 seconds: long enough to bridge the gap while the buds take
turns, short enough that a bud you put away stops claiming to be at 70%.

That expiry is driven by a timer rather than observed, because a bud going quiet
produces no event to react to. The service ages the readings every 5 seconds and the
screen goes blank rather than stale once the pair has been silent for 12.

Long-press the model name for the debug screen: raw advertisement bytes, the model id,
signal strength, and every pair currently in range. That is the fastest way to tell a
parsing bug from a neighbour's earbuds.

## The Connect button

There is no public Android API for "connect my earbuds". `BluetoothA2dp.connect()`
exists but is hidden and requires `BLUETOOTH_PRIVILEGED`, which a sideloaded app
cannot hold. `PodsConnector` tries three things and tells you which one worked:

1. Reflective A2DP connect through the profile proxy.
2. Reflective HFP connect through the profile proxy.
3. An RFCOMM socket to the handsfree service record. Opening it forces the ACL link
   up and the buds usually finish the audio connection themselves.

If all three fail the app opens the system Bluetooth page instead of pretending.
Expect (3) to be the one that fires on LightOS.

Once the earbuds are attached the Connect button is replaced by transport and volume
controls, which go through `AudioManager.dispatchMediaKeyEvent` and need no permission
at all. Noise control and transparency would belong in that same row; they are behind
AAP, so they are not there. The debug screen has a one-tap AAP probe that tries to open
L2CAP 0x1001 and reports exactly how it is refused — worth running once on your own
handset rather than taking the paragraph above on trust.

## Install

```sh
adb install -r LightPods-v1.0.x.apk
```

LightOS may not surface a runtime permission dialog for sideloaded apps. If the app
sits on "Bluetooth permission is needed", grant them over ADB:

```sh
adb shell pm grant com.gios.lightpods android.permission.BLUETOOTH_SCAN
adb shell pm grant com.gios.lightpods android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.gios.lightpods android.permission.POST_NOTIFICATIONS
```

The buds must already be paired through LightOS's own Bluetooth settings. LightPods
reads and connects; it does not pair.

## Battery cost

The scan filter is applied in the Bluetooth controller, not in the app, so the radio
wakes the process only for Apple proximity traffic. The service drops to
`SCAN_MODE_LOW_POWER` with 4-second batching whenever the UI is off screen, and stops
entirely when Bluetooth is switched off.

## Build

```sh
./gradlew :app:testDebugUnitTest   # parser is pinned against LibrePods' decode
./gradlew :app:assembleRelease
```

Every push to `main` builds, runs the tests, verifies the signing certificate and the
launcher icon, and publishes a GitHub Release. The workflow tags `v${versionName}`, so
**bump `versionName` in `app/build.gradle.kts`** or Obtainium sees no new release and
the change never reaches the phone.

The keystore is committed on purpose. Android identifies an app by package name plus
signing certificate, so a rotating CI key breaks in-place updates with an opaque
`Failure: Invalid`. `signing-fingerprint.txt` pins the expected certificate and CI
fails if it ever drifts.

Regenerate the launcher icon with `python3 scripts/generate_icon.py`.

## Layout

```
bt/ProximityPayload.kt   pure decoder, no Android imports, unit tested
bt/AirPodsScanner.kt     controller-side scan filter, two duty cycles
bt/PodsConnector.kt      the three-step connect chain
data/PodsRepository.kt   process-wide StateFlow shared by service and UI
service/PodsService.kt   foreground service, ongoing status notification
ui/                      Compose, greyscale, Akkurat pulled from LightOS
```

## The gi-os Light App collection

Twelve tools for the Light Phone III, all open source, all built in one run.

| Tool | What it does | Built on |
| --- | --- | --- |
| [LightPass](https://github.com/gi-os/LightPass) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [LightRSS](https://github.com/gi-os/LightRSS) | RSS and Atom reader with images and QR subscribe | light-sdk, fork of [zachattack323/LightRSS](https://github.com/zachattack323/LightRSS) |
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| [chat](https://github.com/gi-os/chat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| [LightFog](https://github.com/gi-os/LightFog) | Fog of World companion, GPS recorder and fog map | Fork of [garado/light-topographic](https://github.com/garado/light-topographic) |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| [LightSolitaire](https://github.com/gi-os/LightSolitaire) | Klondike, draw one, unlimited redeals | light-sdk |
| [LightFastread](https://github.com/gi-os/LightFastread) | RSVP speed reader for EPUB and MOBI | Fork of [fluffyspace/FastRead](https://github.com/fluffyspace/FastRead) |
| [LightTip](https://github.com/gi-os/LightTip) | Tip calculator, plus a receipt splitter that reads the line items | Plain Android |
| [LightNoise](https://github.com/gi-os/LightNoise) | Twelve synthesized sounds, a two-layer mixer and a sleep timer | Plain Android |
| **LightPods** (this repo) | AirPods battery, in-ear and lid status | Plain Android, ports [LibrePods](https://github.com/kavishdevar/librepods) |

The Light Phone does not sponsor or endorse any of these. Licences vary per repo.

## Licence

GPL-3.0-or-later. The proximity decode in `bt/ProximityPayload.kt` is a port of
[LibrePods](https://github.com/kavishdevar/librepods)' `BLEManager`, which is
GPL-3.0-or-later, so this project inherits it. LibrePods did the reverse engineering;
this is a small greyscale front end for one phone.
