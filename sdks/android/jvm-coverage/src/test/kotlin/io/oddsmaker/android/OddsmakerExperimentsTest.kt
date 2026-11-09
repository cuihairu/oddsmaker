package io.oddsmaker.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Android SDK JVM 直测（LAY-009 拆分）：实验配置拉取成功/失败/连接错误、缓存未命中/命中/损坏、cached 首拉落库、TTL 命中后台刷新、过期与坏时间戳重拉、prefs 读失败兜底直拉、autoRefresh tick-失败跳回调-停止幂等。
 */
class OddsmakerExperimentsTest : OddsmakerJvmTestBase() {

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
}
