# Personal notebooks

Authenticated users can open `/notebooks`, create a notebook, upload learning materials,
and view the private concept graph after document processing completes.

## API

- `GET /api/notebooks?page=0&size=12`: current user's notebooks only.
- `POST /api/notebooks`: `{ "title": "Algebra", "description": "", "domain": "Math", "language": "vi" }`.
- `GET /api/notebooks/{id}`: owner only.

Notebook IDs use the existing course namespace so document upload, processing controls,
status polling, downloads and graph APIs reuse the existing ingestion pipeline.
Document endpoints now accept either instructors or the owner of a personal notebook;
the document service still verifies ownership and document membership on every operation.

## Persistence and privacy

`course.is_notebook` distinguishes notebooks from courses. Existing records default to
false. The configured Hibernate `ddl-auto: update` adds the column at startup. For
managed schema deployments apply this before starting the updated service:

```sql
ALTER TABLE course ADD COLUMN IF NOT EXISTS is_notebook boolean NOT NULL DEFAULT false;
```

Notebook creation always sets `is_public=false`; course updates reject notebooks.
Notebook records are excluded from instructor course listings and public course search.
Graph reads require ownership for notebooks, even if a stale enrollment exists.
Files and AI processing require the same Supabase, database and AI core configuration
as course materials. No additional AI service or graph namespace is needed.

## Chat with uploaded materials

Open **Personal Notebook** and select **Chat with documents** in its sidebar, then
choose a notebook. The URL `/notebooks/{id}?view=chat` opens that private workspace.
The notebook navbar remains active and its sidebar contains documents, graph and chat
navigation above the conversation history. Legacy `/ask-ai?notebookId={id}` links
redirect to the notebook workspace; general Ask AI lists public knowledge and courses.
Only processed (`COMPLETED`) documents are available. The UI polls while processing
is pending, displays a document-management link, and enables chat when a document
becomes ready. Pending files are not promised as answer sources.

The existing `/api/courses/{notebookId}/chat/sessions` endpoints persist notebook
conversations, streaming responses and citations. No new database tables are needed.
The backend checks notebook ownership for session creation, messages, history and
feedback, even when stale enrollment records exist. Java forwards only the notebook ID
as `course_id` / `authorized_course_ids` to Python. Existing graph/vector retrieval
therefore remains inside that notebook. Public/global chat continues to exclude notebooks.
Changing the selected source remounts the chat workspace, clears its local draft/history
and aborts the previous stream; the old conversation remains saved under its own scope.

Validation: notebook owner/non-owner service tests, scoped SSE payload tests,
frontend URL scope tests, TypeScript, lint and translation checks. A full live answer
requires the database, Supabase, AI core and model service to be running.
