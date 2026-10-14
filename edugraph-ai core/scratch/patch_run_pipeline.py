with open('scripts/run_pipeline.py', 'r', encoding='utf-8') as f: lines = f.readlines()
for i in range(len(lines)):
    if 'course_id = "__test__" if args.dry_run else' in lines[i]:
        lines[i] = '    course_id = "__test__"\n'
with open('scripts/run_pipeline.py', 'w', encoding='utf-8') as f: f.writelines(lines)
