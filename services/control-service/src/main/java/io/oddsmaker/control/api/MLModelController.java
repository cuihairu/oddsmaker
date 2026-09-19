package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.*;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.MLModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 机器学习模型API控制器
 * 提供ML模型管理的接口；鉴权走 AccessGuard 行内风格（ml:read / ml:manage / ml:train / ml:deploy / ml:use）。
 * 历史形态为 @PreAuthorize hasAuthority('VIEW_ML_MODELS') 静态式与 'MANAGE_ML_MODELS:'+gameId 拼接式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/ml-models")
public class MLModelController {

    @Autowired
    private MLModelService mlModelService;

    @Autowired
    private AccessGuard accessGuard;

    // ==================== 模型管理 ====================

    /**
     * 创建ML模型
     */
    @PostMapping
    public ResponseEntity<MLModelEntity> createModel(@RequestBody CreateModelRequest request) {
        accessGuard.requireGamePermission(request.gameId, "ml:manage");
        MLModelEntity model = mlModelService.createModel(
            request.gameId,
            request.modelName,
            request.modelType,
            request.algorithm,
            request.framework,
            request.description,
            request.createdBy
        );
        return ResponseEntity.ok(model);
    }

    /**
     * 获取模型详情
     */
    @GetMapping("/{modelId}")
    public ResponseEntity<MLModelEntity> getModel(@PathVariable String modelId) {
        accessGuard.requirePermission("ml:read");
        MLModelEntity model = mlModelService.getModel(modelId);
        return ResponseEntity.ok(model);
    }

    /**
     * 获取游戏的模型列表
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<MLModelEntity>> getGameModels(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "ml:read");
        List<MLModelEntity> models = mlModelService.getGameModels(gameId);
        return ResponseEntity.ok(models);
    }

    /**
     * 获取已部署的模型
     */
    @GetMapping("/deployed")
    public ResponseEntity<List<MLModelEntity>> getDeployedModels(
            @RequestParam(required = false) String gameId) {
        accessGuard.requirePermission("ml:read");
        List<MLModelEntity> models = mlModelService.getDeployedModels(gameId);
        return ResponseEntity.ok(models);
    }

    /**
     * 更新模型配置
     */
    @PutMapping("/{modelId}")
    public ResponseEntity<MLModelEntity> updateModel(
            @PathVariable String modelId,
            @RequestBody UpdateModelRequest request) {
        accessGuard.requirePermission("ml:manage");
        MLModelEntity model = mlModelService.updateModel(modelId, request.updates, request.updatedBy);
        return ResponseEntity.ok(model);
    }

    /**
     * 归档模型
     */
    @PostMapping("/{modelId}/archive")
    public ResponseEntity<MLModelEntity> archiveModel(
            @PathVariable String modelId,
            @RequestBody ArchiveRequest request) {
        accessGuard.requirePermission("ml:manage");
        MLModelEntity model = mlModelService.archiveModel(modelId, request.archivedBy);
        return ResponseEntity.ok(model);
    }

    /**
     * 删除模型
     */
    @DeleteMapping("/{modelId}")
    public ResponseEntity<Void> deleteModel(
            @PathVariable String modelId,
            @RequestBody DeleteRequest request) {
        accessGuard.requirePermission("ml:manage");
        mlModelService.deleteModel(modelId, request.deletedBy);
        return ResponseEntity.ok().build();
    }

    // ==================== 模型训练 ====================

    /**
     * 创建训练任务
     */
    @PostMapping("/{modelId}/training")
    public ResponseEntity<ModelTrainingEntity> createTrainingJob(
            @PathVariable String modelId,
            @RequestBody CreateTrainingRequest request) {
        accessGuard.requirePermission("ml:train");
        ModelTrainingEntity training = mlModelService.createTrainingJob(
            modelId,
            request.jobName,
            request.trainingConfig,
            request.datasetConfig,
            request.hyperparameterConfig,
            request.triggeredBy
        );
        return ResponseEntity.ok(training);
    }

    /**
     * 启动训练任务
     */
    @PostMapping("/training/{trainingId}/start")
    public ResponseEntity<ModelTrainingEntity> startTraining(@PathVariable String trainingId) {
        accessGuard.requirePermission("ml:train");
        ModelTrainingEntity training = mlModelService.startTraining(trainingId);
        return ResponseEntity.ok(training);
    }

    /**
     * 更新训练进度
     */
    @PutMapping("/training/{trainingId}/progress")
    public ResponseEntity<ModelTrainingEntity> updateTrainingProgress(
            @PathVariable String trainingId,
            @RequestBody UpdateTrainingProgressRequest request) {
        accessGuard.requirePermission("ml:train");
        ModelTrainingEntity training = mlModelService.updateTrainingProgress(
            trainingId,
            request.epoch,
            request.totalEpochs,
            request.loss,
            request.metrics
        );
        return ResponseEntity.ok(training);
    }

    /**
     * 完成训练任务
     */
    @PostMapping("/training/{trainingId}/complete")
    public ResponseEntity<ModelTrainingEntity> completeTraining(
            @PathVariable String trainingId,
            @RequestBody CompleteTrainingRequest request) {
        accessGuard.requirePermission("ml:train");
        ModelTrainingEntity training = mlModelService.completeTraining(
            trainingId,
            request.artifactPath,
            request.finalMetrics
        );
        return ResponseEntity.ok(training);
    }

    /**
     * 训练失败
     */
    @PostMapping("/training/{trainingId}/fail")
    public ResponseEntity<ModelTrainingEntity> failTraining(
            @PathVariable String trainingId,
            @RequestBody FailTrainingRequest request) {
        accessGuard.requirePermission("ml:train");
        ModelTrainingEntity training = mlModelService.failTraining(
            trainingId,
            request.errorMessage,
            request.stackTrace
        );
        return ResponseEntity.ok(training);
    }

    /**
     * 取消训练任务
     */
    @PostMapping("/training/{trainingId}/cancel")
    public ResponseEntity<ModelTrainingEntity> cancelTraining(
            @PathVariable String trainingId,
            @RequestBody CancelRequest request) {
        accessGuard.requirePermission("ml:train");
        ModelTrainingEntity training = mlModelService.cancelTraining(trainingId, request.cancelledBy);
        return ResponseEntity.ok(training);
    }

    /**
     * 获取训练任务详情
     */
    @GetMapping("/training/{trainingId}")
    public ResponseEntity<ModelTrainingEntity> getTrainingJob(@PathVariable String trainingId) {
        accessGuard.requirePermission("ml:read");
        ModelTrainingEntity training = mlModelService.getTrainingJob(trainingId);
        return ResponseEntity.ok(training);
    }

    /**
     * 获取模型的训练历史
     */
    @GetMapping("/{modelId}/training")
    public ResponseEntity<List<ModelTrainingEntity>> getTrainingHistory(@PathVariable String modelId) {
        accessGuard.requirePermission("ml:read");
        List<ModelTrainingEntity> history = mlModelService.getTrainingHistory(modelId);
        return ResponseEntity.ok(history);
    }

    // ==================== 模型部署 ====================

    /**
     * 部署模型
     */
    @PostMapping("/{modelId}/deploy")
    public ResponseEntity<MLModelEntity> deployModel(
            @PathVariable String modelId,
            @RequestBody DeployModelRequest request) {
        accessGuard.requirePermission("ml:deploy");
        MLModelEntity model = mlModelService.deployModel(modelId, request.deploymentConfig, request.deployedBy);
        return ResponseEntity.ok(model);
    }

    /**
     * 配置A/B测试
     */
    @PostMapping("/{modelId}/ab-test")
    public ResponseEntity<MLModelEntity> configureAbTest(
            @PathVariable String modelId,
            @RequestBody ConfigureAbTestRequest request) {
        accessGuard.requirePermission("ml:manage");
        MLModelEntity model = mlModelService.configureAbTest(
            modelId,
            request.baselineModelId,
            request.trafficSplit,
            request.abTestConfig,
            request.configuredBy
        );
        return ResponseEntity.ok(model);
    }

    /**
     * 停止A/B测试
     */
    @DeleteMapping("/{modelId}/ab-test")
    public ResponseEntity<MLModelEntity> stopAbTest(
            @PathVariable String modelId,
            @RequestBody StopAbTestRequest request) {
        accessGuard.requirePermission("ml:manage");
        MLModelEntity model = mlModelService.stopAbTest(
            modelId,
            request.keepCurrentModel,
            request.stoppedBy
        );
        return ResponseEntity.ok(model);
    }

    // ==================== 预测管理 ====================

    /**
     * 记录预测请求
     */
    @PostMapping("/predictions")
    public ResponseEntity<MLModelPredictionEntity> recordPrediction(@RequestBody RecordPredictionRequest request) {
        accessGuard.requirePermission("ml:use");
        MLModelPredictionEntity prediction = mlModelService.recordPrediction(
            request.modelId,
            request.entityType,
            request.entityId,
            request.inputData,
            request.requestId,
            request.clientId,
            request.requestSource
        );
        return ResponseEntity.ok(prediction);
    }

    /**
     * 完成预测
     */
    @PostMapping("/predictions/{predictionId}/complete")
    public ResponseEntity<MLModelPredictionEntity> completePrediction(
            @PathVariable String predictionId,
            @RequestBody CompletePredictionRequest request) {
        accessGuard.requirePermission("ml:use");
        MLModelPredictionEntity prediction = mlModelService.completePrediction(
            predictionId,
            request.output,
            request.score,
            request.latencyMs
        );
        return ResponseEntity.ok(prediction);
    }

    /**
     * 预测失败
     */
    @PostMapping("/predictions/{predictionId}/fail")
    public ResponseEntity<MLModelPredictionEntity> failPrediction(
            @PathVariable String predictionId,
            @RequestBody FailPredictionRequest request) {
        accessGuard.requirePermission("ml:use");
        MLModelPredictionEntity prediction = mlModelService.failPrediction(
            predictionId,
            request.errorCode,
            request.errorMessage
        );
        return ResponseEntity.ok(prediction);
    }

    /**
     * 添加预测反馈
     */
    @PostMapping("/predictions/{predictionId}/feedback")
    public ResponseEntity<MLModelPredictionEntity> addPredictionFeedback(
            @PathVariable String predictionId,
            @RequestBody AddFeedbackRequest request) {
        accessGuard.requirePermission("ml:use");
        MLModelPredictionEntity prediction = mlModelService.addPredictionFeedback(
            predictionId,
            request.feedbackType,
            request.actualValue,
            request.feedbackBy
        );
        return ResponseEntity.ok(prediction);
    }

    /**
     * 获取预测记录
     */
    @GetMapping("/predictions/{predictionId}")
    public ResponseEntity<MLModelPredictionEntity> getPrediction(@PathVariable String predictionId) {
        accessGuard.requirePermission("ml:read");
        MLModelPredictionEntity prediction = mlModelService.getPrediction(predictionId);
        return ResponseEntity.ok(prediction);
    }

    /**
     * 获取模型的预测历史
     */
    @GetMapping("/{modelId}/predictions")
    public ResponseEntity<List<MLModelPredictionEntity>> getPredictionHistory(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "100") int limit) {
        accessGuard.requirePermission("ml:read");
        List<MLModelPredictionEntity> predictions = mlModelService.getPredictionHistory(modelId, limit);
        return ResponseEntity.ok(predictions);
    }

    /**
     * 获取时间范围内的预测
     */
    @GetMapping("/{modelId}/predictions/range")
    public ResponseEntity<List<MLModelPredictionEntity>> getPredictionsByTimeRange(
            @PathVariable String modelId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        accessGuard.requirePermission("ml:read");
        List<MLModelPredictionEntity> predictions = mlModelService.getPredictionsByTimeRange(modelId, startTime, endTime);
        return ResponseEntity.ok(predictions);
    }

    // ==================== 模型监控 ====================

    /**
     * 获取模型统计信息
     */
    @GetMapping("/{modelId}/stats")
    public ResponseEntity<Map<String, Object>> getModelStatistics(@PathVariable String modelId) {
        accessGuard.requirePermission("ml:read");
        Map<String, Object> stats = mlModelService.getModelStatistics(modelId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取全局ML统计
     */
    @GetMapping("/stats/global")
    public ResponseEntity<Map<String, Object>> getGlobalStatistics() {
        accessGuard.requirePermission("ml:read");
        Map<String, Object> stats = mlModelService.getGlobalStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * 检测模型漂移
     */
    @GetMapping("/{modelId}/drift")
    public ResponseEntity<Map<String, Object>> detectModelDrift(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "6") int windowHours) {
        accessGuard.requirePermission("ml:read");
        Map<String, Object> driftReport = mlModelService.detectModelDrift(modelId, windowHours);
        return ResponseEntity.ok(driftReport);
    }

    // ==================== 请求/响应类 ====================

    /**
     * 创建模型请求
     */
    public static class CreateModelRequest {
        public String gameId;
        public String modelName;
        public MLModelEntity.ModelType modelType;
        public String algorithm;
        public String framework;
        public String description;
        public String createdBy;
    }

    /**
     * 更新模型请求
     */
    public static class UpdateModelRequest {
        public Map<String, Object> updates;
        public String updatedBy;
    }

    /**
     * 归档请求
     */
    public static class ArchiveRequest {
        public String archivedBy;
    }

    /**
     * 删除请求
     */
    public static class DeleteRequest {
        public String deletedBy;
    }

    /**
     * 创建训练任务请求
     */
    public static class CreateTrainingRequest {
        public String jobName;
        public Map<String, Object> trainingConfig;
        public Map<String, Object> datasetConfig;
        public Map<String, Object> hyperparameterConfig;
        public String triggeredBy;
    }

    /**
     * 更新训练进度请求
     */
    public static class UpdateTrainingProgressRequest {
        public int epoch;
        public int totalEpochs;
        public double loss;
        public Map<String, Object> metrics;
    }

    /**
     * 完成训练请求
     */
    public static class CompleteTrainingRequest {
        public String artifactPath;
        public Map<String, Object> finalMetrics;
    }

    /**
     * 训练失败请求
     */
    public static class FailTrainingRequest {
        public String errorMessage;
        public String stackTrace;
    }

    /**
     * 取消请求
     */
    public static class CancelRequest {
        public String cancelledBy;
    }

    /**
     * 部署模型请求
     */
    public static class DeployModelRequest {
        public Map<String, Object> deploymentConfig;
        public String deployedBy;
    }

    /**
     * 配置A/B测试请求
     */
    public static class ConfigureAbTestRequest {
        public String baselineModelId;
        public int trafficSplit;
        public Map<String, Object> abTestConfig;
        public String configuredBy;
    }

    /**
     * 停止A/B测试请求
     */
    public static class StopAbTestRequest {
        public boolean keepCurrentModel;
        public String stoppedBy;
    }

    /**
     * 记录预测请求
     */
    public static class RecordPredictionRequest {
        public String modelId;
        public String entityType;
        public String entityId;
        public Map<String, Object> inputData;
        public String requestId;
        public String clientId;
        public String requestSource;
    }

    /**
     * 完成预测请求
     */
    public static class CompletePredictionRequest {
        public Map<String, Object> output;
        public Double score;
        public Integer latencyMs;
    }

    /**
     * 预测失败请求
     */
    public static class FailPredictionRequest {
        public String errorCode;
        public String errorMessage;
    }

    /**
     * 添加反馈请求
     */
    public static class AddFeedbackRequest {
        public MLModelPredictionEntity.FeedbackType feedbackType;
        public String actualValue;
        public String feedbackBy;
    }
}
