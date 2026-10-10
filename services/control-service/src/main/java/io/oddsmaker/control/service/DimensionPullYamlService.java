package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DimensionPullConfigEntity;
import io.oddsmaker.control.jpa.DimensionPullConfigRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 维度同步 Pull 配置的 YAML 导入/导出（dimension-sync.md「配置驱动」Control 面）。
 *
 * 导出：该游戏全部配置 1:1 成 YAML（凭证永不落盘），供评审/备份/接新游戏起稿。
 * 导入：逐条按 (environment, sourceKey) 定位——已存在走 update（credential 缺省=保留既有凭证），
 * 不存在走 create（credential 必填，密文托管）；方法级事务，任一条失败整体回滚（不半套生效）。
 * 字段级校验复用 DimensionPullService 既有链（环境存在/endpoint 形态/区间/唯一约束）。
 */
@Service
public class DimensionPullYamlService {

    private final DimensionPullService pullService;
    private final DimensionPullConfigRepo configRepo;

    public DimensionPullYamlService(DimensionPullService pullService, DimensionPullConfigRepo configRepo) {
        this.pullService = pullService;
        this.configRepo = configRepo;
    }

    /** 导出该游戏全部 Pull 配置为 YAML 文本（凭证不落盘）。 */
    @Transactional(readOnly = true)
    public String export(String gameId) {
        pullService.requireGame(gameId);
        return DimensionPullYaml.toYaml(configRepo.findByGameId(gameId));
    }

    /**
     * 导入 YAML：返回 created/updated 计数。任一条失败抛 IllegalArgumentException → 整体回滚。
     * 定位键 (environment, sourceKey) 与表唯一约束同口径：改 environment 即另一环境的新档。
     */
    @Transactional
    public Map<String, Object> importConfigs(String gameId, String yaml) {
        pullService.requireGame(gameId);
        List<DimensionPullService.PullConfigUpsert> entries = DimensionPullYaml.parse(yaml);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("YAML 中没有可导入的 dimension 配置");
        }
        int created = 0;
        int updated = 0;
        for (DimensionPullService.PullConfigUpsert entry : entries) {
            DimensionPullConfigEntity existing = configRepo
                    .findByGameIdAndEnvironmentAndSourceKey(gameId, entry.environment, entry.sourceKey)
                    .orElse(null);
            if (existing == null) {
                pullService.create(gameId, entry);
                created++;
            } else {
                pullService.update(gameId, existing.id, entry);
                updated++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("total", entries.size());
        return result;
    }
}
