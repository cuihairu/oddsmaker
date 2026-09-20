package io.oddsmaker.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * Minimal Android SDK that mirrors the Web SDK behavior: batching, NDJSON, optional gzip,
 * offline persistence (SharedPreferences), and simple session handling.
 *
 * Authentication: x-api-key header only. HMAC signing is intentionally NOT supported
 * on the client SDK because shipping HMAC secrets in client code is unsafe.
 */
class Oddsmaker(private val ctx: Context, private val opts: Options) {
  data class Options(
    val apiKey: String,
    val endpoint: String,
    val gameId: String,
    val environment: String,
    val deviceId: String? = null,
    val flushIntervalMs: Long = 5000,
    val maxBatch: Int = 50,
    val maxQueueBytes: Int = 512_000,
    val sessionGapMs: Long = 30 * 60 * 1000,
    val debug: Boolean = false
  )

  data class Event(
    val event_id: String,
    val game_id: String,
    val environment: String,
    val event_type: String,
    val event_name: String,
    val user_id: String? = null,
    val device_id: String,
    val player_id: String? = null,
    val session_id: String? = null,
    val ts_client: Long,
    val platform: String? = "android",
    val app_version: String? = null,
    val country: String? = null,
    val level_id: String? = null,
    val game_mode: String? = null,
    val order_id: String? = null,
    val product_id: String? = null,
    val revenue_amount: Double? = null,
    val revenue_currency: String? = null,
    val virtual_currency: String? = null,
    val virtual_amount: Double? = null,
    val item_id: String? = null,
    val resource_id: String? = null,
    val resource_amount: Double? = null,
    val flow_type: String? = null,
    val ad_network: String? = null,
    val ad_placement: String? = null,
    val ad_format: String? = null,
    val props: Map<String, Any?>? = null
  )

  private val client = OkHttpClient()
  private val prefs: SharedPreferences = ctx.getSharedPreferences("oddsmaker", Context.MODE_PRIVATE)
  private val queue = ArrayList<Event>()
  private var queueBytes: Int = 0
  private val timer = Timer(true)
  private val flushing = AtomicBoolean(false)

  private var deviceId: String
  private var userId: String? = null
  private var playerId: String? = null
  private var userProps: Map<String, Any?> = emptyMap()
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
  fun setUserProps(props: Map<String, Any?>) { userProps = userProps + props }
  fun setPlayer(pid: String?) { playerId = pid }

  fun identify(newUserId: String, props: Map<String, Any?>? = null): String {
    val previousUserId = userId
    userId = newUserId
    val identifyProps = mutableMapOf<String, Any?>(
      "\$identify" to true,
      "new_user_id" to newUserId
    )
    if (!previousUserId.isNullOrEmpty() && previousUserId != newUserId) {
      identifyProps["previous_user_id"] = previousUserId
    }
    if (!playerId.isNullOrEmpty()) identifyProps["player_id"] = playerId
    if (props != null) identifyProps.putAll(props)
    return track("\$identify", identifyProps)
  }

  fun track(eventName: String, props: Map<String, Any?>? = null): String =
    queueEvent(eventName, props)

  fun expose(exp: String, variant: String) =
    track("experiment_exposure", mapOf("exp" to exp, "variant" to variant))

  fun revenue(amount: Number, currency: String, props: Map<String, Any?>? = null): String {
    val code = currency.uppercase()
    val merged = withCoreProps(props, mapOf("amount" to amount, "currency" to code))
    return queueEvent(
      "revenue", merged,
      revenueAmount = amount.toDouble(), revenueCurrency = code
    )
  }

  // ---- 类型化便捷事件:与 Web SDK 对齐,填充事件契约顶层字段(分析视图直接消费) ----

  fun levelStart(levelId: String, props: Map<String, Any?>? = null): String {
    val merged = withCoreProps(props, mapOf("level_id" to levelId))
    return queueEvent("level_start", merged, levelId = levelId, gameMode = optStr(merged, "game_mode"))
  }

  fun levelFail(levelId: String, reason: String, props: Map<String, Any?>? = null): String {
    val merged = withCoreProps(props, mapOf("level_id" to levelId, "fail_reason" to reason))
    return queueEvent("level_fail", merged, levelId = levelId, gameMode = optStr(merged, "game_mode"))
  }

  fun levelComplete(levelId: String, props: Map<String, Any?>? = null): String {
    val merged = withCoreProps(props, mapOf("level_id" to levelId))
    return queueEvent("level_complete", merged, levelId = levelId, gameMode = optStr(merged, "game_mode"))
  }

  fun currencySource(currency: String, amount: Number, props: Map<String, Any?>? = null): String {
    val code = currency.uppercase()
    val merged = withCoreProps(props, mapOf("currency_code" to code, "amount" to amount))
    return queueEvent(
      "currency_source", merged,
      resourceId = code, resourceAmount = amount.toDouble(),
      virtualCurrency = code, virtualAmount = amount.toDouble(), flowType = "source"
    )
  }

  fun currencySink(currency: String, amount: Number, props: Map<String, Any?>? = null): String {
    val code = currency.uppercase()
    val merged = withCoreProps(props, mapOf("currency_code" to code, "amount" to amount))
    return queueEvent(
      "currency_sink", merged,
      resourceId = code, resourceAmount = amount.toDouble(),
      virtualCurrency = code, virtualAmount = amount.toDouble(), flowType = "sink"
    )
  }

  fun itemGrant(itemId: String, quantity: Int = 1, props: Map<String, Any?>? = null): String {
    val merged = withCoreProps(props, mapOf("item_id" to itemId, "quantity" to quantity))
    return queueEvent(
      "item_grant", merged,
      itemId = itemId, resourceId = itemId, resourceAmount = quantity.toDouble(), flowType = "source"
    )
  }

  fun itemConsume(itemId: String, quantity: Int = 1, props: Map<String, Any?>? = null): String {
    val merged = withCoreProps(props, mapOf("item_id" to itemId, "quantity" to quantity))
    return queueEvent(
      "item_consume", merged,
      itemId = itemId, resourceId = itemId, resourceAmount = quantity.toDouble(), flowType = "sink"
    )
  }

  fun iapOrder(orderId: String, amount: Number, currency: String, props: Map<String, Any?>? = null): String {
    val code = currency.uppercase()
    val merged = withCoreProps(withCoreProps(props, mapOf("order_id" to orderId)), mapOf("amount" to amount, "currency" to code))
    return queueEvent(
      "iap_order", merged,
      orderId = orderId, productId = optStr(merged, "product_id"),
      revenueAmount = amount.toDouble(), revenueCurrency = code
    )
  }

  fun webshopOrder(orderId: String, amount: Number, currency: String, props: Map<String, Any?>? = null): String {
    val code = currency.uppercase()
    val merged = withCoreProps(withCoreProps(props, mapOf("order_id" to orderId)), mapOf("amount" to amount, "currency" to code))
    return queueEvent(
      "webshop_order", merged,
      orderId = orderId, productId = optStr(merged, "product_id"),
      revenueAmount = amount.toDouble(), revenueCurrency = code
    )
  }

  fun adImpression(amount: Number, currency: String, props: Map<String, Any?>? = null): String {
    val code = currency.uppercase()
    val merged = withCoreProps(props, mapOf("amount" to amount, "currency" to code))
    return queueEvent(
      "ad_impression", merged,
      adNetwork = optStr(merged, "network"), adPlacement = optStr(merged, "placement_id"),
      adFormat = optStr(merged, "ad_format"),
      revenueAmount = amount.toDouble(), revenueCurrency = code
    )
  }

  private fun withCoreProps(props: Map<String, Any?>?, core: Map<String, Any?>): Map<String, Any?> =
    (props ?: emptyMap()) + core

  private fun optStr(props: Map<String, Any?>, key: String): String? = props[key] as? String

  private fun queueEvent(
    eventName: String,
    props: Map<String, Any?>?,
    levelId: String? = null,
    gameMode: String? = null,
    orderId: String? = null,
    productId: String? = null,
    revenueAmount: Double? = null,
    revenueCurrency: String? = null,
    virtualCurrency: String? = null,
    virtualAmount: Double? = null,
    itemId: String? = null,
    resourceId: String? = null,
    resourceAmount: Double? = null,
    flowType: String? = null,
    adNetwork: String? = null,
    adPlacement: String? = null,
    adFormat: String? = null
  ): String {
    val now = System.currentTimeMillis()
    rollSession(now)
    val evt = Event(
      event_id = uuidv7(),
      game_id = opts.gameId,
      environment = opts.environment,
      event_type = inferEventType(eventName),
      event_name = eventName,
      user_id = userId,
      device_id = deviceId,
      player_id = playerId,
      session_id = sessionId,
      ts_client = now,
      platform = "android",
      level_id = levelId,
      game_mode = gameMode,
      order_id = orderId,
      product_id = productId,
      revenue_amount = revenueAmount,
      revenue_currency = revenueCurrency,
      virtual_currency = virtualCurrency,
      virtual_amount = virtualAmount,
      item_id = itemId,
      resource_id = resourceId,
      resource_amount = resourceAmount,
      flow_type = flowType,
      ad_network = adNetwork,
      ad_placement = adPlacement,
      ad_format = adFormat,
      props = mergeProps(props)
    )
    enqueue(evt, now)
    return evt.event_id
  }

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

  private fun enqueue(evt: Event, now: Long) {
    val shouldFlush: Boolean
    synchronized(this) {
      val est = evt.estimateSize()
      queue.add(evt)
      queueBytes += est
      lastActive = now
      shouldFlush = queueBytes >= opts.maxQueueBytes || queue.size >= opts.maxBatch
      persistQueue()
    }
    if (opts.debug) Log.d("Oddsmaker", "queued ${evt.event_id} ${evt.event_name}")
    if (shouldFlush) flush()
  }

  private fun rollSession(now: Long) {
    if (sessionId == null || now - lastActive > opts.sessionGapMs) {
      sessionId = uuidv7()
    }
  }

  private fun send(batch: List<Event>) {
    if (batch.isEmpty()) return
    val ndjson = buildString(batch.size * 128) {
      for (e in batch) append(Json.stringify(e)).append('\n')
    }
    var bodyBytes = ndjson.toByteArray(Charsets.UTF_8)
    val mediaType = "application/x-ndjson".toMediaType()
    val reqBuilder = Request.Builder()
      .url(opts.endpoint.trimEnd('/') + "/v1/batch")
      .addHeader("x-api-key", opts.apiKey)

    val gz = gzip(bodyBytes)
    if (gz != null) {
      bodyBytes = gz
      reqBuilder.addHeader("content-encoding", "gzip")
    }

    val req = reqBuilder.post(
      RequestBody.create(mediaType, bodyBytes)
    ).build()
    try {
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          queue.addAll(0, batch)
          recalcQueueBytes()
        } else {
          handleBatchResponse(resp.body?.string(), batch)
        }
      }
    } catch (_: Exception) {
      queue.addAll(0, batch)
      recalcQueueBytes()
    }
  }

  /**
   * 2xx 响应体仍含逐事件拒绝（gateway BatchResponse）：
   * 仅 kafka_error 属临时故障需回队首发重试；invalid_schema/blocked 等为永久失败，
   * 重发无意义，丢弃并按 debug 记录。响应体解析失败按全成功处理（幂等于既有行为）。
   */
  private fun handleBatchResponse(body: String?, batch: List<Event>) {
    if (body.isNullOrEmpty()) return
    val parsed = try { JSONObject(body) } catch (_: Throwable) { return }
    val rejected = parsed.optJSONArray("rejected") ?: return
    val reasons = HashMap<String, String>()
    for (i in 0 until rejected.length()) {
      val entry = rejected.optJSONObject(i) ?: continue
      val id = entry.optString("event_id")
      if (id.isNotEmpty()) reasons[id] = entry.optString("reason")
    }
    if (reasons.isEmpty()) return
    val retryable = ArrayList<Event>()
    for (e in batch) {
      when (reasons[e.event_id]) {
        null -> {}
        "kafka_error" -> retryable.add(e)
        else -> if (opts.debug) Log.w("Oddsmaker", "event rejected: ${e.event_id} ${reasons[e.event_id]}")
      }
    }
    if (retryable.isNotEmpty()) {
      synchronized(this) {
        queue.addAll(0, retryable)
        recalcQueueBytes()
      }
    }
  }

  private object Json {
    fun stringify(e: Event): String {
      val sb = StringBuilder(256)
      sb.append('{')
      field(sb, "event_id", e.event_id)
      field(sb, "game_id", e.game_id)
      field(sb, "environment", e.environment)
      field(sb, "event_type", e.event_type)
      field(sb, "event_name", e.event_name)
      e.user_id?.let { field(sb, "user_id", it) }
      field(sb, "device_id", e.device_id)
      e.player_id?.let { field(sb, "player_id", it) }
      e.session_id?.let { field(sb, "session_id", it) }
      field(sb, "ts_client", e.ts_client)
      e.platform?.let { field(sb, "platform", it) }
      e.app_version?.let { field(sb, "app_version", it) }
      e.country?.let { field(sb, "country", it) }
      e.level_id?.let { field(sb, "level_id", it) }
      e.game_mode?.let { field(sb, "game_mode", it) }
      e.order_id?.let { field(sb, "order_id", it) }
      e.product_id?.let { field(sb, "product_id", it) }
      e.revenue_amount?.let { field(sb, "revenue_amount", it) }
      e.revenue_currency?.let { field(sb, "revenue_currency", it) }
      e.virtual_currency?.let { field(sb, "virtual_currency", it) }
      e.virtual_amount?.let { field(sb, "virtual_amount", it) }
      e.item_id?.let { field(sb, "item_id", it) }
      e.resource_id?.let { field(sb, "resource_id", it) }
      e.resource_amount?.let { field(sb, "resource_amount", it) }
      e.flow_type?.let { field(sb, "flow_type", it) }
      e.ad_network?.let { field(sb, "ad_network", it) }
      e.ad_placement?.let { field(sb, "ad_placement", it) }
      e.ad_format?.let { field(sb, "ad_format", it) }
      e.props?.let { objField(sb, "props", it) }
      if (sb.last() == ',') sb.setLength(sb.length - 1)
      sb.append('}')
      return sb.toString()
    }

    fun parseEvent(line: String): Event? {
      return try {
        val o = JSONObject(line)
        val propsObj = o.optJSONObject("props")
        Event(
          event_id = o.getString("event_id"),
          game_id = o.getString("game_id"),
          environment = o.getString("environment"),
          event_type = o.getString("event_type"),
          event_name = o.getString("event_name"),
          user_id = o.optStringOrNull("user_id"),
          device_id = o.getString("device_id"),
          player_id = o.optStringOrNull("player_id"),
          session_id = o.optStringOrNull("session_id"),
          ts_client = o.getLong("ts_client"),
          platform = o.optStringOrNull("platform"),
          app_version = o.optStringOrNull("app_version"),
          country = o.optStringOrNull("country"),
          level_id = o.optStringOrNull("level_id"),
          game_mode = o.optStringOrNull("game_mode"),
          order_id = o.optStringOrNull("order_id"),
          product_id = o.optStringOrNull("product_id"),
          revenue_amount = if (o.has("revenue_amount") && !o.isNull("revenue_amount")) o.getDouble("revenue_amount") else null,
          revenue_currency = o.optStringOrNull("revenue_currency"),
          virtual_currency = o.optStringOrNull("virtual_currency"),
          virtual_amount = if (o.has("virtual_amount") && !o.isNull("virtual_amount")) o.getDouble("virtual_amount") else null,
          item_id = o.optStringOrNull("item_id"),
          resource_id = o.optStringOrNull("resource_id"),
          resource_amount = if (o.has("resource_amount") && !o.isNull("resource_amount")) o.getDouble("resource_amount") else null,
          flow_type = o.optStringOrNull("flow_type"),
          ad_network = o.optStringOrNull("ad_network"),
          ad_placement = o.optStringOrNull("ad_placement"),
          ad_format = o.optStringOrNull("ad_format"),
          props = propsObj?.toMap()
        )
      } catch (_: Throwable) {
        null
      }
    }

    private fun field(sb: StringBuilder, k: String, v: String) {
      sb.append('"').append(escape(k)).append('"').append(':')
        .append('"').append(escape(v)).append('"').append(',')
    }

    private fun field(sb: StringBuilder, k: String, v: Long) {
      sb.append('"').append(escape(k)).append('"').append(':').append(v).append(',')
    }

    private fun field(sb: StringBuilder, k: String, v: Double) {
      sb.append('"').append(escape(k)).append('"').append(':').append(v).append(',')
    }

    private fun objField(sb: StringBuilder, k: String, obj: Map<String, Any?>) {
      sb.append('"').append(escape(k)).append('"').append(':').append(mapToJson(obj)).append(',')
    }

    private fun mapToJson(m: Map<String, Any?>, depth: Int = 0): String {
      if (depth > 2) return "{}"
      val sb = StringBuilder("{")
      for ((kk, vv) in m) {
        sb.append('"').append(escape(kk)).append('"').append(':')
          .append(valueToJson(vv, depth + 1)).append(',')
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
        is Map<*, *> -> mapToJson(v as Map<String, Any?>, depth)
        is List<*> -> listToJson(v as List<Any?>, depth)
        else -> '"' + escape(v.toString()) + '"'
      }
    }

    private fun listToJson(l: List<Any?>, depth: Int): String {
      if (depth > 2) return "[]"
      val sb = StringBuilder("[")
      val lim = minOf(50, l.size)
      for (i in 0 until lim) {
        sb.append(valueToJson(l[i], depth + 1)).append(',')
      }
      if (sb.last() == ',') sb.setLength(sb.length - 1)
      sb.append(']')
      return sb.toString()
    }

    private fun escape(s: String): String {
      val out = StringBuilder(s.length + 8)
      for (ch in s) {
        when (ch) {
          '\\' -> out.append("\\\\")
          '"' -> out.append("\\\"")
          '\n' -> out.append("\\n")
          '\r' -> out.append("\\r")
          '\t' -> out.append("\\t")
          else -> if (ch < ' ') {
            out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
          } else {
            out.append(ch)
          }
        }
      }
      return out.toString()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
      if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.toMap(): Map<String, Any?> {
      val out = LinkedHashMap<String, Any?>()
      val keys = keys()
      while (keys.hasNext()) {
        val key = keys.next()
        out[key] = get(key).toJsonValue()
      }
      return out
    }

    private fun JSONArray.toListValue(): List<Any?> {
      val out = ArrayList<Any?>()
      for (i in 0 until length()) out.add(get(i).toJsonValue())
      return out
    }

    private fun Any?.toJsonValue(): Any? =
      when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap()
        is JSONArray -> toListValue()
        else -> this
      }
  }

  private fun Event.estimateSize(): Int =
    Json.stringify(this).toByteArray(Charsets.UTF_8).size + 1

  private fun gzip(input: ByteArray): ByteArray? {
    // runCatching 无分支形态：catch 出口编译为表达式汇合点，行覆盖可达（try/catch 形态的
    // 异常出口在本方法唯一调用路径下不可注入）
    return runCatching {
      val bout = ByteArrayOutputStream()
      GZIPOutputStream(bout).use { it.write(input) }
      bout.toByteArray()
    }.getOrNull()
  }

  private fun devKey() = "oddsmaker_device_id_${opts.gameId}_${opts.environment}"
  private fun queueKey() = "oddsmaker_queue_${opts.gameId}_${opts.environment}_${deviceId}"

  private fun persistQueue() {
    try {
      val arr = StringBuilder(queue.size * 128)
      for (e in queue) arr.append(Json.stringify(e)).append('\n')
      prefs.edit().putString(queueKey(), arr.toString()).apply()
    } catch (_: Throwable) {}
  }

  private fun restoreQueue() {
    try {
      val s = prefs.getString(queueKey(), null) ?: return
      val lines = s.split('\n').filter { it.isNotBlank() }
      for (line in lines) Json.parseEvent(line)?.let { queue.add(it) }
      recalcQueueBytes()
    } catch (_: Throwable) {}
  }

  private fun mergeProps(props: Map<String, Any?>?): Map<String, Any?>? {
    val base: MutableMap<String, Any?> = LinkedHashMap(userProps)
    if (!playerId.isNullOrEmpty()) base["player_id"] = playerId
    if (base.isEmpty()) return props
    return base + (props ?: emptyMap())
  }

  private fun recalcQueueBytes() {
    queueBytes = queue.sumOf { it.estimateSize() }
  }

  private fun genDeviceId(): String =
    "d_" + UUID.randomUUID().toString().replace("-", "")

  private fun uuidv7(): String {
    val t = System.currentTimeMillis()
    val ts = java.lang.Long.toHexString(t).padStart(12, '0')
    val rnd = UUID.randomUUID().toString().replace("-", "")
    val hex = ts + rnd.take(20)
    return hex.substring(0, 8) + "-" +
      hex.substring(8, 12) + "-7" +
      hex.substring(13, 16) + "-" +
      hex.substring(16, 20) + "-" +
      hex.substring(20, 32)
  }

  private fun inferEventType(eventName: String): String {
    val name = eventName.lowercase()
    return when {
      name == "\$identify" || "identity" in name -> "identity"
      "risk" in name || "fraud" in name -> "risk"
      "experiment" in name -> "experiment"
      "ad_" in name -> "ad"
      "level" in name || "quest" in name -> "progression"
      "session" in name -> "session"
      "error" in name || "crash" in name -> "error"
      else -> "business"
    }
  }

  data class Variant(val name: String, val weight: Int)

  private fun hash32(s: String): Int {
    var h = 0x811c9dc5.toInt()
    for (ch in s) {
      h = h xor ch.code
      h += (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24)
    }
    return h ushr 0
  }

  fun assignVariant(expId: String, salt: String?, variants: List<Variant>, key: String): String {
    if (variants.isEmpty()) return "A"
    var sum = 0
    for (v in variants) sum += if (v.weight > 0) v.weight else 1
    // 与服务端 ExperimentSplitter 同源：无符号哈希对 int32 总权重取模（Long 模，sum 符号扩展）。
    // 原 (hash % sum + sum) % sum 的 Int 规范化在 sum 溢出为负时把 h 折进负值域，
    // 同一主体与服务端落位不同变体；兜底也统一为末变体（原 first）
    if (sum == 0) return variants[variants.size - 1].name
    val h = Integer.toUnsignedLong(hash32("$expId:${salt ?: ""}:$key")) % sum.toLong()
    var acc = 0
    for (v in variants) {
      acc += if (v.weight > 0) v.weight else 1
      if (h < acc) return v.name
    }
    return variants[variants.size - 1].name
  }

  /** Fetch experiments config (running) from control-service. WARNING: network on caller thread. */
  fun fetchExperiments(controlEndpoint: String, gameId: String, environment: String): String? {
    return try {
      val url = controlEndpoint.trimEnd('/') + "/api/config/" + gameId + "/" + environment
      val req = Request.Builder().url(url).get().build()
      client.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) resp.body?.string() else null
      }
    } catch (_: Throwable) {
      null
    }
  }

  private fun expsKey(gameId: String, environment: String) =
    "oddsmaker_experiments_${gameId}_${environment}"

  fun getCachedExperiments(
    gameId: String = opts.gameId,
    environment: String = opts.environment
  ): String? {
    return try {
      val raw = prefs.getString(expsKey(gameId, environment), null) ?: return null
      val parts = raw.split('\n', limit = 2)
      if (parts.size == 2) parts[1] else null
    } catch (_: Throwable) {
      null
    }
  }

  fun fetchExperimentsCached(
    controlEndpoint: String,
    gameId: String = opts.gameId,
    environment: String = opts.environment,
    ttlMs: Long = 300_000
  ): String? {
    val now = System.currentTimeMillis()
    try {
      val raw = prefs.getString(expsKey(gameId, environment), null)
      if (raw != null) {
        val parts = raw.split('\n', limit = 2)
        if (parts.size == 2) {
          val ts = parts[0].toLongOrNull() ?: 0L
          val js = parts[1]
          if (now - ts < ttlMs) {
            Thread { fetchAndStore(controlEndpoint, gameId, environment) }.start()
            return js
          }
        }
      }
    } catch (_: Throwable) {}
    val js = fetchExperiments(controlEndpoint, gameId, environment)
    if (js != null) {
      prefs.edit().putString(expsKey(gameId, environment), "$now\n$js").apply()
    }
    return js
  }

  fun startExperimentsAutoRefresh(
    controlEndpoint: String,
    gameId: String = opts.gameId,
    environment: String = opts.environment,
    intervalMs: Long = 300_000,
    onUpdate: (String) -> Unit
  ): () -> Unit {
    val t = Timer(true)
    val task = object : TimerTask() {
      override fun run() {
        fetchAndStore(controlEndpoint, gameId, environment)?.let { onUpdate(it) }
      }
    }
    t.schedule(task, 0L, intervalMs)
    return { try { t.cancel() } catch (_: Throwable) {} }
  }

  private fun fetchAndStore(controlEndpoint: String, gameId: String, environment: String): String? {
    val js = fetchExperiments(controlEndpoint, gameId, environment)
    if (js != null) {
      val now = System.currentTimeMillis()
      prefs.edit().putString(expsKey(gameId, environment), "$now\n$js").apply()
    }
    return js
  }
}
