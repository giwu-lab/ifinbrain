<template>
  <div class="dashboard-page fade-in-up">
    <!-- 欢迎横幅 -->
    <div class="hero">
      <div class="hero-content">
        <div class="hero-text">
          <h1>{{ greeting }}, {{ auth.user?.displayName || auth.user?.username }} 👋</h1>
          <p>{{ t('dashboard.welcomeDesc') }}</p>
        </div>
        <div class="hero-avatar">
          <div class="big-avatar">{{ (auth.user?.displayName || auth.user?.username || '?').charAt(0).toUpperCase() }}</div>
        </div>
      </div>
    </div>

    <!-- 4 个统计卡片 -->
    <el-row :gutter="20" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-primary fade-in-up" style="animation-delay: 0.05s">
          <div class="stat-icon"><el-icon><UserFilled /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.rolesCount') }}</div>
            <div class="stat-value">{{ auth.roles.length }}</div>
            <div class="stat-foot">
              <el-tag v-for="r in auth.roles.slice(0, 2)" :key="r" size="small" effect="plain" round>{{ r }}</el-tag>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-success fade-in-up" style="animation-delay: 0.10s">
          <div class="stat-icon"><el-icon><Key /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.permissionsCount') }}</div>
            <div class="stat-value">{{ auth.permissions.length }}</div>
            <div class="stat-foot muted">{{ t('dashboard.permissionsCount') }} · {{ auth.user?.username }}</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-warning fade-in-up" style="animation-delay: 0.15s">
          <div class="stat-icon"><el-icon><Menu /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.menusCount') }}</div>
            <div class="stat-value">{{ auth.menus.length }}</div>
            <div class="stat-foot muted">
              <span v-for="m in auth.menus.slice(0, 2)" :key="m.id" class="foot-item">{{ menuName(m) }}</span>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-info fade-in-up" style="animation-delay: 0.20s">
          <div class="stat-icon"><el-icon><Folder /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.storageType') }}</div>
            <div class="stat-value">{{ storageLabel }}</div>
            <div class="stat-foot muted">{{ storageHint }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <el-card class="shortcut-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('menu.invoiceUpload') }}</span>
          <el-button type="primary" link @click="goUpload">{{ t('common.actions') }} →</el-button>
        </div>
      </template>
      <div class="shortcuts">
        <div class="shortcut" @click="goUpload">
          <div class="sc-icon sc-blue"><el-icon><UploadFilled /></el-icon></div>
          <div class="sc-name">{{ t('menu.invoiceUpload') }}</div>
        </div>
        <div class="shortcut" @click="goRecords">
          <div class="sc-icon sc-green"><el-icon><Document /></el-icon></div>
          <div class="sc-name">{{ t('menu.invoiceList') }}</div>
        </div>
        <div class="shortcut" @click="goUsers">
          <div class="sc-icon sc-purple"><el-icon><User /></el-icon></div>
          <div class="sc-name">{{ t('menu.systemUser') }}</div>
        </div>
        <div class="shortcut" @click="goStorage">
          <div class="sc-icon sc-amber"><el-icon><Setting /></el-icon></div>
          <div class="sc-name">{{ t('menu.systemStorage') }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import {
  UserFilled, Key, Menu, Folder,
  UploadFilled, Document, User, Setting
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { settingApi } from '@/api/setting';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const storageLabel = ref('—');
const storageHint = ref('');

const greeting = computed(() => {
  const h = new Date().getHours();
  if (h < 6) return t('dashboard.greetingNight');
  if (h < 11) return t('dashboard.greetingMorning');
  if (h < 14) return t('dashboard.greetingNoon');
  if (h < 18) return t('dashboard.greetingAfternoon');
  return t('dashboard.greetingEvening');
});

function menuName(m: { name: string; component?: string }) {
  const comp = m.component?.replace(/\//g, '.');
  if (comp) {
    const translated = t(`menu.${comp}`, m.name);
    if (translated !== `menu.${comp}`) return translated;
  }
  return m.name;
}

function goUpload() { router.push('/invoice/upload'); }
function goRecords() { router.push('/invoice/list'); }
function goUsers() { router.push('/system/user'); }
function goStorage() { router.push('/system/storage'); }

onMounted(async () => {
  try {
    const c = await settingApi.storage();
    storageLabel.value = c.type.toUpperCase();
    storageHint.value = c.type === 'local'
      ? (c.local?.root || '')
      : (c.s3?.bucket ? `${c.s3.endpoint}/${c.s3.bucket}` : c.s3?.endpoint || '');
  } catch {}
});
</script>

<style scoped>
.dashboard-page { padding: 24px; max-width: 1280px; margin: 0 auto; }

/* ===== 欢迎横幅 ===== */
.hero {
  position: relative;
  background: linear-gradient(135deg, #2563eb 0%, #4f46e5 50%, #7c3aed 100%);
  border-radius: 16px;
  padding: 28px 32px;
  margin-bottom: 24px;
  color: white;
  overflow: hidden;
  box-shadow: 0 12px 24px rgba(79, 70, 229, 0.20);
}
.hero::before {
  content: '';
  position: absolute;
  right: -80px; top: -80px;
  width: 260px; height: 260px;
  background: rgba(255, 255, 255, 0.10);
  border-radius: 50%;
  filter: blur(40px);
}
.hero::after {
  content: '';
  position: absolute;
  right: 60px; bottom: -100px;
  width: 220px; height: 220px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 50%;
  filter: blur(40px);
}
.hero-content {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  z-index: 1;
}
.hero-text h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.hero-text p {
  margin: 8px 0 0;
  font-size: 14px;
  opacity: 0.85;
  max-width: 540px;
}
.big-avatar {
  width: 72px; height: 72px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.20);
  backdrop-filter: blur(10px);
  border: 2px solid rgba(255, 255, 255, 0.35);
  display: flex; align-items: center; justify-content: center;
  font-size: 32px;
  font-weight: 700;
  color: white;
}

/* ===== Stat 卡片 ===== */
.stat-row { margin-bottom: 24px; }
.stat-card {
  background: white;
  border-radius: 14px;
  padding: 20px;
  display: flex;
  gap: 16px;
  align-items: flex-start;
  border: 1px solid var(--color-border-soft);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  cursor: default;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.06);
}
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 22px;
  color: white;
  flex-shrink: 0;
}
.stat-primary .stat-icon { background: linear-gradient(135deg, #3b82f6, #2563eb); }
.stat-success .stat-icon { background: linear-gradient(135deg, #34d399, #10b981); }
.stat-warning .stat-icon { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.stat-info .stat-icon    { background: linear-gradient(135deg, #818cf8, #6366f1); }
.stat-body { flex: 1; min-width: 0; }
.stat-label { font-size: 13px; color: var(--color-text-secondary); }
.stat-value { font-size: 28px; font-weight: 700; margin-top: 4px; color: var(--color-text); letter-spacing: -0.01em; }
.stat-foot {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-text-muted);
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.foot-item {
  background: var(--color-bg);
  padding: 2px 8px;
  border-radius: 4px;
}

/* ===== 快捷入口 ===== */
.shortcut-card { border-radius: 14px; }
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-title { font-size: 15px; font-weight: 600; }
.shortcuts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  padding: 8px 0;
}
.shortcut {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 20px 12px;
  border-radius: 12px;
  border: 1px solid var(--color-border-soft);
  background: white;
  cursor: pointer;
  transition: all 0.2s ease;
}
.shortcut:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
  transform: translateY(-2px);
}
.sc-icon {
  width: 52px; height: 52px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 24px;
  color: white;
  transition: transform 0.2s ease;
}
.shortcut:hover .sc-icon { transform: scale(1.05); }
.sc-blue { background: linear-gradient(135deg, #60a5fa, #3b82f6); }
.sc-green { background: linear-gradient(135deg, #34d399, #10b981); }
.sc-purple { background: linear-gradient(135deg, #a78bfa, #8b5cf6); }
.sc-amber { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.sc-name { font-size: 14px; font-weight: 500; color: var(--color-text); }
</style>