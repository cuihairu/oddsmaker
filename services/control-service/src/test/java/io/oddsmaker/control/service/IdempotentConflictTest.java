package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 唯一约束并发幂等回归（T-REV07）：catch DataIntegrityViolation 后在原（已毒化）事务内回查
 * 会抛 TransactionSystemException → 500，设计的中幂等 200 不可达。修复后 catch 回查在
 * 新事务执行，幂等返回既有记录。
 * 用 @MockitoSpyBean 让 save 抛唯一违例、回查返回预插的胜者，确定性走 catch 分支。
 */
@DisplayName("唯一约束并发幂等回归（T-REV07）")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:idemconf;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-smoke",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.kafka.listener.auto-startup=false",
        "management.health.kafka.enabled=false",
        "oddsmaker.clickhouse.url=",
})
class IdempotentConflictTest {

    @Autowired private MailService mailService;
    @Autowired private PlayerDataQueryService playerDataQueryService;
    @Autowired private MailRepo mailRepo;
    @Autowired private MailClaimRepo mailClaimRepo;
    @Autowired private PlayerPaymentRepo paymentRepo;

    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private GameRepo gameRepo;
    @MockitoBean private GameEnvironmentRepo gameEnvironmentRepo;

    @Test
    @DisplayName("claim 并发唯一违例后回查幂等返回既有记录（不 500）")
    void mailClaimConflictReturnsExisting() {
        MailEntity mail = new MailEntity();
        mail.id = "mail_c1";
        mail.gameId = "g1";
        mail.title = "t";
        mail.content = "c";
        mail.scope = MailEntity.Scope.ALL;
        mail.status = MailEntity.Status.SENT;
        mail.sentAt = LocalDateTime.now();
        mailRepo.save(mail);

        // 真实并发：两个线程先查均空 → 一个 save 提交，另一个真实 INSERT 撞唯一约束；
        // catch 回查在（修复后）新事务读到胜者。事务 claim 下 catch 回查在已毒化事务
        // 内 AUTO flush → 500。
        var latch = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(
                () -> {
                    latch.await();
                    return mailService.claim(mail.id, "p1");
                })).toList();
        latch.countDown();
        var results = futures.stream().map(f -> {
            try { return java.util.Optional.ofNullable(f.get(30, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Exception e) { return java.util.Optional.<MailClaimEntity>empty(); }
        }).toList();
        pool.shutdown();

        var winners = results.stream().flatMap(java.util.Optional::stream).toList();
        assertEquals(2, winners.size());
        assertEquals(winners.get(0).id, winners.get(1).id);
    }

    @Test
    @DisplayName("ingestPayment 并发唯一违例后回查幂等返回既有记录（不 500）")
    void ingestPaymentConflictReturnsExisting() {
        when(gameRepo.findById("g1")).thenReturn(java.util.Optional.of(game("g1")));

        PlayerPaymentEntity incoming = new PlayerPaymentEntity();
        incoming.gameId = "g1";
        incoming.playerId = "p1";
        incoming.orderId = "o1";
        incoming.amount = new BigDecimal("10.00");

        var latchPay = new java.util.concurrent.CountDownLatch(1);
        var poolPay = java.util.concurrent.Executors.newFixedThreadPool(2);
        var futuresPay = java.util.stream.IntStream.range(0, 2).mapToObj(i -> poolPay.submit(
                () -> {
                    latchPay.await();
                    return playerDataQueryService.ingestPayment(incoming);
                })).toList();
        latchPay.countDown();
        var resultsPay = futuresPay.stream().map(f -> {
            try { return java.util.Optional.ofNullable(f.get(30, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Exception e) { return java.util.Optional.<PlayerPaymentEntity>empty(); }
        }).toList();
        poolPay.shutdown();

        var winnersPay = resultsPay.stream().flatMap(java.util.Optional::stream).toList();
        assertEquals(2, winnersPay.size());
        assertEquals(winnersPay.get(0).id, winnersPay.get(1).id);
        assertTrue(winnersPay.stream().anyMatch(p -> p.duplicate));
    }

    private static GameEntity game(String id) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = id;
        return g;
    }
}
