package fu.coreservice.service;

import fu.coreservice.service.impl.AiDocumentProcessingServiceImpl;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiDocumentStatusResponse;
import fu.coreservice.dto.ai.AiIngestRequest;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.DocumentProcessingJob;
import fu.coreservice.entity.JobStatus;
import fu.coreservice.entity.UploadStatus;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.AiCoreException;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.after;

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
    private AiDocumentProcessingServiceImpl service;

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
        lenient().when(documentRepository.findForUpdate(10L)).thenReturn(Optional.of(document));
        lenient().doAnswer(invocation -> {
            Consumer<Object> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
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
        document.setDocStatus(UploadStatus.PENDING);
        Resource content = new ByteArrayResource(new byte[]{1, 2, 3});
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
        document.setDocStatus(UploadStatus.PENDING);
        Resource content = new ByteArrayResource(new byte[]{1, 2, 3});
        doThrow(new AiCoreException(400, "unsupported file", java.util.Map.of()))
                .when(aiCoreClient).storeDocumentFile(anyLong(), anyString(), any(), any());
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L)).thenReturn(Optional.of(job));

        service.triggerPipelineAsync(1L, 10L, 7L, "bai1.pdf", MediaType.APPLICATION_PDF, content);

        verify(documentRepository, timeout(3000)).save(document);
        assertEquals(UploadStatus.FAILED, document.getDocStatus());
        verify(documentProcessingJobRepository, timeout(3000)).save(job);
        assertEquals(JobStatus.FAILED, job.getJobStatus());
        assertEquals("unsupported file", job.getErrorMessage());
        verify(aiCoreClient, never()).triggerIngest(any());
    }

    @Test
    void cancelledDocumentIsNotRestartedByQueuedWorker() {
        document.setDocStatus(UploadStatus.CANCELLED);
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L)).thenReturn(Optional.of(job));
        service.triggerPipelineAsync(1L, 10L, 7L, "a.pdf", MediaType.APPLICATION_PDF,
                new ByteArrayResource(new byte[]{1}));
        verify(aiCoreClient, after(200).never()).storeDocumentFile(anyLong(), anyString(), any(), any());
        assertEquals(UploadStatus.CANCELLED, document.getDocStatus());
    }

    @Test
    void pollDoesNotOverwritePauseCommittedAfterCandidateRead() {
        when(documentRepository.findByDocStatus(UploadStatus.PROCESSING)).thenReturn(java.util.List.of(document));
        document.setDocStatus(UploadStatus.PAUSED);
        service.syncProcessingDocuments();
        verify(aiCoreClient, never()).getDocumentStatus(anyLong(), anyLong());
        assertEquals(UploadStatus.PAUSED, document.getDocStatus());
    }

    @Test
    void oldWorkerCannotStartPipelineForNewerJob() {
        document.setDocStatus(UploadStatus.PENDING);
        DocumentProcessingJob newer = DocumentProcessingJob.builder()
                .documentProcessingJobId(101L).document(document).jobStatus(JobStatus.QUEUED).build();
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L))
                .thenReturn(Optional.of(job), Optional.of(newer));
        service.triggerPipelineAsync(1L, 10L, 7L, "a.pdf", MediaType.APPLICATION_PDF,
                new ByteArrayResource(new byte[]{1}));
        verify(aiCoreClient, after(200).never()).triggerIngest(any());
        assertEquals(UploadStatus.PENDING, document.getDocStatus());
    }

    @Test
    void manualRetryWaitsForCommitAndSendsExplicitRestart() {
        document.setDocStatus(UploadStatus.PAUSED);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(documentProcessingJobRepository.save(any())).thenAnswer(invocation -> {
            DocumentProcessingJob saved = invocation.getArgument(0);
            saved.setDocumentProcessingJobId(101L);
            return saved;
        });
        when(documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L))
                .thenReturn(Optional.of(DocumentProcessingJob.builder().documentProcessingJobId(101L).build()));
        var content = new ByteArrayResource(new byte[]{1});
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.triggerPipelineRetry(1L, 10L, 7L, "a.pdf", MediaType.APPLICATION_PDF, content);
            verify(aiCoreClient, never()).triggerIngest(any());
            assertEquals(UploadStatus.PENDING, document.getDocStatus());
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.forEach(callback -> callback.afterCommit());
            ArgumentCaptor<AiIngestRequest> request = ArgumentCaptor.forClass(AiIngestRequest.class);
            verify(aiCoreClient, timeout(3000)).triggerIngest(request.capture());
            assertEquals(true, request.getValue().isRestart());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
