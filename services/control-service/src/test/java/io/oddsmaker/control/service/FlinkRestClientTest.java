package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FlinkRestClient 单测：上传/列表匹配/launch/cancel/state 全分支（@Mock RestTemplate stub）。
 * Flink 1.19 形状：上传响应只回 filename，jarId 需 GET /jars 按文件名匹配。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Flink REST 客户端")
class FlinkRestClientTest {

    private static final String BASE = "http://flink:8081";

    @Mock
    private RestTemplate restTemplate;

    private FlinkRestClient client;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        client = new FlinkRestClient(restTemplate, BASE);
    }

    private Path fakeJar(String name) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "fake-jar-bytes", StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("uploadJar：上传成功后按文件名从 jar 列表解析 jarId")
    void uploadJarResolvesIdByFilename() throws Exception {
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("filename", "/tmp/flink-web-1/abc123_risk-job-0.1.0-all.jar")));
        when(restTemplate.getForEntity(BASE + "/jars", Map.class))
            .thenReturn(ResponseEntity.ok(Map.of("files", List.of(
                Map.of("id", "other_old.jar", "filename", "/tmp/flink-web-1/other_old.jar"),
                Map.of("id", "abc123_risk-job-0.1.0-all.jar", "filename", "/tmp/flink-web-1/abc123_risk-job-0.1.0-all.jar")
            ))));

        assertEquals("abc123_risk-job-0.1.0-all.jar", client.uploadJar(fakeJar("risk-job-0.1.0-all.jar")));
    }

    @Test
    @DisplayName("uploadJar：列表无同名 jar → 抛 IllegalStateException")
    void uploadJarNoMatch() throws Exception {
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("filename", "/tmp/x/ghost.jar")));
        when(restTemplate.getForEntity(BASE + "/jars", Map.class))
            .thenReturn(ResponseEntity.ok(Map.of("files", List.of(
                Map.of("id", "other.jar", "filename", "/tmp/x/other.jar")
            ))));

        assertThrows(IllegalStateException.class, () -> client.uploadJar(fakeJar("ghost.jar")));
    }

    @Test
    @DisplayName("uploadJar：响应缺 filename → 抛 IllegalStateException")
    void uploadJarMissingFilename() throws Exception {
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of()));

        Exception ex = assertThrows(IllegalStateException.class,
            () -> client.uploadJar(fakeJar("a.jar")));
        assertTrue(ex.getMessage().contains("missing filename"));
    }

    @Test
    @DisplayName("uploadJar：上传响应 body 为 null → 抛 IllegalStateException")
    void uploadJarNullUploadBody() throws Exception {
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.<Map>ok().build());

        Exception ex = assertThrows(IllegalStateException.class,
            () -> client.uploadJar(fakeJar("a.jar")));
        assertTrue(ex.getMessage().contains("missing filename"));
    }

    @Test
    @DisplayName("uploadJar：列表响应 body 为 null → 抛 IllegalStateException")
    void uploadJarNullListBody() throws Exception {
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("filename", "/tmp/x/a.jar")));
        when(restTemplate.getForEntity(BASE + "/jars", Map.class))
            .thenReturn(ResponseEntity.<Map>ok().build());

        assertThrows(IllegalStateException.class, () -> client.uploadJar(fakeJar("a.jar")));
    }

    @Test
    @DisplayName("uploadJar：filename 含非法字符不可解析 basename → 回退原始串比较后仍不命中")
    void uploadJarUnparsableFilenameFallback() throws Exception {
        // NUL 字节使 Paths.get 抛 InvalidPathException → basename 回退原始串比较
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("filename", "bad\0name.jar")));
        when(restTemplate.getForEntity(BASE + "/jars", Map.class))
            .thenReturn(ResponseEntity.ok(Map.of()));

        Exception ex = assertThrows(IllegalStateException.class,
            () -> client.uploadJar(fakeJar("a.jar")));
        assertTrue(ex.getMessage().contains("not found in Flink jar list"));
    }

    @Test
    @DisplayName("launch：run 响应回 jobid")
    void launchReturnsJobId() {
        when(restTemplate.postForEntity(eq(BASE + "/jars/{jarId}/run"), any(), eq(Map.class), eq("jar1")))
            .thenReturn(ResponseEntity.ok(Map.of("jobid", "flink-job-9")));

        assertEquals("flink-job-9", client.launch("jar1", "io.oddsmaker.jobs.risk.RiskJob", 1, "--job-id=f1"));
    }

    @Test
    @DisplayName("launch：响应缺 jobid → 抛 IllegalStateException")
    void launchMissingJobId() {
        when(restTemplate.postForEntity(eq(BASE + "/jars/{jarId}/run"), any(), eq(Map.class), eq("jar1")))
            .thenReturn(ResponseEntity.ok(Map.of("unrelated", true)));

        assertThrows(IllegalStateException.class,
            () -> client.launch("jar1", "io.oddsmaker.jobs.risk.RiskJob", 1, ""));
    }

    @Test
    @DisplayName("launch：run 响应 body 为 null → 抛 IllegalStateException")
    void launchNullBody() {
        when(restTemplate.postForEntity(eq(BASE + "/jars/{jarId}/run"), any(), eq(Map.class), eq("jar1")))
            .thenReturn(ResponseEntity.<Map>ok().build());

        Exception ex = assertThrows(IllegalStateException.class,
            () -> client.launch("jar1", "io.oddsmaker.jobs.risk.RiskJob", 1, ""));
        assertTrue(ex.getMessage().contains("missing jobid"));
    }

    @Test
    @DisplayName("cancel：PATCH /jobs/{id}?mode=cancel")
    void cancelUsesPatch() {
        client.cancel("flink-job-9");

        verify(restTemplate).exchange(eq(BASE + "/jobs/{jobId}?mode=cancel"),
            eq(HttpMethod.PATCH), eq(HttpEntity.EMPTY), eq(Void.class), eq("flink-job-9"));
    }

    @Test
    @DisplayName("jobState：返回 state 字段")
    void jobStateReturnsState() {
        when(restTemplate.getForEntity(BASE + "/jobs/{jobId}", Map.class, "j9"))
            .thenReturn(ResponseEntity.ok(Map.of("state", "RUNNING")));

        assertEquals("RUNNING", client.jobState("j9"));
    }

    @Test
    @DisplayName("jobState：404（集群查无此作业）返回 null")
    void jobStateNotFound() {
        when(restTemplate.getForEntity(BASE + "/jobs/{jobId}", Map.class, "gone"))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                new org.springframework.http.HttpHeaders(), new byte[0], StandardCharsets.UTF_8));

        assertNull(client.jobState("gone"));
    }

    @Test
    @DisplayName("jobState：响应 body 为 null 返回 null")
    void jobStateNullBody() {
        when(restTemplate.getForEntity(BASE + "/jobs/{jobId}", Map.class, "j2"))
            .thenReturn(ResponseEntity.<Map>ok().build());

        assertNull(client.jobState("j2"));
    }

    @Test
    @DisplayName("jobState：响应无 state 字段返回 null")
    void jobStateMissingField() {
        when(restTemplate.getForEntity(BASE + "/jobs/{jobId}", Map.class, "j1"))
            .thenReturn(ResponseEntity.ok(Map.of("other", 1)));

        assertNull(client.jobState("j1"));
    }

    @Test
    @DisplayName("mapState：Flink 1.19 全状态映射矩阵")
    void mapStateMatrix() {
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.DEPLOYING,
            FlinkRestClient.mapState("CREATED"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.RUNNING,
            FlinkRestClient.mapState("RUNNING"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.RUNNING,
            FlinkRestClient.mapState("RESTARTING"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.RUNNING,
            FlinkRestClient.mapState("RECONCILING"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.RUNNING,
            FlinkRestClient.mapState("FAILING"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.STOPPING,
            FlinkRestClient.mapState("CANCELLING"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.STOPPED,
            FlinkRestClient.mapState("FINISHED"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.STOPPED,
            FlinkRestClient.mapState("CANCELED"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.STOPPED,
            FlinkRestClient.mapState("SUSPENDED"));
        assertEquals(io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.FAILED,
            FlinkRestClient.mapState("FAILED"));
        assertNull(FlinkRestClient.mapState("SOMETHING_NEW"));
        assertNull(FlinkRestClient.mapState(null));
    }

    @Test
    @DisplayName("uploadJar：列表混入非 Map 元素/缺 filename/命中但缺 id——最终命中有效条目")
    void uploadJarMalformedListEntries() throws Exception {
        when(restTemplate.exchange(eq(BASE + "/jars"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("filename", "/tmp/x/a.jar")));
        when(restTemplate.getForEntity(BASE + "/jars", Map.class))
            .thenReturn(ResponseEntity.ok(Map.of("files", java.util.Arrays.asList(
                "str-item",                                   // 64 行 instanceof false 侧
                Map.of("id", "no-filename.jar"),              // 66 行 fname null 侧
                Map.of("filename", "/tmp/x/a.jar"),           // 68 行命中但无 id 侧
                Map.of("id", "hit_id", "filename", "/tmp/x/a.jar")
            ))));

        assertEquals("hit_id", client.uploadJar(fakeJar("a.jar")));
    }

}
