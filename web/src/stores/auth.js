import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/services/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const token = ref(localStorage.getItem('token') || null)
  const loading = ref(false)
  const error = ref(null)
  const mfaRequired = ref(false)

  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.roles?.includes('ADMIN') || false)
  const userName = computed(() => user.value?.displayName || user.value?.username || '')

  async function login(username, password, mfaCode = null) {
    loading.value = true
    error.value = null
    mfaRequired.value = false

    try {
      const body = { username, password }
      if (mfaCode) body.mfaCode = mfaCode
      const response = await api.post('/api/auth/login', body)
      // MFA 挑战：密码已验过只差验证码，视图凭 mfaRequired 弹码输入后带 mfaCode 重发
      if (response.data.mfaRequired) {
        mfaRequired.value = true
        return false
      }
      token.value = response.data.token
      user.value = response.data.user
      localStorage.setItem('token', token.value)
      api.defaults.headers.common['Authorization'] = `Bearer ${token.value}`
      return true
    } catch (err) {
      error.value = err.response?.data?.message || '登录失败'
      return false
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    try {
      await api.post('/api/auth/logout')
    } catch (err) {
      // 忽略登出错误
    } finally {
      token.value = null
      user.value = null
      localStorage.removeItem('token')
      delete api.defaults.headers.common['Authorization']
    }
  }

  async function fetchUser() {
    if (!token.value) return
    
    try {
      api.defaults.headers.common['Authorization'] = `Bearer ${token.value}`
      const response = await api.get('/api/users/me')
      user.value = response.data
    } catch (err) {
      // Token无效，清除认证状态
      token.value = null
      user.value = null
      localStorage.removeItem('token')
      delete api.defaults.headers.common['Authorization']
    }
  }

  function init() {
    if (token.value) {
      fetchUser()
    }
  }

  return {
    user,
    token,
    loading,
    error,
    mfaRequired,
    isAuthenticated,
    isAdmin,
    userName,
    login,
    logout,
    fetchUser,
    init
  }
})