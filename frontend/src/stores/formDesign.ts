import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import { formDesignApi, type FormFieldSchema, type FormDesignItem, type FieldType } from '@/api/formDesign';

let fieldSeq = 0;

function newFieldId(): string {
  return `f_${Date.now().toString(36)}_${(fieldSeq++).toString(36)}`;
}

function makeDefaultField(type: FieldType): FormFieldSchema {
  const base: FormFieldSchema = {
    id: newFieldId(),
    type,
    key: '',
    label: '',
    required: false,
    defaultValue: '',
    placeholder: '',
    fullWidth: true,
  };
  if (type === 'SELECT' || type === 'RADIO' || type === 'CHECKBOX') {
    base.options = [];
  }
  return base;
}

/** 模式：build 设计 / preview 运行时 / json JSON 查看 */
export type DesignerMode = 'build' | 'preview' | 'json';
export type DeviceMode = 'desktop' | 'tablet' | 'mobile';
const DEVICE_WIDTH: Record<DeviceMode, number> = { desktop: 1200, tablet: 768, mobile: 375 };

/**
 * 表单设计器 store（仿 mfish form-designer 架构）。
 *
 * <p>核心字段：
 * <ul>
 *   <li>meta + fields —— 表单元数据 + 字段列表</li>
 *   <li>mode / deviceMode —— 画布模式（设计/预览/JSON）与设备模式</li>
 *   <li>canvasWidth —— 当前设备模式对应的像素宽（响应式预览）</li>
 *   <li>history + historyIndex —— 撤销/重做栈（每个 commit 是 fields 深拷贝）</li>
 * </ul>
 */
export const useFormDesignStore = defineStore('formDesign', () => {
  const meta = ref<FormDesignItem | null>(null);
  const fields = ref<FormFieldSchema[]>([]);
  const selectedId = ref<string | null>(null);
  const dirty = ref(false);
  const loading = ref(false);

  // —— mfish 风格的状态 ——
  const designerMode = ref<DesignerMode>('build');
  const deviceMode = ref<DeviceMode>('desktop');
  const canvasWidth = computed(() => DEVICE_WIDTH[deviceMode.value]);
  const history = ref<string[]>([]);   // JSON snapshot 栈
  const historyIndex = ref(-1);
  const zoom = ref(1);                 // 0.5 ~ 1.5

  const selectedField = ref<FormFieldSchema | null>(null);

  watch(selectedId, (id) => {
    selectedField.value = fields.value.find(f => f.id === id) || null;
  });

  watch(deviceMode, () => { /* 触发 canvasWidth computed 重算 */ });

  // ===================== Reset / 初始化 =====================

  function reset(item?: FormDesignItem) {
    meta.value = item ?? null;
    fields.value = item?.fields ? structuredClone(item.fields) : [];
    selectedId.value = null;
    dirty.value = false;
    history.value = [];
    historyIndex.value = -1;
    saveHistory();
  }

  function setLoading(v: boolean) { loading.value = v; }
  function markDirty() { dirty.value = true; }
  function clearDirty() { dirty.value = false; }

  function setMode(m: DesignerMode) { designerMode.value = m; }
  function setDevice(d: DeviceMode) { deviceMode.value = d; }
  function setZoom(z: number) { zoom.value = Math.max(0.5, Math.min(1.5, z)); }

  // ===================== 撤销 / 重做 =====================

  function snapshot(): string {
    return JSON.stringify(fields.value);
  }

  /** 提交当前 fields 到 history 栈；标记 dirty。 */
  function commit() {
    // 截掉 historyIndex 之后的栈
    if (historyIndex.value + 1 < history.value.length) {
      history.value = history.value.slice(0, historyIndex.value + 1);
    }
    history.value.push(snapshot());
    historyIndex.value = history.value.length - 1;
    dirty.value = true;
  }

  function saveHistory() {
    if (history.value.length === 0) {
      history.value = [snapshot()];
      historyIndex.value = 0;
    }
  }

  function undo() {
    if (historyIndex.value <= 0) return;
    historyIndex.value--;
    fields.value = JSON.parse(history.value[historyIndex.value]);
    dirty.value = true;
  }

  function redo() {
    if (historyIndex.value >= history.value.length - 1) return;
    historyIndex.value++;
    fields.value = JSON.parse(history.value[historyIndex.value]);
    dirty.value = true;
  }

  const canUndo = computed(() => historyIndex.value > 0);
  const canRedo = computed(() => historyIndex.value < history.value.length - 1);

  // ===================== 字段操作 =====================

  function addField(type: FieldType, insertAt?: number) {
    const f = makeDefaultField(type);
    if (insertAt == null || insertAt < 0 || insertAt > fields.value.length) {
      fields.value.push(f);
    } else {
      fields.value.splice(insertAt, 0, f);
    }
    selectedId.value = f.id;
    commit();
  }

  function removeField(id: string) {
    const idx = fields.value.findIndex(f => f.id === id);
    if (idx >= 0) fields.value.splice(idx, 1);
    if (selectedId.value === id) selectedId.value = null;
    commit();
  }

  function updateField(id: string, patch: Partial<FormFieldSchema>) {
    const f = fields.value.find(x => x.id === id);
    if (!f) return;
    Object.assign(f, patch);
    commit();
  }

  function select(id: string | null) { selectedId.value = id; }

  function moveField(id: string, direction: 'up' | 'down') {
    const idx = fields.value.findIndex(f => f.id === id);
    if (idx < 0) return;
    const target = direction === 'up' ? idx - 1 : idx + 1;
    if (target < 0 || target >= fields.value.length) return;
    const [it] = fields.value.splice(idx, 1);
    fields.value.splice(target, 0, it);
    commit();
  }

  function duplicate(id: string) {
    const src = fields.value.find(f => f.id === id);
    if (!src) return;
    const idx = fields.value.findIndex(f => f.id === id);
    const copy = JSON.parse(JSON.stringify(src));
    copy.id = newFieldId();
    copy.key = `${copy.key}_copy`;
    copy.label = `${copy.label} (copy)`;
    fields.value.splice(idx + 1, 0, copy);
    selectedId.value = copy.id;
    commit();
  }

  // ===================== 校验 =====================

  function validate(): string | null {
    const seen = new Set<string>();
    for (const f of fields.value) {
      if (!f.key || !f.key.trim()) return `字段 "${f.label || f.type}" 未设置 key`;
      if (!seen.add(f.key)) return `字段 key 重复: ${f.key}`;
      if (!f.label || !f.label.trim()) return `字段 key=${f.key} 未设置 label`;
      if ((f.type === 'SELECT' || f.type === 'RADIO' || f.type === 'CHECKBOX')
          && (!f.options || f.options.length === 0)) {
        return `字段 "${f.label}" (${f.type}) 必须配置至少一个选项`;
      }
    }
    return null;
  }

  return {
    // data
    meta, fields, selectedId, selectedField, dirty, loading,
    // designer state
    designerMode, deviceMode, canvasWidth, zoom,
    history, historyIndex, canUndo, canRedo,
    // ops
    reset, setLoading, markDirty, clearDirty,
    setMode, setDevice, setZoom,
    commit, saveHistory, undo, redo,
    addField, removeField, updateField, select, moveField, duplicate,
    validate,
  };
});

export type { FormFieldSchema, FormDesignItem, FieldType } from '@/api/formDesign';