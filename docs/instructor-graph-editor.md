# Instructor graph review and editing

Open **Concept Graph**, select an owned course, and select a node/relationship to
edit in the detail panel below. Bulk review remains available under **Review all
concepts and relationships**. The same editor appears in the instructor course detail
graph tab. Students retain read-only course graphs, but can edit their own private
notebook graphs through the contextual detail panel. The bulk editor defaults to pending relationships and supports
search, status filters, source passages and progressive list loading.

## Workflow

- Concepts: add (embedding draft + publish), view sources, edit name/aliases/language,
  confirm, archive after an impact preview, list archived concepts and restore.
- Relationships: add prerequisite → target, confirm or reject AI suggestions, edit
  endpoints/note, remove and restore rejected relationships. Automatic evidence is
  shown when available; manual relationships do not fabricate source evidence.
- Rejection is retained as `REJECTED`, preventing subsequent extraction from
  reintroducing the same directed relationship. Replacing endpoints rejects the old
  pair and creates the new pair atomically.
- Concept removal uses the existing archive lifecycle. Saved learning-path snapshots
  stay intact. Restoring a concept or relationship checks for prerequisite cycles.
- Edited/confirmed concepts and manual concepts are protected against ingestion
  property overwrite and orphan cleanup. Renames keep the stable key/ID and old
  name as an alias; saved paths and source links keep their identity.

This is item-level curation, not a separate draft/publication system for an entire
course graph. Existing AUTO edges continue to participate in the active graph until
rejected. Rejected edges and archived concepts are excluded by the existing graph
reader; the instructor review endpoint includes rejected edges for recovery.

## API

Prefix: `/api/courses/{courseId}/knowledge-graph`.

| Method | Path | Body |
| --- | --- | --- |
| GET | `/review` | — |
| PUT | `/concepts/{id}?expected_version=N` | `name`, `aliases`, `language` |
| POST | `/concepts/{id}/review?expected_version=N` | — |
| POST | `/edges?expected_version=N` | `source_id`, `target_id`, optional `note` |
| PUT | `/edges/{source}/{target}?expected_version=N` | new `source_id`, `target_id`, optional `note` |
| POST | `/edges/{source}/{target}/review?expected_version=N` | `status` (`CONFIRMED` or `REJECTED`), optional `note` |
| DELETE | `/edges/{source}/{target}?expected_version=N` | — |

Existing concept draft/publication and archive/restore routes are reused. New editor
routes require an authenticated instructor who owns the course, or the authenticated
owner of a personal notebook regardless of role. Java supplies actor
identity to Python; public request bodies cannot select another actor.

Mutations acquire the existing Neo4j Course write lock, check the graph version,
apply changes and increment the version in one transaction. A stale version returns
409; the UI asks the instructor to reload and review rather than retry automatically.
Self-loops, cross-course/inactive nodes, duplicate active edges and cycles are rejected.
The automated prerequisite writer also rechecks cycles under that lock so a stale
LLM result cannot create a cycle against a newly confirmed/manual edge.

No SQL schema migration is needed; metadata is stored as Neo4j properties. Restart
the Java and Python services and rebuild/restart the frontend together.

## Validation

Python focused tests:

```powershell
cd edugraph-ai-core
.\.venv\Scripts\python.exe -m pytest tests/test_graph_editor.py tests/test_concept_lifecycle.py tests/test_concept_creation.py tests/test_knowledge_graph_api.py tests/test_merger_new.py -q
```

Java: `KnowledgeGraphControllerTest`, `AiRagServiceTest`, `AiCoreClientTest` cover
request validation, instructor ownership and internal actor/version forwarding.
Frontend: `npm run build`, `npm run test:i18n`.

These checks mock external services. Live smoke checks still require PostgreSQL,
Neo4j and the embedding service: create a concept, reject/restore an AUTO edge,
reverse a relationship, try a cycle and a stale save, then re-ingest a document to
verify instructor decisions remain intact.
