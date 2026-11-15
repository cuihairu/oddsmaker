// android.content / android.util 测试替身:仅覆盖 Oddsmaker.kt 用到的 API 面(内存实现,可注入故障)。
package android.content

/** 全局可配置的 SharedPreferences 提供点(每个测试换新实例)。 */
object AndroidSim {
  lateinit var prefs: SharedPreferences
  fun install(p: SharedPreferences) { prefs = p }
}

abstract class Context {
  fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = AndroidSim.prefs
  companion object { const val MODE_PRIVATE = 0 }
}

interface SharedPreferences {
  fun getString(key: String?, defValue: String?): String?
  fun edit(): Editor

  interface Editor {
    fun putString(key: String?, value: String?): Editor
    fun apply()
  }
}
