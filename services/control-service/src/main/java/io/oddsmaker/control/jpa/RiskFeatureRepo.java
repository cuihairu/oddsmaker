package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RiskFeatureRepo extends JpaRepository<RiskFeatureEntity, Long> {

    /** 某主体某特征的最近窗口行（规则评估/查询取数路径，配 idx_risk_features_lookup） */
    List<RiskFeatureEntity> findTop20ByGameIdAndEnvironmentAndScopeKeyAndFeatureNameOrderByWindowEndDesc(
            String gameId, String environment, String scopeKey, String featureName);

    /** 某主体窗口闭合之后的全部特征行（一次取全特征做规则评估） */
    List<RiskFeatureEntity> findByGameIdAndEnvironmentAndScopeKeyAndWindowEndAfter(
            String gameId, String environment, String scopeKey, LocalDateTime windowEndAfter);
}
