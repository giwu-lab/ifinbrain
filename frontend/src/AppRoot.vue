<template>
  <el-config-provider :locale="elementLocale">
    <App />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import en from 'element-plus/es/locale/lang/en';
import { useThemeStore } from '@/stores/theme';
import App from './App.vue';

const { locale } = useI18n();
const theme = useThemeStore();

const elementLocale = computed(() => (locale.value === 'en-US' ? en : zhCn));

/**
 * Element Plus 暗色模式开关：
 *   theme.mode='dark'  → 全暗
 *   theme.mode='auto'  → matchMedia('(prefers-color-scheme: dark)') 跟随系统
 *   theme.mode='light' → 全亮
 *
 * HTML 根元素加 `dark` / `light` class（Element Plus 通过 .dark 切换暗色 css-vars）
 */
import { onMounted, onBeforeUnmount, watchEffect } from 'vue';

let mq: MediaQueryList | null = null;
let handler: ((e: MediaQueryListEvent) => void) | null = null;

function syncDarkClass() {
  const dark = theme.mode === 'dark';
  document.documentElement.classList.toggle('dark', dark);
}

onMounted(() => {
  syncDarkClass();
  if (theme.settings.mode === 'auto') {
    mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    handler = () => syncDarkClass();
    mq?.addEventListener?.('change', handler);
  }
});

watchEffect(() => {
  syncDarkClass();
  // mode 切到 auto：注册监听；切到非 auto：移除
  if (theme.settings.mode === 'auto') {
    if (!mq) {
      mq = window.matchMedia?.('(prefers-color-scheme: dark)');
      handler = () => syncDarkClass();
      mq?.addEventListener?.('change', handler);
    }
  } else if (mq && handler) {
    mq.removeEventListener('change', handler);
    mq = null;
    handler = null;
  }
});

onBeforeUnmount(() => {
  if (mq && handler) mq.removeEventListener('change', handler);
});
</script>