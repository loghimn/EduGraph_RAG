package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentControlServiceTest {
    @Mock DocumentRepository documents;
    @Mock DocumentProcessingJobRepository jobs;
    @Mock CourseRepository courses;
    @Mock SupabaseStorageService storage;
    @Mock UserRepository users;
    @Mock AiDocumentProcessingService processing;
    @Mock AiCoreClient python;
    @InjectMocks DocumentServiceImpl service;
    Document document;
    DocumentProcessingJob job;

    @BeforeEach
    void setup() {
        User user = User.builder().userId(7L).email("owner@test.local").build();
        Course course = Course.builder().courseId(1L).user(user).build();
        document = Document.builder().documentId(10L).course(course).user(user)
                .docStatus(UploadStatus.PROCESSING).fileUrlPath("course/1/a.pdf").build();
        job = DocumentProcessingJob.builder().documentProcessingJobId(100L)
                .document(document).jobStatus(JobStatus.RUNNING).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@test.local", null, java.util.List.of()));
        when(users.findByEmail("owner@test.local")).thenReturn(Optional.of(user));
        when(courses.findById(1L)).thenReturn(Optional.of(course));
        when(documents.findForUpdate(10L)).thenReturn(Optional.of(document));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void pauseConfirmsPythonStopBeforeSavingPaused() {
        when(jobs.findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L))
                .thenReturn(Optional.of(job));
        service.pauseProcessing(1L, 10L);
        InOrder order = inOrder(python, documents);
        order.verify(python).pauseDocument(10L, 1L, 7L);
        order.verify(documents).save(document);
        assertEquals(UploadStatus.PAUSED, document.getDocStatus());
        assertEquals(JobStatus.PAUSED, job.getJobStatus());
    }

    @Test
    void pauseFailureDoesNotChangeDatabaseStatus() {
        doThrow(new AppException(ErrorCode.AI_CORE_UNAVAILABLE)).when(python).pauseDocument(10L, 1L, 7L);
        assertThrows(AppException.class, () -> service.pauseProcessing(1L, 10L));
        assertEquals(UploadStatus.PROCESSING, document.getDocStatus());
        verify(documents, never()).save(any());
    }

    @Test
    void cancelConfirmsPythonStopBeforeDeletingStorage() {
        when(jobs.findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(10L))
                .thenReturn(Optional.of(job));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.cancelDocument(1L, 10L);
            verify(python).cancelDocument(10L, 1L, 7L);
            assertEquals(UploadStatus.CANCELLED, document.getDocStatus());
            assertEquals(JobStatus.CANCELLED, job.getJobStatus());
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.forEach(callback -> callback.afterCommit());
            verify(storage).deleteFile("course/1/a.pdf");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void pythonUnavailableLeavesFileAndDatabaseIntact() {
        doThrow(new AppException(ErrorCode.AI_CORE_UNAVAILABLE)).when(python).cancelDocument(10L, 1L, 7L);
        assertThrows(AppException.class, () -> service.cancelDocument(1L, 10L));
        verifyNoInteractions(storage);
        verify(documents, never()).save(any());
        assertEquals(UploadStatus.PROCESSING, document.getDocStatus());
    }
}
