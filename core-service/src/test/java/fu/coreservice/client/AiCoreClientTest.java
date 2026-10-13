package fu.coreservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.config.AiCoreProperties;
import fu.coreservice.dto.ai.AiDocumentStatusResponse;
import fu.coreservice.dto.ai.AiIngestRequest;
import fu.coreservice.dto.ai.AiIngestResponse;
import fu.coreservice.exception.AiCoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiCoreClientTest {

    private MockRestServiceServer server;
    private AiCoreClient client;

    @BeforeEach
    void setUp() {
        AiCoreProperties properties = new AiCoreProperties();
        properties.setBaseUrl("http://localhost:8082");
        properties.setInternalKey("test-key");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // configureTimeouts=false để không thay ClientHttpRequestFactory của MockRestServiceServer
        client = new AiCoreClient(properties, builder, new ObjectMapper(), false);
    }

    @Test
    void triggerIngest_sendsInternalKeyAndSnakeCaseBody() {
        server.expect(once(), requestTo("http://localhost:8082/ingest"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-key"))
                .andExpect(content().json("""
                        {"document_id":"10","course_id":"3","user_id":"7","filename":"bai1.pdf"}
                        """))
                .andRespond(withSuccess("{\"status\":\"processing\"}", MediaType.APPLICATION_JSON));

        AiIngestResponse response = client.triggerIngest(AiIngestRequest.builder()
                .documentId("10")
                .courseId("3")
                .userId("7")
                .filename("bai1.pdf")
                .build());

        assertThat(response.getStatus()).isEqualTo("processing");
        server.verify();
    }

    @Test
    void triggerIngest_mapsPythonErrorStatusAndDetail() {
        server.expect(once(), requestTo("http://localhost:8082/ingest"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Permission denied: user is not course owner\"}"));

        assertThatThrownBy(() -> client.triggerIngest(AiIngestRequest.builder()
                        .documentId("10").courseId("3").userId("7").filename("bai1.pdf").build()))
                .isInstanceOf(AiCoreException.class)
                .satisfies(thrown -> {
                    AiCoreException ex = (AiCoreException) thrown;
                    assertThat(ex.getHttpStatus()).isEqualTo(403);
                    assertThat(ex.getDetail()).contains("Permission denied");
                });
        server.verify();
    }

    @Test
    void getDocumentStatus_parsesSnakeCaseResponse() {
        server.expect(once(), requestTo("http://localhost:8082/documents/10/status?course_id=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", "test-key"))
                .andRespond(withSuccess("""
                        {"document_id":"10","course_id":"3","status":"DONE","progress":null,"error":null}
                        """, MediaType.APPLICATION_JSON));

        AiDocumentStatusResponse response = client.getDocumentStatus(10L, 3L);

        assertThat(response.getStatus()).isEqualTo("DONE");
        assertThat(response.getDocumentId()).isEqualTo("10");
        server.verify();
    }

    @Test
    void storeDocumentFile_sendsMultipartWithKey() {
        server.expect(once(), requestTo("http://localhost:8082/documents/10/file"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("{\"status\":\"stored\"}", MediaType.APPLICATION_JSON));

        client.storeDocumentFile(10L, "bai1.pdf", MediaType.APPLICATION_PDF,
                new ByteArrayResource("%PDF-1.4 fake".getBytes()));

        server.verify();
    }
}
