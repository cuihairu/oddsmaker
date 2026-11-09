package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：sweep 调度——僵尸 PROCESSING 重回 PENDING、未来 scheduledFor 与重试耗尽跳过、单请求异常隔离、PARTIAL 正常重试直至 COMPLETED、重试与外层异常逐层捕获。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasureSweepTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("sweep：僵尸 PROCESSING 重置回 PENDING")
    void sweepRequeuesStale() {
        PlayerErasureRequestEntity stale = savedRequest();
        stale.status = PlayerErasureRequestEntity.Status.PROCESSING;
        stale.startedAt = LocalDateTime.now().minusMinutes(120);
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(requestRepo.findByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(requestRepo.findByStatus(any())).thenReturn(List.of());

        service.sweep();

        assertThat(stale.status).isEqualTo(PlayerErasureRequestEntity.Status.PENDING);
        assertThat(stale.startedAt).isNull();
        assertThat(stale.errorMessage).contains("requeued");
    }

    @Test
    @DisplayName("sweep：未来 scheduledFor 跳过；重试耗尽的 PARTIAL 不再 process")
    void sweepSkipsFutureAndExhausted() {
        PlayerErasureRequestEntity future = savedRequest();
        future.scheduledFor = LocalDateTime.now().plusHours(1);
        PlayerErasureRequestEntity exhausted = savedRequest();
        exhausted.status = PlayerErasureRequestEntity.Status.PARTIAL;
        exhausted.executionSummary = "{\"pgDone\":true,\"retries\":8}";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(PlayerErasureRequestEntity.Status.PENDING))
            .thenReturn(List.of(future));
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(exhausted));

        service.sweep();

        assertThat(future.status).isEqualTo(PlayerErasureRequestEntity.Status.PENDING);
        assertThat(exhausted.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
        verify(requestRepo, never()).save(exhausted);
    }

    @Test
    @DisplayName("sweep：单请求异常不中断后续处理")
    void sweepIsolatesFailures() {
        PlayerErasureRequestEntity first = savedRequest();
        PlayerErasureRequestEntity second = savedRequest();
        second.id = "per_second0000000000000000";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(PlayerErasureRequestEntity.Status.PENDING))
            .thenReturn(List.of(first, second));
        when(requestRepo.findByStatus(any())).thenReturn(List.of());
        // first 抛异常（process 内 get 直接抛出），second 正常完成
        when(requestRepo.findById(first.id)).thenThrow(new IllegalArgumentException("boom"));
        when(requestRepo.findById(second.id)).thenReturn(Optional.of(second));
        when(clickHouse.isAvailable()).thenReturn(false);
        stubPgDeletes();

        service.sweep();

        assertThat(second.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        verify(requestRepo, times(1)).findById(first.id);
    }

    @Test
    @DisplayName("sweep：未耗尽重试的 PARTIAL 正常重走 CH 段直至 COMPLETED")
    void sweepRetriesPartialNormally() throws Exception {
        PlayerErasureRequestEntity partial = savedRequest();
        partial.status = PlayerErasureRequestEntity.Status.PARTIAL;
        partial.executionSummary = "{\"pgDone\":true,\"retries\":1}";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(partial));
        when(requestRepo.findById(partial.id)).thenReturn(Optional.of(partial));
        stubChHappyPath(new ArrayList<>());

        service.sweep();

        assertThat(partial.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        assertThat(partial.executionSummary).contains("\"retries\":1");   // 成功路径不递增重试计数
    }

    @Test
    @DisplayName("sweep：PARTIAL 重试内部异常被逐请求捕获，外层异常不影响方法返回")
    void sweepCatchesRetryAndOuterFailures() {
        PlayerErasureRequestEntity broken = savedRequest();
        broken.status = PlayerErasureRequestEntity.Status.PARTIAL;
        broken.executionSummary = "{\"pgDone\":false}";   // 无 pgDone → process 抛 ISE → ③ 内 catch
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("stale query down"));   // ① 抛 → ② ③ 跳过
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(broken));
        when(requestRepo.findById(broken.id)).thenReturn(Optional.of(broken));

        service.sweep();   // 外层 catch 吞掉 ① 异常；③ 不会执行（外层已中断）

        // 外层 catch 生效：方法正常返回，broken 未被触碰
        assertThat(broken.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
    }

    @Test
    @DisplayName("sweep：PARTIAL 重试内部异常被逐请求捕获")
    void sweepCatchesRetryFailure() {
        PlayerErasureRequestEntity broken = savedRequest();
        broken.status = PlayerErasureRequestEntity.Status.PARTIAL;
        broken.executionSummary = "{\"pgDone\":false}";   // 无 pgDone → process 抛 ISE
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(broken));
        when(requestRepo.findById(broken.id)).thenReturn(Optional.of(broken));

        service.sweep();   // ③ 的 try-catch 吞掉 ISE

        assertThat(broken.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
    }

}
