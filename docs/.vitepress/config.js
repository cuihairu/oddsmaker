import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(defineConfig({
  // Base path for GitHub Pages
  base: '/oddsmaker/',

  // Ignore dead links
  ignoreDeadLinks: true,

  // Locales
  locales: {
    root: {
      label: 'English',
      lang: 'en',
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
        ],
        sidebar: {
          '/zh/reference/': [
            {
              text: 'API 参考',
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

  // Site metadata
  title: 'Oddsmaker',
  description: 'Gaming Analytics Platform Documentation',

  // Theme configuration
  themeConfig: {
    // Navigation
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Guide', link: '/guide/' },
      { text: 'API Reference', link: '/reference/' },
      { text: 'Operations', link: '/operations/' },
      { text: '中文', link: '/zh/' },
    ],

    // Sidebar
    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Quick Start', link: '/guide/getting-started' },
            { text: 'Architecture', link: '/guide/architecture' },
          ]
        },
        {
          text: 'Core Concepts',
          items: [
            { text: 'Games & Environments', link: '/guide/games-environments' },
            { text: 'API Keys', link: '/guide/api-keys' },
            { text: 'Experiments', link: '/guide/experiments' },
          ]
        },
        {
          text: 'Advanced',
          items: [
            { text: 'Risk Management', link: '/guide/risk-management' },
            { text: 'Machine Learning', link: '/guide/machine-learning' },
            { text: 'Integrations', link: '/guide/integrations' },
          ]
        }
      ],
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
      '/operations/': [
        {
          text: 'Operations',
          items: [
            { text: 'Overview', link: '/operations/' },
            { text: 'Deployment', link: '/operations/deployment' },
            { text: 'Monitoring', link: '/operations/monitoring' },
            { text: 'Incident Response', link: '/operations/incident-response' },
            { text: 'Troubleshooting', link: '/operations/troubleshooting' },
          ]
        }
      ]
    },

    // Social links
    socialLinks: [
      { icon: 'github', link: 'https://github.com/cuihairu/oddsmaker' }
    ],

    // Footer
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © 2024 Oddsmaker'
    },

    // Search
    search: {
      provider: 'local'
    }
  }
}))
