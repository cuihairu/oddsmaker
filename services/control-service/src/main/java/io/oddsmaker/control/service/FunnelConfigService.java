package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 漏斗配置服务
 * 提供漏斗配置的CRUD操作
 */
@Service
@Transactional
public class FunnelConfigService {

    private static final Logger logger = LoggerFactory.getLogger(FunnelConfigService.class);

    @Autowired
    private FunnelConfigRepo funnelConfigRepo;

    @Autowired
    private FunnelStepRepo funnelStepRepo;

    /**
     * 创建漏斗配置
     */
    public FunnelConfigEntity createFunnel(FunnelConfigEntity funnel) {
        logger.info("Creating funnel: {}", funnel.name);

        // 生成唯一ID
        if (funnel.id == null || funnel.id.trim().isEmpty()) {
            funnel.id = "funnel_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // 检查名称是否已存在
        if (funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull(funnel.gameId, funnel.name)) {
            throw new IllegalArgumentException("Funnel name already exists: " + funnel.name);
        }

        // 设置默认值
        if (funnel.type == null) {
            funnel.type = FunnelConfigEntity.FunnelType.STANDARD;
        }
        if (funnel.userKey == null) {
            funnel.userKey = "user_id";
        }
        if (funnel.enabled == null) {
            funnel.enabled = true;
        }

        // 保存漏斗配置
        funnel = funnelConfigRepo.save(funnel);

        // 保存漏斗步骤
        if (funnel.steps != null) {
            for (FunnelStepEntity step : funnel.steps) {
                step.funnel = funnel;
                funnelStepRepo.save(step);
            }
        }

        logger.info("Funnel created successfully: {} (ID: {})", funnel.name, funnel.id);
        return funnel;
    }

    /**
     * 更新漏斗配置
     */
    public FunnelConfigEntity updateFunnel(String funnelId, FunnelConfigEntity updates) {
        logger.info("Updating funnel: {}", funnelId);

        FunnelConfigEntity funnel = funnelConfigRepo.findById(funnelId)
            .filter(f -> f.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Funnel not found: " + funnelId));

        // 更新字段
        if (updates.name != null) {
            // 检查名称是否已存在
            if (!funnel.name.equals(updates.name) && 
                funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull(funnel.gameId, updates.name)) {
                throw new IllegalArgumentException("Funnel name already exists: " + updates.name);
            }
            funnel.name = updates.name;
        }
        if (updates.description != null) {
            funnel.description = updates.description;
        }
        if (updates.type != null) {
            funnel.type = updates.type;
        }
        if (updates.userKey != null) {
            funnel.userKey = updates.userKey;
        }
        if (updates.timeWindowSec != null) {
            funnel.timeWindowSec = updates.timeWindowSec;
        }
        if (updates.enabled != null) {
            funnel.enabled = updates.enabled;
        }

        // 更新步骤
        if (updates.steps != null) {
            // 删除旧步骤
            funnelStepRepo.deleteByFunnelId(funnel.id);
            
            // 添加新步骤
            for (FunnelStepEntity step : updates.steps) {
                step.funnel = funnel;
                funnelStepRepo.save(step);
            }
            funnel.steps = updates.steps;
        }

        funnel = funnelConfigRepo.save(funnel);

        logger.info("Funnel updated successfully: {}", funnelId);
        return funnel;
    }

    /**
     * 删除漏斗配置（软删除）
     */
    public void deleteFunnel(String funnelId) {
        logger.info("Deleting funnel: {}", funnelId);

        FunnelConfigEntity funnel = funnelConfigRepo.findById(funnelId)
            .orElseThrow(() -> new IllegalArgumentException("Funnel not found: " + funnelId));

        funnel.deletedAt = LocalDateTime.now();
        funnelConfigRepo.save(funnel);

        logger.info("Funnel deleted successfully: {}", funnelId);
    }

    /**
     * 根据ID查找漏斗配置
     */
    public FunnelConfigEntity findById(String funnelId) {
        return funnelConfigRepo.findById(funnelId)
            .filter(f -> f.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Funnel not found: " + funnelId));
    }

    /**
     * 根据游戏ID查找漏斗配置
     */
    public Page<FunnelConfigEntity> findByGameId(String gameId, Pageable pageable) {
        return funnelConfigRepo.findByGameIdAndDeletedAtIsNull(gameId, pageable);
    }

    /**
     * 根据游戏ID和类型查找漏斗配置
     */
    public List<FunnelConfigEntity> findByGameIdAndType(String gameId, FunnelConfigEntity.FunnelType type) {
        return funnelConfigRepo.findByGameIdAndTypeAndDeletedAtIsNull(gameId, type);
    }

    /**
     * 查找启用的漏斗配置
     */
    public List<FunnelConfigEntity> findEnabledByGameId(String gameId) {
        return funnelConfigRepo.findByGameIdAndEnabledTrueAndDeletedAtIsNull(gameId);
    }

    /**
     * 搜索漏斗配置
     */
    public Page<FunnelConfigEntity> searchByName(String gameId, String query, Pageable pageable) {
        return funnelConfigRepo.searchByName(gameId, query, pageable);
    }

    /**
     * 启用/禁用漏斗
     */
    public FunnelConfigEntity toggleFunnel(String funnelId, boolean enabled) {
        logger.info("Toggling funnel {} to {}", funnelId, enabled);

        FunnelConfigEntity funnel = funnelConfigRepo.findById(funnelId)
            .filter(f -> f.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Funnel not found: " + funnelId));

        funnel.enabled = enabled;
        funnel = funnelConfigRepo.save(funnel);

        logger.info("Funnel toggled successfully: {}", funnelId);
        return funnel;
    }

    /**
     * 添加漏斗步骤
     */
    public FunnelStepEntity addStep(String funnelId, FunnelStepEntity step) {
        logger.info("Adding step to funnel: {}", funnelId);

        FunnelConfigEntity funnel = funnelConfigRepo.findById(funnelId)
            .filter(f -> f.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Funnel not found: " + funnelId));

        step.funnel = funnel;
        step = funnelStepRepo.save(step);

        logger.info("Step added successfully to funnel: {}", funnelId);
        return step;
    }

    /**
     * 更新漏斗步骤
     */
    public FunnelStepEntity updateStep(String stepId, FunnelStepEntity updates) {
        logger.info("Updating step: {}", stepId);

        FunnelStepEntity step = funnelStepRepo.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

        if (updates.name != null) {
            step.name = updates.name;
        }
        if (updates.description != null) {
            step.description = updates.description;
        }
        if (updates.eventName != null) {
            step.eventName = updates.eventName;
        }
        if (updates.eventFilter != null) {
            step.eventFilter = updates.eventFilter;
        }
        if (updates.timeWindowSec != null) {
            step.timeWindowSec = updates.timeWindowSec;
        }
        if (updates.optional != null) {
            step.optional = updates.optional;
        }
        if (updates.icon != null) {
            step.icon = updates.icon;
        }
        if (updates.color != null) {
            step.color = updates.color;
        }

        step = funnelStepRepo.save(step);

        logger.info("Step updated successfully: {}", stepId);
        return step;
    }

    /**
     * 删除漏斗步骤
     */
    public void deleteStep(String stepId) {
        logger.info("Deleting step: {}", stepId);

        FunnelStepEntity step = funnelStepRepo.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

        funnelStepRepo.delete(step);

        logger.info("Step deleted successfully: {}", stepId);
    }

    /**
     * 获取漏斗统计信息
     */
    public long getFunnelCount(String gameId) {
        return funnelConfigRepo.countByGameIdAndDeletedAtIsNull(gameId);
    }

    /**
     * 获取启用的漏斗数量
     */
    public long getEnabledFunnelCount(String gameId) {
        return funnelConfigRepo.countByGameIdAndEnabledTrueAndDeletedAtIsNull(gameId);
    }
}