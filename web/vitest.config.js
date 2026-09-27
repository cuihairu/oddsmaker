import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 独立于 vite.config.js：build/dev 走原配置，测试链路（jsdom + SFC 编译 + 覆盖率）只在这里生效
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: 'coverage',
      // 全量 src 计入（含未测的 main.js/App.vue——如实记账，不做报告内隐藏）
      include: ['src/**/*.js', 'src/**/*.vue'],
      exclude: ['src/assets/**']
    }
  }
})
