with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f: lines = f.readlines()
for i in range(len(lines)):
    if 'total_dropped_fmt = 0' in lines[i]:
        lines[i] = '        total_dropped_fmt = 0\n        dropped_concepts = []\n'
    if 'total_dropped_fmt += stats["dropped_format"]' in lines[i]:
        lines[i] += '            dropped_concepts.extend(stats.get("dropped_list", []))\n'
    if 'return {' in lines[i] and '"merge_candidates"' in lines[i+1]:
        lines[i] = '            return {\n                "valid_concepts": merged_new,\n                "dropped_concepts": dropped_concepts,\n'
with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f: f.writelines(lines)
