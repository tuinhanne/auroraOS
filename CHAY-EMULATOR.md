# Running the LineageOS 23.2 emulator

Verified working configuration.

## Run

```powershell
D:\androidSDK\emulator\emulator.exe -avd LineageOS_23_2
```

**One argument, nothing else.** Do not add `-gpu`, `-no-snapshot` or `-feature`,
and do not set the `QT_OPENGL` environment variable. The emulator reads
everything it needs from `config.ini`. Passing those as command-line flags
overrides the config and breaks Qt's mouse handling — the window renders but no
click reaches the guest. That cost most of a day to track down.

Launching from Android Studio's Device Manager works too.

## Environment

| | |
|---|---|
| SDK | `D:\androidSDK` — the one Android Studio and Flutter use |
| Emulator | 37.1.11. Version 36.x kills `system_server` on this image |
| System image | `D:\androidSDK\system-images\android-36.1\lineage\x86_64\` |
| `opengl32sw.dll` | copied into `emulator\`, `emulator\lib64\` and `emulator\lib64\qt\lib\` (taken from `C:\Program Files\AMD\CIM\Bin64\`) |
| GPU driver | AMD 31.0.21925.1001 (2026-05-20) |

## Key config.ini values

Path: `%USERPROFILE%\.android\avd\LineageOS_23_2.avd\config.ini`

```ini
hw.gpu.mode=swiftshader_indirect   ; REQUIRED - the host GPU crashes in
                                   ; GoldfishMapper::readFromHost on this machine
hw.gpu.enabled=yes
hw.lcd.width=1080                  ; keep the native resolution, see below
hw.lcd.height=2400
hw.lcd.density=420
hw.ramSize=4096
disk.dataPartition.size=6G
hw.keyboard=yes
showDeviceFrame=no
fastboot.forceColdBoot=yes         ; a stale snapshot makes the emulator exit
                                   ; about one second after starting
```

### Do not lower the resolution

The display cutout is baked into the system image as an absolute-pixel path:

```
M 507,64 a 33,33 0 1 0 66,0 33,33 0 1 0 -66,0 Z
```

That is a circle centred at x=540, which is the middle of a 1080-wide display.
The path does **not** scale with `hw.lcd.width`. Halving the display to 540 wide
leaves the circle sitting at the right edge, half of it off-screen, and turns a
128 px top inset from 5.3% of the screen height into 10.7% — which shoves the
status bar icons sideways.

Lowering the resolution was once used to work around a SystemUI ANR. That ANR
was really caused by the old 36.x emulator plus corrupted userdata, both since
fixed, so the native resolution runs fine.

## Known limitations

- **Screenshots do not work** (`screencap` aborts with
  `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`) and **scrcpy
  fails** (its MediaCodec encoder throws). Both need to read rendered frames back
  from the GPU, which this machine's integrated AMD Radeon cannot serve through
  gfxstream. Record the Windows screen instead.
- **Android Studio's embedded Running Devices view stays blank** for the same
  reason: it streams frames over gRPC. Use the standalone emulator window.
- The UI is slow and stutters because rendering happens on the CPU. Do not judge
  animation smoothness here.

None of the above is caused by the ROM. Google's own stock system image
crash-loops on this machine with the same `readFromHost` failure.

## If the emulator exits right after starting

Delete the snapshot and start again:

```powershell
Remove-Item "$env:USERPROFILE\.android\avd\LineageOS_23_2.avd\snapshots" -Recurse -Force
```

## Driving it from adb

```powershell
D:\androidSDK\platform-tools\adb.exe shell input tap <x> <y>
D:\androidSDK\platform-tools\adb.exe shell dumpsys window | Select-String mCurrentFocus
```

---

# Running a locally built image

The AVD above runs the prebuilt LineageOS image. This section is for running one built
on the VM — which is how Sprint 03 got its Boot PASS, and which needs a different set of
steps for four reasons that each cost time to find.

## The AVD

```powershell
D:\androidSDK\emulator\emulator.exe -avd LineageOS_Aurora
```

`LineageOS_Aurora` points at `system-images\android-36.1\lineage-aurora\`, a directory
parallel to `lineage\`. **Both AVDs exist on purpose.** A locally built image that does not
boot is a much smaller problem when the known-good one is still there to compare against,
and the working configuration this file documents took a day to arrive at.

## Getting an image off the build VM

**`m sdk_repo` is the wrong target.** It succeeds, takes twelve minutes, and produces
platform-tools zips and no system image.

**`m sdk_addon` is also wrong here**, though it looks right — the product sets
`PRODUCT_SDK_ADDON_NAME`. It fails on a missing goldfish prebuilt:

```
ninja: 'device/generic/goldfish/data/etc/userdata.img', missing and no known rule to make it
```

**`m emu_img_zip` is the one that works.** It writes
`out/target/product/emu64x/sdk-repo-linux-system-images.zip`, whose layout is exactly what
`image.sysdir.1` expects: `x86_64/system.img`, `vendor.img`, `ramdisk.img`, `kernel-ranchu`,
`build.prop`, `source.properties`.

## Download size is 1.1 GB, not 8.6

`system.img` is 8.2 GB and nearly empty — the super partition is sized for growth. It
compresses to about 0.9 GB, and `emu_img_zip` has already done that, so the zip is 1.1 GB
and there is no reason to copy the raw images.

## Extracting it: not with PowerShell

`Expand-Archive` fails part way through with:

```
Exception calling "ExtractToFile": "A local file header is corrupt."
```

That is not a corrupt file. `system.img` is over 4 GB, so the archive is ZIP64, which
`Expand-Archive` does not handle. Git Bash's `tar` does not read zip at all. Use Windows'
own bsdtar:

```powershell
C:\WINDOWS\System32\tar.exe -xf aurora-sysimg.zip
```

## What the new image directory needs beyond the zip

`package.xml`, copied from `lineage\x86_64\`. The zip does not carry it, and without it the
SDK manager does not list the image. The emulator itself reads `image.sysdir.1` and does not
care, so a missing `package.xml` shows up later and confusingly.

## Making the AVD

Copy the working one rather than creating from scratch — this file's own warnings about
`config.ini` are the reason:

```powershell
$avd = "$env:USERPROFILE\.android\avd"
Copy-Item "$avd\LineageOS_23_2.avd" "$avd\LineageOS_Aurora.avd" -Recurse
# then in the copy's config.ini:
#   image.sysdir.1=system-images\android-36.1\lineage-aurora\x86_64\
# and an .ini beside it whose path= points at the new .avd
```

Delete `snapshots` from the copy. A snapshot taken against one system image is not valid
against another, and the failure it produces looks nothing like its cause.
