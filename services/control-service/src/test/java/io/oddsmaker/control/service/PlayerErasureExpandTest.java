package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.IdentityEntity;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：标识展开——收敛不动点、防环、超上限截断、直匹配种子、links 未知类型与空 linkedId 跳过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasureExpandTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("展开收敛到不动点：player→identity→多标识→第二个 identity")
    void resolveConverges() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "player_id", "p1")));
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(
            identity("idt1", "u1", "p1", "d1", "c1")));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1"))
            .thenReturn(List.of(link("idt1", "user_id", "u1"), link("idt1", "device_id", "d1"),
                link("idt1", "character_id", "c1"), link("idt1", "player_id", "p2")));
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of());
        when(identityLinkRepo.findByTypeAndIdAnyStatus("user_id", "u1")).thenReturn(List.of());
        when(identityRepo.findByGameIdAndUserIdAnyStatus("g1", "u1")).thenReturn(List.of());
        when(identityLinkRepo.findByTypeAndIdAnyStatus("device_id", "d1"))
            .thenReturn(List.of(link("idt2", "device_id", "d1")));
        when(identityRepo.findByGameIdAndDeviceIdAnyStatus("g1", "d1")).thenReturn(List.of());
        when(identityRepo.findById("idt2")).thenReturn(Optional.of(
            identity("idt2", "u1", "p2", "d1", null)));

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertThat(r.identityIds()).containsExactlyInAnyOrder("idt1", "idt2");
        assertThat(r.playerIds()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(r.userIds()).containsExactly("u1");
        assertThat(r.deviceIds()).containsExactly("d1");
        assertThat(r.characterIds()).containsExactly("c1");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    @DisplayName("展开防环：p1↔p2 双向 link 只解析一次")
    void resolveCutsCycle() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "player_id", "p1")));
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(identity("idt1", null, "p1", null, null)));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1"))
            .thenReturn(List.of(link("idt1", "player_id", "p2")));
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of());
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p2"))
            .thenReturn(List.of(link("idt2", "player_id", "p2")));
        when(identityRepo.findById("idt2")).thenReturn(Optional.of(identity("idt2", null, "p2", null, null)));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt2"))
            .thenReturn(List.of(link("idt2", "player_id", "p1")));   // 回指 p1
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p2")).thenReturn(List.of());

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertThat(r.playerIds()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    @DisplayName("展开超上限：第 limit+1 个新标识触发 truncated=true")
    void resolveTruncates() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndUserIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndDeviceIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityLinkRepo.findByIdentityIdAnyStatus(anyString())).thenReturn(List.of());
        // 种子直匹配命中一个四字段齐全的身份行 → 展开出 3 个新标识，limit=2 时第 3 个触发截断
        IdentityEntity seed = identity("idt0", "u0", "p0", "d0", "c0");
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p0")).thenReturn(List.of(seed));
        when(identityRepo.findById("idt0")).thenReturn(Optional.of(seed));

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p0", 2);

        assertThat(r.truncated()).isTrue();
    }

    @Test
    @DisplayName("直匹配种子：无 identity_links 的历史身份行也被纳入")
    void resolveDirectMatchSeed() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndDeviceIdAnyStatus("g1", "d1"))
            .thenReturn(List.of(identity("idt9", "u9", null, "d1", null)));
        when(identityRepo.findById("idt9")).thenReturn(Optional.empty());
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt9")).thenReturn(List.of());

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.DEVICE_ID, "d1", 500);

        assertThat(r.identityIds()).containsExactly("idt9");
        assertThat(r.deviceIds()).containsExactly("d1");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    @DisplayName("links 中未知类型 / 空 linkedId 项被安全跳过")
    void expandIdentitySkipsBlankAndUnknownLinks() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "player_id", "p1")));
        when(identityRepo.findById("idt1"))
            .thenReturn(Optional.of(identity("idt1", "u1", "p1", null, null)));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1")).thenReturn(List.of(
            link("idt1", "other_type", "zzz"),   // 未知类型 → 跳过
            link("idt1", "user_id", " "),         // blank → 跳过
            link("idt1", null, "nn")));           // null 类型 → 跳过
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of());

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertThat(r.userIds()).containsExactly("u1");
        assertThat(r.deviceIds()).isEmpty();
        assertThat(r.characterIds()).isEmpty();
    }

}
