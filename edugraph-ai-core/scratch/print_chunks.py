import asyncio
from app.ingestion.loaders import load_document
from app.ingestion.cleaner import clean_text, remove_repeating_headers_footers
from app.ingestion.chunker import chunk_document
from app.core.embedding import embed_model
import json

def get_chunks():
    embed_model.load()
    pages = load_document('demo-sql-doc', 'scratch/real_doc.pdf')
    cleaned = [(p, clean_text(t)) for p, t in pages]
    cleaned = remove_repeating_headers_footers(cleaned)
    chunks = chunk_document('demo-sql-doc', 'demo-sql', cleaned)
    for idx in [5, 7, 9, 11]:
        if idx < len(chunks):
            c = chunks[idx]
            print(f'\n--- CHUNK {idx} (Length {len(c["text"])} chars) ---')
            print(c["text"][:300])
            print('...')
        else:
            print(f'Chunk {idx} out of bounds.')

get_chunks()
