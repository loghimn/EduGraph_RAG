import pytest
from app.concept_extraction.llm_filter import verify_and_map_evidence, PROMPT_TEMPLATE, PROMPT_VERSION

def test_prompt_loading():
    # Kiá»m tra dÃ²ng PROMPT_VERSION khÃ´ng bá» gá»­i cho LLM
    assert "PROMPT_VERSION" not in PROMPT_TEMPLATE
    assert PROMPT_VERSION == "v3"
    
    # Kiá»m tra khÃ´ng lá»i khi náº¡p {}
    # Thay tháº¿ <<TEXT>> vÃ  <<CANDIDATES>> báº±ng str.replace khÃ´ng gÃ¢y lá»i KeyError nhÆ° str.format
    try:
        final_prompt = PROMPT_TEMPLATE.replace("<<TEXT>>", "test").replace("<<CANDIDATES>>", "cands")
        assert "{" in final_prompt
    except Exception as e:
        pytest.fail(f"Prompt loading failed with replace: {e}")

def test_evidence_mapping_different_chunk():
    chunk_texts = [
        "First chunk doesn't have it.",
        "Second chunk has the important evidence.",
        "Third chunk."
    ]
    
    # LLM báº£o nÃ³ á» chunk 1 (tá»©c index 0)
    preferred_idx = 0
    evidence = "Second chunk has the important evidence."
    
    mapped = verify_and_map_evidence(chunk_texts, evidence, preferred_idx)
    assert mapped is not None
    actual_idx, start, end = mapped
    assert actual_idx == 1  # TÃ¬m tháº¥y á» chunk thá»© 2 (index 1)

def test_evidence_normalization():
    chunk_texts = [
        "Here is a quote: \u201cMachine Learning\u201d with some double   spaces."
    ]
    # LLM tráº£ vá» dáº¥u nhÃ¡y kÃ©p chuáº©n vÃ  má»t khoáº£ng tráº¯ng
    evidence = 'Here is a quote: "Machine Learning" with some double spaces.'
    
    mapped = verify_and_map_evidence(chunk_texts, evidence, 0)
    assert mapped is not None
    actual_idx, start, end = mapped
    assert actual_idx == 0
    # Äáº£m báº£o ranh giá»i láº¥y chuáº©n xÃ¡c trÃªn text gá»c
    assert "quote:" in chunk_texts[0][start:end]

def test_json_missing_chunk_field():
    # MÃ´ phá»ng JSON thiáº¿u chunk, default = 1, parse_chunk = 1 - 1 = 0
    c = {"canonical": "test", "evidence": "my evidence"}
    chunk_num = c.get("chunk", 1) - 1
    assert chunk_num == 0
    # Sau ÄÃ³ verify_and_map_evidence sáº½ tá»± rÃ  soÃ¡t toÃ n bá» náº¿u chunk 0 sai.


def test_path_traversal():
    from app.ingestion.loaders import get_safe_path
    from app.core.errors import InvalidPathError
    with pytest.raises(InvalidPathError):
        get_safe_path('doc-1', '../../windows/system32/cmd.exe')

def test_mention_scanner_boundaries():
    from app.concept_extraction.mention_scanner import scan_mentions
    # 'lim' vs 'limit'
    from app.config import settings
    settings.ALIAS_SCAN_MIN_LEN = 3
    chunks = [{'id': 'c1', 'text': 'limit x to 0, lim(x) is 0. And a lim... in the end lim'}]
    concepts = [{'key': 'lim', 'name': 'lim', 'aliases': []}]
    mentions = scan_mentions(chunks, concepts)
    # Nên kh?p 3 ch?: lim(x), lim..., lim ? cu?i
    assert mentions[0]["count"] == 3

def test_stopwords_count():
    from app.concept_extraction.candidates import load_stopwords
    # Vi stopwords from file only
    with open('app/resources/stopwords_vi.txt', 'r', encoding='utf-8') as f:
        lines = [l for l in f if l.strip() and not l.startswith('#')]
    assert 200 <= len(lines) <= 300

def test_merger_rejected():
    from app.concept_extraction.merger import merge_concepts
    nc = [{'canonical': 'Concept A', 'language': 'vi', 'evidence_chunk_id': 'c1', 'evidence_start': 0, 'evidence_end': 5}]
    db_c = [{'id': 'db-1', 'key': 'concept a', 'canonical': 'Concept A', 'has_reviewed_edge': True}]
    merged_new, merge_cands = merge_concepts(nc, db_c)
    # S? sinh ra merge_cands thay vì merge tr?c ti?p vào db_c
    assert len(merge_cands) == 1
    assert merge_cands[0] == ('Concept A', 'Concept A', 'has_reviewed_edge (Alias Match)')

def test_clean_repeating_headers():
    from app.ingestion.cleaner import remove_repeating_headers_footers
    from app.config import settings
    settings.ENABLE_HEADER_CLEANING = True
    pages = [(1, 'Edugraph AI\nTrang 1\nN?i dung 1'), (2, 'Edugraph AI\nTrang 2\nN?i dung 2'), (3, 'Edugraph AI\nTrang 3\nN?i dung 3')]
    # S? xóa 'Edugraph AI' vì l?p
    cleaned = remove_repeating_headers_footers(pages)
    assert 'Edugraph AI' not in cleaned[0][1]
    # N?u t?t config thì không xóa
    settings.ENABLE_HEADER_CLEANING = False
    cleaned_disabled = remove_repeating_headers_footers(pages)
    assert 'Edugraph AI' in cleaned_disabled[0][1]

