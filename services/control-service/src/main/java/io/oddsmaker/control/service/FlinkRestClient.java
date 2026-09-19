package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.FlinkJobEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Flink REST API 客户端（面向 Flink 1.19 JobManager REST 口）。
 *
 * 与 WebhookService 共享 RestTemplate bean（连接 3s / 读取 5s——大 jar 跨 WAN 上传需调全局超时）。
 * cancel 是 PATCH /jobs/{id}?mode=cancel，JDK 默认 requestFactory 不支持 PATCH，
 * 依赖 httpclient5 后 RestTemplateBuilder 自动切换 HttpComponents factory。
 * 所有方法失败抛 RuntimeException（具体原因在消息里），由 FlinkJobService 落 FAILED。
 */
@Component
public class FlinkRestClient {

    private final RestTemplate restTemplate;
    private final String restUrl;

    public FlinkRestClient(RestTemplate restTemplate,
                           @Value("${oddsmaker.flink.rest.url:http://localhost:8081}") String restUrl) {
        this.restTemplate = restTemplate;
        this.restUrl = restUrl;
    }

    /**
     * 上传 jar 并解析 jarId。Flink 上传响应只回 filename（含服务端暂存路径），
     * jarId 需再查 GET /jars 按文件名（basename）匹配。
     */
    public String uploadJar(Path jarPath) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("jarfile", new FileSystemResource(jarPath.toFile()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map> uploaded = restTemplate.exchange(
            restUrl + "/jars", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        Object filename = uploaded.getBody() == null ? null : uploaded.getBody().get("filename");
        if (filename == null) {
            throw new IllegalStateException("Flink upload response missing filename");
        }
        String uploadedName = basename(filename.toString());

        ResponseEntity<Map> list = restTemplate.getForEntity(restUrl + "/jars", Map.class);
        Object files = list.getBody() == null ? null : list.getBody().get("files");
        if (files instanceof List<?> fileList) {
            for (Object entry : fileList) {
                if (entry instanceof Map<?, ?> jar) {
                    Object fname = jar.get("filename");
                    if (fname != null && basename(fname.toString()).equals(uploadedName)) {
                        Object id = jar.get("id");
                        if (id != null) {
                            return id.toString();
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Uploaded jar " + uploadedName + " not found in Flink jar list");
    }

    /**
     * 从已上传 jar 启动作业，返回 Flink jobid。
     * programArgs 为单个空格分隔字符串（Flink 1.19 仍支持，另 --key=value 由 RiskJob 自行解析）。
     */
    public String launch(String jarId, String entryClass, int parallelism, String programArgs) {
        Map<String, Object> body = Map.of(
            "entryClass", entryClass,
            "parallelism", parallelism,
            "programArgs", programArgs);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            restUrl + "/jars/{jarId}/run", body, Map.class, jarId);
        Object jobId = resp.getBody() == null ? null : resp.getBody().get("jobid");
        if (jobId == null) {
            throw new IllegalStateException("Flink run response missing jobid");
        }
        return jobId.toString();
    }

    /** 取消作业（PATCH ?mode=cancel，202 即受理；非 2xx 由 RestTemplate 抛异常）。 */
    public void cancel(String flinkJobId) {
        restTemplate.exchange(restUrl + "/jobs/{jobId}?mode=cancel",
            HttpMethod.PATCH, HttpEntity.EMPTY, Void.class, flinkJobId);
    }

    /**
     * 查询作业状态（state 字段）。作业已从集群消失（404）返回 null。
     * 可能的状态：CREATED/RUNNING/FAILING/FAILED/CANCELLING/CANCELED/FINISHED/RESTARTING/RECONCILING/SUSPENDED。
     */
    public String jobState(String flinkJobId) {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                restUrl + "/jobs/{jobId}", Map.class, flinkJobId);
            Object state = resp.getBody() == null ? null : resp.getBody().get("state");
            return state != null ? state.toString() : null;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** Flink state → 平台 JobStatus；无法映射（null/未知）返回 null，调用方保持原状态。 */
    static FlinkJobEntity.JobStatus mapState(String flinkState) {
        if (flinkState == null) {
            return null;
        }
        return switch (flinkState) {
            case "CREATED" -> FlinkJobEntity.JobStatus.DEPLOYING;
            case "RUNNING", "RESTARTING", "RECONCILING", "FAILING" -> FlinkJobEntity.JobStatus.RUNNING;
            case "CANCELLING" -> FlinkJobEntity.JobStatus.STOPPING;
            case "FINISHED", "CANCELED", "SUSPENDED" -> FlinkJobEntity.JobStatus.STOPPED;
            case "FAILED" -> FlinkJobEntity.JobStatus.FAILED;
            default -> null;
        };
    }

    private static String basename(String pathLike) {
        try {
            return Paths.get(pathLike).getFileName().toString();
        } catch (RuntimeException e) {
            // 服务端 filename 形态不可解析（含非法字符等）时退回原始串比较
            return pathLike;
        }
    }
}
