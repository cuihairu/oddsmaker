import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

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
          { text: '中文', link: '/zh/' },
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
          ]
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
          { text: 'English', link: '/' },
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
          ]
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
