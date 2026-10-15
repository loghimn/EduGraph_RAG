import pytest
from app.core.cache import generate_cache_key

def test_cache_key_order():
    data1 = {"b": 2, "a": 1, "c": [3, 1, 2]}
    data2 = {"a": 1, "c": [2, 1, 3], "b": 2}
    
    key1 = generate_cache_key("modelA", "v1", data1)
    key2 = generate_cache_key("modelA", "v1", data2)
    
    assert key1 == key2, "Chênh lệch thứ tự list hoặc dict sinh ra key khác nhau!"

def test_cache_key_diff_version():
    data1 = {"a": 1}
    key1 = generate_cache_key("modelA", "v1", data1)
    key2 = generate_cache_key("modelA", "v2", data1)
    key3 = generate_cache_key("modelB", "v1", data1)
    
    assert key1 != key2, "Khác version nhưng sinh trùng key!"
    assert key1 != key3, "Khác model nhưng sinh trùng key!"
