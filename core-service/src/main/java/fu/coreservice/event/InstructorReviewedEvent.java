package fu.coreservice.event;

/** Immutable delivery details captured when an administrator reviews a registration. */
public record InstructorReviewedEvent(Long userId, String email, String username,
                                      boolean approved, boolean active, String rejectionReason) {}
