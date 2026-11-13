# Ask AI updates — 2026-11-13

## Behavior

- Ambiguous concept matches return selectable choices. Choices are saved on the
  original USER message in the same transaction that removes the temporary BOT
  placeholder. Reopening a chat preserves the choices; selecting one resubmits
  the question with the exact concept ID.
- A failed concept match no longer ends retrieval. Authorized vector and
  fulltext searches still run. Graph, vector and fulltext results are fused
  before truncation, with lexical overlap, duplicate text removal and document
  diversity. `CHAT_MIN_VECTOR_SCORE` defaults to 0.35 and can be tuned using
  actual course questions. The default maximum is still six passages.
- Graph questions carry concept IDs and directed edge endpoints. The AI checks
  the exact active, non-rejected PREREQUISITE_OF edge and same-course evidence
  passage. The prompt distinguishes inferred AUTO edges from confirmed facts;
  review notes and scores do not become document evidence.
- After token streaming, Python emits a canonical `answer` event containing
  cleaned citation numbers, then `sources` and `done`. Java and the UI replace
  the draft with this canonical text. Legacy token/sources/done streams remain
  accepted. Valid numbering alone does not prove semantic support by a source.
- Source cards show file names, snippets and page numbers. Clicking a source
  checks current ownership/course visibility and document state before issuing
  a five-minute signed URL. PDF links include `#page=N`; browser PDF support is
  required for that fragment. Private notebook documents cannot be opened from
  global chat citations.
- Chats open with the newest 30 messages. Earlier messages load on demand with
  a stable message-ID cursor; finishing a turn merges the recent window into
  displayed history. URL `sessionId` restores the selected chat. Scope changes
  retain source isolation.
- Sidebar search queries all owned conversations in the selected scope by title
  or message content, with pagination. `%`, `_` and `!` are literal input.
  Rename and confirmed delete are available from each row's options menu.
  Delete removes citations, feedback and messages in a transaction and refuses
  sessions with STREAMING messages. It leaves documents and graphs intact.

## Deployment

Deploy core-service, AI core and frontend together to enable all new behavior.
The existing development configuration uses Hibernate `ddl-auto: update`.
For databases managed manually, apply
[`2026-11-13-chat-clarification-history.sql`](sql/2026-11-13-chat-clarification-history.sql)
before starting core-service. It adds a nullable choices column and cursor
index, without changing existing message content. No production database was
modified as part of this source change.

## API additions

All paths exist under both `/api/chat/sessions` and
`/api/courses/{courseId}/chat/sessions` with exact scope and owner checks.

| Method | Relative path | Purpose |
| --- | --- | --- |
| GET | `/search?q=...&page=0&size=20` | Search titles and messages |
| GET | `/{sessionId}` | Restore session metadata |
| PATCH | `/{sessionId}` | Rename with `{ "title": "..." }` |
| DELETE | `/{sessionId}` | Delete conversation and dependent records |
| GET | `/{sessionId}/messages/recent?beforeId=...&size=30` | Cursor history |
| GET | `/{sessionId}/citations/{citationId}/source` | Authorized source URL |

## Verification

Automated coverage includes real Spring Security/controller/JPA requests on H2
for ownership, exact scope, literal search, rename, deletion of dependent rows,
streaming deletion refusal, cursor windows and source signing authorization.
Python tests cover fallback retrieval, cross-channel ranking, exact edge
context and final citation cleanup. Frontend tests cover session restoration,
incremental loading, selectable clarification, graph IDs, canonical answers,
server search and rename/delete.

Verified results: 340 core-service tests and 236 Python tests passed; Python
excluded 15 integration/slow tests and skipped one Windows symlink test.
Frontend tests, TypeScript production build and ESLint also passed (see the
frontend test suite for the latest count). The build retains the existing
large 3D graph bundle warning.

Actual Supabase signed URL expiry, Neo4j index relevance and model answer
quality require an environment running those external services. These tests
use controlled substitutes and do not establish that every generated claim is
supported by its citation.
