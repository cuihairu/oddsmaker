import js from '@eslint/js'
import vue from 'eslint-plugin-vue'

export default [
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**'] },
  js.configs.recommended,
  ...vue.configs['flat/essential'],
  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        window: 'readonly', document: 'readonly', console: 'readonly',
        setTimeout: 'readonly', clearTimeout: 'readonly', setInterval: 'readonly', clearInterval: 'readonly',
        fetch: 'readonly', localStorage: 'readonly', sessionStorage: 'readonly', location: 'readonly',
        navigator: 'readonly', URL: 'readonly', Blob: 'readonly', FormData: 'readonly',
        Headers: 'readonly', Request: 'readonly', Response: 'readonly', AbortController: 'readonly',
        performance: 'readonly', crypto: 'readonly', TextEncoder: 'readonly', TextDecoder: 'readonly',
        FileReader: 'readonly', Image: 'readonly', XMLHttpRequest: 'readonly',
        alert: 'readonly', confirm: 'readonly', Event: 'readonly', CustomEvent: 'readonly',
        MutationObserver: 'readonly', IntersectionObserver: 'readonly', ResizeObserver: 'readonly',
        requestAnimationFrame: 'readonly', cancelAnimationFrame: 'readonly',
        WebSocket: 'readonly', EventSource: 'readonly', Node: 'readonly', HTMLElement: 'readonly',
        Element: 'readonly', SVGElement: 'readonly', CSSStyleSheet: 'readonly',
        matchMedia: 'readonly', getComputedStyle: 'readonly', history: 'readonly',
        screen: 'readonly', innerWidth: 'readonly', innerHeight: 'readonly', devicePixelRatio: 'readonly',
        process: 'readonly', __dirname: 'readonly', module: 'readonly', require: 'readonly',
        describe: 'readonly', it: 'readonly', expect: 'readonly', vi: 'readonly', beforeEach: 'readonly', afterEach: 'readonly', beforeAll: 'readonly', afterAll: 'readonly'
      }
    },
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      'no-useless-assignment': 'off',
      'vue/multi-word-component-names': 'off'
    }
  }
]
