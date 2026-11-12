package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.notebook.NotebookRequest;
import fu.coreservice.dto.notebook.NotebookResponse;

public interface NotebookService {
    boolean owns(Long id);
    boolean isNotebook(Long id);
    NotebookResponse get(Long id);
    NotebookResponse create(NotebookRequest request);
    PageResponse<NotebookResponse> list(int page, int size);
}
