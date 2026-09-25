package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户分群数据访问接口。
 */
@Repository
public interface SegmentRepo extends JpaRepository<SegmentEntity, String> {

    List<SegmentEntity> findByGameIdAndDeletedAtIsNullOrderByNameAsc(String gameId);

    List<SegmentEntity> findByGameIdAndDeletedAtIsNull(String gameId);

    Optional<SegmentEntity> findByIdAndDeletedAtIsNull(String id);

    Optional<SegmentEntity> findByGameIdAndNameAndDeletedAtIsNull(String gameId, String name);
}
