package fu.coreservice.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.config.AiCoreProperties;
import fu.coreservice.dto.ai.AiChatRequest;
import fu.coreservice.dto.ai.AiDocumentStatusResponse;
import fu.coreservice.dto.ai.AiIngestRequest;
import fu.coreservice.dto.ai.AiIngestResponse;
import fu.coreservice.dto.ai.AiLearningPathRequest;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Gói RestClient gọi tới AI core-service (Python FastAPI, edugraph-ai-core).
 * Mọi request đều gắn header X-Internal-Key; lỗi hạ tầng ném
 * AppException(AI_CORE_UNAVAILABLE), lỗi HTTP từ Python ném AiCoreException
 * (giữ status gốc để GlobalExceptionHandler relay).
 */
@Component
@Slf4j
public class AiCoreClient {

    private static final int MAX_DETAIL_LENGTH = 500;

    private final AiCoreProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public AiCoreClient(AiCoreProperties properties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this(properties, restClientBuilder, objectMapper, true);
    }

    /**
     * Constructor cho test: configureTimeouts=false để MockRestServiceServer
     * (bind vào builder trước) không bị thay ClientHttpRequestFactory.
     */
    AiCoreClient(AiCoreProperties properties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                 boolean configureTimeouts) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        RestClient.Builder builder = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-Internal-Key", properties.getInternalKey());

        if (configureTimeouts) {
            // HttpURLConnection (SimpleClientHttpRequestFactory) rejects PATCH
            // before sending it, masking a healthy AI core as AI_001.
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
            builder.requestFactory(requestFactory);
        }

        this.restClient = builder.build();
    }

    /**
     * POST /documents/{documentId}/file — gửi file nhị phân (multipart) để Python
     * lưu vào RAW_DATA_DIR/{documentId}/{filename} trước khi gọi /ingest.
     */
    public void storeDocumentFile(Long documentId, String filename, MediaType mediaType, Resource content) {
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", content)
                .filename(filename)
                .contentType(mediaType == null ? MediaType.APPLICATION_OCTET_STREAM : mediaType);

        try {
            restClient.post()
                    .uri("/documents/{documentId}/file", documentId)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart.build())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Stored file to AI core. documentId={}", documentId);
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(documentId), e);
        }
    }

    /**
     * POST /ingest — yêu cầu Python chạy pipeline (managed task, trả ack ngay).
     */
    public AiIngestResponse triggerIngest(AiIngestRequest request) {
        try {
            AiIngestResponse response = restClient.post()
                    .uri("/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AiIngestResponse.class);
            log.info("Triggered AI ingest. documentId={}, courseId={}", request.getDocumentId(), request.getCourseId());
            return response;
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(request.getDocumentId(), e);
        }
    }

    /**
     * GET /documents/{documentId}/status?course_id=... — trạng thái trong Neo4j để poller đồng bộ.
     */
    public AiDocumentStatusResponse getDocumentStatus(Long documentId, Long courseId) {
        try {
            return restClient.get()
                    .uri("/documents/{documentId}/status?course_id={courseId}", documentId, courseId)
                    .retrieve()
                    .body(AiDocumentStatusResponse.class);
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(documentId), e);
        }
    }

    public void pauseDocument(Long documentId, Long courseId, Long userId) {
        try {
            restClient.patch()
                    .uri("/documents/{documentId}/pause?course_id={courseId}&user_id={userId}",
                            documentId, courseId, userId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(documentId), e);
        }
    }

    /**
     * DELETE /documents/{doc_id}?course_id=...&user_id=... — yêu cầu Python xóa
     * Chunk, Concept orphan và cạnh liên quan khỏi Neo4j graph.
     */
    public void cancelDocument(Long documentId, Long courseId, Long userId) {
        try {
            restClient.delete()
                    .uri("/documents/{documentId}?course_id={courseId}&user_id={userId}",
                            documentId, courseId, userId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Cancelled document in AI core. documentId={}", documentId);
        } catch (RestClientResponseException e) {
            // 404 = chưa ingest lần nào → bỏ qua (graph chưa có data)
            if (e.getStatusCode().value() == 404) {
                log.info("Document not found in AI core graph (not yet ingested). documentId={}", documentId);
                return;
            }
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            // Do not delete Storage or report cancellation until Python confirms stop.
            throw unavailable(String.valueOf(documentId), e);
        }
    }


    public Map<String, Object> postConceptDraft(Long courseId, Long actorId,
                                               String draftId, Map<String, Object> request) {
        try {
            String path = "/courses/{courseId}/knowledge-graph/concept-drafts";
            var call = restClient.post();
            if (draftId == null) {
                call.uri(path, courseId);
            } else {
                call.uri(path + "/{draftId}/publish", courseId, draftId);
            }
            return call.header("X-Actor-Id", String.valueOf(actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(courseId), e);
        }
    }


    public Map<String, Object> conceptLifecycle(Long courseId, Long actorId, String conceptId,
                                                String action, Long expectedVersion) {
        String base = "/courses/{courseId}/knowledge-graph";
        try {
            RestClient.RequestHeadersSpec<?> call;
            switch (action) {
                case "archived" -> call = restClient.get().uri(base + "/archived-concepts", courseId);
                case "impact" -> call = restClient.get().uri(
                        base + "/concepts/{conceptId}/deletion-impact", courseId, conceptId);
                case "archive" -> call = restClient.delete().uri(
                        base + "/concepts/{conceptId}?expected_version={version}", courseId, conceptId, expectedVersion);
                case "restore" -> call = restClient.post().uri(
                        base + "/concepts/{conceptId}/restore?expected_version={version}", courseId, conceptId, expectedVersion);
                default -> throw new IllegalArgumentException("Unknown concept lifecycle action");
            }
            return call.header("X-Actor-Id", String.valueOf(actorId)).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(courseId), e);
        }
    }

    public Map<String, Object> getKnowledgeGraph(Long courseId) {
        try {
            return restClient.get()
                    .uri("/courses/{courseId}/knowledge-graph", courseId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(courseId), e);
        }
    }

    public Map<String, Object> getKnowledgeGraphConcept(Long courseId, String conceptId) {
        try {
            return restClient.get()
                    .uri("/courses/{courseId}/knowledge-graph/concepts/{conceptId}", courseId, conceptId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException e) {
            throw toAiCoreException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw unavailable(String.valueOf(courseId), e);
        }
    }

    /**
     * POST /chat — mở luồng SSE; caller đọc body và streaming tới client.
     * PHẢI đóng response khi xong (try-with-resources ở caller).
     */
    public ClientHttpResponse openChatStream(AiChatRequest request) {
        try {
            return restClient.post()
                    .uri("/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(request)
                    .exchange((httpRequest, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            String errorBody = readBodySafely(httpResponse);
                            httpResponse.close();
                            throw toAiCoreException(httpResponse.getStatusCode().value(), errorBody);
                        }
                        return httpResponse;
                    }, false);
        } catch (AiCoreException e) {
            throw e;
        } catch (RestClientException e) {
            throw unavailable(request.getCourseId(), e);
        }
    }

    /**
     * POST /learning-paths/plan — trả payload JSON của Python (thành công hoặc lỗi 4xx/5xx).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createLearningPathPlan(AiLearningPathRequest request) {
        try {
            return restClient.post()
                    .uri("/learning-paths/plan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, httpResponse) -> {
                        String body = readBodySafely(httpResponse);
                        if (httpResponse.getStatusCode().is2xxSuccessful()) {
                            return objectMapper.readValue(body, Map.class);
                        }
                        throw toAiCoreException(httpResponse.getStatusCode().value(), body);
                    });
        } catch (AiCoreException e) {
            throw e;
        } catch (RestClientException e) {
            throw unavailable(request.getCourseId(), e);
        }
    }

    private AiCoreException toAiCoreException(int status, String rawBody) {
        Map<String, Object> body = Collections.emptyMap();
        String detail = rawBody;

        if (rawBody != null && !rawBody.isBlank()) {
            try {
                body = objectMapper.readValue(rawBody, new TypeReference<>() {});
                Object rawDetail = body.get("detail");
                if (rawDetail instanceof Map<?, ?> detailMap && detailMap.get("message") instanceof String message) {
                    detail = message;
                } else if (rawDetail != null) {
                    detail = String.valueOf(rawDetail);
                }
            } catch (JsonProcessingException ignored) {
                // body không phải JSON object — giữ nguyên rawBody làm detail
            }
        }

        if (detail == null || detail.isBlank()) {
            detail = "AI core returned HTTP " + status;
        }
        if (detail.length() > MAX_DETAIL_LENGTH) {
            detail = detail.substring(0, MAX_DETAIL_LENGTH) + "...";
        }

        log.warn("AI core returned error. status={}, detail={}", status, detail);
        return new AiCoreException(status, detail, body);
    }

    private AppException unavailable(String documentId, RestClientException e) {
        log.error("AI core unreachable. documentId={}, baseUrl={}, reason={}",
                documentId, properties.getBaseUrl(), e.getMessage());
        return new AppException(ErrorCode.AI_CORE_UNAVAILABLE);
    }

    private String readBodySafely(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
