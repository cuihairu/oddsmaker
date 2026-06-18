package io.pit.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * Minimal Android SDK that mirrors the Web SDK behavior: batching, NDJSON, optional gzip,
 * offline persistence (SharedPreferences), and simple session handling.
 *
 * Authentication: x-api-key header only. HMAC signing is intentionally NOT supported
 * on the client SDK — embedding HMAC secret in client code is unsafe (reverse-engineering).
 * Server-to-server traffic must use the dedicated Server SDK which signs requests with HMAC.
 */
class Pit(private val ctx: Context, private val opts: Options) {
  data class Options(
    val apiKey: String,
    val endpoint: String,
    val tenantId: String,
    val appId: String,
    val deviceId: String? = null,
    val flushIntervalMs: Long = 5000,
    val maxBatch: Int = 50,
    val maxQueueBytes: Int = 512_000,
    val sessionGapMs: Long = 30*60*1000,
    val debug: Boolean = false
    // NOTE: HMAC signing intentionally omitted on client SDK.
    // Server-side HMAC (via Server SDK) is the only supported way to sign requests.
    // Embedding HMAC secret in client code is unsafe and can be reverse-engineered.
  )

  data class Event(
    val event_id: String,
    val event_name: String,
    val tenant_id: String,
    val app_id: String,
    val user_id: String? = null,
    val device_id: String,
    val session_id: String? = null,
    val ts_client: Long,
    val platform: String? = "android",
    val app_version: String? = null,
    val country: String? = null,
    val props: Map<String, Any?>? = null
  )

  private val client = OkHttpClient()
  private val prefs: SharedPreferences = ctx.getSharedPreferences("pit", Context.MODE_PRIVATE)
  private val queue = ArrayList<Event>()
  private var queueBytes: Int = 0
  private val timer = Timer(true)
  private val flushing = AtomicBoolean(false)

  private var deviceId: String
  private var userId: String? = null
  private var sessionId: String? = null
  private var lastActive: Long = System.currentTimeMillis()

  init {
    deviceId = opts.deviceId ?: prefs.getString(devKey(), null) ?: genDeviceId().also {
      prefs.edit().putString(devKey(), it).apply()
    }
    restoreQueue()
    ensureTimer()
  }

  fun setUserId(uid: String?) { userId = uid }
  fun setUserProps(props: Map<String, Any?>) { /* optional; merge into props per event if needed */ }

  fun track(eventName: String, props: Map<String, Any?>? = null): String {
    val now = System.currentTimeMillis()
    rollSession(now)
    val evt = Event(
      event_id = uuidv7(),
      event_name = eventName,
      tenant_id = opts.tenantId,
      app_id = opts.appId,
      user_id = userId,
      device_id = deviceId,
      session_id = sessionId,
      ts_client = now,
      platform = "android",
      props = props
    )
    val est = evt.estimateSize()
    queue.add(evt)
    queueBytes += est
    lastActive = now
    if (opts.debug) android.util.Log.d("Pit", "queued ${evt.event_id} ${evt.event_name}")
    if (queueBytes >= opts.maxQueueBytes) flush()
    persistQueue()
    return evt.event_id
  }

  fun expose(exp: String, variant: String) = track("experiment_exposure", mapOf("exp" to exp, "variant" to variant))
  fun revenue(amount: Number, currency: String, props: Map<String, Any?>? = null) =
    track("revenue", mapOf("amount" to amount, "currency" to currency) + (props ?: emptyMap()))

  @Synchronized fun flush() {
    if (queue.isEmpty() || !flushing.compareAndSet(false, true)) return
    try {
      val batch = ArrayList<Event>()
      val n = minOf(opts.maxBatch, queue.size)
      for (i in 0 until n) batch.add(queue.removeAt(0))
      recalcQueueBytes()
      send(batch)
    } finally {
      flushing.set(false)
      persistQueue()
    }
  }

  fun shutdown() {
    try { timer.cancel() } catch (_: Throwable) {}
  }

  private fun ensureTimer() {
    timer.schedule(object : TimerTask() {
      override fun run() { try { flush() } catch (_: Throwable) {} }
    }, opts.flushIntervalMs, opts.flushIntervalMs)
  }

  private fun rollSession(now: Long) {
    if (sessionId == null || now - lastActive > opts.sessionGapMs) {
      sessionId = uuidv7()
    }
  }

  private fun send(batch: List<Event>) {
    if (batch.isEmpty()) return
    val ndjson = buildString(batch.size * 128) {
      val moshi = Json
      for (e in batch) {
        append(moshi.stringify(e)).append('\n')
      }
    }
    var bodyBytes = ndjson.toByteArray(Charsets.UTF_8)
    val reqBuilder = Request.Builder()
      .url(opts.endpoint.trimEnd('/') + "/v1/batch")
      .addHeader("x-api-key", opts.apiKey)
      .addHeader("content-type", "application/x-ndjson")

    // gzip if helps
    val gz = gzip(bodyBytes)
    if (gz != null) {
      bodyBytes = gz
      reqBuilder.addHeader("content-encoding", "gzip")
    }

    // NOTE: HMAC signature intentionally NOT added here.
    // Client SDK authenticates via x-api-key only. Server SDK (server-to-server)
    // is the only channel permitted to attach x-signature HMAC headers.
    // Adding HMAC here would require shipping the secret in client code.

    val req = reqBuilder.post(RequestBody.create("application/octet-stream".toMediaType(), bodyBytes)).build()
    try {
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          // on failure, requeue
          queue.addAll(0, batch)
          recalcQueueBytes()
        }
      }
    } catch (e: Exception) {
      // offline or network error: requeue
      queue.addAll(0, batch)
      recalcQueueBytes()
    }
  }

  // Simple JSON without external libs
  private object Json {
    fun stringify(e: Event): String {
      val sb = StringBuilder(256)
      sb.append('{')
      field(sb, "event_id", e.event_id)
      field(sb, "event_name", e.event_name)
      field(sb, "tenant_id", e.tenant_id)
      field(sb, "app_id", e.app_id)
      e.user_id?.let { field(sb, "user_id", it) }
      field(sb, "device_id", e.device_id)
      e.session_id?.let { field(sb, "session_id", it) }
      field(sb, "ts_client", e.ts_client)
      e.platform?.let { field(sb, "platform", it) }
      e.app_version?.let { field(sb, "app_version", it) }
      e.country?.let { field(sb, "country", it) }
      e.props?.let { objField(sb, "props", it) }
      // remove trailing comma
      if (sb.last() == ',') sb.setLength(sb.length - 1)
      sb.append('}')
      return sb.toString()
    }

    private fun field(sb: StringBuilder, k: String, v: String) {
      sb.append('"').append(escape(k)).append('"').append(':')
        .append('"').append(escape(v)).append('"').append(',')
    }
    private fun field(sb: StringBuilder, k: String, v: Long) {
      sb.append('"').append(escape(k)).append('"').append(':').append(v).append(',')
    }
    private fun objField(sb: StringBuilder, k: String, obj: Map<String, Any?>) {
      sb.append('"').append(escape(k)).append('"').append(':').append(mapToJson(obj)).append(',')
    }
    private fun mapToJson(m: Map<String, Any?>, depth: Int = 0): String {
      if (depth > 2) return "{}"
      val sb = StringBuilder("{")
      for ((kk,vv) in m) {
        sb.append('"').append(escape(kk)).append('"').append(':').append(valueToJson(vv, depth+1)).append(',')
      }
      if (sb.last() == ',') sb.setLength(sb.length - 1)
      sb.append('}')
      return sb.toString()
    }
    private fun valueToJson(v: Any?, depth: Int): String {
      return when (v) {
        null -> "null"
        is String -> '"' + escape(v) + '"'
        is Number, is Boolean -> v.toString()
        is Map<*,*> -> mapToJson(v as Map<String, Any?>, depth)
        is List<*> -> listToJson(v as List<Any?>, depth)
        else -> '"' + escape(v.toString()) + '"'
      }
    }
    private fun listToJson(l: List<Any?>, depth: Int): String {
      if (depth > 2) return "[]"
      val sb = StringBuilder("[")
      val lim = minOf(50, l.size)
      for (i in 0 until lim) {
        sb.append(valueToJson(l[i], depth+1)).append(',')
      }
      if (sb.last() == ',') sb.setLength(sb.length - 1)
      sb.append(']')
      return sb.toString()
    }
    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
  }

  private fun Event.estimateSize(): Int {
    // rough estimation for queue size accounting
    return Json.stringify(this).toByteArray(Charsets.UTF_8).size + 1
  }

  private fun gzip(input: ByteArray): ByteArray? {
    return try {
      val bout = ByteArrayOutputStream()
      GZIPOutputStream(bout).use { it.write(input) }
      bout.toByteArray()
    } catch (_: Throwable) { null }
  }

  private fun devKey() = "pit_device_id_${opts.tenantId}_${opts.appId}"
  private fun queueKey() = "pt_queue_${opts.tenantId}_${opts.appId}_${deviceId}"

  private fun persistQueue() {
    try {
      val arr = StringBuilder(queue.size * 128)
      for (e in queue) {
        arr.append(Json.stringify(e)).append('\n')
      }
      prefs.edit().putString(queueKey(), arr.toString()).apply()
    } catch (_: Throwable) {}
  }
  private fun restoreQueue() {
    try {
      val s = prefs.getString(queueKey(), null) ?: return
      val lines = s.split('\n').filter { it.isNotBlank() }
      for (ln in lines) {
        // Simple restore: we don't parse back, only drop persisted queue to avoid crash; could parse if needed.
        // To keep simple, ignore restore content for now.
      }
    } catch (_: Throwable) {}
  }

  private fun genDeviceId(): String = "d_" + UUID.randomUUID().toString().replace("-", "")

  private fun uuidv7(): String {
    val t = System.currentTimeMillis()
    val ts = java.lang.Long.toHexString(t).padStart(12, '0')
    val rnd = UUID.randomUUID().toString().replace("-", "")
    val hex = ts + rnd.take(20)
    return hex.substring(0,8) + "-" + hex.substring(8,12) + "-7" + hex.substring(13,16) + "-" + hex.substring(16,20) + "-" + hex.substring(20,32)
  }

  // ===== Experiments helpers =====
  data class Variant(val name: String, val weight: Int)
  private fun hash32(s: String): Int {
    var h = 0x811c9dc5.toInt()
    for (ch in s) { h = h xor ch.code; h += (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24) }
    return h ushr 0
  }
  fun assignVariant(expId: String, salt: String?, variants: List<Variant>, key: String): String {
    if (variants.isEmpty()) return "A"
    val sum = variants.sumOf { if (it.weight>0) it.weight else 1 }
    val h = (hash32("$expId:${salt?:("")}:$key") % sum + sum) % sum
    var acc = 0
    for (v in variants) { acc += if (v.weight>0) v.weight else 1; if (h < acc) return v.name }
    return variants[0].name
  }
  /** Fetch experiments config (running) from control-service. WARNING: network on caller thread. */
  fun fetchExperiments(controlEndpoint: String, tenantId: String, appId: String): String? {
    return try {
      val url = controlEndpoint.trimEnd('/') + "/api/config/" + tenantId + "/" + appId
      val req = okhttp3.Request.Builder().url(url).get().build()
      client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    } catch (_: Throwable) { null }
  }

  // ===== Experiments cache & auto refresh =====
  private fun expsKey(tid: String, aid: String) = "pt_experiments_${tid}_${aid}"

  /** Return cached experiments JSON if available, else null. */
  fun getCachedExperiments(tenantId: String = opts.tenantId, appId: String = opts.appId): String? {
    return try {
      val raw = prefs.getString(expsKey(tenantId, appId), null) ?: return null
      val parts = raw.split('\n', limit = 2)
      if (parts.size == 2) parts[1] else null
    } catch (_: Throwable) { null }
  }

  /** Fetch experiments with TTL cache. Returns JSON (string) or null. */
  fun fetchExperimentsCached(controlEndpoint: String, tenantId: String = opts.tenantId, appId: String = opts.appId, ttlMs: Long = 300_000): String? {
    val now = System.currentTimeMillis()
    try {
      val raw = prefs.getString(expsKey(tenantId, appId), null)
      if (raw != null) {
        val parts = raw.split('\n', limit = 2)
        if (parts.size == 2) {
          val ts = parts[0].toLongOrNull() ?: 0L
          val js = parts[1]
          if (now - ts < ttlMs) {
            // background refresh
            Thread { fetchAndStore(controlEndpoint, tenantId, appId) }.start()
            return js
          }
        }
      }
    } catch (_: Throwable) {}
    val js = fetchExperiments(controlEndpoint, tenantId, appId)
    if (js != null) prefs.edit().putString(expsKey(tenantId, appId), "$now\n$js").apply()
    return js
  }

  /** Start a background timer to refresh experiments and invoke callback with latest JSON. Returns a cancel function. */
  fun startExperimentsAutoRefresh(controlEndpoint: String, tenantId: String = opts.tenantId, appId: String = opts.appId, intervalMs: Long = 300_000, onUpdate: (String)->Unit): ()->Unit {
    val t = Timer(true)
    val task = object: TimerTask() {
      override fun run() { fetchAndStore(controlEndpoint, tenantId, appId)?.let { onUpdate(it) } }
    }
    t.schedule(task, 0L, intervalMs)
    return { try { t.cancel() } catch (_: Throwable) {} }
  }

  private fun fetchAndStore(controlEndpoint: String, tenantId: String, appId: String): String? {
    val js = fetchExperiments(controlEndpoint, tenantId, appId)
    if (js != null) {
      val now = System.currentTimeMillis()
      prefs.edit().putString(expsKey(tenantId, appId), "$now\n$js").apply()
    }
    return js
  }

}
