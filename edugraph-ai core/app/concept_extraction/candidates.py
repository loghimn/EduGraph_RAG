from keybert import KeyBERT
from sklearn.feature_extraction.text import CountVectorizer
import numpy as np
from typing import List, Dict, Any
from app.config import settings

# Singleton KeyBERT, dùng lại embed_model
_keybert_model = None

def get_keybert_model():
    global _keybert_model
    if _keybert_model is None:
        from app.core.embedding import embed_model
        
        # Chú ý: KeyBERT nhận trực tiếp `SentenceTransformer` model object
        _keybert_model = KeyBERT(model=embed_model._model)
    return _keybert_model

def load_stopwords() -> List[str]:
    import os
    vi_path = os.path.join(os.path.dirname(__file__), "..", "resources", "stopwords_vi.txt")
    stopwords = []
    if os.path.exists(vi_path):
        with open(vi_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    stopwords.append(line)
    # Lấy stopwords tiếng Anh từ sklearn
    from sklearn.feature_extraction.text import ENGLISH_STOP_WORDS
    stopwords.extend(list(ENGLISH_STOP_WORDS))
    return stopwords

def extract_candidates(chunks: List[Dict[str, Any]], doc_embeddings: List[List[float]]) -> List[List[str]]:
    """
    Trích xuất từ khóa cho danh sách chunks.
    Sử dụng doc_embeddings tính sẵn.
    Trả về list of candidate lists, độ dài bằng len(chunks).
    """
    if not chunks:
        return []
        
    texts = [c["text"] for c in chunks]
    model = get_keybert_model()
    stopwords = load_stopwords()
    
    vectorizer = CountVectorizer(ngram_range=(1, 4), stop_words=stopwords, token_pattern=r"(?u)\b\w+\b")
    
    # KeyBERT.extract_keywords hỗ trợ doc_embeddings từ version >= 0.5.0
    keywords_list = model.extract_keywords(
        texts,
        vectorizer=vectorizer,
        doc_embeddings=np.array(doc_embeddings),
        use_mmr=True,
        diversity=0.3,
        top_n=settings.KEYBERT_TOP_N
    )
    
    # Xử lý kết quả trả về. Nếu truyền list text, nó trả về list of list of tuples.
    # Tuy nhiên nếu len(texts) == 1, nó trả về list of tuples.
    if isinstance(keywords_list, list) and len(keywords_list) > 0 and not isinstance(keywords_list[0], list):
        if len(texts) == 1:
            keywords_list = [keywords_list]
            
    # Lấy ra danh sách từ khóa
    result = []
    for kw_list in keywords_list:
        cands = [kw[0] for kw in kw_list]
        result.append(cands)
        
    return result
