package io.oddsmaker.agent;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * assign()/poll() 离线单测（mock KafkaConsumer，不触网）：适配器消费回路的逐臂覆盖——
 * 无分区 fail-fast、null/部分/全量三种起点的 seek 语义（断点缺分区补 seekToBeginning、
 * 全量断点不回卷）、poll 空批与记录映射。
 *
 * <p>真实 broker 回路（earliest 定位/断点续传/扩分区追赶）仍以 KafkaSource*BrokerE2ETest
 * 端到端实测为准——本类只证明逐臂逻辑，不替代 E2E。
 */
class KafkaConsumerAdapterAssignPollTest {

    /** 无人监听的 bootstrap：构造期不建连（见 KafkaConsumerAdapterCtorTest），构造出的真身随即 close。 */
    private static final String DEAD_BOOTSTRAP = "127.0.0.1:9999";
    private static final String TOPIC = "dims";

    private KafkaConsumer<byte[], byte[]> consumer;
    private KafkaConsumerAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new KafkaConsumerAdapter(DEAD_BOOTSTRAP, "g-assign-poll", TOPIC);
        // 构造器里 new 出来的真身离线不触网，但会占后台线程：先关掉，再把字段换成 mock
        Field field = KafkaConsumerAdapter.class.getDeclaredField("consumer");
        field.setAccessible(true);
        KafkaConsumer<?, ?> real = (KafkaConsumer<?, ?>) field.get(adapter);
        real.close(Duration.ZERO);
        @SuppressWarnings("unchecked")
        KafkaConsumer<byte[], byte[]> mockConsumer = mock(KafkaConsumer.class);
        consumer = mockConsumer;
        field.set(adapter, consumer);
    }

    @AfterEach
    void tearDown() {
        adapter.close();   // 字段已是 mock，close 为空操作
    }

    private static PartitionInfo partition(int id) {
        return new PartitionInfo(TOPIC, id, null, null, null);
    }

    private static TopicPartition tp(int id) {
        return new TopicPartition(TOPIC, id);
    }

    @Test
    @DisplayName("assign：topic 无可用分区 → IllegalStateException（先刷元数据再判空，fail-fast 不静默空指派）")
    void assignRejectsTopicWithoutPartitions() {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.assign(null));
        assertTrue(ex.getMessage().contains(TOPIC), ex.getMessage());
        verify(consumer).listTopics();   // 判空前先全刷元数据（扩分区感知的既定路径）
        verify(consumer, never()).assign(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("assign：起点 null → 指派全部分区并整体 seekToBeginning，不调 seek")
    void assignWithNullStartOffsetsSeeksEveryPartitionToBeginning() throws Exception {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of(partition(0), partition(1)));

        adapter.assign(null);

        ArgumentCaptor<List<TopicPartition>> assignCap = ArgumentCaptor.forClass(List.class);
        verify(consumer).assign(assignCap.capture());
        assertEquals(List.of(tp(0), tp(1)), assignCap.getValue());

        ArgumentCaptor<Collection<TopicPartition>> beginCap = ArgumentCaptor.forClass(Collection.class);
        verify(consumer).seekToBeginning(beginCap.capture());
        assertEquals(Set.of(tp(0), tp(1)), new HashSet<>(beginCap.getValue()));
        verify(consumer, never()).seek(any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("assign：起点只覆盖部分分区 → 有断点的 seek 断点，缺断点的补 seekToBeginning")
    void assignWithPartialOffsetsSeeksExplicitAndBeginningRest() throws Exception {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of(partition(0), partition(1)));

        adapter.assign(Map.of(0, 5L));

        verify(consumer).seek(tp(0), 5L);
        ArgumentCaptor<Collection<TopicPartition>> beginCap = ArgumentCaptor.forClass(Collection.class);
        verify(consumer).seekToBeginning(beginCap.capture());
        assertEquals(Set.of(tp(1)), new HashSet<>(beginCap.getValue()));
        verify(consumer, never()).seek(eq(tp(1)), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("assign：起点覆盖全部分区 → 逐分区 seek 断点，不回卷 seekToBeginning")
    void assignWithFullOffsetsSkipsSeekToBeginning() throws Exception {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of(partition(0), partition(1)));

        adapter.assign(Map.of(0, 5L, 1, 7L));

        verify(consumer).seek(tp(0), 5L);
        verify(consumer).seek(tp(1), 7L);
        verify(consumer, never()).seekToBeginning(any());
    }

    @Test
    @DisplayName("poll：空批 → 返回空列表（超时无数据的正常路径，不抛）")
    void pollReturnsEmptyListOnEmptyBatch() throws Exception {
        when(consumer.poll(Duration.ofMillis(3000))).thenReturn(ConsumerRecords.empty());

        List<KafkaConsumerPort.PortRecord> out = adapter.poll(3000);

        assertTrue(out.isEmpty(), "空批应返回空列表");
        verify(consumer).poll(Duration.ofMillis(3000));   // 超时按调用方入参透传
    }

    @Test
    @DisplayName("poll：非空批 → 逐条映射 partition/offset/value（字节数组原样透传）")
    void pollMapsConsumerRecordsToPortRecords() throws Exception {
        TopicPartition p0 = tp(0);
        TopicPartition p1 = tp(1);
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> raw = new LinkedHashMap<>();
        raw.put(p0, List.of(new ConsumerRecord<>(TOPIC, 0, 41L, null, new byte[]{1, 2})));
        raw.put(p1, List.of(new ConsumerRecord<>(TOPIC, 1, 7L, null, null)));
        when(consumer.poll(Duration.ofMillis(100))).thenReturn(new ConsumerRecords<>(raw));

        List<KafkaConsumerPort.PortRecord> out = adapter.poll(100);

        assertEquals(2, out.size());
        KafkaConsumerPort.PortRecord r0 = out.stream().filter(r -> r.partition() == 0)
                .findFirst().orElseThrow();
        assertEquals(41L, r0.offset());
        assertArrayEquals(new byte[]{1, 2}, r0.value());
        KafkaConsumerPort.PortRecord r1 = out.stream().filter(r -> r.partition() == 1)
                .findFirst().orElseThrow();
        assertEquals(7L, r1.offset());
        assertNull(r1.value());
        verify(consumer).poll(Duration.ofMillis(100));
    }
}
