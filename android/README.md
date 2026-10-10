# Webandr — bản Android (x86_64) của Qrtuxa

Repo này được sao chép từ `Qrtuxa` (đủ lịch sử commit). Thư mục `desktop/` và
`docs/` giữ nguyên code gốc (Electron cho Windows) để tham khảo; thư mục
`android/` là ứng dụng Android thật (Kotlin, không cần Electron).

## Tính năng đã có trên Android

- Trình duyệt nhiều tab (chạm tab để chuyển, nhấn giữ để đóng, nút ＋ mở tab mới)
- Thanh địa chỉ/tìm kiếm Google, quay lại, tải lại
- Dấu trang, lịch sử, chế độ máy tính (đổi User-Agent)
- Tải xuống (DownloadManager), chọn tệp để tải lên, camera/micro cho trang web,
  video toàn màn hình, link mở ngoài app (tel:, mailto:, intent:)
- Chặn quảng cáo theo danh sách domain
- **QR Remote Input**: menu ⋮ → "QR Remote Input" hiện mã QR; điện thoại khác
  quét mã đó (trang `docs/index.html` trên GitHub Pages của Qrtuxa) rồi quét mã
  QR/mã vạch cần nhập — nội dung được gõ vào ô đang chọn trong tab hiện tại và
  tự nhấn Enter (giống bản máy tính). Dùng chung Firebase `qrremod`, nhánh
  `sessions/{id}`.

## Chưa chuyển sang Android (còn ở bản desktop)

QR Cam (xem webcam điện thoại qua WebRTC), quay video theo lịch, mật khẩu đã lưu,
chia đôi màn hình, trang cảnh báo web đen/lừa đảo tự quản lý.

## Build

Đẩy lên nhánh `main` (hoặc chạy tay workflow **Build Android APK (x86_64)**) —
file APK nằm trong Artifacts: `webandr-android-x86_64`. APK được ký bằng khóa
debug nên cài thẳng được (bật "Cài từ nguồn không rõ").

Build local: cần JDK 17 + Android SDK 34, rồi `cd android && gradle assembleRelease`.

`applicationId`: `com.h.webandr` · minSdk 24 · ABI: `x86_64`.
