package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiDocumentStatusResponse;
import fu.coreservice.dto.ai.AiIngestRequest;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.DocumentProcessingJob;
import fu.coreservice.entity.JobStatus;
import fu.coreservice.entity.UploadStatus;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.DocumentProcessingJobRepository;
import fu.coreservice.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDocumentProcessingServiceTest {

    @Mock
    private AiCoreClient aiCoreClient;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentProcessingJobRepository documentProcessingJobRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AiDocumentProcessingService service;

    private Document document;
    private DocumentProcessingJob job;

    @BeforeEach
    void setUp() {
        Course course = Course.builder().courseId(1L).build();
        document = Document.builder()
                .documentId(10L)
                .course(course)
                .docStatus(UploadStatus.PROCESSING)
                .fileName("bai1.pdf")
                .build();
        job = DocumentProcessingJob.builder()
                .documentProcessingJobId(100L)
                .document(document)
                .jobStatus(JobStatus.RUNNING)
                .build();
    }

    /** Chạy inline các transaction callback (markProcessing/markFailed dùng TransactionTemplate). */
    private void stubInlineTransactions() {
        doAnswer(invocation -> {
            Consumer<Object> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void syncProcessingDocuments_done_mapsDocumentAndJobToCompleted() {
        when(documentRepository.findByDocStatus(UploadStatus.PROCESSING)).thenReturn(java.util.List.of(document));
        when(aiCoreClient.getDocumentStatus(10L, 1L))
                .thenReturn(AiDocumentStatusResponse.builder().status("DONE").progress("done").build());
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L)).thenReturn(Optional.of(job));

        service.syncProcessingDocuments();

        assertEquals(UploadStatus.COMPLETED, document.getDocStatus());
        verify(documentRepository).save(document);
        assertEquals(JobStatus.DONE, job.getJobStatus());
        verify(documentProcessingJobRepository).save(job);
    }

    @Test
    void syncProcessingDocuments_failed_setsFailedStatusAndTruncatesError() {
        String longError = "x".repeat(1500);
        when(documentRepository.findByDocStatus(UploadStatus.PROCESSING)).thenReturn(java.util.List.of(document));
        when(aiCoreClient.getDocumentStatus(10L, 1L))
                .thenReturn(AiDocumentStatusResponse.builder().status("FAILED").error(longError).build());
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L)).thenReturn(Optional.of(job));

        service.syncProcessingDocuments();

        assertEquals(UploadStatus.FAILED, document.getDocStatus());
        verify(documentRepository).save(document);
        assertEquals(JobStatus.FAILED, job.getJobStatus());
        assertEquals(1000, job.getErrorMessage().length());
    }

    @Test
    void syncProcessingDocuments_processing_keepsDocumentUnchanged() {
        when(documentRepository.findByDocStatus(UploadStatus.PROCESSING)).thenReturn(java.util.List.of(document));
        when(aiCoreClient.getDocumentStatus(10L, 1L))
                .thenReturn(AiDocumentStatusResponse.builder().status("PROCESSING").progress("0.5").build());

        service.syncProcessingDocuments();

        assertEquals(UploadStatus.PROCESSING, document.getDocStatus());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void syncProcessingDocuments_aiCoreUnavailable_keepsDocumentUnchanged() {
        when(documentRepository.findByDocStatus(UploadStatus.PROCESSING)).thenReturn(java.util.List.of(document));
        when(aiCoreClient.getDocumentStatus(10L, 1L))
                .thenThrow(new AppException(ErrorCode.AI_CORE_UNAVAILABLE));

        service.syncProcessingDocuments();

        assertEquals(UploadStatus.PROCESSING, document.getDocStatus());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void triggerPipelineAsync_success_sendsFileAndIngest_marksProcessing() {
        stubInlineTransactions();
        Resource content = new ByteArrayResource(new byte[]{1, 2, 3});
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L)).thenReturn(Optional.of(job));

        service.triggerPipelineAsync(1L, 10L, 7L, "bai1.pdf", MediaType.APPLICATION_PDF, content);

        verify(aiCoreClient, timeout(3000)).storeDocumentFile(10L, "bai1.pdf", MediaType.APPLICATION_PDF, content);
        ArgumentCaptor<AiIngestRequest> captor = ArgumentCaptor.forClass(AiIngestRequest.class);
        verify(aiCoreClient, timeout(3000)).triggerIngest(captor.capture());
        assertEquals("10", captor.getValue().getDocumentId());
        assertEquals("1", captor.getValue().getCourseId());
        assertEquals("7", captor.getValue().getUserId());
        assertEquals("bai1.pdf", captor.getValue().getFilename());
        verify(documentRepository, timeout(3000)).save(document);
        assertEquals(UploadStatus.PROCESSING, document.getDocStatus());
    }

    @Test
    void triggerPipelineAsync_aiCoreError_marksFailed() {
        stubInlineTransactions();
        Resource content = new ByteArrayResource(new byte[]{1, 2, 3});
        doThrow(new RuntimeException("python down"))
                .when(aiCoreClient).storeDocumentFile(anyLong(), anyString(), any(), any());
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L)).thenReturn(Optional.of(job));

        service.triggerPipelineAsync(1L, 10L, 7L, "bai1.pdf", MediaType.APPLICATION_PDF, content);

        verify(documentRepository, timeout(3000)).save(document);
        assertEquals(UploadStatus.FAILED, document.getDocStatus());
        verify(documentProcessingJobRepository, timeout(3000)).save(job);
        assertEquals(JobStatus.FAILED, job.getJobStatus());
        assertEquals("python down", job.getErrorMessage());
        verify(aiCoreClient, never()).triggerIngest(any());
    }
}
