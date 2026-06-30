# Goal: Hệ thống Remote Desktop WebRTC (Lấy ý tưởng từ RustDesk)

Mục tiêu của kế hoạch này là xây dựng một hệ thống Remote Desktop hoàn toàn mới dựa trên **WebRTC**, nhưng **vay mượn các thiết kế và tính năng cốt lõi của RustDesk** (Phương án B nâng cao). 

Chúng ta sẽ không nhúng mã nguồn RustDesk (để giữ cho ứng dụng nhẹ và thuần Java/TypeScript), mà sẽ tự code một hệ thống WebRTC có các đặc tính "giống RustDesk" như:
1. Xác thực an toàn và cấp quyền động cho các session.
2. Tách biệt luồng Video và luồng Điều khiển.
3. Sử dụng Relay Server chuyên dụng (TURN) tương tự như `hbbr` của RustDesk.
4. Ép sử dụng codec H264 phần cứng thông qua EGL Context.

## Quyết Định Thiết Kế (Design Decisions)

> ✅ Fixed: Resolved password mechanism question: No PIN required, use existing dashboard session token

**Cơ chế xác thực (Password Mechanism):**
Vì FasonMDM là một sản phẩm quản trị thiết bị (MDM), các admin đã được xác thực an toàn thông qua màn hình đăng nhập của web dashboard. Việc bắt buộc nhập thêm mã PIN sinh ra từ thiết bị (như bản chất của RustDesk) là dư thừa và làm giảm trải nghiệm.
- **Quyết định:** **Không yêu cầu mã PIN.** Sử dụng session token hiện tại của dashboard làm bằng chứng ủy quyền.
- **Luồng Auth (Auth Flow):** Admin đăng nhập Dashboard → Có được Session Token → Backend xác thực Token + `device_id` → Nếu hợp lệ, Backend cho phép luồng Signaling WebRTC đi qua đến thiết bị. Thiết bị không cần hiển thị hay yêu cầu mã PIN.

## Proposed Changes

---

### Component 1: STUN/TURN Server (Relay - Tương đương hbbr)

Để đảm bảo kết nối xuyên tường lửa như RustDesk, chúng ta cần một Relay Server sử dụng time-limited credentials để bảo mật.

> ✅ Fixed: Replaced static credentials with RFC 5389 time-limited HMAC credentials

#### [MODIFY] docker/docker-compose.yml
- Thêm service `coturn/coturn` vào docker-compose.
- Khởi chạy Coturn với cờ sử dụng secret key tĩnh cho việc tính toán HMAC (thay vì user tĩnh).

---

### Component 2: Backend (Signaling Server - Tương đương hbbs)

Backend sẽ đóng vai trò như máy chủ Rendezvous, giúp các bên tìm thấy nhau và cấp phát thông tin Relay an toàn.

#### [NEW] backend/src/utils/turnAuth.ts (hoặc file tương đương)
- Hàm sinh thông tin xác thực TURN (RFC 5389) on-demand trước mỗi session.
- **Định dạng:** 
  - `username = "{expire_unix_timestamp}:{device_id}"` (với TTL = 86400 seconds / 24 hours).
  - `password = base64(HMAC-SHA1(shared_secret, username))`.
- Backend sẽ sử dụng secret dùng chung với Coturn để sinh các credentials động này nhằm tránh lạm dụng Relay server vô thời hạn.

#### [MODIFY] backend/src/services/socket.ts
- Thêm hệ thống quản lý **Session** thay vì chỉ rely vào Socket.IO rooms.
- Triển khai trao đổi tín hiệu WebRTC (Signaling: Offer, Answer, ICE Candidates).
- Xác thực Web Client thông qua Dashboard Session Token trước khi cho phép Signaling.

#### [MODIFY] backend/src/types/index.ts
- Định nghĩa type cho các message Signaling: `CMD.WEBRTC_OFFER`, `CMD.WEBRTC_ANSWER`, `CMD.WEBRTC_ICE`.

#### [MODIFY] backend/src/routes/device.ts
- Thêm API cấp phát thông tin cấu hình ICE Servers (địa chỉ STUN/TURN và HMAC credential động) cho Web Client và Android Client.

---

### Component 3: Frontend (WebRTC Client)

Xây dựng một giao diện xem và điều khiển mượt mà, tối ưu hóa các chiến lược kết nối mạng.

> ✅ Fixed: Added explicit ICE transport policies and ICE restart flow on disconnect

#### [MODIFY] frontend/src/pages/device/Screen.tsx
- Loại bỏ thẻ `<img>` hiện tại. Thêm thẻ `<video autoPlay playsInline>` để stream video real-time.
- **Cấu hình PeerConnection:** `RTCPeerConnection` cần được khởi tạo với: 
  - `bundlePolicy: "max-bundle"`
  - `rtcpMuxPolicy: "require"`
  - `iceTransportPolicy: "all"`
- **ICE Restart Flow:** Bắt sự kiện `onconnectionstatechange`. Khi state chuyển sang `"disconnected"`, thay vì ngắt kết nối hoàn toàn và kết nối lại từ đầu (full reconnect), chỉ gọi hàm `restartIce()` trên connection hiện tại để thử tìm lại đường mạng.

> ✅ Fixed: Split DataChannel into "control" (unordered) and "clipboard" (ordered) to prevent head-of-line blocking

- **Thiết lập DataChannels:** Khởi tạo HAI kênh riêng biệt:
  - `"control"`: `ordered=false`, `maxRetransmits=0` (Dùng cho touch, swipe, keyboard. Vì là lệnh real-time, nếu một packet chậm/rớt, bỏ qua luôn thay vì chặn các thao tác mới, giúp tránh head-of-line blocking).
  - `"clipboard"`: `ordered=true` (reliable - mặc định). (Dùng cho clipboard và gửi file, đảm bảo dữ liệu toàn vẹn).

---

### Component 4: Android App (WebRTC Host)

Thiết bị Android sẽ đóng vai trò là host, ưu tiên sử dụng giải mã phần cứng.

#### [MODIFY] fason/app/build.gradle
- Thêm dependency `org.webrtc:google-webrtc`.

> ✅ Fixed: Added EGL context initialization to fix H264 hardware encoding

#### [MODIFY] fason/app/src/main/java/com/fason/app/features/screen/ScreenCaptureService.java
- **Khởi tạo EGL & Factory theo thứ tự:** 
  1. Khởi tạo `EglBase` trước.
  2. Truyền `EglBase.Context` vào `DefaultVideoEncoderFactory` (với `enableH264HighProfile = true`) và `DefaultVideoDecoderFactory`.
  3. Mới dùng các factory này để khởi tạo `PeerConnectionFactory`. (Thiếu EGL, H264 encoder phần cứng sẽ lỗi ngầm và lùi về dùng phần mềm gây nóng và lag máy).
- Dùng `ScreenCapturerAndroid` của WebRTC để lấy luồng màn hình.
- Nhận và xử lý Signaling (ICE, Offer, Answer) từ server, thiết lập kết nối TURN bằng HMAC credential.
- **Lắng nghe 2 DataChannels:**
  - Kênh `"control"`: Đọc toạ độ và lệnh rồi đẩy cho `ScreenControlService` (Accessibility) thực thi ngay lập tức.
  - Kênh `"clipboard"`: Đồng bộ text vào clipboard của Android.

---

## Verification Plan

### Manual Verification
1. **Relay Server (TURN):** Start docker-compose, dùng trang web [Trickle ICE](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/) kèm HMAC credentials mới sinh để test xem Coturn server có trả về các candidate kiểu `relay` hay không.
2. **Signaling & Connection:** Nhấn "Remote" từ Web Dashboard. Kiểm tra quá trình bắt tay Offer/Answer, đảm bảo video stream hiện lên nhanh chóng.
3. **DataChannel Latency:** Click và kéo chuột trên Web, kiểm tra log của Android `ScreenControlService` xem sự kiện được nhận qua DataChannel tức thời không.
4. **Network Drop Test:** Thử chuyển Android từ Wifi sang 4G để kích hoạt ICE restart (`restartIce()`) và kiểm tra xem WebRTC có tự động chuyển đường qua TURN server mượt mà không.

> ✅ Fixed: Added stress tests (Session Leak, Reconnect Backoff, Concurrent Session)

### Stress Tests & Failure Scenarios
- **Session Leak Test:** Mở 10 kết nối cùng lúc, sau đó force kill ứng dụng Android một cách đột ngột. Chờ 60 giây và gọi API kiểm tra active session count của backend xem có trở về `0` (không rò rỉ session).
- **Reconnect Backoff Test:** Chặn port của TURN server giữa phiên kết nối để mô phỏng mất kết nối dài hạn. Kiểm tra console/log để đảm bảo hệ thống client sẽ thực hiện retry với logic exponential backoff (1s, 2s, 4s, 8s...) thay vì spam reconnect liên tục làm sập server.
- **Concurrent Session Test:** Chạy script hoặc mở thủ công để mô phỏng 5 admin đang remote 5 thiết bị khác nhau *đồng thời*. Đo lường tải của signaling server để đảm bảo không bị thắt cổ chai ở bước bắt tay.
