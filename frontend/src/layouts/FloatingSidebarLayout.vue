<template>
  <el-container class="layout-root floating-root">
    <el-aside :width="collapsed ? '72px' : '220px'" class="aside">
      <div class="brand" :class="{ collapsed }">
        <router-link v-if="s.showLogo" to="/dashboard" class="brand-link" :title="t('layout.home')">
          <div class="logo-mark">
            <svg viewBox="0 0 32 32" fill="none">
              <defs>
                <linearGradient id="brand-grad-float" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#6366f1" />
                  <stop offset="1" stop-color="#3b82f6" />
                </linearGradient>
              </defs>
              <rect width="32" height="32" rx="9" fill="url(#brand-grad-float)" />
              <path d="M9 11h14M9 16h14M9 21h9" stroke="white" stroke-width="2" stroke-linecap="round"/>
              <circle cx="22.5" cy="22.5" r="1.5" fill="white"/>
            </svg>
          </div>
          <div v-if="!collapsed" class="brand-text">
            <div class="brand-name">{{ t('layout.brand') }}</div>
          </div>
        </router-link>
        <el-button v-if="!collapsed" class="brand-toggle" text :title="t('layout.collapsedHint')" @click="app.toggle()">
          <el-icon :size="16"><Fold /></el-icon>
        </el-button>
      </div>

      <el-menu
        :default-active="active"
        :default-openeds="openedDirs"
        :collapse="collapsed"
        :collapse-transition="false"
        :unique-opened="s.collapseMenu"
        class="side-menu"
        @select="go"
      >
        <template v-for="m in auth.menus" :key="m.id">
          <el-sub-menu v-if="m.type === 'DIR'" :index="String(m.id)">
            <template #title>
              <span class="dir-title">
                <el-icon v-if="m.icon"><component :is="iconOf(m.icon)!" /></el-icon>
                <template v-if="!collapsed"><span>{{ menuName(m) }}</span></template>
              </span>
            </template>
            <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path || ''">
              <el-icon v-if="c.icon"><component :is="iconOf(c.icon)!" /></el-icon>
              <template #title>{{ menuName(c) }}</template>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else-if="m.type === 'MENU' && m.path" :index="m.path">
            <el-icon v-if="m.icon"><component :is="iconOf(m.icon)!" /></el-icon>
            <template #title>{{ menuName(m) }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-button class="toggle-btn" text @click="app.toggle()">
            <el-icon :size="18"><component :is="app.collapsed ? Expand : Fold" /></el-icon>
          </el-button>
          <el-breadcrumb v-if="s.showBreadcrumb" separator="/" class="breadcrumb">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">{{ t('layout.home') }}</el-breadcrumb-item>
            <el-breadcrumb-item v-if="breadcrumbLabel">{{ breadcrumbLabel }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <slot name="header-actions" />
        </div>
      </el-header>

      <el-main class="main">
        <div class="main-inner">
          <slot name="main" />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Expand, Fold } from '@element-plus/icons-vue';
import { resolveElIcon } from '@/utils/icon';
import { resolveMenuName } from '@/utils/menu';
import { useAuthStore } from '@/stores/auth';
import { useTabStore } from '@/stores/tab';
import { useAppStore } from '@/stores/app';
import { useThemeStore } from '@/stores/theme';

const { t } = useI18n();
const auth = useAuthStore();
const tabStore = useTabStore();
const app = useAppStore();
const theme = useThemeStore();
const route = useRoute();
const router = useRouter();

const s = computed(() => theme.settings);
const collapsed = computed(() => app.collapsed);
const active = computed(() => route.path);

const openedDirs = computed(() => {
  const cur = route.path;
  const ids: string[] = [];
  for (const m of auth.menus) {
    if (m.type !== 'DIR' || !m.children) continue;
    if (m.children.some((c) => c.path === cur)) ids.push(String(m.id));
  }
  return ids;
});

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
.floating-root { height: 100vh; background: var(--color-bg); }

.aside {
  background: var(--sidebar-bg-actual, white);
  transition: width 0.25s ease;
  overflow: hidden;
  border-right: none;
  display: flex;
  flex-direction: column;
  margin: 12px 0 12px 12px;
  border-radius: 0 14px 14px 0;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
}

.brand {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid var(--color-border-soft);
  white-space: nowrap;
  overflow: hidden;
  flex-shrink: 0;
  justify-content: space-between;
}
.brand.collapsed { padding: 0; justify-content: center; }
.brand-toggle { width: 32px; height: 32px; border-radius: 8px; color: var(--color-text-muted); }
/* logo + 品牌文字整体可点击回仪表盘（router-link，保留折叠按钮在链接外） */
.brand-link {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  color: inherit;
  text-decoration: none;
  border-radius: 10px;
  transition: background 0.18s ease;
}
.brand-link:hover { background: var(--color-primary-soft); }
.brand-link:hover .logo-mark { box-shadow: 0 4px 14px rgba(99, 102, 241, 0.4); }
.brand-link:hover .brand-name { color: var(--color-primary); }
.logo-mark {
  width: 34px; height: 34px;
  border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 10px rgba(99, 102, 241, 0.25);
}
.brand-text { line-height: 1.2; }
.brand-name { font-size: 15px; font-weight: 700; color: var(--color-text); }

.side-menu {
  flex: 1;
  background: transparent !important;
  border-right: 0 !important;
  padding: 12px 10px;
  overflow-y: auto;
  overflow-x: hidden;
}
.side-menu::-webkit-scrollbar { width: 4px; }
.side-menu::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 2px; }

.side-menu :deep(.el-menu-item),
.side-menu :deep(.el-sub-menu__title) {
  color: var(--color-text-sidebar) !important;
  border-radius: 8px;
  margin: 2px 0;
  height: 40px;
  line-height: 40px;
  font-weight: 500;
  font-size: 14px;
}
.side-menu :deep(.el-menu-item .el-icon),
.side-menu :deep(.el-sub-menu__title .el-icon) { color: var(--color-text-secondary); font-size: 16px; }
.side-menu :deep(.el-menu-item:hover),
.side-menu :deep(.el-sub-menu__title:hover) {
  background: var(--color-primary-soft) !important;
  color: var(--color-primary) !important;
}
.side-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, var(--color-primary) 0%, var(--color-primary-hover, #3b82f6) 100%) !important;
  color: white !important;
  box-shadow: 0 6px 16px var(--color-primary-soft);
}
.side-menu :deep(.el-menu-item.is-active .el-icon) { color: white; }
.side-menu :deep(.el-sub-menu .el-menu-item) {
  background: transparent !important;
  height: 36px;
  line-height: 36px;
  font-size: 13.5px;
  padding-left: 44px !important;
  color: var(--color-text-sidebar) !important;
}
.side-menu :deep(.el-sub-menu .el-menu-item.is-active) {
  background: var(--color-primary-soft) !important;
  color: var(--color-primary) !important;
}
.side-menu :deep(.el-sub-menu .el-menu) { background: transparent !important; padding: 0 !important; }
.dir-title { display: inline-flex; align-items: center; gap: 6px; }

.header {
  background: transparent !important;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  height: 64px;
}
.header-right { display: flex; align-items: center; gap: 12px; }
.toggle-btn { width: 36px; height: 36px; border-radius: 8px; }
.toggle-btn:hover { background: var(--color-bg); }
.breadcrumb { font-size: 14px; }

.main { background: transparent; padding: 0; overflow: auto; }
.main-inner {
  min-height: 100%;
  background: var(--color-bg-elev);
  margin: 0 12px 12px 0;
  border-radius: 14px;
  box-shadow: var(--shadow-sm);
  overflow: auto;
}
</style>