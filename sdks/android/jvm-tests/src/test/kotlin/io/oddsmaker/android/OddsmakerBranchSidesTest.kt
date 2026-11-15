package io.oddsmaker.android

import android.util.Log
import io.oddsmaker.android.Oddsmaker.Event
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any

/**
 * Android SDK JVM 直测（LAY-009 拆分）：分支对侧补充收口：identify 空串侧、flush CAS 忙侧、send 空批早退、204 无体、rejected 非对象/空 id 跳过、stringify 顶层 order/product、parseEvent 显式 null 与全可选往返、toJsonValue Java null、cached 拉取失败不落库、hashCode 碰撞 else 侧、空 list props、mergeProps 空 playerId、204 空体、单段 raw 回落拉取。
 */
class OddsmakerBranchSidesTest : OddsmakerJvmTestBase() {

  // ---------- 分支对侧补充(BRANCH 收口) ----------

  @Test
  fun identifySkipsEmptyPreviousUserIdAndEmptyPlayerId() {
    val sdk = newSdk()
    sdk.setUserId("")                       // previousUserId="" → isNullOrEmpty 空串侧
    sdk.identify("u1")
    sdk.setPlayer("")                       // playerId="" → 不并入 identifyProps
    sdk.identify("u2")
    val evts = queueJson()
    assertTrue(!evts[0].getJSONObject("props").has("previous_user_id"))
    assertTrue(!evts[1].getJSONObject("props").has("player_id"))
    assertEquals("u2", evts[1].getJSONObject("props").getString("new_user_id"))
  }

  @Test
  fun flushIsNoopWhileAlreadyFlushing() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.track("pending")
    val f = Oddsmaker::class.java.getDeclaredField("flushing").apply { isAccessible = true }
    val flag = f.get(sdk) as java.util.concurrent.atomic.AtomicBoolean
    flag.set(true)                          // 模拟并发 flush 进行中
    sdk.flush()                             // CAS false→true 失败 → 直接返回
    assertEquals(0, server.requests.size)
    flag.set(false)
  }

  @Test
  fun sendSkipsEmptyBatchDirectly() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    call("send", sdk, ArrayList<Event>())   // 私有直调:空批早退
    assertEquals(0, server.requests.size)
  }

  @Test
  fun batch204WithoutBodyTreatedAsAccepted() {
    val server = ScriptedServer().also { it.enqueue(204); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.track("no-body")
    sdk.flush()                             // OkHttp 204 → resp.body null → body?.string() null 侧
    assertEquals(1, server.requests.size)
    assertTrue(queueJson().isEmpty())       // 2xx 无 body → isNullOrEmpty 早退,视为全成功
  }

  @Test
  fun rejectedEntriesNonObjectAndBlankIdAreSkipped() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url(), debug = true)
    val idOk = sdk.track("dead")
    server.enqueue(200, """{"accepted":[],"rejected":[
      "not-an-object",
      {"event_id":"","reason":"no-id"},
      {"event_id":"$idOk","reason":"invalid_schema"}]}""")
    sdk.flush()
    assertTrue(queueJson().isEmpty())       // 非对象条目 continue;空串 id 不入 reasons
    assertTrue(Log.lines.any { it.contains(idOk) && it.contains("invalid_schema") })
  }

  @Test
  fun stringifyWritesOrderIdAndProductIdTopLevel() {
    val e = Event(event_id = "i", game_id = "g", environment = "p", event_type = "b",
      event_name = "n", device_id = "d", ts_client = 1L,
      order_id = "o-1", product_id = "p-1")
    val json = jsonInvoke("stringify", e) as String
    assertTrue(json.contains("\"order_id\":\"o-1\""))
    assertTrue(json.contains("\"product_id\":\"p-1\""))
  }

  @Test
  fun parseEventExplicitNullsAndFullOptionalsRoundTrip() {
    // 显式 JSON null:has=T 但 isNull=T → 短路返回 null(全部可选数值/字符串字段)
    val withNulls = jsonInvoke("parseEvent",
      """{"event_id":"e","game_id":"g","environment":"p","event_type":"b","event_name":"n",""" +
      """"device_id":"d","ts_client":1,"user_id":null,"order_id":null,"product_id":null,""" +
      """"revenue_amount":null,"revenue_currency":null,"item_id":null,""" +
      """"virtual_amount":null,"resource_amount":null}""") as Event
    assertNull(withNulls.user_id)
    assertNull(withNulls.order_id)
    assertNull(withNulls.product_id)
    assertNull(withNulls.revenue_amount)
    assertNull(withNulls.revenue_currency)
    assertNull(withNulls.item_id)
    assertNull(withNulls.virtual_amount)
    assertNull(withNulls.resource_amount)

    // 非 null 值 round-trip(order_id/product_id/revenue_currency/item_id 等取值侧)
    val full = jsonInvoke("parseEvent",
      """{"event_id":"e2","game_id":"g","environment":"p","event_type":"b","event_name":"n",""" +
      """"device_id":"d","ts_client":2,"order_id":"o9","product_id":"p9",""" +
      """"revenue_currency":"JPY","item_id":"sword","virtual_currency":"gem","resource_id":"gem",""" +
      """"virtual_amount":1.5,"resource_amount":2.5}""") as Event
    assertEquals("o9", full.order_id)
    assertEquals("p9", full.product_id)
    assertEquals("JPY", full.revenue_currency)
    assertEquals("sword", full.item_id)
    assertEquals("gem", full.virtual_currency)
    assertEquals("gem", full.resource_id)
    assertEquals(1.5, full.virtual_amount!!, 1e-9)
    assertEquals(2.5, full.resource_amount!!, 1e-9)
  }

  @Test
  fun toJsonValueJavaNullMapsToNull() {
    // org.json 解析路径只产生 JSONObject.NULL,Java null 仅反射直调可达
    val m = jsonObj.declaredMethods.first { it.name == "toJsonValue" && it.parameterCount == 1 }
    m.isAccessible = true
    assertNull(m.invoke(jsonObj.getDeclaredField("INSTANCE").get(null), *arrayOfNulls<Any?>(1)))
  }

  @Test
  fun fetchExperimentsCachedFetchFailureStoresNothing() {
    val dead = ScriptedServer().also { servers.add(it.start()) }
    val url = dead.url()
    dead.stop()                          // 连接拒绝 → fetchExperiments null → 不落库
    val sdk = newSdk()
    assertNull(sdk.fetchExperimentsCached(url))
    assertTrue(!prefs.store.containsKey(expsKey()))
  }

  @Test
  fun rejectedSubjectNullAndHashCollisionElseBranches() {
    // when(String) 编译为 null 检查 + hashCode switch + equals:
    // 1) batch 中未被拒绝的事件 → subject=null → 空分支(既有用例 reasons 空数组会提前 return,未达此路径)
    // 2) "kafka_errpS" 与 "kafka_error" hashCode 同为 -51759185 → switch 命中但 equals=F → else 丢弃
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url(), debug = true)
    val idA = sdk.track("accepted-one")
    val idB = sdk.track("collide")
    val collide = "kafka_errpS"
    assertEquals("kafka_error".hashCode(), collide.hashCode())
    server.enqueue(200, """{"accepted":["$idA"],"rejected":[{"event_id":"$idB","reason":"$collide"}]}""")
    sdk.flush()
    assertTrue(queueJson().isEmpty())    // idA 走 null 分支不回队;idB 走 else 分支丢弃
    assertTrue(Log.lines.any { it.contains(idB) && it.contains(collide) })
  }

  @Test
  fun emptyListPropSerializesAsEmptyArray() {
    // listToJson 空表:循环零次 + sb.last()==']' 的不裁剪侧
    val e = Event(event_id = "i", game_id = "g", environment = "p", event_type = "b",
      event_name = "n", device_id = "d", ts_client = 1L, props = mapOf("none" to emptyList<Any?>()))
    assertTrue((jsonInvoke("stringify", e) as String).contains("\"none\":[]"))
  }

  @Test
  fun mergePropsSkipsBlankPlayerId() {
    val sdk = newSdk()
    sdk.setPlayer("")                       // 空串不入 base → base 空 → props 原样返回
    sdk.track("plain", mapOf("k" to "v"))
    val props = queueJson()[0].getJSONObject("props")
    assertEquals("v", props.getString("k"))
    assertTrue(!props.has("player_id"))
  }

  @Test
  fun fetchExperiments204ReturnsEmptyBody() {
    val server = ScriptedServer().also { it.enqueue(204); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    // OkHttp 对 204 无实体响应给空实体而非 null body —— ?. null 侧在真实传输层不可达
    assertEquals("", sdk.fetchExperiments(server.url(), "game1", "prod"))
  }

  @Test
  fun fetchExperimentsCachedSingleSegmentRawFallsThroughToFetch() {
    val server = ScriptedServer().also { it.enqueue(200, """[{"id":"seg"}]"""); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    prefs.store[expsKey()] = "single-segment"   // 非空但无 \n 分隔 → parts.size==1 → 直接拉取
    assertEquals("""[{"id":"seg"}]""", sdk.fetchExperimentsCached(server.url()))
    assertTrue(prefs.store[expsKey()]!!.contains('\n'))   // 拉取成功后重新落库带时间戳
  }
}
