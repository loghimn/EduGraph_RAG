package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.entity.Document;
import fu.coreservice.repository.CourseEnrollmentRepository;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.DocumentRepository;
import fu.coreservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRagServiceTest {

    @Mock
    private AiCoreClient aiCoreClient;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private CourseEnrollmentRepository courseEnrollmentRepository;
    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private AiRagService service;

    @Test
    void enrichSourceFileNames_resolvesNamesFromJavaDocumentMetadata() {
        Map<String, Object> source = new HashMap<>();
        source.put("document_id", "123");
        source.put("filename", null);
        Map<String, Object> concept = new HashMap<>();
        concept.put("sources", List.of(source));

        Document document = Document.builder()
                .documentId(123L)
                .fileName("algebra.pdf")
                .build();
        when(documentRepository.findByDocumentIdInAndCourseCourseId(List.of(123L), 7L))
                .thenReturn(List.of(document));

        ReflectionTestUtils.invokeMethod(service, "enrichSourceFileNames", concept, 7L);

        List<?> sources = (List<?>) concept.get("sources");
        Map<?, ?> enrichedSource = (Map<?, ?>) sources.get(0);
        assertEquals("algebra.pdf", enrichedSource.get("filename"));
        verify(documentRepository).findByDocumentIdInAndCourseCourseId(List.of(123L), 7L);
    }
}
