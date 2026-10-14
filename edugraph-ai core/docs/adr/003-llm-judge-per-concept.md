# ADR 003: LLM Judge Per Concept for Edge Pruning

**Status:** Accepted

## Context
The conceptual graph generated from LLM extractions can be extremely dense. We need to prune edges (prerequisites) to keep the graph manageable and prevent cycles.

## Decision
- **Pruning mechanism**: We use an LLM as a "Judge" to evaluate prerequisite candidate edges.
- **Cycle Breaking**: Deterministic cycle breaking by sorting (score, source id, target id). 
- **AUTO edges**: Auto edges broken by cycle breaking are NOT written to Neo4j. 
- `MERGE ... ON MATCH` will only update scores/sources if `status = 'AUTO'`. We will never override `CONFIRMED`, `MANUAL`, or `REJECTED` user states.

## Consequences
- Requires strong dependency tracking when saving.
- The LLM judge will increase API calls, so candidates must be cached.
- Strict determinism allows retry without chaos.
