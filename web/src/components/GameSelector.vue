<script setup>
import { useGameList } from '@/composables/useGameList'

const emit = defineEmits(['change'])
const { games, currentGameId, loading, selectGame } = useGameList()

function onChange(event) {
  selectGame(event.target.value)
  emit('change', event.target.value)
}
</script>

<template>
  <div class="flex items-center gap-2">
    <label class="text-sm text-gray-500 whitespace-nowrap">游戏</label>
    <select
      :value="currentGameId"
      :disabled="loading || games.length === 0"
      class="input max-w-xs"
      @change="onChange"
    >
      <option v-if="games.length === 0" value="">暂无可用游戏</option>
      <option v-for="game in games" :key="game.id" :value="game.id">
        {{ game.displayName || game.name }}（{{ game.id }}）
      </option>
    </select>
  </div>
</template>
