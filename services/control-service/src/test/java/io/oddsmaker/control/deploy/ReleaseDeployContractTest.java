package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEPLOY-001 发布契约钉测试（2026-10-19 审计整改）。
 * 审计发现 release.yaml 发布只更 control（gateway 清单存在却从不 apply/滚动），
 * 健康检查 curl https://control.oddsmaker.local（ubuntu-latest runner 解析不了 .local，
 * 首次发版必挂）。同轮还发现四份被 apply 的清单内嵌 CHANGE_ME 占位 Secret——
 * 发布即用占位值覆盖产线真实凭据。钉住：双服务同批滚动、集群内健康检查、
 * 运行版本记录断言、占位 Secret 与发布流水线隔离。
 */
class ReleaseDeployContractTest {

    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    private static final String WORKFLOW = ".github/workflows/release.yaml";

    private static final List<String> SECRET_MANIFESTS = List.of(
            "deploy/k8s/postgres-secrets.yaml",
            "deploy/k8s/redis-secrets.yaml",
            "deploy/k8s/control-service-secrets.yaml",
            "deploy/k8s/gateway-service-secrets.yaml");

    private static String read(String relative) throws IOException {
        return Files.readString(REPO.resolve(relative));
    }

    @Test
    void releaseRollsOutGatewayTogetherWithControl() throws IOException {
        String wf = read(WORKFLOW);
        assertTrue(wf.contains("kubectl apply -f deploy/k8s/gateway-service.yaml"),
                "发布必须同批 apply gateway 清单（原流程只更 control，两服务版本漂移）");
        assertTrue(wf.contains(
                        "kubectl set image deployment/oddsmaker-control control-service=${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${VERSION}"),
                "control 应滚动到本次发布的不可变版本 tag");
        assertTrue(wf.contains(
                        "kubectl set image deployment/oddsmaker-gateway gateway-service=${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${VERSION}"),
                "gateway 应滚动到同一不可变版本 tag（单镜像双服务，SERVICE 区分）");
        assertTrue(wf.contains("kubectl rollout status deployment/oddsmaker-control"),
                "control 滚动需等 rollout status");
        assertTrue(wf.contains("kubectl rollout status deployment/oddsmaker-gateway"),
                "gateway 滚动需等 rollout status");
    }

    @Test
    void healthCheckRunsInClusterNotDotLocal() throws IOException {
        String wf = read(WORKFLOW);
        assertFalse(wf.contains(".local"),
                "release 工作流不得用 .local 域名（GH-hosted runner 解析不了，健康检查必挂；"
                        + "也不得为探活把管理端点暴露到公网）");
        // control 在 k8s 清单里经 MANAGEMENT_SERVER_PORT 把 actuator 迁到 8086 管理口
        // （镜像自带 /app/healthcheck.sh 对 control 只查 8085 app 口，k8s 下必 404，不能复用）
        assertTrue(wf.contains("exec deploy/oddsmaker-control -- wget"),
                "control 健康检查应 exec 进 pod 探本机管理口");
        assertTrue(wf.contains("http://localhost:8086/actuator/health"),
                "control 应探 MANAGEMENT_SERVER_PORT=8086 的 actuator/health");
        assertTrue(wf.contains("exec deploy/oddsmaker-gateway -- wget"),
                "gateway 健康检查应 exec 进 pod 探本机端口");
        assertTrue(wf.contains("http://localhost:8080/actuator/health"),
                "gateway 应探 8080 的 actuator/health");
    }

    @Test
    void releaseRecordsActualRunningVersionsForBothServices() throws IOException {
        String wf = read(WORKFLOW);
        assertTrue(wf.contains("grep -qx \"oddsmaker-control="),
                "verify 步应断言 control 实际运行镜像 = 本次发布版本");
        assertTrue(wf.contains("grep -qx \"oddsmaker-gateway="),
                "verify 步应断言 gateway 实际运行镜像 = 本次发布版本");
    }

    @Test
    void workflowYamlIsWellFormed() throws IOException {
        assertYamlWellFormed(REPO.resolve(WORKFLOW), read(WORKFLOW));
    }

    /**
     * 发布流水线 apply 的清单不得携带 Secret：manifest 内是 CHANGE_ME 占位值，
     * 每次 apply 都会用占位值覆盖产线真实凭据。占位 Secret 拆到 *-secrets.yaml
     * （运维首次部署填真值后手工 apply 一次），并要求这些文件存在、可解析。
     */
    @Test
    void appliedManifestsCarryNoPlaceholderSecrets() throws IOException {
        String wf = read(WORKFLOW);
        List<String> applied = new ArrayList<>();
        Matcher m = Pattern.compile("kubectl apply -f (\\S+)").matcher(wf);
        while (m.find()) {
            applied.add(m.group(1));
        }
        assertTrue(applied.size() >= 5, "发布至少 apply namespace/postgres/redis/control/gateway 五份清单，实得: " + applied);
        for (String manifest : applied) {
            Path path = REPO.resolve(manifest);
            assertTrue(Files.exists(path), "release 引用的清单不存在: " + manifest);
            String content = Files.readString(path);
            assertFalse(content.contains("kind: Secret"),
                    "发布 apply 的清单不得内嵌 Secret（占位值覆盖产线凭据）: " + manifest);
            assertFalse(content.contains("CHANGE_ME_IN_PRODUCTION"),
                    "发布 apply 的清单不得携带占位凭据: " + manifest);
            assertYamlWellFormed(path, content);
        }
        for (String secretManifest : SECRET_MANIFESTS) {
            Path path = REPO.resolve(secretManifest);
            assertTrue(Files.exists(path), "占位 Secret 独立文件缺失（运维首次部署入口）: " + secretManifest);
            String content = Files.readString(path);
            assertTrue(content.contains("kind: Secret"), secretManifest + " 应是 Secret 清单");
            assertYamlWellFormed(path, content);
        }
    }

    /** 语法门：多文档清单缩进错误即解析抛错；顺便保证至少有一个文档。 */
    private static void assertYamlWellFormed(Path path, String content) {
        int docs = 0;
        for (Object ignored : new Yaml().loadAll(content)) {
            docs++;
        }
        assertTrue(docs >= 1, path + " 应至少解析出一个 YAML 文档");
    }
}
