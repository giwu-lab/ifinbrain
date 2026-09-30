<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    role="img"
  >
    <!-- 调色板肾形轮廓 + 右下拇指孔 -->
    <path
      d="M12 2.25c-5.385 0-9.75 4.365-9.75 9.75s4.365 9.75 9.75 9.75c.75 0 1.357-.607 1.357-1.357 0-.32-.115-.622-.31-.857a1.357 1.357 0 0 1-.31-.857c0-.75.607-1.357 1.357-1.357h2.143C20.186 17.822 22 16.018 22 13.5 22 7.5 17.523 2.25 12 2.25z"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <!-- 4 个色点：左上 / 右上 / 上方 / 右下 -->
    <circle cx="7.5" cy="10.5" r="1" fill="currentColor" />
    <circle cx="9.75" cy="6.75" r="1" fill="currentColor" />
    <circle cx="14.25" cy="6.75" r="1" fill="currentColor" />
    <circle cx="16.5" cy="10.5" r="1" fill="currentColor" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number | string }>(), { size: 16 });
</script>