# Learner mastery

All endpoints require authentication. The learner comes from the token and must
own the course or be enrolled in it.

## Frontend integration

1. On opening the learning-path form, call
   `GET /api/courses/{courseId}/masteries/me`.
2. Tick known concepts whose returned `status` is `MASTERED`.
   Each entry contains `conceptId`, `status`, `selfRated`, and `updatedAt`.
3. Submit the edited `knownConceptIds` to the existing learning-path endpoint.
   Omitted/null means use stored mastered concepts; `[]` explicitly means none.
   Unticking a concept for one plan does not erase its stored mastery.

Both `POST /api/courses/{courseId}/learning-path/plan` (preview) and
`POST /api/courses/{courseId}/learning-paths` (save) use these defaults.
Only a successful saved plan initializes missing step concepts as `NOT_STARTED`.
Submitted known concepts are saved as self-rated `MASTERED` after successful save.
Default known concepts read before the AI call are used only for generation;
they are not written back as new ratings when saving the plan.
Existing step mastery is preserved. Python's existing `known_concept_ids` contract
is unchanged.

## Update mastery

`PATCH /api/courses/{courseId}/masteries/me/concepts/{conceptId}`

```json
{"status": "MASTERED"}
```

Allowed statuses: `NOT_STARTED`, `IN_PROGRESS`, `MASTERED`.
Direct updates require an existing mastery record. Existing learning-path step
updates also synchronize mastery: starting sets `IN_PROGRESS`, completing sets
`MASTERED`. Resetting a step to pending does not erase accumulated mastery, and
activity on another plan does not downgrade a mastered concept.

## History

`GET /api/courses/{courseId}/masteries/me/concepts/{conceptId}/history`

Returns `historyId`, `oldStatus`, `newStatus`, `changedBy`, `changedAt`.
Creation has null `oldStatus`. Repeating the same status adds no history.
Automatically initialized and step-derived statuses have `selfRated: false`.
Explicit mastery updates and submitted known selections have `selfRated: true`.
Explicitly confirming an unchanged status updates this flag without adding a
status-history entry.
Mastery, history, and saved-plan/step changes commit or roll back together.
Writes are serialized per learner to prevent duplicate initialization and lost
history when different learning paths update the same concept concurrently.

No schema changes are required; the existing two mastery entities are used.
Existing saved paths are populated lazily as their steps are updated; this change
does not backfill historical completed steps.
