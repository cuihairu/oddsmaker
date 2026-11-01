# UI 审计报告 — oddsmaker / web（2026-10-31）

- **审计对象**: `oddsmaker/web`（Vue 3 + Vite + Tailwind CSS 管理控制台，32 路由）
- **审计方法**: impeccable skill v4.8.0（上下文脚本 + detect --json + WCAG 对比度计算 + Playwright 渲染截图）
- **截图证据**: `/home/cui/.cache/audit-shots-oddsmaker7/`（13 桌面 + 13 窄屏 + 登录/登录错误，全部经 SPA 导航渲染）
- **修复前后对照**: `docs/review/shots-after/`（每项修复的 before/after 截图对，文件名对齐问题编号，见 P1/P3 表与 P2-1 项）
- **审计维度**: 层级/排版 · 色彩对比度（WCAG AA）· 动效 · 响应式 · 边界状态 · UX 文案

---

## 总体批评（不留情面）

这套界面的**设计系统骨架是成立的**：单一 primary 色板、统一的卡片/徽章/表格样式、37 张表格中绝大多数套了 `overflow-x-auto`（RiskLabView/UsersView/WebhooksView 弹窗内约 5 张未套）、窄屏下卡片自动堆叠、侧边栏收起为抽屉——响应式处理是这个仓库最值得称赞的部分，比同类管理后台认真得多。

**但它在「最不该出错的地方」出了错，而且错在全局层：**

1. **主按钮对比度不达 AA**。`.btn-primary` 用白字配 `bg-primary-600`（#0284c7），实测 **4.10:1**，低于 WCAG AA 的 4.5:1。这不是某一个页面的局部问题——它是 `main.css` 里的单一工具类，**23 个视图文件**的登录按钮、全部主 CTA 都依赖它。一套设计系统如果连自己的主行动色都不达标，那它传达给用户的信号是「这个按钮能点，但你要凑近看」。更讽刺的是，修复只需要把 600 升到 700（5.93:1）——说明这是**选择色阶时压根没算过对比度**，而非能力问题。

2. **三个全局图标按钮对屏幕阅读器不可见、对低视力用户几乎不可见**。顶部通知铃铛、汉堡菜单、底部登出按钮全部是**纯图标、无文字**，前两者连 `aria-label` 都没有——读屏软件只会报「按钮」，不会报它干嘛的。同时这三个图标用 `text-gray-400`（#9ca3af），在白底上 **2.54:1**，远低于非文本元素 3:1 的标准。这是典型的「看得见的人看得见，看不见的人被遗忘」的 checkbox 式可访问性缺失。

3. **标题层级结构是错的**。侧边栏 Logo 原为 `<h1>`（已修复，见 P3-1），每个页面标题又是 `<h1>`——修复前任何页面都同时出现**两个一级标题**，文档大纲被破坏。这是 SEO/读屏层面的结构性错误，不是审美偏好。

以上三条都是**「修起来一行、错起来全局」**的典型。设计系统的价值就在于「一处定义、处处生效」，但反面是「一处错、处处错」——这次的对比度错误就是被 23 个文件共同放大的一次。

**好的方面也要说**：登录页有 sr-only 标签和 MFA 验证码流程、行内错误提示；留存报表空状态文案既说了「ClickHouse 未配置」又给了下一步指引；`.input`/`.btn` 都有 focus ring；活动态导航对比度 5.57:1、全部徽章、`btn-danger`（4.83:1）都达标。**没有发现 P0 级阻塞问题**——审核路径全部可渲染、无崩溃。

**一个曾经被误诊的源码缺陷，现已修复**（见 P2-1）：`App.vue` **无条件**将 `AppLayout` 包裹在所有路由周围，`/login` 也被嵌进应用外壳——登录卡片被推到视口下方约 450px，用户打开站点第一屏只看到侧边栏和一片空白，**登录入口是坏损的**。初版报告将其误判为「部署产物漂移、源码正确」；复核发现部署 dist 忠实渲染的正是这一源码缺陷，属**可修复的源码缺陷**。已通过条件渲染修复：`AppLayout` 仅当 `route.meta.requiresAuth !== false` 渲染，`/login` 独立渲染（居中卡片，无侧边栏）。

---

## 严重度分级

### P0（阻塞/致命）
无。审核的 13 条路由全部可渲染，无空白页（SPA 导航下）、无 JS 崩溃。

### P1（严重 — 对比度不达标 / 可访问性阻断）

| # | 问题 | before | after | commit |
|---|------|--------|-------|--------|
| P1-1 | `.btn-primary` 白字/`bg-primary-600` 对比度 **4.10:1** < 4.5:1（`main.css:17`，影响 23 个视图的主 CTA + 登录按钮）→ bg-700 **5.93:1**，hover→bg-800（7.56:1） | [before](shots-after/P1-1-before.png) | [after](shots-after/P1-1-after.png) | `c78b47a` |
| P1-2 | 纯图标按钮（通知铃/汉堡/登出）`text-gray-400` = **2.54:1** < 3:1（非文本，`AppLayout.vue:186,209,225`）→ gray-500 **4.83:1** | [before](shots-after/P1-2-before.png) | [after](shots-after/P1-2-after.png) | `f15b7c7` |
| P1-3 | 铃铛/汉堡按钮无 `aria-label`，读屏报「按钮」无功能说明（`AppLayout.vue:207,225`）→ 加 `aria-label="通知"`/`aria-label="打开菜单"` | [before](shots-after/P1-3-narrow-before.png) | [after](shots-after/P1-3-narrow-after.png) | `da8ce9f` |

### P2（中等）

| # | 问题 | before | after | commit |
|---|------|--------|-------|--------|
| P2-1 | `App.vue` **无条件**将 `AppLayout` 包裹在所有路由周围（含 `/login`）——登录页被嵌套进应用外壳，登录卡片起点 `y=1349`（视口 900px），首屏只见侧边栏 + 空白，登录入口损坏。**根因**：`App.vue` 模板恒为 `<AppLayout><RouterView/></AppLayout>`，未按路由 meta 区分认证页。**修复**：`AppLayout` 仅当 `route.meta.requiresAuth !== false` 渲染，`/login` 独立渲染（居中卡片，无侧边栏）。**修正说明**：初版附录 A 误判为「部署产物漂移、源码正确」；复核确认部署 dist 忠实渲染源码缺陷，属可修复的源码缺陷。 | [before](shots-after/P2-1-before.png) | [after](shots-after/P2-1-after.png) | `221a3f4` |

### P3（轻微）

| # | 问题 | before | after | commit |
|---|------|--------|-------|--------|
| P3-1 | 双 `<h1>`：侧边栏 Logo 为 `<h1>`，页面标题亦为 `<h1>`，文档大纲错乱（`AppLayout.vue:104` + 各页面标题）→ Logo 改为 `<span>`（视觉不变，DOM 层级修复） | [before](shots-after/P3-1-before.png) | [after](shots-after/P3-1-after.png) | `5fa96a1` |
| P3-2 | 偏离主色板的强调色标题（AI 检测器 4 条告警；`FinanceView.vue:128`、`FunnelsView.vue:357`、`PaymentFunnelView.vue:130,134`，`text-indigo-600`/`text-purple-600`）→ 改用 `primary`/`accent` 色板 | [before](shots-after/P3-2-before.png) | [after](shots-after/P3-2-after.png) | `fb98c7f` |
| P3-3 | 弹窗遮罩不可键盘关闭：18 处可点击关闭遮罩（15 处 `@click.self` + 3 处 `absolute inset-0` 背板），无 Escape 关闭、无 `role="dialog"`/`aria-modal`（13 个视图弹窗）→ 共享 `useEscapeClose` 组合式 + `role="dialog"`/`aria-modal`，加载遮罩加 `role="status"` | [before](shots-after/P3-3-before.png) | [after](shots-after/P3-3-after.png) | `3f47e58` |
| P3-4 | 「管理」分组标签 `text-gray-400` = 2.54:1 < 4.5:1（文本，`AppLayout.vue:137`）→ 升为 `text-gray-500`（4.83:1） | [before](shots-after/P3-4-before.png) | [after](shots-after/P3-4-after.png) | `f23cabe` |

---

## 逐维度结论

- **层级/排版**：布局统一（`lg:pl-64` + sticky 顶栏），侧边栏 29 项（27 导航 + 2 管理）含「管理」分组标签（非扁平）；P3-1 双 h1 修复前是唯一结构性问题。
- **色彩对比度（WCAG AA）**：`.btn-primary` 4.10:1、图标按钮 2.54:1、`管理`标签 2.54:1 不达标 → P1/P3 修复；`nav-active` 5.57:1、`btn-danger` 4.83:1、全部徽章、`btn-secondary` 均达标。
- **动效**：侧边栏 `transition-transform duration-300`、按钮 `transition-colors duration-200`、加载 spinner——克制且无过度动效；无 `prefers-reduced-motion` 降级（轻微，未单列）。
- **响应式**：✅ 良好。37 张表绝大多数 `overflow-x-auto`（RiskLabView/UsersView/WebhooksView 弹窗内约 5 张未套）；窄屏卡片堆叠、侧边栏抽屉化、汉堡可用；窄屏截图（390px）验证无横向溢出。
- **边界状态**：✅ 留存报表空状态文案优秀（「ClickHouse 未配置，留存报表不可用」+ 指引）；登录错误行内提示 + MFA 挑战流；未发现空白/死链状态。
- **UX 文案**：中文文案一致、无生硬英文直译；错误提示具体（登录失败明确）；按钮文案为动词式（登录/导出/保存）。

---

## 附录 A：登录页硬加载空白的根因定位（已修正）

对 `/login` 硬加载（纯静态、无 API）实测几何（修复前）：

```
#app
  div.min-h-screen            ← 布局外壳（AppLayout）
    div.fixed.w-64            ← 侧边栏（y=0）
    div.lg:pl-64              ← 主内容区（y=1253 ← 被侧边栏撑开）
      div.sticky (顶栏)        ← y=1253 h=64
      main.p-4.lg:p-8          ← y=1317 h=964
        div.min-h-screen (登录卡片) ← y=1349 h=900   ← 起点在视口(900px)之下
```

`main.innerText` 含完整登录表单（「登录到控制台/用户名/密码/登录」），但几何起点 y=1349 > 视口 900px → 首屏只见侧边栏 + 空白。

**修正后的判定**：初版报告曾将其误判为「dist（10-30）早于/异于当前 src 的构建漂移，源码正确」。复核发现：部署 dist 忠实渲染的正是 `App.vue` 的**源码缺陷**——`App.vue` 模板恒为 `<AppLayout><RouterView/></AppLayout>`，**无条件**将 AppLayout 包裹在**所有**路由周围（`/login` 的 `requiresAuth:false` 与其余路由一样被包进外壳），与 dist 是否漂移无关。**根因在源码**，故为可修复的源码缺陷，而非部署漂移。`router/index.js` 正确（`/login` 为独立顶层路由）、`LoginView.vue` 正确（独立居中卡片），唯 `App.vue` 的包裹逻辑错误。

**修复**（`221a3f4`）：`App.vue` 改为 `AppLayout v-if="route.meta.requiresAuth !== false"`，`/login` 走 `<RouterView v-else />` 独立渲染（居中卡片，无侧边栏）。修复后 `/login` 全页高度恰为视口（`login-full` 截图 1800px@2x = 900px 视口），登录卡片首屏完整可见。对照见 [P2-1-before](shots-after/P2-1-before.png) / [P2-1-after](shots-after/P2-1-after.png)。

## 附录 B：非 UI 缺陷的环境性说明

- **`/api/games` 数据形状不一致**：接口返回 `items`，`GamesView` 读取 `.content` → 本环境游戏列表空白。判定为后端/前端版本漂移，**非 UI 缺陷**，未改动。
- 审计所用截图来自**现有 dist 静态服务 + 本地后端（127.0.0.1:38085）+ Playwright SPA 导航**；演示站点（`oddsmaker.cuihairu.site`）返回 502，未能使用。

---

## 修复汇总

| Commit | 内容 | 类型 |
|--------|------|------|
| `c78b47a` | `fix(ui): primary button contrast`（`.btn-primary` bg-600→700，hover→800） | P1 修复 |
| `f15b7c7` | `fix(ui): icon button contrast`（铃/汉堡/登出 gray-400→500） | P1 修复 |
| `da8ce9f` | `fix(ui): icon button labels`（铃铛/汉堡加 aria-label） | P1 修复 |
| `5fa96a1` | `fix(ui): single page heading`（侧边栏 Logo `<h1>`→`<span>`，消除双 h1） | P3 修复 |
| `fb98c7f` | `fix(ui): stat title palette`（Finance/Funnels/PaymentFunnel 共 4 处 indigo/purple→primary/accent） | P3 修复 |
| `3f47e58` | `fix(ui): modal keyboard accessibility`（共享 `useEscapeClose` 组合式，13 视图 18 处弹窗 Escape+`role="dialog"`/`aria-modal`，加载遮罩 `role="status"`） | P3 修复 |
| `f23cabe` | `fix(ui): group label contrast`（「管理」标签 gray-400→500） | P3 修复 |
| `221a3f4` | `fix(ui): render login outside app layout`（`App.vue` 条件渲染 AppLayout，修复 `/login` 嵌套；根因见附录 A） | P2 修复 |

**统计**：UI 发现 8 项全部处理（P1×3、P2×1、P3×4 已修），另 2 项环境性说明（非 UI，不计入）。P1 批次当时 `web/node_modules` 为空未跑测试（改动均为样式类/属性级）；P3 批次每项提交前本地验证：`pnpm build` 绿 + vitest 全量 202/202 通过。P2-1（`221a3f4`）提交前 `pnpm build` 绿；vitest 全量在当前环境下受并行负载抖动影响（单次运行 0–13 例失败，逐次失败集不同，均集中在异步数据加载类用例；隔离重跑受影响文件——含 MetricAlertBell、RetentionView——通过 15/15，且**带与不带本修复均通过**），判定为既有环境性抖动、与 `221a3f4` 无关。**每项修复均带同视图 before/after 截图对**（`docs/review/shots-after/`，文件名对齐问题编号）。
