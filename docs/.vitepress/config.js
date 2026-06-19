import { defineConfig } from 'vitepress'

export default defineConfig({
  // Base path for GitHub Pages
  base: '/oddsmaker/',

  // Ignore dead links
  ignoreDeadLinks: true,

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
})
