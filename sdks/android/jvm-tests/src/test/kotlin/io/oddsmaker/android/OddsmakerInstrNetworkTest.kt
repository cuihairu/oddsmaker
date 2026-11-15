package io.oddsmaker.android

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * Android SDK JVM 直测（LAY-009 拆分）：INSTR 收口：$default 桥默认值体、send 路径 204、非 2xx 返回 null、连接拒绝回队、TimerTask 吞 flush 失败保 TimerThread、gzip 失败明文回退、截断体回队与 fetch null、handleBatchResponse 坏 JSON 早退。
 */
class OddsmakerInstrNetworkTest : OddsmakerJvmTestBase() {

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
