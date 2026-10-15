package fu.coreservice.controller;

import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.service.AiRagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;

/**
 * Proxy SSE /chat của AI core — frontend chỉ gọi qua Gateway, Java chuyển tiếp từng morsel.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/chat")
@RequiredArgsConstructor
@Slf4j
public class AiChatController {

    private static final MediaType SSE_MEDIA_TYPE = MediaType.valueOf("text/event-stream");

    private final AiRagService aiRagService;

    @PostMapping
    public ResponseEntity<StreamingResponseBody> chat(@PathVariable Long courseId,
                                                      @Valid @RequestBody ChatRequest request) {
        ClientHttpResponse pythonResponse = aiRagService.openChatStream(courseId, request);

        StreamingResponseBody stream = outputStream -> {
            try (InputStream in = pythonResponse.getBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                    outputStream.flush();
                }
            } catch (IOException e) {
                log.warn("SSE stream interrupted. courseId={}, reason={}", courseId, e.getMessage());
            } finally {
                pythonResponse.close();
            }
        };

        return ResponseEntity.ok()
                .contentType(SSE_MEDIA_TYPE)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }
}
