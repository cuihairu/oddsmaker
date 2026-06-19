package io.oddsmaker.android

import org.junit.Test
import kotlin.test.assertEquals

class ConsistencyTest {
  data class Variant(val name: String, val weight: Int)

  private fun hash32(s: String): Int {
    var h = 0x811c9dc5.toInt()
    for (ch in s) { h = h xor ch.code; h += (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24) }
    return h ushr 0
  }

  private fun assignVariant(expId: String, salt: String?, variants: List<Variant>, key: String): String {
    if (variants.isEmpty()) return "A"
    val sum = variants.sumOf { if (it.weight>0) it.weight else 1 }
    val h = (hash32("$expId:${salt?:("")}:$key") % sum + sum) % sum
    var acc = 0
    for (v in variants) { acc += if (v.weight>0) v.weight else 1; if (h < acc) return v.name }
    return variants[0].name
  }

  @Test
  fun deterministicAssignmentAndHash() {
    val variants = listOf(Variant("A",50), Variant("B",50))
    val keys = listOf("u1","u2","u3","device123","device456")
    val res1 = keys.map { assignVariant("exp_consistency","s",variants,it) }
    val res2 = keys.map { assignVariant("exp_consistency","s",variants,it) }
    assertEquals(res1, res2)
    // Hash spot checks: stable across runs
    assertEquals(0xe40c292c.toInt() ushr 0, hash32("a"))
    assertEquals(0xbf9cf968.toInt() ushr 0, hash32("foobar"))
  }
}
