package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetricAlertRuleRepo extends JpaRepository<MetricAlertRuleEntity, String> {

    List<MetricAlertRuleEntity> findByGameIdAndDeletedAtIsNullOrderByName(String gameId);

    /** 调度评估：全部启用且未删除的规则 */
    List<MetricAlertRuleEntity> findByEnabledTrueAndDeletedAtIsNull();
}
