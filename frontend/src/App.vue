<template>
  <BasicLayout v-if="!isPublic" />
  <router-view v-else />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import BasicLayout from '@/layouts/BasicLayout.vue';

const route = useRoute();
const isPublic = computed(() => route.meta?.public === true);
</script>