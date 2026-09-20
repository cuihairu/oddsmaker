package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 漏斗步骤数据访问接口
 */
@Repository
public interface FunnelStepRepo extends JpaRepository<FunnelStepEntity, String> {

    /**
     * 根据漏斗ID删除所有步骤
     */
    void deleteByFunnelId(String funnelId);

}