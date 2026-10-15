with open('app/concept_extraction/graph_cleaner.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace("nx.find_cycle(G, orientation='original')", "nx.find_cycle(G)")
with open('app/concept_extraction/graph_cleaner.py', 'w', encoding='utf-8') as f: f.write(text)
