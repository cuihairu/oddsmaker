import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

// 调研报告组：跨目录（docs/research、docs 根、zh/redesign），四份互引成一套
const researchSidebar = [
  {
    text: '调研报告',
    items: [
      { text: '成熟产品调研（交互与 SDK）', link: '/research/mature-products-survey' },
      { text: '竞品对比与功能差距', link: '/competitive-analysis' },
      { text: 'MMP 归因接入评估', link: '/mmp-attribution-evaluation' },
      { text: '开源参考项目取舍', link: '/zh/redesign/03-open-source-reference' }
    ]
  }
]

// 重设计系列侧边栏（zh/redesign 01~07），供 03 参考取舍等页挂载
const redesignSidebar = [
  {
    text: '平台重设计',
    items: [
      { text: '索引', link: '/zh/redesign/' },
      { text: '01 现状评估', link: '/zh/redesign/01-assessment' },
      { text: '02 关键设计问题', link: '/zh/redesign/02-design-issues' },
      { text: '03 参考项目取舍', link: '/zh/redesign/03-open-source-reference' },
      { text: '04 新架构', link: '/zh/redesign/04-redesign' },
      { text: '05 实施路线', link: '/zh/redesign/05-roadmap' },
      { text: '06 2.0 重构计划书', link: '/zh/redesign/06-od2-restructure-plan' },
      { text: '07 B10 设计定稿', link: '/zh/redesign/07-b10-data-quality-feature-store' }
    ]
  }
]

export default withMermaid(defineConfig({
  base: '/oddsmaker/',
  ignoreDeadLinks: true,

  locales: {
    root: {
      label: 'English',
      lang: 'en',
      title: 'Oddsmaker',
      description: 'Gaming Analytics Platform Documentation',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/' },
          { text: 'API Reference', link: '/reference/' },
          { text: '调研报告', link: '/research/mature-products-survey' },
          // 语言切换只用 VitePress 原生 locale 切换器（locales 自动渲染），不在此手写第二套
        ],
        sidebar: {
          '/reference/': [
            {
              text: 'API Reference',
              items: [
                { text: 'Overview', link: '/reference/' },
                { text: 'Authentication', link: '/reference/authentication' },
                { text: 'Games API', link: '/reference/games' },
                { text: 'Environments API', link: '/reference/environments' },
                { text: 'Experiments API', link: '/reference/experiments' },
                { text: 'Risk API', link: '/reference/risk' },
                { text: 'ML Models API', link: '/reference/ml-models' },
              ]
            },
            {
              text: 'Analytics',
              items: [
                { text: 'Analytics API', link: '/reference/analytics' },
                { text: 'Gaming Scenarios', link: '/reference/gaming-scenarios' },
              ]
            }
          ],
          // 调研文档在根 locale 各自路径下挂同一组侧边栏
          '/research/': researchSidebar,
          '/competitive-analysis': researchSidebar,
          '/mmp-attribution-evaluation': researchSidebar
        },
        editLink: {
          pattern: 'https://github.com/cuihairu/oddsmaker/edit/main/docs/:path',
          text: 'Edit this page on GitHub'
        }
      }
    },
    zh: {
      label: '中文',
      lang: 'zh-CN',
      title: 'Oddsmaker',
      description: '游戏分析平台文档',
      themeConfig: {
        nav: [
          { text: '首页', link: '/zh/' },
          { text: 'API 文档', link: '/zh/reference/' },
          { text: '调研报告', link: '/research/mature-products-survey' },
          // 语言切换只用 VitePress 原生 locale 切换器（locales 自动渲染），不在此手写第二套
        ],
        sidebar: {
          '/zh/reference/': [
            {
              text: 'API 参考',
              items: [
                { text: '概览', link: '/zh/reference/' },
                { text: '系统架构', link: '/zh/reference/architecture' },
                { text: '采集 API', link: '/zh/reference/api' },
                { text: 'API 参考', link: '/zh/reference/api-reference' },
                { text: '控制面', link: '/zh/reference/control' },
                { text: '环境与存储', link: '/zh/reference/environment-and-storage' },
                { text: 'SDK 设计规范', link: '/zh/reference/sdk-design' },
                { text: '维度数据同步', link: '/zh/reference/dimension-sync' },
                { text: '后续推进规划', link: '/zh/reference/follow-up-plan' },
              ]
            },
            {
              text: '分析',
              items: [
                { text: '分析 API', link: '/zh/reference/analytics' },
                { text: '游戏分析场景', link: '/zh/reference/gaming-scenarios' },
              ]
            }
          ],
          // 重设计系列（01~07）挂系列侧边栏，03 参考取舍由此进入导航
          '/zh/redesign/': redesignSidebar
        },
        editLink: {
          pattern: 'https://github.com/cuihairu/oddsmaker/edit/main/docs/:path',
          text: '在 GitHub 上编辑此页面'
        },
        lastUpdated: {
          text: '最后更新于'
        },
        docFooter: {
          prev: '上一页',
          next: '下一页'
        },
        outline: {
          label: '页面导航'
        }
      }
    }
  },

  title: 'Oddsmaker',
  description: 'Gaming Analytics Platform Documentation',

  themeConfig: {
    logo: '/logo.svg',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/cuihairu/oddsmaker' }
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © 2024 Oddsmaker'
    },
    search: {
      provider: 'local'
    }
  }
}))
