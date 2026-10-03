import { flushPromises } from '@vue/test-utils'

/**
 * 视图测试统一等待原语：同时排空微任务与宏任务队列。
 *
 * 为什么不能只 flushPromises()：
 * - flushPromises 内部走 setImmediate（check 阶段）；
 * - 而各 spec 的 api mock（ok()/bad()）用 setTimeout(..., 0) 落定（timers 阶段）；
 * - 固定 N 轮 flushPromises 在本机低负载恰好够，但在 CI runner / 受限 CPU
 *   （实测 taskset 2 核必挂）下宏任务链可能没排空，表现为
 *   「Cannot call trigger on an empty DOMWrapper」或空态断言早断言。
 *
 * 口径：每轮先 flush 微任务、再让出一个真实宏任务（setTimeout 0），
 * 两级队列交替推进；5 轮覆盖视图数据面 load() 链（请求 → 赋值 → finally → 渲染）。
 * 绿路径只多几个 event-loop tick，不引入固定 sleep。
 */
export const settle = async (rounds = 5) => {
  for (let i = 0; i < rounds; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

/**
 * 条件等待：断言命中即返（绿路径不加耗时），tries 轮仍未命中抛最后一次断言错误
 * （真 bug 照常红，不会被吞掉）。
 */
export const waitFor = async (assertFn, tries = 30, settleFn = settle) => {
  let last
  for (let i = 0; i < tries; i++) {
    try {
      assertFn()
      return
    } catch (e) {
      last = e
    }
    await settleFn()
  }
  throw last
}