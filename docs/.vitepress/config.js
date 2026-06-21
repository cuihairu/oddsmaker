import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(defineConfig({
  // Base path for GitHub Pages
  base: '/oddsmaker/',

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
          { text: '运维', link: '/zh/operations/deploy.k8s' },
          { text: '重设计', link: '/zh/redesign/' },
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
              ]
            },
            {
              text: '分析',
              items: [
                { text: '分析 API', link: '/zh/reference/analytics' },
                { text: '游戏分析场景', link: '/zh/reference/gaming-scenarios' },
              ]
            },
            {
              text: '分析专题',
              items: [
                { text: 'Flink 作业', link: '/zh/analysis/jobs' },
                { text: '实验平台', link: '/zh/analysis/experiments' },
                { text: '产品路线图', link: '/zh/analysis/roadmap' },
              ]
            }
          ],
          '/zh/operations/': [
            {
              text: '运维',
              items: [
                { text: 'K8s 部署', link: '/zh/operations/deploy.k8s' },
                { text: '可观测', link: '/zh/operations/observability' },
                { text: '运维手册', link: '/zh/operations/ops' },
                { text: '性能压测', link: '/zh/operations/perf' },
                { text: '性能调优', link: '/zh/operations/perf-tuning' },
                { text: 'Release 指南', link: '/zh/operations/release' },
              ]
            }
          ],
          '/zh/redesign/': [
            {
              text: '重设计',
              items: [
                { text: '概览', link: '/zh/redesign/' },
                { text: '01 - 评估', link: '/zh/redesign/01-assessment' },
                { text: '02 - 设计问题', link: '/zh/redesign/02-design-issues' },
                { text: '03 - 开源参考', link: '/zh/redesign/03-open-source-reference' },
                { text: '04 - 重设计', link: '/zh/redesign/04-redesign' },
                { text: '05 - 路线图', link: '/zh/redesign/05-roadmap' },
              ]
            }
          ],
          '/zh/getting-started/': [
            {
              text: '入门',
              items: [
                { text: '端到端验证', link: '/zh/getting-started/e2e' },
                { text: '开发者指南', link: '/zh/getting-started/developer' },
                { text: '贡献指南', link: '/zh/getting-started/contributing' },
              ]
            }
          ],
          '/zh/': [
            {
              text: '首页',
              items: [
                { text: '中文首页', link: '/zh/' },
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
    ],

    // Sidebar
    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
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
