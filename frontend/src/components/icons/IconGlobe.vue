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
    <!-- 外圈：地球轮廓 -->
    <circle
      cx="12"
      cy="12"
      r="10"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <!-- 赤道 -->
    <line
      x1="2"
      y1="12"
      x2="22"
      y2="12"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
    />
    <!-- 中央经线（椭圆弧）：体现 3D 球感 -->
    <path
      d="M12 2 C 15.5 6, 15.5 18, 12 22 C 8.5 18, 8.5 6, 12 2 Z"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number | string }>(), { size: 14 });
</script>