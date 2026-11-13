# Learning paths inside Ask AI

Requests such as `Muốn học Servlet cần biết gì trước?`,
`Cho tôi lộ trình học để master Servlet` and
`Create a learning path to master Servlet` now use the graph path engine.
The chat composer also offers a **Tạo lộ trình học** shortcut.

## Flow

1. Recognize an explicit path/prerequisite request without a model call.
   Ordinary explanations and explicit graph-relationship questions retain the
   existing RAG answer flow. Explicit target names skip optional history rewriting.
2. Resolve the target against active names/aliases in the authorized scope.
   Multiple matching concepts produce persisted clarification choices, including
   the course name. Selecting a choice resends the original question with its ID.
   Missing targets request a specific concept/source instead of inventing steps.
3. Load the selected course's versioned graph. Paths never combine separate
   courses. Rejected relationships and archived concepts are excluded.
4. Traverse all ancestors of the goal, report direct and indirect prerequisites,
   and compute stages using the existing `generate_learning_path(mode="goal")`.
   Same-stage concepts can be studied in parallel. A cycle in the relevant
   subgraph blocks generation; an unrelated cycle does not.
5. For selected course/notebook chat, Java supplies the learner's saved MASTERED
   concept IDs. Only those nodes are removed. Knowing a node does not implicitly
   mark its ancestors mastered. Dependency ordering is retained through removed
   mastered nodes: A → mastered B → C still places A before C, with B taking no
   learning stage. Global/public chat uses a general path without
   the learner's private mastery data.
6. Fetch the step evidence passages inside the same course, render numbered
   citations, and label inferred AUTO relationships. The ordered answer is
   rendered deterministically, so the model cannot invent or reorder steps.
7. Use the existing token/answer/sources/done protocol. The result and citations
   persist as a normal chat answer and reopen through history.

This creates a path **inside the conversation**. It does not automatically
create a separate progress-tracking `LearningPathPlan` or update mastery.
Existing Learning Path creation and progress tracking remain available through
their original screens and APIs.

## Limits and verification

- At most 200 goal-related concepts per chat path; broader goals receive a
  clear request to select a smaller target rather than a silently partial path.
- At most 24 source passages per answer, numbered consistently with source cards.
- No recorded prerequisites is reported as missing graph information, not proof
  that no prerequisite knowledge is necessary.
- A snapshot follows the graph version at generation time. Subsequent graph
  edits do not rewrite old messages.
- No schema migration is needed for this feature.

Tests exercise Vietnamese/English/no-accent intent recognition, ordinary and
negated requests, direct and indirect dependencies, topological stage order,
mastery filtering, duplicate targets, private scope, related/unrelated cycles,
isolated goals, evidence citations, oversized goals, HTTP/SSE serialization and
the Java durable-answer protocol. External Neo4j and model services are mocked
in automated regression tests.
