package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 存储路由配置服务
 * 管理 StorageProfile 的完整生命周期
 */
@Service
@Transactional
public class StorageProfileService {

    private static final Logger logger = LoggerFactory.getLogger(StorageProfileService.class);

    @Autowired
    private StorageProfileRepo storageProfileRepo;

    @Autowired
    private GameEnvironmentRepo environmentRepo;

    /**
     * 创建存储配置
     */
    public StorageProfileDTO createStorageProfile(StorageProfileDTO dto) {
        logger.info("Creating storage profile: {}", dto.name);

        // 生成唯一ID
        if (dto.id == null || dto.id.trim().isEmpty()) {
            dto.id = slug(dto.name);
        }

        // 检查 ID 是否已存在
        if (storageProfileRepo.existsById(dto.id)) {
            throw new IllegalArgumentException("Storage profile ID already exists: " + dto.id);
        }

        // 检查名称是否已存在
        if (storageProfileRepo.existsByNameAndDeletedAtIsNull(dto.name)) {
            throw new IllegalArgumentException("Storage profile name already exists: " + dto.name);
        }

        // 转换并保存
        StorageProfileEntity entity = dto.toEntity();
        entity = storageProfileRepo.save(entity);

        logger.info("Storage profile created successfully: {} (ID: {})", entity.name, entity.id);
        return new StorageProfileDTO(entity);
    }

    /**
     * 更新存储配置
     */
    public StorageProfileDTO updateStorageProfile(String profileId, StorageProfileDTO dto) {
        logger.info("Updating storage profile: {}", profileId);

        StorageProfileEntity entity = storageProfileRepo.findById(profileId)
            .filter(profile -> profile.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Storage profile not found: " + profileId));

        // 检查名称冲突
        if (dto.name != null && !dto.name.equals(entity.name)
            && storageProfileRepo.existsByNameAndDeletedAtIsNull(dto.name)) {
            throw new IllegalArgumentException("Storage profile name already exists: " + dto.name);
        }

        dto.updateEntity(entity);
        entity = storageProfileRepo.save(entity);

        logger.info("Storage profile updated successfully: {}", profileId);
        return new StorageProfileDTO(entity);
    }

    /**
     * 删除存储配置（软删除）
     */
    public void deleteStorageProfile(String profileId) {
        logger.info("Deleting storage profile: {}", profileId);

        StorageProfileEntity entity = storageProfileRepo.findById(profileId)
            .filter(profile -> profile.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Storage profile not found: " + profileId));

        // 检查是否有环境在使用该 profile
        List<GameEnvironmentEntity> environments = environmentRepo.findByStorageProfileIdAndDeletedAtIsNull(profileId);
        if (!environments.isEmpty()) {
            throw new IllegalStateException(
                "Cannot delete storage profile: " + profileId + ". It is currently in use by " +
                environments.size() + " environment(s). Please reassign or delete the environments first."
            );
        }

        // 软删除
        entity.deletedAt = LocalDateTime.now();
        entity.active = false;
        storageProfileRepo.save(entity);

        logger.info("Storage profile deleted successfully: {}", profileId);
    }

    /**
     * 根据ID获取存储配置
     */
    @Transactional(readOnly = true)
    public Optional<StorageProfileDTO> getStorageProfile(String profileId) {
        return storageProfileRepo.findById(profileId)
            .filter(profile -> profile.deletedAt == null)
            .map(entity -> {
                StorageProfileDTO dto = new StorageProfileDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            });
    }

    /**
     * 获取存储配置列表
     */
    @Transactional(readOnly = true)
    public List<StorageProfileDTO> getStorageProfiles() {
        return storageProfileRepo.findAll().stream()
            .filter(profile -> profile.deletedAt == null)
            .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
            .map(entity -> {
                StorageProfileDTO dto = new StorageProfileDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * 分页获取存储配置列表
     */
    @Transactional(readOnly = true)
    public Page<StorageProfileDTO> getStorageProfiles(Pageable pageable) {
        return storageProfileRepo.findAll(pageable)
            .map(entity -> {
                StorageProfileDTO dto = new StorageProfileDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            });
    }

    /**
     * 根据隔离策略获取存储配置
     */
    @Transactional(readOnly = true)
    public List<StorageProfileDTO> getStorageProfilesByStrategy(StorageProfileEntity.IsolationStrategy strategy) {
        return storageProfileRepo.findAll().stream()
            .filter(profile -> profile.deletedAt == null && profile.isolationStrategy == strategy)
            .map(entity -> {
                StorageProfileDTO dto = new StorageProfileDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * 获取活跃的存储配置
     */
    @Transactional(readOnly = true)
    public List<StorageProfileDTO> getActiveStorageProfiles() {
        return storageProfileRepo.findByActiveTrueAndDeletedAtIsNullOrderByNameAsc().stream()
            .map(entity -> {
                StorageProfileDTO dto = new StorageProfileDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            })
            .collect(Collectors.toList());
    }

    // 私有辅助方法

    /**
     * 丰富统计信息
     */
    private void enrichWithStatistics(StorageProfileDTO dto) {
        // 统计使用该 profile 的环境数量
        dto.totalEnvironments = (int) environmentRepo.findByStorageProfileIdAndDeletedAtIsNull(dto.id).size();
    }

    /**
     * 将字符串转换为 slug 格式
     */
    private String slug(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Storage profile name is required");
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
