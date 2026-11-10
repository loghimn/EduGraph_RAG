package fu.coreservice.service;

import fu.coreservice.config.SupabaseStorageConfig;
import fu.coreservice.service.impl.SupabaseStorageServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageCleanupAlertTest {
    @Test void failedCleanupRemainsBestEffortButRaisesDataInconsistencyAlert() {
        var config = new SupabaseStorageConfig();
        config.setUrl("http://storage.test");
        config.setBucketName("documents");
        config.setSecretKey("test-key");
        var http = mock(RestTemplate.class);
        when(http.exchange(anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("provider failure"));
        var alerts = mock(ActivityAlertService.class);
        var storage = new SupabaseStorageServiceImpl(config, http, alerts);
        assertThatCode(() -> storage.deleteFile("course/1/file.pdf")).doesNotThrowAnyException();
        verify(alerts).critical("STORAGE_CLEANUP_FAILED", "DOCUMENT", "DATA_INCONSISTENCY");
    }
}
