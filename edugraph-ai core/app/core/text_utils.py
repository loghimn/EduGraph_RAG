import unicodedata
import re

def normalize_concept_key(text: str) -> str:
    """NFC + casefold + bỏ dấu câu + gộp khoảng trắng. GIỮ LẠI dấu tiếng Việt."""
    if not text:
        return ""
    text = unicodedata.normalize("NFC", text)
    text = text.casefold()
    text = re.sub(r'[^\w\s]', ' ', text)
    text = " ".join(text.split())
    return text

def normalize_text_unaccent(text: str) -> str:
    """Chỉ dùng cho fulltext search và tính toán lúc nạp, KHÔNG LƯU."""
    text = unicodedata.normalize("NFD", text)
    text = re.sub(r'[\u0300-\u036f]', '', text)
    text = text.replace('đ', 'd').replace('Đ', 'D')
    return text

def normalize_evidence(text: str) -> str:
    """Chuẩn hóa để so khớp evidence: NFC, gộp space, đồng nhất ngoặc kép/nháy."""
    text = unicodedata.normalize("NFC", text)
    text = re.sub(r'[\u201c\u201d\u2018\u2019"\'`]', '"', text)
    text = re.sub(r'[\u2010-\u2015\u2212]', '-', text)
    text = " ".join(text.split())
    return text

def find_verbatim_evidence_offset(raw_chunk: str, evidence_text: str):
    """
    Tìm evidence trong raw_chunk, bỏ qua khác biệt về khoảng trắng kép hoặc dấu nháy/gạch ngang.
    Trả về (start, end) nếu tìm thấy, ngược lại trả về None.
    Cách hoạt động: Dùng Regex xây dựng từ evidence_text để tìm trên raw_chunk.
    """
    if not evidence_text:
        return None
        
    norm_evidence = normalize_evidence(evidence_text).strip()
    if not norm_evidence:
        return None

    # Tạo regex pattern từ evidence: mỗi từ (word) sẽ được nối bằng [\s\u200b]+
    # Các dấu câu sẽ được escape.
    words = norm_evidence.split(' ')
    
    # Hàm escape tùy chỉnh: nếu là dấu nháy/gạch ngang thì khớp nhiều loại
    escaped_words = []
    for word in words:
        w_esc = ""
        for char in word:
            if char == '"':
                w_esc += r'[\u201c\u201d\u2018\u2019"\'`]'
            elif char == '-':
                w_esc += r'[\u2010-\u2015\u2212-]'
            else:
                w_esc += re.escape(char)
        escaped_words.append(w_esc)
        
    pattern = r'[\s\u200b]*'.join(escaped_words)
    
    # Tìm kiếm không phân biệt hoa thường để mềm dẻo hơn một chút xíu? 
    # SPEC yêu cầu "character for character... same spelling, diacritics". Nên KHÔNG (?i).
    try:
        match = re.search(pattern, unicodedata.normalize("NFC", raw_chunk))
        if match:
            return match.start(), match.end()
    except:
        pass
    return None
