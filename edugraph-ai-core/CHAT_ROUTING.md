# Chat routing

Clear learning-path/prerequisite requests use the existing deterministic parser.
Other questions and unresolved references use `app/chat/intent.py` to classify
the requested action and resolve its subject from bounded conversation history.
The last explicitly replied-to message takes priority over recent history.

The model returns validated JSON: `intent` (`explain`, `learning_path`,
`prerequisites`, `clarify`), `target`, `query`, `confidence`. Unknown fields and
invalid values are rejected. A clarification, missing path target, or confidence
below 0.7 produces a follow-up question rather than a guessed learning path.

The model cannot select course IDs, assign mastery, invent edges, or calculate
the learning order. A path target is matched against names/aliases in authorized
sources, with existing fuzzy matching for spelling variants. Ambiguous matches
use the existing concept picker; missing concepts produce a scoped no-match
answer. Learning paths still use the DAG/topological path engine and saved mastery.
Definitions and other explanations continue through scoped RAG with citations.

`CHAT_INTENT_TIMEOUT_SECONDS` defaults to 8 seconds (maximum 30). Invalid output,
provider failures and timeouts fall back to the existing context-resolution and
chat flow. Stream heartbeats remain active while awaiting the classifier. The
existing model cache includes the bounded question/history/reply context.

Run the chat suites with `python -m pytest tests/test_chat_intent.py
tests/test_chat_paths.py tests/test_chat_context.py tests/test_chat_sse_api.py
tests/test_chat_streaming.py tests/test_chat_improvements.py`.
Semantic tests mock the model response and verify the downstream routing,
authorization, ambiguity, clarification and timeout behavior independently.
