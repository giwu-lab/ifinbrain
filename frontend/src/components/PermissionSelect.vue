<template>
  <el-select
    :model-value="modelValue || undefined"
    filterable
    :filter-method="filterMethod"
    allow-create
    clearable
    :placeholder="placeholder"
    class="perm-select"
    :loading="loading"
    @update:model-value="onChange"
    @visible-change="onVisible"
  >
    <el-option-group
      v-for="mod in filteredGroups"
      :key="mod.name"
      :label="modLabel(mod)"
    >
      <el-option
        v-for="p in mod.perms"
        :key="p.id ?? p.code"
        :value="p.code"
        :label="`${p.code} · ${p.name}`"
      >
        <div class="opt-row">
          <span class="opt-code">{{ p.code }}</span>
          <span class="opt-name">{{ p.name }}</span>
          <el-tag v-if="p.type" size="small" effect="plain" round class="opt-tag">
            {{ typeLabel(p.type) }}
          </el-tag>
        </div>
      </el-option>
    </el-option-group>
  </el-select>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { permissionApi, type PermissionItem } from '@/api/system';
import { groupByModule, type PermissionLike } from '@/utils/permission';

const { t: _t } = useI18n();

const props = withDefaults(
  defineProps<{
    modelValue?: string | null;
    placeholder?: string;
  }>(),
  { placeholder: '选择权限（可输入自定义码）' }
);

const emit = defineEmits<{ 'update:modelValue': [string | null] }>();

const options = ref<PermissionItem[]>([]);
const loading = ref(false);
const keyword = ref('');

const groupedOptions = computed(() => groupByModule(options.value));

const filteredGroups = computed(() => {
  if (!keyword.value.trim()) return groupedOptions.value;
  const q = keyword.value.trim().toLowerCase();
  return groupedOptions.value
    .map(mod => ({
      name: mod.name,
      perms: mod.perms.filter((p: PermissionLike) =>
        p.code.toLowerCase().includes(q) || (p.name || '').toLowerCase().includes(q)
      ),
    }))
    .filter(mod => mod.perms.length > 0);
});

function filterMethod(val: string) {
  keyword.value = val || '';
}

async function load() {
  if (options.value.length > 0) return;
  loading.value = true;
  try {
    options.value = await permissionApi.list();
  } catch (e) {
    options.value = [];
  } finally {
    loading.value = false;
  }
}

function onChange(v: string | undefined | null) {
  if (v === undefined || v === null || v === '') {
    emit('update:modelValue', null);
  } else {
    emit('update:modelValue', v);
  }
}

function onVisible(open: boolean) {
  if (open) load();
}

function modLabel(mod: { name: string; perms: PermissionLike[] }) {
  const name = _t(`system.permModule.${mod.name}`, mod.name);
  return `${name} · ${mod.perms.length}`;
}

const typeLabels: Record<string, string> = {
  API: 'API',
  MENU: '菜单',
  BUTTON: '按钮',
};
function typeLabel(t: string) {
  return typeLabels[t] || t;
}

onMounted(load);
</script>

<style scoped>
.perm-select {
  width: 100%;
}
.opt-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.opt-code {
  font-family: 'SF Mono', Menlo, monospace;
  color: var(--color-text);
  flex: 0 0 auto;
}
.opt-name {
  color: var(--color-text-secondary);
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.opt-tag {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--color-text-muted);
}
</style>