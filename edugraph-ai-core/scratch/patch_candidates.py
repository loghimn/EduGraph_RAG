with open('app/concept_extraction/candidates.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('CountVectorizer(ngram_range=(1, 4), stop_words=stopwords)', 'CountVectorizer(ngram_range=(1, 4), stop_words=stopwords, token_pattern=r"(?u)\\b\\w+\\b")')
with open('app/concept_extraction/candidates.py', 'w', encoding='utf-8') as f: f.write(text)
