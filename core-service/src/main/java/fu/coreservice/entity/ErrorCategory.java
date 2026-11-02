package fu.coreservice.entity;

/**
 * Phân loại lỗi xử lý tài liệu:
 * - USER_ERROR: Lỗi do phía người dùng (file lỗi, định dạng sai...) — KHÔNG retry.
 * - SYSTEM_ERROR: Lỗi hạ tầng/dịch vụ (Supabase, LLM API, Neo4j...) — CÓ thể retry.
 */
public enum ErrorCategory {
    USER_ERROR,
    SYSTEM_ERROR
}
