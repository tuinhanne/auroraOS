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
