# Concept archive and restore

Public routes below use the prefix `/api/courses/{courseId}/knowledge-graph`.
Core-service requires the authenticated course owner for all four operations.
AI-core requires the internal key; write actor IDs come from core-service.

## API flow

1. `GET /concepts/{conceptId}/deletion-impact` returns id, name, status,
   source_chunks, incoming_edges, outgoing_edges and graph_version.
   Counts include distinct linked chunks and non-rejected prerequisite edges
   whose opposite endpoint is active.
2. Show the impact and obtain confirmation in the frontend.
3. `DELETE /concepts/{conceptId}?expected_version={graph_version}` archives it.
4. `GET /archived-concepts` lists archived concepts for the owner.
5. Get the current version using deletion-impact (works for archived nodes), then
   `POST /concepts/{conceptId}/restore?expected_version={graph_version}` restores it.

Mutations return `{id, status, graph_version, changed}` in ApiResponse.data.
Repeated archive/restore is harmless: changed=false, with no version increment.
A stale version returns 409 and the current version; reload preview and reconfirm.
Missing concepts return 404; invalid/missing versions are rejected.

Restore checks the connected component containing the concept, using retained
non-rejected edges and active endpoints. A cycle returns 409 with the conflicting
edges. Edges to other archived concepts remain excluded.

## Data and pipeline rules

- New manual and ingested Concepts receive status=ACTIVE. Ingestion also assigns
  origin=AUTO and created_at, only on creation. Existing nodes without status
  are treated as ACTIVE; no backfill is needed.
- Archive sets status=ARCHIVED, archived_by and archived_at. Restore sets
  status=ACTIVE, restored_by and restored_at. Metadata records the latest actions,
  not a complete audit log.
- Source chunks, documents and edges are retained by archive/restore. No shortcut
  edges are inferred. Normal document deletion/re-ingestion can still replace
  chunks and mentions; archived concepts survive orphan cleanup without sources.
- Status and Course.version change in one transaction, using the common Course
  lock also used by ingestion, publication, document deletion and edge persistence.
- Graph loading, concept detail, linking, graph evidence retrieval, document-scoped
  plans and prerequisite generation exclude archived concepts. Edge persistence
  checks status again at write time and preserves edges incident to archived nodes.
- Ingestion keeps archived concepts as protected merge targets. Extractions that
  match existing key/name/alias/merge rules map to the old ID; status is not reset
  and archived fields are not overwritten. A substantially different name that
  fails these rules can still become a new concept and require review.
- Manual creation checks archived names/aliases too; restore the old node instead
  of creating a duplicate.
- The graph cache checks the shared Course version on each ordinary read, so
  other workers observe lifecycle changes without the former TTL delay.
- Existing stored learning-path snapshots remain; newly planned paths use the
  new graph version. Archiving does not redact source text: independent vector
  or fulltext retrieval may still find it.

This repository implements backend APIs. Frontend confirmation, archive lists
and restore buttons must be connected in the frontend project.
