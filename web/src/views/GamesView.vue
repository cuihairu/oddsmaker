<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/services/api'
import { useEscapeClose } from '@/composables/useEscapeClose'

const router = useRouter()
const games = ref([])
const loading = ref(true)
const showCreateModal = ref(false)
useEscapeClose(showCreateModal, () => (showCreateModal.value = false))
const newGame = ref({
  name: '',
  displayName: '',
  description: '',
  genre: 'OTHER',
  platforms: ['MOBILE']
})

const genres = [
  { value: 'ACTION', label: '动作' },
  { value: 'RPG', label: '角色扮演' },
  { value: 'STRATEGY', label: '策略' },
  { value: 'PUZZLE', label: '解谜' },
  { value: 'CASUAL', label: '休闲' },
  { value: 'SIMULATION', label: '模拟' },
  { value: 'SPORTS', label: '体育' },
  { value: 'RACING', label: '竞速' },
  { value: 'SHOOTER', label: '射击' },
  { value: 'MMORPG', label: '大型多人在线角色扮演' },
  { value: 'MOBA', label: '多人在线战斗竞技' },
  { value: 'BATTLE_ROYALE', label: '大逃杀' },
  { value: 'OTHER', label: '其他' }
]

const platforms = [
  { value: 'WEB', label: '网页' },
  { value: 'MOBILE', label: '移动端' },
  { value: 'PC', label: 'PC端' },
  { value: 'CONSOLE', label: '主机' },
  { value: 'VR', label: '虚拟现实' },
  { value: 'AR', label: '增强现实' }
]

onMounted(async () => {
  await loadGames()
})

async function loadGames() {
  loading.value = true
  try {
    const response = await api.get('/api/games')
    games.value = response.data.content || []
  } catch (error) {
    console.error('Failed to load games:', error)
  } finally {
    loading.value = false
  }
}

async function createGame() {
  try {
    await api.post('/api/games', newGame.value)
    showCreateModal.value = false
    newGame.value = {
      name: '',
      displayName: '',
      description: '',
      genre: 'OTHER',
      platforms: ['MOBILE']
    }
    await loadGames()
  } catch (error) {
    console.error('Failed to create game:', error)
    alert('创建游戏失败: ' + (error.response?.data?.message || error.message))
  }
}

function viewGame(gameId) {
  router.push(`/games/${gameId}`)
}

function getStatusColor(status) {
  const colors = {
    DEVELOPMENT: 'bg-yellow-100 text-yellow-800',
    TESTING: 'bg-blue-100 text-blue-800',
    LIVE: 'bg-green-100 text-green-800',
    MAINTENANCE: 'bg-orange-100 text-orange-800',
    DISCONTINUED: 'bg-gray-100 text-gray-800'
  }
  return colors[status] || 'bg-gray-100 text-gray-800'
}

function getStatusLabel(status) {
  const labels = {
    DEVELOPMENT: '开发中',
    TESTING: '测试中',
    LIVE: '已上线',
    MAINTENANCE: '维护中',
    DISCONTINUED: '已停服'
  }
  return labels[status] || status
}

function getGenreLabel(genre) {
  const found = genres.find(g => g.value === genre)
  return found ? found.label : genre
}
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">游戏管理</h1>
        <p class="mt-1 text-sm text-gray-500">
          管理您的游戏项目
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 sm:flex-none">
        <button @click="showCreateModal = true" class="btn btn-primary">
          创建游戏
        </button>
      </div>
    </div>

    <!-- 游戏列表 -->
    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="games.length === 0" class="text-center py-12">
      <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 4a2 2 0 114 0v1a1 1 0 001 1h3a1 1 0 011 1v3a1 1 0 01-1 1h-1a2 2 0 100 4h1a1 1 0 011 1v3a1 1 0 01-1 1h-3a1 1 0 01-1-1v-1a2 2 0 10-4 0v1a1 1 0 01-1 1H7a1 1 0 01-1-1v-3a1 1 0 00-1-1H4a2 2 0 110-4h1a1 1 0 001-1V7a1 1 0 011-1h3a1 1 0 001-1V4z" />
      </svg>
      <h3 class="mt-2 text-sm font-medium text-gray-900">暂无游戏</h3>
      <p class="mt-1 text-sm text-gray-500">开始创建您的第一个游戏项目</p>
      <div class="mt-6">
        <button @click="showCreateModal = true" class="btn btn-primary">
          创建游戏
        </button>
      </div>
    </div>

    <div v-else class="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="game in games"
        :key="game.id"
        class="card cursor-pointer hover:shadow-md transition-shadow"
        @click="viewGame(game.id)"
      >
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-medium text-gray-900">{{ game.displayName || game.name }}</h3>
          <span :class="[getStatusColor(game.status), 'badge']">
            {{ getStatusLabel(game.status) }}
          </span>
        </div>
        
        <p v-if="game.description" class="text-sm text-gray-500 mb-4 line-clamp-2">
          {{ game.description }}
        </p>
        
        <div class="flex flex-wrap gap-2 mb-4">
          <span class="badge bg-gray-100 text-gray-800">
            {{ getGenreLabel(game.genre) }}
          </span>
          <span
            v-for="platform in game.platforms"
            :key="platform"
            class="badge bg-blue-100 text-blue-800"
          >
            {{ platform }}
          </span>
        </div>
        
        <div class="text-xs text-gray-400">
          创建于 {{ new Date(game.createdAt).toLocaleDateString('zh-CN') }}
        </div>
      </div>
    </div>

    <!-- 创建游戏模态框 -->
    <div
      v-if="showCreateModal"
      class="fixed inset-0 z-50 overflow-y-auto"
      @click.self="showCreateModal = false"
    >
      <div class="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
        <div class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"></div>
        
        <div role="dialog" aria-modal="true" class="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full">
          <div class="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
            <h3 class="text-lg font-medium text-gray-900 mb-4">创建新游戏</h3>
            
            <form @submit.prevent="createGame">
              <div class="space-y-4">
                <div>
                  <label class="label">游戏名称 *</label>
                  <input
                    v-model="newGame.name"
                    type="text"
                    required
                    class="input"
                    placeholder="例如: game_demo"
                  />
                </div>
                
                <div>
                  <label class="label">显示名称</label>
                  <input
                    v-model="newGame.displayName"
                    type="text"
                    class="input"
                    placeholder="例如: 我的游戏"
                  />
                </div>
                
                <div>
                  <label class="label">描述</label>
                  <textarea
                    v-model="newGame.description"
                    class="input"
                    rows="3"
                    placeholder="游戏描述..."
                  ></textarea>
                </div>
                
                <div>
                  <label class="label">游戏类型</label>
                  <select v-model="newGame.genre" class="input">
                    <option v-for="genre in genres" :key="genre.value" :value="genre.value">
                      {{ genre.label }}
                    </option>
                  </select>
                </div>
                
                <div>
                  <label class="label">平台</label>
                  <div class="grid grid-cols-2 gap-2">
                    <label
                      v-for="platform in platforms"
                      :key="platform.value"
                      class="flex items-center"
                    >
                      <input
                        v-model="newGame.platforms"
                        type="checkbox"
                        :value="platform.value"
                        class="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
                      />
                      <span class="ml-2 text-sm text-gray-700">{{ platform.label }}</span>
                    </label>
                  </div>
                </div>
              </div>
              
              <div class="mt-5 sm:mt-4 sm:flex sm:flex-row-reverse">
                <button type="submit" class="btn btn-primary w-full sm:w-auto sm:ml-3">
                  创建
                </button>
                <button
                  type="button"
                  @click="showCreateModal = false"
                  class="btn btn-secondary w-full sm:w-auto mt-3 sm:mt-0"
                >
                  取消
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>