# LightPods

AirPods battery and status on the Light Phone III. Sideloaded APK, launcher label
**Earbuds**.

Shows left, right and case charge, in-ear and lid state, and offers a best-effort
Connect button — all without root, without Google Play Services, and without the
phone ever pretending to be an iPhone.

<!-- screenshots go in docs/screenshots once it is running on hardware -->

## What it can and cannot do

AirPods speak two protocols. LightPods uses the one that is reachable.

**Reachable — BLE proximity advertisements.** The buds broadcast a 25-byte
manufacturer payload (Apple company ID `0x004C`, type `0x07`) continuously and in the
clear. It carries battery deciles for both buds and the case, charging flags, in-ear
detection and lid state. Reading it needs nothing but a scan permission.

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

## Licence

GPL-3.0-or-later. The proximity decode in `bt/ProximityPayload.kt` is a port of
[LibrePods](https://github.com/kavishdevar/librepods)' `BLEManager`, which is
GPL-3.0-or-later, so this project inherits it. LibrePods did the reverse engineering;
this is a small greyscale front end for one phone.
