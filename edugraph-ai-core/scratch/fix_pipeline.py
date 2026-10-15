import re
with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f:
    text = f.read()

# Fix the dict so it maps canonical to name before saving
text = text.replace('record = await save_ingestion_transaction(', 
'''for c in merged_new:
                if "canonical" in c:
                    c["name"] = c["canonical"]
            record = await save_ingestion_transaction(''')

with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f:
    f.write(text)
