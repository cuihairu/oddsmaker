import { ref, watch } from 'vue'
import api from '@/services/api'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

// 模块级单例：当前游戏的 ACTIVE 分群列表 + 报表页选中的 segment_id（'' 表示不过滤）
const segments = ref([])
const segmentId = ref('')
const loading = ref(false)

async function loadSegments() {
  if (!currentGameId.value) {
    segments.value = []
    return
  }
  loading.value = true
  try {
    const response = await api.get(`/api/games/${currentGameId.value}/segments`)
    segments.value = (response.data || []).filter((s) => s.status === 'ACTIVE')
  } catch (e) {
    console.error('Failed to load segments:', e)
    segments.value = []
  } finally {
    loading.value = false
  }
}

watch(currentGameId, () => {
  segmentId.value = ''
  loadSegments()
})

export function useSegments() {
  return { segments, segmentId, loading, loadSegments }
}
