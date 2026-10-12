<template>
  <el-container class="layout-root header-sidebar-root" direction="vertical">
    <!-- 顶栏：logo + 工具栏 -->
    <el-header class="hs-header">
      <div class="hs-left">
        <router-link v-if="s.showLogo" to="/dashboard" class="brand-link" :title="t('layout.home')">
          <div class="logo-mark">
            <svg viewBox="0 0 32 32" fill="none">
              <defs>
                <linearGradient id="brand-grad-hs" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#6366f1" />
                  <stop offset="1" stop-color="#3b82f6" />
                </linearGradient>
              </defs>
              <rect width="32" height="32" rx="9" fill="url(#brand-grad-hs)" />
              <path d="M9 11h14M9 16h14M9 21h9" stroke="white" stroke-width="2" stroke-linecap="round"/>
              <circle cx="22.5" cy="22.5" r="1.5" fill="white"/>
            </svg>
          </div>
          <span class="brand-name">{{ t('layout.brand') }}</span>
        </router-link>

        <el-breadcrumb v-if="s.showBreadcrumb" separator="/" class="breadcrumb">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">{{ t('layout.home') }}</el-breadcrumb-item>
          <el-breadcrumb-item v-if="breadcrumbLabel">{{ breadcrumbLabel }}</el-breadcrumb-item>
        </el-breadcrumb>
      </div>
      <div class="hs-right">
        <slot name="header-actions" />
      </div>
    </el-header>

    <!-- 二级菜单条：横向，铺满 -->
    <el-menu
      mode="horizontal"
      :default-active="active"
      :ellipsis="false"
      class="hs-menu"
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
import { resolveMenuName } from '@/utils/menu';
import { useAuthStore } from '@/stores/auth';
import { useTabStore } from '@/stores/tab';
import { useThemeStore } from '@/stores/theme';

const { t } = useI18n();
const auth = useAuthStore();
const tabStore = useTabStore();
const theme = useThemeStore();
const route = useRoute();
const router = useRouter();

const s = computed(() => theme.settings);
const active = computed(() => route.path);

function iconOf(name?: string) {
  return resolveElIcon(name);
}

// 菜单显示名解析搬到 @/utils/menu —— 4 个 layout + 两个发布对话框共用一份
function menuName(m: { name: string; component?: string; path?: string }) {
  return resolveMenuName(m, t);
}

const breadcrumbLabel = computed(() => {
  // 页面自设的标题优先（报表名 / 大屏名…）。必须和 TabBar 用同一份，
  // 否则会出现「tab 写订单报表、面包屑写报表展示」这种自相矛盾。
  if (tabStore.activeTitle) return tabStore.activeTitle;
  const m = route.meta as { name?: string; component?: string; path?: string; title?: string } | undefined;
  if (!m) return '';
  // name / title 都可能缺：缺 name 时也要走 menuName()，否则会直接把 meta.title
  // 那个硬编码英文显示出来。title 仅作为 i18n 没命中时的最后兜底。
  return menuName({ name: m.name ?? m.title ?? '', component: m.component, path: m.path });
});

function go(index: string) {
  if (index && index !== active.value) router.push(index);
}
</script>

<style scoped>
.header-sidebar-root { height: 100vh; background: var(--color-bg); }
.hs-header {
  background: var(--header-bg, white);
  border-bottom: 1px solid var(--color-border-soft);
  height: 56px !important;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.hs-left { display: flex; align-items: center; gap: 16px; flex: 1; min-width: 0; }
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
.breadcrumb { font-size: 14px; }
.hs-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }

.hs-menu {
  background: var(--menu-bg, var(--color-bg-elev)) !important;
  border-bottom: 1px solid var(--color-border-soft) !important;
}
.hs-menu :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
  font-size: 14px;
  color: var(--color-text) !important;
  border-bottom: 2px solid transparent !important;
}
.hs-menu :deep(.el-menu-item:hover) { color: var(--color-primary) !important; }
.hs-menu :deep(.el-menu-item.is-active) {
  color: var(--color-primary) !important;
  border-bottom-color: var(--color-primary) !important;
  background: var(--color-primary-softer) !important;
}

.main { background: var(--color-bg); padding: 0; overflow: auto; }
.main-inner { min-height: 100%; }
</style>