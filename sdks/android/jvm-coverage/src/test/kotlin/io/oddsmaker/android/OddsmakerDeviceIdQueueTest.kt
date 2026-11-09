package io.oddsmaker.android

import android.content.AndroidSim
import io.oddsmaker.android.Oddsmaker.Options
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Android SDK JVM 直测（LAY-009 拆分）：deviceId 三级链（显式优先不落 prefs/prefs 命中与生成回写）与队列持久化恢复（坏行空行丢弃、嵌套 props 公共链路恢复、默认选项、定时 flush、autoRefresh 默认间隔即时 tick、持久化与恢复故障吞错）。
 */
class OddsmakerDeviceIdQueueTest : OddsmakerJvmTestBase() {

  // ---------- deviceId 三级链 ----------

  @Test
  fun deviceIdExplicitBeatsPrefsAndIsNotStored() {
    newSdk()
    assertTrue(!prefs.store.containsKey(devKey()))   // 显式 deviceId 不落 prefs(仅生成路径写)
  }

  @Test
  fun deviceIdFallsBackToPrefsThenGenerates() {
    prefs.store[devKey()] = "dev-saved"
    val sdk = newSdk(deviceId = null)
    sdk.track("x")
    // deviceId=dev-saved → 队列 key 用它;显式 prefs 命中不再回写
    val saved = JSONObject(prefs.store["oddsmaker_queue_game1_prod_dev-saved"]!!.trim())
    assertEquals("dev-saved", saved.getString("device_id"))

    // 都无 → d_ + 32 hex 并回写
    prefs.reset(); AndroidSim.install(prefs)
    newSdk(deviceId = null)
    val gen = prefs.store[devKey()]!!
    assertTrue(gen.matches(Regex("^d_[0-9a-f]{32}$")), gen)
  }

  // ---------- 队列持久化与恢复 ----------

  @Test
  fun restoreQueueKeepsValidDropsCorruptAndIgnoresBlankLines() {
    val valid = """{"event_id":"e-restored","game_id":"game1","environment":"prod","event_type":"business",""" +
      """"event_name":"buy","device_id":"dev-explicit","ts_client":9}"""
    prefs.store[queueKey()] = "{not json\n\n$valid\n"
    val server = ScriptedServer().also { it.enqueue(); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.track("fresh")
    sdk.flush()
    assertEquals(1, server.requests.size)
    val ndjson = String(gunzip(server.requests[0].body))
    val ids = ndjson.trim().split('\n').map { JSONObject(it).getString("event_id") }
    assertEquals(2, ids.size)   // 坏行/空行丢弃,好行 + 新事件
    assertTrue("e-restored" in ids)
  }

  @Test
  fun restoreQueueParsesNestedPropsViaPublicPath() {
    // 走公共链路(init → restoreQueue → parseEvent → toMap/toListValue),嵌套 props 恢复为 Map/List
    val line = """{"event_id":"e-p","game_id":"game1","environment":"prod","event_type":"business",""" +
      """"event_name":"buy","device_id":"dev-explicit","ts_client":9,""" +
      """"props":{"tier":"gold","n":3,"ok":true,"arr":[1,"x"],"sub":{"z":"v"},"nil":null}}"""
    prefs.store[queueKey()] = line + "\n"
    newSdk()
    // 再 track 一条触发持久化,恢复事件的 props 原样写出
    val sdk2 = newSdk()
    sdk2.track("next")
    val events = (prefs.store[queueKey()] ?: "").split('\n').filter { it.isNotBlank() }
    assertEquals(2, events.size)
    val restored = JSONObject(events[0]).getJSONObject("props")
    assertEquals("gold", restored.getString("tier"))
    assertEquals(3, restored.getInt("n"))
    assertEquals(true, restored.getBoolean("ok"))
    assertEquals(1, restored.getJSONArray("arr").get(0))
    assertEquals("v", restored.getJSONObject("sub").getString("z"))
    assertTrue(restored.has("nil") && restored.isNull("nil"))
  }

  @Test
  fun defaultOptionsUseAllDefaults() {
    // 只填必填参数:deviceId=null → 生成路径,flushIntervalMs=5000 默认(构造 + shutdown 不炸)
    prefs.store["oddsmaker_device_id_game1_prod"] = "d_default"
    val sdk = Oddsmaker(FakeContext, Options(
      apiKey = "k", endpoint = "https://ing.example/", gameId = "game1", environment = "prod"))
    sdk.track("with-defaults")
    sdk.shutdown()
  }

  @Test
  fun jsonToMapToListValueToJsonValueViaReflection() {
    // Json 的私有扩展 toMap/toListValue/toJsonValue 被 org.json 成员 toMap() 遮蔽(parseEvent 走的是成员),
    // 唯一覆盖方式是反射直调。全分支:嵌套对象/数组/null/标量。
    val instance = jsonObj.getDeclaredField("INSTANCE").get(null)
    val toMap = jsonObj.getDeclaredMethod("toMap", org.json.JSONObject::class.java).apply { isAccessible = true }
    val src = JSONObject("""{"m":{"x":1},"l":[1,"y"],"n":null,"s":"str","i":5}""")
    @Suppress("UNCHECKED_CAST")
    val m = toMap.invoke(instance, src) as Map<String, Any?>
    assertEquals(1, (m["m"] as Map<*, *>)["x"])
    assertEquals(listOf(1, "y"), m["l"])
    assertNull(m["n"])   // org.json 把 JSON null 解析为 JSONObject.NULL → 映射 null
    assertEquals("str", m["s"])
    assertEquals(5, m["i"])
  }

  @Test
  fun timerTaskFlushesPeriodically() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = Oddsmaker(FakeContext, defaultOptions().copy(
      endpoint = server.url(), flushIntervalMs = 80))   // 不 shutdown,让定时器跑
    sdk.track("timer-flush")                            // maxBatch 默认 50 → 不立即 flush
    val deadline = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < deadline && server.requests.isEmpty()) Thread.sleep(20)
    sdk.shutdown()
    assertEquals(1, server.requests.size)
    assertEquals("timer-flush",
      JSONObject(String(gunzip(server.requests[0].body)).trim()).getString("event_name"))
  }

  @Test
  fun autoRefreshUsesDefaultIntervalAndTicksOnceImmediately() {
    val server = ScriptedServer().also { it.enqueue(200, """[{"id":"now"}]"""); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    val updates = mutableListOf<String>()
    val stop = sdk.startExperimentsAutoRefresh(server.url()) { updates.add(it) }   // intervalMs 默认
    val deadline = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < deadline && updates.isEmpty()) Thread.sleep(20)
    stop()
    assertEquals(listOf("""[{"id":"now"}]"""), updates)
  }

  @Test
  fun persistQueueFailureIsSwallowed() {
    val sdk = newSdk()
    prefs.failPut = true
    sdk.track("still ok")   // catch 吞掉,不炸
    assertTrue(sdk.track("again").isNotEmpty())
  }

  @Test
  fun restoreQueueFailureIsSwallowed() {
    prefs.store[devKey()] = "d_x"
    prefs.failGetKey = "oddsmaker_queue_game1_prod_d_x"
    newSdk(deviceId = null)   // getString 抛 → catch → 空队列启动
  }
}
