// Oddsmaker Android SDK 源码直测(JVM:okhttp + org.json 真实依赖,android.* 用 stub)。
// 网络用 JDK HttpServer 脚本化响应;SharedPreferences 用内存 FakePrefs(可注入故障)。
package io.oddsmaker.android

import android.content.AndroidSim
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.oddsmaker.android.Oddsmaker.Event
import io.oddsmaker.android.Oddsmaker.Options
import io.oddsmaker.android.Oddsmaker.Variant
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

// ---------- 测试替身 ----------

class FakePrefs : SharedPreferences {
  val store = LinkedHashMap<String, String>()
  var failGetKey: String? = null
  var failPut = false

  override fun getString(key: String?, defValue: String?): String? {
    if (key == failGetKey) throw RuntimeException("get boom: $key")
    return store[key] ?: defValue
  }

  override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
      if (failPut) throw RuntimeException("put boom")
      store[key!!] = value!!
      return this
    }
    override fun apply() {}
  }

  fun reset() { store.clear(); failGetKey = null; failPut = false }
}

private object FakeContext : Context()

/** 脚本化 HttpServer:按请求顺序出队响应(status,body);记录全部请求供断言。 */
private class ScriptedServer {
  class Resp(val status: Int, val body: String)

  class Recorded(var method: String = "", var path: String = "",
                 val headers: LinkedHashMap<String, String> = LinkedHashMap(), var body: ByteArray = ByteArray(0))

  val requests = mutableListOf<Recorded>()
  private val script = mutableListOf<Resp>()
  private var server: HttpServer? = null

  fun url(): String = "http://127.0.0.1:${server!!.address.port}"

  fun enqueue(status: Int = 200, body: String = "") { script.add(Resp(status, body)) }

  fun start(): ScriptedServer {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { ex: HttpExchange ->
      val rec = Recorded()
      rec.method = ex.requestMethod
      rec.path = ex.requestURI.path
      ex.requestHeaders.forEach { (k, v) -> rec.headers[k.lowercase()] = v.joinToString(",") }
      rec.body = ex.requestBody.readBytes()
      synchronized(requests) { requests.add(rec) }
      val resp = synchronized(script) { if (script.isNotEmpty()) script.removeAt(0) else Resp(200, "") }
      val bytes = resp.body.toByteArray()
      ex.sendResponseHeaders(resp.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
      ex.close()
    }
    s.start()
    server = s
    return this
  }

  fun stop() { server?.stop(0) }
}

class OddsmakerJvmTests {

  private lateinit var prefs: FakePrefs
  private val servers = mutableListOf<ScriptedServer>()

  private val jsonObj by lazy {
    Oddsmaker::class.java.declaredClasses.first { it.simpleName == "Json" }
  }

  private fun jsonInvoke(name: String, arg: Any?): Any? {
    val instance = jsonObj.getDeclaredField("INSTANCE").get(null)
    val method = jsonObj.declaredMethods.first { it.name == name && it.parameterCount == 1 }
    method.isAccessible = true
    return method.invoke(instance, arg)
  }

  /** 按名称+参数个数反射调用 Oddsmaker 私有成员。 */
  private fun call(name: String, target: Any? = null, vararg args: Any?): Any? {
    val method = Oddsmaker::class.java.declaredMethods.first {
      it.name == name && it.parameterCount == args.size
    }
    method.isAccessible = true
    return method.invoke(target, *args)
  }

  @BeforeEach
  fun setUp() {
    if (!::prefs.isInitialized) prefs = FakePrefs()
    prefs.reset()
    AndroidSim.install(prefs)
    Log.reset()
  }

  @AfterEach
  fun tearDown() { servers.forEach { it.stop() } }

  private fun defaultOptions() = Options(
    apiKey = "key-1", endpoint = "https://ing.example/", gameId = "game1",
    environment = "prod", deviceId = "dev-explicit", flushIntervalMs = 3_600_000
  )

  private fun newSdk(
    endpoint: String = "https://ing.example/",
    deviceId: String? = "dev-explicit",
    maxBatch: Int = 50,
    maxQueueBytes: Int = 512_000,
    sessionGapMs: Long = 30 * 60 * 1000,
    debug: Boolean = false
  ): Oddsmaker = Oddsmaker(FakeContext, defaultOptions().copy(
    endpoint = endpoint, deviceId = deviceId, maxBatch = maxBatch,
    maxQueueBytes = maxQueueBytes, sessionGapMs = sessionGapMs, debug = debug
  )).also { it.shutdown() }   // 关掉后台定时器,统一显式 flush

  private fun devKey() = "oddsmaker_device_id_game1_prod"
  private fun queueKey() = "oddsmaker_queue_game1_prod_dev-explicit"
  private fun expsKey() = "oddsmaker_experiments_game1_prod"

  private fun queueJson(): List<JSONObject> =
    (prefs.store[queueKey()] ?: "").split('\n').filter { it.isNotBlank() }.map { JSONObject(it) }

  private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).readBytes()

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

  // ---------- flush / send ----------

  @Test
  fun autoFlushOnMaxBatchSendsGzipNdjson() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url(), maxBatch = 1)
    sdk.track("boot")
    assertEquals(1, server.requests.size)
    val req = server.requests[0]
    assertEquals("POST", req.method)
    assertEquals("/v1/batch", req.path)
    assertEquals("key-1", req.headers["x-api-key"])
    assertEquals("application/x-ndjson", req.headers["content-type"])
    assertEquals("gzip", req.headers["content-encoding"])
    assertEquals(0x1f, req.body[0].toInt() and 0xff)
    val ndjson = String(gunzip(req.body))
    assertEquals("boot", JSONObject(ndjson.trim()).getString("event_name"))
    assertEquals("", prefs.store[queueKey()]!!.trim())   // 队列已清空
  }

  @Test
  fun autoFlushOnQueueBytes() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url(), maxQueueBytes = 1)
    sdk.track("big")
    assertEquals(1, server.requests.size)
  }

  @Test
  fun flushWithEmptyQueueIsNoop() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.flush()
    assertEquals(0, server.requests.size)
  }

  @Test
  fun http500RestoresQueueForRetry() {
    val server = ScriptedServer().also { it.enqueue(500); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url(), maxBatch = 1)
    sdk.track("lost")
    assertEquals(1, server.requests.size)
    assertEquals(1, queueJson().size)   // 还原后 persistQueue 仍含事件
    assertEquals("lost", queueJson()[0].getString("event_name"))
  }

  @Test
  fun connectionFailureRestoresQueue() {
    val dead = ScriptedServer().also { servers.add(it.start()) }
    val url = dead.url()
    dead.stop()   // 端口已关 → okhttp 抛 → catch
    val sdk = newSdk(endpoint = url, maxBatch = 1)
    sdk.track("offline")
    assertEquals(1, queueJson().size)
  }

  @Test
  fun debugLogsQueuedEvent() {
    val sdk = newSdk(debug = true)
    sdk.track("dbg")
    assertTrue(Log.lines.any { it.contains("queued") && it.contains("dbg") })
  }

  @Test
  fun shutdownIsIdempotent() {
    val sdk = Oddsmaker(FakeContext, defaultOptions())
    sdk.shutdown()
    sdk.shutdown()
  }

  // ---------- Json 序列化边角(反射) ----------

  @Test
  fun stringifyFullFieldsOptionalSkipsAndEscapes() {
    val full = Event(
      event_id = "id1", game_id = "g", environment = "prod", event_type = "business", event_name = "n",
      user_id = "u", device_id = "d", session_id = "s", ts_client = 1L, platform = "android",
      app_version = "1.0", country = "CN", revenue_amount = 1.5, revenue_currency = "USD",
      props = mapOf("k" to "v")
    )
    val json = jsonInvoke("stringify", full) as String
    assertEquals("""{"event_id":"id1","game_id":"g","environment":"prod","event_type":"business",""" +
      """"event_name":"n","user_id":"u","device_id":"d","session_id":"s","ts_client":1,"platform":"android",""" +
      """"app_version":"1.0","country":"CN","revenue_amount":1.5,"revenue_currency":"USD","props":{"k":"v"}}""", json)

    val minimal = Event(event_id = "i", game_id = "g", environment = "p", event_type = "b",
      event_name = "n", device_id = "d", ts_client = 1L)
    val minimalJson = jsonInvoke("stringify", minimal) as String
    assertEquals("""{"event_id":"i","game_id":"g","environment":"p","event_type":"b",""" +
      """"event_name":"n","device_id":"d","ts_client":1,"platform":"android"}""", minimalJson)

    // 空 props map → 尾逗号不裁剪分支(last == '}')
    val emptyProps = minimal.copy(props = emptyMap())
    assertTrue((jsonInvoke("stringify", emptyProps) as String).endsWith("\"props\":{}}"))

    // escape 全分支:反斜杠/引号/换行/回车/Tab/控制字符
    val esc = minimal.copy(event_name = "a\\b\"c\nd\re\tfg")
    assertEquals("a\\b\"c\nd\re\tf\u0001g",
      JSONObject(jsonInvoke("stringify", esc) as String).getString("event_name"))
  }

  @Test
  fun stringifyValueBranchesAndDepthAndCountLimits() {
    val base = Event(event_id = "i", game_id = "g", environment = "p", event_type = "b",
      event_name = "n", device_id = "d", ts_client = 1L)
    val props = mapOf<String, Any?>(
      "nil" to null, "b" to true, "n" to 2.5, "s" to "x",
      "m" to mapOf("in" to "v"), "l" to listOf(1, "y"),
      "deep" to mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to 1)))),   // 第 4 层 → {}
      "deepList" to listOf(listOf(listOf(listOf(1)))),                       // 第 4 层 → []
      "obj" to object { override fun toString() = "custom-object" },         // else 分支 → toString
      "long" to listOf((1..60).map { it })                                   // 50 条截断
    )
    val json = jsonInvoke("stringify", base.copy(props = props)) as String
    assertTrue(json.contains("\"nil\":null"))
    assertTrue(json.contains("\"b\":true"))
    assertTrue(json.contains("\"n\":2.5"))
    assertTrue(json.contains("\"m\":{\"in\":\"v\"}"))
    assertTrue(json.contains("\"l\":[1,\"y\"]"))
    assertTrue(json.contains("\"deep\":{\"a\":{\"b\":{}}}"))   // 第 3 层截断为 {}
    assertTrue(json.contains("\"deepList\":[[[]]]"))   // 4 层数据,第 3 层起截为 []
    assertTrue(json.contains("\"obj\":\"custom-object\""))
    val o = JSONObject(json).getJSONObject("props")
    assertEquals(50, o.getJSONArray("long").getJSONArray(0).length())   // 双层:外层 1,内层截 50
    assertTrue(!json.contains(",51,"))
  }

  @Test
  fun parseEventRoundTripsAndReturnsNullOnCorrupt() {
    val line = """{"event_id":"e1","game_id":"g","environment":"prod","event_type":"business",""" +
      """"event_name":"buy","user_id":"u","device_id":"d","session_id":"s","ts_client":1700000000000,""" +
      """"platform":"android","revenue_amount":9.99,"revenue_currency":"USD",""" +
      """"props":{"tier":"gold","n":3,"ok":true,"arr":[1,"x"],"sub":{"z":"v"},"nil":null}}"""
    val e = jsonInvoke("parseEvent", line) as Event
    assertEquals("e1", e.event_id)
    assertEquals("u", e.user_id)
    assertEquals(1700000000000L, e.ts_client)
    assertEquals(9.99, e.revenue_amount!!, 1e-9)
    assertEquals("USD", e.revenue_currency)
    @Suppress("UNCHECKED_CAST")
    val props = e.props as Map<String, Any?>
    assertEquals("gold", props["tier"])
    assertEquals(3, props["n"])
    assertEquals(true, props["ok"])
    assertEquals(listOf(1, "x"), props["arr"])
    assertEquals(mapOf("z" to "v"), props["sub"])
    assertNull(props["nil"])

    // 坏行 / 缺必填 → null
    assertNull(jsonInvoke("parseEvent", "{not json"))
    assertNull(jsonInvoke("parseEvent", """{"game_id":"g"}"""))
    // 可选字段缺失 → null(optStringOrNull / revenue null 分支)
    val sparse = jsonInvoke("parseEvent",
      """{"event_id":"e2","game_id":"g","environment":"p","event_type":"b","event_name":"n","device_id":"d","ts_client":1}""") as Event
    assertNull(sparse.user_id)
    assertNull(sparse.session_id)
    assertNull(sparse.app_version)
    assertNull(sparse.country)
    assertNull(sparse.revenue_amount)
    assertNull(sparse.revenue_currency)
    assertNull(sparse.props)
  }

  // ---------- 纯函数 ----------

  @Test
  fun hash32MatchesCrossSdkVectors() {
    val sdk = newSdk()
    fun h(s: String) = (call("hash32", sdk, s) as Int).toLong() and 0xffffffffL
    assertEquals(0x811c9dc5L, h(""))
    assertEquals(0xe40c292cL, h("a"))
    assertEquals(0xbf9cf968L, h("foobar"))
  }

  @Test
  fun assignVariantRules() {
    val sdk = newSdk()
    assertEquals("A", sdk.assignVariant("e", "s", emptyList(), "k"))
    assertEquals("B", sdk.assignVariant("e", "s", listOf(Variant("B", 0)), "k"))   // weight<=0 按 1
    val variants = listOf(Variant("A", 50), Variant("B", 50))
    val pick = sdk.assignVariant("exp", "s", variants, "u1")
    assertTrue(pick in listOf("A", "B"))
    assertEquals(pick, sdk.assignVariant("exp", "s", variants, "u1"))
    assertTrue(sdk.assignVariant("exp", null, variants, "u1") in listOf("A", "B"))
  }

  @Test
  fun assignVariantOverflowMatchesServer() {
    // 与服务端 ExperimentSplitterTest 同向量（跨端一致锚定）：溢出形态下统一为
    // 无符号取模 + int32 落位 + 兜底末变体
    val sdk = newSdk()
    // [2, MAX]：sum 溢出为负，u1 的 h=507466947 越过前缀和 → 兜底 last（原实现返回 first 漂移）
    val two = listOf(Variant("control", 2), Variant("treatment", Int.MAX_VALUE))
    assertEquals("treatment", sdk.assignVariant("exp", "salt", two, "u1"))
    // 3×MAX：sum 回绕 2147483645 > 0（合法可创建形态），h=507466949 < MAX → control
    val three = listOf(
      Variant("control", Int.MAX_VALUE),
      Variant("treat-a", Int.MAX_VALUE),
      Variant("treat-b", Int.MAX_VALUE))
    assertEquals("control", sdk.assignVariant("exp", "salt", three, "u1"))
    assertEquals("control", sdk.assignVariant("exp", "salt", three, "u8"))
    // [MAX, MAX, 2]：sum 回绕恰为 0，取模无定义 → 兜底 last 不抛 ArithmeticException（原实现崩溃）
    val zero = listOf(
      Variant("control", Int.MAX_VALUE),
      Variant("treat-a", Int.MAX_VALUE),
      Variant("treat-b", 2))
    assertEquals("treat-b", sdk.assignVariant("exp", "salt", zero, "u1"))
  }

  @Test
  fun uuidv7Layout() {
    val sdk = newSdk()
    val id = call("uuidv7", sdk) as String
    assertEquals(36, id.length)
    val parts = id.split('-')
    assertEquals(listOf(8, 4, 4, 4, 12), parts.map { it.length })
    assertEquals('7', parts[2][0])
    val ts = (parts[0] + parts[1]).toLong(16)
    assertTrue(Math.abs(ts - System.currentTimeMillis()) < 10_000)
    assertTrue(id != call("uuidv7", sdk))
  }

  @Test
  fun gzipAndEstimateSize() {
    val sdk = newSdk()
    val gz = call("gzip", sdk, "hello".toByteArray()) as ByteArray
    assertEquals(0x1f, gz[0].toInt() and 0xff)
    assertEquals(0x8b, gz[1].toInt() and 0xff)
    assertEquals("hello", String(gunzip(gz)))

    val e = Event(event_id = "i", game_id = "g", environment = "p", event_type = "b",
      event_name = "n", device_id = "d", ts_client = 1L)
    val est = call("estimateSize", sdk, e) as Int
    assertEquals((jsonInvoke("stringify", e) as String).toByteArray().size + 1, est)
  }

  // ---------- 实验配置 ----------

  @Test
  fun fetchExperimentsSuccessFailureAndConnectionError() {
    val server = ScriptedServer().also { it.enqueue(200, """[{"id":"e1"}]"""); it.enqueue(500); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())

    assertEquals("""[{"id":"e1"}]""", sdk.fetchExperiments(server.url(), "game1", "prod"))
    assertEquals("/api/config/game1/prod", server.requests[0].path)
    assertEquals("GET", server.requests[0].method)
    assertNull(sdk.fetchExperiments(server.url(), "game1", "prod"))   // 500 → null

    val dead = ScriptedServer().also { servers.add(it.start()) }
    val url = dead.url(); dead.stop()
    assertNull(sdk.fetchExperiments(url, "g", "p"))                    // 连接失败 → catch → null
  }

  @Test
  fun cachedExperimentsMissHitAndCorrupt() {
    val sdk = newSdk()
    assertNull(sdk.getCachedExperiments())                       // 无缓存
    prefs.store[expsKey()] = "123\n[{\"id\":\"c\"}]"
    assertEquals("""[{"id":"c"}]""", sdk.getCachedExperiments())   // 命中
    prefs.store[expsKey()] = "single-segment"                    // 无换行分隔 → null
    assertNull(sdk.getCachedExperiments())
    prefs.failGetKey = expsKey()                                 // 读取抛错 → catch → null
    assertNull(sdk.getCachedExperiments())
  }

  @Test
  fun fetchExperimentsCachedFetchesAndStoresWhenNoCache() {
    val server = ScriptedServer().also { it.enqueue(200, """[{"id":"fresh"}]"""); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    val js = sdk.fetchExperimentsCached(server.url())
    assertEquals("""[{"id":"fresh"}]""", js)
    val stored = prefs.store[expsKey()]!!
    assertTrue(stored.contains("\n"), stored)
    assertEquals("""[{"id":"fresh"}]""", stored.substringAfter('\n'))
    assertTrue(stored.substringBefore('\n').toLong() > 0)
  }

  @Test
  fun fetchExperimentsCachedReturnsCacheWithinTtlAndRefreshesInBackground() {
    val server = ScriptedServer().also {
      it.enqueue(200, """[{"id":"bg"}]""")   // 供后台线程拉取
      servers.add(it.start())
    }
    val sdk = newSdk(endpoint = server.url())
    val now = System.currentTimeMillis()
    prefs.store[expsKey()] = "$now\n[{\"id\":\"cached\"}]"
    assertEquals("""[{"id":"cached"}]""", sdk.fetchExperimentsCached(server.url(), ttlMs = 60_000))
    // 等后台刷新线程落库(最多 5s)
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline &&
      prefs.store[expsKey()]?.contains("bg") != true) Thread.sleep(20)
    assertTrue(prefs.store[expsKey()]!!.contains("bg"))
  }

  @Test
  fun fetchExperimentsCachedRefetchesWhenStaleOrBadTimestamp() {
    val server = ScriptedServer().also {
      it.enqueue(200, """[{"id":"refetched"}]""")
      it.enqueue(200, """[{"id":"ts-not-number"}]""")
      servers.add(it.start())
    }
    val sdk = newSdk(endpoint = server.url())
    prefs.store[expsKey()] = "1000\n[{\"id\":\"old\"}]"   // 过期
    assertEquals("""[{"id":"refetched"}]""", sdk.fetchExperimentsCached(server.url()))

    prefs.store[expsKey()] = "abc\n[{\"id\":\"old\"}]"    // ts 非数字 → 0 → 视为过期
    assertEquals("""[{"id":"ts-not-number"}]""", sdk.fetchExperimentsCached(server.url()))
  }

  @Test
  fun fetchExperimentsCachedFallsBackWhenPrefsReadFails() {
    val server = ScriptedServer().also { it.enqueue(200, """[{"id":"direct"}]"""); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    prefs.store[expsKey()] = "whatever"
    prefs.failGetKey = expsKey()   // getString 抛 → catch → 直接拉取
    assertEquals("""[{"id":"direct"}]""", sdk.fetchExperimentsCached(server.url()))
  }

  @Test
  fun autoRefreshTicksThenStopsAndSkipsCallbackOnFailure() {
    val server = ScriptedServer().also {
      it.enqueue(200, """[{"id":"t1"}]""")
      it.enqueue(500)                       // tick 失败 → onUpdate 不调
      it.enqueue(200, """[{"id":"t2"}]""")
      it.enqueue(200, """[{"id":"t3"}]""")  // stop 后不应到达
      servers.add(it.start())
    }
    val sdk = newSdk(endpoint = server.url())
    val updates = mutableListOf<String>()
    // 间隔 300ms:tick0→t1、tick300→500、tick600→t2 后立即 stop,tick900 前有充足余量,
    // 避免全量套件负载下 stop 前第 4 个 tick 抢跑(50ms 时实测会偶发)
    val stop = sdk.startExperimentsAutoRefresh(server.url(), intervalMs = 300) { updates.add(it) }
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline && updates.size < 2) Thread.sleep(20)
    stop()
    val countAfterStop = server.requests.size
    Thread.sleep(400)
    assertEquals(countAfterStop, server.requests.size)   // stop 后不再拉取
    assertTrue(updates.contains("""[{"id":"t1"}]"""))
    assertTrue(updates.contains("""[{"id":"t2"}]"""))
    assertTrue(!updates.contains("""[{"id":"t3"}]"""))
    stop()   // 幂等
  }

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

  // ---------- INSTR 收口:$default 桥默认值体 / 网络边路 / gzip 失败回退 ----------

  @Test
  fun defaultParamsCoverQuantityDefaultBridge() {
    // 单参调用:quantity/props 均省略 → itemGrant/itemConsume 的 $default 桥内
    // quantity=1 默认赋值体执行(两参调用只覆盖桥的 else 路)
    val sdk = newSdk()
    sdk.itemGrant("sword")
    sdk.itemConsume("sword")
    val events = queueJson().map { it.getString("item_id") to it.getDouble("resource_amount") }
    assertEquals(listOf("sword" to 1.0, "sword" to 1.0), events)   // 默认 quantity=1 生效
  }

  @Test
  fun batchResponse204EmptyBodyTreatedAsSuccess() {
    // send 路径 204:isSuccessful true → handleBatchResponse 收到空 body
    // → isNullOrEmpty 早退(事件不回队,区别于非 2xx 的回队路径)
    val server = ScriptedServer().also { it.enqueue(204, ""); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.track("no-body")
    sdk.flush()
    assertEquals(1, server.requests.size)
    assertTrue(queueJson().isEmpty())   // 204 成功:批次不回填
  }

  @Test
  fun fetchExperimentsReturnsNullOnNon2xx() {
    val server = ScriptedServer().also { it.enqueue(404, "nope"); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    assertNull(sdk.fetchExperiments(server.url(), "game1", "prod"))
    assertEquals("/api/config/game1/prod", server.requests[0].path)
  }

  @Test
  fun sendConnectionRefusedRefillsQueue() {
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    server.stop()   // 连接拒绝 → execute 抛 → catch 回队
    sdk.track("refused")
    sdk.flush()     // 不抛:异常被 send 的 catch 吞
    assertEquals(1, queueJson().size)   // 批次回填并持久化
    assertEquals("refused", queueJson()[0].getString("event_name"))
  }

  @Test
  fun timerTaskRunSwallowsFlushFailure() {
    // 非法 URL:send 内 Request.Builder().url() 抛 IAE,冒泡出 flush 的 try
    // → TimerTask.run 的 catch 吞掉(TimerThread 存活,后续周期照常调度)
    val sdk = Oddsmaker(FakeContext, defaultOptions().copy(
      endpoint = "not-a-url", flushIntervalMs = 80))   // 不 shutdown,让定时器跑
    sdk.track("boom")
    Thread.sleep(400)
    assertTrue(queueJson().isEmpty())   // 第一轮:取出 → send 抛 → finally 持久化空队列
    sdk.track("still-alive")
    Thread.sleep(400)
    assertTrue(queueJson().isEmpty())   // 第二轮照常执行 → catch 吞错未杀 TimerThread
    sdk.shutdown()
  }

  @Test
  fun gzipFailureFallsBackToPlainNdjson() {
    // GZIPOutputStream.write 注入 IOException → runCatching 捕获 → getOrNull null
    // → 明文回退(不加 content-encoding 头,body 不经 gunzip)
    val server = ScriptedServer().also { servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    Mockito.mockConstruction(GZIPOutputStream::class.java) { mock, _ ->
      Mockito.doThrow(IOException("zip boom")).`when`(mock).write(any<ByteArray>())
    }.use {
      sdk.track("plain-fallback")
      sdk.flush()
    }
    assertEquals(1, server.requests.size)
    assertNull(server.requests[0].headers["content-encoding"])   // 明文:未加 gzip 头
    assertEquals("plain-fallback",
      JSONObject(String(server.requests[0].body).trim()).getString("event_name"))
  }

  /** 声明 Content-Length 但只写一半 → 客户端读 body 时 EOF:驱动 string() 在 use 体内抛的路径。 */
  private fun truncatedServer(): HttpServer {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { ex ->
      ex.sendResponseHeaders(200, 1_000L)   // 声明 1000 字节
      ex.responseBody.use { it.write(ByteArray(10)) }   // 只写 10 → 提前 EOF
      ex.close()
    }
    s.start()
    return s
  }

  @Test
  fun sendTruncatedBodyRefillsQueue() {
    // 200 + 截断体:string() 在 use 体内抛 IOException(区别于 execute 阶段的连接拒绝,
    // 会经过 use 的异常传播段 closeFinally 后 rethrow)→ 外层 catch 回队
    val server = truncatedServer()
    try {
      val sdk = newSdk(endpoint = "http://127.0.0.1:${server.address.port}/")
      sdk.track("truncated")
      sdk.flush()   // 不抛:string() 的 IOException 被 send 的 catch 吞
      assertEquals(1, queueJson().size)   // 批次回填
      assertEquals("truncated", queueJson()[0].getString("event_name"))
    } finally { server.stop(0) }
  }

  @Test
  fun fetchExperimentsTruncatedBodyReturnsNull() {
    // 200 + 截断体:string() 在 use 体内抛 → 异常传播段 → catch(_ : Throwable) → null
    val server = truncatedServer()
    try {
      val sdk = newSdk()
      assertNull(sdk.fetchExperiments(
        "http://127.0.0.1:${server.address.port}/", "game1", "prod"))
    } finally { server.stop(0) }
  }

  @Test
  fun handleBatchResponseNonJsonBodyIgnored() {
    // 2xx + 坏 JSON 文本("{{{" 解析中途 EOF 必抛;"not-json" 会被 org.json 20240303 宽松接受):
    // JSONObject 构造抛 → handleBatchResponse 的 catch 早退(事件不回队)
    val server = ScriptedServer().also { it.enqueue(200, "{{{"); servers.add(it.start()) }
    val sdk = newSdk(endpoint = server.url())
    sdk.track("opaque")
    sdk.flush()
    assertEquals(1, server.requests.size)
    assertTrue(queueJson().isEmpty())   // 解析失败按全成功处理:不回队
  }
}
