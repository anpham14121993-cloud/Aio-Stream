<p align="center">
  <h1 align="center">AioStream</h1>
  <p align="center">
    <b>Trình phát đa phương tiện đa nền tảng — 1 file APK duy nhất cho Android Mobile, Tablet & Android TV / TV Box (armeabi-v7a + arm64-v8a)</b>
  </p>
</p>

## Tính năng nổi bật

- **1 APK Universal (`armeabi-v7a` + `arm64-v8a`)**: Chạy mượt trên điện thoại, máy tính bảng và các dòng TV Box 32-bit/64-bit, Google TV, Android TV, Nvidia Shield, Ugoos, Xiaomi Mi Box, Chromecast.
- **Giao diện thích ứng Tri-Mode "Obsidian Monolith"**:
  - **Mobile**: Thanh Dock kính nổi phía dưới (`Bottom Dock`) hỗ trợ màn hình dọc & xoay ngang thông minh + Cử chỉ chạm trình phát (chạm đơn bật/tắt HUD, chạm đúp ±10s, vuốt trái chỉnh độ sáng, vuốt phải chỉnh âm lượng, vuốt ngang tua nhanh, nút Khóa chạm màn hình).
  - **Tablet**: Thanh điều hướng Micro-Rail bên cạnh trái + hỗ trợ cả cảm ứng và điều khiển rời.
  - **TV / TV Box**: Thanh Top Lens Pill nổi điều hướng Remote D-Pad Up/Down tức thì, tự động chuyển đổi trực tiếp giữa chạm cảm ứng và D-Pad theo thao tác thực tế.
- **100% Giao diện Tiếng Việt**: Toàn bộ hệ thống cài đặt, trình phát và thông báo hoàn toàn bằng tiếng Việt.
- **Tự động làm giàu dữ liệu (TMDB & MDBList)**: Tích hợp sẵn bộ khóa TMDB cộng đồng có tự động xoay vòng khi quá tải và nguồn điểm IMDb/TMDB công khai + MDBList không cần thao tác cài đặt thủ công.
- **Lõi phát cao cấp**:
  - ExoPlayer + LibMPV + LibASS + Dav1d + FFmpeg downmix.
  - Quản lý bộ nhớ gốc tầng thấp (off-heap native buffer allocator), tự động căn chỉnh dung lượng đệm an toàn cho thiết bị 32-bit RAM thấp.
  - Kết nối song song & chia chunk thông minh, hỗ trợ pre-warm link trước khi phát giúp mở phim tức thì.
  - Hỗ trợ Dolby Vision Profile 7/8 (`libdovi` RPU conversion + native FEL), HEVC 10-bit, AV1, HDR10+, và HDMI Audio Passthrough (TrueHD, DTS-HD MA, DTS:X, E-AC3, AC3) cho TV Box / Soundbar.
  - Tùy chỉnh đầy đủ phụ đề ASS/SRT/PGS/VobSub, đồng bộ trễ phụ đề, và bảng thông số phát trực tiếp (`Playback Stats`).

## Xây dựng từ mã nguồn (Build)

```bash
# Chạy kiểm thử đơn vị (Unit tests)
./gradlew :app:testFullDebugUnitTest

# Biên dịch bản APK Universal (ARMv7a + ARM64v8a)
./gradlew :app:assembleFullRelease

# Kiểm tra thư viện native C++/Rust trong file APK Universal
python3 scripts/verify_native_apk.py app/build/outputs/apk/full/release/*.apk --universal
```

## Bản quyền & Nguồn gốc (GPLv3 Attribution)

Dự án **AioStream** được phát triển dựa trên nền tảng mã nguồn mở xuất sắc của **[NuvioTV](https://github.com/NuvioMedia/NuvioTV)** (NuvioMedia) và **[NuvioTV-Fork / Nuvio Arc](https://github.com/ysosrs123/NuvioTV-Fork)** (`ysosrs123`), phát hành theo giấy phép **GNU General Public License v3.0 (GPL-3.0)**. Xem chi tiết trong tệp [LICENSE](./LICENSE).

*Lưu ý: AioStream là trình phát đa phương tiện, không lưu trữ hay cung cấp nội dung phim/luồng phát sẵn.*
