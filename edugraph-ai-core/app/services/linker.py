import os
import re
import unicodedata

def normalize_concept_key(text: str) -> str:
    """
    Chuẩn hóa theo SPEC: NFC + casefold + bỏ dấu câu + gộp khoảng trắng.
    GIỮ LẠI dấu tiếng Việt.
    """
    if not text:
        return ""
    
    # 1. NFC normalization
    text = unicodedata.normalize("NFC", text)
    
    # 2. Casefold
    text = text.casefold()
    
    # 3. Bỏ dấu câu (giữ lại chữ cái, số, và khoảng trắng)
    # \w bao gồm cả các chữ cái có dấu tiếng Việt, \s là khoảng trắng
    text = re.sub(r'[^\w\s]', ' ', text)
    
    # 4. Gộp khoảng trắng
    text = " ".join(text.split())
    
    return text

def normalize_text_unaccent(text: str) -> str:
    """
    Chuẩn hóa không dấu: chỉ dùng cho fulltext search và tính toán lúc nạp, KHÔNG LƯU.
    """
    text = unicodedata.normalize("NFD", text)
    text = re.sub(r'[\u0300-\u036f]', '', text)
    text = text.replace('đ', 'd').replace('Đ', 'D')
    return text

def is_valid_alias_for_exact_match(alias: str, min_len: int = 4, acronym_min_len: int = 2) -> bool:
    """
    Kiểm tra xem alias có đủ điều kiện để quét exact match không.
    - Dài >= 4 ký tự.
    - HOẶC là từ viết tắt (toàn viết hoa) >= 2 ký tự.
    """
    alias_strip = alias.strip()
    if len(alias_strip) >= min_len:
        return True
    if len(alias_strip) >= acronym_min_len and alias_strip.isupper() and alias_strip.isalpha():
        return True
    return False

def find_exact_mentions(chunk_text: str, aliases: list[str]) -> list[tuple[int, int]]:
    """
    Tìm vị trí (start, end) của các alias trong văn bản bằng regex (có ranh giới từ \b).
    Để tránh lỗi ranh giới từ với tiếng Việt, ta chuẩn hóa văn bản trước khi tìm.
    Tuy nhiên SPEC yêu cầu ánh xạ offset ngược về text gốc, nên ta tìm trên văn bản gốc 
    bằng cách escape các regex.
    """
    mentions = []
    for alias in aliases:
        if not is_valid_alias_for_exact_match(alias):
            continue
            
        # Tìm không phân biệt hoa thường, ranh giới từ
        pattern = r'(?i)\b' + re.escape(alias.strip()) + r'\b'
        for match in re.finditer(pattern, chunk_text):
            mentions.append((match.start(), match.end()))
            
    return mentions
