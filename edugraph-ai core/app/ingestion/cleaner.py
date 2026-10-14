import re
import unicodedata
from collections import defaultdict
from app.config import settings

def clean_text(text: str) -> str:
    """NFC, gỡ gạch nối cuối dòng, thay công thức thành [FORMULA]"""
    # NFC
    text = unicodedata.normalize("NFC", text)
    
    # Gỡ gạch nối cuối dòng (Ví dụ: "infor-\nmation" -> "information")
    text = re.sub(r'([a-zA-Z])-\n([a-zA-Z])', r'\1\2', text)
    
    # Thay thế công thức toán học đứng riêng bằng [FORMULA]
    text = re.sub(r'\$\$.+?\$\$', '[FORMULA]', text, flags=re.DOTALL)
    
    # Bỏ khoảng trắng thừa (không bỏ \n)
    text = re.sub(r'[ \t]+', ' ', text)
    
    return text

def _normalize_line_for_header_check(line: str) -> str:
    """Chuyển chữ thường, bỏ khoảng trắng, chuẩn hóa mọi số thành <NUM>"""
    line = line.strip().lower()
    line = re.sub(r'\d+', '<NUM>', line)
    return line

def remove_repeating_headers_footers(pages: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Phát hiện các dòng lặp lại ở đầu/cuối các trang và xóa chúng."""
    if not getattr(settings, 'ENABLE_HEADER_CLEANING', False):
        return pages
        
    num_pages = len(pages)
    if num_pages <= 2:
        return pages
        
    # Thống kê tần suất các dòng (đã chuẩn hóa) ở đầu/cuối
    line_counts = defaultdict(int)
    
    for _, text in pages:
        lines = [l for l in text.strip().split('\n') if l.strip()]
        if not lines:
            continue
            
        # Lấy tối đa 3 dòng đầu và 3 dòng cuối
        heads = lines[:3]
        tails = lines[-3:] if len(lines) > 3 else []
        
        for line in heads + tails:
            norm = _normalize_line_for_header_check(line)
            if len(norm) > 3: # Chỉ đếm các dòng không quá ngắn
                line_counts[norm] += 1
                
    # Lọc ra các dòng xuất hiện >= HEADER_REPEAT_RATIO số trang
    threshold = num_pages * getattr(settings, 'HEADER_REPEAT_RATIO', 0.5)
    repeating_norms = {norm for norm, count in line_counts.items() if count >= threshold}
    
    # Dọn dẹp
    cleaned_pages = []
    for page_num, text in pages:
        lines = text.split('\n')
        kept_lines = []
        for line in lines:
            if not line.strip():
                kept_lines.append(line)
                continue
            norm = _normalize_line_for_header_check(line)
            if norm in repeating_norms:
                # Xóa dòng này (bỏ qua)
                continue
            kept_lines.append(line)
        cleaned_pages.append((page_num, "\n".join(kept_lines)))
        
    return cleaned_pages
