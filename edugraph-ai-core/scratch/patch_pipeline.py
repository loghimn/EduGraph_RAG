with open('app/prerequisite/pipeline.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('surviving_edges, conflicts = await clean_graph_cycles(course_id, all_proposed_edges)', 'surviving_edges, conflicts, cut_edges = await clean_graph_cycles(course_id, all_proposed_edges)')
text = text.replace('await save_prerequisite_edges(course_id, surviving_edges)', 'rescored_ids = list(candidates_dict.keys())\n    cut_edge_dicts = [{"source_id": u, "target_id": v} for u, v in cut_edges]\n    await save_prerequisite_edges(course_id, surviving_edges, rescored_ids, cut_edge_dicts)')

with open('app/prerequisite/pipeline.py', 'w', encoding='utf-8') as f:
    f.write(text)
