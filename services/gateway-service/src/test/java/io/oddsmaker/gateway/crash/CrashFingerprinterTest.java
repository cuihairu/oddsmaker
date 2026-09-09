package io.oddsmaker.gateway.crash;

import io.oddsmaker.common.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃指纹测试：堆栈规范化聚合（地址/路径/行号漂移不改变指纹）、注入幂等与事件类型守卫。
 */
@DisplayName("崩溃指纹测试")
class CrashFingerprinterTest {

    private static final String STACK_A =
            "java.lang.NullPointerException\n"
            + "    at com.game.battle.CombatSystem.resolve(CombatSystem.kt:87)\n"
            + "    at com.game.battle.CombatSystem.turn(CombatSystem.kt:120)\n"
            + "    at com.game.core.Loop.tick(Loop.kt:31)\n"
            + "    at java.lang.Thread.run(Thread.java:829)";

    private static final String STACK_A_SAME_CRASH_DIFFERENT_DEVICE =
            "java.lang.NullPointerException\n"
            + "    at com.game.battle.CombatSystem.resolve(CombatSystem.kt:91)\n"
            + "    at com.game.battle.CombatSystem.turn(CombatSystem.kt:133)\n"
            + "    at com.game.core.Loop.tick(Loop.kt:31)\n"
            + "    at java.lang.Thread.run(Thread.java:834)";

    @Test
    @DisplayName("指纹：行号漂移不影响（同一崩溃源同指纹）")
    void fingerprintStableAcrossLineNumbers() {
        String a = CrashFingerprinter.fingerprint("error_crash", STACK_A);
        String b = CrashFingerprinter.fingerprint("error_crash", STACK_A_SAME_CRASH_DIFFERENT_DEVICE);
        assertEquals(a, b);
        assertEquals(16, a.length());
    }

    @Test
    @DisplayName("指纹：不同调用栈/事件名产生不同指纹")
    void fingerprintDiffersAcrossCrashes() {
        String a = CrashFingerprinter.fingerprint("error_crash", STACK_A);
        String b = CrashFingerprinter.fingerprint("error_crash",
                "java.lang.IllegalStateException\n"
                + "    at com.game.shop.Purchase.buy(Purchase.kt:45)\n"
                + "    at com.game.core.Loop.tick(Loop.kt:31)");
        assertNotEquals(a, b);
        // 事件名不同（无堆栈回退路径）
        assertNotEquals(
            CrashFingerprinter.fingerprint("error_crash", null),
            CrashFingerprinter.fingerprint("error_exception", null));
    }

    @Test
    @DisplayName("指纹：地址与路径归一（0x.. / 文件路径 / 数字）")
    void normalizeFrameStripsVolatileParts() {
        assertEquals("com.game.a.Foo.bar(Foo.kt:N)",
                CrashFingerprinter.normalizeFrame("at com.game.a.Foo.bar(/data/app/com.game/Foo.kt:117)"));
        assertEquals("libgame.so+0xX",
                CrashFingerprinter.normalizeFrame("at libgame.so+0x7f3a2b10c000"));
        assertEquals("Foo.bar(Foo.kt:N)",
                CrashFingerprinter.normalizeFrame("Foo.bar(C:\\build\\Foo.kt:9)"));
    }

    @Test
    @DisplayName("指纹：仅前 8 帧参与，第 8 帧之后漂移不影响")
    void onlyTopFramesCounted() {
        // 两个栈前 8 帧相同（1 异常行 + 7 帧调用），其后帧不同
        StringBuilder base = new StringBuilder("java.lang.NullPointerException");
        for (int i = 0; i < 7; i++) {
            base.append("\n    at com.game.deep.Frame").append(i).append(".run(F").append(i).append(".kt:").append(i).append(")");
        }
        StringBuilder longer = new StringBuilder(base);
        longer.append("\n    at com.game.tail.Extra.run(Extra.kt:1)");
        longer.append("\n    at com.game.tail.More.run(More.kt:2)");

        assertEquals(
            CrashFingerprinter.fingerprint("error_crash", base.toString()),
            CrashFingerprinter.fingerprint("error_crash", longer.toString()));
    }

    @Test
    @DisplayName("指纹：无堆栈回退事件名，空行与省略帧跳过")
    void fallbackAndSkips() {
        assertEquals("name:error_crash", CrashFingerprinter.signature("Error_Crash ", null));
        assertEquals("name:error_crash", CrashFingerprinter.signature("error_crash", "\n   \n...3 more frames\n"));
    }

    @Test
    @DisplayName("注入：error 事件补 crash_hash 与 crash_message，SDK 已提供则保留")
    void enrichInjectsHashAndMessage() {
        Event event = new Event();
        event.eventType = "error";
        event.eventName = "error_crash";
        event.props = new java.util.HashMap<>();
        event.props.put("error_stack_trace", STACK_A);
        event.props.put("error_message", "NPE in combat");

        CrashFingerprinter.enrich(event);

        assertEquals(CrashFingerprinter.fingerprint("error_crash", STACK_A), event.props.get("crash_hash"));
        assertEquals("NPE in combat", event.props.get("crash_message"));

        // SDK 已提供 hash：保留且不覆盖
        event.props.put("crash_hash", "sdk_hash");
        CrashFingerprinter.enrich(event);
        assertEquals("sdk_hash", event.props.get("crash_hash"));
    }

    @Test
    @DisplayName("注入：非 error 事件与空 props 不处理")
    void enrichGuards() {
        Event business = new Event();
        business.eventType = "business";
        business.props = new java.util.HashMap<>();
        CrashFingerprinter.enrich(business);
        assertNull(business.props.get("crash_hash"));

        // eventType 为 null 也不处理（未分类事件）
        Event untyped = new Event();
        untyped.props = new java.util.HashMap<>();
        CrashFingerprinter.enrich(untyped);
        assertNull(untyped.props.get("crash_hash"));

        Event noProps = new Event();
        noProps.eventType = "error";
        CrashFingerprinter.enrich(noProps);
        assertNull(noProps.props);

        // error 但无 message：不注入 crash_message
        Event noMessage = new Event();
        noMessage.eventType = "error";
        noMessage.props = new java.util.HashMap<>();
        CrashFingerprinter.enrich(noMessage);
        assertTrue(noMessage.props.containsKey("crash_hash"));
        assertNull(noMessage.props.get("crash_message"));
    }

    @Test
    @DisplayName("指纹：全空输入走事件名回退且稳定")
    void fingerprintNullInputs() {
        String hash = CrashFingerprinter.fingerprint(null, null);
        assertEquals(16, hash.length());
        assertEquals(hash, CrashFingerprinter.fingerprint(null, "  \n"));
    }
}
