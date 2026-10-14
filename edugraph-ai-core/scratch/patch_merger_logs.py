with open('app/concept_extraction/merger.py', 'r', encoding='utf-8') as f: text = f.read()

text = text.replace(
'''                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    # Update aliases
                    merged = True''',
'''                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "Alias Match"))
                    # Update aliases
                    merged = True'''
)

text = text.replace(
'''                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merged = True''',
'''                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merged = True'''
)

with open('app/concept_extraction/merger.py', 'w', encoding='utf-8') as f: f.write(text)
