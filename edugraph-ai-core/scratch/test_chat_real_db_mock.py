import asyncio
import json
from unittest.mock import patch

from app.db.neo4j_client import neo4j_client
from app.api.chat import chat_explain_stream_generator

COURSE_ID = "demo-sql"

# Bộ câu hỏi tiếng Anh thực tế khớp với tài liệu real_doc.pdf
QUESTIONS = [
    {
        "title": "Test 1: Concept Linking & Chunk Retrieval cho câu hỏi về View & JOIN",
        "message": "How do I create a view in SQL and how does it relate to JOIN?",
        "concept_ids": None,
        # Giả lập LLM sinh câu trả lời có trích dẫn [1], [2] và cố tình thêm trích dẫn rác [99]
        "mock_tokens": [
            "In ", "SQL, ", "you ", "create ", "a ", "view ", "using ", "the ", "CREATE ", "VIEW ", "statement [1]. ",
            "Views ", "often ", "use ", "JOIN ", "operations ", "to ", "combine ", "multiple ", "tables [2]. ",
            "Here ", "is ", "an ", "invalid ", "citation [99]."
        ]
    },
    {
        "title": "Test 2: Câu hỏi nâng cao về INSTEAD OF trigger và soft deletion",
        "message": "How does an INSTEAD OF trigger handle soft deletion on a view?",
        "concept_ids": None,
        "mock_tokens": [
            "An ", "INSTEAD ", "OF ", "trigger ", "intercepts ", "modifications ", "on ", "views [1]. ",
            "For ", "soft ", "deletion, ", "it ", "updates ", "the ", "deleted ", "flag ", "instead ", "of ", "removing ", "rows [2]."
        ]
    }
]

async def run_mock_chat_test():
    await neo4j_client.connect()
    try:
        from app.core.embedding import embed_model
        embed_model.load()

        print("======================================================================")
        print("          MOCKTEST CHAT (DỮ LIỆU THẬT NEO4J + CÂU HỎI TIẾNG ANH)       ")
        print("======================================================================\n")

        for q_idx, q in enumerate(QUESTIONS, start=1):
            print(f"----------------------------------------------------------------------")
            print(f"{q['title']}")
            print(f"Question: \"{q['message']}\"")
            print(f"----------------------------------------------------------------------")

            async def fake_stream(prompt, priority=True):
                # In ra độ dài prompt và số chunk được nạp từ DB
                for token in q["mock_tokens"]:
                    await asyncio.sleep(0.01)
                    yield token

            with patch("app.api.chat.llm_client.generate_stream", side_effect=fake_stream):
                generator = chat_explain_stream_generator(
                    course_id=COURSE_ID,
                    message=q["message"],
                    concept_ids=q["concept_ids"]
                )

                events_received = []
                tokens_streamed = []
                sources_received = []

                async for sse_chunk in generator:
                    lines = [l for l in sse_chunk.strip().split("\n") if l]
                    if len(lines) >= 2:
                        event_name = lines[0].replace("event: ", "").strip()
                        raw_data = lines[1].replace("data: ", "").strip()
                        data_obj = json.loads(raw_data) if raw_data else {}
                        
                        events_received.append(event_name)

                        if event_name == "token":
                            tokens_streamed.append(data_obj.get("token", ""))
                        elif event_name == "sources":
                            sources_received = data_obj

                print(f"[SSE Events Flow]: {' -> '.join(events_received)}")
                print(f"[Full Answer Streamed]: {''.join(tokens_streamed)}")
                print(f"[Sources Retrieved & Filtered from Neo4j]: {len(sources_received)} nguồn")
                for s in sources_received:
                    print(f"   * [{s['citation']}] Chunk ID: {s['chunk_id']} | Page: {s['page']} | Index: {s['index']}")
                    print(f"     Preview: \"{s['text'][:120]}...\"\n")
                
                # Kiểm chứng citation [99] có bị server loại bỏ hay không
                if "[99]" in q["mock_tokens"][-1]:
                    citations_in_sources = [s["citation"] for s in sources_received]
                    assert 99 not in citations_in_sources, "LỖI: Trích dẫn [99] không bị loại bỏ!"
                    print("=> XÁC NHẬN: Bộ lọc trích dẫn đã loại bỏ hoàn toàn [99] ngoài phạm vi!\n")

    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(run_mock_chat_test())
