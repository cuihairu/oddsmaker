package io.oddsmaker.android

import android.util.Log
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any

/**
 * Android SDK JVM 直测（LAY-009 拆分）：maxBatch/队列字节触发的自动 flush（gzip NDJSON 头与队列清空）、空队列 noop、HTTP 500 回队重试、连接失败回队、debug 日志、shutdown 幂等。
 */
class OddsmakerFlushSendTest : OddsmakerJvmTestBase() {

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
}
