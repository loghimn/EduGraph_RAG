# Manual concept creation

Public endpoints are served by core-service. Both require the authenticated course
owner; enrolled learners cannot write. AI-core endpoints require the internal key
and an actor ID supplied by core-service, never trusted from a public request.

## 1. Compute embedding and create a draft

`POST /api/courses/{courseId}/knowledge-graph/concept-drafts`

```json
{"name":"HTTP Requests","aliases":["HTTP Request"],"language":"en"}
```

Only `name`, `aliases` and `language` are accepted. Language defaults to `mixed`;
allowed values are `vi`, `en`, `mixed`. Name/aliases are limited to 200 characters,
with at most 50 aliases. Blank/punctuation-only names and aliases are rejected.

The server uses the existing normalized BAAI/bge-m3 embedding service, on the same
`name; alias; alias` representation and alias limit as ingestion. The vector stays
on the server in a `ConceptDraft`, outside the active graph.

The normal ApiResponse data contains `draft_id`, normalized input,
`embedding_ready`, `embedding_model`, `embedding_dimensions`, `expires_in_seconds`.
Drafts expire after 24 hours. Unpublished expired drafts are removed on subsequent
draft creation. A changed name/alias requires creating a new draft and embedding.

## 2. Publish

`POST /api/courses/{courseId}/knowledge-graph/concept-drafts/{draftId}/publish`

No request body is needed. The backend uses the stored draft content and vector.
It checks draft course/creator/expiry and duplicates again, then creates the node,
marks the draft published and increments Course.version in one write transaction.
The draft vector is removed after publication. Repeated publication returns the
same concept ID without creating a second node or incrementing version again.

UUID, key, course_key, order, creation metadata and origin=MANUAL are server-owned.
Order is the current maximum concept order plus one. No evidence or prerequisite
edges are fabricated. Manual nodes survive ingestion and document-deletion orphan
cleanup. The local graph cache is invalidated; other workers observe version on
their next version check under the existing cache policy.

Errors: 403 unauthorized owner/internal key; 404 inaccessible/missing draft;
409 duplicate name/key/alias; 410 expired draft; 422 invalid input.

## Frontend integration

1. Enable Compute embedding after valid input.
2. Store returned draft_id and enable Add to graph after success.
3. Clear draft_id and disable Add whenever input changes.
4. Publish the draft and refresh the course graph after success.

This repository implements backend endpoints; no frontend button is included.
Source selection, edge creation, concept editing/archive and fuzzy semantic
duplicate review are separate work. Duplicate checks here use normalized exact
name/key/alias overlap. Run the usual AI-core startup schema initialization to
install the ConceptDraft ID constraint.
