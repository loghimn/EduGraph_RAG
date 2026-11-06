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
