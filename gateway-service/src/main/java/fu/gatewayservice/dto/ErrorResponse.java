package fu.gatewayservice.dto;

import java.nio.charset.StandardCharsets;

public record ErrorResponse(
        boolean success,
        String errorCode,
        String message,
        Object data
) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(false, null, message, null);
    }

    public static ErrorResponse of(String errorCode, String message) {
        return new ErrorResponse(false, errorCode, message, null);
    }

    public byte[] toJsonBytes() {
        return toJson(errorCode, message).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] errorJson(String message) {
        return of(message).toJsonBytes();
    }

    public static byte[] errorJson(String errorCode, String message) {
        return of(errorCode, message).toJsonBytes();
    }

    private static String toJson(String errorCode, String message) {

        String escapedMessage = String.valueOf(message)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");

        String errorCodeJson = errorCode == null
                ? "null"
                : "\"" + errorCode
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ") + "\"";

        return "{\"success\":false,\"errorCode\":"
                + errorCodeJson
                + ",\"message\":\""
                + escapedMessage
                + "\",\"data\":null}";
    }
}