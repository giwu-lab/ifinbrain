<script setup lang="ts">
/**
 * 共享设置对话框：可见范围（四档）+ 点名名单（读/写）。
 *
 * 名单采用「全量替换」语义（PUT /notes/{id}/shares），
 * 所以本地维护一份 items 草稿，关闭时不回写，保存时一次提交。
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { noteApi, type NoteItem, type NoteScope, type NoteShareItem, type NoteCandidate } from '@/api/note';
import type { ArchiveLibrary } from '@/api/document';
import { useAuthStore } from '@/stores/auth';

const props = defineProps<{ note: NoteItem }>();
const emit = defineEmits<{ (e: 'updated', note: NoteItem): void; (e: 'close'): void }>();

const { t } = useI18n();
const auth = useAuthStore();

const visible = defineModel<boolean>({ default: false });

const saving = ref(false);
const loading = ref(false);

const scopeType = ref<NoteScope>(props.note.scopeType);
const scopeDeptId = ref<number | undefined>(props.note.scopeDeptId);
const categoryId = ref<number | undefined>(props.note.categoryId);

/** 草稿名单：userId → grantType */
const items = ref<{ userId: number; grantType: 'READ' | 'WRITE' }[]>([]);
const candidates = ref<NoteCandidate[]>([]);
const libraries = ref<ArchiveLibrary[]>([]);
const pickUser = ref<number | undefined>(undefined);
const pickGrant = ref<'READ' | 'WRITE'>('READ');

const deptName = computed(() => candidates.value.find((c) => c.id === scopeDeptId.value)?.deptName);

const scopeOptions: NoteScope[] = ['PERSONAL', 'TEAM', 'LIBRARY', 'ORG'];

const needsDept = computed(() => scopeType.value === 'TEAM');
const needsLibrary = computed(() => scopeType.value === 'LIBRARY');

/** 候选表里已经授权过的人 + 本篇作者，后者不该出现在名单里。 */
const grantedIds = computed(() => new Set(items.value.map((i) => i.userId)));
const available = computed(() =>
  candidates.value.filter((c) => c.id !== props.note.ownerId && !grantedIds.value.has(c.id)),
);

const myDeptOptions = computed(() => {
  // 部门候选从候选用户里反推：项目里笔记页面不额外拉部门树，够用且不新增接口
  const seen = new Map<number, string>();
  candidates.value.forEach((c) => { if (c.deptId) seen.set(c.deptId, c.deptName || `#${c.deptId}`); });
  return Array.from(seen, ([id, name]) => ({ id, name }));
});

watch(visible, async (v) => {
  if (!v) return;
  scopeType.value = props.note.scopeType;
  scopeDeptId.value = props.note.scopeDeptId;
  categoryId.value = props.note.categoryId;
  loading.value = true;
  try {
    const [shares, cands, libs] = await Promise.all([
      noteApi.shares(props.note.id),
      noteApi.candidates(),
      noteApi.libraries(),
    ]);
    items.value = shares.map((s) => ({ userId: s.userId, grantType: s.grantType }));
    candidates.value = cands;
    libraries.value = libs;
  } finally {
    loading.value = false;
  }
});

function addMember() {
  if (!pickUser.value) return;
  if (!grantedIds.value.has(pickUser.value)) {
    items.value.push({ userId: pickUser.value, grantType: pickGrant.value });
  }
  pickUser.value = undefined;
}

function removeMember(userId: number) {
  items.value = items.value.filter((i) => i.userId !== userId);
}

function displayName(userId: number) {
  return candidates.value.find((c) => c.id === userId)?.displayName
      || candidates.value.find((c) => c.id === userId)?.username
      || `#${userId}`;
}

function deptOf(userId: number) {
  return candidates.value.find((c) => c.id === userId)?.deptName || '';
}

async function save() {
  if (needsDept.value && !scopeDeptId.value) {
    ElMessage.warning(t('note.deptRequired'));
    return;
  }
  if (needsLibrary.value && !categoryId.value) {
    ElMessage.warning(t('note.libraryRequired'));
    return;
  }
  saving.value = true;
  try {
    const updated = await noteApi.update(props.note.id, {
      title: props.note.title,
      content: props.note.content ?? '',
      scopeType: scopeType.value,
      scopeDeptId: needsDept.value ? scopeDeptId.value : undefined,
      categoryId: needsLibrary.value ? categoryId.value : undefined,
      tags: props.note.tags ?? [],
      relatedDocumentId: props.note.relatedDocumentId,
      version: props.note.version ?? 0,
    });
    await noteApi.replaceShares(props.note.id, items.value);
    ElMessage.success(t('note.shareSaved'));
    emit('updated', updated);
    visible.value = false;
  } finally {
    saving.value = false;
  }
}

/** 只看名单，不改范围（owner 也可能只是加个协作者）。 */
async function saveMembersOnly() {
  saving.value = true;
  try {
    await noteApi.replaceShares(props.note.id, items.value);
    ElMessage.success(t('note.shareSaved'));
    visible.value = false;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('note.shareTitle')"
    width="620px"
    :close-on-click-modal="false"
    append-to-body
  >
    <div v-loading="loading" class="share-body">
      <el-form label-width="100px" label-position="left">
        <el-form-item :label="t('note.scopeLabel')">
          <el-radio-group v-model="scopeType" class="scope-group">
            <el-radio v-for="s in scopeOptions" :key="s" :value="s" :disabled="!auth.hasPerm('note:share')">
              {{ t(`note.scopeTypes.${s}`) }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="needsDept" :label="t('note.deptLabel')" required>
          <el-select v-model="scopeDeptId" class="w-full" :placeholder="t('note.deptLabel')">
            <el-option v-for="d in myDeptOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needsLibrary" :label="t('note.publishTo')" required>
          <el-select v-model="categoryId" class="w-full" :placeholder="t('note.publishTo')">
            <el-option v-for="l in libraries" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <p class="hint">
          {{ t('note.scopeHint') }}：{{ scopeType ? t(`note.scopeHint.${scopeType}`) : '' }}
          <template v-if="needsDept && deptName"> · {{ deptName }}</template>
        </p>
      </el-form>

      <el-divider content-position="left">{{ t('note.share') }}</el-divider>
      <p class="hint">{{ t('note.shareScopeTip') }}</p>

      <div class="member-add">
        <el-select
          v-model="pickUser"
          filterable
          class="member-picker"
          :placeholder="t('note.shareAdd')"
        >
          <el-option v-for="c in available" :key="c.id" :label="c.displayName || c.username" :value="c.id">
            <span>{{ c.displayName || c.username }}</span>
            <span v-if="c.deptName" class="opt-dept">{{ c.deptName }}</span>
          </el-option>
        </el-select>
        <el-radio-group v-model="pickGrant" size="small">
          <el-radio-button value="READ">{{ t('note.shareGrantRead') }}</el-radio-button>
          <el-radio-button value="WRITE">{{ t('note.shareGrantWrite') }}</el-radio-button>
        </el-radio-group>
        <el-button :disabled="!pickUser" @click="addMember">{{ t('note.shareAdd') }}</el-button>
      </div>

      <div v-if="items.length === 0" class="member-empty">{{ t('note.shareCandidatesEmpty') }}</div>
      <div v-for="it in items" :key="it.userId" class="member-row">
        <div class="member-info">
          <span class="member-name">{{ displayName(it.userId) }}</span>
          <span v-if="deptOf(it.userId)" class="member-dept">{{ deptOf(it.userId) }}</span>
        </div>
        <div class="member-ops">
          <el-radio-group v-model="it.grantType" size="small">
            <el-radio-button value="READ">{{ t('note.shareGrantRead') }}</el-radio-button>
            <el-radio-button value="WRITE">{{ t('note.shareGrantWrite') }}</el-radio-button>
          </el-radio-group>
          <el-button text size="small" class="danger-text" @click="removeMember(it.userId)">
            {{ t('note.shareRemove') }}
          </el-button>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button :loading="saving" @click="saveMembersOnly">{{ t('common.save') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('common.ok') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.share-body { min-height: 200px; }
.w-full { width: 100%; }
.scope-group { display: flex; flex-wrap: wrap; gap: 4px 14px; }
.hint {
  font-size: 12px;
  color: var(--color-text-muted);
  margin: 0 0 4px;
  line-height: 1.6;
}
.member-add {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 10px 0;
}
.member-picker { flex: 1; min-width: 0; }
.opt-dept { float: right; color: var(--color-text-muted); font-size: 12px; }
.member-empty {
  font-size: 13px;
  color: var(--color-text-muted);
  padding: 12px 0;
}
.member-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid var(--color-border-soft);
}
.member-info { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.member-name { font-size: 13px; color: var(--color-text); }
.member-dept { font-size: 12px; color: var(--color-text-muted); }
.member-ops { display: flex; align-items: center; gap: 8px; }
.danger-text { color: var(--color-danger); }
</style>