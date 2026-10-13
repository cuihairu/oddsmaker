<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const mfaCode = ref('')
const loading = ref(false)
const error = ref(null)

async function handleLogin() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  if (authStore.mfaRequired && !mfaCode.value) {
    error.value = '请输入 MFA 验证码'
    return
  }

  loading.value = true
  error.value = null

  const success = await authStore.login(username.value, password.value, mfaCode.value || null)

  if (success) {
    router.push('/')
  } else if (authStore.mfaRequired) {
    // 挑战不是错误：提示语由模板里的 mfaRequired 提示行展示
    error.value = null
  } else {
    error.value = authStore.error || '登录失败'
  }

  loading.value = false
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
    <div class="max-w-md w-full space-y-8">
      <div>
        <h1 class="text-center text-3xl font-bold text-primary-600">Oddsmaker</h1>
        <h2 class="mt-6 text-center text-2xl font-bold text-gray-900">
          登录到控制台
        </h2>
        <p class="mt-2 text-center text-sm text-gray-600">
          游戏分析与风控平台
        </p>
      </div>
      
      <form class="mt-8 space-y-6" @submit.prevent="handleLogin">
        <div class="rounded-md shadow-sm -space-y-px">
          <div>
            <label for="username" class="sr-only">用户名</label>
            <input
              id="username"
              v-model="username"
              name="username"
              type="text"
              autocomplete="username"
              required
              class="input rounded-t-lg rounded-b-none"
              placeholder="用户名"
            />
          </div>
          <div>
            <label for="password" class="sr-only">密码</label>
            <input
              id="password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              required
              :class="authStore.mfaRequired ? 'input rounded-none' : 'input rounded-t-none rounded-b-lg'"
              placeholder="密码"
            />
          </div>
          <div v-if="authStore.mfaRequired">
            <label for="mfaCode" class="sr-only">MFA 验证码</label>
            <input
              id="mfaCode"
              v-model="mfaCode"
              name="mfaCode"
              type="text"
              autocomplete="one-time-code"
              inputmode="numeric"
              required
              class="input rounded-t-none rounded-b-lg"
              placeholder="MFA 验证码"
            />
          </div>
        </div>

        <p v-if="authStore.mfaRequired" class="text-sm text-center text-primary-600">
          密码已验证，请输入 MFA 验证码
        </p>

        <div v-if="error" class="text-red-600 text-sm text-center">
          {{ error }}
        </div>

        <div>
          <button
            type="submit"
            :disabled="loading"
            class="btn btn-primary w-full"
          >
            <span v-if="loading" class="flex items-center justify-center">
              <svg class="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              登录中...
            </span>
            <span v-else>登录</span>
          </button>
        </div>

        <div class="text-center">
          <p class="text-sm text-gray-600">
            使用管理员账号登录
          </p>
        </div>
      </form>
    </div>
  </div>
</template>