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

## Các tính năng đã chuyển thêm

- **QR Cam** (menu ⋮ → "QR Cam"): nhập mã 6 số của app QrtuxaCam (tối đa 4 điện thoại),
  xem trực tiếp qua WebRTC (lưới 1/2/4 ô), tự nối lại khi mất tín hiệu, nhớ mã
  đã nhập. Dùng chung Firebase `qrremod`, nhánh `rooms/{mã}`. Điện thoại cũng có
  thể gõ chữ vào ô đang chọn trong trình duyệt (text-edit).
- **Quay video + hẹn giờ**: nút ⏺ trên từng ô để quay, nút ⚙ đặt giờ bắt đầu/kết
  thúc lặp lại mỗi ngày. File `.webm` lưu ở thư mục riêng của app
  (`Android/data/com.h.webandr/files/Movies/QRCam`); app báo đường dẫn khi lưu xong.
  Cần để app mở (màn hình tự giữ sáng khi đang kết nối/quay).
- **Mật khẩu đã lưu**: hỏi lưu khi gửi form đăng nhập, tự điền khi vào lại trang,
  lưu mã hoá bằng Android Keystore. Xem/xoá ở menu ⋮ → "Mật khẩu đã lưu".
- **Chia đôi màn hình** (Telegram/Zalo/Facebook/YouTube/Google): menu ⋮ → "Chia đôi
  màn hình". Màn hình dọc: pane ở trên; màn hình ngang: pane bên trái (2/7).
  Tắt chia đôi không huỷ phiên đăng nhập của pane.
- **Chặn web đen / lừa đảo**: danh sách tên miền tự quản lý (menu ⋮ → "Chặn web
  đen / lừa đảo"); vào tên miền đó (hoặc tên miền con) sẽ hiện trang cảnh báo với
  nút "Quay lại an toàn" / "vẫn tiếp tục".

## Đã chuyển thêm (bản 1.1.0)

- **Gửi/nhận tệp qua QR Cam** (DataChannel, cùng giao thức bản máy tính):
  - Nhận: điện thoại QrtuxaCam gửi tệp → app gom đủ rồi lưu vào
    `Android/data/com.h.webandr/files/Download/QRCam` và chép thêm vào **Tải xuống/QRCam**
    (tối đa 300 MB/tệp, mất kết nối giữa chừng thì huỷ).
  - Gửi: khi ô đang kết nối kiểu truyền tệp (có DataChannel, không có video) hiện nút 📤
    để chọn tệp trên điện thoại và gửi sang máy kia.
- **Sửa ảnh nhận được**: ảnh nhận về tự mở trình sửa (✂️ Cắt, 🔄 Xoay, ✏️ Vẽ, 🔲 Đánh dấu,
  ↩ Hoàn tác 1 bước). Mỗi lần sửa tự ghi đè tệp đã nhận; nút 💾 chép bản hiện tại vào Tải xuống/QRCam.
- **Chặn quảng cáo YouTube bằng script**: tự bấm "Bỏ qua quảng cáo", quảng cáo không bỏ qua được thì
  tắt tiếng + tua đến cuối (xong tự bật lại tiếng/tốc độ), ẩn banner/quảng cáo hiển thị. Chạy trên mọi
  tab và pane YouTube chia đôi. Bật/tắt: menu ⋮ → "Chặn quảng cáo YouTube".
- **Nhớ zoom theo trang**: chụm/giãn ngón tay để zoom, app nhớ riêng cho từng tên miền và áp lại khi
  mở lại trang đó. Menu ⋮ → "Đặt lại zoom trang này" để về mặc định.
- **Nút "Mở link bằng 1 chạm"** (👆 trên thanh địa chỉ, cũng có trong menu ⋮): BẬT (mặc định) = chạm
  vào link là mở như bình thường. TẮT (nút mờ) = chạm vào link không mở, chặn cửa sổ pop-up; muốn mở
  1 link thì **nhấn giữ** link → chọn "Mở trong tab mới / Mở ở tab này / Sao chép". Link `#...` và
  `javascript:` vẫn chạy bình thường.

## Chưa chuyển sang Android

Nhật ký console (chỉ có ở bản máy tính).

## Build

Đẩy lên nhánh `main` (hoặc chạy tay workflow **Build Android APK (x86_64)**) —
file APK nằm trong Artifacts: `webandr-android-x86_64`. APK được ký bằng khóa
debug nên cài thẳng được (bật "Cài từ nguồn không rõ").

Build local: cần JDK 17 + Android SDK 34, rồi `cd android && gradle assembleRelease`.

`applicationId`: `com.h.webandr` · minSdk 24 · ABI: `x86_64`.
