<template>
  <div class="login-page">
    <!-- 背景层：渐变 + 等距几何（CSS 自绘，不引版权插画） -->
    <div class="bg-deco">
      <svg class="grid" viewBox="0 0 100 100" preserveAspectRatio="none">
        <defs>
          <pattern id="grid" width="6" height="6" patternUnits="userSpaceOnUse">
            <path d="M 6 0 L 0 0 0 6" fill="none" stroke="rgba(255,255,255,0.06)" stroke-width="0.2" />
          </pattern>
        </defs>
        <rect width="100" height="100" fill="url(#grid)" />
      </svg>
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>

      <!-- 等距几何：立方体 / 八面体 / 六边形，CSS 3D -->
      <div class="iso-layer" aria-hidden="true">
        <div class="iso-cube cube-1"><span></span><span></span><span></span></div>
        <div class="iso-cube cube-2"><span></span><span></span><span></span></div>
        <div class="iso-cube cube-3"><span></span><span></span><span></span></div>
        <div class="iso-hex hex-1"></div>
        <div class="iso-hex hex-2"></div>
      </div>
    </div>

    <!-- 右上角语言切换 -->
    <div class="lang-switch">
      <el-dropdown trigger="click" @command="onLang">
        <span class="lang-chip">
          <el-icon><Position /></el-icon>
          <span>{{ currentLangLabel }}</span>
          <el-icon><CaretBottom /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="zh-CN">中文</el-dropdown-item>
            <el-dropdown-item command="en-US">English</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
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
          <li><span class="dot"></span>{{ t('login.featureOcr') }}</li>
          <li><span class="dot"></span>{{ t('login.featureStorage') }}</li>
          <li><span class="dot"></span>{{ t('login.featureRbac') }}</li>
        </ul>
      </div>

      <!-- 右侧：登录卡片 -->
      <el-card class="login-card fade-in-up" shadow="never">
        <h2 class="title">{{ t('login.subtitle') }}</h2>

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

        <!-- 登录失败提示：就地显示在卡片里，不用 toast。
             为什么必须本页兜：登录接口对「密码错」和「用户名不存在」都返回 HTTP 401，
             而 api/http.ts 的 error 拦截器对「无 refreshToken + 已在 /login」的 401
             是静默 reject 的（走不到那条 ElMessage），所以不自己处理就是毫无反馈。 -->
        <el-alert
          v-if="loginErrorKey"
          type="error"
          :closable="false"
          class="error-alert"
          show-icon
        >
          <template #title>
            <span class="error-text">{{ t(loginErrorKey) }}</span>
          </template>
        </el-alert>

        <el-alert
          type="info"
          :closable="false"
          class="hint-alert"
          show-icon
        >
          <template #title>
            <span class="hint">{{ t('login.defaultHint', { code: 'admin / Admin@123' }) }}</span>
          </template>
        </el-alert>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { Position, CaretBottom } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { setLocale } from '@/i18n';
import { setupDynamicRoutes } from '@/router';

const { t, locale } = useI18n();
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

/** 登录失败提示。存 i18n key 而不是文案本身 —— 用户在登录页切语言时提示要跟着变。 */
type LoginErrorKey = 'login.invalidCredentials' | 'login.loginFailed';
const loginErrorKey = ref<LoginErrorKey | ''>('');

/* 用户一改动输入就把上一次的失败提示收掉，避免「已经改对了，红字还挂着」 */
watch(() => [form.username, form.password], () => { loginErrorKey.value = ''; });

const currentLangLabel = computed(() => (locale.value === 'en-US' ? 'English' : '中文'));

function onLang(cmd: string) {
  if (cmd !== 'zh-CN' && cmd !== 'en-US') return;
  setLocale(cmd);
}

async function submit() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    loginErrorKey.value = '';   // 每次重试先清掉上一次的提示
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
      // 不透传后端 message：那是英文原文（"Invalid username or password"），
      // 中文界面上很突兀，而且「密码错」和「账号被停用」在用户眼里是同一件事。
      const status = (authErr as { response?: { status?: number } })?.response?.status;
      loginErrorKey.value =
        status === 401 || status === 403 ? 'login.invalidCredentials' : 'login.loginFailed';
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

/* 等距几何层：立方体 + 六边形，纯 CSS */
.iso-layer { position: absolute; inset: 0; }

.iso-cube {
  position: absolute;
  width: 100px;
  height: 100px;
  transform-style: preserve-3d;
  transform: rotateX(-25deg) rotateY(45deg);
  opacity: 0.18;
}
.iso-cube span {
  position: absolute;
  inset: 0;
  border: 1.5px solid rgba(255, 255, 255, 0.6);
  background: rgba(99, 102, 241, 0.10);
}
.iso-cube span:nth-child(1) { transform: translateZ(50px); }
.iso-cube span:nth-child(2) { transform: rotateY(180deg) translateZ(50px); background: rgba(59, 130, 246, 0.10); }
.iso-cube span:nth-child(3) { transform: rotateY(90deg) translateZ(50px); background: rgba(139, 92, 246, 0.10); }

.cube-1 { top: 18%; left: 18%; transform: rotateX(-25deg) rotateY(45deg) scale(1.0); }
.cube-2 { top: 62%; left: 8%;  transform: rotateX(-20deg) rotateY(35deg) scale(0.7); opacity: 0.14; }
.cube-3 { top: 70%; right: 14%; transform: rotateX(-30deg) rotateY(55deg) scale(1.2); opacity: 0.12; }

.iso-hex {
  position: absolute;
  width: 120px;
  height: 104px;
  opacity: 0.16;
  background:
    linear-gradient(135deg, rgba(56, 189, 248, 0.20), rgba(99, 102, 241, 0.20));
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
}
.hex-1 { top: 24%; right: 22%; transform: rotate(8deg); }
.hex-2 { bottom: 18%; right: 8%; transform: rotate(-12deg) scale(0.6); opacity: 0.10; }

/* 右上角语言切换 */
.lang-switch {
  position: absolute;
  top: 24px;
  right: 32px;
  z-index: 2;
}
.lang-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 999px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
  backdrop-filter: blur(8px);
}
.lang-chip:hover {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.30);
  color: white;
}
.lang-chip .el-icon { font-size: 13px; }
.lang-chip .el-icon:last-child { font-size: 11px; opacity: 0.7; }

/* 主容器：两栏 */
.login-wrap {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 64px;
  align-items: center;
  padding: 16px;
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

/* 右：登录卡片（紧凑化） */
.login-card {
  width: 400px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  box-shadow: var(--shadow-lg);
  padding: 8px 8px;
}
.title {
  margin: 0 0 12px;
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.01em;
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
.hint-alert {
  margin-top: 18px;
  border-radius: 8px;
  background: #f3f4f6;
  border-color: #e5e7eb;
}
/* 失败提示紧跟表单，间距比下面的默认账号提示小一点，视觉上属于「表单的一部分」 */
.error-alert {
  margin-top: 14px;
  border-radius: 8px;
}
.error-alert :deep(.el-alert__title) {
  font-size: 13px;
  line-height: 1.5;
}
.hint-alert :deep(.el-alert__title) {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.hint-alert :deep(.el-alert__icon) {
  color: var(--color-primary);
}
.hint code {
  background: var(--color-bg-elev);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  color: var(--color-primary);
}

/* 窄屏：品牌区隐藏 */
@media (max-width: 768px) {
  .brand-pane { display: none; }
  .login-wrap { justify-content: center; }
  .lang-switch { right: 16px; top: 16px; }
}
</style>