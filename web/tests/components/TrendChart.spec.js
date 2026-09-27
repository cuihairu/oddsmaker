import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TrendChart from '@/components/TrendChart.vue'

/**
 * TrendChart.vue 的数据 → SVG 换算契约。组件画布常量：
 *   W=800 H=300 PAD={top:16,right:16,bottom:34,left:56} → 绘图区 728×250，
 *   x(i) = 56 + i*728/(n-1)（单点居中 420），y(v) = 16 + (1 - v/yMax)*250，yMax = 峰值×1.1。
 * 下面用手工常量钉住映射（而非复刻公式），断言解析自真实渲染出的 polyline points。
 */
const mount_ = (props) => mount(TrendChart, { props })

function parsePoints(wrapper, index = 0) {
  return wrapper
    .findAll('polyline')
    [index].attributes('points')
    .split(' ')
    .map((pt) => pt.split(',').map(Number))
}

/** y 轴网格刻度文本（x=48 的 text 节点，顺序 r=0(顶)→1(底)） */
function gridLabels(wrapper) {
  return wrapper
    .findAll('text')
    .filter((t) => t.attributes('x') === '48')
    .map((t) => t.text())
}

/** x 轴刻度文本（y=290 的 text 节点，按渲染顺序） */
function xTickTexts(wrapper) {
  return wrapper
    .findAll('text')
    .filter((t) => t.attributes('y') === '290')
    .map((t) => t.text())
}

describe('TrendChart', () => {
  it('画布形态：viewBox 0 0 800 300，默认高 280px，可用 height prop 覆盖', () => {
    const w = mount_({ labels: ['a'], series: [{ name: 'X', values: [1] }] })
    expect(w.find('svg').attributes('viewBox')).toBe('0 0 800 300')
    expect(w.find('svg').attributes('style')).toContain('height: 280px')

    const w2 = mount_({ labels: ['a'], series: [], height: 120 })
    expect(w2.find('svg').attributes('style')).toContain('height: 120px')
  })

  it('坐标换算：三点等距 56/420/784，yMax=峰值×1.1，y 随值递增向上（越接近顶部数值越大）', () => {
    const w = mount_({
      labels: ['a', 'b', 'c'],
      series: [{ name: 'X', color: '#f00', values: [1, 2, 3] }]
    })
    const pts = parsePoints(w)
    expect(pts).toHaveLength(3)
    expect(pts[0][0]).toBeCloseTo(56)
    expect(pts[1][0]).toBeCloseTo(420)
    expect(pts[2][0]).toBeCloseTo(784)
    // yMax = 3*1.1 = 3.3：y(1)=16+(1-1/3.3)*250≈190.24，y(2)≈114.48，y(3)=16+(0.3/3.3)*250≈38.73
    expect(pts[0][1]).toBeCloseTo(190.24, 1)
    expect(pts[1][1]).toBeCloseTo(114.48, 1)
    expect(pts[2][1]).toBeCloseTo(38.73, 1)
    expect(pts[2][1]).toBeLessThan(pts[0][1])
  })

  it('单点居中：labels 只有一个时 x 落在绘图区中线 420', () => {
    const w = mount_({
      labels: ['only'],
      series: [{ name: 'X', values: [5] }]
    })
    const pts = parsePoints(w)
    expect(pts[0][0]).toBeCloseTo(420)
    // yMax = 5.5：y(5) = 16 + (0.5/5.5)*250 ≈ 38.73
    expect(pts[0][1]).toBeCloseTo(38.73, 1)
  })

  it('null 断段：null 前后各成一段 polyline，段内坐标各自正确', () => {
    const w = mount_({
      labels: ['a', 'b', 'c'],
      series: [{ name: 'X', values: [1, null, 2] }]
    })
    const polys = w.findAll('polyline')
    expect(polys).toHaveLength(2)
    const seg1 = parsePoints(w, 0)
    const seg2 = parsePoints(w, 1)
    expect(seg1).toHaveLength(1)
    expect(seg1[0][0]).toBeCloseTo(56)
    expect(seg2).toHaveLength(1)
    expect(seg2[0][0]).toBeCloseTo(784)
    // yMax = 2*1.1 = 2.2：y(2) = 16 + 0*... → 16 + (1-2/2.2)*250 ≈ 38.73
    expect(seg2[0][1]).toBeCloseTo(38.73, 1)
  })

  it('全 null 序列：不产出 polyline，但图例仍显示', () => {
    const w = mount_({
      labels: ['a', 'b'],
      series: [{ name: '空序列', values: [null, null] }]
    })
    expect(w.findAll('polyline')).toHaveLength(0)
    expect(w.text()).toContain('空序列')
  })

  it('values 缺省（undefined）与图例颜色', () => {
    const w = mount_({
      labels: ['a'],
      series: [{ name: '无值', color: '#123abc' }]
    })
    expect(w.findAll('polyline')).toHaveLength(0)
    const swatch = w.find('span[style]')
    expect(swatch.attributes('style')).toContain('background-color: rgb(18, 58, 188)') // #123abc
    expect(w.text()).toContain('无值')
  })

  it('y 轴刻度格式化：toeFixed(2) 小数为主，仅底部 0 走整数分支（峰值×1.1 的浮点污染使中间刻度非整数）', () => {
    const w = mount_({ labels: ['a'], series: [{ name: 'X', values: [100] }] })
    // 100*1.1 = 110.00000000000001 → 顶层刻度也落到 toFixed(2)；r=1 时 v=0 才是精确整数
    expect(gridLabels(w)).toEqual(['110.00', '82.50', '55.00', '27.50', '0'])
  })

  it('y 轴刻度格式化——>=10000 走 k 缩写分支', () => {
    const w = mount_({ labels: ['a'], series: [{ name: 'X', values: [10000] }] })
    expect(gridLabels(w)[0]).toBe('11k') // yMax = 11000 → Math.round(11)
  })

  it('y 轴刻度格式化——percent 模式按百分比显示', () => {
    const w = mount_({ labels: ['a'], series: [{ name: 'X', values: [0.5, 1] }], percent: true })
    // yMax = 1.1 → 顶 (1.1*100).toFixed(1)%，底 0.0%
    expect(gridLabels(w)).toEqual(['110.0%', '82.5%', '55.0%', '27.5%', '0.0%'])
  })

  it('x 轴稀疏刻度：20 个标签按步长 3 取 7 个（最多 8 个）；8 个标签全取', () => {
    const labels20 = Array.from({ length: 20 }, (_, i) => `d${i}`)
    const w = mount_({
      labels: labels20,
      series: [{ name: 'X', values: labels20.map(() => 1) }]
    })
    expect(xTickTexts(w)).toEqual(['d0', 'd3', 'd6', 'd9', 'd12', 'd15', 'd18'])

    const labels8 = Array.from({ length: 8 }, (_, i) => `w${i}`)
    const w8 = mount_({ labels: labels8, series: [{ name: 'X', values: labels8.map(() => 1) }] })
    expect(xTickTexts(w8)).toEqual(labels8)
  })

  it('空数据：显示「暂无数据」且无 x 轴刻度', () => {
    const w = mount_({ labels: [], series: [] })
    expect(w.text()).toContain('暂无数据')
    expect(xTickTexts(w)).toEqual([])
  })

  it('有数据时不显示「暂无数据」', () => {
    const w = mount_({ labels: ['a'], series: [{ name: 'X', values: [1] }] })
    expect(w.text()).not.toContain('暂无数据')
  })
})
