<template>
  <el-container class="layout-root topnav-root" direction="vertical">
    <!-- 顶栏：logo + 横向菜单 + 右侧工具 -->
    <el-header class="topnav-header">
      <div class="topnav-left">
        <router-link v-if="s.showLogo" to="/dashboard" class="brand-link" :title="t('layout.home')">
          <div class="logo-mark">
            <svg viewBox="0 0 32 32" fill="none">
              <defs>
                <linearGradient id="brand-grad-topnav" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#6366f1" />
                  <stop offset="1" stop-color="#3b82f6" />
                </linearGradient>
              </defs>
              <rect width="32" height="32" rx="9" fill="url(#brand-grad-topnav)" />
              <path d="M9 11h14M9 16h14M9 21h9" stroke="white" stroke-width="2" stroke-linecap="round"/>
              <circle cx="22.5" cy="22.5" r="1.5" fill="white"/>
            </svg>
          </div>
          <span class="brand-name">{{ t('layout.brand') }}</span>
        </router-link>

        <el-menu
          mode="horizontal"
          :default-active="active"
          :ellipsis="false"
          class="topnav-menu"
          @select="go"
        >
          <template v-for="m in auth.menus" :key="m.id">
            <el-menu-item v-if="m.type === 'MENU' && m.path" :index="m.path">
              <el-icon v-if="m.icon"><component :is="iconOf(m.icon)!" /></el-icon>
              <template #title>{{ menuName(m) }}</template>
            </el-menu-item>
            <el-sub-menu v-else-if="m.type === 'DIR' && m.children?.length" :index="String(m.id)">
              <template #title>
                <el-icon v-if="m.icon"><component :is="iconOf(m.icon)!" /></el-icon>
                <span>{{ menuName(m) }}</span>
              </template>
              <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path || ''">
                <template #title>{{ menuName(c) }}</template>
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
      </div>

      <div class="topnav-right">
        <slot name="header-actions" />
      </div>
    </el-header>

    <el-main class="main">
      <div class="main-inner">
        <slot name="main" />
      </div>
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { resolveElIcon } from '@/utils/icon';
import { useAuthStore } from '@/stores/auth';
import { useThemeStore } from '@/stores/theme';

const { t } = useI18n();
const auth = useAuthStore();
const theme = useThemeStore();
const route = useRoute();
const router = useRouter();

const s = computed(() => theme.settings);
const active = computed(() => route.path);

function iconOf(name?: string) {
  return resolveElIcon(name);
}

function menuName(m: { name: string; component?: string; path?: string }) {
  const comp = m.component?.replace(/\//g, '.');
  const candidates = [
    comp ? `menu.${comp}` : null,
    m.path && m.path !== '/' ? `menu.${m.path.replace(/^\//, '')}` : null,
    m.path && m.path !== '/' ? `menu.${m.path.replace(/^\//, '').replace(/\//g, '.')}._` : null,
  ].filter(Boolean) as string[];
  for (const key of candidates) {
    const translated = t(key);
    if (translated !== key) return translated;
  }
  return m.name;
}

function go(index: string) {
  if (index && index !== active.value) router.push(index);
}
</script>

<style scoped>
.topnav-root { height: 100vh; background: var(--color-bg); }
.topnav-header {
  background: var(--header-bg, white);
  border-bottom: 1px solid var(--color-border-soft);
  height: 60px !important;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.topnav-left {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  min-width: 0;
}
/* logo + 品牌名整体可点击回仪表盘 */
.brand-link {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: inherit;
  text-decoration: none;
  border-radius: 8px;
  padding: 2px 6px;
  margin-left: -6px;
  transition: background 0.18s ease;
}
.brand-link:hover { background: var(--color-primary-soft); }
.brand-link:hover .brand-name { color: var(--color-primary); }
.logo-mark {
  width: 32px; height: 32px;
  border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 3px 8px rgba(99, 102, 241, 0.25);
}
.brand-name { font-size: 16px; font-weight: 700; color: var(--color-text); letter-spacing: -0.01em; }

.topnav-menu {
  background: transparent !important;
  border-bottom: none !important;
  flex: 1;
  min-width: 0;
}
.topnav-menu :deep(.el-menu-item) {
  height: 60px;
  line-height: 60px;
  padding: 0 14px;
  font-size: 14px;
  color: var(--color-text) !important;
  border-bottom: 2px solid transparent !important;
}
.topnav-menu :deep(.el-menu-item:hover) {
  color: var(--color-primary) !important;
  border-bottom-color: var(--color-primary-soft) !important;
}
.topnav-menu :deep(.el-menu-item.is-active) {
  color: var(--color-primary) !important;
  border-bottom-color: var(--color-primary) !important;
  background: var(--color-primary-softer) !important;
}
.topnav-menu :deep(.el-sub-menu__title) {
  height: 60px;
  line-height: 60px;
  padding: 0 14px;
  color: var(--color-text) !important;
}
.topnav-menu :deep(.el-sub-menu__title:hover),
.topnav-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--color-primary) !important;
}

.topnav-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }

.main { background: var(--color-bg); padding: 0; overflow: auto; }
.main-inner { min-height: 100%; }
</style>