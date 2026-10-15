# ADR 001: Vector in Neo4j

**Status:** Accepted

## Context
We need to store and query text chunks and their embeddings for the RAG and conceptual extraction process. Neo4j offers native vector search since version 5.11 (and in Aura).

## Decision
We will use Neo4j's native vector index (`db.index.vector.queryNodes`) for both storing graph nodes (`Chunk`) and their embeddings (1024 dimensions from `bge-m3`).
No separate vector database (like Milvus or Qdrant) will be introduced in this phase to reduce operational complexity and take advantage of unified graph-vector traversals.

## Consequences
- Data is unified in Neo4j.
- Neo4j Aura Free tier limits might apply, so we need to strictly monitor chunk storage and dimension configurations (1024).
- The vector index must be created explicitly using Cypher DDL upon initialization.
