import asyncio
import time
import os
import sys
import statistics

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.db.neo4j_client import neo4j_client
from app.db.repo import repo

async def run_latency_test(name: str, coro, iterations: int = 30) -> list[float]:
    latencies = []
    # Warmup
    for _ in range(2):
        await coro()
        
    print(f"\n--- Running test: {name} ({iterations} iterations) ---")
    for i in range(iterations):
        start = time.perf_counter()
        await coro()
        end = time.perf_counter()
        latencies.append((end - start) * 1000)  # ms
        
    min_lat = min(latencies)
    median_lat = statistics.median(latencies)
    p95_lat = statistics.quantiles(latencies, n=100)[94] if len(latencies) >= 20 else max(latencies)
    
    print(f"Results for {name}:")
    print(f"  Min   : {min_lat:.2f} ms")
    print(f"  Median: {median_lat:.2f} ms")
    print(f"  P95   : {p95_lat:.2f} ms")
    return latencies

async def main():
    print("WARNING: Please ensure you have pressed 'Resume' on your Neo4j Aura console if it was paused.")
    print("Waiting 3 seconds...")
    await asyncio.sleep(3)
    
    await neo4j_client.connect()
    
    course_id = "__smoke__"
    try:
        # Test 1: RETURN 1 (ping)
        await run_latency_test("Ping (RETURN 1)", repo.smoke_test_ping)
        
        # Test 2: Vector Query
        # Insert 100 fake vectors for testing
        print("\nInserting 100 fake vectors for vector query test...")
        await repo.smoke_test_insert_fake_vectors(course_id, count=100)
        
        import random
        # Query with random vector
        async def query_vector():
            test_vector = [random.random() for _ in range(1024)]
            await repo.smoke_test_vector_query(course_id, test_vector, k=50)
            
        await run_latency_test("Vector Query (Top-50 from 100)", query_vector)
        
    finally:
        print("\nCleaning up smoke test data...")
        await repo.smoke_test_cleanup(course_id)
        await neo4j_client.close()
        print("Cleanup done.")

if __name__ == "__main__":
    asyncio.run(main())
