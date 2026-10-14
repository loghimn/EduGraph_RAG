# Đánh Giá Các Cạnh Tiên Quyết (demo-sql)

- **Thời gian chạy lần 1**: 103.61s
- **Số request LLM lần 1**: 18
- **Tổng token tiêu thụ lần 1**: 12853
- **Số lần gặp 429**: 0
- **Finish reason bất thường**: Không có (toàn bộ là stop)
- **Số request LLM lần 2 (cache test)**: 0

## 1. Danh sách cạnh tiên quyết (sắp xếp theo Target B)

| STT | Cạnh (A -> B) | Score | LLM Score | Rule Score | Evidence |
|---|---|---|---|---|---|
| 1 | **JOIN** -> **CREATE VIEW** | 0.76 | 0.95 | 0.00 | The evidence explicitly demonstrates creating a view by first using JOIN to combine data from multiple tables, indicating that understanding JOIN is necessary to construct the underlying query for the view. |
| 2 | **foreign key** -> **CREATE VIEW** | 0.68 | 0.85 | 0.00 | Similar to primary keys, understanding foreign keys is essential for correctly specifying the join conditions between tables when defining the data source for a view. |
| 3 | **primary key** -> **CREATE VIEW** | 0.68 | 0.85 | 0.00 | The material notes that the primary key column of one table is usually joined to the foreign key of another, which is a fundamental concept for correctly structuring the SELECT statement within a CREATE VIEW command. |
| 4 | **GROUP BY** -> **Common Table Expression** | 0.68 | 0.85 | 0.00 | The provided example query for the CTE uses GROUP BY to calculate average ratings per year, which is essential to understanding the specific use case demonstrated. |
| 5 | **temporary view** -> **Common Table Expression** | 0.72 | 0.90 | 0.00 | The text directly compares CTEs to temporary views, explaining that while a temporary view lasts for the duration of a connection, a CTE lasts for a single query. |
| 6 | **view** -> **Common Table Expression** | 0.76 | 0.95 | 0.00 | The course material explicitly defines a CTE by contrasting it with regular and temporary views, stating that a CTE is a view that exists for a single query alone. |
| 7 | **JOIN** -> **GROUP BY** | 0.76 | 0.95 | 0.00 | The evidence explicitly demonstrates using GROUP BY in conjunction with a JOIN to combine data from multiple tables, indicating that understanding JOIN is necessary to fully utilize GROUP BY in complex queries. |
| 8 | **INSTEAD OF trigger** -> **INSTEAD OF INSERT** | 0.80 | 1.00 | 0.00 | The target concept 'INSTEAD OF INSERT' is a specific type of trigger, so understanding the general concept of an INSTEAD OF trigger is strictly necessary. |
| 9 | **soft deletion** -> **INSTEAD OF INSERT** | 0.68 | 0.85 | 0.00 | The provided evidence explicitly describes the trigger's logic as handling the reversal of a 'soft deletion' by setting a 'deleted' flag, making this concept essential to understanding the specific use case. |
| 10 | **view** -> **INSTEAD OF INSERT** | 0.76 | 0.95 | 0.00 | INSTEAD OF triggers are specifically used on views to handle DML operations that cannot be performed directly on the underlying base tables. |
| 11 | **CREATE VIEW** -> **INSTEAD OF trigger** | 0.72 | 0.90 | 0.00 | The evidence explicitly states that one cannot insert or delete data from a view, which is the problem the INSTEAD OF trigger solves; thus, understanding how views are created and their limitations is a prerequisite. |
| 12 | **view** -> **INSTEAD OF trigger** | 0.80 | 1.00 | 0.00 | The INSTEAD OF trigger is specifically designed to allow data modification on views, so understanding what a view is is fundamental to understanding the trigger's purpose. |
| 13 | **foreign key** -> **JOIN** | 0.76 | 0.95 | 0.00 | JOIN operations are fundamentally based on combining rows from tables based on related columns, which are typically defined by foreign key relationships. |
| 14 | **primary key** -> **JOIN** | 0.72 | 0.90 | 0.00 | Understanding primary keys is essential to identify the unique identifiers in tables that are used to establish the relationships for joining. |
| 15 | **CREATE VIEW** -> **ORDER BY clause** | 0.72 | 0.90 | 0.00 | The material states that a view itself can be ordered by including an ORDER BY clause in the query used to create the view, requiring knowledge of the CREATE VIEW statement. |
| 16 | **view** -> **ORDER BY clause** | 0.76 | 0.95 | 0.00 | The evidence explicitly discusses ordering data within a view and creating a view with an ORDER BY clause, making an understanding of what a view is essential to grasp the context of the target concept. |
| 17 | **CREATE VIEW** -> **Viewing in SQL** | 0.76 | 0.95 | 0.00 | To view data in SQL, one must understand how views are created and defined using the CREATE VIEW statement. |
| 18 | **nested query** -> **Viewing in SQL** | 0.68 | 0.85 | 0.00 | The evidence shows a trigger using a nested query (SELECT ... FROM) to check for existing records, which is a common pattern in view definitions and related SQL operations. |
| 19 | **view** -> **Viewing in SQL** | 0.80 | 1.00 | 0.00 | The target concept is 'Viewing in SQL', which fundamentally requires understanding what a view is in a database context. |
| 20 | **INSTEAD OF trigger** -> **WHEN keyword** | 0.76 | 0.95 | 0.00 | The WHEN keyword is used within the context of an INSTEAD OF trigger, as shown in the example 'CREATE TRIGGER ... INSTEAD OF INSERT ... WHEN ...', making understanding of INSTEAD OF triggers a prerequisite. |
| 21 | **nested query** -> **WHEN keyword** | 0.72 | 0.90 | 0.00 | The WHEN clause in the example contains a nested query (SELECT ... FROM ...), so understanding nested queries is necessary to fully comprehend the WHEN keyword's usage. |
| 22 | **primary key** -> **accession number** | 0.76 | 0.95 | 0.00 | The evidence states that an accession number 'uniquely identifies every piece of art', which is the definition of a primary key; understanding this concept is necessary to grasp the role of the accession number. |
| 23 | **primary key** -> **foreign key** | 0.76 | 0.95 | 0.00 | A foreign key is defined as a column that references the primary key of another table, so understanding primary keys is essential to grasp the concept of a foreign key. |
| 24 | **INSTEAD OF trigger** -> **soft deletion** | 0.76 | 0.95 | 0.00 | The evidence explicitly describes using an INSTEAD OF trigger to handle the logic for soft deletion (updating the 'deleted' flag or inserting new rows) on a view. |
| 25 | **WHEN keyword** -> **soft deletion** | 0.68 | 0.85 | 0.00 | The provided SQL code uses the WHEN keyword within the trigger to conditionally execute the soft deletion reversal logic based on the accession number. |
| 26 | **view** -> **soft deletion** | 0.72 | 0.90 | 0.00 | Soft deletion is implemented by manipulating a 'deleted' flag in the underlying table while exposing data through a view, as shown in the 'current_collections' example. |
| 27 | **GROUP BY** -> **temporary view** | 0.68 | 0.85 | 0.00 | The evidence shows the use of GROUP BY to aggregate data, a key component of the query that would be encapsulated in a view. |
| 28 | **JOIN** -> **temporary view** | 0.72 | 0.90 | 0.00 | The provided evidence demonstrates creating a query that uses a JOIN to combine tables, which is a common use case for defining the underlying query of a view. |
| 29 | **view** -> **temporary view** | 0.80 | 1.00 | 0.00 | A temporary view is a specific type of view, so understanding the general concept of a view is fundamental. |
| 30 | **GROUP BY** -> **view** | 0.72 | 0.90 | 0.00 | The evidence demonstrates creating a view that includes aggregated data (average rating) by using the GROUP BY clause in conjunction with JOIN, indicating that understanding GROUP BY is necessary to fully grasp the examples of view creation provided. |
| 31 | **JOIN** -> **view** | 0.80 | 1.00 | 0.00 | The evidence explicitly states that the JOIN command is used to combine rows from two or more tables to create a single view, making it a fundamental prerequisite for understanding how views are constructed from multiple tables. |

## 2. Concept không có cạnh nào (cả vào lẫn ra)

- Personally Identifiable Information
- partitioning

## 3. Cạnh bị cắt khi phá vòng & Xung đột

- **Cạnh bị cắt khi phá vòng**: Không có chu trình (DAG hợp lệ).
- **Xung đột cứng**: 0

## 4. Chi tiết từng lần gọi LLM

| Request | Model | Prompt Tokens | Completion Tokens | Total Tokens | Duration (s) | 429? | Finish Reason |
|---|---|---|---|---|---|---|---|
| 1 | qwen/qwen3.8-27b | 574 | 85 | 659 | 0.93 | False | stop |
| 2 | qwen/qwen3.8-27b | 741 | 195 | 936 | 1.09 | False | stop |
| 3 | qwen/qwen3.8-27b | 749 | 163 | 912 | 1.16 | False | stop |
| 4 | qwen/qwen3.8-27b | 557 | 109 | 666 | 0.42 | False | stop |
| 5 | qwen/qwen3.8-27b | 558 | 84 | 642 | 0.38 | False | stop |
| 6 | qwen/qwen3.8-27b | 476 | 16 | 492 | 0.27 | False | stop |
| 7 | qwen/qwen3.8-27b | 547 | 16 | 563 | 0.23 | False | stop |
| 8 | qwen/qwen3.8-27b | 565 | 216 | 781 | 0.64 | False | stop |
| 9 | qwen/qwen3.8-27b | 540 | 164 | 704 | 0.52 | False | stop |
| 10 | qwen/qwen3.8-27b | 728 | 212 | 940 | 8.96 | False | stop |
| 11 | qwen/qwen3.8-27b | 566 | 192 | 758 | 0.62 | False | stop |
| 12 | qwen/qwen3.8-27b | 548 | 92 | 640 | 19.82 | False | stop |
| 13 | qwen/qwen3.8-27b | 540 | 216 | 756 | 5.90 | False | stop |
| 14 | qwen/qwen3.8-27b | 498 | 153 | 651 | 0.48 | False | stop |
| 15 | qwen/qwen3.8-27b | 550 | 212 | 762 | 9.81 | False | stop |
| 16 | qwen/qwen3.8-27b | 552 | 193 | 745 | 0.59 | False | stop |
| 17 | qwen/qwen3.8-27b | 543 | 154 | 697 | 17.85 | False | stop |
| 18 | qwen/qwen3.8-27b | 538 | 11 | 549 | 0.32 | False | stop |
