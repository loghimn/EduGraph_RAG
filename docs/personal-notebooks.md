# Personal notebooks

## Edit the private graph

Notebook owners can edit their own graph regardless of the STUDENT/INSTRUCTOR role.
Course enrollment never grants graph write access. For ordinary courses, writes
still require the owning instructor. The same authorization policy covers concept
drafts/publication, review, edits, archive/restore and relationship operations.

In the notebook graph, click a concept or directed relationship in either 2D or 3D
to open its detail panel below the canvas. Concept details show incoming/outgoing
prerequisites and source passages, with edit, add-relationship and archive actions.
Relationship details show direction, evidence and note, with edit, reverse (confirmed
preview) and remove actions. The graph toolbar creates concepts/relationships and
opens removed items for restoration. Changes take effect immediately; no instructor
approval is needed for private notebooks.

The selected concept/relationship stays selected after save; changing endpoints
selects the new relationship. Cycles leave the edit form intact. An open edit retains
its original expected graph version even if ingestion refreshes the graph; version
conflicts require an explicit reload. The UI locks graph selection during a write.

Frontend regression checks: `npm run test:graph`, `npm run test:i18n`, `npm run build`.

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

Open **Ask AI** and select a notebook from **My notebooks** in the source dropdown.
The canonical URL is `/ask-ai?notebookId={id}`. Notebook management now contains
documents and graph only; it has no separate chat tab or chat button. Legacy
`/notebooks/{id}?view=chat` links redirect to Ask AI while preserving the private
notebook scope. The same source dropdown switches between public knowledge,
courses and owned notebooks, with chat history in the Ask AI sidebar.
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
