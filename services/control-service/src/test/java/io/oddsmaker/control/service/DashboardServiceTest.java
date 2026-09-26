package io.oddsmaker.control.service;

import io.oddsmaker.control.exception.BusinessException;
import io.oddsmaker.control.jpa.DashboardEntity;
import io.oddsmaker.control.jpa.DashboardRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义仪表盘服务测试：布局校验（类型/数据源/参数钳制）、规范化、CRUD、软删。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("自定义仪表盘服务测试")
class DashboardServiceTest {

    @Mock
    private DashboardRepo repo;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(repo);
    }

    private DashboardEntity saved() {
        DashboardEntity e = new DashboardEntity();
        e.id = "dash123";
        e.gameId = "game_a";
        e.name = "ops_daily";
        e.layout = "{\"widgets\":[{\"id\":\"w1\",\"type\":\"kpi\",\"source\":\"online-overview\",\"title\":\"在线\",\"span\":3}]}";
        return e;
    }

    private static final String VALID_LAYOUT =
            "{\"widgets\":[{\"id\":\"w1\",\"type\":\"kpi\",\"source\":\"online-overview\",\"title\":\"当前在线\"},"
                    + "{\"id\":\"w2\",\"type\":\"line\",\"source\":\"retention-trend\",\"span\":9,"
                    + "\"params\":{\"environment\":\"prod\",\"days\":30}}]}";

    // ---------------- 创建与校验 ----------------

    @Test
    @DisplayName("create：合法布局入库，规范化补 span/title")
    void createPersistsCanonicalLayout() {
        when(repo.findByGameIdAndNameAndDeletedAtIsNull("game_a", "ops_daily")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DashboardEntity created = service.create("game_a", "ops_daily", null, VALID_LAYOUT);

        assertEquals("game_a", created.gameId);
        assertEquals(DashboardEntity.DashboardStatus.ACTIVE, created.status);
        // span 缺省补 6（w1）、已提供的保留（w2=9）；title 缺省补 source（w2）
        assertTrue(created.layout.contains("\"span\":6"));
        assertTrue(created.layout.contains("\"span\":9"));
        assertTrue(created.layout.contains("\"title\":\"retention-trend\""));
        assertTrue(created.layout.contains("\"title\":\"当前在线\""));
    }

    @Test
    @DisplayName("create：同名 / 非法名称 / 非法布局 均被拒")
    void createRejectsInvalidInput() {
        when(repo.findByGameIdAndNameAndDeletedAtIsNull("game_a", "ops_daily"))
                .thenReturn(Optional.of(saved()));

        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "ops_daily", null, VALID_LAYOUT));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "bad name!", null, VALID_LAYOUT));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "ok_name", null, "not json"));
    }

    @Test
    @DisplayName("布局校验：空 widgets / 超上限 / 未知类型 / 白名单外数据源 / 标题过长")
    void layoutValidation() {
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s1", null, "{\"widgets\":[]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s2", null, null));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s3", null,
                "{\"widgets\":[{\"id\":\"w1\",\"type\":\"gauge\",\"source\":\"online-overview\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s4", null,
                "{\"widgets\":[{\"id\":\"w1\",\"type\":\"kpi\",\"source\":\"raw-sql\"}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s5", null,
                "{\"widgets\":[{\"id\":\"w1\",\"type\":\"kpi\",\"source\":\"online-overview\",\"title\":\""
                        + "x".repeat(101) + "\"}]}"));
    }

    @Test
    @DisplayName("params 校验：环境枚举 / 数值钳制 / 未知键拒绝")
    void paramsValidation() {
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s6", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"environment\":\"prod2\"}}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s7", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"minutes\":61}}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s8", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"days\":0}}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s9", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"evil\":\"1\"}}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "s10", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"minutes\":\"abc\"}}]}"));
        // 边界内合法
        when(repo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DashboardEntity ok = service.create("game_a", "s11", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"minutes\":60}}]}");
        assertTrue(ok.layout.contains("\"minutes\":60"));
    }

    // ---------------- 更新 ----------------

    @Test
    @DisplayName("update：布局替换 + 状态切换；非法状态拒绝")
    void updateReplacesLayoutAndStatus() {
        when(repo.findByIdAndDeletedAtIsNull("dash123")).thenReturn(Optional.of(saved()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DashboardEntity updated = service.update("dash123", "desc", "INACTIVE",
                "{\"widgets\":[{\"id\":\"w9\",\"type\":\"table\",\"source\":\"payment-funnel\",\"span\":12}]}");

        assertEquals(DashboardEntity.DashboardStatus.INACTIVE, updated.status);
        assertTrue(updated.layout.contains("payment-funnel"));
        assertNotNull(updated.updatedAt);

        assertThrows(BusinessException.class, () -> service.update("dash123", null, "PAUSED", null));
    }

    // ---------------- 覆盖率补测：读取与校验分支 ----------------

    @Test
    @DisplayName("get：不存在抛 NOT_FOUND；listByGame 委托仓储")
    void getNotFoundAndList() {
        when(repo.findByIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.get("nope"));

        when(repo.findByGameIdAndDeletedAtIsNullOrderByNameAsc("game_a")).thenReturn(java.util.List.of(saved()));
        assertEquals(1, service.listByGame("game_a").size());
    }

    @Test
    @DisplayName("create：缺 gameId / 非法名称字符 被拒")
    void nameAndGameValidation() {
        assertThrows(BusinessException.class, () -> service.create(null, "s1", null, VALID_LAYOUT));
        assertThrows(BusinessException.class, () -> service.create(" ", "s1", null, VALID_LAYOUT));
        assertThrows(BusinessException.class, () -> service.create("game_a", "bad name!", null, VALID_LAYOUT));
    }

    @Test
    @DisplayName("校验补分支：granularity/limit 非法、数值串合法、widget 超上限、null widget")
    void paramsAndLayoutEdgeValidation() {
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "p1", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"granularity\":\"month\"}}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "p2", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"limit\":51}}]}"));
        assertThrows(BusinessException.class, () -> service.create(
                "game_a", "p3", null, "{\"widgets\":[null]}"));

        StringBuilder many = new StringBuilder("{\"widgets\":[");
        for (int i = 0; i < 31; i++) {
            if (i > 0) many.append(",");
            many.append("{\"type\":\"kpi\",\"source\":\"online-overview\"}");
        }
        many.append("]}");
        assertThrows(BusinessException.class, () -> service.create("game_a", "p4", null, many.toString()));

        // 数值串参数合法通过校验（校验不回改原值，布局仍存字符串 "30"）
        when(repo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DashboardEntity ok = service.create("game_a", "p5", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"days\":\"30\"}}]}");
        assertTrue(ok.layout.contains("\"days\":\"30\""));
    }

    // ---------------- 删除 ----------------

    @Test
    @DisplayName("delete：软删 + 置 INACTIVE；不存在返回 false")
    void deleteSoftDeletes() {
        when(repo.findById("dash123")).thenReturn(Optional.empty());
        assertEquals(false, service.delete("dash123"));

        DashboardEntity entity = saved();
        when(repo.findById("dash123")).thenReturn(Optional.of(entity));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertEquals(true, service.delete("dash123"));
        assertNotNull(entity.deletedAt);
        assertEquals(DashboardEntity.DashboardStatus.INACTIVE, entity.status);
        verify(repo).save(entity);
    }
}
