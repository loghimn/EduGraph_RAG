DDL_STATEMENTS = [
    # Constraint 
    "CREATE CONSTRAINT course_id IF NOT EXISTS FOR (n:Course) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT document_id IF NOT EXISTS FOR (n:Document) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT chunk_id IF NOT EXISTS FOR (n:Chunk) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT concept_id IF NOT EXISTS FOR (n:Concept) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT concept_course_key IF NOT EXISTS FOR (n:Concept) REQUIRE n.course_key IS UNIQUE",
    
    "CREATE CONSTRAINT concept_draft_id IF NOT EXISTS FOR (n:ConceptDraft) REQUIRE n.id IS UNIQUE",
    # Indexes
    "CREATE INDEX chunk_course IF NOT EXISTS FOR (n:Chunk) ON (n.course_id)",
    "CREATE INDEX chunk_doc IF NOT EXISTS FOR (n:Chunk) ON (n.doc_id)",
    "CREATE INDEX concept_course IF NOT EXISTS FOR (n:Concept) ON (n.course_id)",
    "CREATE INDEX document_course IF NOT EXISTS FOR (n:Document) ON (n.course_id)",
    
    # Vector Index
    """
    CREATE VECTOR INDEX chunk_embedding IF NOT EXISTS
    FOR (c:Chunk) ON (c.embedding)
    OPTIONS {indexConfig: {`vector.dimensions`: 1024, `vector.similarity_function`: 'cosine'}}
    """,
    
    # Fulltext Index
    """
    CREATE FULLTEXT INDEX chunk_text IF NOT EXISTS
    FOR (c:Chunk) ON EACH [c.text_unaccent]
    """
]
