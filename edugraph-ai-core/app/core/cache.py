import hashlib
import json
from typing import Any
import diskcache
import os

CACHE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), ".cache")
cache = diskcache.Cache(CACHE_DIR)

def generate_cache_key(model: str, prompt_version: str, data: Any) -> str:
    """
    Tạo cache key bằng sha256 cho cấu trúc (model, prompt_version, data).
    Data có thể là dict hoặc list. Danh sách sẽ được sắp xếp (sort) để đảm bảo 
    cùng một nội dung nhưng khác thứ tự sẽ sinh ra CÙNG một hash (theo spec).
    """
    def _normalize(obj: Any) -> Any:
        if isinstance(obj, dict):
            # Sort keys for deterministic output
            return {k: _normalize(v) for k, v in sorted(obj.items())}
        elif isinstance(obj, list):
            # Sort lists (convert elements to string representation for safe sorting)
            try:
                sorted_list = sorted(obj, key=lambda x: json.dumps(_normalize(x), sort_keys=True))
                return [_normalize(item) for item in sorted_list]
            except TypeError:
                # Fallback if list items aren't directly sortable
                return [_normalize(item) for item in obj]
        return obj
    
    normalized_data = _normalize(data)
    key_string = f"{model}_{prompt_version}_{json.dumps(normalized_data, sort_keys=True)}"
    return hashlib.sha256(key_string.encode("utf-8")).hexdigest()
