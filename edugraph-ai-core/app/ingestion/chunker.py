from typing import List, Dict, Any
from app.config import settings
from app.core.embedding import embed_model
from app.core.identifiers import validate_document_id, build_chunk_id, IdentifierValidationError

def chunk_document(pages: List[tuple[int, str]], doc_id: str, course_id: str) -> List[Dict[str, Any]]:
    """
    Sử dụng tokenizer bge-m3 để đếm token và cắt chunk.
    pages: danh sách (page_number, text)
    """
    validate_document_id(doc_id)
    
    # Chú ý: embedding model đang load `sentence-transformers` mô hình.
    # Ta lấy thẳng tokenizer của nó để đếm token.
    tokenizer = embed_model._model.tokenizer
    
    chunks = []
    chunk_index = 0
    
    for page_num, text in pages:
        if not text.strip():
            continue
            
        # Chia nhỏ text nếu cần (ví dụ chia theo câu/dòng rồi cộng dồn token)
        # Tuy nhiên để đảm bảo character offset chính xác trên raw text,
        # cách tốt nhất là encode toàn bộ trang lấy offset_mapping (nếu tokenizer hỗ trợ)
        # Hoặc dùng một sliding window đơn giản trên chuỗi văn bản, nhưng chunk dựa trên 
        # số lượng token đếm qua `tokenizer.encode`.
        
        # Để dễ dàng tính offset_start, offset_end cho từng từ, ta dùng regex chia từ.
        # Nhưng SPEC nói "đếm token bằng tokenizer bge-m3".
        # Phương pháp: Lặp qua văn bản, chia đoạn theo dấu chấm/xuống dòng, sau đó đếm token.
        
        lines = text.split('\n')
        current_chunk_text = ""
        current_char_start = 0
        current_tokens = 0
        
        # Một cách xử lý đơn giản:
        import re
        words_or_spaces = re.findall(r'\S+|\s+', text)
        
        chunk_words = []
        chunk_start = 0
        char_cursor = 0
        
        for item in words_or_spaces:
            # Tokenize item
            # Do bge-m3 là mô hình XLM-Roberta, đếm token chính xác cần tokenize cả câu.
            # Dùng len(tokenizer.tokenize) làm xấp xỉ an toàn.
            pass
            
        # Tạm thời để giảm độ phức tạp: chunking bằng tokenizer với return_offsets_mapping 
        # (nếu tokenzier hỗ trợ dạng fast)
        page_chunk_start = len(chunks)
        page_chunk_index = chunk_index
        try:
            tokens = tokenizer(
                text, 
                return_offsets_mapping=True, 
                add_special_tokens=False, 
                truncation=False
            )
            offsets = tokens["offset_mapping"]
            input_ids = tokens["input_ids"]
            
            idx = 0
            while idx < len(input_ids):
                # lấy CHUNK_TOKENS
                end_idx = min(idx + settings.CHUNK_TOKENS, len(input_ids))
                
                start_char = offsets[idx][0]
                # tìm end_char chính xác (bỏ qua những token có offset (0,0) nếu có)
                end_char = offsets[end_idx-1][1]
                
                chunk_text = text[start_char:end_char]
                
                chunks.append({
                    "id": build_chunk_id(doc_id, chunk_index),
                    "doc_id": doc_id,
                    "course_id": course_id,
                    "index": chunk_index,
                    "page": page_num,
                    "heading_path": "", # Sẽ parse sau nếu MD/TXT
                    "char_start": start_char,
                    "char_end": end_char,
                    "text": chunk_text
                })
                
                chunk_index += 1
                idx += (settings.CHUNK_TOKENS - settings.CHUNK_OVERLAP)
                # Đảm bảo tiến tới
                if settings.CHUNK_TOKENS - settings.CHUNK_OVERLAP <= 0:
                    idx += settings.CHUNK_TOKENS

        except IdentifierValidationError:
            raise
        except Exception:
            del chunks[page_chunk_start:]
            chunk_index = page_chunk_index

            # Nếu tokenizer không phải fast, fall back sang cắt theo ký tự (xấp xỉ 1 token = 3-4 chars)
            # 500 tokens ~ 1500 chars
            char_step = settings.CHUNK_TOKENS * 3
            char_overlap = settings.CHUNK_OVERLAP * 3
            char_advance = char_step - char_overlap
            if char_advance <= 0:
                raise ValueError(
                    "CHUNK_TOKENS must be greater than CHUNK_OVERLAP for character fallback chunking"
                )

            idx = 0
            while idx < len(text):
                end_idx = min(idx + char_step, len(text))
                chunk_text = text[idx:end_idx]
                chunks.append({
                    "id": build_chunk_id(doc_id, chunk_index),
                    "doc_id": doc_id,
                    "course_id": course_id,
                    "index": chunk_index,
                    "page": page_num,
                    "heading_path": "",
                    "char_start": idx,
                    "char_end": end_idx,
                    "text": chunk_text
                })
                chunk_index += 1
                idx += char_advance
                
    return chunks
