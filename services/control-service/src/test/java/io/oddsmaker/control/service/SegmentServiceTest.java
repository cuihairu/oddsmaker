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
}
