package io.oddsmaker.android

import io.oddsmaker.android.Oddsmaker.Event
import io.oddsmaker.android.Oddsmaker.Variant
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Android SDK JVM 直测（LAY-009 拆分）：Json 序列化反射边角（stringify 全字段/可选跳过/转义全分支、嵌套深度与条数截断、parseEvent 往返与损坏返回 null）与纯函数（hash32 跨端向量、assignVariant 规则与溢出形态对齐服务端、uuidv7 布局、gzip/体积估算）。
 */
class OddsmakerJsonPureFnsTest : OddsmakerJvmTestBase() {

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
}
