package io.oddsmaker.android

import android.content.Context
import android.content.SharedPreferences
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * 测试替身（LAY-009 自 OddsmakerJvmTests 拆出，供各拆分类共享）：
 * FakePrefs 内存 SharedPreferences（可注入读写故障）、FakeContext、ScriptedServer 脚本化 HttpServer。
 */
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

internal object FakeContext : Context()

/** 脚本化 HttpServer:按请求顺序出队响应(status,body);记录全部请求供断言。 */
class ScriptedServer {
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
