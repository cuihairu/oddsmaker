package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.IdentityEventDto;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：IdentityConsumer——空 identityId 与坏 JSON 跳过、已有身份合并更新且环境 miss 走缓存、上游落库异常上抛（T-REV08）、新建身份 primaryId 依 首设备/userId/identityId 回退。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2IdentityConsumerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @InjectMocks
    private IdentityConsumer identityConsumer;

    private String identityJson(IdentityEventDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("身份消费：空 identityId 与坏 JSON 跳过，已有身份合并更新且环境解析 miss 走缓存")
    void identityConsumerSkipMergeAndEnvCache() {
        identityConsumer.onIdentityEvent("{}");
        identityConsumer.onIdentityEvent("not-a-json");
        verifyNoInteractions(identityRepo, identityLinkRepo, gameEnvironmentRepo);

        // 已有身份：eventCount 自增、firstSeen 回填、空白 device 跳过、链接 usageCount 自增
        IdentityEventDto merge = new IdentityEventDto();
        merge.gameId = "game_1";
        merge.identityId = "id_existing";
        merge.userId = "u9";
        merge.playerId = "p9";
        merge.deviceIds = List.of("dev_1", "  ");
        merge.playerIds = List.of("p_9");
        merge.firstSeen = 1_000L;
        merge.lastSeen = 2_000L;

        IdentityEntity existing = new IdentityEntity();
        existing.id = "id_existing";
        existing.gameId = "game_1";
        existing.eventCount = 5L;
        existing.firstSeenAt = null;
        lenient().when(identityRepo.findById("id_existing")).thenReturn(Optional.of(existing));
        lenient().when(identityRepo.save(any(IdentityEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IdentityLinkEntity deviceLink = new IdentityLinkEntity();
        deviceLink.id = "ilk_1";
        deviceLink.identityId = "id_existing";
        deviceLink.linkedIdentityType = "device_id";
        deviceLink.linkedId = "dev_1";
        deviceLink.usageCount = 3L;
        lenient().when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(
            "id_existing", "device_id", "dev_1")).thenReturn(Optional.of(deviceLink));

        identityConsumer.onIdentityEvent(identityJson(merge));

        assertEquals(6L, existing.eventCount);
        assertNotNull(existing.firstSeenAt);
        assertEquals("u9", existing.userId);
        assertEquals("p9", existing.playerId);
        assertEquals("dev_1", existing.deviceId);
        assertEquals(4L, deviceLink.usageCount);
        verify(identityLinkRepo, org.mockito.Mockito.atLeast(3)).save(any(IdentityLinkEntity.class));

        // 新建身份：环境 miss → environmentId 为 null，并缓存结果
        IdentityEventDto first = new IdentityEventDto();
        first.gameId = "game_1";
        first.environment = "prod";
        first.identityId = "id_new";
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of());
        identityConsumer.onIdentityEvent(identityJson(first));

        IdentityEventDto second = new IdentityEventDto();
        second.gameId = "game_1";
        second.environment = "prod";
        second.identityId = "id_new2";
        identityConsumer.onIdentityEvent(identityJson(second));

        ArgumentCaptor<IdentityEntity> captor = ArgumentCaptor.forClass(IdentityEntity.class);
        verify(identityRepo, times(3)).save(captor.capture());
        assertNull(captor.getAllValues().get(1).environmentId);
        assertNull(captor.getAllValues().get(2).environmentId);
        verify(gameEnvironmentRepo, times(1))
            .findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod");

        // 上游落库异常必须向上传播：Kafka 容器按错误处理器重试，offset 不前进（T-REV08）。
        lenient().when(identityRepo.save(any(IdentityEntity.class)))
            .thenThrow(new RuntimeException("db down"));
        IdentityEventDto boom = new IdentityEventDto();
        boom.gameId = "game_1";
        boom.identityId = "id_err";
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> identityConsumer.onIdentityEvent(identityJson(boom)));
    }

    @Test
    @DisplayName("身份消费：新建身份 primaryId 依 首设备/userId/identityId 顺序回退")
    void identityConsumerPrimaryIdFallbackChain() {
        lenient().when(identityRepo.save(any(IdentityEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IdentityEventDto withUser = new IdentityEventDto();
        withUser.gameId = "game_1";
        withUser.identityId = "id_u";
        withUser.userId = "u1";
        withUser.playerId = "p1";
        identityConsumer.onIdentityEvent(identityJson(withUser));

        IdentityEventDto bare = new IdentityEventDto();
        bare.gameId = "game_1";
        bare.identityId = "id_min";
        identityConsumer.onIdentityEvent(identityJson(bare));

        IdentityEventDto withDevices = new IdentityEventDto();
        withDevices.gameId = "game_1";
        withDevices.identityId = "id_dev";
        withDevices.deviceIds = List.of("dev_a", "dev_b");
        withDevices.lastSeen = 3_000L;
        identityConsumer.onIdentityEvent(identityJson(withDevices));

        ArgumentCaptor<IdentityEntity> captor = ArgumentCaptor.forClass(IdentityEntity.class);
        verify(identityRepo, times(3)).save(captor.capture());
        IdentityEntity byUser = captor.getAllValues().get(0);
        assertEquals("u1", byUser.primaryId);
        assertEquals(IdentityEntity.IdentityStatus.ACTIVE, byUser.status);
        assertEquals(1L, byUser.eventCount);
        assertNotNull(byUser.lastSeenAt);
        IdentityEntity minimal = captor.getAllValues().get(1);
        assertEquals("id_min", minimal.primaryId);
        IdentityEntity byDevice = captor.getAllValues().get(2);
        assertEquals("dev_a", byDevice.primaryId);
        assertEquals("dev_a", byDevice.deviceId);
    }

}
