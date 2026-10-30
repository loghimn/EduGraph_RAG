# Document processing controls

Run the updated Java core-service and Python AI core together. This change adds the `retry_count` column to `document_processing_jobs` (`INTEGER NOT NULL DEFAULT 0`);
no other additional Supabase columns are needed. The existing PAUSED/CANCELLED status
values and processing-job columns must already be available.

The AI core supports **one worker/process**. Run, for example:

```powershell
python -m uvicorn app.main:app --host 0.0.0.0 --port 8082 --workers 1
```

The task registry and per-document locks live in that process. Multiple workers
or replicas require a distributed task queue/control mechanism.

## Behavior

| Public API suffix | Java | Python |
| --- | --- | --- |
| `PATCH /pause` | Waits for Python, then saves document/job PAUSED | Cancels and drains extraction/prerequisite tasks, then saves Neo4j PAUSED |
| `POST /retry` | Creates a new job; launches after commit | Receives the file and explicit restart/run_id, starts a fresh pipeline |
| `DELETE` | Waits for Python before deleting Storage; keeps CANCELLED history | Stops tasks, cleans graph, keeps a minimal CANCELLED Document tombstone |

Base path: `/api/document/course/{courseId}/documents/{documentId}`.
All public controls require an instructor who owns the course.

Pause stops the current execution; retry starts from the beginning and uses the
existing cache where available. Completed graph writes before pause remain.
CPU/GPU work already running in a thread is allowed to finish before acknowledging
the stop; it cannot safely be killed. Cancelled pipelines do not proceed to the
next stage or leave LLM child tasks running after stop acknowledgement.

Duplicate active `/ingest` calls acknowledge the existing task. A delayed request
for the same paused run_id cannot resume it. Cancelled tombstones reject ingest.
Java serializes trigger, controls and polling with a row lock and binds queued
workers to their original job ID so an old worker cannot operate on a newer job.

If Python cannot confirm pause/cancel, Java returns an error and does not delete
the Storage file. If cancellation times out while Python finishes stopping,
repeat DELETE to complete Storage cleanup. Storage deletion remains best effort;
failures are logged. This API does not delete the existing local raw-file cache.

## Manual verification

Use disposable documents with valid PDFs and an owner instructor token.

1. Upload, poll GET on the base path until PROCESSING, then PATCH `/pause`.
   Expect PAUSED in Java and GET Python `/documents/{id}/status?course_id=...`.
   Python status requests require `X-Internal-Key`. Verify no subsequent pipeline
   stage starts after the pause response; already completed graph writes can remain.
2. POST `/retry`. Expect a new job, initially PENDING, then PROCESSING and eventually
   COMPLETED/FAILED. The new Java job ID is passed to Python as `run_id`.
3. On another active document, DELETE the base path. Expect CANCELLED in Java,
   no remaining document chunks, Storage deletion, and a minimal CANCELLED Neo4j
   Document tombstone. Verify the stopped task does not recreate graph data.
4. Send repeated ingest calls for the same active document: only one task should
   execute. Concurrent public retries serialize: the second sees PENDING or
   PROCESSING and returns 409.
5. With Python unreachable, pause/cancel must fail and the file must remain.
   A worker queued before cancellation must not start after cancellation commits.

## Automated checks

From `edugraph-ai-core`:

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_document_controls.py tests/test_api_ingest.py tests/test_concept_filter_budget.py tests/test_concept_pipeline_merge.py tests/test_token_limiter.py tests/test_token_limiter_priority.py -q
```

From `core-service`:

```powershell
.\mvnw.cmd -o "-Dtest=DocumentControlServiceTest,AiDocumentProcessingServiceTest,AiCoreClientTest" test
```

These tests mock external services. They do not replace a live check against the
deployed Java, Python, PostgreSQL, Storage and Neo4j services.
