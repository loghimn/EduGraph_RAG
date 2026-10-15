package fu.coreservice.dto.document;

import fu.coreservice.entity.JobStatus;
import fu.coreservice.entity.JobType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessingResponse {

    private Long documentId;

    private JobStatus jobStatus;

    private JobType jobType;

    private String errorMessage;

    private LocalDateTime updatedAt;
}
