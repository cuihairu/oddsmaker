package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.SymbolMappingEntity;
import io.oddsmaker.control.jpa.SymbolMappingRepo;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.GameService;
import io.oddsmaker.control.service.StorageProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·组3：ApiController.listKeys 五条件短路链的各 false 边（79 行）+
 * page/size null 兜底侧（84/85 行）+ SymbolMappingController.register 的
 * 空串 id 重新生成侧（31 行）与显式 null status 补默认侧（34 行，实体初始化器坑）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("组3 分支补充：ApiController 短路边与符号表登记缺侧")
class ApiKeysBranchFinalTest {

    // ---------- ApiController ----------

    @Mock private io.oddsmaker.control.api.ControlService svc;
    @Mock private GameService gameService;
    @Mock private io.oddsmaker.control.service.ExperimentService experimentService;
    @Mock private StorageProfileService storageProfileService;
    @InjectMocks private ApiController apiController;

    @BeforeEach
    void setUp() {
        List<Models.KeyDetailResp> all = List.of(new Models.KeyDetailResp());
        lenient().when(svc.listKeys()).thenReturn(all);
        lenient().when(svc.searchKeys(any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new ControlService.Paged<>(all, 1L));
    }

    @Test
    @DisplayName("listKeys：短路链逐条件 false 边（q/size/gameId/environmentId 单独非 null）+ page/size null 兜底")
    void listKeysShortCircuitEdges() {
        // c1T c2T c3F：只有 q 非 null → 84 行 page null→0、85 行 size null→50
        apiController.listKeys("q", null, null, null, null);
        verify(svc).searchKeys(null, null, "q", 0, 50);
        // c1T c2F：只有 size 非 null（page null → 0）
        apiController.listKeys(null, null, null, null, 10);
        verify(svc).searchKeys(null, null, null, 0, 10);
        // c1T c2T c3T c4F：只有 gameId 非 null
        apiController.listKeys(null, "g", null, null, null);
        verify(svc).searchKeys("g", null, null, 0, 50);
        // c1T..c4T c5F：只有 environmentId 非 null
        apiController.listKeys(null, null, "e", null, null);
        verify(svc).searchKeys(null, "e", null, 0, 50);
    }

    // ---------- SymbolMappingController ----------

    @Mock private SymbolMappingRepo symbolMappingRepo;
    @Mock private AuditLogService auditLog;
    @InjectMocks private SymbolMappingController symbolMappingController;

    @Test
    @DisplayName("register：空串 id 重新生成（isEmpty true 边）；status 显式 null 补默认（实体初始化器坑）")
    void registerEmptyIdAndNullStatus() {
        when(symbolMappingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 31 行第二条件：id 非 null 但为空串 → 走重新生成分支
        SymbolMappingEntity emptyId = new SymbolMappingEntity();
        emptyId.id = "";
        emptyId.platform = "ios";
        SymbolMappingEntity r1 = symbolMappingController.register(emptyId).getBody();
        assertTrue(r1.id.startsWith("sym_"));

        // 34 行 true 边：status 有初始化器 ACTIVE，须显式置 null 才能走「补默认」侧
        SymbolMappingEntity nullStatus = new SymbolMappingEntity();
        nullStatus.id = "sym_x";
        nullStatus.platform = "android";
        nullStatus.status = null;
        SymbolMappingEntity r2 = symbolMappingController.register(nullStatus).getBody();
        assertEquals(SymbolMappingEntity.MappingStatus.ACTIVE, r2.status);
        assertEquals("sym_x", r2.id);   // 非 null 非空 id 直用（31 行整体 false 边）
    }
}
