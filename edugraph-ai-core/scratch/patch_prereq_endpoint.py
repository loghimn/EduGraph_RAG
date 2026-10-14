with open('app/api/ingest.py', 'a', encoding='utf-8') as f:
    f.write("""
@router.post("/courses/{course_id}/prerequisites")
async def generate_course_prerequisites(
    course_id: str,
    background_tasks: BackgroundTasks,
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)
    
    query_course = "MATCH (c:Course {id: $course_id}) RETURN c.id AS id"
    async with neo4j_client.driver.session() as session:
        result = await session.run(query_course, course_id=course_id)
        if not await result.single():
            raise HTTPException(status_code=404, detail="Course not found")
            
    from app.concept_extraction.prerequisite_pipeline import run_prerequisite_pipeline
    
    async def _bg_prereq():
        try:
            await run_prerequisite_pipeline(course_id)
        except Exception as e:
            print(f"Error in background prerequisites: {e}")
            
    background_tasks.add_task(_bg_prereq)
    return {"status": "processing prerequisites"}
""")
