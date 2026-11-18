# CI/CD cho edugraph-rag.io.vn

Phương án: GitHub Actions build/test trên GitHub-hosted runner; image lưu trong GHCR; máy Windows của bạn chạy Docker Desktop và một self-hosted runner chỉ dành cho deploy. PostgreSQL, Neo4j, Supabase và SMTP dùng dịch vụ bên ngoài. Máy cần luôn bật và không sleep khi phục vụ website.

```text
Pull request -> Java/Python/frontend tests -> Docker build kiểm tra
Merge vào main -> tests -> GHCR image gắn sha-<commit>
Run Deploy home server -> pull image -> health checks -> kiểm tra origin
                                    -> lỗi: khôi phục image trước

https://edugraph-rag.io.vn -> Cloudflare Tunnel -> frontend:80 (Nginx)
  /                        -> React SPA
  /api/*, /oauth2/*, /login/* -> gateway-service:8080 -> core-service:8081
                                                       -> ai-core:8082
```

Hai repo có SHA độc lập: một tag backend dùng chung cho ba service, một tag riêng cho frontend. Không tự deploy khi PR chạy; workflow deploy chỉ nhận nhánh mặc định và dùng environment `production`. Hiện CD được kích hoạt bằng tay để chọn cặp phiên bản; sau khi kích hoạt, các bước triển khai/kiểm tra/rollback chạy tự động.

## 1. Chuẩn bị máy một lần

1. Cài/chạy Docker Desktop với WSL2 và Linux containers; `docker version` phải có Server và `docker compose version` phải chạy được. Compose cần hỗ trợ `up --wait`.
2. Cài PowerShell 7 (`pwsh`), Git. Runner cần chạy bằng tài khoản có quyền dùng Docker Desktop và đọc file secrets. Docker Desktop cần được khởi động trong phiên Windows đó trước khi deploy; runner chạy dưới một Windows service account khác có thể không thấy Docker.
3. Tắt sleep tự động, cho Docker Desktop và runner khởi động cùng phiên đăng nhập. Kiểm tra lại sau reboot. `restart: unless-stopped` chỉ hoạt động khi Docker Engine đang chạy.
4. GPU: cần NVIDIA driver và GPU passthrough trong WSL2/Docker Desktop. Workflow tạo riêng image CPU và CUDA 12.1 (`torch==2.5.1`); bật `gpu` khi deploy để dùng image CUDA và yêu cầu một NVIDIA GPU.

Máy kiểm tra hiện có Quadro M620 2 GB VRAM. Chưa xác nhận tương thích GPU trong container; bắt đầu `EMBED_BATCH_SIZE=1`. Nếu lỗi device/kernel/VRAM, chọn `gpu=false` để chạy CPU. Chỉ chạy một AI container với một Uvicorn worker vì khóa xử lý tài liệu đang nằm trong bộ nhớ tiến trình.

## 2. Đưa thay đổi vào cả hai repository

- Backend: `.github/workflows/ci.yml`, `.github/workflows/deploy.yml`, Dockerfile của ba module và thư mục `deploy/`.
- Frontend: `.github/workflows/ci.yml`, `Dockerfile`, `.dockerignore`, `deploy/nginx.conf`, `.env.example`.
- Tạo PR vào `main` ở cả hai repo. Bật branch protection và yêu cầu các job kiểm tra pass trước merge.
- CI dùng Java 21, Python 3.12 và Node 22; máy kiểm tra local dùng JDK 25, biên dịch target 21. CI sẽ xác minh lại trên đúng JDK runtime.

Backend kiểm tra Maven `verify`, Python unit/API tests không gọi dịch vụ bên ngoài, Compose CPU/GPU và script deploy. Frontend chạy `npm ci`, lint, toàn bộ `tests/*.test.mjs` và production build. Image chỉ publish sau các job kiểm tra pass trên push vào nhánh mặc định. Build PR không publish.

Tên image (owner phải viết thường):

```text
ghcr.io/<backend-owner>/edugraph/core-service:sha-<backend-commit>
ghcr.io/<backend-owner>/edugraph/gateway-service:sha-<backend-commit>
ghcr.io/<backend-owner>/edugraph/edugraph-ai-core:sha-<backend-commit>
ghcr.io/<backend-owner>/edugraph/edugraph-ai-core-gpu:sha-<backend-commit>
ghcr.io/<frontend-owner>/edugraph/frontend:sha-<frontend-commit>
```

Nếu tổ chức hạn chế package publishing, cho phép workflow tạo/ghi GHCR package. Giữ package private hoặc public theo nhu cầu. Nếu package đã tồn tại từ trước, cấp Actions access cho đúng repository.

## 3. Tạo file secrets trên máy server

Sao chép `deploy/.env.example` đến vị trí ngoài checkout, ví dụ `C:/edugraph-secrets/production.env`, thay toàn bộ `CHANGE_ME`. Chỉ cấp quyền đọc cho tài khoản vận hành/runner. Không gửi file này lên GitHub hay đưa vào Docker build context. Nếu password chứa `$`, dùng dấu nháy đơn theo ví dụ để tránh Compose nội suy.

Điền:

- `REGISTRY_PREFIX=ghcr.io/<backend-owner>/edugraph`.
- `FRONTEND_IMAGE=ghcr.io/<frontend-owner>/edugraph/frontend`.
- Kết nối PostgreSQL, Neo4j, Supabase, SMTP; tài khoản/khóa cho môi trường production.
- `JWT_SECRET`: Base64 của tối thiểu 32 byte ngẫu nhiên. `AI_CORE_INTERNAL_KEY`: secret ngẫu nhiên riêng. Compose dùng cùng giá trị JWT cho hai Java service và cùng internal key cho Java/Python.
- Khóa LLM và Google OAuth. Không đặt secret vào biến `VITE_*` vì chúng xuất hiện trong JavaScript trình duyệt.
- `BACKEND_TAG`, `FRONTEND_TAG`: tag của build thành công. Script deploy sẽ ghi đè hai biến này cho mỗi lần chạy.

Trong container, `localhost` là container đó. Nếu DB chạy trực tiếp trên Windows host, dùng `host.docker.internal`; không dùng `localhost`. Nếu DB chạy trong Docker stack khác, cấu hình mạng/kết nối tương ứng trước.

Profile production tắt SQL log/Swagger và bật secure session cookie. Hiện vẫn dùng `DB_DDL_AUTO=update` để tương thích cơ chế schema upgrade của dự án. Sao lưu DB trước mỗi release. Chỉ chuyển sang `validate` sau khi schema được tạo và có quy trình migration đầy đủ; rollback image không hoàn tác schema hay dữ liệu.

## 4. Domain và Cloudflare Tunnel

1. Thêm `edugraph-rag.io.vn` vào Cloudflare DNS. Với full DNS setup, cập nhật nameserver được Cloudflare cấp tại Mắt Bão, giữ đủ bản ghi hiện tại, đặc biệt MX/TXT nếu có email. Không cần chuyển nhà đăng ký tên miền.
2. Tạo remotely-managed Cloudflare Tunnel, lấy connector token và điền `CLOUDFLARE_TUNNEL_TOKEN` trên server. Không đưa token vào workflow hoặc commit.
3. Tạo public hostname `edugraph-rag.io.vn`, service HTTP `http://frontend:80` vì connector chạy cùng mạng Compose. Không dùng `localhost:8088` bên trong container cloudflared.
4. Bật chuyển HTTP sang HTTPS. Không đặt Access login trước các public route nếu chưa chủ động tích hợp cơ chế đó.
5. Origin chỉ bind `127.0.0.1:8088`; không mở router/forward port 8080/8081/8082. Java actuator chỉ dùng nội bộ, Nginx không proxy `/actuator`.
6. Nginx đã tắt buffering cho chat và đặt timeout 240 giây. Kiểm tra rule cache/proxy ở Cloudflare không cache `/api/*`, `/oauth2/*`, `/login/*`.

Không trỏ A record về IP LAN của máy. Với phương án tunnel này, DNS hostname được quản lý qua Cloudflare thay cho A record đến VPS đã nêu ở kế hoạch cũ.

`CLOUDFLARED_IMAGE` có thể đặt thành version/digest cụ thể đã kiểm tra; mặc định dùng `cloudflare/cloudflared:latest`. Image ứng dụng dùng tag commit, không dùng `latest`.

## 5. Google OAuth

Google Console phải có Authorized redirect URI chính xác:

```text
https://edugraph-rag.io.vn/login/oauth2/code/google
```

Nếu OAuth consent app đang ở testing, thêm các tài khoản demo vào test users hoặc hoàn thành cấu hình phát hành tương ứng. Frontend build mặc định dùng `/api` và gateway origin `https://edugraph-rag.io.vn`. Nginx chuyển OAuth và callback qua gateway; backend chuyển về `/auth/callback` hoặc `/auth/error`.

## 6. Đăng ký runner và GHCR credentials

1. Trong **backend repository**, Settings -> Actions -> Runners -> New self-hosted runner -> Windows x64. Dùng hướng dẫn/token GitHub cấp tại thời điểm đăng ký.
2. Thêm label `edugraph-production`; giữ các label `self-hosted`, `Windows`, `X64`.
3. Settings -> Environments -> tạo `production`. Chỉ cho deploy từ nhánh mặc định. Nếu muốn có người duyệt mỗi release, bật required reviewers tại đây.
4. Tạo environment variable `DEPLOY_ENV_FILE=C:/edugraph-secrets/production.env`.
5. Đăng nhập GHCR bằng đúng tài khoản Windows chạy runner. Với image private, dùng token có quyền `read:packages` cho package của cả hai repo, qua `docker login ghcr.io --username <github-user>` và nhập token tại lời nhắc. Không đưa token vào đối số command/log.
6. Chỉ dùng runner này cho deploy từ repo tin cậy; không gắn job PR vào runner cá nhân. Không cần mở cổng inbound cho GitHub runner.

## 7. Deploy lần đầu

1. Đợi workflow backend/frontend trên `main` thành công và các image xuất hiện trong GHCR.
2. Sao chép tag `sha-` cộng **đủ 40 ký tự commit** của từng repo.
3. Backend repo -> Actions -> **Deploy home server** -> Run workflow trên nhánh mặc định.
4. Điền backend/frontend tags; chọn `gpu` theo kiểm tra thực tế, chọn `tunnel=true` sau khi đã cấu hình token/hostname.
5. Script kiểm tra cấu hình/secrets, pull toàn bộ image trước khi đổi container, chờ health và kiểm tra frontend/SPA/protected API. Khởi động model lần đầu có thể mất nhiều phút vì tải model; thời gian chờ deploy là 20 phút.
6. Kiểm tra domain trên trình duyệt: login thường/Google, reset password, upload tài liệu, duyệt đồ thị, chat stream và lộ trình học. Health checks không thay thế kiểm tra nghiệp vụ này.

Chạy từ máy local cũng được (PowerShell 7, trong backend checkout):

```powershell
./deploy/deploy.ps1 -EnvFile C:/edugraph-secrets/production.env `
  -BackendTag sha-<40-character-backend-SHA> `
  -FrontendTag sha-<40-character-frontend-SHA> -Gpu -Tunnel
```

Các SHA trong ví dụ là placeholder, phải thay trước khi chạy. Bỏ `-Gpu` để chạy CPU. `-ValidateOnly` chỉ kiểm tra cấu trúc Compose, không pull/start container và không xác minh credentials thật.

## 8. Theo dõi, rollback và backup

Sau deploy thành công, script lưu `current-release.json` và `previous-release.json` cạnh file secrets, không chứa credentials. Nếu health/smoke check của release mới lỗi, script tự khôi phục tag và chế độ GPU/tunnel của release thành công trước đó, rồi vẫn đánh dấu job thất bại. Lần deploy đầu chưa có release cũ nên cần sửa lỗi rồi chạy lại.

Rollback chủ động: chạy lại workflow với tags và chế độ GPU/tunnel trong `previous-release.json`. Không rollback database tự động. Nếu registry/image repository thay đổi, cập nhật cấu hình và kiểm tra lại trước rollback.

Với lệnh Compose chạy tay, truyền đúng cả hai tags từ `current-release.json`; script deploy không sửa file `.env`. Ví dụ xem trạng thái mà không hiện secrets:

```powershell
$release = Get-Content C:/edugraph-secrets/current-release.json -Raw | ConvertFrom-Json
$env:BACKEND_TAG = $release.backendTag
$env:FRONTEND_TAG = $release.frontendTag
docker compose --env-file C:/edugraph-secrets/production.env -f deploy/compose.yml ps
```

Khi dùng GPU/tunnel, thêm `-f deploy/compose.gpu.yml` / `--profile tunnel` cho các thao tác quản trị cần đúng cấu hình đó. Không in `docker compose config` đầy đủ vì chứa secrets; dùng `config --quiet`. Giới hạn log Docker đã đặt 10 MB x 3 file mỗi service.

Backup PostgreSQL và Neo4j theo công cụ nhà cung cấp; backup Supabase Storage theo chính sách dự án. Backup Docker volume `edugraph_ai-raw` khi đã dừng AI để giữ file gốc và bản nháp nhất quán. `edugraph_huggingface` và `edugraph_ai-cache` là cache, có thể tạo lại. Không dùng `docker compose down -v` khi cập nhật vì sẽ xóa volume dữ liệu. Thử khôi phục backup trước khi coi quy trình hoàn tất.

## Tài liệu chính thức

- [GitHub Actions publishing Docker images](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)
- [Docker Compose GPU support](https://docs.docker.com/compose/how-tos/gpu-support/)
- [Cloudflare Tunnel FAQ / DNS setup](https://developers.cloudflare.com/cloudflare-one/faq/cloudflare-tunnels-faq/)
- [Gateway CORS header deduplication](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/deduperesponseheader-factory.html)
