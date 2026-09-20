package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体列映射 vs Flyway 迁移建列对齐测试。
 *
 * 背景（MLModelEntity.canaryDeployment 病例）：@Column 显式驼峰列名与迁移的蛇形列名漂移时，
 * H2 create-drop（test profile flyway disabled）按实体注解建列 → 测试全绿；生产 PG 按迁移建表，
 * 未加引号标识符折叠小写后列名不存在 → 该表任何读写 500。e2e 只覆盖基础链路兜不住。
 *
 * 断言方向：实体映射的每个列名都存在于该表的迁移 DDL 列集中（大小写不敏感，对齐 PG 折叠行为）。
 * 只防"实体引用不存在的列"（读写即炸），不断言反向（迁移多余的旧列是历史遗留，见 audit_logs）。
 */
@DisplayName("实体列映射与迁移建列对齐（防驼峰/拼错列名在生产 PG 上 500）")
class EntitiesSchemaAlignmentTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\\w.\"]*?(\\w+)\\s*\\((.*?)\\);",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_COLUMN = Pattern.compile(
        "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?[\\w.\"]*?(\\w+)\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_PREFIX = Pattern.compile(
        "^(PRIMARY|FOREIGN|CONSTRAINT|UNIQUE|CHECK|KEY|INDEX|EXCLUDE)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("全部 @Table 实体的映射列都落在迁移 DDL 列集内")
    void entityColumnsExistInMigrationDdl() throws IOException {
        Map<String, Set<String>> migrationColumns = loadMigrationColumns();
        Map<String, List<String>> violations = new HashMap<>();

        List<Class<?>> entities = scanEntityClasses();
        // 扫描机制防假绿：实体清单一贯 60+，扫描路径崩坏时（返回空集=断言空过）在此暴露
        assertTrue(entities.size() >= 60,
            "实体扫描异常（预期 60+ 实际 " + entities.size() + "），对齐断言将空转");

        for (Class<?> entity : entities) {
            Table table = entity.getAnnotation(Table.class);
            String tableName = table.name();
            Set<String> ddlColumns = migrationColumns.get(tableName.toLowerCase());
            if (ddlColumns == null) {
                // 表不在迁移中（如 Q 系/P 系后补实体遗漏迁移）单独暴露，不与列级混谈
                violations.computeIfAbsent(tableName, k -> new ArrayList<>())
                    .add("<表本身不在任何迁移 DDL 中>");
                continue;
            }
            for (String col : entityColumnNames(entity)) {
                if (!ddlColumns.contains(col.toLowerCase())) {
                    violations.computeIfAbsent(tableName, k -> new ArrayList<>())
                        .add(col);
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "实体映射列在迁移 DDL 中不存在（生产 PG 上读写即 500，H2 测试掩盖）: " + violations.entrySet().stream()
                .map(e -> e.getKey() + " -> " + e.getValue())
                .collect(Collectors.joining("; ")));
    }

    /** 扫描本包全部 classpath（main 实体 + 目录/jar 两种形态）中的实体类 */
    private static List<Class<?>> scanEntityClasses() throws IOException {
        String pkgPath = EntitiesSchemaAlignmentTest.class.getPackageName().replace('.', '/');
        List<Class<?>> out = new ArrayList<>();
        org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
            new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (org.springframework.core.io.Resource r : resolver.getResources("classpath*:" + pkgPath + "/*.class")) {
            String url = r.getURL().toString();
            int at = url.indexOf(pkgPath);
            if (at < 0 || !r.getFilename().endsWith(".class") || r.getFilename().contains("$")) {
                continue;
            }
            String fqn = url.substring(at, url.length() - ".class".length()).replace('/', '.');
            Class<?> c;
            try {
                c = Class.forName(fqn, false, EntitiesSchemaAlignmentTest.class.getClassLoader());
            } catch (ClassNotFoundException brokenRef) {
                throw new IllegalStateException("entity-class 扫描失败: " + fqn, brokenRef);
            }
            if (c.isAnnotationPresent(Table.class)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 反射收集实体列名：@Column name / @JoinColumn name / 无注解字段按 Spring 默认驼峰转蛇形 */
    private static List<String> entityColumnNames(Class<?> entity) {
        List<String> out = new ArrayList<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                Class<?> t = f.getType();
                if (List.class.isAssignableFrom(t) || Set.class.isAssignableFrom(t)
                    || Map.class.isAssignableFrom(t)) {
                    continue;  // 集合关系（@OneToMany/@ManyToMany）不占列
                }
                Column column = f.getAnnotation(Column.class);
                if (column != null && !column.name().isEmpty()) {
                    out.add(column.name());
                    continue;
                }
                JoinColumn join = f.getAnnotation(JoinColumn.class);
                if (join != null && !join.name().isEmpty()) {
                    out.add(join.name());
                    continue;
                }
                if (f.isAnnotationPresent(ManyToOne.class) || f.isAnnotationPresent(OneToOne.class)) {
                    // Spring Boot 默认策略：关联列 = 字段名蛇形 + _id
                    out.add(toSnake(f.getName()) + "_id");
                    continue;
                }
                out.add(toSnake(f.getName()));
            }
        }
        return out;
    }

    /** CamelCaseToUnderscoresNamingStrategy 同款（连续大写也逐字符断词，与 Spring Boot 默认一致） */
    private static String toSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 解析 classpath 全部 Flyway 迁移的列集（CREATE TABLE 列 + ALTER ADD COLUMN），表名/列名统一小写 */
    private static Map<String, Set<String>> loadMigrationColumns() throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        List<org.springframework.core.io.Resource> sqls = new ArrayList<>();
        org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
            new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (org.springframework.core.io.Resource r : resolver.getResources("classpath:db/migration/*.sql")) {
            sqls.add(r);
        }
        sqls.sort(java.util.Comparator.comparing(r -> r.getFilename()));
        for (org.springframework.core.io.Resource r : sqls) {
            String sql = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher ct = CREATE_TABLE.matcher(sql);
            while (ct.find()) {
                String table = ct.group(1).toLowerCase();
                Set<String> cols = out.computeIfAbsent(table, k -> new TreeSet<>());
                for (String line : ct.group(2).split("\n")) {
                    String trimmed = line.trim().replaceAll(",$", "");
                    Matcher col = Pattern.compile("^(\\w+)\\s+\\w+").matcher(trimmed);
                    if (col.find() && !CONSTRAINT_PREFIX.matcher(trimmed).find()) {
                        cols.add(col.group(1).toLowerCase());
                    }
                }
            }
            Matcher ac = ADD_COLUMN.matcher(sql);
            while (ac.find()) {
                out.computeIfAbsent(ac.group(1).toLowerCase(), k -> new TreeSet<>())
                    .add(ac.group(2).toLowerCase());
            }
        }
        return out;
    }
}
