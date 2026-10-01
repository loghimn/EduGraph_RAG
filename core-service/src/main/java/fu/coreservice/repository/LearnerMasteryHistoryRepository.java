package fu.coreservice.repository;

import fu.coreservice.entity.LearnerMastery;
import fu.coreservice.entity.LearnerMasteryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LearnerMasteryHistoryRepository extends JpaRepository<LearnerMasteryHistory, Long> {
    List<LearnerMasteryHistory> findByMasteryOrderByChangedAtDesc(LearnerMastery mastery);
    List<LearnerMasteryHistory> findByMastery_MasteryIdOrderByChangedAtDesc(Long masteryId);
}
