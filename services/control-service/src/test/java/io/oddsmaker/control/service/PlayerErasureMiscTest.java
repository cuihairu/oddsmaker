package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：webhook 失败不影响主流程、removeRecipient 精确 token 摘除、inList 字面量拼接与单引号/反斜杠转义、replaceAll 字面量替换。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasureMiscTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("webhook 失败不影响主流程")
    void webhookFailureIsTolerated() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        when(clickHouse.isAvailable()).thenReturn(false);
        stubPgDeletes();
        doThrow(new RuntimeException("webhook down"))
            .when(webhookService).sendCustomWebhook(anyString(), anyString(), any());

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
    }

    @Test
    @DisplayName("removeRecipient 精确 token 摘除（含前后逗号边界与前缀混淆）")
    void removeRecipientExactToken() {
        assertThat(PlayerErasureService.removeRecipient("a,p1,b", List.of("p1"))).isEqualTo("a,b");
        assertThat(PlayerErasureService.removeRecipient("p1,a", List.of("p1"))).isEqualTo("a");
        assertThat(PlayerErasureService.removeRecipient("a,p1", List.of("p1"))).isEqualTo("a");
        assertThat(PlayerErasureService.removeRecipient("p11", List.of("p1"))).isEqualTo("p11");
        assertThat(PlayerErasureService.removeRecipient(null, List.of("p1"))).isNull();
        assertThat(PlayerErasureService.removeRecipient("p1", List.of("p1"))).isEmpty();
        assertThat(PlayerErasureService.removeRecipient("a, p1 ,b", List.of("p1"))).isEqualTo("a,b");
    }

    @Test
    @DisplayName("inList 字面量拼接与单引号转义；replaceAll 字面量替换")
    void inListEscapesAndReplaceAll() {
        assertThat(PlayerErasureService.inList(List.of("a", "b"))).isEqualTo("('a','b')");
        assertThat(PlayerErasureService.inList(List.of("o'brien"))).isEqualTo("('o''brien')");
        assertThat(PlayerErasureService.inList(List.of())).isEqualTo("('')");
        assertThat(PlayerErasureService.inList(java.util.Arrays.asList("a", null))).isEqualTo("('a')");
        assertThat(PlayerErasureService.replaceAll("x=a x=b", List.of("a", "b"), "erased:r"))
            .isEqualTo("x=erased:r x=erased:r");
        assertThat(PlayerErasureService.replaceAll(null, List.of("a"), "erased:r")).isNull();
    }

    @Test
    @DisplayName("inList 反斜杠转义：CH 字面量 \\' 是转义单引号，反斜杠结尾的值只翻单引号会吃掉闭合引号")
    void inListEscapesBackslash() {
        // 值 a\' 逃逸形态：只翻单引号得 'a\''，CH 解析为字符串 a' 后 OR 1=1 逃出字符串
        assertThat(PlayerErasureService.inList(List.of("a\\' OR 1=1")))
            .isEqualTo("('a\\\\'' OR 1=1')");
        // 普通反斜杠值翻倍；反斜杠+单引号组合完整闭合
        assertThat(PlayerErasureService.inList(List.of("a\\b"))).isEqualTo("('a\\\\b')");
        assertThat(PlayerErasureService.inList(List.of("\\'"))).isEqualTo("('\\\\''')");
    }

}
