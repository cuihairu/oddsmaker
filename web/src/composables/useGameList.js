import { ref } from 'vue'
import api from '@/services/api'

const STORAGE_KEY = 'oddsmaker.selectedGameId'
const games = ref([])
const currentGameId = ref(localStorage.getItem(STORAGE_KEY) || '')
const loading = ref(false)
const loaded = ref(false)

async function loadGames() {
  if (loading.value) return
  loading.value = true
  try {
    const response = await api.get('/api/games')
    games.value = response.data.content || []
    if (!currentGameId.value && games.value.length > 0) {
      currentGameId.value = games.value[0].id
      persist()
    }
    loaded.value = true
  } catch (error) {
    console.error('Failed to load games:', error)
  } finally {
    loading.value = false
  }
}

function persist() {
  if (currentGameId.value) {
    localStorage.setItem(STORAGE_KEY, currentGameId.value)
  }
}

function selectGame(gameId) {
  currentGameId.value = gameId
  persist()
}

export function useGameList() {
  if (!loaded.value) {
    loadGames()
  }
  return { games, currentGameId, loading, loadGames, selectGame }
}
