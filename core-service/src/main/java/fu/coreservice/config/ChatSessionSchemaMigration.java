package fu.coreservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /** Apply the chat_sessions course_id nullability migration. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("ALTER TABLE chat_sessions ALTER COLUMN course_id DROP NOT NULL");
            log.info("Dropped NOT NULL constraint on chat_sessions.course_id");
        } catch (Exception e) {
            // Verify column nullability after migration failure
            boolean isNullable;
            try {
                isNullable = isCourseIdNullable();
            } catch (Exception checkException) {
                e.addSuppressed(checkException);
                throw new RuntimeException(
                        "chat_sessions.course_id migration failed and column nullability could not be verified.", e);
            }
            if (isNullable) {
                // Column is now nullable (migration succeeded or was already nullable)
                log.warn("chat_sessions.course_id NOT NULL migration failed but column is now nullable: {}", e.getMessage());
            } else {
                // Column remains NOT NULL - fail startup so operators can correct the schema
                log.error("chat_sessions.course_id is NOT NULL and migration failed. Startup aborted. " +
                        "Please ensure the course_id column is nullable or apply the ALTER TABLE migration manually.");
                throw new RuntimeException("chat_sessions.course_id NOT NULL migration failed and column remains NOT NULL. " +
                        "Application cannot start with this schema constraint.", e);
            }
        }
    }

    /**
     * Check if the course_id column in chat_sessions table is nullable.
     * Returns true if the column allows NULL values, false if it is NOT NULL.
     */
    private boolean isCourseIdNullable() {
        String sql = "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE LOWER(TABLE_NAME) = 'chat_sessions' AND LOWER(COLUMN_NAME) = 'course_id' "
                + "AND TABLE_SCHEMA = CURRENT_SCHEMA";
        List<String> nullabilityValues = jdbcTemplate.queryForList(sql, String.class);
        return !nullabilityValues.isEmpty() && nullabilityValues.stream().allMatch("YES"::equalsIgnoreCase);
    }
}