        if args.dry_run:
            print("[DRY-RUN] S? không luu vào DB.")
            from app.db import repo
            async def mock_save(*args, **kwargs):
                return {'version': 0}
            repo.save_ingestion_transaction = mock_save
