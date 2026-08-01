# Cách chạy emulator LineageOS 23.2 (cấu hình đã kiểm chứng)

## Chạy

```powershell
D:\androidSDK\emulator\emulator.exe -avd LineageOS_23_2
```

**Chỉ đúng một tham số.** Không thêm `-gpu`, `-no-snapshot`, `-feature`, và
tuyệt đối **không** đặt biến môi trường `QT_OPENGL`. Emulator tự đọc mọi thiết
lập từ `config.ini`. Thêm cờ dòng lệnh sẽ ghi đè và làm hỏng khâu xử lý chuột —
đây là nguyên nhân khiến máy ảo hiện hình nhưng không bấm được suốt buổi đầu.

Hoặc bấm ▶ ở dòng `LineageOS 23 2` trong Device Manager của Android Studio.

## Môi trường

| | |
|---|---|
| SDK | `D:\androidSDK` (Android Studio và Flutter đều dùng bộ này) |
| Emulator | 37.1.11 — bản 36.x làm chết `system_server` |
| System image | `D:\androidSDK\system-images\android-36.1\lineage\x86_64\` |
| `opengl32sw.dll` | đã chép vào `emulator\`, `emulator\lib64\`, `emulator\lib64\qt\lib\` (lấy từ `C:\Program Files\AMD\CIM\Bin64\`) |
| Driver GPU | AMD 31.0.21925.1001 (2026-05-20) |

## config.ini quan trọng

Đường dẫn: `%USERPROFILE%\.android\avd\LineageOS_23_2.avd\config.ini`

```ini
hw.gpu.mode=swiftshader_indirect   ; BẮT BUỘC — GPU thật gây crash readFromHost
hw.gpu.enabled=yes
hw.lcd.width=540                   ; hạ từ 1080 để CPU render kịp, tránh ANR
hw.lcd.height=1200
hw.lcd.density=240
hw.ramSize=4096
disk.dataPartition.size=6G
hw.keyboard=yes
showDeviceFrame=no
fastboot.forceColdBoot=yes         ; snapshot cũ bị hỏng làm máy ảo tắt sau 1 giây
```

## Hạn chế đã biết

- **Không chụp được màn hình** (`screencap` đổ với assertion `hasReadColorBufferDma`)
  và **scrcpy không chạy** (MediaCodec đổ). Cả hai đều cần đọc ngược khung hình
  từ GPU — thao tác mà GPU AMD tích hợp của máy này không phục vụ được qua
  gfxstream. Muốn ghi lại kết quả thì quay màn hình Windows từ bên ngoài.
- **Running Devices nhúng của Android Studio không hiển thị được** vì luồng gRPC
  cũng cần đọc ngược khung hình. Dùng cửa sổ emulator độc lập.
- Giao diện chậm và giật do render bằng CPU. Đừng đánh giá độ mượt animation
  trên đây.

## Nếu máy ảo tắt ngay sau khi khởi động

Xoá snapshot rồi chạy lại:

```powershell
Remove-Item "$env:USERPROFILE\.android\avd\LineageOS_23_2.avd\snapshots" -Recurse -Force
```

## Điều khiển bằng adb khi cần

```powershell
D:\androidSDK\platform-tools\adb.exe shell input tap <x> <y>
D:\androidSDK\platform-tools\adb.exe shell dumpsys window | Select-String mCurrentFocus
```
