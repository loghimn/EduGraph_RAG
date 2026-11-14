package fu.coreservice.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.dto.chat.ClarificationCandidate;
import java.util.List;

/** A bounded JSON value stored on the user message, in the same transaction as the clarification receipt. */
public final class ClarificationCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ClarificationCodec() {}

    public static String encode(List<ClarificationCandidate> candidates) {
        try { return JSON.writeValueAsString(candidates); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid clarification candidates", error); }
    }

    public static List<ClarificationCandidate> decode(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return JSON.readValue(value, new TypeReference<List<ClarificationCandidate>>() {}); }
        catch (Exception error) { return List.of(); }
    }
}
