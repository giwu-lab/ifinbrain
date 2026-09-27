<template>
  <div class="login-page">
    <!-- 背景层：渐变 + 装饰光斑 -->
    <div class="bg-deco">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
      <svg class="grid" viewBox="0 0 100 100" preserveAspectRatio="none">
        <defs>
          <pattern id="grid" width="6" height="6" patternUnits="userSpaceOnUse">
            <path d="M 6 0 L 0 0 0 6" fill="none" stroke="rgba(255,255,255,0.06)" stroke-width="0.2" />
          </pattern>
        </defs>
        <rect width="100" height="100" fill="url(#grid)" />
      </svg>
    </div>

    <div class="login-wrap">
      <!-- 左侧：品牌区 -->
      <div class="brand-pane fade-in-up">
        <div class="logo-mark">
          <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect width="32" height="32" rx="8" fill="white" fill-opacity="0.15" stroke="white" stroke-width="1.5"/>
            <path d="M9 11h14M9 16h14M9 21h9" stroke="white" stroke-width="2" stroke-linecap="round"/>
            <circle cx="22.5" cy="22.5" r="1.5" fill="white"/>
          </svg>
        </div>
        <h1 class="brand-title">{{ t('login.title') }}</h1>
        <p class="brand-desc">{{ t('login.brandDesc') }}</p>
        <ul class="brand-features">
          <li><span class="dot"></span>批量上传 PDF/XML 发票</li>
          <li><span class="dot"></span>本地磁盘 / S3 兼容存储一键切换</li>
          <li><span class="dot"></span>基于 RBAC 的细粒度权限管理</li>
        </ul>
      </div>

      <!-- 右侧：登录卡片 -->
      <el-card class="login-card fade-in-up" shadow="never">
        <h2 class="title">{{ t('login.subtitle') }}</h2>
        <p class="muted subtitle">使用账号密码登录</p>

        <el-form
          :model="form"
          :rules="rules"
          ref="formRef"
          label-position="top"
          @submit.prevent
          class="login-form"
        >
          <el-form-item :label="t('login.username')" prop="username">
            <el-input
              v-model="form.username"
              :placeholder="t('login.usernamePlaceholder')"
              size="large"
              prefix-icon="User"
              clearable
            />
          </el-form-item>
          <el-form-item :label="t('login.password')" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              size="large"
              prefix-icon="Lock"
              :placeholder="t('login.passwordPlaceholder')"
            />
          </el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            size="large"
            class="submit-btn"
            @click="submit"
          >
            {{ t('login.submit') }}
          </el-button>
        </el-form>

        <div class="hint">
          <el-icon><InfoFilled /></el-icon>
          <span>默认账号 <code>admin / Admin@123</code>，首次登录后请修改</span>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { InfoFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { setupDynamicRoutes } from '@/router';

const { t } = useI18n();
const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({ username: 'admin', password: 'Admin@123' });
const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('common.required') }],
  password: [{ required: true, message: t('common.required') }]
}));

async function submit() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    try {
      await auth.login(form.username, form.password);
      try {
        await setupDynamicRoutes(auth.menus);
      } catch (routeErr) {
        console.warn('setupDynamicRoutes failed, continuing anyway:', routeErr);
      }
      ElMessage.success(t('common.welcome'));
      const redirect = (route.query.redirect as string) || '/dashboard';
      try {
        await router.push(redirect);
      } catch (navErr) {
        console.warn('router.push failed:', navErr);
        window.location.href = redirect;
      }
    } catch (authErr) {
      console.error('login error:', authErr);
    } finally {
      loading.value = false;
    }
  });
}
</script>

<style scoped>
.login-page {
  position: relative;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background:
    radial-gradient(ellipse at top left, #1e40af 0%, transparent 50%),
    radial-gradient(ellipse at bottom right, #7c3aed 0%, transparent 50%),
    linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #312e81 100%);
  color: white;
}

/* 背景装饰 */
.bg-deco { position: absolute; inset: 0; pointer-events: none; }
.bg-deco .grid { position: absolute; inset: 0; width: 100%; height: 100%; }
.blob { position: absolute; border-radius: 50%; filter: blur(80px); opacity: 0.45; }
.blob-1 { width: 380px; height: 380px; background: #3b82f6; top: -120px; left: -120px; }
.blob-2 { width: 460px; height: 460px; background: #8b5cf6; bottom: -180px; right: -160px; }
.blob-3 { width: 220px; height: 220px; background: #06b6d4; top: 40%; left: 45%; opacity: 0.25; }

/* 主容器：两栏 */
.login-wrap {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 64px;
  align-items: center;
  padding: 32px;
  max-width: 1080px;
  width: 100%;
}

/* 左：品牌区 */
.brand-pane {
  flex: 1;
  color: white;
  display: flex;
  flex-direction: column;
  gap: 24px;
  max-width: 480px;
}
.logo-mark {
  width: 64px; height: 64px;
  background: rgba(255, 255, 255, 0.10);
  border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.18);
}
.brand-title {
  font-size: 42px;
  font-weight: 700;
  margin: 0;
  letter-spacing: -0.02em;
  background: linear-gradient(135deg, #ffffff 0%, #c7d2fe 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.brand-desc {
  margin: 0;
  font-size: 16px;
  opacity: 0.85;
  line-height: 1.6;
}
.brand-features {
  list-style: none;
  padding: 0;
  margin: 16px 0 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.brand-features li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
}
.brand-features .dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #38bdf8;
  box-shadow: 0 0 0 4px rgba(56, 189, 248, 0.18);
}

/* 右：登录卡片 */
.login-card {
  width: 420px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  box-shadow: 0 24px 48px rgba(15, 23, 42, 0.35);
  padding: 8px 8px;
}
.title {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.01em;
}
.subtitle {
  margin: 0 0 28px;
  font-size: 14px;
  color: #6b7280;
}
.login-form :deep(.el-form-item__label) {
  font-weight: 500;
  color: #374151;
  padding-bottom: 4px;
}
.submit-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  margin-top: 8px;
  background: linear-gradient(135deg, #2563eb 0%, #4f46e5 100%);
  border: none;
  border-radius: 8px;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.30);
  letter-spacing: 0.02em;
}
.submit-btn:hover {
  background: linear-gradient(135deg, #1d4ed8 0%, #4338ca 100%);
}
.hint {
  margin-top: 18px;
  padding: 10px 12px;
  background: #f3f4f6;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #6b7280;
}
.hint code {
  background: white;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: 'SF Mono', Menlo, monospace;
  color: #2563eb;
}

/* 窄屏：品牌区隐藏 */
@media (max-width: 768px) {
  .brand-pane { display: none; }
  .login-wrap { justify-content: center; }
}
</style>