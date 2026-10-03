package fu.gatewayservice.dto;

import java.nio.charset.StandardCharsets;

/**
 * Gateway error body aligned with core-service ApiResponse shape
 * ({@code success=false}, {@code message}, {@code data=null}).
 * Serialized manually to avoid Jackson 2 vs 3 package differences in Boot 4.
 */
public record ErrorResponse(boolean success, String message, Object data) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(false, message, null);
    }

    public byte[] toJsonBytes() {
        return toJson(message).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] errorJson(String message) {
        return toJson(message).getBytes(StandardCharsets.UTF_8);
    }

    private static String toJson(String message) {
        String escaped = String.valueOf(message)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
        return "{\"success\":false,\"message\":\"" + escaped + "\",\"data\":null}";
    }
}
