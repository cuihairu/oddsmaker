package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedeemCodeRepo extends JpaRepository<RedeemCodeEntity, String> {

    Optional<RedeemCodeEntity> findByCode(String code);

    List<RedeemCodeEntity> findByBatchId(String batchId);

    /**
     * 原子核销唯一码：仅当状态为 AVAILABLE 时更新，返回受影响行数（1=成功）。
     */
    @Modifying
    @Query("UPDATE RedeemCodeEntity c SET c.status = 'REDEEMED', c.redeemedBy = :playerKey, c.redeemedAt = CURRENT_TIMESTAMP "
        + "WHERE c.id = :id AND c.status = 'AVAILABLE'")
    int redeemIfAvailable(@Param("id") String id, @Param("playerKey") String playerKey);
}
