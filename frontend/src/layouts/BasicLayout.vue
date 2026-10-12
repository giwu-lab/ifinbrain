<template>
  <component :is="layoutComp">
    <template #header-actions>
      <!-- 顶栏工具栏（全局通用） -->
      <el-tooltip v-if="s.showFullscreenBtn" :content="isFullscreen ? t('header.exitFullscreen') : t('header.fullscreen')" placement="bottom">
        <el-button text class="header-icon-btn" @click="toggleFullscreen">
          <el-icon><component :is="isFullscreen ? Aim : FullScreen" /></el-icon>
        </el-button>
      </el-tooltip>

      <el-tooltip :content="t('header.notifications')" placement="bottom">
        <el-badge :is-dot="hasNotif" :hidden="!hasNotif" class="header-icon-btn-wrap">
          <el-button text class="header-icon-btn" @click="onNotifClick">
            <el-icon><Bell /></el-icon>
          </el-button>
        </el-badge>
      </el-tooltip>

      <el-tooltip :content="t('stylePanel.title')" placement="bottom">
        <el-button text class="header-icon-btn" @click="styleOpen = true">
          <IconPalette :size="16" />
        </el-button>
      </el-tooltip>

      <el-popover placement="bottom-end" :width="280" trigger="click">
        <template #reference>
          <el-button text class="header-icon-btn" :title="t('header.help')">
            <el-icon><QuestionFilled /></el-icon>
          </el-button>
        </template>
        <div class="help-popover">
          <div class="help-title">{{ t('header.shortcuts') }}</div>
          <ul class="help-list">
            <li><kbd>Esc</kbd><span>{{ t('header.shortcutEsc') }}</span></li>
            <li><kbd>F11</kbd><span>{{ t('header.shortcutFullscreen') }}</span></li>
            <li><kbd>Ctrl</kbd>+<kbd>K</kbd><span>{{ t('header.shortcutSearch') }}</span></li>
          </ul>
        </div>
      </el-popover>

      <el-dropdown trigger="click" @command="onLang">
        <span class="pill">
          <IconGlobe :size="14" />
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
    </template>

    <template #main>
      <!-- 顶部多标签页 -->
      <TabBar
        v-if="s.showTabs && tabStore.hasTabs"
        @select="onTabSelect"
        @close="onTabClose"
        @close-others="onTabCloseOthers"
        @close-all="onTabCloseAll"
        @refresh="onTabRefresh"
      />

      <!-- 路由切换动画 -->
      <router-view v-slot="{ Component }">
        <transition v-if="s.enableAnimation" :name="animName" mode="out-in">
          <component
            :is="Component"
            :key="`${$route.path}#${tabStore.refreshKeys[$route.path] || 0}`"
          />
        </transition>
        <keep-alive v-else>
          <component
            :is="Component"
            :key="`${$route.path}#${tabStore.refreshKeys[$route.path] || 0}`"
          />
        </keep-alive>
      </router-view>
    </template>
  </component>

  <!-- 右侧"界面风格"设置抽屉 -->
  <StylePanel v-model="styleOpen" />

  <!-- 通知中心抽屉 -->
  <NotificationsDrawer v-model="notifOpen" />
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import {
  Expand, Fold, CaretBottom, FullScreen, Aim, Bell,
  QuestionFilled
} from '@element-plus/icons-vue';
import IconPalette from '@/components/icons/IconPalette.vue';
import IconGlobe from '@/components/icons/IconGlobe.vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import { useTabStore } from '@/stores/tab';
import { useThemeStore, type LayoutType } from '@/stores/theme';
import { setLocale } from '@/i18n';
import TabBar from '@/components/layout/TabBar.vue';
import StylePanel from '@/components/layout/StylePanel.vue';
import NotificationsDrawer from '@/components/layout/NotificationsDrawer.vue';

import SidebarLayout from '@/layouts/SidebarLayout.vue';
import TopNavLayout from '@/layouts/TopNavLayout.vue';
import HeaderSidebarLayout from '@/layouts/HeaderSidebarLayout.vue';
import FloatingSidebarLayout from '@/layouts/FloatingSidebarLayout.vue';

const { t, locale } = useI18n();
const auth = useAuthStore();
const app = useAppStore();
const tabStore = useTabStore();
const theme = useThemeStore();
const route = useRoute();
const router = useRouter();

const s = computed(() => theme.settings);
const styleOpen = ref(false);
const notifOpen = ref(false);
const currentLangLabel = computed(() => (locale.value === 'en-US' ? 'English' : '中文'));

/** 按 layout 切换 component is */
const layoutComp = computed(() => {
  switch (s.value.layout) {
    case 'topnav':           return TopNavLayout;
    case 'header-sidebar':   return HeaderSidebarLayout;
    case 'floating-sidebar': return FloatingSidebarLayout;
    case 'sidebar':
    default:                 return SidebarLayout;
  }
});

/** 路由 transition 名字 = sp-{animType}；style panel 自身不需要走这个 */
const animName = computed(() => `sp-${s.value.animationType}`);

/* ===== 顶栏：全屏 / 通知 / 帮助 ===== */
const isFullscreen = ref(!!document.fullscreenElement);
/** 通知红点：登录后查一次 flow + todo 待办数，任一 > 0 即显示 */
const hasNotif = ref(false);

function onFsChange() { isFullscreen.value = !!document.fullscreenElement; }

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen?.();
    } else {
      // requestFullscreen() 是 Element 接口方法，不是 Document 接口方法。
      // 原代码通过 `as Document & { requestFullscreen?: ... }` 假装 document 有该方法，
      // 运行时实际为 undefined，?.() 静默返回 undefined —— 表现为「点击无效果」。
      // 正确做法：调 documentElement（真正的 Element）。
      await document.documentElement.requestFullscreen?.();
    }
  } catch (e: any) {
    const raw = e?.message || '';
    const msg = /permission|denied|iframe|element[- ]request/i.test(raw)
      ? '当前页面无法进入全屏（可能为嵌入页面 / 浏览器权限受限）'
      : (raw || '当前浏览器不支持全屏');
    ElMessage.warning(msg);
  }
}

function onNotifClick() {
  notifOpen.value = true;
  /* 抽屉关闭时刷新一次未读数（用户可能在抽屉里处理了任务） */
}

/** 拉一次待办计数，决定 badge 红点 */
async function loadNotifBadge() {
  if (!auth.isLogged) { hasNotif.value = false; return; }
  let any = false;
  try {
    // 注意：曾经写的是 todoApi.count() —— 该方法前后端都不存在，
    // 运行时 TypeError 被下面的空 catch 吞掉，导致「待办」这一路的红点永远不亮。
    // 现走专用的 pending/count 端点（形态与 flowTaskApi.pendingCount 一致）。
    const { todoApi } = await import('@/api/todo');
    const n = await todoApi.pendingCount();
    if (n > 0) any = true;
  } catch { /* 忽略权限/网络错误 */ }
  if (!any) {
    try {
      const { flowTaskApi } = await import('@/api/flowTask');
      const n = await flowTaskApi.pendingCount();
      if (n > 0) any = true;
    } catch { /* 同上 */ }
  }
  hasNotif.value = any;
}

/* ===== 路由 transition：只在 data-anim != "none" 时启用 =====
 * animate CSS 写在 styles/animations.css，name = `sp-{animType}` */
watch(() => s.value.layout, (v) => {
  document.documentElement.setAttribute('data-layout', v);
});

onMounted(async () => {
  document.addEventListener('fullscreenchange', onFsChange);
  /* 登录后从 DB 拉一次偏好（刷新页面时 auth.user 已在路由守卫里 fetchMe 拿到） */
  if (auth.isLogged && !theme.loaded) {
    await theme.hydrate(auth.user?.username);
  }
  /* 拉一次未读计数，决定 bell 红点 */
  await loadNotifBadge();
});

/** 抽屉关闭时刷新未读数（用户可能处理了任务） */
watch(notifOpen, (open) => {
  if (!open) loadNotifBadge();
});

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFsChange);
});

/* ===== 语言切换 / 登出 ===== */
function onLang(cmd: string) {
  if (cmd !== 'zh-CN' && cmd !== 'en-US') return;
  setLocale(cmd);
  ElMessage.success(cmd === 'zh-CN' ? t('common.langSwitchedZh') : t('common.langSwitchedEn'));
}

async function handleCmd(cmd: string) {
  if (cmd === 'logout') {
    await auth.logout();
    // auth.clear() 内部已 theme.purge()：本地主题整体丢弃，落回默认配色，
    // 避免下一位登录用户继承这一位的深色侧栏/顶栏。
    ElMessage.success(t('common.logout'));
    router.push('/login');
  }
}

/* ===== Tab handlers ===== */
function onTabSelect(id: string) {
  const tab = tabStore.tabs.find((x) => x.id === id);
  if (!tab) return;
  if (route.path !== tab.id || route.fullPath !== tab.fullPath) {
    router.push(tab.fullPath);
  }
  tabStore.setActive(id);
}
function onTabClose(id: string) {
  const closing = tabStore.tabs.find((x) => x.id === id);
  const wasActive = tabStore.activeId === id;
  tabStore.closeTab(id);
  if (wasActive) {
    const nextId = tabStore.activeId;
    const next = tabStore.tabs.find((x) => x.id === nextId);
    if (next && route.fullPath !== next.fullPath) router.push(next.fullPath);
    else if (!next) router.push('/dashboard');
  }
}
function onTabCloseOthers(id: string) {
  const closing = tabStore.tabs.find((x) => x.id === id);
  tabStore.closeOthers(id);
  if (closing && route.fullPath !== closing.fullPath) router.push(closing.fullPath);
}
function onTabCloseAll() {
  tabStore.closeAll();
  router.push('/dashboard');
}
function onTabRefresh(id?: string) {
  tabStore.refreshTab(id);
}
</script>

<style scoped>
/* 全屏按钮 / 帮助按钮 / 语言 chip / 用户 chip：共享样式（不依赖具体 layout） */
.header-icon-btn,
.header-icon-btn-wrap :deep(.el-button) {
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 8px;
  color: var(--color-text-secondary);
  transition: background 0.15s ease, color 0.15s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.header-icon-btn:hover,
.header-icon-btn-wrap :deep(.el-button):hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.header-icon-btn .el-icon,
.header-icon-btn-wrap .el-icon { font-size: 16px; }
.header-icon-btn-wrap { display: inline-flex; align-items: center; }

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

.help-popover { font-size: 13px; color: var(--color-text); }
.help-title { font-weight: 600; margin-bottom: 8px; color: var(--color-text); }
.help-list { list-style: none; padding: 0; margin: 0; }
.help-list li { display: flex; align-items: center; gap: 6px; padding: 4px 0; color: var(--color-text-secondary); }
.help-list kbd {
  display: inline-block;
  min-width: 24px;
  padding: 1px 6px;
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-bottom-width: 2px;
  border-radius: 4px;
  color: var(--color-text);
  text-align: center;
}

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
  background: linear-gradient(135deg, var(--color-primary) 0%, #3b82f6 100%);
  color: white;
  display: flex; align-items: center; justify-content: center;
  font-weight: 600;
  font-size: 13px;
  box-shadow: 0 2px 8px var(--color-primary-soft);
}
.user-name { font-size: 14px; font-weight: 500; color: var(--color-text); }
.user-chip .el-icon { font-size: 12px; color: var(--color-text-muted); }

/* 路由过渡：none 时直接走 keep-alive；其他 6 种走 .sp-{anim} 类 */
</style>