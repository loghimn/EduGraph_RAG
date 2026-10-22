package fu.coreservice.repository;

import fu.coreservice.entity.LearningPathPlan;
import fu.coreservice.entity.LearningPathStep;
import fu.coreservice.entity.PathStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface LearningPathStepRepository extends JpaRepository<LearningPathStep, Long> {
    interface PlanStepCounts {
        Long getPlanId();
        Long getTotal();
        Long getCompleted();
    }

    @Query("""
            select s.plan.planId as planId, count(s) as total,
                   sum(case when s.status = :completedStatus then 1 else 0 end) as completed
            from LearningPathStep s where s.plan.planId in :planIds group by s.plan.planId
            """)
    List<PlanStepCounts> countStepsForPlans(@Param("planIds") List<Long> planIds,
                                          @Param("completedStatus") PathStepStatus completedStatus);
    List<LearningPathStep> findByPlanOrderByStepOrderAsc(LearningPathPlan plan);
    List<LearningPathStep> findByPlan_PlanIdOrderByStepOrderAsc(Long planId);
    List<LearningPathStep> findByPlanAndStatus(LearningPathPlan plan, PathStepStatus status);
}
