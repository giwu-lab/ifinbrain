<template>
  <el-container class="layout-root">
    <!-- ===== 侧边栏（浅色专业主题） ===== -->
    <el-aside :width="collapsed ? '72px' : '240px'" class="aside">
      <!-- 品牌区 -->
      <div class="brand" :class="{ collapsed }">
        <div class="logo-mark">
          <svg viewBox="0 0 32 32" fill="none">
            <defs>
              <linearGradient id="brand-grad" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
                <stop offset="0" stop-color="#6366f1" />
                <stop offset="1" stop-color="#3b82f6" />
              </linearGradient>
            </defs>
            <rect width="32" height="32" rx="9" fill="url(#brand-grad)" />
            <path d="M9 11h14M9 16h14M9 21h9" stroke="white" stroke-width="2" stroke-linecap="round"/>
            <circle cx="22.5" cy="22.5" r="1.5" fill="white"/>
          </svg>
        </div>
        <div v-if="!collapsed" class="brand-text">
          <div class="brand-name">{{ t('layout.brand') }}</div>
          <div class="brand-sub">{{ t('layout.brandSub') }}</div>
        </div>
      </div>

      <el-menu
        :default-active="active"
        :collapse="collapsed"
        :collapse-transition="false"
        class="side-menu"
        unique-opened
        @select="go"
      >
        <template v-for="m in auth.menus" :key="m.id">
          <el-sub-menu v-if="m.type === 'DIR'" :index="String(m.id)">
            <template #title>
              <!-- DIR 不绑路由 —— 点击只展开/收起（el-sub-menu 默认行为），不跳转 -->
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

      <!-- 底部用户信息 -->
      <div v-if="!collapsed" class="aside-footer">
        <div class="user-mini">
          <div class="avatar">{{ (auth.user?.displayName || auth.user?.username || '?').charAt(0).toUpperCase() }}</div>
          <div class="user-info">
            <div class="user-info-name">{{ auth.user?.displayName || auth.user?.username }}</div>
            <div class="user-info-role">{{ auth.roles?.[0] || 'User' }}</div>
          </div>
        </div>
      </div>
    </el-aside>

    <el-container>
      <!-- ===== 顶栏 ===== -->
      <el-header class="header">
        <div class="header-left">
          <el-button class="toggle-btn" text :title="collapsed ? t('layout.expandHint') : t('layout.collapsedHint')" @click="app.toggle()">
            <el-icon :size="18"><component :is="app.collapsed ? Expand : Fold" /></el-icon>
          </el-button>
          <el-breadcrumb separator="/" class="breadcrumb">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">{{ t('layout.home') }}</el-breadcrumb-item>
            <el-breadcrumb-item v-if="breadcrumbLabel">{{ breadcrumbLabel }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown trigger="click" @command="onLang">
            <span class="pill">
              <el-icon><Position /></el-icon>
              <span class="pill-text">{{ currentLangLabel }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="zh-CN">中文</el-dropdown-item>
                <el-dropdown-item command="en-US">English</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="handleCmd">
            <span class="user-chip">
              <div class="avatar">{{ (auth.user?.displayName || auth.user?.username || '?').charAt(0).toUpperCase() }}</div>
              <span class="user-name">{{ auth.user?.displayName || auth.user?.username }}</span>
              <el-icon><CaretBottom /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">{{ t('common.logout') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- ===== 主内容 ===== -->
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" :key="$route.fullPath" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Avatar, Expand, Fold, Position, CaretBottom } from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import { setLocale } from '@/i18n';

const { t, locale } = useI18n();
const auth = useAuthStore();
const app = useAppStore();
const route = useRoute();
const router = useRouter();

const collapsed = computed(() => app.collapsed);
const active = computed(() => route.path);
const currentLangLabel = computed(() => (locale.value === 'en-US' ? 'English' : '中文'));

function iconOf(name?: string) {
  if (!name) return ElIcons.Document;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Document;
}

/**
 * 解析菜单名：component 命中具体子菜单，DIR 用 `_<path>` 取自名称，path 直接当 key。
 * vue-i18n v11 把 . 当路径分隔符。
 */
function menuName(m: { name: string; component?: string; path?: string }) {
  const comp = m.component?.replace(/\//g, '.')
  const candidates = [
    comp ? `menu.${comp}` : null,         // 'menu.system.user.Index'
    m.path && m.path !== '/' ? `menu.${m.path.replace(/^\//, '')}` : null,           // 'menu.system'
    m.path && m.path !== '/' ? `menu.${m.path.replace(/^\//, '').replace(/\//g, '.')}._` : null, // 'menu.system._'
  ].filter(Boolean) as string[]
  for (const key of candidates) {
    const translated = t(key)
    if (translated !== key) return translated
  }
  return m.name
}

/** 面包屑末段：从 route meta 反查 menu.* 命名空间，找到就翻译，找不到回退 meta.title */
const breadcrumbLabel = computed(() => {
  const m = route.meta as { name?: string; component?: string; path?: string; title?: string } | undefined
  if (!m?.name) return m?.title || ''
  return menuName({
    name: m.name,
    component: m.component,
    path: m.path,
  })
})

function onLang(cmd: string) {
  if (cmd !== 'zh-CN' && cmd !== 'en-US') return;
  setLocale(cmd);
  ElMessage.success(cmd === 'zh-CN' ? t('common.langSwitchedZh') : t('common.langSwitchedEn'));
}

function go(index: string) {
  if (index && index !== active.value) {
    router.push(index);
  }
}

async function handleCmd(cmd: string) {
  if (cmd === 'logout') {
    await auth.logout();
    ElMessage.success(t('common.logout'));
    router.push('/login');
  }
}
</script>

<style scoped>
.layout-root { height: 100vh; background: var(--color-bg); }

/* ===== 浅色侧边栏 ===== */
.aside {
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  transition: width 0.25s ease;
  overflow: hidden;
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  box-shadow: 1px 0 0 var(--color-border-soft);
}
.brand {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid var(--color-border-soft);
  white-space: nowrap;
  overflow: hidden;
  flex-shrink: 0;
}
.brand.collapsed { padding: 0; justify-content: center; }
.logo-mark {
  width: 38px; height: 38px;
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 10px rgba(99, 102, 241, 0.25);
}
.brand-text { line-height: 1.2; }
.brand-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.01em;
}
.brand-sub {
  font-size: 11px;
  color: var(--color-text-muted);
  margin-top: 2px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

/* el-menu 容器 */
.side-menu {
  flex: 1;
  background: transparent !important;
  border-right: 0 !important;
  padding: 16px 12px;
  overflow-y: auto;
  overflow-x: hidden;
}
.side-menu::-webkit-scrollbar { width: 4px; }
.side-menu::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 2px; }

/* 顶层 + 子菜单 项 */
.side-menu :deep(.el-menu-item),
.side-menu :deep(.el-sub-menu__title) {
  color: var(--color-text-sidebar) !important;
  border-radius: 8px;
  margin: 2px 0;
  height: 42px;
  line-height: 42px;
  font-weight: 500;
  font-size: 14px;
  transition: all 0.18s ease;
}
.side-menu :deep(.el-menu-item .el-icon),
.side-menu :deep(.el-sub-menu__title .el-icon) {
  font-size: 16px;
  color: var(--color-text-secondary);
  transition: color 0.18s ease;
}

.side-menu :deep(.el-menu-item:hover),
.side-menu :deep(.el-sub-menu__title:hover) {
  background: var(--color-primary-soft) !important;
  color: var(--color-primary) !important;
}
.side-menu :deep(.el-menu-item:hover .el-icon),
.side-menu :deep(.el-sub-menu__title:hover .el-icon) {
  color: var(--color-primary);
}

/* 激活项：主色渐变 + 阴影 */
.side-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%) !important;
  color: white !important;
  box-shadow: 0 6px 16px rgba(99, 102, 241, 0.35);
}
.side-menu :deep(.el-menu-item.is-active .el-icon) {
  color: white;
}
.side-menu :deep(.el-menu-item.is-active:hover) {
  background: linear-gradient(135deg, #4f46e5 0%, #2563eb 100%) !important;
  color: white !important;
}

/* 子菜单展开的项 */
.side-menu :deep(.el-sub-menu .el-menu-item) {
  background: transparent !important;
  height: 38px;
  line-height: 38px;
  font-size: 13.5px;
  padding-left: 44px !important;
  min-width: 0;
  color: var(--color-text-sidebar) !important;
}
.side-menu :deep(.el-sub-menu .el-menu-item .el-icon) {
  font-size: 14px;
}
.side-menu :deep(.el-sub-menu .el-menu-item.is-active) {
  background: var(--color-primary-soft) !important;
  color: var(--color-primary) !important;
  box-shadow: none;
}
.side-menu :deep(.el-sub-menu .el-menu-item.is-active .el-icon) {
  color: var(--color-primary);
}
.side-menu :deep(.el-sub-menu .el-menu-item.is-active:hover) {
  background: var(--color-primary-soft) !important;
  color: var(--color-primary) !important;
}
.side-menu :deep(.el-sub-menu .el-menu) {
  background: transparent !important;
  padding: 0 !important;
}
/* 子菜单展开箭头颜色 */
.side-menu :deep(.el-sub-menu__icon-arrow) {
  color: var(--color-text-secondary);
}
.dir-title { display: inline-flex; align-items: center; gap: 6px; }

/* 侧栏底部：用户小卡片 */
.aside-footer {
  border-top: 1px solid var(--color-border-soft);
  padding: 12px 16px;
  background: rgba(248, 250, 252, 0.5);
}
.user-mini {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-radius: 8px;
  transition: background 0.15s ease;
  cursor: pointer;
}
.user-mini:hover { background: var(--color-bg); }
.user-mini .avatar {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%);
  color: white;
  display: flex; align-items: center; justify-content: center;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
}
.user-info { line-height: 1.2; min-width: 0; flex: 1; }
.user-info-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-info-role {
  font-size: 11px;
  color: var(--color-text-muted);
  margin-top: 2px;
}

/* ===== 顶栏 ===== */
.header {
  background: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid var(--color-border-soft);
  height: 64px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.header-left { display: flex; align-items: center; gap: 12px; }
.toggle-btn {
  width: 36px; height: 36px;
  border-radius: 8px;
  transition: all 0.15s ease;
}
.toggle-btn:hover { background: var(--color-bg); }
.breadcrumb { font-size: 14px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--color-bg);
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-secondary);
  transition: all 0.15s ease;
  border: 1px solid var(--color-border-soft);
}
.pill:hover { background: var(--color-primary-soft); color: var(--color-primary); border-color: var(--color-primary-soft); }
.pill .el-icon { font-size: 14px; }
.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 4px 12px 4px 4px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.user-chip:hover { background: var(--color-bg); }
.user-chip .avatar {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%);
  color: white;
  display: flex; align-items: center; justify-content: center;
  font-weight: 600;
  font-size: 13px;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.30);
}
.user-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text);
}
.user-chip .el-icon { font-size: 12px; color: var(--color-text-muted); }

/* ===== 主内容 ===== */
.main {
  background: var(--color-bg);
  padding: 0;
  overflow: auto;
}

/* 路由切换动画 */
.fade-enter-active, .fade-leave-active { transition: opacity 0.18s ease, transform 0.18s ease; }
.fade-enter-from { opacity: 0; transform: translateY(6px); }
.fade-leave-to   { opacity: 0; transform: translateY(-6px); }
</style>