# CI/CD cho edugraph-rag.io.vn

Phương án: GitHub Actions build/test trên GitHub-hosted runner; image lưu trong GHCR; máy Windows của bạn chạy Docker Desktop và script deploy. Chỉ dùng self-hosted runner cho CD sau khi đã cách ly quyền truy cập theo mục 6; có thể chạy script trực tiếp trên server trong lúc chưa có cách ly đó. PostgreSQL, Neo4j, Supabase và SMTP dùng dịch vụ bên ngoài. Máy cần luôn bật và không sleep khi phục vụ website.

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

Hai repo có SHA độc lập: một tag backend dùng chung cho ba service, một tag riêng cho frontend. Tag dùng để tra cứu image; deploy chốt từng image bằng digest `repository@sha256:...` trước khi khởi động. Không tự deploy khi PR chạy; workflow deploy chỉ nhận nhánh mặc định và dùng environment `production`. Hiện CD được kích hoạt bằng tay để chọn cặp phiên bản; sau khi kích hoạt, các bước triển khai/kiểm tra/rollback chạy tự động.

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

Mỗi job publish ghi `image-release.json` vào artifact `image-<service>-<commit>-<run-attempt>`, chứa tag và digest do Buildx trả về. Artifact nằm trong đúng workflow run/attempt, giúp phân biệt các lần build lại cùng commit. Tag commit có thể bị ghi đè khi rerun; digest xác định nội dung image bất biến.

## 3. Tạo file secrets trên máy server

Sao chép `deploy/.env.example` đến vị trí ngoài checkout, ví dụ `C:/edugraph-secrets/production.env`, thay toàn bộ `CHANGE_ME`. Không gửi file này lên GitHub hay đưa vào Docker build context. Nếu password chứa `$`, dùng dấu nháy đơn theo ví dụ để tránh Compose nội suy.

Phân biệt hai yêu cầu quyền truy cập cho tài khoản vận hành/runner:

- **File secrets `production.env`:** runner cần quyền đọc; script không sửa nội dung file này. Giới hạn người được đọc secrets vào các tài khoản vận hành cần thiết.
- **Thư mục chứa trạng thái release:** runner cần quyền đọc, tạo, ghi, thay thế/đổi tên và xóa các file metadata `current-release.json`, `previous-release.json` cùng các file staging/backup `*.json.<id>.tmp`. Script hiện đặt các file này cạnh `production.env`, nên với ví dụ trên, runner cần các quyền đó trong `C:/edugraph-secrets`. Cấu hình ACL file secrets riêng nếu cần giữ file đó chỉ đọc; không cấp quyền rộng cho các tài khoản khác.

**Thư mục secrets chỉ đọc không dùng được với vị trí state hiện tại của script.** Kiểm tra quyền bằng đúng tài khoản chạy runner. Nếu cần thư mục secrets chỉ đọc hoàn toàn, phải sửa script để đặt state vào một thư mục ghi được riêng và cập nhật các đường dẫn deploy/rollback tương ứng; script hiện chưa có tùy chọn tách thư mục state.

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

## 6. Cách ly runner và GHCR credentials

Label `edugraph-production` chỉ chọn runner, không giới hạn ai được dùng nó. `environment: production`, điều kiện `if` trong YAML và yêu cầu duyệt fork PR không ngăn một workflow khác nhắm tới cùng runner. Runner có quyền đọc secrets và dùng Docker trên server nên phải được cách ly bằng GitHub settings hoặc hạ tầng, không chỉ bằng quy ước trong README.

Với repo public thuộc tài khoản cá nhân như `loghimn/EduGraph_RAG`, **không đăng ký runner trên máy production trực tiếp vào repo backend/frontend**. Khi chưa có cách ly bên dưới, chạy `deploy/deploy.ps1` trực tiếp trên server theo mục 7; CI tiếp tục dùng GitHub-hosted runners để build image.

Chọn một trong hai cách triển khai CD:

- **Runner group có chính sách giới hạn workflow:** dùng runner cấp organization/enterprise. Trong Settings -> Actions -> Runner groups, đặt Repository access chỉ cho repo deploy và Workflow access thành **Selected workflows**, chỉ cho phép `<owner>/<deploy-repo>/.github/workflows/deploy.yml@refs/heads/main` (thay bằng owner/repo thực tế). Không cho mọi workflow trong repo truy cập. Bảo vệ nhánh `main`, giới hạn người được sửa/merge workflow deploy, rồi đăng ký/chuyển runner vào group này; bỏ mọi đăng ký runner production ở cấp repo có thể đi vòng group. Nếu giao diện/gói tài khoản không hỗ trợ Selected workflows, cách này chưa đủ và phải dùng cách dưới. Job `deploy` là job duy nhất trực tiếp định nghĩa trong workflow được cho phép.
- **Repo deploy private riêng:** tạo repo chỉ các tài khoản vận hành tin cậy được truy cập; sao chép thư mục `deploy/` và `.github/workflows/deploy.yml` vào đó. Đăng ký runner chỉ với repo private này, không chia sẻ runner cho các repo ứng dụng. Chỉ giữ workflow deploy thủ công từ nhánh mặc định được bảo vệ; không chạy PR/fork PR, `pull_request_target`, hoặc tự động thực thi code/artifact từ PR của repo ứng dụng trên runner này. Tắt fork-PR workflows và giới hạn khả năng fork/quyền truy cập theo các chính sách GitHub khả dụng. Repo public vẫn chỉ build image trên GitHub-hosted runners. Với cách này, workflow deploy trong repo public chỉ là nguồn mẫu để sao chép, không dùng để kết nối máy production.

Sau khi cấu hình, quản trị viên cần xác nhận bằng settings thực tế: backend/frontend public không có runner production cấp repo; group chỉ cho phép đúng workflow và ref (hoặc runner chỉ thuộc repo deploy private, không cho người gửi PR ứng dụng truy cập); PR thông thường và fork PR không có đường cấp job cho runner production. Nếu có runner cũ chưa cách ly, dừng và gỡ đăng ký đó trước khi nối runner vào mô hình mới. Một `runs-on` thêm label production trong PR không được phép vượt chính sách này. Chỉ đánh dấu cách ly hoàn tất sau khi xác minh các điều kiện trên; README không tự áp dụng policy lên GitHub.

Thiết lập tiếp trong phạm vi deployment đã cách ly:

1. Đăng ký runner Windows x64 bằng token GitHub cấp tại thời điểm đăng ký, ở đúng group hoặc repo deploy private đã chọn.
2. Thêm label `edugraph-production`; giữ các label `self-hosted`, `Windows`, `X64`. Label này dùng cho định tuyến sau khi đã có chính sách cách ly.
3. Trong repo thực sự chạy deploy, Settings -> Environments -> tạo `production`. Chỉ cho deploy từ nhánh mặc định. Nếu muốn có người duyệt mỗi release, bật required reviewers tại đây; đây là lớp bổ sung, không thay runner isolation.
4. Tạo environment variable `DEPLOY_ENV_FILE=C:/edugraph-secrets/production.env`. Xác nhận tài khoản runner đọc được file này và tạo/thay thế/xóa được metadata trong thư mục chứa nó, theo mục 3.
5. Đăng nhập GHCR bằng đúng tài khoản Windows chạy runner. Với image private, dùng token có quyền `read:packages` cho package của cả hai repo, qua `docker login ghcr.io --username <github-user>` và nhập token tại lời nhắc. Không đưa token vào đối số command/log.
6. Không cho job build/test hoặc code PR chạy trong cùng máy/tài khoản/Docker daemon chứa production credentials. Runner không giữ secrets cũng chưa đủ nếu nó vẫn có quyền điều khiển Docker production. Không cần mở cổng inbound cho GitHub runner.

## 7. Deploy lần đầu

1. Đợi workflow backend/frontend trên `main` thành công và các image xuất hiện trong GHCR.
2. Sao chép tag `sha-` cộng **đủ 40 ký tự commit** của từng repo.
3. Nếu đã cách ly CD theo mục 6: trong repo thực sự chạy deploy, Actions -> **Deploy home server** -> Run workflow trên nhánh mặc định. Nếu chưa cách ly, dùng lệnh PowerShell trực tiếp bên dưới, không kết nối runner production vào repo public.
4. Điền backend/frontend tags; chọn `gpu` theo kiểm tra thực tế, chọn `tunnel=true` sau khi đã cấu hình token/hostname.
5. Script kiểm tra cấu hình/secrets, pull toàn bộ image theo tag rồi lấy repository digest từ image đã pull. Một Compose override tạm chỉ chứa các digest được dùng để khởi động với `--pull never`. Script chờ health, kiểm tra frontend/SPA/protected API và đối chiếu image ID của container đang chạy với image đã ghim. Khi bật tunnel, script còn kiểm tra HTTPS qua domain trước khi lưu release thành công. Khởi động model lần đầu có thể mất nhiều phút vì tải model; thời gian chờ Compose là 20 phút.
6. Kiểm tra domain trên trình duyệt: login thường/Google, reset password, upload tài liệu, duyệt đồ thị, chat stream và lộ trình học. Health checks không thay thế kiểm tra nghiệp vụ này.

Chạy từ máy local cũng được (PowerShell 7, trong backend checkout):

```powershell
./deploy/deploy.ps1 -EnvFile C:/edugraph-secrets/production.env `
  -BackendTag sha-<40-character-backend-SHA> `
  -FrontendTag sha-<40-character-frontend-SHA> -Gpu -Tunnel
```

Các SHA trong ví dụ là placeholder, phải thay trước khi chạy. Bỏ `-Gpu` để chạy CPU. `-ValidateOnly` chỉ kiểm tra cấu trúc Compose, không pull/start container và không xác minh credentials thật.

## 8. Theo dõi, rollback và backup

Sau deploy thành công, script lưu `current-release.json` và `previous-release.json` cạnh file secrets, không chứa credentials; với ví dụ trên là `C:/edugraph-secrets/current-release.json` và `C:/edugraph-secrets/previous-release.json`. Thư mục này phải cho runner tạo/thay thế/xóa metadata như mục 3. Mỗi file có tag để tra cứu, chế độ GPU/tunnel và map `images` chứa digest của từng service đã qua health/smoke check (kể cả cloudflared khi bật tunnel). Nếu health/smoke check hoặc kiểm tra image ID của release mới lỗi, script pull và khôi phục đúng các digest cùng chế độ GPU/tunnel của release trước, rồi vẫn đánh dấu job thất bại. Tag có bị ghi đè cũng không đổi image rollback. Lần deploy đầu chưa có release cũ nên cần sửa lỗi rồi chạy lại.

Trước khi đổi container, script ghi sẵn metadata mới và bản sao phục hồi vào các file tạm cạnh file đích. Sau khi health/smoke checks đạt, script công bố `previous-release.json` rồi đổi tên file metadata mới thành `current-release.json` bằng cùng filesystem; current là bước commit cuối. Lỗi staging không thay runtime. Lỗi công bố một trong hai file đi qua cùng luồng rollback runtime; nếu previous đã đổi thì script phục hồi lại bản ghi previous cũ, kể cả khi phục hồi runtime thất bại. Lần deploy đầu chưa có release để rollback thì script dừng các service vừa bật và xác minh chúng đã dừng.

Nếu rollback cũng lỗi, script cảnh báo `restoration could not be confirmed`, báo lỗi rollback riêng và vẫn ném lại lỗi deploy ban đầu. Nếu phục hồi metadata lỗi, script báo `Release-state reconciliation failed`; các bản ghi còn lại, metadata đã stage và Compose override chứa image/digest được giữ tại đường dẫn trong cảnh báo để phục hồi thủ công. Lỗi phụ có trong `Exception.Data['RollbackFailure']` hoặc `Exception.Data['StateRecoveryFailure']` khi gọi từ PowerShell. Kiểm tra container thực tế và các file trước khi retry; không coi file release cũ là xác nhận hệ thống đã được khôi phục thành công. Cơ chế này xử lý lỗi được script bắt, không phải giao dịch chống mất điện giữa các bước.

Rollback chủ động: chạy workflow với `rollback_previous=true`; script đọc `previous-release.json`, bỏ qua các input tag/GPU/tunnel và dùng đúng digest đã lưu. Không nhập lại tag cũ để rollback vì tag đó có thể đã đổi. Hoặc chạy PowerShell:

```powershell
./deploy/deploy.ps1 -EnvFile C:/edugraph-secrets/production.env -ReleaseFile C:/edugraph-secrets/previous-release.json
```

Không rollback database tự động. Digest phải còn tồn tại trong registry; giữ image của các release cần rollback. Các file release cũ chỉ chứa tag, chưa có `images`, bị từ chối trước khi thay container vì không chứng minh được image cũ. Khi nâng cấp từ định dạng đó, cần xác minh digest của các container cũ và bổ sung đủ map `images` trước khi deploy tiếp; không suy ra digest cũ từ tag hiện tại.

Khi chế độ đích là `tunnel=false`, cả deploy và rollback đều gọi `stop cloudflared` trong đúng Compose project rồi xác minh connector không còn chạy trước khi chấp nhận release. Chỉ bỏ `--profile tunnel` hoặc dùng `--remove-orphans` không bảo đảm dừng connector từ release trước. Khi `tunnel=true`, connector vẫn khởi động qua luồng `up` hiện tại.

Khi chế độ đích là `tunnel=true`, cả deploy và rollback phải qua smoke check tại `https://edugraph-rag.io.vn`: `/healthz` trả `200/ok`, `/courses` trả HTML chứa các asset JavaScript của frontend local, `/api/users/me` trả `401` khi chưa đăng nhập. Probe không theo redirect, thêm cache-busting query và header `no-cache`, dùng timeout 10 giây mỗi request, tối đa 4 lượt với khoảng nghỉ 2 giây giữa các lượt. Tunnel cần có hostname/upstream đúng trước khi chạy; lỗi public route làm release thất bại dù loopback/container health vẫn xanh. Chế độ tunnel-off chỉ kiểm tra loopback.

Workflow dùng concurrency group `edugraph-production`; script còn dùng named mutex `Global\edugraph-production` trên máy Windows để bảo vệ cùng project khi chạy trực tiếp hoặc qua runner, kể cả từ checkout/file secrets khác. Khóa được giữ từ trước khi đọc file release đến hết deploy/rollback và ghi trạng thái, rồi nhả trong `finally`. Nếu đã có lượt chạy giữ khóa, lượt mới báo bận và dừng trước khi gọi Docker; đợi lượt cũ kết thúc rồi chạy lại. Runner và người chạy trực tiếp cần quyền truy cập cùng mutex (khuyến nghị dùng cùng tài khoản Windows như hướng dẫn runner); lỗi quyền truy cập không được bỏ qua để tiếp tục deploy.

Với lệnh Compose chạy tay, truyền đúng cả hai tags từ `current-release.json`; script deploy không sửa file `.env`. Ví dụ xem trạng thái mà không hiện secrets:

```powershell
$release = Get-Content C:/edugraph-secrets/current-release.json -Raw | ConvertFrom-Json
$env:BACKEND_TAG = $release.backendTag
$env:FRONTEND_TAG = $release.frontendTag
docker compose --env-file C:/edugraph-secrets/production.env -f deploy/compose.yml ps
```

Khi dùng GPU/tunnel, thêm `-f deploy/compose.gpu.yml` / `--profile tunnel` cho các thao tác quản trị cần đúng cấu hình đó. Không in `docker compose config` đầy đủ vì chứa secrets; dùng `config --quiet`. Giới hạn log Docker đã đặt 10 MB x 3 file mỗi service.

Lệnh `ps` trên chỉ xem trạng thái. Dùng script deploy để cập nhật/rollback; chạy `compose up` trực tiếp từ file gốc sẽ dùng tag và bỏ qua các digest đã lưu. Script tạo override tạm rồi xóa sau khi kết thúc, trừ khi rollback lỗi thì giữ lại để phục hồi; không sửa file secrets hoặc mount volume dữ liệu.

Backup PostgreSQL và Neo4j theo công cụ nhà cung cấp; backup Supabase Storage theo chính sách dự án. Backup Docker volume `edugraph_ai-raw` khi đã dừng AI để giữ file gốc và bản nháp nhất quán. `edugraph_huggingface` và `edugraph_ai-cache` là cache, có thể tạo lại. Không dùng `docker compose down -v` khi cập nhật vì sẽ xóa volume dữ liệu. Thử khôi phục backup trước khi coi quy trình hoàn tất.

## Tài liệu chính thức

- [GitHub Actions publishing Docker images](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)
- [GitHub runner group workflow access policies](https://docs.github.com/en/enterprise-cloud@latest/actions/how-tos/manage-runners/self-hosted-runners/manage-access)
- [GitHub self-hosted runner security](https://docs.github.com/en/actions/reference/security/secure-use)
- [Docker Compose GPU support](https://docs.docker.com/compose/how-tos/gpu-support/)
- [Cloudflare Tunnel FAQ / DNS setup](https://developers.cloudflare.com/cloudflare-one/faq/cloudflare-tunnels-faq/)
- [Gateway CORS header deduplication](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/deduperesponseheader-factory.html)
