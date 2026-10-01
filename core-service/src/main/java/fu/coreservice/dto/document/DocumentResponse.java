package fu.coreservice.dto.document;

import fu.coreservice.entity.UploadStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    private Long documentId;

    private Long courseId;

    private Long uploadedBy;

    private String fileName;

    private String fileType;

    private Long fileSizeKb;

    private UploadStatus docStatus;

    private LocalDateTime uploadAt;

    private LocalDateTime parseAt;

}
