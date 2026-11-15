package io.oddsmaker.android

import android.content.AndroidSim
import android.util.Log
import io.oddsmaker.android.Oddsmaker.Options
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Android SDK JVM 直测公共基类（LAY-009 自 OddsmakerJvmTests 拆出）：
 * 共享 prefs/servers 生命周期、Json/私有成员反射调用 helper、newSdk 默认选项、
 * 队列/prefs 键名 helper、queueJson/gunzip。
 */
abstract class OddsmakerJvmTestBase {

  protected lateinit var prefs: FakePrefs
  protected val servers = mutableListOf<ScriptedServer>()

  protected val jsonObj by lazy {
    Oddsmaker::class.java.declaredClasses.first { it.simpleName == "Json" }
  }

  protected fun jsonInvoke(name: String, arg: Any?): Any? {
    val instance = jsonObj.getDeclaredField("INSTANCE").get(null)
    val method = jsonObj.declaredMethods.first { it.name == name && it.parameterCount == 1 }
    method.isAccessible = true
    return method.invoke(instance, arg)
  }

  /** 按名称+参数个数反射调用 Oddsmaker 私有成员。 */
  protected fun call(name: String, target: Any? = null, vararg args: Any?): Any? {
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

  protected fun defaultOptions() = Options(
    apiKey = "key-1", endpoint = "https://ing.example/", gameId = "game1",
    environment = "prod", deviceId = "dev-explicit", flushIntervalMs = 3_600_000
  )

  protected fun newSdk(
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

  protected fun devKey() = "oddsmaker_device_id_game1_prod"
  protected fun queueKey() = "oddsmaker_queue_game1_prod_dev-explicit"
  protected fun expsKey() = "oddsmaker_experiments_game1_prod"

  protected fun queueJson(): List<JSONObject> =
    (prefs.store[queueKey()] ?: "").split('\n').filter { it.isNotBlank() }.map { JSONObject(it) }

  protected fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).readBytes()
}
