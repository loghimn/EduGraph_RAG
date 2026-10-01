package fu.coreservice.repository;

import fu.coreservice.entity.LearningPathPlan;
import fu.coreservice.entity.LearningPathStep;
import fu.coreservice.entity.PathStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LearningPathStepRepository extends JpaRepository<LearningPathStep, Long> {
    List<LearningPathStep> findByPlanOrderByStepOrderAsc(LearningPathPlan plan);
    List<LearningPathStep> findByPlan_PlanIdOrderByStepOrderAsc(Long planId);
    List<LearningPathStep> findByPlanAndStatus(LearningPathPlan plan, PathStepStatus status);
}
