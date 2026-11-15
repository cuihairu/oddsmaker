package io.oddsmaker.android

import android.util.Log
import io.oddsmaker.android.Oddsmaker.Event
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any

/**
 * Android SDK JVM 直测（LAY-009 拆分）：与 Web SDK/后端契约一致性：revenue 币种大写与顶层字段、player_id 顶层对齐 Web、便捷 helper 无 props 默认桥、类型化 helper 契约顶层字段、batch 响应 kafka_error 回队/永久拒绝丢弃/2xx 宽松体/边缘分支、新契约字段经队列持久化往返。
 */
class OddsmakerContractConsistencyTest : OddsmakerJvmTestBase() {

  // ---------- 与 Web SDK/后端契约一致性 ----------

  private fun firstEventJson(server: ScriptedServer): JSONObject =
    JSONObject(String(gunzip(server.requests[0].body)).trim().substringBefore('\n'))

  @Test
  fun revenueNormalizesCurrencyToUpperAndFillsTopLevel() {
    val server = ScriptedServer().also { it.enqueue(); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url()).also { it.setUserId("u1") }
    sdk.revenue(9.99, "usd")
    sdk.flush()
    val e = firstEventJson(server)
    assertEquals("USD", e.getString("revenue_currency"))
    assertEquals(9.99, e.getDouble("revenue_amount"))
    assertEquals("USD", e.getJSONObject("props").getString("currency"))
    assertEquals(9.99, e.getJSONObject("props").getDouble("amount"))
  }

  @Test
  fun setPlayerEmitsTopLevelPlayerIdLikeWeb() {
    val server = ScriptedServer().also { it.enqueue(); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.setPlayer("player-1")
    sdk.track("level_seen")
    sdk.flush()
    val e = firstEventJson(server)
    assertEquals("player-1", e.getString("player_id"))
    assertEquals("player-1", e.getJSONObject("props").getString("player_id"))
  }

  @Test
  fun convenienceHelpersWorkWithoutProps() {
    // 不带 props 调用：走 Kotlin 默认参数 $default 桥，同时验证核心字段仍完整
    val server = ScriptedServer().also { srv ->
      repeat(3) { srv.enqueue() }
      servers.add(srv.start())
    }
    val sdk = newSdk(endpoint = server.url())

    sdk.levelFail("L3", "timeout")
    sdk.levelComplete("L4")
    sdk.adImpression(0.01, "jpy")
    sdk.flush()

    fun ev(i: Int): JSONObject =
      server.requests.flatMap { String(gunzip(it.body)).trim().split('\n') }
        .filter { it.isNotBlank() }
        .let { lines -> JSONObject(lines[i]) }
    val fail = ev(0)
    assertEquals("level_fail", fail.getString("event_name"))
    assertEquals("L3", fail.getString("level_id"))
    // Event 契约无 fail_reason 顶层字段，reason 只随 props 透传
    assertEquals("timeout", fail.getJSONObject("props").getString("fail_reason"))
    val complete = ev(1)
    assertEquals("level_complete", complete.getString("event_name"))
    assertEquals("L4", complete.getString("level_id"))
    val ad = ev(2)
    assertEquals("ad_impression", ad.getString("event_name"))
    assertEquals("JPY", ad.getString("revenue_currency"))
    assertEquals(0.01, ad.getDouble("revenue_amount"))
  }

  @Test
  fun typedHelpersFillContractTopLevelFields() {
    val server = ScriptedServer().also { srv ->
      repeat(10) { srv.enqueue() }
      servers.add(srv.start())
    }
    val sdk = newSdk(endpoint = server.url())

    sdk.levelStart("L1")
    sdk.currencySource("gem", 5)
    sdk.itemGrant("gem_pack", 3)
    sdk.itemConsume("sword", 2)
    sdk.iapOrder("order-9", 4.5, "eur")
    sdk.flush()

    sdk.levelComplete("L1", mapOf("game_mode" to "pvp"))
    sdk.levelFail("L2", "boss", mapOf("game_mode" to "pve"))
    sdk.currencySink("gem", 1)
    sdk.webshopOrder("ws-1", 2.5, "gbp")
    sdk.adImpression(0.02, "usd", mapOf("network" to "adcolony", "placement_id" to "menu", "ad_format" to "rewarded"))
    sdk.flush()

    fun ev(i: Int): JSONObject =
      server.requests.flatMap { String(gunzip(it.body)).trim().split('\n') }
        .filter { it.isNotBlank() }
        .let { lines -> JSONObject(lines[i]) }
    val level = ev(0)
    assertEquals("level_start", level.getString("event_name"))
    assertEquals("L1", level.getString("level_id"))
    val source = ev(1)
    assertEquals("GEM", source.getString("resource_id"))
    assertEquals(5.0, source.getDouble("resource_amount"))
    assertEquals("GEM", source.getString("virtual_currency"))
    assertEquals(5.0, source.getDouble("virtual_amount"))
    assertEquals("source", source.getString("flow_type"))
    val grant = ev(2)
    assertEquals("gem_pack", grant.getString("item_id"))
    assertEquals("source", grant.getString("flow_type"))
    val consume = ev(3)
    assertEquals("sword", consume.getString("item_id"))
    assertEquals("sink", consume.getString("flow_type"))
    val iap = ev(4)
    assertEquals("order-9", iap.getString("order_id"))
    assertEquals("EUR", iap.getString("revenue_currency"))
    assertEquals(4.5, iap.getDouble("revenue_amount"))
    val complete = ev(5)
    assertEquals("L1", complete.getString("level_id"))
    assertEquals("pvp", complete.getString("game_mode"))
    val fail = ev(6)
    assertEquals("L2", fail.getString("level_id"))
    assertEquals("pve", fail.getString("game_mode"))
    val ad = ev(9)
    assertEquals("adcolony", ad.getString("ad_network"))
    assertEquals("menu", ad.getString("ad_placement"))
    assertEquals("rewarded", ad.getString("ad_format"))
    assertEquals("USD", ad.getString("revenue_currency"))
    val ws = ev(8)
    assertEquals("ws-1", ws.getString("order_id"))
    assertEquals("GBP", ws.getString("revenue_currency"))
  }

  @Test
  fun flushRequeuesKafkaErrorRejectedAndDropsPermanentRejections() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    val idRetry = sdk.track("retryable")
    val idDead = sdk.track("permanent")
    server.enqueue(200, """{"accepted":["$idRetry"],"rejected":[
      {"event_id":"$idRetry","reason":"kafka_error"},
      {"event_id":"$idDead","reason":"invalid_schema"}]}""")
    sdk.flush()
    // kafka_error 回队、permanent 丢弃
    val queued = queueJson().map { it.getString("event_id") }
    assertEquals(listOf(idRetry), queued)

    server.enqueue()
    sdk.flush()
    assertEquals(2, server.requests.size)
    val resent = String(gunzip(server.requests[1].body)).trim().split('\n').map { JSONObject(it).getString("event_id") }
    assertEquals(listOf(idRetry), resent)
  }

  @Test
  fun flushTreatsUnparsable2xxBodyAsAccepted() {
    val server = ScriptedServer().also { it.enqueue(200, "<html>gateway</html>"); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.track("tolerant")
    sdk.flush()
    assertTrue(queueJson().isEmpty())   // 解析失败按全成功,不回队
  }

  @Test
  fun flushHandleBatchResponseEdgeBranches() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url(), debug = true)
    // 1) rejected 空数组 → reasons 空 → 无动作
    server.enqueue(200, """{"accepted":["a"],"rejected":[]}""")
    sdk.track("e1")
    sdk.flush()
    assertTrue(queueJson().isEmpty())
    // 2) 响应体缺 rejected 键 → 早退
    server.enqueue(200, """{"accepted":["b"]}""")
    sdk.track("e2")
    sdk.flush()
    assertTrue(queueJson().isEmpty())
    // 3) permanent 拒绝 + debug → Log.w 告警、事件丢弃
    val idDead = sdk.track("e3")
    server.enqueue(200, """{"accepted":[],"rejected":[{"event_id":"$idDead","reason":"blocked"}]}""")
    sdk.flush()
    assertTrue(queueJson().isEmpty())
    assertTrue(Log.lines.any { it.contains(idDead) && it.contains("blocked") })
  }

  @Test
  fun newContractFieldsRoundTripThroughQueuePersistence() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.setPlayer("p-rt")
    sdk.levelFail("L9", "timeout", mapOf("game_mode" to "pve"))
    sdk.currencySource("gem", 7)
    sdk.adImpression(0.03, "usd", mapOf("network" to "n1", "placement_id" to "pl1", "ad_format" to "rewarded"))
    sdk.iapOrder("ord-rt", 1.5, "jpy")
    sdk.shutdown()

    // 模拟重启:同一 prefs 重建实例 → 恢复队列 → flush
    val server2 = ScriptedServer().also { srv -> repeat(4) { srv.enqueue() }; servers.add(srv.start()) }
    val sdk2 = newSdk(endpoint = server2.url())
    sdk2.flush()
    val lines = server2.requests.flatMap { String(gunzip(it.body)).trim().split('\n') }.filter { it.isNotBlank() }
    assertEquals(4, lines.size)
    val fail = JSONObject(lines[0])
    assertEquals("L9", fail.getString("level_id"))
    assertEquals("pve", fail.getString("game_mode"))
    val src = JSONObject(lines[1])
    assertEquals(7.0, src.getDouble("virtual_amount"))
    val ad = JSONObject(lines[2])
    assertEquals("n1", ad.getString("ad_network"))
    assertEquals("pl1", ad.getString("ad_placement"))
    assertEquals("rewarded", ad.getString("ad_format"))
    assertEquals("USD", ad.getString("revenue_currency"))
    val e = JSONObject(lines[3])
    assertEquals("p-rt", e.getString("player_id"))
    assertEquals("ord-rt", e.getString("order_id"))
    assertEquals("JPY", e.getString("revenue_currency"))
  }
}
