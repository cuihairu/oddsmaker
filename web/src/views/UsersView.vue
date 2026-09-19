<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import api from '@/services/api'
import { useGameList } from '@/composables/useGameList'

const users = ref([])
const loading = ref(true)
const error = ref('')
const success = ref('')

const { games, loadGames } = useGameList()

onMounted(async () => {
  loadGames()
  await loadUsers()
})

async function loadUsers() {
  loading.value = true
  try {
    const response = await api.get('/api/users')
    users.value = response.data.content || []
  } catch (e) {
    error.value = e.response?.data?.message || '加载用户失败'
  } finally {
    loading.value = false
  }
}

function getStatusColor(status) {
  const colors = {
    ACTIVE: 'bg-green-100 text-green-800',
    INACTIVE: 'bg-gray-100 text-gray-800',
    LOCKED: 'bg-red-100 text-red-800',
    PENDING: 'bg-yellow-100 text-yellow-800'
  }
  return colors[status] || 'bg-gray-100 text-gray-800'
}

function getStatusLabel(status) {
  const labels = {
    ACTIVE: '活跃',
    INACTIVE: '未激活',
    LOCKED: '已锁定',
    PENDING: '待审核'
  }
  return labels[status] || status
}

// ===== 角色分配（RBAC：user_role_assignments，global/game/environment 三级 scope）=====

const raTarget = ref(null)      // 目标用户
const assignments = ref([])     // 现有分配
const roles = ref([])           // 可分配角色（/api/roles）
const raLoading = ref(false)
const raError = ref('')
const raForm = reactive({ roleId: '', scope: 'GLOBAL', gameId: '', environment: '' })
const saving = ref(false)
const revoking = ref('')
const environments = ref([])

function openRA(user) {
  raTarget.value = user
  raError.value = ''
  raForm.roleId = ''
  raForm.scope = 'GLOBAL'
  raForm.gameId = ''
  raForm.environment = ''
  environments.value = []
  raLoading.value = true
  Promise.all([
    api.get('/api/roles'),
    loadAssignments(user.id)
  ])
    .then(([rolesRes]) => {
      roles.value = rolesRes.data || []
    })
    .catch((e) => {
      raError.value = e.response?.data?.message || '加载角色失败'
    })
    .finally(() => {
      raLoading.value = false
    })
}

async function loadAssignments(userId) {
  const response = await api.get(`/api/users/${userId}/role-assignments`)
  assignments.value = response.data || []
}

watch(() => raForm.scope, () => {
  raForm.gameId = ''
  raForm.environment = ''
  environments.value = []
})

watch(() => raForm.gameId, async (gameId) => {
  raForm.environment = ''
  environments.value = []
  if (raForm.scope === 'ENVIRONMENT' && gameId) {
    try {
      const response = await api.get(`/api/games/${gameId}/environments`)
      environments.value = response.data || []
    } catch {
      environments.value = []
    }
  }
})

async function submitAssignment() {
  raError.value = ''
  if (!raForm.roleId) {
    raError.value = '请选择角色'
    return
  }
  if (raForm.scope !== 'GLOBAL' && !raForm.gameId) {
    raError.value = '请选择游戏'
    return
  }
  if (raForm.scope === 'ENVIRONMENT' && !raForm.environment) {
    raError.value = '请选择环境'
    return
  }
  saving.value = true
  try {
    await api.post(`/api/users/${raTarget.value.id}/role-assignments`, {
      roleId: raForm.roleId,
      gameId: raForm.scope === 'GLOBAL' ? null : raForm.gameId,
      environment: raForm.scope === 'ENVIRONMENT' ? raForm.environment : null
    })
    success.value = `已为 ${raTarget.value.username} 分配角色`
    await loadAssignments(raTarget.value.id)
  } catch (e) {
    raError.value = e.response?.data?.message || '分配失败'
  } finally {
    saving.value = false
  }
}

async function revokeAssignment(a) {
  if (!confirm(`确定回收角色「${a.roleName}」？`)) return
  revoking.value = a.roleId + (a.gameId || '') + (a.environment || '')
  try {
    await api.delete(`/api/users/${raTarget.value.id}/role-assignments`, {
      params: { roleId: a.roleId, gameId: a.gameId || undefined, environment: a.environment || undefined }
    })
    success.value = '已回收角色'
    await loadAssignments(raTarget.value.id)
  } catch (e) {
    raError.value = e.response?.data?.message || '回收失败'
  } finally {
    revoking.value = ''
  }
}

function scopeLabel(scope) {
  const labels = { global: '全局', game: '游戏', environment: '环境' }
  return labels[scope] || scope
}
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">用户管理</h1>
        <p class="mt-1 text-sm text-gray-500">
          管理系统用户
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 sm:flex-none">
        <button class="btn btn-primary">
          添加用户
        </button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>
    <div v-if="success" class="card mb-6 text-sm text-green-600">{{ success }}</div>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="users.length === 0" class="text-center py-12">
      <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
      </svg>
      <h3 class="mt-2 text-sm font-medium text-gray-900">暂无用户</h3>
      <p class="mt-1 text-sm text-gray-500">添加一个用户来开始</p>
    </div>

    <div v-else class="card">
      <div class="overflow-x-auto">
        <table class="table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>邮箱</th>
              <th>显示名称</th>
              <th>状态</th>
              <th>角色</th>
              <th>最后登录</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td class="font-medium">{{ user.username }}</td>
              <td>{{ user.email || '-' }}</td>
              <td>{{ user.displayName || '-' }}</td>
              <td>
                <span :class="[getStatusColor(user.status), 'badge']">
                  {{ getStatusLabel(user.status) }}
                </span>
              </td>
              <td>
                <div class="flex flex-wrap gap-1">
                  <span
                    v-for="role in user.roles"
                    :key="role"
                    class="badge bg-blue-100 text-blue-800"
                  >
                    {{ role }}
                  </span>
                </div>
              </td>
              <td>
                {{ user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString('zh-CN') : '从未登录' }}
              </td>
              <td>
                <button
                  class="text-xs text-blue-600 hover:underline"
                  @click="openRA(user)"
                >
                  角色分配
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 角色分配弹层 -->
    <div
      v-if="raTarget"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="raTarget = null"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[85vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-semibold text-gray-900">
            角色分配 — {{ raTarget.username }}
          </h3>
          <button class="text-sm text-gray-500 hover:text-gray-700" @click="raTarget = null">关闭</button>
        </div>

        <p v-if="raError" class="mb-4 text-sm text-red-600">{{ raError }}</p>

        <div v-if="raLoading" class="text-center py-8">
          <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600 mx-auto"></div>
        </div>

        <template v-else>
          <h4 class="text-xs font-medium text-gray-500 mb-2">现有分配</h4>
          <div v-if="assignments.length === 0" class="text-sm text-gray-400 mb-4">暂无角色分配</div>
          <table v-else class="table mb-6">
            <thead>
              <tr>
                <th>角色</th>
                <th>范围</th>
                <th>分配人</th>
                <th>分配时间</th>
                <th>状态</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in assignments" :key="a.roleId + (a.gameId || '') + (a.environment || '')">
                <td class="font-medium">{{ a.roleName }}</td>
                <td>
                  <span class="badge bg-gray-100 text-gray-800">{{ scopeLabel(a.scope) }}</span>
                  <span v-if="a.gameId" class="ml-1 text-xs text-gray-500">{{ a.gameId }}</span>
                  <span v-if="a.environment" class="ml-1 text-xs text-gray-500">{{ a.environment }}</span>
                </td>
                <td class="text-xs text-gray-500">{{ a.assignedBy || '-' }}</td>
                <td class="text-xs text-gray-500">
                  {{ a.assignedAt ? new Date(a.assignedAt).toLocaleString('zh-CN') : '-' }}
                </td>
                <td>
                  <span :class="['badge', a.valid ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800']">
                    {{ a.valid ? '生效中' : '已失效' }}
                  </span>
                </td>
                <td>
                  <button
                    class="text-xs text-red-600 hover:underline"
                    :disabled="revoking === a.roleId + (a.gameId || '') + (a.environment || '')"
                    @click="revokeAssignment(a)"
                  >
                    {{ revoking === a.roleId + (a.gameId || '') + (a.environment || '') ? '回收中...' : '回收' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>

          <h4 class="text-xs font-medium text-gray-500 mb-2">新增分配</h4>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label class="block text-xs text-gray-500 mb-1">角色 <span class="text-red-500">*</span></label>
              <select v-model="raForm.roleId" class="input">
                <option value="">请选择角色</option>
                <option v-for="r in roles" :key="r.id" :value="r.id">
                  {{ r.name }}（{{ r.id }}）
                </option>
              </select>
            </div>
            <div>
              <label class="block text-xs text-gray-500 mb-1">范围</label>
              <select v-model="raForm.scope" class="input">
                <option value="GLOBAL">全局</option>
                <option value="GAME">指定游戏</option>
                <option value="ENVIRONMENT">游戏环境</option>
              </select>
            </div>
            <div v-if="raForm.scope !== 'GLOBAL'">
              <label class="block text-xs text-gray-500 mb-1">游戏 <span class="text-red-500">*</span></label>
              <select v-model="raForm.gameId" class="input">
                <option value="">请选择游戏</option>
                <option v-for="g in games" :key="g.id" :value="g.id">
                  {{ g.displayName || g.name }}
                </option>
              </select>
            </div>
            <div v-if="raForm.scope === 'ENVIRONMENT'">
              <label class="block text-xs text-gray-500 mb-1">环境 <span class="text-red-500">*</span></label>
              <select v-model="raForm.environment" class="input">
                <option value="">请选择环境</option>
                <option v-for="env in environments" :key="env.id" :value="env.id">
                  {{ env.displayName || env.name }}
                </option>
              </select>
            </div>
          </div>

          <div class="mt-6 flex justify-end gap-3">
            <button class="btn btn-secondary" @click="raTarget = null">关闭</button>
            <button class="btn btn-primary" :disabled="saving" @click="submitAssignment">
              {{ saving ? '分配中...' : '分配角色' }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
