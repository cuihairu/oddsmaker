package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEPLOY-001 发布契约钉测试（2026-10-19 审计整改）。
 * 审计发现 release.yaml 发布只更 control（gateway 清单存在却从不 apply/滚动），
 * 健康检查 curl https://control.oddsmaker.local（ubuntu-latest runner 解析不了 .local，
 * 首次发版必挂）。同轮还发现四份被 apply 的清单内嵌 CHANGE_ME 占位 Secret——
 * 发布即用占位值覆盖产线真实凭据。钉住：双服务同批滚动、集群内健康检查、
 * 运行版本记录断言、占位 Secret 与发布流水线隔离、失败自动回滚、部署后采集入口冒烟。
 */
class ReleaseDeployContractTest {

    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    private static final String WORKFLOW = ".github/workflows/release.yaml";

    private static final String ROLLBACK_SCRIPT = "deploy/k8s/rollback-release.sh";

    private static final String ROLLBACK_TRAP =
            "trap 'bash deploy/k8s/rollback-release.sh \"$prev_control\" \"$prev_gateway\"' ERR";

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

    /**
     * 审计验收项「回滚步骤经过验证」的机制面：滚动前必须把两服务当前镜像记下来，
     * 否则失败时无回滚目标。首次发布 deployment 尚不存在，查询失败须置空继续。
     */
    @Test
    void releaseCapturesPreviousImagesBeforeRollout() throws IOException {
        String wf = read(WORKFLOW);
        assertTrue(wf.contains("Record running images"),
                "滚动前须记录两服务当前运行镜像（自动回滚目标）");
        assertTrue(wf.contains("-o jsonpath='{.spec.template.spec.containers[?(@.name==\"control-service\")].image}'"),
                "prev_control 应按容器名精确取 control 当前镜像（容器顺序变化不得取错）");
        assertTrue(wf.contains("-o jsonpath='{.spec.template.spec.containers[?(@.name==\"gateway-service\")].image}'"),
                "prev_gateway 应按容器名精确取 gateway 当前镜像");
        assertTrue(wf.contains(">> \"$GITHUB_ENV\""),
                "prev 镜像应写入 GITHUB_ENV，供后续发布步的回滚 trap 使用");
        assertTrue(wf.contains("2>/dev/null || true"),
                "首次发布 deployment 不存在，prev 查询失败须置空继续而非中断发布");
    }

    /** Deploy/Verify/Smoke 三步都要挂 ERR 回滚：滚动失败、健康断言失败、采集入口冒烟失败一律回退。 */
    @Test
    void deployAndVerifyStepsTrapErrToRollback() throws IOException {
        String wf = read(WORKFLOW);
        int traps = wf.split(Pattern.quote(ROLLBACK_TRAP), -1).length - 1;
        assertEquals(3, traps,
                "Deploy、Verify、Smoke ingest endpoint 三步都应挂 ERR trap 自动回滚"
                        + "（健康检查/采集冒烟失败也必须回退，否则发回坏版本却把 prod 卡在坏状态），实挂: " + traps);
        assertTrue(wf.contains("set -euo pipefail"),
                "发布步须显式 set -euo pipefail，否则 trap ERR 不会随管道失败触发");
    }

    /**
     * 审计 DEPLOY-001 执行项「为部署后的验证增加真实事件采集冒烟测试」的机制面：
     * 健康检查只证明 pod 活着、版本断言只证明镜像正确，都不证明采集入口能按契约应答
     * （gateway 把 /v1/batch 全部 401 掉今天的发布照样绿）。发布须把 runner 经
     * kubectl port-forward 隧道进集群直击刚滚动的 gateway pod：无凭据 POST 必 401
     * missing_api_key（HmacFilter 在链上），伪造凭据 POST 必 401 invalid_api_key
     * （gateway→control 内部密钥查询链路通）。不用公网域名、不暴露管理口。
     */
    @Test
    void releaseSmokesIngestEndpointInCluster() throws IOException {
        String wf = read(WORKFLOW);
        int verify = wf.indexOf("Verify deployment");
        int smoke = wf.indexOf("Smoke ingest endpoint");
        assertTrue(verify >= 0 && smoke > verify,
                "采集冒烟步应存在且排在 Verify deployment 之后（先证明部署成立，再证明采集契约）");
        assertTrue(wf.contains("port-forward deploy/oddsmaker-gateway"),
                "冒烟须经 kubectl port-forward 隧道进集群，不得依赖公网域名/入口配置");
        assertTrue(wf.contains("localhost:18080/v1/batch"),
                "冒烟应直击 gateway pod 的 /v1/batch 采集入口");
        assertTrue(wf.contains("missing_api_key"),
                "无凭据探针须断言 401 missing_api_key（HmacFilter 在链上且生效）");
        assertTrue(wf.contains("invalid_api_key"),
                "伪造凭据探针须断言 401 invalid_api_key（gateway→control 密钥查询链路通）");
        assertTrue(wf.contains("curl"),
                "冒烟断言须用 curl 拿状态码+响应体（busybox wget 对 4xx 不吐 body，断不了错误码）");
    }

    /**
     * 正向冒烟（真实事件被接受进发布链路）需要一枚可写冒烟密钥：仓库不持密钥，
     * 必须 secret 门控——配置 SMOKE_API_KEY/SMOKE_API_SECRET/SMOKE_GAME_ID 才跑，
     * 未配置告警跳过（不得静默失败，也不得从库里捞生产密钥进流水线日志）。
     * 签名契约与 HmacFilter 一致：t=unix 秒，s=hex(hmac_sha256(secret, t+'.'+body))。
     */
    @Test
    void positiveIngestSmokeIsSecretGated() throws IOException {
        String wf = read(WORKFLOW);
        assertTrue(wf.contains("secrets.SMOKE_API_KEY"),
                "正向冒烟密钥须来自 secrets.SMOKE_API_KEY（用户在 Control 发放后配进 GitHub）");
        assertTrue(wf.contains("secrets.SMOKE_API_SECRET"),
                "正向冒烟签名密钥须来自 secrets.SMOKE_API_SECRET");
        assertTrue(wf.contains("vars.SMOKE_GAME_ID"),
                "正向冒烟游戏须来自 vars.SMOKE_GAME_ID（密钥按 game+env 作用域，事件须落同一 game）");
        assertTrue(wf.contains("::warning::"),
                "冒烟凭据未配置时须 ::warning:: 明示跳过，不得静默失败让人误以为正向冒烟跑过");
        assertTrue(wf.contains("openssl dgst -sha256 -hmac"),
                "正向冒烟须按 x-signature 契约签名（t.unix 秒 + hmac_sha256(secret, t+'.'+body)）");
        assertTrue(wf.contains("environment\":\"dev"),
                "冒烟事件须落 dev 环境（key 级 rejectUnknownEvents 对 dev 豁免，冒烟不必先发 Schema）");
    }

    /** 回滚脚本自身的契约钉：双服务对称、不用 -e（一处失败不得中止另一处）、空 prev 跳过。 */
    @Test
    void rollbackScriptPinsContract() throws Exception {
        Path script = REPO.resolve(ROLLBACK_SCRIPT);
        assertTrue(Files.exists(script), "回滚脚本缺失: " + ROLLBACK_SCRIPT);
        String sh = Files.readString(script);
        assertTrue(sh.contains("rollback_one oddsmaker-control control-service"),
                "control 应有对称回滚调用（deployment 名 + 容器名）");
        assertTrue(sh.contains("rollback_one oddsmaker-gateway gateway-service"),
                "gateway 应有对称回滚调用（原流程只更 control，回滚也不能只回 control）");
        assertTrue(sh.contains("kubectl rollout status"),
                "回滚后须等 rollout status，不能 set image 完就走（半回滚状态）");
        assertFalse(sh.contains("set -euo pipefail"),
                "回滚脚本刻意不用 -e：一个服务回滚失败不得中止另一个服务的回退");
        assertTrue(sh.contains("::warning::"),
                "无 prev 镜像（首次发布）时须跳过并告警，不得对空镜像 set image");
        Process p = new ProcessBuilder("/bin/bash", "-n", script.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "bash -n 语法检查超时");
        assertEquals(0, p.exitValue(), "bash -n 语法检查失败: " + new String(p.getInputStream().readAllBytes()));
    }

    @Test
    void rollbackScriptSkipsEmptyPrevImagesOnFirstRelease() throws Exception {
        Run r = runRollbackScript("", "");
        assertEquals(0, r.exitCode, "首次发布（无 prev 镜像）应整体成功退出: " + r.output);
        assertTrue(r.output.contains("::warning::"), "无 prev 镜像须跳过并告警: " + r.output);
        assertFalse(r.kubectlCalls.contains("set image"),
                "无回滚目标时不得执行 set image（空值会把容器镜像清空）: " + r.kubectlCalls);
    }

    @Test
    void rollbackScriptRollsBothServicesBackToPreviousImages() throws Exception {
        Run r = runRollbackScript("reg.example/oddsmaker:v0.1.0", "reg.example/oddsmaker:v0.1.0");
        assertEquals(0, r.exitCode, "正常回滚应成功退出: " + r.output);
        assertTrue(r.kubectlCalls.contains(
                        "set image deployment/oddsmaker-control control-service=reg.example/oddsmaker:v0.1.0"),
                "control 应 set image 回 prev 镜像: " + r.kubectlCalls);
        assertTrue(r.kubectlCalls.contains(
                        "set image deployment/oddsmaker-gateway gateway-service=reg.example/oddsmaker:v0.1.0"),
                "gateway 应 set image 回 prev 镜像: " + r.kubectlCalls);
        assertTrue(r.kubectlCalls.contains("rollout status deployment/oddsmaker-control"), r.kubectlCalls.toString());
        assertTrue(r.kubectlCalls.contains("rollout status deployment/oddsmaker-gateway"), r.kubectlCalls.toString());
    }

    @Test
    void rollbackScriptContinuesOtherServiceWhenOneRollbackFails() throws Exception {
        Run r = runRollbackScript("reg.example/oddsmaker:FAIL_ME", "reg.example/oddsmaker:v0.0.9");
        assertTrue(r.exitCode != 0, "有服务回滚失败须非零退出，让发布红灯: " + r.output);
        assertTrue(r.kubectlCalls.contains(
                        "set image deployment/oddsmaker-gateway gateway-service=reg.example/oddsmaker:v0.0.9"),
                "control 回滚失败不得中止 gateway 的回退: " + r.kubectlCalls);
        assertTrue(r.kubectlCalls.contains("rollout status deployment/oddsmaker-gateway"), r.kubectlCalls.toString());
        assertFalse(r.kubectlCalls.contains("rollout status deployment/oddsmaker-control"),
                "set image 已失败的服务不应再等 rollout status: " + r.kubectlCalls);
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

    // ===== rollback-release.sh 沙箱功能验证 =====

    private record Run(int exitCode, String output, String kubectlCalls) { }

    /**
     * 桩 kubectl：记录全部调用供断言；镜像串含 FAIL_ME 即失败，用于验证
     * 一个服务回滚失败不中止另一个（脚本刻意不用 -e 的语义）。
     */
    private static Path writeKubectlStub(Path binDir) throws IOException {
        Path stub = binDir.resolve("kubectl");
        Files.writeString(stub, """
                #!/bin/bash
                echo "CALL $*" >> "$KUBECTL_LOG"
                case "$*" in *FAIL_ME*) exit 43 ;; esac
                exit 0
                """);
        stub.toFile().setExecutable(true);
        return stub;
    }

    /** 沙箱执行回滚脚本：PATH 只含桩 bin（host 无 kubectl 也不受污染），调用记录进日志文件。 */
    private static Run runRollbackScript(String prevControl, String prevGateway) throws Exception {
        Path script = REPO.resolve(ROLLBACK_SCRIPT);
        assertTrue(Files.exists(script), "回滚脚本缺失: " + ROLLBACK_SCRIPT);
        Path binDir = Files.createTempDirectory("rollback-bin");
        writeKubectlStub(binDir);
        Path callLog = Files.createTempFile("kubectl-log", ".txt");
        Path out = Files.createTempFile("rollback-out", ".log");
        ProcessBuilder pb = new ProcessBuilder(List.of(
                "/bin/bash", script.toAbsolutePath().toString(), prevControl, prevGateway));
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", binDir.toAbsolutePath().toString());
        env.put("KUBECTL_LOG", callLog.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "rollback script timed out");
        return new Run(p.exitValue(), Files.readString(out), Files.readString(callLog));
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
