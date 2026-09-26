package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** ml 训练产物注册仓库；「最新版本」以 created_at desc, id desc 定序。 */
public interface MlArtifactRepo extends JpaRepository<MlArtifactEntity, String> {

    Optional<MlArtifactEntity> findByGameIdAndModelTypeAndModelVersion(
            String gameId, String modelType, String modelVersion);

    Optional<MlArtifactEntity> findFirstByGameIdAndModelTypeOrderByCreatedAtDescIdDesc(
            String gameId, String modelType);

    List<MlArtifactEntity> findByGameIdAndModelTypeOrderByCreatedAtDescIdDesc(String gameId, String modelType);

    List<MlArtifactEntity> findByGameIdOrderByCreatedAtDescIdDesc(String gameId);

    /** 已接入产物注册的游戏（调度重训按此发现目标，手动注册一次即进入自动循环）。 */
    @Query("select distinct a.gameId from MlArtifactEntity a")
    List<String> findDistinctGameIds();
}
