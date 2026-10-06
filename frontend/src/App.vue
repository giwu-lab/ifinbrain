<template>
  <BasicLayout v-if="!isPublic && !isImmersive" />
  <router-view v-else />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import BasicLayout from '@/layouts/BasicLayout.vue';

const route = useRoute();
// immersive：沉浸式页面（BI 大屏播放页 / 展示墙）。脱离 BasicLayout 全屏渲染，
// 但**不跳过鉴权** —— 路由守卫只认 meta.public，两者必须分开。
// 不能直接用 meta.public：那样未登录用户也能打开大屏。
const isPublic = computed(() => route.meta?.public === true);
const isImmersive = computed(() => route.meta?.immersive === true);
</script>