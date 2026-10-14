# ADR 002: Neo4j Schema & Transactions

**Status:** Accepted

## Context
We need a clear strategy to manage entity IDs, deduplication, and transaction boundaries during data ingestion to avoid dirty reads and partial updates.

## Decision
- **Concept IDs**: UUIDs. `key` is NFC, casefolded, punctuation stripped, whitespace merged, BUT keeps Vietnamese accents.
- **Constraints**: 
  - `course_id` is scoped. 
  - We use a synthetic property `course_key = course_id + "|" + key` and apply a single-property `UNIQUE` constraint on it, since Aura Free supports single-property unique constraints but not composite ones.
- **Transactions**: All chunking, embedding, LLM calls, and alias merging MUST be calculated in RAM first. We will write to Neo4j using ONE SINGLE transaction per document (`UNWIND`), which increments the course version exactly once.

## Consequences
- Better data consistency if the process crashes midway.
- RAM requirements are higher per document, but we enforce `EMBED_BATCH_SIZE` limits.
- Resolves concurrency issues across documents in the same course.
