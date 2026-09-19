package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.PermissionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限种子完备性网：AccessGuard 行内鉴权引用的每个 id 必须在迁移 SQL 中落库并被 role_operator 绑定。
 * 背景：e2e 全程 x-admin-token 直通 ROLE_ADMIN，种子/绑定错误 e2e 查不出——非 admin 用户会恒 403。
 * 历史教训：23 个已转换控制器引用的 game:read 等只存在于 PermissionService.initializeDefaults()（仅测试调用），
 * 生产库不存在 → RBAC 死锁。此测试防同类回归：新增 guard id 忘记落库时立刻红。
 */
@DisplayName("权限种子完备性")
class PermissionSeedCoverageTest {

    /** V0.9.8 落库的 52 个 guard id（18 控制器死 @PreAuthorize 换血 + Part A 已引用未种子 8 条）。 */
    private static final List<String> V098_IDS = List.of(
        // Part A：已引用未种子
        "game:read", "game:update",
        "risk_rule:read", "risk_rule:create", "risk_rule:update", "risk_rule:delete",
        "user:read", "user:update",
        // Part B：18 控制器新词汇
        "ml:read", "ml:manage", "ml:train", "ml:deploy", "ml:use",
        "sdkkey:read", "sdkkey:manage",
        "sdkversion:read", "sdkversion:manage",
        "telemetry:read", "telemetry:manage",
        "integration:read", "integration:manage", "integration:trigger",
        "pipeline:read", "pipeline:manage", "pipeline:execute",
        "qualityrule:read", "qualityrule:manage",
        "ratelimit:read", "ratelimit:manage",
        "quota:read", "quota:manage",
        "risk:manage", "risk:review",
        "cohort:manage",
        "export:execute",
        "funnel:read", "funnel:manage",
        "audit:read", "audit:sensitive", "audit:manage",
        "security:read", "security:manage",
        "metrics:read", "metrics:infra",
        "health:read", "health:manage",
        "maintenance:read", "maintenance:manage",
        "system:read", "system:manage",
        "featureflag:read", "featureflag:manage");

    private String readMigration(String name) throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/" + name)) {
            if (in == null) {
                throw new IllegalStateException("迁移文件不在 classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("V0.9.8：52 个 guard id 全部落库且全部绑定 role_operator")
    void v098SeedsAllIdsPresentAndOperatorBound() throws IOException {
        String sql = readMigration("V0.9.8__rbac_guard_permission_seeds.sql");

        // 1) 每个 id 作为权限定义出现
        List<String> missingInsert = new ArrayList<>();
        for (String id : V098_IDS) {
            if (!sql.contains("('" + id + "',")) {
                missingInsert.add(id);
            }
        }
        assertTrue(missingInsert.isEmpty(), "permissions INSERT 缺失 id: " + missingInsert);

        // 2) 每个 id 都出现在含 role_operator 的绑定 INSERT..SELECT 的 IN 列表中
        String operatorBound = operatorBindingClause(sql);
        List<String> missingBind = new ArrayList<>();
        for (String id : V098_IDS) {
            if (!operatorBound.contains("'" + id + "'")) {
                missingBind.add(id);
            }
        }
        assertTrue(missingBind.isEmpty(), "role_operator 绑定缺失 id: " + missingBind);
    }

    /** 拼接所有绑定 role_operator 的 INSERT INTO role_permissions 段中 p.id IN (...) 的集合。 */
    private String operatorBindingClause(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String segment : sql.split("INSERT INTO role_permissions")) {
            // 绑定段必含 r.id 条件；只取绑到 role_operator 的段
            if (segment.contains("r.id IN ('role_operator'") || segment.contains("r.id = 'role_operator'")) {
                int where = segment.indexOf("WHERE");
                int end = segment.indexOf(";", where);
                sb.append(where >= 0 ? segment.substring(where, end > where ? end : segment.length()) : segment);
            }
        }
        assertTrue(sb.length() > 0, "未找到绑定 role_operator 的 INSERT..SELECT 段");
        return sb.toString();
    }

    @Test
    @DisplayName("HealthController 复用的 alert:read/alert:manage 在 V0.9.1 已种子")
    void alertIdsStillSeeded() throws IOException {
        String sql = readMigration("V0.9.1__metric_alerts.sql");
        assertTrue(sql.contains("('alert:read',"), "V0.9.1 缺 alert:read 种子");
        assertTrue(sql.contains("('alert:manage',"), "V0.9.1 缺 alert:manage 种子");
    }

    @Test
    @DisplayName("V0.9.9：8 个种子角色 type 修为 SYSTEM 且 enabled/system NULL 修复在位")
    void v099RolesDataFixPresent() throws IOException {
        String sql = readMigration("V0.9.9__rbac_roles_data_fix.sql");
        // type 枚举炸弹修复：8 个 role_* 行必须被 UPDATE 为 SYSTEM（RoleType 枚举合法值）
        List<String> roleIds = List.of("role_operator", "role_game_admin", "role_analyst",
            "role_marketing", "role_finance", "role_developer", "role_viewer", "role_qa");
        List<String> missing = new ArrayList<>();
        for (String id : roleIds) {
            if (!sql.contains("'" + id + "'")) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "V0.9.9 缺角色 id: " + missing);
        assertTrue(sql.contains("SET type = 'SYSTEM'"), "V0.9.9 缺 type 枚举修复");
        assertTrue(sql.contains("SET enabled = TRUE WHERE enabled IS NULL"), "V0.9.9 缺 enabled NULL 修复");
        assertTrue(sql.contains("SET \"system\" = TRUE WHERE \"system\" IS NULL"), "V0.9.9 缺 system NULL 修复");
    }

    @Test
    @DisplayName("PermissionAction 枚举覆盖 V0.9.8 种子全部 action 字面量（EAGER 水化炸弹防线）")
    void permissionActionEnumCoversSeedLiterals() throws IOException {
        String sql = readMigration("V0.9.8__rbac_guard_permission_seeds.sql");
        // INSERT 行尾形态 …,'resource_type','ACTION','SCOPE',TRUE,TRUE) —— 抽每一行的 action 列字面量。
        // roles.permissions 是 EAGER @ManyToMany：枚举缺任一落库值 → 加载绑定角色即水化炸（非 admin 鉴权全挂）
        Matcher m = Pattern.compile("'([a-z_]+)','([A-Z_]+)','(?:GAME|GLOBAL|ENVIRONMENT)',TRUE,TRUE\\)")
            .matcher(sql);
        Set<String> constants = Arrays.stream(PermissionEntity.PermissionAction.values())
            .map(Enum::name).collect(Collectors.toSet());
        int checked = 0;
        List<String> illegal = new ArrayList<>();
        while (m.find()) {
            checked++;
            if (!constants.contains(m.group(2))) {
                illegal.add(m.group(2));
            }
        }
        assertTrue(checked >= 52, "正则仅匹配 " + checked + " 行（种子 ≥52 行）——行尾格式漂移导致测试失灵");
        assertTrue(illegal.isEmpty(), "permissions.action 枚举外值（水化即炸）: " + illegal);
    }

    @Test
    @DisplayName("V0.9.10：枚举漂移数据修复（perm_* 回填/int_1 auth_type/audit status/api_keys 默认值）在位")
    void v0910EnumDriftFixPresent() throws IOException {
        String sql = readMigration("V0.9.10__data_enum_drift_fix.sql");
        // 病例 3：38 行 perm_* 的 type/action/scope 按旧列（operation/applicable_scope）推导回填
        assertTrue(sql.contains("UPDATE permissions SET"), "V0.9.10 缺 perm_* 回填");
        assertTrue(sql.contains("CASE operation"), "V0.9.10 缺 operation→action 推导");
        assertTrue(sql.contains("WHERE type IS NULL AND action IS NULL AND scope IS NULL"),
            "V0.9.10 缺三列 NULL 守卫");
        // 病例 4：int_1（SLACK）auth_type WEBHOOK 不在 AuthType 枚举 → NONE
        assertTrue(sql.contains("UPDATE integrations SET auth_type = 'NONE' WHERE id = 'int_1'"),
            "V0.9.10 缺 int_1 auth_type 修复");
        // 病例 5：audit_logs.status 存量 NULL 按 result 对齐回填
        assertTrue(sql.contains("UPDATE audit_logs SET status = CASE WHEN result = 'FAILURE'"),
            "V0.9.10 缺 audit status 回填");
        // 病例 6：api_keys.key_type DDL 默认 'PRODUCTION' 不在 ApiKeyType 枚举 → 防患改 'CLIENT'
        assertTrue(sql.contains("ALTER TABLE api_keys ALTER COLUMN key_type SET DEFAULT 'CLIENT'"),
            "V0.9.10 缺 api_keys 默认值修复");
    }
}
