<script setup lang="ts">
/**
 * 实体级授权对话框（角色 × 动作 的勾选矩阵）。
 *
 * 档案库、大屏、报表三处共用同一套语义：每个实体有若干个权限码
 * {@code {prefix}:{code}:{verb}}，每个码绑定到一组角色。把这段抽出来是因为
 * 复制三份必然出现「档案库改了另外两个没改」的授权不一致 —— 那是最难发现、
 * 后果最严重的一类 bug。
 *
 * <p><b>admin 行强制勾选且不可改</b>：对应后端 {@code EntityAuthSupport}
 * 的「admin 永远保留」。前端禁用只是为了让管理员看见「这一格我改不了」，
 * 真正的兜底在后端 —— 否则直接调 API 就能把 admin 自己关在门外。
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { roleApi, type RoleItem } from '@/api/system';
import { useAuthStore } from '@/stores/auth';
// 类型放在 permMatrixTypes.ts —— <script setup> 不能写具名 export
import type { PermGrants, PermVerbSpec } from './permMatrixTypes';

const props = defineProps<{
  modelValue: boolean;
  title: string;
  /** 权限码前缀提示，如 report:zrep_test_r: —— 让人一眼看出这些码长什么样 */
  codePrefix: string;
  verbs: PermVerbSpec[];
  entityId: number | null;
  load: (id: number) => Promise<PermGrants>;
  save: (id: number, grants: PermGrants) => Promise<void>;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'saved'): void;
}>();

const { t } = useI18n();
const auth = useAuthStore();

const loading = ref(false);
const saving = ref(false);
const roles = ref<RoleItem[]>([]);
/** roleId → Set<verbKey> */
const grants = ref<Map<number, Set<string>>>(new Map());
const initial = ref<Map<number, Set<string>>>(new Map());

function isAdminRole(r: RoleItem) {
  return r.code === 'admin';
}

/** 任一格与初始值不同 = 有未保存改动 */
const dirty = computed(() => {
  const cur = grants.value;
  const init = initial.value;
  const keys = new Set<number>([...cur.keys(), ...init.keys()]);
  for (const k of keys) {
    const a = [...(cur.get(k) ?? [])].sort().join(',');
    const b = [...(init.get(k) ?? [])].sort().join(',');
    if (a !== b) return true;
  }
  return false;
});

function setGrant(roleId: number, verb: string, on: boolean) {
  const set = grants.value.get(roleId) ?? new Set<string>();
  if (on) set.add(verb); else set.delete(verb);
  grants.value.set(roleId, set);
  // 触发响应式更新：Set 是引用类型，原地 add 不会让 computed 重算
  grants.value = new Map(grants.value);
}

function toggleCell(roleId: number, verb: string, on: boolean | string | number) {
  if (isAdminRole(roles.value.find((r) => r.id === roleId)!)) return;
  setGrant(roleId, verb, Boolean(on));
}

function toggleColumn(verb: string, on: boolean) {
  for (const r of roles.value) {
    if (isAdminRole(r)) continue;
    setGrant(r.id, verb, on);
  }
}

async function ensureRoles() {
  if (roles.value.length) return roles.value;
  roles.value = await roleApi.list();
  return roles.value;
}

async function open(id: number) {
  loading.value = true;
  grants.value = new Map();
  initial.value = new Map();
  try {
    const [rs, current] = await Promise.all([ensureRoles(), props.load(id)]);
    // 把 {view:[1,2], design:[1]} 拍扁成 Map<roleId, Set<verb>>
    const next = new Map<number, Set<string>>();
    for (const v of props.verbs) {
      for (const rid of current[v.key] ?? []) {
        if (!next.has(rid)) next.set(rid, new Set<string>());
        next.get(rid)!.add(v.key);
      }
    }
    const adminRole = rs.find(isAdminRole);
    if (adminRole) {
      next.set(adminRole.id, new Set<string>(props.verbs.map((v) => v.key)));
    }
    grants.value = next;
    initial.value = new Map(Array.from(next.entries()).map(([k, v]) => [k, new Set(v)]));
  } catch {
    ElMessage.error(t('documents.authLoadFailed'));
    emit('update:modelValue', false);
  } finally {
    loading.value = false;
  }
}

function flatten(): PermGrants {
  const out: PermGrants = {};
  for (const v of props.verbs) out[v.key] = [];
  for (const [roleId, verbs] of grants.value) {
    for (const v of props.verbs) {
      if (verbs.has(v.key)) out[v.key].push(roleId);
    }
  }
  return out;
}

async function save() {
  if (props.entityId == null) return;
  saving.value = true;
  try {
    await props.save(props.entityId, flatten());
    ElMessage.success(t('documents.authSaved'));
    emit('update:modelValue', false);
    // 授权变更会影响侧边栏与列表可见性 —— 立即刷新当前用户的菜单
    await auth.fetchMe();
    // 关键：当前用户自己的 perm 可能刚被改 → 重发 JWT，浏览器侧立刻生效，
    // 否则要等旧 token 过期才看得到变化
    await auth.refreshTokens();
    emit('saved');
  } catch {
    /* http 拦截器已toast */
  } finally {
    saving.value = false;
  }
}

watch(
  () => props.modelValue,
  (openNow) => {
    if (openNow && props.entityId != null) open(props.entityId);
  },
);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="640px"
    :close-on-click-modal="false"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="auth-header">
      <div class="auth-row">
        <span class="muted small">{{ t('documents.authCodeLabel') }}：</span>
        <code class="mono">{{ codePrefix }}{{ verbs.map((v) => v.key).join('|') }}</code>
      </div>
      <div class="form-hint muted">{{ t('documents.authRoleHint') }}</div>
    </div>

    <el-table
      :data="roles"
      v-loading="loading"
      border
      size="small"
      class="auth-grid"
      row-key="id"
    >
      <el-table-column :label="t('system.colCode')" min-width="140">
        <template #default="{ row }">
          <div class="auth-role-cell">
            <span class="strong">{{ row.name }}</span>
            <code class="mono small muted">{{ row.code }}</code>
            <el-tag v-if="isAdminRole(row as RoleItem)" size="small" type="warning" effect="plain" round>
              {{ t('system.roleAdminTag') }}
            </el-tag>
          </div>
        </template>
      </el-table-column>

      <el-table-column
        v-for="v in verbs"
        :key="v.key"
        :label="v.label"
        align="center"
        width="120"
      >
        <template #header>
          <div class="auth-col-head">
            <span>{{ v.label }}</span>
            <el-button link size="small" @click="toggleColumn(v.key, true)">
              {{ t('documents.authSelectAll') }}
            </el-button>
          </div>
        </template>
        <template #default="{ row }">
          <el-checkbox
            :model-value="!!grants.get(row.id)?.has(v.key)"
            :disabled="isAdminRole(row as RoleItem)"
            @change="(on: any) => toggleCell(row.id, v.key, on)"
          />
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">
        {{ t('common.save') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.auth-header {
  margin-bottom: 10px;
}
.auth-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.muted {
  color: var(--color-text-muted);
}
.small {
  font-size: 12px;
}
.form-hint {
  font-size: 12px;
  line-height: 1.5;
}
.mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--color-text-secondary);
  word-break: break-all;
}
.auth-grid :deep(.el-table__cell) {
  padding: 6px 0;
}
.auth-role-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
.strong {
  font-weight: 600;
}
.auth-col-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  line-height: 1.2;
}
</style>