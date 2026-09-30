<template>
  <div class="approval-node-config">
    <el-form label-position="top" size="small">
      <el-form-item :label="t('flowDesigner.node.label')">
        <el-input v-model="local.label" :placeholder="t('flowDesigner.node.labelPh')" @input="emitPatch" />
      </el-form-item>

      <el-form-item :label="t('flowDesigner.node.approvalType')">
        <el-radio-group v-model="local.approvalType!" @change="emitPatch">
          <el-radio-button label="OR">{{ t('flowDesigner.approval.or') }}</el-radio-button>
          <el-radio-button label="AND">{{ t('flowDesigner.approval.and') }}</el-radio-button>
          <el-radio-button label="PERCENT">{{ t('flowDesigner.approval.percentShort') }}</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="local.approvalType === 'PERCENT'" :label="t('flowDesigner.node.completionPercent')">
        <el-input-number v-model="local.completionPercent" :min="1" :max="100" controls-position="right" style="width: 100%;" @change="emitPatch" />
      </el-form-item>

      <el-form-item :label="t('flowDesigner.node.assignees')">
        <el-tabs v-model="assigneeTab" class="assignee-tabs">
          <el-tab-pane :label="t('flowDesigner.node.tabUsers')" name="user">
            <el-select
              v-model="local.userIds"
              multiple
              filterable
              :placeholder="t('flowDesigner.node.userPh')"
              :loading="loadingUsers"
              style="width: 100%;"
              @change="emitPatch"
            >
              <el-option v-for="u in userOptions" :key="u.id" :label="u.label" :value="String(u.id)" />
            </el-select>
          </el-tab-pane>
          <el-tab-pane :label="t('flowDesigner.node.tabRoles')" name="role">
            <el-select
              v-model="local.roleIds"
              multiple
              filterable
              :placeholder="t('flowDesigner.node.rolePh')"
              style="width: 100%;"
              @change="emitPatch"
            >
              <el-option v-for="r in roleOptions" :key="r.id" :label="r.label" :value="String(r.id)" />
            </el-select>
          </el-tab-pane>
          <el-tab-pane :label="t('flowDesigner.node.tabOrgs')" name="org">
            <el-tree-select
              v-model="local.orgIds"
              multiple
              :data="orgOptions"
              :placeholder="t('flowDesigner.node.orgPh')"
              check-strictly
              show-checkbox
              style="width: 100%;"
              @change="emitPatch"
            />
          </el-tab-pane>
        </el-tabs>
      </el-form-item>

      <el-form-item :label="t('flowDesigner.node.formCode')">
        <el-select
          v-model="local.formCode"
          :placeholder="t('flowDesigner.node.formCodePh')"
          filterable
          clearable
          style="width: 100%;"
          @change="emitPatch"
        >
          <el-option v-for="f in formOptions" :key="f.code" :label="`${f.name} (${f.code})`" :value="f.code" />
        </el-select>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FlowNodeData } from '@/api/flowDefine';
import { formDesignApi, type FormDesignItem } from '@/api/formDesign';

const props = defineProps<{ data: FlowNodeData }>();
const emit = defineEmits<{ patch: [Partial<FlowNodeData>] }>();
const { t } = useI18n();

const local = reactive<FlowNodeData>({
  type: 'APPROVAL',
  label: '',
  userIds: [],
  roleIds: [],
  orgIds: [],
  approvalType: 'OR',
  formCode: '',
});

const assigneeTab = ref('user');
const userOptions = ref<Array<{ id: number | string; label: string }>>([]);
const roleOptions = ref<Array<{ id: number | string; label: string }>>([]);
const orgOptions = ref<any[]>([]);
const formOptions = ref<FormDesignItem[]>([]);
const loadingUsers = ref(false);

watch(() => props.data, (d) => {
  Object.assign(local, JSON.parse(JSON.stringify(d || {})));
  if (!local.userIds) local.userIds = [];
  if (!local.roleIds) local.roleIds = [];
  if (!local.orgIds) local.orgIds = [];
}, { immediate: true, deep: true });

onMounted(async () => {
  const { http } = await import('@/api/http');
  try {
    const u = await http.get<any, any>('/system/users', { params: { page: 1, size: 200 } });
    const list: any[] = Array.isArray(u) ? u : ((u as any)?.records ?? (u as any)?.items ?? []);
    userOptions.value = list.map((x: any) => ({ id: x.id, label: x.username + (x.nickname ? ` (${x.nickname})` : '') }));
  } catch { /* ignore */ }
  try {
    const r = await http.get<any, any[]>('/system/roles');
    roleOptions.value = (Array.isArray(r) ? r : []).map((x: any) => ({ id: x.id, label: x.code + (x.name ? ` (${x.name})` : '') }));
  } catch { /* ignore */ }
  try {
    const o = await http.get<any, any>('/system/depts/tree');
    orgOptions.value = Array.isArray(o) ? o : [];
  } catch { /* ignore */ }
  try {
    formOptions.value = await formDesignApi.list();
  } catch { /* ignore */ }
});

function emitPatch() {
  emit('patch', { ...local });
}
</script>

<style scoped>
.assignee-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.assignee-tabs :deep(.el-tabs__item) { font-size: 12px; padding: 0 10px; }
</style>