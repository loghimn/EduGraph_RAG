# SPEC: Python service cho hệ thống học tập theo đồ thị khái niệm

> Đây là nguồn sự thật duy nhất cho dự án. Agent đọc file này trước khi làm bất cứ việc gì.
> Nếu spec mâu thuẫn với code cũ trong repo, spec thắng. Nếu spec mơ hồ, hỏi người dùng, không tự đoán.

---

## 0. Mục tiêu

Xây Python service (FastAPI) cho đồ án "học tập theo đồ thị khái niệm":

1. Đọc tài liệu (PDF / DOCX / MD / TXT, tiếng Việt + tiếng Anh lẫn lộn).
2. Trích xuất khái niệm (concept).
3. Xác định quan hệ tiên quyết giữa các khái niệm (đồ thị có hướng, không chu trình).
4. Chat kiểu GraphRAG: nhận câu hỏi tự nhiên, trả về lộ trình học hợp lệ kèm trích dẫn chunk gốc.

Ví dụ câu hỏi: *"Tôi muốn học Đạo hàm, tôi đã biết Giới hạn. Cần học gì nữa?"*
Hệ thống trả về: (a) lộ trình theo thứ tự tô-pô, không vi phạm tiên quyết; (b) lời giải thích có trích dẫn `[1][2]` trỏ về chunk gốc.

---

## 1. Cách làm việc (BẮT BUỘC)

1. Làm theo **5 giai đoạn** ở mục 8. Trước mỗi giai đoạn: viết implementation plan ngắn và **chờ người dùng duyệt**. Sau mỗi giai đoạn: chạy test, báo cáo (file đã tạo, cách chạy, **điều chưa kiểm chứng**), rồi **DỪNG** chờ người dùng nói "tiếp tục". Không làm trước giai đoạn sau.
2. Repo có thể đã có code cũ trong `prerequisite/` và `concept_extraction/`. Đọc trước, giữ phần tốt (cache embedding, fallback LLM, `LLMUnavailableError`), sửa theo spec này, và liệt kê những gì đã đổi so với bản cũ. *(Kiểm tra 2026-10-07: repo không có code cũ, chỉ có `app/config.py` và `app/main.py` rỗng.)*
3. **Không bịa API thư viện.** Kiểm tra theo phiên bản đã cài (đọc docstring, source, docs), đặc biệt: KeyBERT, sentence-transformers, neo4j driver, cú pháp tạo vector index. Ghim version bằng `pip freeze` **sau khi** cài và chạy được, không ghim theo trí nhớ.
4. **Không nuốt lỗi.** Lỗi LLM / DB / file phải raise exception có tên rõ ràng hoặc ghi vào `Document.error`.
5. Không đọc hay commit `.env` thật (`.env` nằm trong `.gitignore`). Agent chỉ tạo và sửa `.env.example`. `.env` thật do người dùng quản lý.
6. Chỗ nào mơ hồ thì hỏi. Nêu rõ giả định đang dùng.
7. Mọi con số (ngưỡng, trọng số, top-k, thời gian) trong spec là **điểm khởi đầu**, chưa được đo. Để trong `config.py` để chỉnh, không rải trong code.

---

## 2. Ràng buộc kỹ thuật

### Ngôn ngữ và kiểu code
- Python 3.11+, type hints đầy đủ, docstring cho hàm công khai.
- Async cho I/O. Việc nặng CPU (embedding, parse file) chạy qua `asyncio.to_thread`, không chặn event loop.

### Embedding
- Model: `BAAI/bge-m3` (sentence-transformers), 1024 chiều, chuẩn hóa vector.
- Load **đúng một lần** (singleton, FastAPI `lifespan`) và warmup khi khởi động. Dùng chung cho cả 3 phần.
- KeyBERT phải nhận **model singleton này**. Không để KeyBERT tự load bản thứ hai.
- Mỗi chunk embed **một lần**, dùng lại cho cả KeyBERT lẫn ghi Neo4j (nếu KeyBERT không nhận embedding tính sẵn thì báo rõ, không âm thầm embed lại).
- Lưu kèm `embedding_model` trên node để biết khi nào phải embed lại.

### LLM
- OpenAI (chính) + Gemini (dự phòng tùy chọn). Cùng gọi qua SDK `openai`.
- Model chính (OpenAI): `gpt-5.4-mini` (chủ đạo cho mọi tác vụ). Để trong `config.py` (`MODEL_MAIN`).
- Gemini là **TÙY CHỌN**: nếu không có `GEMINI_API_KEY` hoặc `GEMINI_API_KEYS` thì chỉ dùng OpenAI (không dùng dummy key), khi OpenAI lỗi thì raise `LLMUnavailableError` ngay lập tức. Giữ nguyên code fallback và test mock phòng khi dùng.
- Retry với backoff dựa trên rate limit header của API, `Semaphore` giới hạn số request đồng thời (khởi đầu 2-3 để tránh 429 trên gói Free).
- Với stream: fallback chỉ thực hiện nếu có Gemini và lỗi xảy ra trước token đầu tiên.
- Với yêu cầu JSON trên GPT-5.x/6.x, dùng `reasoning_effort=none` và ngân sách đầu ra cấu hình được qua `LLM_JSON_MAX_OUTPUT_TOKENS` (mặc định 3000); log `finish_reason` để phát hiện trường hợp bị cắt vì token.
- Cache kết quả bằng `diskcache`. Chạy cùng 1 tài liệu lần hai thì không tốn request LLM nào (100% từ cache).
- Bắt JSON bằng chế độ JSON của provider. **Mọi JSON trả về là object** (ví dụ `{"concepts": [...]}`).

### Thư viện được phép
- Lõi: `fastapi`, `uvicorn`, `pydantic-settings`, `sse-starlette`, `sentence-transformers` (+ `torch`), `keybert`, `neo4j`, `openai`, `diskcache`, `rapidfuzz`, `unidecode`, `networkx`, `numpy`, `pymupdf`, `python-docx`, `tenacity` (hoặc tự viết retry), `langchain-text-splitters` (nếu cần).
- Test: `pytest`, `pytest-asyncio`, `httpx`.
- Không cần `python-multipart` (Python không nhận upload). Thư viện khác: hỏi trước.

### Không dùng
- LangChain (chỉ được dùng gói `langchain-text-splitters` cho chunker, nếu cần).
- spaCy, FAISS, Chroma.
- sklearn trong code eval (sklearn vẫn được cài như dependency của KeyBERT).
- `token_set_ratio` của rapidfuzz một mình để gộp alias (nó gộp nhầm "gradient" với "gradient descent").

### Quy ước code
- Prompt để trong `prompts/*.txt`, mỗi file có `PROMPT_VERSION`; version đi vào cache key.
- **Mọi câu Cypher chỉ nằm trong `db/repo.py`.** Ngoại lệ duy nhất: `db/schema.py`, chỉ chứa DDL. Cypher của script (kể cả `smoke_neo4j.py`) cũng nằm trong `repo.py`.
- Ghi Neo4j bằng `UNWIND` (khoảng 100 chunk mỗi câu).
- **Quy tắc transaction:**
  - Tính toàn bộ trong RAM trước (đọc file, chunk, embed, gọi LLM, gộp concept). Sau đó ghi **một transaction cho mỗi tài liệu**, gồm nhiều câu `UNWIND`: xóa chunk cũ, ghi chunk / concept / `MENTIONS`, dọn concept mồ côi, tăng `Course.version` **đúng 1 lần**.
  - Lỗi xảy ra trước bước ghi thì dữ liệu cũ còn nguyên.
  - Bước tiên quyết cũng ghi trong **một transaction** và tăng version một lần.
  - Nếu Aura từ chối transaction quá lớn thì chia lô, tăng version ở mỗi lô, và **báo người dùng**.
- Log thời gian từng bước. Không hardcode secret.
- Test dùng model nặng gắn marker `slow`. Test mặc định mock LLM và Neo4j.

---

## 3. Kiến trúc

```
Giao diện ──► Java (Spring Boot) ──► Neo4j Aura
                  │                      ▲
                  │ gọi sau khi upload   │ ghi / đọc
                  ▼                      │
              Python FastAPI ────────────┘
              POST /ingest, POST /chat (SSE), DELETE /documents/{id},
              POST /courses/{id}/prerequisites, GET /courses/{id}/knowledge-graph
```

- **Java** lo: đăng nhập, upload file vào `/data/raw/<doc_id>/<filename>`, tạo `Course`, `Document(UPLOADED)`, cạnh `HAS_DOCUMENT` và `Document.seq`, chặn upload trùng `file_hash`, gọi Python, xem / sửa đồ thị (confirm / remove / add cạnh), kiểm quyền theo `owner_id`.
- **Python** lo: `/ingest` (phần 0-2), `/chat` (phần 3), xóa tài liệu (`DELETE /documents/{id}`, để toàn bộ logic dọn dẹp nằm một chỗ), chạy lại bước tiên quyết cho một khóa học, và cung cấp graph concepts/quan hệ tiên quyết cùng chi tiết nguồn concept.
- **Knowledge graph:** Java cung cấp `GET /api/courses/{courseId}/knowledge-graph` cho chủ khóa học hoặc học viên đã đăng ký; endpoint proxy graph concepts và cạnh `PREREQUISITE_OF` từ Python. `GET /api/courses/{courseId}/knowledge-graph/concepts/{conceptId}` trả chi tiết concept cùng các chunk evidence, trang và tài liệu nguồn. FE chỉ gọi Java; Python vẫn private.
- Hai bên chỉ chia sẻ Neo4j và `/data/raw/` (cùng máy hoặc cùng mount). Đường dẫn file = `/data/raw/<doc_id>/<filename>`; sau khi resolve phải kiểm tra vẫn nằm trong `/data/raw` (chống path traversal).
- **Đã xác nhận:** Python không public; Java gọi kèm header `X-Internal-Key`. Body của `/ingest` là `{document_id, course_id, user_id}`; `/chat` cũng có `course_id`, `user_id`. Python đối chiếu với DB: `Document` thuộc `Course`, `Course.owner_id = user_id`.
- Neo4j chạy trên cloud (AuraDB): URI dạng `neo4j+s://...`, có độ trễ mạng, nên gộp truy vấn và ghi theo lô.
- Chat chạy độc lập với pipeline trích xuất: `retrieval/` chỉ đọc từ Neo4j.
- **Tiến trình:** uvicorn **1 worker**; `/ingest` và `/chat` chung một tiến trình (bge-m3 chỉ load một lần, khóa theo course có tác dụng). Ingest chạy **tối đa 1 tài liệu cùng lúc**, thêm `asyncio.Lock` theo `course_id`.
- **Phục hồi khi khởi động (single-worker):** mọi `Document` còn `PROCESSING` đều bị coi là mồ côi (BackgroundTasks chết cùng tiến trình) nên đổi thẳng thành `FAILED`, `error = "interrupted by restart"` (không lọc theo tuổi `updated_at`).

- **Máy chạy (dev):** Ryzen 7 5800H, RAM 15 GB, GPU RTX 3050 Ti Laptop 4 GB VRAM, Python 3.12.10.

---

## 4. Lược đồ Neo4j (nguồn sự thật duy nhất, không tự đổi)

### Node

| Node | Thuộc tính |
|---|---|
| `Course` | `id`, `name`, `owner_id`, `version` (int) |
| `Document` | `id`, `course_id`, `seq`, `filename`, `file_hash`, `status`, `error`, `updated_at` |
| `Chunk` | `id` = `<doc_id>:<index>`, `doc_id`, `course_id`, `index`, `page`, `heading_path`, `char_start`, `char_end`, `text`, `text_unaccent`, `embedding` (1024), `embedding_model` |
| `Concept` | `id` (UUID, bất biến sau khi tạo), `course_id`, `key`, `course_key` (`course_id` + `\|` + `key`), `name`, `aliases[]`, `language` (`vi`/`en`/`mixed`), `order` (int), `embedding` (1024), `embedding_model` |

`Document.status`: `UPLOADED` → `PROCESSING` → `DONE` | `FAILED` (kèm `error`). Chỉ `DONE` khi **cả** trích xuất concept lẫn bước tiên quyết đều xong.

### Quan hệ

| Quan hệ | Thuộc tính |
|---|---|
| `(Course)-[:HAS_DOCUMENT]->(Document)` | |
| `(Document)-[:HAS_CHUNK]->(Chunk)` | |
| `(Chunk)-[:MENTIONS]->(Concept)` | `is_evidence` (bool), `start`, `end` (offset trong `Chunk.text`, **không bao giờ null**) |
| `(a:Concept)-[:PREREQUISITE_OF]->(b:Concept)` | `status`, `score`, `llm_score`, `rule_score`, `source`, `evidence`, `evidence_chunk_id`, `model`, `prompt_version`, `reviewed_by`, `reviewed_at` |

`(a)-[:PREREQUISITE_OF]->(b)` đọc là **"a là tiên quyết của b"** (ví dụ `derivative → gradient`).
`PREREQUISITE_OF.status`: `AUTO` | `CONFIRMED` | `REJECTED` | `MANUAL`. `source`: `LLM` | `RULE` | `HYBRID`.
`PREREQUISITE_OF.evidence` là **lời giải thích ngắn do LLM sinh**, không phải trích dẫn. `evidence_chunk_id` là chunk đã đưa cho LLM xem khi chấm.

### Quy ước

- **`Concept.id` = UUID, bất biến.** Sau khi tạo thì không đổi dù tên đổi.
- **`Concept.key`** = NFC + casefold + bỏ dấu câu + gộp khoảng trắng, **giữ dấu tiếng Việt** (tránh gộp nhầm kiểu "bàn" / "bản"). Bản không dấu của tên/alias chỉ dùng cho `concept_linker` và fulltext, được tính khi nạp đồ thị, **không lưu trên `Concept`**. (`Chunk.text_unaccent` vẫn lưu để làm fulltext index.)
- **`Concept.language`:** LLM trả `language` (`vi` | `en`) cho mỗi concept. Gán `mixed` khi có alias khác ngôn ngữ với tên.
- **`Concept.embedding`** tính từ chuỗi `"name; alias1; alias2"` (tối đa `CONCEPT_EMBED_MAX_ALIASES` alias). Tính lại khi alias đổi.
- **`Concept.order`** = `Document.seq * 100000 + Chunk.index` của lần xuất hiện đầu tiên. Không đổi khi thêm tài liệu sau.
- **`MENTIONS` có 2 loại:**
  - (a) LLM trả concept kèm evidence trong chunk đó: `is_evidence = true`, `start` / `end` theo vị trí evidence.
  - (b) Quét thêm trên mọi chunk bằng khớp exact alias đã chuẩn hóa, có ranh giới từ. Chỉ quét alias dài ≥ `ALIAS_SCAN_MIN_LEN` ký tự, hoặc viết tắt toàn chữ hoa dài ≥ `ACRONYM_MIN_LEN`. Gán `is_evidence = false`, `start` / `end` theo chỗ khớp.
  - Khi truy xuất mà có nhiều chunk `is_evidence = true`, lấy chunk có `index` nhỏ nhất.
- **Gộp concept:**
  - Node thắng là node **đã có trong DB**; `id` của nó giữ nguyên. Xóa node thua, chuyển `MENTIONS` và `PREREQUISITE_OF` sang node thắng. `order` lấy giá trị nhỏ nhất.
  - Python chỉ được gộp khi node thua **không có** cạnh `CONFIRMED` / `MANUAL` / `REJECTED`. Ngược lại thì **không gộp**, ghi cặp đó vào danh sách `merge_candidates` trong báo cáo. Trường hợp này chỉ xảy ra khi cả hai node đều đã có trong DB.
  - Hai cạnh trùng sau khi gộp: giữ cạnh đã duyệt, bỏ cạnh `AUTO`. Nếu cả hai đều `AUTO` thì giữ cạnh điểm cao hơn. Bỏ self-loop phát sinh do gộp.
  - Concept mới được so với concept đã có trong DB của khóa học.
- **`Course.version`** là số nguyên. **Mọi** thay đổi concept hoặc cạnh (cả Python lẫn Java) đều `SET c.version = c.version + 1` trong **cùng transaction** với thay đổi đó. Python tăng **đúng 1 lần** mỗi transaction ghi (xem "Quy tắc transaction" ở mục 2). Chat dùng version này để biết khi nào nạp lại đồ thị.
- **Re-ingest** một tài liệu (idempotent): xóa `Chunk` cũ, ghi mới, rồi dọn concept mồ côi theo đúng quy tắc xóa tài liệu. Tất cả nằm trong transaction ghi cuối. Python xử lý bất cứ khi nào được gọi (Java đã chặn trùng `file_hash`).
- **Xóa tài liệu** (`DELETE /documents/{id}` của Python): xóa `Chunk` của nó; xóa `Concept` không còn `MENTIONS` và không có cạnh `CONFIRMED`/`MANUAL`; tăng version.
- **Ai sở hữu `status`:** Python chỉ ghi cạnh mới với `ON CREATE SET status = 'AUTO'`. Việc đổi `status` (confirm / remove / add) do Java làm. Python **không bao giờ** cắt, ghi đè hay đề xuất lại cạnh `CONFIRMED` / `MANUAL` / `REJECTED`.
- **Python ghi / cắt cạnh `AUTO` theo 4 quy tắc:**
  1. Cạnh `AUTO` bị cắt khi phá vòng thì **không ghi** vào Neo4j (nếu đã có sẵn thì xóa).
  2. Với concept vừa được chấm lại, cạnh `AUTO` cũ mà LLM không chọn nữa thì xóa. Concept không được chấm lại thì không đụng đến cạnh của nó.
  3. Cạnh đã tồn tại: `MERGE ... ON MATCH` chỉ cập nhật `score` / `llm_score` / `rule_score` / `source` khi `status = 'AUTO'`. Không bao giờ đổi `status`, không đụng cạnh `CONFIRMED` / `MANUAL` / `REJECTED`.
  4. Cắt vòng phải **tất định**: chọn cạnh `AUTO` yếu nhất theo thứ tự `(score, id nguồn, id đích)`, để cùng đầu vào cho cùng kết quả. Log danh sách cạnh bị cắt mỗi lần chạy.
- Java **không trả** trường `embedding` về giao diện (nặng khoảng 4 KB mỗi cái).

### DDL (`db/schema.py`, chạy idempotent)

```cypher
CREATE CONSTRAINT course_id   IF NOT EXISTS FOR (n:Course)   REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT document_id IF NOT EXISTS FOR (n:Document) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT chunk_id    IF NOT EXISTS FOR (n:Chunk)    REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT concept_id  IF NOT EXISTS FOR (n:Concept)  REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT concept_course_key IF NOT EXISTS FOR (n:Concept) REQUIRE n.course_key IS UNIQUE;

CREATE INDEX chunk_course     IF NOT EXISTS FOR (n:Chunk)    ON (n.course_id);
CREATE INDEX chunk_doc        IF NOT EXISTS FOR (n:Chunk)    ON (n.doc_id);
CREATE INDEX concept_course   IF NOT EXISTS FOR (n:Concept)  ON (n.course_id);
CREATE INDEX document_course  IF NOT EXISTS FOR (n:Document) ON (n.course_id);

CREATE VECTOR INDEX chunk_embedding IF NOT EXISTS
FOR (c:Chunk) ON (c.embedding)
OPTIONS {indexConfig: {`vector.dimensions`: 1024, `vector.similarity_function`: 'cosine'}};

CREATE FULLTEXT INDEX chunk_text IF NOT EXISTS
FOR (c:Chunk) ON EACH [c.text_unaccent];
```

Kiểm tra cú pháp theo phiên bản Aura thật. **Không dùng property existence constraint** (có thể chỉ có ở bản Enterprise). Vector index trả top-k trước rồi mới lọc `course_id`, nên truy vấn phải lấy dư (k = 50) rồi lọc, retry với k lớn dần khi kết quả rỗng (adaptive overfetch).

**Đã xác nhận:** Sử dụng constraint một thuộc tính `concept_course_key` dựa trên trường hợp gộp `course_key = course_id + "|" + key`. Aura Free hỗ trợ constraint dạng này. Lệnh `MERGE` concept sẽ được thực hiện dựa trên `course_key`.

---

## 5. Cây thư mục

Thư mục gốc của repo chính là service Python (không có tiền tố `python-service/`).

```
./
├── requirements.txt
├── .env.example
├── .gitignore                   # có .env
├── docs/adr/
│   ├── 001-vector-in-neo4j.md
│   ├── 002-neo4j-schema.md
│   └── 003-llm-judge-per-concept.md
├── app/
│   ├── main.py                  # FastAPI + lifespan (load bge-m3, mở driver, warmup, phục hồi PROCESSING)
│   ├── config.py                # biến môi trường, hằng số (top-k, ngưỡng, tên model)
│   ├── resources/
│   │   └── stopwords_vi.txt     # soạn tay 200-300 từ, ghi nguồn tham khảo ở đầu file
│   ├── api/
│   │   ├── ingest.py            # POST /ingest, POST /courses/{id}/prerequisites
│   │   ├── documents.py         # DELETE /documents/{id}
│   │   └── chat.py              # POST /chat (SSE)
│   ├── core/
│   │   ├── embedding.py         # singleton bge-m3, embed qua to_thread
│   │   ├── llm.py               # OpenAI → Gemini qua SDK openai, retry, stream
│   │   ├── cache.py             # diskcache + cache_key()
│   │   ├── text_utils.py        # NFC, unidecode, n-gram
│   │   └── errors.py            # ScannedPDFError, UnsupportedFormatError,
│   │                            # LLMUnavailableError, GraphNotDagError,
│   │                            # DatabaseError, DocumentNotFoundError,
│   │                            # PermissionDeniedError, InvalidPathError
│   ├── db/
│   │   ├── neo4j_client.py
│   │   ├── schema.py            # tạo constraint và index (chỉ DDL)
│   │   └── repo.py              # TOÀN BỘ Cypher còn lại nằm ở đây
│   ├── ingestion/
│   │   ├── loaders.py           # PDF (theo trang), DOCX (+ bảng), MD/TXT
│   │   ├── cleaner.py
│   │   └── chunker.py
│   ├── concept_extraction/
│   │   ├── candidates.py        # KeyBERT
│   │   ├── llm_filter.py        # LLM lọc + thêm concept
│   │   ├── merger.py            # gộp trùng
│   │   └── pipeline.py
│   ├── prerequisite/
│   │   ├── candidate_pairs.py
│   │   ├── rule_signals.py
│   │   ├── llm_judge.py
│   │   ├── fusion.py
│   │   ├── graph_cleaner.py
│   │   └── pipeline.py
│   └── retrieval/
│       ├── concept_linker.py
│       ├── intent.py
│       ├── graph_cache.py
│       ├── path_engine.py
│       ├── chunk_retriever.py
│       └── answer.py
├── prompts/
│   ├── concept_filter.txt
│   ├── prerequisite.txt
│   ├── intent.txt
│   └── answer.txt
├── scripts/
│   ├── init_schema.py
│   ├── smoke_neo4j.py
│   ├── smoke_llm.py             # gọi thử từng model OpenAI / Gemini, báo model không tồn tại
│   └── run_pipeline.py
├── eval/
│   ├── gold/
│   ├── bench_extraction.py
│   ├── eval_prerequisite.py
│   └── eval_chat.py
└── tests/
```

**Chiều phụ thuộc một hướng:** `api` → `pipeline` → `core` / `db`. `core` và `db` không import ngược lên.
`retrieval/` **không** import `concept_extraction/` hay `prerequisite/`.

Nếu muốn bắt đầu gọn, có thể gộp mỗi thư mục con thành một file và tách khi file vượt khoảng 300 dòng.

---

## 6. Quyết định kiến trúc đã chốt (ADR)

| ADR | Quyết định | Xem lại khi |
|---|---|---|
| 001 | Vector và fulltext nằm trong Neo4j (vector index + fulltext index), không FAISS / Chroma | Truy vấn lấy chunk chậm ở điều kiện thật dù mạng ổn, hoặc vượt giới hạn node của gói Aura. Phương án dự phòng: ma trận numpy trong RAM, **vẫn không FAISS** |
| 002 | Lược đồ Neo4j ở mục 4 | Chỉ đổi khi hỏi người dùng |
| 003 | LLM judge hỏi **theo concept** (một lần gọi mỗi concept). Per-pair chỉ bật nếu ablation trên tập nhãn cho thấy precision tăng rõ | Có số đo từ `eval_prerequisite.py` |

Mỗi ADR là một file markdown ngắn: Trạng thái, Bối cảnh, Quyết định, Hệ quả, Xem lại khi.

---

## 7. Hằng số khởi đầu (đặt trong `config.py`, chưa đo)

| Tên | Giá trị | Ghi chú |
|---|---|---|
| `CHUNK_TOKENS` / `CHUNK_OVERLAP` | 500 / 50 | Đếm bằng tokenizer bge-m3 |
| `KEYBERT_NGRAM_RANGE` | (1, 4) | Tiếng Việt: cụm 4 âm tiết |
| `KEYBERT_TOP_N` | 50 | Mỗi chunk |
| `CHUNKS_PER_LLM_CALL` | 4 | Ghép chunk khi gọi LLM lọc |
| `FUZZY_THRESHOLD` | 90 | `token_sort_ratio` |
| `MERGE_COSINE_MIN` | 0.85 | Gộp bằng cosine, chỉ khi hai concept khác ngôn ngữ |
| `PREREQ_TOPK_COSINE` / `PREREQ_CANDIDATES` | 30 / 15 | |
| `PREREQ_MAX_PER_CONCEPT` | 5 | Số tiên quyết tối đa LLM được chọn |
| `JUDGE_MAX_EVIDENCE_CHUNKS` / `JUDGE_CHUNK_CHARS` | 2 / 800 | Chunk của target đưa cho judge xem |
| `W_LLM` / `W_RULE` | 0.8 / 0.2 | Chỉnh theo tập nhãn |
| `EDGE_MIN_SCORE` | 0.4 | Điểm tối thiểu để ghi cạnh `AUTO`; cạnh bắt buộc có `llm_score` |
| `LLM_CONCURRENCY` | 5 | Semaphore |
| `STREAM_FIRST_TOKEN_TIMEOUT` | 8 s | |
| `GRAPH_VERSION_CHECK_INTERVAL` | 10 s | |
| `VECTOR_OVERFETCH_K` | 50 | Lấy dư rồi lọc `course_id`, retry khi rỗng (adaptive overfetch) |
| `VECTOR_OVERFETCH_STEP` | 4 | Hệ số nhân k mỗi lần retry (50 → 200 → 800) |
| `VECTOR_OVERFETCH_MAX` | 2000 | Trần k an toàn cho adaptive overfetch |
| `CHAT_MAX_CHUNKS` | 6 | Chunk đưa vào prompt; ưu tiên chunk của goal và concept missing |
| `MIN_CHARS_PER_PAGE` | 100 | Phát hiện PDF scan |
| `ALIAS_FUZZY_MIN_LEN` | 4 | Alias ngắn hơn chỉ khớp exact (linker) |
| `ALIAS_SCAN_MIN_LEN` / `ACRONYM_MIN_LEN` | 4 / 2 | Quét `MENTIONS` loại (b) |
| `CONCEPT_EMBED_MAX_ALIASES` | 3 | Số alias đưa vào chuỗi embed concept |
| `NEO4J_BATCH_SIZE` | 100 | Số dòng mỗi câu `UNWIND` |
| `FORMULA_*` | (đặt khi làm cleaner) | Ngưỡng mật độ ký hiệu, danh sách font toán, độ dài `$...$` |

---

## 8. Các giai đoạn

### GIAI ĐOẠN 1: Nền tảng

Làm: `config`, `core/*`, `db/*`, `scripts/init_schema.py`, `scripts/smoke_neo4j.py`, `scripts/smoke_llm.py`, ADR 001-003, `requirements.txt` (sau khi cài chạy được thì `pip freeze`), `.env.example`, `.gitignore`.

- `smoke_neo4j.py`: kết nối Aura, tạo schema, thử vector index với khoảng 100 vector ngẫu nhiên, đo p50 / p95 của `RETURN 1` và của vector query top-50 (sau khi làm nóng kết nối), in kết quả. Mục đích: xác nhận Aura tạo được vector index 1024 chiều và biết độ trễ thật. Kiểm tra thêm: phiên bản/edition Aura, có hỗ trợ UNIQUE `(course_id, key)` không. Dữ liệu thử dùng label/`course_id` riêng và dọn sạch sau khi chạy.
- `smoke_llm.py`: gọi thử từng model (thường, JSON mode, stream) và báo rõ nếu model không tồn tại hoặc thiếu key.
- `core/embedding.py`: singleton, `embed_batch` qua `to_thread`, warmup.
- `core/llm.py`: một lớp gọi chung OpenAI → Gemini, hỗ trợ JSON mode và stream, retry, semaphore.
- Test dùng mock cho LLM và Neo4j.

### GIAI ĐOẠN 2: Ingestion + trích xuất khái niệm

- **loaders:** PDF bằng PyMuPDF, trả `list[str]` theo trang. Nếu 0 trang hoặc trung bình < `MIN_CHARS_PER_PAGE` ký tự/trang thì raise `ScannedPDFError` (không chia cho 0). DOCX bằng python-docx, đọc cả `doc.tables`. File `.doc` cũ thì raise `UnsupportedFormatError` kèm hướng dẫn đổi sang `.docx`. MD/TXT đọc thẳng, regex lấy heading. Đường dẫn `/data/raw/<doc_id>/<filename>`, kiểm tra chống path traversal (`InvalidPathError`).
- **cleaner:**
  - NFC, gỡ gạch nối cuối dòng, bỏ header/footer lặp, phát hiện text rác do font lỗi.
  - `[FORMULA]` chỉ thay công thức **đứng riêng một dòng**. PDF: dòng dùng font toán (CMMI / CMSY / Cambria Math…) hoặc có mật độ ký hiệu cao và ít chữ. MD: `$$...$$` và `$...$` dài. Giữ nguyên ký hiệu ngắn nằm trong câu.
  - Mọi ngưỡng để config. Log số lần thay mỗi tài liệu. Thử trên 1 tài liệu Toán thật.
- **chunker:** khoảng `CHUNK_TOKENS` token (đếm bằng tokenizer bge-m3), overlap `CHUNK_OVERLAP`; mỗi chunk giữ `page`, `index`, `char_start` / `char_end`, `heading_path`.
- **candidates:**
  - KeyBERT với model singleton, `ngram_range=(1,4)`, stopword tiếng Việt (`app/resources/stopwords_vi.txt`) + tiếng Anh, `use_mmr`, `KEYBERT_TOP_N` cụm mỗi chunk. Đưa cả danh sách chunk vào một lần, không lặp từng chunk.
  - Lưu ý: CountVectorizer bỏ stopword **trước** khi tạo n-gram, nên cụm candidate có thể không liền nhau trong văn bản. Candidate chỉ là gợi ý cho LLM, **không** dùng để tìm vị trí.
- **llm_filter:**
  - Ghép khoảng 4 chunk mỗi lần gọi, gọi song song. LLM lọc candidate **và được thêm** khái niệm bị sót.
  - Mỗi concept trả về `{canonical, aliases, type, language, evidence}`, bọc trong `{"concepts": [...]}`. Không có `confidence`. `type` chỉ dùng để lọc (ví dụ bỏ `type = example`), không lưu.
  - Kiểm evidence: chuẩn hóa NFC, gộp khoảng trắng, chuẩn hóa dấu nháy / gạch ngang, rồi `find` trên từng chunk của lần gọi đó. Ánh xạ offset ngược về `Chunk.text` gốc. Không khớp thì loại concept.
  - Log tỉ lệ concept bị loại mỗi tài liệu.
  - `MENTIONS` theo 2 loại ở mục 4.
- **merger** (rẻ → đắt):
  1. `key` trùng thì gộp.
  2. `token_sort_ratio` ≥ `FUZZY_THRESHOLD` **và** không phải tập con thực sự của nhau ("gradient" ≠ "gradient descent").
  3. Alias do LLM trả trong cùng chunk là **đủ** để gộp. Thêm quy tắc viết tắt (chữ cái đầu).
  4. Cosine bge-m3 chỉ gộp khi hai concept **khác ngôn ngữ**, cosine ≥ `MERGE_COSINE_MIN` và không phải tập con của nhau.
  - Các cặp khác chỉ ghi log vào `merge_candidates`. So cả với concept đã có trong DB; quy tắc node thắng / cạnh theo mục 4.
- **pipeline:**
  - Giữ lock theo `course_id`. Đặt `Document.status = PROCESSING`, cập nhật `updated_at` ở mỗi bước.
  - Tính toàn bộ trong RAM, tính `Concept.order`, rồi ghi **một transaction** theo "Quy tắc transaction" (mục 2).
  - Sau đó chạy bước tiên quyết (giai đoạn 3) rồi mới `DONE`. Lỗi bất kỳ bước nào thì `FAILED` kèm `error`. Trong lúc làm giai đoạn 2 (chưa có bước tiên quyết) thì dừng ở trạng thái trung gian của giai đoạn đó và **ghi rõ trong báo cáo**; sang giai đoạn 3, `DONE` chỉ đặt sau khi cả hai bước xong, và tài liệu đã ingest ở giai đoạn 2 cần `POST /courses/{id}/prerequisites` để bổ sung cạnh.
- **`POST /ingest {document_id, course_id, user_id}`:** kiểm `X-Internal-Key` và quyền (mục 3), chạy nền, trả về ngay.
- **`GET /courses/{course_id}/knowledge-graph`:** trả toàn bộ concept của khóa học và cạnh tiên quyết không bị `REJECTED`; chiều cạnh `source -> target` biểu thị source là tiên quyết của target.
- **`GET /courses/{course_id}/knowledge-graph/concepts/{concept_id}`:** trả metadata concept và tối đa 50 chunk evidence làm nguồn, sắp theo thứ tự tài liệu/chunk.
- **`DELETE /documents/{id}`:** dọn dẹp theo quy tắc xóa tài liệu ở mục 4, một transaction, tăng version.
- **Test:**
  - Tiếng Việt có dấu, không dấu, mixed EN-VI. PDF scan. DOCX có bảng.
  - Evidence không phải substring thì bị loại; evidence chỉ lệch khoảng trắng / dấu nháy vẫn được nhận và offset đúng.
  - "gradient" vs "gradient descent" không bị gộp; "bàn" vs "bản" không bị gộp.
  - Re-ingest không tạo chunk trùng. Lỗi trước bước ghi thì dữ liệu cũ còn nguyên.
  - Path traversal bị chặn. Không gộp concept có cạnh đã duyệt.

### GIAI ĐOẠN 3: Quan hệ tiên quyết

- **Khi nào chạy:** tự động sau mỗi `/ingest`, cho **toàn khóa học**. Cache LLM theo danh sách ứng viên, nên concept nào không đổi ứng viên thì không gọi LLM lại. Có thêm `POST /courses/{id}/prerequisites` để chạy riêng cho một khóa học.
- **candidate_pairs:** với mỗi concept lấy top-30 theo cosine (numpy brute-force) → filter → tối đa 15 cho LLM.

  | Trạng thái cạnh | Hỏi LLM lại? | Có trong đồ thị khi cắt vòng / tính lộ trình? |
  |---|---|---|
  | `CONFIRMED`, `MANUAL` | Không | Có |
  | `REJECTED` | Không (loại khỏi ứng viên) | Không |
  | `AUTO` | Được chạy lại, nhưng không ghi đè | Có |

  Bỏ chính nó, bỏ khác khóa học. Union thêm concept cùng chunk qua `MENTIONS`; nếu vượt 15 thì ưu tiên cùng chunk, rồi tới cosine.
- **rule_signals:** regex EN + VN + biến thể không dấu ("requires", "before learning", "cần biết trước", "truoc khi hoc", "yêu cầu"...). Chỉ là điểm cộng nhỏ. Hướng cạnh xác định bằng **nhóm bắt** trong pattern. Nếu hướng ngược với đề xuất của LLM thì `rule_score = 0`.
- **llm_judge:**
  - Hỏi **theo concept** (1 lần gọi mỗi concept): "Trong các ứng viên, cái nào người học PHẢI hiểu trước target? Chỉ chọn nếu không biết nó thì không thể hiểu target. Tối đa 5."
  - Judge được xem tối đa `JUDGE_MAX_EVIDENCE_CHUNKS` chunk `is_evidence` của target (mỗi chunk cắt còn khoảng `JUDGE_CHUNK_CHARS` ký tự). Ứng viên chỉ gửi tên + 1 alias.
  - JSON `{"prerequisites": [{name, confidence, evidence}]}`. `name` phải nằm trong danh sách ứng viên đã đưa, không thì loại. `evidence` là lý do ngắn do LLM sinh (mục 4).
- **fusion:** `score = W_LLM * llm + W_RULE * rule`. `source` ∈ LLM / RULE / HYBRID. `llm_score` chỉ có cho cạnh LLM đã chọn. Cạnh **bắt buộc có `llm_score`** (chỉ có regex thì không ghi) và `score ≥ EDGE_MIN_SCORE`.
- **graph_cleaner:** nạp đồ thị (loại `REJECTED`), bỏ self-loop, lặp `nx.find_cycle`: mỗi vòng cắt cạnh `AUTO` yếu nhất theo thứ tự `(score, id nguồn, id đích)` (tất định, mục 4). Vòng chỉ gồm `CONFIRMED` / `MANUAL` thì **không cắt**, trả danh sách xung đột. Log danh sách cạnh bị cắt mỗi lần chạy. Transitive reduction và `topological_generations` chỉ tính trong RAM để vẽ, **không ghi Neo4j**.
- **Ghi cạnh:** một transaction, tăng version một lần. `MERGE ... ON CREATE SET status = 'AUTO', ...`; ghi / cắt cạnh `AUTO` theo **4 quy tắc ở mục 4**.
- **`DONE` chỉ sau khi cả hai bước (trích xuất concept + tiên quyết) xong.** Lỗi ở bước tiên quyết thì `Document` → `FAILED` kèm `error`. Chạy lại rẻ nhờ cache.
- **Lưu ý:** tài liệu đã ingest ở giai đoạn 2 (chưa có bước tiên quyết) chưa có cạnh tiên quyết. Dùng `POST /courses/{id}/prerequisites` để bổ sung.
- **Test:** vòng toàn cạnh `CONFIRMED` không bị cắt mà được báo; cặp `REJECTED` không bị đề xuất lại; LLM bịa tên ngoài danh sách thì bị loại; hướng cạnh đúng (derivative → gradient); cạnh chỉ có regex không được ghi; cạnh `AUTO` bị cắt không được ghi (và bị xóa nếu đã có); cạnh `AUTO` cũ LLM không chọn nữa bị xóa, concept không chấm lại thì giữ nguyên; `ON MATCH` không đổi `status` / không đụng cạnh `CONFIRMED`; **chạy cắt vòng hai lần liên tiếp cho kết quả giống hệt nhau**.

### GIAI ĐOẠN 4: Truy xuất + chat

Thứ tự trong `/chat`:

**Request `/chat`:** `{course_id, user_id, message, concept_ids?}`. Kiểm `X-Internal-Key` và `Course.owner_id = user_id`. Mỗi request độc lập, bản đầu chưa gửi history. Nếu có `concept_ids` (người dùng đã chọn sau event `clarify`) thì bỏ qua bước link.

1. **concept_linker:**
   - Normalize (NFC + unidecode) → n-gram 1-4 từ, **longest-match-first**, exact trước rồi rapidfuzz. Alias < `ALIAS_FUZZY_MIN_LEN` ký tự chỉ khớp exact. Không so cả câu với alias.
   - Bản không dấu của tên / alias tính khi nạp đồ thị (mục 4).
   - Khớp không dấu mà ra **nhiều** concept thì không tự chọn: gửi event `clarify`.
   - Không khớp gì thì lấy cosine numpy top-3, gửi `clarify` để **hỏi lại người dùng**.
2. **intent:** LLM chỉ **gán nhãn** `goal` / `known` cho danh sách concept đã link ở bước 1, kèm `intent` ∈ `path` | `explain` | `mixed`. Kết quả bọc trong object (`{"intent": ..., "labels": [...]}`). Kiểm tra kết quả là tập con của danh sách đó. Không có trường `confidence`.
3. **graph_cache:** cache đồ thị và embedding concept theo `(course_id, version)`; kiểm tra version tối đa mỗi `GRAPH_VERSION_CHECK_INTERVAL`. Khi nạp đồ thị thì log **toàn bộ** xung đột (vòng).
4. **path_engine:**
   - `known_all = known ∪ ancestors(known)` (có công tắc tắt giả định này).
   - `need = ∪(ancestors(goal) ∪ {goal}) − known_all`.
   - Tính need-set trước, rồi **chỉ kiểm tra DAG trên subgraph của need-set**. Xung đột ở phần không liên quan không chặn câu hỏi.
   - `lexicographical_topological_sort`, tie-break theo `Concept.order`.
   - Subgraph chưa là DAG: raise `GraphNotDagError` kèm danh sách xung đột, không dùng `assert`.
   - Trường hợp biên: goal đã nằm trong known; goal không có tiên quyết; concept không có trong đồ thị (nói rõ tài liệu chưa có).
5. **chunk_retriever:**
   - Graph-first: mỗi concept trong lộ trình lấy 1 chunk `is_evidence = true` qua `MENTIONS`; nếu nhiều thì lấy `index` nhỏ nhất.
   - Tổng tối đa `CHAT_MAX_CHUNKS`, ưu tiên chunk của goal và concept missing.
   - Câu hỏi `explain` / `mixed` thì thêm vector + fulltext (lấy k = 50 rồi lọc `course_id`, retry khi rỗng), gộp bằng RRF tự viết (khoảng 10 dòng). Embed câu hỏi bằng bge-m3 qua `to_thread`.
6. **answer:**
   - Dựng prompt, stream SSE theo thứ tự sự kiện `path` → `token` → `sources` → `done`. Gửi `error` riêng nếu lỗi giữa chừng.
   - `intent = explain` thì **không** gửi `path`.
   - Event `clarify` `{candidates: [{id, name}]}` thay cho cả luồng trên khi cần hỏi lại (kết thúc bằng `done`).
   - Lộ trình do code tính, LLM chỉ giải thích, không được đổi thứ tự hay thêm bớt bước.
   - Trích dẫn `[1][2]`; server loại số nằm ngoài danh sách chunk; `sources` lấy từ DB, không để LLM chép lại.
7. **Dữ liệu UI:** `networkx.readwrite.json_graph.cytoscape_data` của subgraph, node có `role` (`goal` / `known` / `missing`) và `order`.

Reranker: không có (mặc định tắt, để cờ cấu hình). Chỉ xem lại nếu MRR thấp trên bộ 20-30 câu hỏi có nhãn.

**Test:** câu hỏi tiếng Việt có dấu, không dấu, mixed; khớp không dấu ra nhiều concept thì gửi `clarify`; `concept_ids` bỏ qua bước link; lộ trình không vi phạm tiên quyết (kiểm tra tự động, phải bằng 0); xung đột ngoài need-set không chặn câu hỏi; trích dẫn ngoài danh sách bị loại; fallback LLM chỉ xảy ra trước token đầu.

### GIAI ĐOẠN 5: Đo

- `bench_extraction.py`: so 3 nhánh (**KeyBERT only** / **KeyBERT + LLM** / **LLM only**) trên `eval/gold/`, báo precision / recall / F1, latency, RAM, chi phí ước tính. Nếu LLM only không thua KeyBERT + LLM thì đề xuất bỏ KeyBERT.
- `eval_prerequisite.py`: P/R/F1 trên tập cạnh có nhãn, bằng Python thuần. So: LLM only, LLM + rule, có / không làm sạch DAG. Dùng để chọn trọng số và ngưỡng.
- `eval_chat.py`: tỉ lệ đúng goal / known, số lần lộ trình vi phạm tiên quyết (phải = 0), trích dẫn đúng chunk, thời gian đến `path`, đến token đầu, tổng thời gian.
- Nhãn gold do người dùng cung cấp (khoảng 8-10 tài liệu, 30-50 cạnh, 20-30 câu hỏi). Agent không tự sinh nhãn rồi đo trên chính nhãn đó.

---

## 9. KHÔNG làm

- Không dựng giao diện, không viết code Java.
- Không thêm thư viện ngoài spec khi chưa hỏi.
- Không ghi kết quả transitive reduction vào Neo4j.
- Không tự cắt hay đề xuất lại cạnh `CONFIRMED` / `MANUAL` / `REJECTED`.
- Không đổi lược đồ Neo4j khi chưa hỏi.
- Không để LLM quyết định thứ tự lộ trình hay chép lại trích dẫn.

---

## 10. Nghiệm thu mỗi giai đoạn

- `pytest` xanh (test dùng model nặng gắn marker `slow`).
- Chạy được các lệnh ghi trong báo cáo.
- Không có TODO ngầm.
- Có danh sách rõ ràng những gì **chưa kiểm chứng**.
- Báo cáo nêu các giả định đã dùng và các điểm cần người dùng quyết định.

---

## 11. Câu hỏi còn mở (hỏi người dùng khi đến lúc)

1. ~~Giao diện gọi thẳng FastAPI hay đi qua Java?~~ **Đã chốt:** đi qua Java (Python không public), Java chuyển tiếp SSE từng mảnh.
2. ~~Gói Aura~~ **Đã chốt:** AuraDB **Free**. Giới hạn node / relationship / dung lượng chưa kiểm chứng: `smoke_neo4j.py` in ra những gì đọc được, người dùng đối chiếu với console Aura.
3. ~~Ngôn ngữ tài liệu~~ **Đã chốt:** **tiếng Anh là chính**, có thể lẫn tiếng Việt. Ưu tiên cấu hình tiếng Anh; vẫn giữ xử lý tiếng Việt như spec.
4. ~~Có cần chạy `/ingest` và `/chat` trong cùng một tiến trình không?~~ **Đã chốt:** cùng tiến trình, uvicorn 1 worker (mục 3).
5. Có thêm UNIQUE `(course_id, key)` trên `Concept` không? Chờ kết quả kiểm tra Aura ở giai đoạn 1 (mục 4).
6. Bản torch: máy dev có GPU RTX 3050 Ti 4 GB. Cài torch CUDA (tải khoảng 2.5 GB, embed nhanh hơn) hay torch CPU (nhẹ, giống máy chạy thật nếu server không có GPU)?
