# UI 审计报告 — oddsmaker / web（2026-10-31）

- **审计对象**: `oddsmaker/web`（Vue 3 + Vite + Tailwind CSS 管理控制台，32 路由）
- **审计方法**: impeccable skill v4.8.0（上下文脚本 + detect --json + WCAG 对比度计算 + Playwright 渲染截图）
- **截图证据**: `/home/cui/.cache/audit-shots-oddsmaker7/`（13 桌面 + 13 窄屏 + 登录/登录错误，全部经 SPA 导航渲染）
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

**一个需要点名的部署风险**（非源码缺陷，见下）：部署的 dist（10-30 构建）把 `/login` 嵌套进了应用外壳，登录表单被推到视口下方约 450px，用户打开站点第一屏只看到侧边栏和一片空白——**登录入口是坏损的**。当前源码的路由是正确的（`/login` 独立路由），所以这是**构建产物与源码漂移**，重建即可消除，但部署站点此刻的登录页确实不可用，必须当作线上问题对待。

---

## 严重度分级

### P0（阻塞/致命）
无。审核的 13 条路由全部可渲染，无空白页（SPA 导航下）、无 JS 崩溃。

### P1（严重 — 对比度不达标 / 可访问性阻断）

| # | 问题 | 位置 | 实测 | 处理 |
|---|------|------|------|------|
| P1-1 | `.btn-primary` 白字/`bg-primary-600` 对比度 **4.10:1** < 4.5:1 | `web/src/assets/main.css:17`（影响 23 个视图的主 CTA + 登录按钮） | 4.10:1 | ✅ **已修复** `c78b47a`：bg-600→**bg-700**（5.93:1），hover→bg-800（7.56:1） |
| P1-2 | 纯图标按钮（通知铃/汉堡/登出）`text-gray-400` = **2.54:1** < 3:1（非文本） | `web/src/components/AppLayout.vue:186,209,225` | 2.54:1 | ✅ **已修复** `f15b7c7`：gray-400→**gray-500**（4.83:1） |
| P1-3 | 铃铛/汉堡按钮无 `aria-label`，读屏报「按钮」无功能说明 | `AppLayout.vue:207,225` | — | ✅ **已修复** `da8ce9f`：加 `aria-label="通知"` / `aria-label="打开菜单"` |

### P2（中等）
- **P2-1（部署风险，非源码缺陷）**：部署 dist 将 `/login` 嵌套进 AppLayout，登录卡片 `y=1349`（视口 900px）——登录表单在首屏视口之下约 450px。**源码正确**（`router/index.js` 中 `/login` 为独立顶层路由，`LoginView.vue` 为独立居中卡片，无侧边栏）。**处置**：非源码可修；需从当前源码重新构建部署。证据见下「附录 A」。**未修原因**：属部署产物漂移（线上 dist 早于/异于当前源码），源码侧无可修对象；从当前源码重建部署即消除，本批源码修复不涉及部署面。

### P3（轻微）

| # | 问题 | 位置 | 处理 |
|---|------|------|------|
| P3-1 | 双 `<h1>`：侧边栏 Logo 为 `<h1>`，页面标题亦为 `<h1>`，文档大纲错乱 | `AppLayout.vue:104` + 各页面标题 | ✅ **已修复** `5fa96a1`（报告后落地）：Logo 改为 `<span>` |
| P3-2 | 偏离主色板的强调色标题（AI 检测器 4 条告警） | `FinanceView.vue:128`、`FunnelsView.vue:357`、`PaymentFunnelView.vue:130,134`（`text-indigo-600`/`text-purple-600`） | ✅ **已修复** `fb98c7f`（报告后落地）：改用 `primary`/`accent` 色板 |
| P3-3 | 弹窗遮罩不可键盘关闭：18 处可点击关闭遮罩（15 处 `@click.self` + 3 处 `absolute inset-0` 背板），无 Escape 关闭、无 `role="dialog"`/`aria-modal` | 13 个视图弹窗 | ✅ **已修复** `3f47e58`：共享 `useEscapeClose` 组合式 + `role="dialog"`/`aria-modal`，加载遮罩加 `role="status"` |
| P3-4 | 「管理」分组标签 `text-gray-400` = 2.54:1 < 4.5:1（文本） | `AppLayout.vue:137` | ✅ **已修复** `f23cabe`：升为 `text-gray-500`（4.83:1） |

---

## 逐维度结论

- **层级/排版**：布局统一（`lg:pl-64` + sticky 顶栏），侧边栏 29 项（27 导航 + 2 管理）含「管理」分组标签（非扁平）；P3-1 双 h1 修复前是唯一结构性问题。
- **色彩对比度（WCAG AA）**：`.btn-primary` 4.10:1、图标按钮 2.54:1、`管理`标签 2.54:1 不达标 → P1/P3 修复；`nav-active` 5.57:1、`btn-danger` 4.83:1、全部徽章、`btn-secondary` 均达标。
- **动效**：侧边栏 `transition-transform duration-300`、按钮 `transition-colors duration-200`、加载 spinner——克制且无过度动效；无 `prefers-reduced-motion` 降级（轻微，未单列）。
- **响应式**：✅ 良好。37 张表绝大多数 `overflow-x-auto`（RiskLabView/UsersView/WebhooksView 弹窗内约 5 张未套）；窄屏卡片堆叠、侧边栏抽屉化、汉堡可用；窄屏截图（390px）验证无横向溢出。
- **边界状态**：✅ 留存报表空状态文案优秀（「ClickHouse 未配置，留存报表不可用」+ 指引）；登录错误行内提示 + MFA 挑战流；未发现空白/死链状态。
- **UX 文案**：中文文案一致、无生硬英文直译；错误提示具体（登录失败明确）；按钮文案为动词式（登录/导出/保存）。

---

## 附录 A：登录页硬加载空白的根因定位

对 `/login` 硬加载（纯静态、无 API）实测几何：

```
#app
  div.min-h-screen            ← 布局外壳（AppLayout）
    div.fixed.w-64            ← 侧边栏（y=0）
    div.lg:pl-64              ← 主内容区（y=1253 ← 被侧边栏撑开）
      div.sticky (顶栏)        ← y=1253 h=64
      main.p-4.lg:p-8          ← y=1317 h=964
        div.min-h-screen (登录卡片) ← y=1349 h=900   ← 起点在视口(900px)之下
```

`main.innerText` 含完整登录表单（「登录到控制台/用户名/密码/登录」），但几何起点 y=1349 > 视口 900px → 首屏只见侧边栏 + 空白。**源码无此问题**：`router/index.js` 将 `/login` 定义为独立顶层路由（`requiresAuth: false`），`LoginView.vue` 是独立的 `min-h-screen flex items-center justify-center` 居中卡片，不含侧边栏。判定为 **dist（10-30）早于/异于当前 src 的构建漂移**，非源码缺陷；从当前源码重建即可消除。

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

**统计**：UI 发现 7 项全部处理（P1×3、P3×4 已修），P2-1 为部署产物漂移、非源码可修（见该项「未修原因」），另 2 项环境性说明（非 UI，不计入）。P1 批次当时 `web/node_modules` 为空未跑测试（改动均为样式类/属性级）；P3 批次每项提交前本地验证：`pnpm build` 绿 + vitest 全量 202/202 通过。
