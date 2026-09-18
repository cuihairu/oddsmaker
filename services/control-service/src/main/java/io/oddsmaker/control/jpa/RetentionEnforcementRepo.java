package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetentionEnforcementRepo extends JpaRepository<RetentionEnforcementEntity, String> {

    List<RetentionEnforcementEntity> findAllByOrderByChTable();
}
