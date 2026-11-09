package io.oddsmaker.android

import android.content.AndroidSim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Android SDK JVM 直测（LAY-009 拆分）：track 核心字段与 props 合并、无 user props 不合并、identify 新旧与同用户三分支、expose 曝光、revenue 字段与 Int 金额、事件类型推断全分支、会话 30min 间隙滚动与 0 间隙旋转。
 */
class OddsmakerTrackEventsTest : OddsmakerJvmTestBase() {

  // ---------- track / identify / expose / revenue ----------

  @Test
  fun trackCarriesCoreFieldsAndMergedProps() {
    val sdk = newSdk()
    sdk.setUserId("u1")
    sdk.setUserProps(mapOf("tier" to "gold"))
    sdk.setPlayer("p9")
    val id = sdk.track("shop_buy", mapOf("n" to 3))
    assertTrue(id.matches(Regex("^[0-9a-f-]{36}$")))
    val e = queueJson()[0]
    assertEquals("game1", e.getString("game_id"))
    assertEquals("prod", e.getString("environment"))
    assertEquals("business", e.getString("event_type"))
    assertEquals("shop_buy", e.getString("event_name"))
    assertEquals("u1", e.getString("user_id"))
    assertEquals("dev-explicit", e.getString("device_id"))
    assertEquals("android", e.getString("platform"))
    assertTrue(e.getLong("ts_client") > 0)
    assertNotNull(e.getString("session_id"))
    val props = e.getJSONObject("props")
    assertEquals("gold", props.getString("tier"))
    assertEquals("p9", props.getString("player_id"))
    assertEquals(3, props.getInt("n"))
  }

  @Test
  fun trackWithoutUserPropsKeepsPropsUnmerged() {
    val sdk = newSdk()
    sdk.track("plain")                     // mergeProps: base 空 → 返回 props(null)
    sdk.setPlayer("p2")                    // base 非空 → base + props
    sdk.track("with_player", mapOf("k" to "v"))
    val evts = queueJson()
    assertNull(evts[0].optJSONObject("props"))
    assertEquals("p2", evts[1].getJSONObject("props").getString("player_id"))
    assertEquals("v", evts[1].getJSONObject("props").getString("k"))
  }

  @Test
  fun identifyPreviousNewAndSameUserBranches() {
    val sdk = newSdk()
    sdk.identify("u1")
    sdk.setPlayer("p9")
    sdk.identify("u2", mapOf("tier" to "gold"))
    sdk.identify("u2")
    val evts = queueJson()
    assertEquals("\$identify", evts[0].getString("event_name"))
    assertEquals("identity", evts[0].getString("event_type"))
    assertTrue(!evts[0].getJSONObject("props").has("previous_user_id"))
    val p1 = evts[1].getJSONObject("props")
    assertEquals("u1", p1.getString("previous_user_id"))
    assertEquals("u2", p1.getString("new_user_id"))
    assertEquals(true, p1.getBoolean("\$identify"))
    assertEquals("p9", p1.getString("player_id"))
    assertEquals("gold", p1.getString("tier"))
    assertEquals("u2", evts[1].getString("user_id"))
    assertTrue(!evts[2].getJSONObject("props").has("previous_user_id"))
  }

  @Test
  fun exposeTracksExperimentExposure() {
    val sdk = newSdk()
    sdk.expose("e1", "B")
    val e = queueJson()[0]
    assertEquals("experiment_exposure", e.getString("event_name"))
    assertEquals("experiment", e.getString("event_type"))
    assertEquals("B", e.getJSONObject("props").getString("variant"))
  }

  @Test
  fun revenueEventFieldsAndIntAmount() {
    val sdk = newSdk()
    sdk.revenue(9.99, "USD", mapOf("order_id" to "o1"))
    sdk.revenue(3, "eur")   // Int → toDouble
    val evts = queueJson()
    assertEquals("revenue", evts[0].getString("event_name"))
    assertEquals(9.99, evts[0].getDouble("revenue_amount"), 1e-9)
    assertEquals("USD", evts[0].getString("revenue_currency"))
    assertEquals(9.99, evts[0].getJSONObject("props").getDouble("amount"), 1e-9)
    assertEquals(3.0, evts[1].getDouble("revenue_amount"), 1e-9)
  }

  @Test
  fun inferEventTypeCoversAllBranches() {
    val sdk = newSdk()
    val names = listOf("\$identify", "identity_login", "risk_hit", "fraud_check", "experiment_view",
      "ad_click", "level_up", "quest_done", "session_start", "error_bad", "crash_now", "shop_buy")
    names.forEach { sdk.track(it) }
    val types = queueJson().map { it.getString("event_type") }
    assertEquals(listOf("identity", "identity", "risk", "risk", "experiment",
      "ad", "progression", "progression", "session", "error", "error", "business"), types)
  }

  @Test
  fun sessionRollsWithinGapAndRotatesAfterGap() {
    val sdk = newSdk()
    sdk.track("a"); sdk.track("b")
    val s = queueJson().map { it.getString("session_id") }
    assertEquals(s[0], s[1])   // 默认 30min 间隙 → 同会话

    prefs.reset(); AndroidSim.install(prefs)
    val sdk2 = newSdk(sessionGapMs = 0)
    sdk2.track("a")
    Thread.sleep(5)
    sdk2.track("b")
    val s2 = queueJson().map { it.getString("session_id") }
    assertTrue(s2[0] != s2[1])
  }
}
