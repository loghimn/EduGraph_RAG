package fu.coreservice.dto.document;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDownloadResponse {

    private Long documentId;
    private String fileName;
    private String downloadUrl;

}
