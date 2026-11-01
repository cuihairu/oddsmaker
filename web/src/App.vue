<script setup>
import { RouterView, useRoute } from 'vue-router'
import AppLayout from './components/AppLayout.vue'

const route = useRoute()
</script>

<template>
  <AppLayout v-if="route.meta.requiresAuth !== false">
    <RouterView />
  </AppLayout>
  <RouterView v-else />
</template>
