// android.util 替身:Log.d 记录以便断言;Base64 仅被 import(未使用),提供空壳即可编译。
package android.util

object Log {
  val lines = mutableListOf<String>()
  fun d(tag: String, msg: String): Int { lines.add("$tag: $msg"); return 0 }
  fun reset() { lines.clear() }
}

object Base64
