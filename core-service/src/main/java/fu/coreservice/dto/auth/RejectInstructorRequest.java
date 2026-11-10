package fu.coreservice.dto.auth;

import jakarta.validation.constraints.Size;

public record RejectInstructorRequest(@Size(max = 1000) String reason) {}
