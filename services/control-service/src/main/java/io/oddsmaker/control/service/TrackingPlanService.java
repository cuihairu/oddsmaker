package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.*;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 追踪计划管理服务
 */
@Service
@Transactional
public class TrackingPlanService {

    private static final Logger logger = LoggerFactory.getLogger(TrackingPlanService.class);

    @Autowired
    private TrackingPlanRepo trackingPlanRepo;

    @Autowired
    private EventDefinitionRepo eventDefinitionRepo;

    @Autowired
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private GameEnvironmentRepo environmentRepo;

    /**
     * 创建追踪计划
     */
    public TrackingPlanDTO createTrackingPlan(String gameId, TrackingPlanDTO dto) {
        logger.info("Creating tracking plan for game: {}", gameId);

        // 验证游戏存在
        requireGame(gameId);

        // 检查名称是否已存在
        if (trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc(gameId)
                .stream()
                .anyMatch(tp -> tp.name.equals(dto.name))) {
            throw new IllegalArgumentException("Tracking plan name already exists: " + dto.name);
        }

        TrackingPlanEntity entity = dto.toEntity();
        entity.id = "tp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        entity.gameId = gameId;
        entity.status = TrackingPlanEntity.PlanStatus.DRAFT;
        entity.totalEvents = 0;
        entity.activeEvents = 0;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = LocalDateTime.now();

        // 验证环境存在（如果指定）
        if (entity.environmentId != null) {
            requireEnvironment(gameId, entity.environmentId);
        }

        entity = trackingPlanRepo.save(entity);
        logger.info("Tracking plan created: {}", entity.id);
        return new TrackingPlanDTO(entity);
    }

    /**
     * 更新追踪计划（仅草稿状态可编辑）
     */
    public TrackingPlanDTO updateTrackingPlan(String trackingPlanId, TrackingPlanDTO dto) {
        logger.info("Updating tracking plan: {}", trackingPlanId);

        TrackingPlanEntity entity = requireTrackingPlan(trackingPlanId);
        if (!entity.canEdit()) {
            throw new IllegalStateException("Only draft tracking plans can be edited");
        }

        dto.updateEntity(entity);
        entity.updatedAt = LocalDateTime.now();
        entity = trackingPlanRepo.save(entity);

        logger.info("Tracking plan updated: {}", trackingPlanId);
        return new TrackingPlanDTO(entity);
    }

    /**
     * 激活追踪计划
     */
    public TrackingPlanDTO activateTrackingPlan(String trackingPlanId, String userId) {
        logger.info("Activating tracking plan: {} by user: {}", trackingPlanId, userId);

        TrackingPlanEntity entity = requireTrackingPlan(trackingPlanId);
        entity.activate(userId);
        entity.updatedAt = LocalDateTime.now();
        entity = trackingPlanRepo.save(entity);

        // 如果有环境绑定，更新活跃事件数
        updateEventCounts(entity);

        logger.info("Tracking plan activated: {}", trackingPlanId);
        return new TrackingPlanDTO(entity);
    }

    /**
     * 弃用追踪计划
     */
    public TrackingPlanDTO deactivateTrackingPlan(String trackingPlanId) {
        logger.info("Deactivating tracking plan: {}", trackingPlanId);

        TrackingPlanEntity entity = requireTrackingPlan(trackingPlanId);
        entity.deactivate();
        entity.updatedAt = LocalDateTime.now();
        entity = trackingPlanRepo.save(entity);

        logger.info("Tracking plan deactivated: {}", trackingPlanId);
        return new TrackingPlanDTO(entity);
    }

    /**
     * 删除追踪计划（软删除）
     */
    public void deleteTrackingPlan(String trackingPlanId) {
        logger.info("Deleting tracking plan: {}", trackingPlanId);

        TrackingPlanEntity entity = requireTrackingPlan(trackingPlanId);
        if (entity.isActive()) {
            throw new IllegalStateException("Cannot delete active tracking plan. Please deactivate first.");
        }

        entity.deletedAt = LocalDateTime.now();
        entity.status = TrackingPlanEntity.PlanStatus.DEPRECATED;
        trackingPlanRepo.save(entity);

        // 软删除相关事件定义
        List<EventDefinitionEntity> eventDefs = eventDefinitionRepo.findByTrackingPlanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(trackingPlanId);
        eventDefs.forEach(ed -> {
            ed.deletedAt = LocalDateTime.now();
            ed.status = EventDefinitionEntity.DefinitionStatus.DEPRECATED;
            eventDefinitionRepo.save(ed);
        });

        logger.info("Tracking plan deleted: {}", trackingPlanId);
    }

    /**
     * 获取追踪计划
     */
    @Transactional(readOnly = true)
    public Optional<TrackingPlanDTO> getTrackingPlan(String trackingPlanId) {
        return trackingPlanRepo.findByIdAndDeletedAtIsNull(trackingPlanId)
            .map(entity -> {
                TrackingPlanDTO dto = new TrackingPlanDTO(entity);
                enrichWithGameInfo(dto);
                return dto;
            });
    }

    /**
     * 列出游戏的追踪计划
     */
    @Transactional(readOnly = true)
    public List<TrackingPlanDTO> listTrackingPlans(String gameId) {
        requireGame(gameId);
        return trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc(gameId).stream()
            .map(TrackingPlanDTO::new)
            .collect(Collectors.toList());
    }

    /**
     * 获取游戏的活跃追踪计划
     */
    @Transactional(readOnly = true)
    public List<TrackingPlanDTO> getActiveTrackingPlans(String gameId) {
        requireGame(gameId);
        return trackingPlanRepo.findActiveByGameId(gameId).stream()
            .map(TrackingPlanDTO::new)
            .collect(Collectors.toList());
    }

    /**
     * 根据环境获取追踪计划
     */
    @Transactional(readOnly = true)
    public List<TrackingPlanDTO> getTrackingPlansForEnvironment(String gameId, String environmentId) {
        requireGame(gameId);
        return trackingPlanRepo.findByGameIdAndEnvironment(gameId, environmentId).stream()
            .map(TrackingPlanDTO::new)
            .collect(Collectors.toList());
    }

    // ========== 事件定义管理 ==========

    /**
     * 创建事件定义
     */
    public EventDefinitionDTO createEventDefinition(String trackingPlanId, EventDefinitionDTO dto) {
        logger.info("Creating event definition for tracking plan: {}", trackingPlanId);

        TrackingPlanEntity trackingPlan = requireTrackingPlan(trackingPlanId);
        if (!trackingPlan.canEdit()) {
            throw new IllegalStateException("Event definitions can only be added to draft tracking plans");
        }

        // 检查事件名是否已存在
        if (eventDefinitionRepo.existsByTrackingPlanIdAndEventName(trackingPlanId, dto.eventName)) {
            throw new IllegalArgumentException("Event name already exists: " + dto.eventName);
        }

        EventDefinitionEntity entity = dto.toEntity();
        entity.id = "ed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        entity.trackingPlanId = trackingPlanId;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = LocalDateTime.now();
        entity.usageCount = 0L;

        entity = eventDefinitionRepo.save(entity);

        // 更新追踪计划的事件计数
        trackingPlan.totalEvents = (int) eventDefinitionRepo.countByTrackingPlanId(trackingPlanId);
        trackingPlanRepo.save(trackingPlan);

        logger.info("Event definition created: {}", entity.id);
        return new EventDefinitionDTO(entity);
    }

    /**
     * 更新事件定义
     */
    public EventDefinitionDTO updateEventDefinition(String eventDefinitionId, EventDefinitionDTO dto) {
        logger.info("Updating event definition: {}", eventDefinitionId);

        EventDefinitionEntity entity = requireEventDefinition(eventDefinitionId);
        TrackingPlanEntity trackingPlan = requireTrackingPlan(entity.trackingPlanId);
        if (!trackingPlan.canEdit()) {
            throw new IllegalStateException("Event definitions can only be edited in draft tracking plans");
        }

        dto.updateEntity(entity);
        entity.updatedAt = LocalDateTime.now();
        entity = eventDefinitionRepo.save(entity);

        logger.info("Event definition updated: {}", eventDefinitionId);
        return new EventDefinitionDTO(entity);
    }

    /**
     * 删除事件定义（软删除）
     */
    public void deleteEventDefinition(String eventDefinitionId) {
        logger.info("Deleting event definition: {}", eventDefinitionId);

        EventDefinitionEntity entity = requireEventDefinition(eventDefinitionId);
        TrackingPlanEntity trackingPlan = requireTrackingPlan(entity.trackingPlanId);
        if (!trackingPlan.canEdit()) {
            throw new IllegalStateException("Event definitions can only be deleted from draft tracking plans");
        }

        entity.deletedAt = LocalDateTime.now();
        entity.status = EventDefinitionEntity.DefinitionStatus.DEPRECATED;
        eventDefinitionRepo.save(entity);

        // 更新追踪计划的事件计数
        trackingPlan.totalEvents = (int) eventDefinitionRepo.countByTrackingPlanId(trackingPlan.id);
        trackingPlanRepo.save(trackingPlan);

        logger.info("Event definition deleted: {}", eventDefinitionId);
    }

    /**
     * 获取事件定义
     */
    @Transactional(readOnly = true)
    public Optional<EventDefinitionDTO> getEventDefinition(String eventDefinitionId) {
        return eventDefinitionRepo.findById(eventDefinitionId)
            .filter(ed -> ed.deletedAt == null)
            .map(EventDefinitionDTO::new);
    }

    /**
     * 列出追踪计划的事件定义
     */
    @Transactional(readOnly = true)
    public List<EventDefinitionDTO> listEventDefinitions(String trackingPlanId) {
        requireTrackingPlan(trackingPlanId);
        return eventDefinitionRepo.findByTrackingPlanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(trackingPlanId).stream()
            .map(EventDefinitionDTO::new)
            .collect(Collectors.toList());
    }

    // ========== 属性定义管理 ==========

    /**
     * 创建属性定义
     */
    public EventPropertyDefinitionDTO createPropertyDefinition(String eventDefinitionId, EventPropertyDefinitionDTO dto) {
        logger.info("Creating property definition for event: {}", eventDefinitionId);

        EventDefinitionEntity eventDef = requireEventDefinition(eventDefinitionId);
        TrackingPlanEntity trackingPlan = requireTrackingPlan(eventDef.trackingPlanId);
        if (!trackingPlan.canEdit()) {
            throw new IllegalStateException("Property definitions can only be added to draft tracking plans");
        }

        // 检查属性名是否已存在
        if (propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName(eventDefinitionId, dto.propertyName).isPresent()) {
            throw new IllegalArgumentException("Property name already exists: " + dto.propertyName);
        }

        EventPropertyDefinitionEntity entity = dto.toEntity();
        entity.id = "epd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        entity.eventDefinitionId = eventDefinitionId;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = LocalDateTime.now();

        entity = propertyDefinitionRepo.save(entity);

        logger.info("Property definition created: {}", entity.id);
        return new EventPropertyDefinitionDTO(entity);
    }

    /**
     * 获取事件的所有属性定义
     */
    @Transactional(readOnly = true)
    public List<EventPropertyDefinitionDTO> listPropertyDefinitions(String eventDefinitionId) {
        requireEventDefinition(eventDefinitionId);
        return propertyDefinitionRepo.findByEventDefinitionIdAndDeletedAtIsNullOrderByDisplayOrderAsc(eventDefinitionId).stream()
            .map(EventPropertyDefinitionDTO::new)
            .collect(Collectors.toList());
    }

    // ========== 辅助方法 ==========

    private GameEntity requireGame(String gameId) {
        return gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }

    private GameEnvironmentEntity requireEnvironment(String gameId, String environmentId) {
        return environmentRepo.findById(environmentId)
            .filter(env -> env.deletedAt == null && env.gameId.equals(gameId))
            .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentId));
    }

    private TrackingPlanEntity requireTrackingPlan(String trackingPlanId) {
        return trackingPlanRepo.findByIdAndDeletedAtIsNull(trackingPlanId)
            .orElseThrow(() -> new IllegalArgumentException("Tracking plan not found: " + trackingPlanId));
    }

    private EventDefinitionEntity requireEventDefinition(String eventDefinitionId) {
        return eventDefinitionRepo.findById(eventDefinitionId)
            .filter(ed -> ed.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Event definition not found: " + eventDefinitionId));
    }

    private void enrichWithGameInfo(TrackingPlanDTO dto) {
        if (dto.gameId != null) {
            gameRepo.findById(dto.gameId).ifPresent(game -> dto.gameName = game.name);
        }
        if (dto.environmentId != null) {
            environmentRepo.findById(dto.environmentId).ifPresent(env -> dto.environmentName = env.name);
        }
    }

    private void updateEventCounts(TrackingPlanEntity entity) {
        long total = eventDefinitionRepo.countByTrackingPlanId(entity.id);
        long active = eventDefinitionRepo.findActiveByTrackingPlanId(entity.id).stream()
            .filter(ed -> ed.isActive()).count();
        entity.totalEvents = (int) total;
        entity.activeEvents = (int) active;
    }
}
