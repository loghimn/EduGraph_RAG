            # MENTIONS (b)
            all_concepts = db_concepts + merged_new
            final_mentions = {}
            scanned_mentions = await asyncio.to_thread(scan_mentions, chunks, all_concepts)
            for m in scanned_mentions:
                pair = (m['chunk_id'], m['concept_key'])
                final_mentions[pair] = m
            for m in mentions:
                pair = (m['chunk_id'], m['concept_key'])
                if pair in final_mentions:
                    final_mentions[pair]['is_evidence'] = True
                    final_mentions[pair]['start'] = m['start']
                    final_mentions[pair]['end'] = m['end']
                else:
                    m['count'] = 1
                    final_mentions[pair] = m
            mentions = list(final_mentions.values())
