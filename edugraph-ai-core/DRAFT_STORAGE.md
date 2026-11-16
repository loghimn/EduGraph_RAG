# Storage and recovery for document approval

Course ingestion stages private chunks, concepts and proposed edges before publication.
The original upload remains in Supabase; Java stores document/job status in its database.
AI stores its working files in `RAW_DATA_DIR/<documentId>/` and the review workspace in
`RAW_DATA_DIR/.graph-drafts/<documentId>.json` plus `<documentId>.payload`.
Neither review file may be discarded while the document awaits approval.

## Deployment

- Set `RAW_DATA_DIR` to an absolute persistent path (for example `/data/raw`) and mount
  a durable disk/volume at that path. Mount the whole directory, including hidden files.
  Back up the metadata and payload together. Do not expose the directory as static files.
- Relative paths resolve against the AI project directory, not the shell's working directory.
- Use one AI replica with one Uvicorn worker. Task ownership and locks are process-local.
  A shared disk alone does not make multiple workers safe.
- Do not use `--reload` in production. Source changes/restarts interrupt extraction;
  startup recovery marks interrupted tasks failed so the owner can retry them.
- A redeploy must preserve Java's database, Supabase objects, the AI volume and Neo4j.
  Persistent original uploads do not replace the AI review payload.

## Recovery

If a document says `AWAITING_REVIEW` but preview returns missing data:

1. Check the running AI service's configured data volume, permissions and matching
   document/course/owner identifiers. Restore the original metadata and payload if backed up.
2. If the review workspace is gone, use **Rebuild draft from original** in the review dialog,
   **Regenerate draft** in the document list, or retry a failed document. Java downloads the
   original object from Supabase and restarts extraction with a new run/review identity.
3. Wait for `AWAITING_REVIEW`, inspect the graph and publish explicitly. Regeneration never
   publishes automatically. A previous review ID must not approve the replacement draft.

The poller turns a missing upstream document (404) into a retryable system failure only
when Java was awaiting review. A transient AI outage does not invalidate a valid draft.
AI similarly marks a missing review payload failed; preview returns an actionable conflict.
Publication receipts in Neo4j are checked before treating a missing payload as a failure.

For lost originals or expired Supabase access, retry can fail; preserve the error and repair
storage access instead of marking the document completed or bypassing approval.

## Updating drafts after another document is published

New payloads retain `extracted_concepts` before graph merging, including evidence
offsets and concept embeddings. `POST /documents/{id}/review/update` accepts the
current `review_id`, starts an owned background task, and returns immediately.
Java exposes the same operation under `/api/document/course/{courseId}/documents`.
The review response includes `can_update`, `update_status` and `update_error` for polling.

Updates reuse stored chunks and extracted concepts, re-run merging/mention scanning
against the current graph and infer prerequisites again. They never publish or
re-embed/re-extract the document. A successful update replaces the review ID and
version; reviewers must inspect the replacement and select exclusions again.
Publication is blocked while an update runs. If the graph changes during updating,
or inference fails, the previous review remains intact and the update can be retried.
Startup recovery marks interrupted updates failed without losing that review.
Older payloads without `extracted_concepts` need one full regeneration first.

## DAG and publication

Publication acquires the same Course write lock used by graph editing, checks the
draft version, writes the selected relationships, and validates all active,
non-rejected prerequisite relationships before committing. A cycle (including a
self-loop or a pre-existing course conflict) raises HTTP 409 with
`detail.code = PREREQUISITE_CYCLE` and one cycle witness. The transaction rolls
back the document, chunks, concepts, relationships and publication receipt.
The private draft remains available for correcting the selection and retrying.
If the conflict already exists outside the draft, correct the course graph and
update the now-outdated draft (or regenerate a legacy draft). Existing curated relationships are not removed
automatically. Archived concepts and REJECTED relationships do not constrain paths.

Learning paths check the relevant subgraph before topological ordering. Cycle
reporting returns one witness rather than enumerating all possible cycles.
Topological validity does not by itself establish pedagogical difficulty.

Run `python -m pytest tests/test_document_review.py tests/test_learning_path_plan.py`.
For live Cypher verification, set `EDUGRAPH_TEST_NEO4J=1` and run
`python -m pytest tests/test_publication_dag_integration.py`. These checks create
isolated transaction-local fixtures, explicitly roll them back and verify that
no fixture nodes remain; they do not publish user documents.
