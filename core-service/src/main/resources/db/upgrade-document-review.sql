-- Hibernate ddl-auto=update does not extend existing PostgreSQL enum CHECKs.
-- Keep all existing statuses; run once when upgrading an existing database.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'documents'::regclass AND conname = 'documents_doc_status_check'
          AND position('AWAITING_REVIEW' in pg_get_constraintdef(oid)) = 0
    ) THEN
        ALTER TABLE documents DROP CONSTRAINT documents_doc_status_check;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'documents'::regclass AND conname = 'documents_doc_status_check'
    ) THEN
        ALTER TABLE documents ADD CONSTRAINT documents_doc_status_check
            CHECK (doc_status IN ('PENDING', 'PROCESSING', 'AWAITING_REVIEW', 'COMPLETED', 'FAILED', 'PAUSED', 'CANCELLED'));
    END IF;
END $$;
