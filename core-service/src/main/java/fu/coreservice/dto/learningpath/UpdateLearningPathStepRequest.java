package fu.coreservice.dto.learningpath;

import fu.coreservice.entity.PathStepStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateLearningPathStepRequest(@NotNull PathStepStatus status) {}
