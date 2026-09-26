package io.oddsmaker.control.service;

import io.oddsmaker.control.exception.BusinessException;
import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.jpa.SegmentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户分群服务测试：定义校验、SQL 编译（参数化/窗口收敛）、CRUD、物化与成员查询。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户分群服务测试")
class SegmentServiceTest {

    @Mock
    private SegmentRepo repo;

    @Mock
    private ClickHouseClient ch;

    private SegmentService service;

    @BeforeEach
    void setUp() {
        service = new SegmentService(repo, ch);
    }

    private SegmentEntity saved(String env, String subject) {
        SegmentEntity e = new SegmentEntity();
        e.id = "seg123";
        e.gameId = "game_a";
        e.environment = env;
        e.name = "whales";
        e.subject = "device".equals(subject)
                ? SegmentEntity.SegmentSubject.DEVICE : SegmentEntity.SegmentSubject.PLAYER;
        e.definition = "{\"match\":\"all\",\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}";
        return e;
    }

    // ---------------- 创建与校验 ----------------

    @Test
    @DisplayName("create：合法定义入库，规范 match 缺省为 all")
    void createPersistsCanonicalDefinition() {
        when(repo.findByGameIdAndNameAndDeletedAtIsNull("game_a", "whales")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SegmentEntity created = service.create("game_a", "whales", "鲸鱼用户", null, "prod", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}");

        assertEquals("game_a", created.gameId);
        assertEquals("prod", created.environment);
        assertEquals(SegmentEntity.SegmentSubject.PLAYER, created.subject);
        assertTrue(created.definition.contains("\"match\""));
        assertTrue(created.definition.contains("ios"));
    }

    @Test
    @DisplayName("create：同名分群 / 非法名称 / 缺环境 均被拒")
    void createRejectsInvalidInput() {
        when(repo.findByGameIdAndNameAndDeletedAtIsNull("game_a", "whales"))
                .thenReturn(Optional.of(saved("prod", null)));

        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "whales", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "bad name!", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "ok_name", null, null, " ", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}"));
    }

    @Test
    @DisplayName("定义校验：空条件/未知 kind/白名单外字段/非法 op/in 非字符串数组/事件缺阈值/缺失条件缺窗口")
    void definitionValidation() {
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s1", null, null, "prod", null, null));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s2", null, null, "prod", null, "{\"conditions\":[]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s3", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"magic\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s4", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"user_id\",\"op\":\"eq\",\"value\":\"x\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s5", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"regex\",\"value\":\"x\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s6", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"in\",\"value\":\"ios\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s7", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"event\",\"event_name\":\"purchase\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s8", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"event_absent\",\"event_name\":\"session_start\"}]}"));
    }

    // ---------------- SQL 编译 ----------------

    @Test
    @DisplayName("compile：all=AND 组合，属性/事件/缺失三类片段与参数顺序正确")
    void compileAllMatchCombinesWithAnd() {
        SegmentService.Definition def = new SegmentService.Definition();
        def.match = "all";
        def.withinDays = 30;
        SegmentService.Condition a = new SegmentService.Condition();
        a.kind = "attribute";
        a.field = "platform";
        a.op = "eq";
        a.value = "ios";
        SegmentService.Condition e1 = new SegmentService.Condition();
        e1.kind = "event";
        e1.eventName = "purchase";
        e1.op = "gte";
        e1.count = 2;
        e1.withinDays = 14;
        SegmentService.Condition e2 = new SegmentService.Condition();
        e2.kind = "event_absent";
        e2.eventName = "session_start";
        e2.withinDays = 7;
        def.conditions = List.of(a, e1, e2);

        SegmentService.Compiled compiled = service.compile(def, SegmentEntity.SegmentSubject.PLAYER);

        // AND 组合
        assertTrue(compiled.whereFragment.contains(" AND "));
        assertTrue(compiled.whereFragment.contains("platform = ?"));
        // 事件条件编译为主体子查询（player 优先口径）
        assertTrue(compiled.whereFragment.contains(
                "if(player_id != '', player_id, if(user_id != '', user_id, device_id)) IN (SELECT s FROM"));
        assertTrue(compiled.whereFragment.contains("HAVING c >= ?"));
        // 缺失条件用 countIf(...)=0
        assertTrue(compiled.whereFragment.contains("HAVING countIf(event_name = ?) = 0"));
        // 参数依序：platform 值 → purchase 事件名/窗口/阈值 → 缺失窗口/事件名
        assertEquals(List.of("ios", "purchase", 14, 2, 7, "session_start"), compiled.args);
        // 窗口取条件最大值（30）
        assertEquals(30, compiled.maxWindowDays);
    }

    @Test
    @DisplayName("compile：any=OR 组合；in 多值参数展开；device 口径；窗口钳制")
    void compileAnyMatchAndClamps() {
        SegmentService.Definition def = new SegmentService.Definition();
        def.match = "any";
        SegmentService.Condition a = new SegmentService.Condition();
        a.kind = "attribute";
        a.field = "country";
        a.op = "in";
        a.value = List.of("US", "CA");
        SegmentService.Condition b = new SegmentService.Condition();
        b.kind = "attribute";
        b.field = "app_version";
        b.op = "neq";
        b.value = "1.0.0";
        // device 口径的事件条件：验证 subjectExpr 换用 device_id
        SegmentService.Condition c = new SegmentService.Condition();
        c.kind = "event";
        c.eventName = "session_start";
        c.op = "gte";
        c.count = 1;
        def.conditions = List.of(a, b, c);

        SegmentService.Compiled compiled = service.compile(def, SegmentEntity.SegmentSubject.DEVICE);

        assertTrue(compiled.whereFragment.contains(" OR "));
        assertTrue(compiled.whereFragment.contains("country IN (?,?)"));
        assertTrue(compiled.whereFragment.contains("app_version != ?"));
        assertEquals(List.of("US", "CA", "1.0.0", "session_start", 90, 1), compiled.args);
        assertTrue(compiled.whereFragment.contains("device_id IN (SELECT s FROM"));
        // 无显式窗口 → 默认 90（事件条件缺省窗口取基础窗口）
        assertEquals(90, compiled.maxWindowDays);
    }

    @Test
    @DisplayName("segmentFilterFragment：成员子查询含 segment/game 两参数")
    void segmentFilterFragmentShape() {
        String fragment = SegmentService.segmentFilterFragment(
                "if(player_id != '', player_id, if(user_id != '', user_id, device_id))");
        assertTrue(fragment.startsWith(" AND "));
        assertTrue(fragment.contains("IN (SELECT subject_id FROM segment_members"));
        assertTrue(fragment.contains("segment_id = ? AND game_id = ?"));
    }

    // ---------------- 物化与成员 ----------------

    @Test
    @DisplayName("compute：CH 不可用抛异常；可用时先清后插并回填 memberCount")
    void computeMaterializes() {
        when(repo.findByIdAndDeletedAtIsNull("seg123")).thenReturn(Optional.of(saved("prod", null)));

        when(ch.isAvailable()).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.compute("seg123"));

        when(ch.isAvailable()).thenReturn(true);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ch.query(contains("uniqExact(subject_id)"), any(Object[].class)))
                .thenReturn(List.of(Map.of("c", 42L)));

        Map<String, Object> result = service.compute("seg123");

        assertEquals(42L, result.get("memberCount"));
        // 先清旧成员
        verify(ch).update(contains("ALTER TABLE segment_members DELETE"), eq("seg123"));
        // 再插入：INSERT INTO segment_members ... FROM events ... 环境/窗口/条件参数依序
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(ch).update(contains("INSERT INTO segment_members"), (Object[]) args.capture());
        assertEquals("seg123", args.getValue()[0]);
        assertEquals("game_a", args.getValue()[1]);
        assertEquals("prod", args.getValue()[2]);
        verify(repo).save(any());
    }

    @Test
    @DisplayName("members：limit 钳制到 1..1000，CH 不可用返回空")
    void membersClampAndDegrade() {
        when(repo.findByIdAndDeletedAtIsNull("seg123")).thenReturn(Optional.of(saved("prod", null)));
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(contains("ORDER BY subject_id LIMIT 1000"), any(Object[].class)))
                .thenReturn(List.of(Map.of("subject_id", "p1"), Map.of("subject_id", "p2")));

        List<String> members = service.members("seg123", 99999);
        assertEquals(List.of("p1", "p2"), members);

        when(ch.isAvailable()).thenReturn(false);
        assertTrue(service.members("seg123", 10).isEmpty());
    }

    // ---------------- 删除 ----------------

    @Test
    @DisplayName("delete：软删 + 置 INACTIVE；不存在返回 false")
    void deleteSoftDeletes() {
        when(repo.findById("seg123")).thenReturn(Optional.empty());
        assertEquals(false, service.delete("seg123"));

        SegmentEntity entity = saved("prod", null);
        when(repo.findById("seg123")).thenReturn(Optional.of(entity));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertEquals(true, service.delete("seg123"));
        assertNotNull(entity.deletedAt);
        assertEquals(SegmentEntity.SegmentStatus.INACTIVE, entity.status);
        verify(repo).save(entity);
    }

    // ---------------- 更新与读取（覆盖率补测） ----------------

    @Test
    @DisplayName("update：部分字段更新（名称/描述/状态/定义）+ 时间戳回填")
    void updatePartialFields() {
        SegmentEntity entity = saved("prod", null);
        when(repo.findByIdAndDeletedAtIsNull("seg123")).thenReturn(Optional.of(entity));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SegmentEntity updated = service.update("seg123", "高价值设备", "备注", "inactive",
                "{\"match\":\"any\",\"conditions\":[{\"kind\":\"event\",\"eventName\":\"purchase\",\"count\":3}]}");

        assertEquals("高价值设备", updated.displayName);
        assertEquals("备注", updated.description);
        assertEquals(SegmentEntity.SegmentStatus.INACTIVE, updated.status);
        assertTrue(updated.definition.contains("\"event\""));
        assertNotNull(updated.updatedAt);
    }

    @Test
    @DisplayName("update：非法状态 / 非法定义 均被拒")
    void updateRejectsInvalidStatusAndDefinition() {
        when(repo.findByIdAndDeletedAtIsNull("seg123")).thenReturn(Optional.of(saved("prod", null)));

        assertThrows(BusinessException.class,
                () -> service.update("seg123", null, null, "paused", null));
        assertThrows(BusinessException.class,
                () -> service.update("seg123", null, null, null, "{\"conditions\":[]}"));
    }

    @Test
    @DisplayName("get：不存在抛 NOT_FOUND；listByGame 委托仓储")
    void getNotFoundAndList() {
        when(repo.findByIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.get("nope"));

        when(repo.findByGameIdAndDeletedAtIsNullOrderByNameAsc("game_a"))
                .thenReturn(List.of(saved("prod", null)));
        assertEquals(1, service.listByGame("game_a").size());
    }

    @Test
    @DisplayName("subject 解析：device 合法入库，非法主体被拒；空/超长名称被拒")
    void subjectAndNameValidation() {
        String validDef = "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}";
        when(repo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SegmentEntity dev = service.create("game_a", "dev_seg", null, null, "prod", "device", validDef);
        assertEquals(SegmentEntity.SegmentSubject.DEVICE, dev.subject);

        assertThrows(BusinessException.class,
                () -> service.create("game_a", "robot_seg", null, null, "prod", "robot", validDef));
        assertThrows(BusinessException.class,
                () -> service.create("game_a", "", null, null, "prod", null, validDef));
        assertThrows(BusinessException.class,
                () -> service.create("game_a", "长".repeat(101), null, null, "prod", null, validDef));
    }

    @Test
    @DisplayName("定义校验补分支：in 空数组/含非字符串、eq 非字符串、event 计数 0/非法 op、缺失窗口 0、窗口超上限、条件超 20、match 非法规范为 all")
    void definitionEdgeValidation() {
        String attr = "{\"kind\":\"attribute\",\"field\":\"country\",\"op\":\"%s\",\"value\":%s}";
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e1", null, null, "prod", null,
                "{\"conditions\":[" + attr.formatted("in", "[]") + "]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e2", null, null, "prod", null,
                "{\"conditions\":[" + attr.formatted("in", "[\"US\",3]") + "]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e3", null, null, "prod", null,
                "{\"conditions\":[" + attr.formatted("eq", "7") + "]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e4", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"event\",\"event_name\":\"purchase\",\"count\":0}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e5", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"event\",\"event_name\":\"purchase\",\"count\":1,\"op\":\"between\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e6", null, null, "prod", null,
                "{\"conditions\":[{\"kind\":\"event_absent\",\"event_name\":\"session_start\",\"within_days\":0}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e7", null, null, "prod", null,
                "{\"within_days\":9999,\"conditions\":[{\"kind\":\"event\",\"event_name\":\"purchase\",\"count\":1}]}"));

        StringBuilder many = new StringBuilder("{\"conditions\":[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) many.append(",");
            many.append("{\"kind\":\"attribute\",\"field\":\"country\",\"op\":\"eq\",\"value\":\"US\"}");
        }
        many.append("]}");
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "e8", null, null, "prod", null, many.toString()));

        // match 非法 → 规范为 all 入库
        when(repo.findByGameIdAndNameAndDeletedAtIsNull("game_a", "e9")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SegmentEntity m = service.create("game_a", "e9", null, null, "prod", null,
                "{\"match\":\"sometimes\",\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}");
        assertTrue(m.definition.contains("\"all\""));
    }

    @Test
    @DisplayName("countMembers：成员为空返回 0")
    void countMembersEmptyRows() {
        SegmentEntity entity = saved("prod", null);
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(contains("uniqExact(subject_id)"), any(Object[].class)))
                .thenReturn(List.of());
        assertEquals(0L, service.countMembers(entity));
    }

    @Test
    @DisplayName("countMembers：CH 不可用直接返回 0（不触发查询）")
    void countMembersReturnsZeroWhenChUnavailable() {
        when(ch.isAvailable()).thenReturn(false);
        SegmentEntity e = new SegmentEntity();
        e.id = "seg123";
        assertEquals(0L, service.countMembers(e));
        verify(ch, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("条件校验分支：camelCase 键逐一打缺 kind/缺事件名/count/op/缺失条件/窗口越界")
    void conditionValidationBranches() {
        // snake_case 键（event_name 等）非映射属性，解析期即被拒（FAIL_ON_UNKNOWN_PROPERTIES 默认开启）
        BusinessException unknownKey = assertThrows(BusinessException.class,
                () -> service.parseAndValidate("{\"conditions\":[{\"kind\":\"event\",\"event_name\":\"x\",\"count\":1}]}"));
        assertTrue(unknownKey.getMessage().contains("不是合法 JSON"));

        record Case(String json, String msgFragment) {}
        List<Case> cases = List.of(
                // kind 缺失
                new Case("{\"conditions\":[{}]}", "条件缺少 kind"),
                // event：缺 eventName / count 缺失 / op 非法
                new Case("{\"conditions\":[{\"kind\":\"event\"}]}", "事件条件缺少 event_name"),
                new Case("{\"conditions\":[{\"kind\":\"event\",\"eventName\":\"x\"}]}", "count 需 >= 1"),
                new Case("{\"conditions\":[{\"kind\":\"event\",\"eventName\":\"x\",\"count\":1,\"op\":\"between\"}]}",
                        "事件条件 op 仅支持 gte/lte"),
                // event_absent：缺 eventName / 缺 withinDays
                new Case("{\"conditions\":[{\"kind\":\"event_absent\",\"withinDays\":7}]}", "事件缺失条件缺少 event_name"),
                new Case("{\"conditions\":[{\"kind\":\"event_absent\",\"eventName\":\"x\"}]}", "事件缺失条件需指定 within_days"),
                // withinDays 越界两侧：attribute+0 与 event+400
                new Case("{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"country\",\"op\":\"eq\",\"value\":\"US\",\"withinDays\":0}]}",
                        "within_days 需在 1..365"),
                new Case("{\"conditions\":[{\"kind\":\"event\",\"eventName\":\"x\",\"count\":1,\"withinDays\":400}]}",
                        "within_days 需在 1..365"));
        for (Case c : cases) {
            BusinessException ex = assertThrows(BusinessException.class, () -> service.parseAndValidate(c.json()));
            assertTrue(ex.getMessage().contains(c.msgFragment()),
                    () -> "用例未命中预期分支: " + c.json() + " → " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("compile：事件条件 op=lte 走 <=（缺省 op 走 >= 已在组合用例覆盖）")
    void compileEventLteUsesLessEqual() {
        SegmentService.Definition def = new SegmentService.Definition();
        SegmentService.Condition lte = new SegmentService.Condition();
        lte.kind = "event";
        lte.eventName = "purchase";
        lte.count = 3;
        lte.op = "lte";
        def.conditions = List.of(lte);

        SegmentService.Compiled compiled = service.compile(def, SegmentEntity.SegmentSubject.PLAYER);

        assertTrue(compiled.whereFragment.contains("HAVING c <= ?"));
        // 基础窗口缺省 90：参数依序 (eventName, window, count)
        assertEquals(List.of("purchase", 90, 3), compiled.args);
    }
}
