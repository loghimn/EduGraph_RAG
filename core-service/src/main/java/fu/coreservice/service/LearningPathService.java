package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.learningpath.CreateLearningPathRequest;
import fu.coreservice.dto.learningpath.LearningPathDetail;
import fu.coreservice.dto.learningpath.LearningPathSummary;
import fu.coreservice.dto.learningpath.UpdateLearningPathStepRequest;
import fu.coreservice.entity.LearningPathStatus;

public interface LearningPathService {

    LearningPathDetail create(Long courseId, CreateLearningPathRequest request);

    PageResponse<LearningPathSummary> list(Long courseId, LearningPathStatus status, int page, int size);

    LearningPathDetail get(Long planId);

    LearningPathDetail updateStep(Long planId, Long stepId, UpdateLearningPathStepRequest request);
}
