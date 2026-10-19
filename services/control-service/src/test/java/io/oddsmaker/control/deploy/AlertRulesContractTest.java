package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-001 告警可验证性钉测试（2026-10-19 FIRE1019H 审计整改）。
 * 审计验收「重要故障具备可验证的告警」此前只做到文件面（18 条规则能被 promtool 解析），
 * 没有任何一处证明表达式在真实序列下真的会触发/恢复。配套 promtool 单元测试文件
 * `oddsmaker-alerts.test.yml` 逐条给出触发序列与静默序列；本钉测试保证两者不掉队：
 * 新增/改名告警必须同步补触发与静默用例，删用例即打红。
 */
class AlertRulesContractTest {

    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    private static final String RULES = "deploy/monitoring/alerts/oddsmaker-alerts.yml";

    private static final String RULE_TESTS = "deploy/monitoring/alerts/oddsmaker-alerts.test.yml";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> load(Path path, String what) throws IOException {
        assertTrue(Files.exists(path), what + " 缺失: " + path);
        Object doc = new Yaml().load(Files.readString(path));
        assertTrue(doc instanceof Map, what + " 应是 YAML 映射");
        return (List<Map<String, Object>>) ((Map<String, Object>) doc).get("tests");
    }

    /** 规则文件里的全部告警名（多 group 展平）。 */
    @SuppressWarnings("unchecked")
    private static List<String> alertNames() throws IOException {
        Path path = REPO.resolve(RULES);
        assertTrue(Files.exists(path), "告警规则文件缺失: " + path);
        Object doc = new Yaml().load(Files.readString(path));
        List<String> names = new ArrayList<>();
        for (Object group : (List<Object>) ((Map<String, Object>) doc).get("groups")) {
            for (Object rule : (List<Object>) ((Map<String, Object>) group).get("rules")) {
                names.add((String) ((Map<String, Object>) rule).get("alert"));
            }
        }
        return names;
    }

    /**
     * 每条告警都必须有触发用例（exp_alerts 非空）：规则文件能解析不代表会触发——
     * 指标名拼错、阈值单位错（秒/毫秒）、rate 窗口错都只在真实序列评估时暴露。
     */
    @Test
    void everyAlertHasAFiringCase() throws IOException {
        List<String> names = alertNames();
        assertFalse(names.isEmpty(), "规则文件应至少含一条告警");
        Map<String, Integer> firing = firingCases();
        for (String name : names) {
            assertTrue(firing.getOrDefault(name, 0) >= 1,
                    "告警 " + name + " 无触发用例（oddsmaker-alerts.test.yml 须有 exp_alerts 非空的断言）");
        }
    }

    /** 存活类告警（*Down / *Failed）必须有静默用例：健康序列下不许误报。 */
    @Test
    void availabilityAlertsHaveQuietCases() throws IOException {
        Map<String, Integer> quiet = quietCases();
        for (String name : alertNames()) {
            if (name.endsWith("Down") || name.endsWith("Failed")) {
                assertTrue(quiet.getOrDefault(name, 0) >= 1,
                        "存活告警 " + name + " 无静默用例（健康序列 exp_alerts 必须为空断言）");
            }
        }
    }

    /** 测试文件里引用的告警名不得超出规则文件（改名/删除后残留用例即打红）。 */
    @Test
    void testFileReferencesNoUnknownAlerts() throws IOException {
        List<String> names = alertNames();
        for (String referenced : referencedAlertNames()) {
            assertTrue(names.contains(referenced),
                    "测试文件引用了规则文件中不存在的告警: " + referenced + "（规则已改名/删除？）");
        }
    }

    /** 测试文件必须以 rule_files 指向当前规则文件（防止测的是别处规则的孤儿文件）。 */
    @Test
    void testFilePinsTheRulesFile() throws IOException {
        Path path = REPO.resolve(RULE_TESTS);
        assertTrue(Files.exists(path), "promtool 单元测试文件缺失: " + path + "（OBS-001 告警可验证性）");
        String content = Files.readString(path);
        assertTrue(content.contains(RULES.substring(RULES.lastIndexOf('/') + 1)),
                "测试文件 rule_files 应指向 " + RULES.substring(RULES.lastIndexOf('/') + 1));
        assertTrue(content.contains("evaluation_interval"), "promtool 测试须显式声明 evaluation_interval");
    }

    /**
     * 两处可跑监控配置的 rule_files 不得把 promtool 单测文件吞进规则加载：
     * 单测文件顶层是 tests/rule_files/evaluation_interval 字段，被当规则文件加载会
     * 解析失败、整栈拒绝起（2026-10-19 解阻塞复核实锤：加单测文件后 k8s 面
     * `check config` 已红、compose 面沿用同款通配同病）。展开须命中规则文件本体。
     */
    @Test
    void ruleFilesExcludeTheUnitTestFile() throws IOException {
        List<Path[]> configs = List.of(
                // k8s 面：配置与 alerts/ 同在 deploy/monitoring/
                new Path[]{REPO.resolve("deploy/monitoring/prometheus.yaml"), REPO.resolve("deploy/monitoring")},
                // compose 面：infra/prometheus.yml 的相对 rule_files 依赖 compose 把
                // deploy/monitoring/alerts 挂到容器配置同目录（挂载钉在下一测试）
                new Path[]{REPO.resolve("infra/prometheus.yml"), REPO.resolve("deploy/monitoring")});
        for (Path[] entry : configs) {
            List<String> matched = ruleFileMatches(entry[0], entry[1]);
            assertFalse(matched.isEmpty(), entry[0] + " rule_files 未命中任何规则文件");
            assertTrue(matched.stream().anyMatch(m -> m.endsWith("oddsmaker-alerts.yml")),
                    entry[0] + " rule_files 须加载 oddsmaker-alerts.yml");
            for (String file : matched) {
                assertFalse(file.endsWith(".test.yml"),
                        entry[0] + " 的 rule_files 把单测文件当规则加载: " + file);
            }
        }
    }

    /** compose 面接线钉：infra compose 须把规则目录挂到容器配置同目录（丢了即静默无告警）。 */
    @Test
    void infraComposeMountsTheRulesDirectory() throws IOException {
        Path compose = REPO.resolve("infra/docker-compose.yml");
        assertTrue(Files.exists(compose), "infra compose 缺失: " + compose);
        String content = Files.readString(compose);
        assertTrue(content.contains("../deploy/monitoring/alerts:/etc/prometheus/alerts"),
                "infra compose 应把 deploy/monitoring/alerts 挂到 /etc/prometheus/alerts"
                        + "（与 infra/prometheus.yml 的 rule_files 相对路径对齐）");
    }

    /**
     * histogram_quantile 类告警依赖 `_bucket` 序列；micrometer 默认只导出 summary
     * （无 bucket），真实 exposition 无此序列告警即永不触发——2026-10-20 对实跑
     * control 的真实 exposition 核对实证（promtool 合成序列手写 bucket 所以一直绿，
     * 该缺口测不出来）。钉住：规则引用的每个 _bucket 族，对应业务服务的
     * application.yaml 必须开 percentile histogram。
     */
    @Test
    void bucketMetricsHavePercentileHistogramsEnabled() throws IOException {
        Path controlYaml = REPO.resolve("services/control-service/src/main/resources/application.yaml");
        Path gatewayYaml = REPO.resolve("services/gateway-service/src/main/resources/application.yaml");
        // 规则里的 _bucket 族 → (直方图开关键名, 需要开该键的服务配置)
        Map<String, String> histogramKeys = Map.of(
                "http_server_requests_seconds_bucket", "http.server.requests",
                "hikaricp_connections_usage_seconds_bucket", "hikaricp.connections.usage");
        Map<String, List<Path>> requiredConfigs = Map.of(
                "http_server_requests_seconds_bucket", List.of(controlYaml, gatewayYaml),
                "hikaricp_connections_usage_seconds_bucket", List.of(controlYaml));
        Set<String> families = bucketFamilies();
        assertFalse(families.isEmpty(), "规则文件应至少引用一个 _bucket 序列（histogram_quantile 告警）");
        for (String family : families) {
            assertTrue(histogramKeys.containsKey(family),
                    family + " 不在直方图清单中：新增 _bucket 告警须同时补 application.yaml "
                            + "percentiles-histogram 开关与本测试映射");
            for (Path config : requiredConfigs.get(family)) {
                assertTrue(Files.exists(config), "业务服务配置缺失: " + config);
                assertTrue(Files.readString(config).contains(histogramKeys.get(family)),
                        config + " 未为 " + family + " 开 percentile histogram（真实 exposition "
                                + "无 bucket 序列，histogram_quantile 告警永不触发）");
            }
        }
    }

    /** 规则文件里被引用的 `_bucket` 序列名（排序去重）。 */
    private static Set<String> bucketFamilies() throws IOException {
        Set<String> families = new TreeSet<>();
        Matcher matcher = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*_bucket")
                .matcher(Files.readString(REPO.resolve(RULES)));
        while (matcher.find()) {
            families.add(matcher.group());
        }
        return families;
    }

    /**
     * 规则引用的每个 `up{job="..."}` 必须有对应抓取 job，否则存活告警是死序列
     * （2026-10-20 实锤：infra 面此前只有 control/gateway/otel 三 job，compose 生产档
     * 下 PostgresDown/RedisDown/KafkaDown 及配套指标告警永不触发）。k8s 面须全覆盖；
     * compose 面白名单豁免 flink——根 compose 的 7 个 Flink 作业是 local executor
     * 形态（DEPLOY-002），无 JobManager 指标端点可抓，集群化为独立后续工作。
     */
    @Test
    void alertScrapeJobsDefinedInMonitoringFaces() throws IOException {
        Matcher matcher = Pattern.compile("up\\{job=\"([a-z-]+)\"")
                .matcher(Files.readString(REPO.resolve(RULES)));
        Set<String> ruleJobs = new TreeSet<>();
        while (matcher.find()) {
            ruleJobs.add(matcher.group(1));
        }
        assertFalse(ruleJobs.isEmpty(), "规则应至少引用一个 up{job=...} 存活序列");
        String k8s = Files.readString(REPO.resolve("deploy/monitoring/prometheus.yaml"));
        String infra = Files.readString(REPO.resolve("infra/prometheus.yml"));
        Set<String> composeFaceExceptions = Set.of("flink");
        for (String job : ruleJobs) {
            assertTrue(k8s.contains("job_name: '" + job + "'"),
                    "k8s 面缺抓取 job: " + job + "（up{job=\"" + job + "\"} 是死序列）");
            if (composeFaceExceptions.contains(job)) {
                continue;
            }
            assertTrue(infra.contains("job_name: '" + job + "'"),
                    "compose 面缺抓取 job: " + job + "（该档下告警永不触发；确属该档不抓的组件"
                            + "须把理由写进 composeFaceExceptions）");
        }
    }

    /** 按配置的 rule_files 通配在实际（或挂载等价）目录下展开，返回命中的仓内相对路径。 */
    @SuppressWarnings("unchecked")
    private static List<String> ruleFileMatches(Path config, Path expansionRoot) throws IOException {
        assertTrue(Files.exists(config), "监控配置缺失: " + config);
        Object doc = new Yaml().load(Files.readString(config));
        assertTrue(doc instanceof Map, config + " 应是 YAML 映射");
        List<String> patterns = (List<String>) ((Map<String, Object>) doc).get("rule_files");
        assertFalse(patterns == null || patterns.isEmpty(), config + " 缺 rule_files（告警未接线）");
        List<String> matched = new ArrayList<>();
        for (String pattern : patterns) {
            Path patternPath = Paths.get(pattern);
            Path base = patternPath.getParent() == null
                    ? expansionRoot
                    : expansionRoot.resolve(patternPath.getParent());
            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + patternPath.getFileName());
            try (Stream<Path> files = Files.list(base)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> matcher.matches(f.getFileName()))
                        .forEach(f -> matched.add(
                                REPO.relativize(f.normalize()).toString().replace('\\', '/')));
            }
        }
        return matched;
    }

    // ===== 用例盘点 =====

    private Map<String, Integer> firingCases() throws IOException {
        return tally(true);
    }

    private Map<String, Integer> quietCases() throws IOException {
        return tally(false);
    }

    /** 按 alertname 统计触发/静默用例数（exp_alerts 非空=触发，空=静默）。 */
    private Map<String, Integer> tally(boolean firing) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> block : load(REPO.resolve(RULE_TESTS), "promtool 测试文件")) {
            for (Object checkObj : (List<Object>) block.getOrDefault("alert_rule_test", List.of())) {
                Map<String, Object> check = (Map<String, Object>) checkObj;
                String name = (String) check.get("alertname");
                List<Object> exp = (List<Object>) check.getOrDefault("exp_alerts", List.of());
                boolean isFiring = !exp.isEmpty();
                if (isFiring == firing) {
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private List<String> referencedAlertNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> block : load(REPO.resolve(RULE_TESTS), "promtool 测试文件")) {
            for (Object checkObj : (List<Object>) block.getOrDefault("alert_rule_test", List.of())) {
                names.add((String) ((Map<String, Object>) checkObj).get("alertname"));
            }
        }
        return names;
    }
}
