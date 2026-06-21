package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 机器学习模型服务
 * 管理ML模型的生命周期，包括训练、评估、部署和预测
 */
@Service
@Transactional
public class MLModelService {

    private static final Logger logger = LoggerFactory.getLogger(MLModelService.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private MLModelRepo mlModelRepo;

    @Autowired
    private ModelTrainingRepo modelTrainingRepo;

    @Autowired
    private ModelPredictionRepo modelPredictionRepo;

    @Autowired
    private AuditLogService auditLogService;

    // ==================== 模型管理 ====================

    /**
     * 创建ML模型
     */
    public MLModelEntity createModel(String gameId, String modelName, MLModelEntity.ModelType modelType,
                                     String algorithm, String framework, String description, String createdBy) {

        // 检查名称唯一性
        if (mlModelRepo.existsByModelNameAndGameIdAndDeletedAtIsNull(modelName, gameId)) {
            throw new IllegalArgumentException("Model name already exists: " + modelName);
        }

        MLModelEntity model = new MLModelEntity();
        model.id = "ml_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        model.gameId = gameId;
        model.modelName = modelName;
        model.modelType = modelType;
        model.algorithm = algorithm;
        model.framework = framework;
        model.description = description;
        model.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        model.version = 1;
        model.createdBy = createdBy;

        model = mlModelRepo.save(model);

        auditLogService.logCreate("ml_model", model.id, modelName, createdBy, createdBy, null,
            Map.of("type", modelType, "gameId", gameId, "algorithm", algorithm));

        logger.info("Created ML model: {} for game: {}", model.id, gameId);
        return model;
    }

    /**
     * 更新模型配置
     */
    public MLModelEntity updateModel(String modelId, Map<String, Object> updates, String updatedBy) {
        MLModelEntity model = getModel(modelId);

        if (updates.containsKey("description")) {
            model.description = (String) updates.get("description");
        }
        if (updates.containsKey("modelConfig")) {
            try {
                model.modelConfig = objectMapper.writeValueAsString(updates.get("modelConfig"));
            } catch (Exception e) {
                logger.error("Failed to serialize model config", e);
            }
        }
        if (updates.containsKey("hyperparameters")) {
            try {
                model.hyperparameters = objectMapper.writeValueAsString(updates.get("hyperparameters"));
            } catch (Exception e) {
                logger.error("Failed to serialize hyperparameters", e);
            }
        }
        if (updates.containsKey("featureConfig")) {
            try {
                model.featureConfig = objectMapper.writeValueAsString(updates.get("featureConfig"));
            } catch (Exception e) {
                logger.error("Failed to serialize feature config", e);
            }
        }
        if (updates.containsKey("inputSchema")) {
            try {
                model.inputSchema = objectMapper.writeValueAsString(updates.get("inputSchema"));
            } catch (Exception e) {
                logger.error("Failed to serialize input schema", e);
            }
        }
        if (updates.containsKey("outputSchema")) {
            try {
                model.outputSchema = objectMapper.writeValueAsString(updates.get("outputSchema"));
            } catch (Exception e) {
                logger.error("Failed to serialize output schema", e);
            }
        }
        if (updates.containsKey("trainingConfig")) {
            try {
                model.trainingConfig = objectMapper.writeValueAsString(updates.get("trainingConfig"));
            } catch (Exception e) {
                logger.error("Failed to serialize training config", e);
            }
        }
        if (updates.containsKey("retrainPolicy")) {
            try {
                model.retrainPolicy = objectMapper.writeValueAsString(updates.get("retrainPolicy"));
            } catch (Exception e) {
                logger.error("Failed to serialize retrain policy", e);
            }
        }

        model = mlModelRepo.save(model);

        auditLogService.logUpdate("ml_model", model.id, model.modelName, updatedBy, updatedBy, null,
            Map.of("updates", updates.keySet()));

        return model;
    }

    /**
     * 获取模型
     */
    public MLModelEntity getModel(String modelId) {
        return mlModelRepo.findById(modelId)
            .orElseThrow(() -> new IllegalArgumentException("ML model not found: " + modelId));
    }

    /**
     * 获取游戏的所有模型
     */
    public List<MLModelEntity> getGameModels(String gameId) {
        return mlModelRepo.findByGameIdAndDeletedAtIsNull(gameId);
    }

    /**
     * 获取已部署的模型
     */
    public List<MLModelEntity> getDeployedModels(String gameId) {
        if (gameId != null) {
            return mlModelRepo.findDeployedByGameId(gameId);
        }
        return mlModelRepo.findAllDeployed();
    }

    /**
     * 归档模型
     */
    public MLModelEntity archiveModel(String modelId, String archivedBy) {
        MLModelEntity model = getModel(modelId);

        if (model.isTraining()) {
            throw new IllegalStateException("Cannot archive model while training");
        }

        model.archive();
        model = mlModelRepo.save(model);

        auditLogService.logUpdate("ml_model", model.id, model.modelName, archivedBy, archivedBy, null,
            Map.of("action", "archive"));

        logger.info("Archived ML model: {}", modelId);
        return model;
    }

    /**
     * 删除模型（软删除）
     */
    public void deleteModel(String modelId, String deletedBy) {
        MLModelEntity model = getModel(modelId);

        if (model.isDeployed()) {
            throw new IllegalStateException("Cannot delete deployed model. Archive it first.");
        }

        model.deletedAt = LocalDateTime.now();
        mlModelRepo.save(model);

        auditLogService.logDelete("ml_model", model.id, model.modelName, deletedBy, deletedBy, null);

        logger.info("Deleted ML model: {}", modelId);
    }

    // ==================== 模型训练 ====================

    /**
     * 创建训练任务
     */
    public ModelTrainingEntity createTrainingJob(String modelId, String jobName,
                                                  Map<String, Object> trainingConfig,
                                                  Map<String, Object> datasetConfig,
                                                  Map<String, Object> hyperparameterConfig,
                                                  String triggeredBy) {

        MLModelEntity model = getModel(modelId);

        if (model.isTraining()) {
            throw new IllegalStateException("Model is already training");
        }

        ModelTrainingEntity training = new ModelTrainingEntity();
        training.id = "train_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        training.modelId = modelId;
        training.gameId = model.gameId;
        training.trainingJobName = jobName;
        training.triggeredBy = triggeredBy;
        training.triggerType = "manual";

        try {
            if (trainingConfig != null) {
                training.trainingConfig = objectMapper.writeValueAsString(trainingConfig);
            }
            if (datasetConfig != null) {
                training.datasetConfig = objectMapper.writeValueAsString(datasetConfig);
            }
            if (hyperparameterConfig != null) {
                training.hyperparameterConfig = objectMapper.writeValueAsString(hyperparameterConfig);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize training config", e);
        }

        training = modelTrainingRepo.save(training);

        // 更新模型状态
        model.startTraining();
        mlModelRepo.save(model);

        auditLogService.logCreate("model_training", training.id, jobName, triggeredBy, triggeredBy, null,
            Map.of("modelId", modelId, "triggerType", "manual"));

        logger.info("Created training job: {} for model: {}", training.id, modelId);
        return training;
    }

    /**
     * 启动训练任务
     */
    public ModelTrainingEntity startTraining(String trainingId) {
        ModelTrainingEntity training = modelTrainingRepo.findById(trainingId)
            .orElseThrow(() -> new IllegalArgumentException("Training job not found: " + trainingId));

        if (!training.isPending()) {
            throw new IllegalStateException("Training job is not pending: " + training.trainingStatus);
        }

        training.start();
        training = modelTrainingRepo.save(training);

        logger.info("Started training job: {}", trainingId);
        return training;
    }

    /**
     * 更新训练进度
     */
    public ModelTrainingEntity updateTrainingProgress(String trainingId, int epoch, int totalEpochs,
                                                       double loss, Map<String, Object> metrics) {
        ModelTrainingEntity training = modelTrainingRepo.findById(trainingId)
            .orElseThrow(() -> new IllegalArgumentException("Training job not found: " + trainingId));

        if (!training.isRunning()) {
            throw new IllegalStateException("Training job is not running");
        }

        training.updateProgress(epoch, totalEpochs, loss);

        if (metrics != null) {
            try {
                training.trainingMetrics = objectMapper.writeValueAsString(metrics);
            } catch (Exception e) {
                logger.error("Failed to serialize training metrics", e);
            }
        }

        training = modelTrainingRepo.save(training);

        logger.debug("Updated training progress: {} - epoch {}/{}", trainingId, epoch, totalEpochs);
        return training;
    }

    /**
     * 完成训练任务
     */
    public ModelTrainingEntity completeTraining(String trainingId, String artifactPath,
                                                 Map<String, Object> finalMetrics) {
        ModelTrainingEntity training = modelTrainingRepo.findById(trainingId)
            .orElseThrow(() -> new IllegalArgumentException("Training job not found: " + trainingId));

        if (!training.isRunning()) {
            throw new IllegalStateException("Training job is not running");
        }

        training.complete();
        training.artifactPath = artifactPath;

        if (finalMetrics != null) {
            try {
                training.validationMetrics = objectMapper.writeValueAsString(finalMetrics);
            } catch (Exception e) {
                logger.error("Failed to serialize final metrics", e);
            }
        }

        training = modelTrainingRepo.save(training);

        // 更新模型状态
        MLModelEntity model = getModel(training.modelId);
        model.completeTraining();
        model.modelArtifactPath = artifactPath;
        model.version++;

        // 更新模型指标
        if (finalMetrics != null) {
            if (finalMetrics.containsKey("accuracy")) {
                model.accuracyMetric = ((Number) finalMetrics.get("accuracy")).doubleValue();
            }
            if (finalMetrics.containsKey("precision")) {
                model.precisionMetric = ((Number) finalMetrics.get("precision")).doubleValue();
            }
            if (finalMetrics.containsKey("recall")) {
                model.recallMetric = ((Number) finalMetrics.get("recall")).doubleValue();
            }
            if (finalMetrics.containsKey("f1")) {
                model.f1Score = ((Number) finalMetrics.get("f1")).doubleValue();
            }
            if (finalMetrics.containsKey("auc")) {
                model.aucScore = ((Number) finalMetrics.get("auc")).doubleValue();
            }
        }

        mlModelRepo.save(model);

        auditLogService.logUpdate("model_training", training.id, training.trainingJobName,
            training.triggeredBy, training.triggeredBy, null, Map.of("action", "complete"));

        logger.info("Completed training job: {} for model: {}", trainingId, training.modelId);
        return training;
    }

    /**
     * 训练失败
     */
    public ModelTrainingEntity failTraining(String trainingId, String errorMessage, String stackTrace) {
        ModelTrainingEntity training = modelTrainingRepo.findById(trainingId)
            .orElseThrow(() -> new IllegalArgumentException("Training job not found: " + trainingId));

        training.fail(errorMessage);
        training.errorStackTrace = stackTrace;
        training = modelTrainingRepo.save(training);

        // 更新模型状态
        MLModelEntity model = getModel(training.modelId);
        model.fail();
        mlModelRepo.save(model);

        logger.error("Training job failed: {} - {}", trainingId, errorMessage);
        return training;
    }

    /**
     * 取消训练任务
     */
    public ModelTrainingEntity cancelTraining(String trainingId, String cancelledBy) {
        ModelTrainingEntity training = modelTrainingRepo.findById(trainingId)
            .orElseThrow(() -> new IllegalArgumentException("Training job not found: " + trainingId));

        if (!training.isPending() && !training.isRunning()) {
            throw new IllegalStateException("Cannot cancel training in status: " + training.trainingStatus);
        }

        training.cancel();
        training = modelTrainingRepo.save(training);

        // 更新模型状态
        MLModelEntity model = getModel(training.modelId);
        if (model.isTraining()) {
            model.modelStatus = MLModelEntity.ModelStatus.DRAFT;
            mlModelRepo.save(model);
        }

        auditLogService.logUpdate("model_training", training.id, training.trainingJobName,
            cancelledBy, cancelledBy, null, Map.of("action", "cancel"));

        logger.info("Cancelled training job: {}", trainingId);
        return training;
    }

    /**
     * 获取训练任务
     */
    public ModelTrainingEntity getTrainingJob(String trainingId) {
        return modelTrainingRepo.findById(trainingId)
            .orElseThrow(() -> new IllegalArgumentException("Training job not found: " + trainingId));
    }

    /**
     * 获取模型的训练历史
     */
    public List<ModelTrainingEntity> getTrainingHistory(String modelId) {
        return modelTrainingRepo.findByModelIdOrderByCreatedAtDesc(modelId);
    }

    // ==================== 模型部署 ====================

    /**
     * 部署模型
     */
    public MLModelEntity deployModel(String modelId, Map<String, Object> deploymentConfig, String deployedBy) {
        MLModelEntity model = getModel(modelId);

        if (model.modelStatus != MLModelEntity.ModelStatus.EVALUATING &&
            model.modelStatus != MLModelEntity.ModelStatus.STAGING) {
            throw new IllegalStateException("Model must be in EVALUATING or STAGING status to deploy. Current: " + model.modelStatus);
        }

        if (model.modelArtifactPath == null) {
            throw new IllegalStateException("Model has no artifact. Train the model first.");
        }

        model.deploy();

        if (deploymentConfig != null) {
            try {
                model.deploymentConfig = objectMapper.writeValueAsString(deploymentConfig);

                if (deploymentConfig.containsKey("servingEndpoint")) {
                    model.servingEndpoint = (String) deploymentConfig.get("servingEndpoint");
                }
                if (deploymentConfig.containsKey("canaryDeployment")) {
                    model.canaryDeployment = (Boolean) deploymentConfig.get("canaryDeployment");
                }
            } catch (Exception e) {
                logger.error("Failed to serialize deployment config", e);
            }
        }

        model = mlModelRepo.save(model);

        auditLogService.logUpdate("ml_model", model.id, model.modelName, deployedBy, deployedBy, null,
            Map.of("action", "deploy", "version", model.version));

        logger.info("Deployed ML model: {} version: {}", modelId, model.version);
        return model;
    }

    /**
     * 配置A/B测试
     */
    public MLModelEntity configureAbTest(String modelId, String baselineModelId, int trafficSplit,
                                          Map<String, Object> abTestConfig, String configuredBy) {
        MLModelEntity model = getModel(modelId);

        if (!model.isDeployed()) {
            throw new IllegalStateException("Model must be deployed to configure A/B test");
        }

        model.isAbTest = true;
        model.baselineModelId = baselineModelId;
        model.trafficSplit = trafficSplit;

        if (abTestConfig != null) {
            try {
                model.abTestConfig = objectMapper.writeValueAsString(abTestConfig);
            } catch (Exception e) {
                logger.error("Failed to serialize A/B test config", e);
            }
        }

        model = mlModelRepo.save(model);

        auditLogService.logUpdate("ml_model", model.id, model.modelName, configuredBy, configuredBy, null,
            Map.of("action", "configureAbTest", "baselineModelId", baselineModelId, "trafficSplit", trafficSplit));

        logger.info("Configured A/B test for model: {} with baseline: {} split: {}%",
            modelId, baselineModelId, trafficSplit);
        return model;
    }

    /**
     * 停止A/B测试
     */
    public MLModelEntity stopAbTest(String modelId, boolean keepCurrentModel, String stoppedBy) {
        MLModelEntity model = getModel(modelId);

        model.isAbTest = false;
        model.baselineModelId = null;
        model.trafficSplit = 0;
        model.abTestConfig = null;

        model = mlModelRepo.save(model);

        auditLogService.logUpdate("ml_model", model.id, model.modelName, stoppedBy, stoppedBy, null,
            Map.of("action", "stopAbTest", "keepCurrentModel", keepCurrentModel));

        logger.info("Stopped A/B test for model: {}", modelId);
        return model;
    }

    // ==================== 预测管理 ====================

    /**
     * 记录预测请求
     */
    public MLModelPredictionEntity recordPrediction(String modelId, String entityType, String entityId,
                                                     Map<String, Object> inputData, String requestId,
                                                     String clientId, String requestSource) {

        MLModelEntity model = getModel(modelId);

        if (!model.isDeployed()) {
            throw new IllegalStateException("Model is not deployed: " + model.modelStatus);
        }

        MLModelPredictionEntity prediction = new MLModelPredictionEntity();
        prediction.id = "pred_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        prediction.modelId = modelId;
        prediction.modelVersion = model.version;
        prediction.gameId = model.gameId;
        prediction.entityType = entityType;
        prediction.entityId = entityId;
        prediction.requestId = requestId;
        prediction.clientId = clientId;
        prediction.requestSource = requestSource;

        // A/B测试处理
        if (model.isAbTest()) {
            prediction.isAbTest = true;
            prediction.abTestGroup = determineAbTestGroup(model, entityId);
        }

        // 金丝雀部署处理
        if (model.isCanaryDeployment()) {
            prediction.isCanary = true;
        }

        try {
            if (inputData != null) {
                prediction.inputData = objectMapper.writeValueAsString(inputData);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize input data", e);
        }

        prediction = modelPredictionRepo.save(prediction);

        // 更新模型预测计数
        model.incrementPredictionCount();
        mlModelRepo.save(model);

        return prediction;
    }

    /**
     * 完成预测
     */
    public MLModelPredictionEntity completePrediction(String predictionId, Map<String, Object> output,
                                                       Double score, Integer latencyMs) {
        MLModelPredictionEntity prediction = modelPredictionRepo.findById(predictionId)
            .orElseThrow(() -> new IllegalArgumentException("Prediction not found: " + predictionId));

        try {
            prediction.complete(
                output != null ? objectMapper.writeValueAsString(output) : null,
                score
            );
        } catch (Exception e) {
            prediction.complete(null, score);
        }

        prediction.latencyMs = latencyMs;

        if (output != null) {
            if (output.containsKey("class")) {
                prediction.predictionClass = (String) output.get("class");
            }
            if (output.containsKey("probability")) {
                prediction.predictionProbability = ((Number) output.get("probability")).doubleValue();
            }
            if (output.containsKey("topPredictions")) {
                try {
                    prediction.topPredictions = objectMapper.writeValueAsString(output.get("topPredictions"));
                } catch (Exception e) {
                    logger.error("Failed to serialize top predictions", e);
                }
            }
            if (output.containsKey("featureImportance")) {
                try {
                    prediction.featureImportance = objectMapper.writeValueAsString(output.get("featureImportance"));
                } catch (Exception e) {
                    logger.error("Failed to serialize feature importance", e);
                }
            }
            if (output.containsKey("explanation")) {
                prediction.explanation = output.get("explanation").toString();
            }
        }

        prediction = modelPredictionRepo.save(prediction);
        return prediction;
    }

    /**
     * 预测失败
     */
    public MLModelPredictionEntity failPrediction(String predictionId, String errorCode, String errorMessage) {
        MLModelPredictionEntity prediction = modelPredictionRepo.findById(predictionId)
            .orElseThrow(() -> new IllegalArgumentException("Prediction not found: " + predictionId));

        prediction.fail(errorMessage);
        prediction.errorCode = errorCode;

        prediction = modelPredictionRepo.save(prediction);
        return prediction;
    }

    /**
     * 添加预测反馈
     */
    public MLModelPredictionEntity addPredictionFeedback(String predictionId,
                                                           MLModelPredictionEntity.FeedbackType feedbackType,
                                                           String actualValue, String feedbackBy) {
        MLModelPredictionEntity prediction = modelPredictionRepo.findById(predictionId)
            .orElseThrow(() -> new IllegalArgumentException("Prediction not found: " + predictionId));

        prediction.addFeedback(feedbackType, actualValue, feedbackBy);

        prediction = modelPredictionRepo.save(prediction);

        logger.info("Added feedback to prediction: {} - {}", predictionId, feedbackType);
        return prediction;
    }

    /**
     * 获取预测记录
     */
    public MLModelPredictionEntity getPrediction(String predictionId) {
        return modelPredictionRepo.findById(predictionId)
            .orElseThrow(() -> new IllegalArgumentException("Prediction not found: " + predictionId));
    }

    /**
     * 获取模型的预测历史
     */
    public List<MLModelPredictionEntity> getPredictionHistory(String modelId, int limit) {
        return modelPredictionRepo.findRecentByModelId(modelId).stream()
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 获取时间范围内的预测
     */
    public List<MLModelPredictionEntity> getPredictionsByTimeRange(String modelId,
                                                                     LocalDateTime startTime,
                                                                     LocalDateTime endTime) {
        return modelPredictionRepo.findByTimeRange(modelId, startTime, endTime);
    }

    // ==================== 模型监控 ====================

    /**
     * 获取模型统计信息
     */
    public Map<String, Object> getModelStatistics(String modelId) {
        MLModelEntity model = getModel(modelId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("modelId", model.id);
        stats.put("modelName", model.modelName);
        stats.put("modelType", model.modelType);
        stats.put("status", model.modelStatus);
        stats.put("version", model.version);
        stats.put("predictionCount", model.predictionCount);
        stats.put("lastPredictionAt", model.lastPredictionAt);
        stats.put("lastTrainedAt", model.lastTrainedAt);
        stats.put("lastDeployedAt", model.lastDeployedAt);

        // 训练统计
        long trainingCount = modelTrainingRepo.countByModelId(modelId);
        Double avgDuration = modelTrainingRepo.calculateAverageDuration(modelId);
        List<ModelTrainingEntity> recentTrainings = modelTrainingRepo.findRecentByModelId(modelId).stream()
            .limit(5)
            .collect(Collectors.toList());

        stats.put("trainingCount", trainingCount);
        stats.put("averageTrainingDurationMs", avgDuration);
        stats.put("recentTrainings", recentTrainings.stream()
            .map(t -> {
                Map<String, Object> trainingStats = new HashMap<>();
                trainingStats.put("id", t.id);
                trainingStats.put("status", t.trainingStatus);
                trainingStats.put("startedAt", t.startedAt);
                trainingStats.put("durationMs", t.durationMs != null ? t.durationMs : 0);
                return trainingStats;
            })
            .collect(Collectors.toList()));

        // 预测统计
        long predictionTotal = modelPredictionRepo.countByModelId(modelId);
        Double avgLatency = modelPredictionRepo.calculateAverageLatency(modelId);
        long cacheHits = modelPredictionRepo.countCacheHits(modelId);

        stats.put("predictionTotal", predictionTotal);
        stats.put("averageLatencyMs", avgLatency);
        stats.put("cacheHitRate", predictionTotal > 0 ? (double) cacheHits / predictionTotal : 0);

        // 反馈统计
        List<Object[]> feedbackStats = modelPredictionRepo.countByFeedbackType(modelId);
        Map<String, Long> feedbackDistribution = new HashMap<>();
        for (Object[] row : feedbackStats) {
            feedbackDistribution.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("feedbackDistribution", feedbackDistribution);

        // 准确率（基于反馈）
        Long correctCount = feedbackDistribution.getOrDefault("CORRECT", 0L);
        Long totalFeedback = feedbackDistribution.values().stream().mapToLong(Long::longValue).sum();
        stats.put("accuracyFromFeedback", totalFeedback > 0 ? (double) correctCount / totalFeedback : null);

        return stats;
    }

    /**
     * 获取全局ML统计
     */
    public Map<String, Object> getGlobalStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // 模型统计
        List<Object[]> modelStatusStats = mlModelRepo.countByStatus();
        Map<String, Long> modelByStatus = new HashMap<>();
        for (Object[] row : modelStatusStats) {
            modelByStatus.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("modelsByStatus", modelByStatus);

        List<Object[]> modelTypeStats = mlModelRepo.countByType();
        Map<String, Long> modelByType = new HashMap<>();
        for (Object[] row : modelTypeStats) {
            modelByType.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("modelsByType", modelByType);

        // 训练统计
        List<Object[]> trainingStatusStats = modelTrainingRepo.countByStatus();
        Map<String, Long> trainingByStatus = new HashMap<>();
        for (Object[] row : trainingStatusStats) {
            trainingByStatus.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("trainingJobsByStatus", trainingByStatus);

        Double totalGpuHours = modelTrainingRepo.calculateTotalGpuHours();
        Double totalCpuHours = modelTrainingRepo.calculateTotalCpuHours();
        stats.put("totalGpuHours", totalGpuHours);
        stats.put("totalCpuHours", totalCpuHours);

        // 部署模型数量
        long deployedCount = mlModelRepo.findAllDeployed().size();
        long abTestCount = mlModelRepo.findAllAbTestModels().size();
        stats.put("deployedModels", deployedCount);
        stats.put("abTestModels", abTestCount);

        return stats;
    }

    /**
     * 检测模型漂移
     */
    public Map<String, Object> detectModelDrift(String modelId, int recentWindow) {
        MLModelEntity model = getModel(modelId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentStart = now.minusHours(recentWindow);

        // 获取最近的预测反馈
        List<MLModelPredictionEntity> recentPredictions = modelPredictionRepo.findByTimeRange(modelId, recentStart, now)
            .stream()
            .filter(p -> p.hasFeedback())
            .collect(Collectors.toList());

        Map<String, Object> driftReport = new HashMap<>();
        driftReport.put("modelId", modelId);
        driftReport.put("windowHours", recentWindow);
        driftReport.put("totalPredictions", recentPredictions.size());

        if (recentPredictions.isEmpty()) {
            driftReport.put("driftDetected", false);
            driftReport.put("message", "No predictions with feedback in the window");
            return driftReport;
        }

        // 计算最近准确率
        long correctRecent = recentPredictions.stream()
            .filter(MLModelPredictionEntity::isCorrect)
            .count();
        double recentAccuracy = (double) correctRecent / recentPredictions.size();

        // 与基线比较
        double baselineAccuracy = model.accuracyMetric != null ? model.accuracyMetric : 0.5;
        double accuracyDrop = baselineAccuracy - recentAccuracy;

        driftReport.put("recentAccuracy", recentAccuracy);
        driftReport.put("baselineAccuracy", baselineAccuracy);
        driftReport.put("accuracyDrop", accuracyDrop);

        // 漂移检测阈值（默认5%）
        boolean driftDetected = accuracyDrop > 0.05;
        driftReport.put("driftDetected", driftDetected);

        if (driftDetected) {
            driftReport.put("severity", accuracyDrop > 0.15 ? "CRITICAL" : accuracyDrop > 0.10 ? "HIGH" : "MEDIUM");
            driftReport.put("recommendation", "Consider retraining the model");
        }

        return driftReport;
    }

    /**
     * 确定A/B测试组
     */
    private String determineAbTestGroup(MLModelEntity model, String entityId) {
        // 简单的哈希分桶
        int hash = entityId.hashCode() & 0x7FFFFFFF;
        int bucket = hash % 100;
        return bucket < model.trafficSplit ? "treatment" : "control";
    }

    // ==================== 定时任务 ====================

    /**
     * 清理过期预测记录
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点
    public void cleanupExpiredPredictions() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(90);  // 保留90天
        modelPredictionRepo.deleteByCreatedAtBefore(threshold);
        logger.info("Cleaned up predictions older than {}", threshold);
    }

    /**
     * 检测超时训练任务
     */
    @Scheduled(fixedRate = 300000)  // 每5分钟
    public void detectTimedOutTrainings() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);  // 24小时超时
        List<ModelTrainingEntity> timedOut = modelTrainingRepo.findTimedOutJobs(threshold);

        for (ModelTrainingEntity training : timedOut) {
            training.fail("Training job timed out after 24 hours");
            modelTrainingRepo.save(training);

            // 更新模型状态
            MLModelEntity model = getModel(training.modelId);
            if (model.isTraining()) {
                model.fail();
                mlModelRepo.save(model);
            }

            logger.warn("Training job timed out: {}", training.id);
        }
    }

    /**
     * 检测模型漂移并告警
     */
    @Scheduled(cron = "0 0 */6 * * ?")  // 每6小时
    public void scheduledDriftDetection() {
        List<MLModelEntity> deployedModels = mlModelRepo.findAllDeployed();

        for (MLModelEntity model : deployedModels) {
            try {
                Map<String, Object> driftReport = detectModelDrift(model.id, 6);

                if (Boolean.TRUE.equals(driftReport.get("driftDetected"))) {
                    logger.warn("Model drift detected for model: {} - severity: {}",
                        model.id, driftReport.get("severity"));

                    // 可以在这里触发告警通知
                    // notificationService.sendDriftAlert(model, driftReport);
                }
            } catch (Exception e) {
                logger.error("Failed to detect drift for model: {}", model.id, e);
            }
        }
    }
}
