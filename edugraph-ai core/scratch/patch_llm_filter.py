with open('app/concept_extraction/llm_filter.py', 'r', encoding='utf-8') as f: lines = f.readlines()
for i in range(len(lines)):
    if 'stats = {"total_returned": 0,' in lines[i]:
        lines[i] = '    stats = {"total_returned": 0, "dropped_no_evidence": 0, "dropped_format": 0, "dropped_list": []}\n'
    if 'stats["dropped_no_evidence"] += 1' in lines[i]:
        lines[i] += '                stats["dropped_list"].append({"concept": c, "reason": "No evidence found in chunks"})\n'
    if 'stats["dropped_format"] += 1' in lines[i]:
        lines[i] += '            stats["dropped_list"].append({"concept": c, "reason": "Format error or missing keys"})\n'
with open('app/concept_extraction/llm_filter.py', 'w', encoding='utf-8') as f: f.writelines(lines)
