import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import {
  formDesignApi,
  type FormFieldSchema,
  type FormDesignItem,
  type FieldType,
  type FormGridConfig,
} from '@/api/formDesign';
import {
  DEFAULT_GRID,
  clampLayout,
  findFreeSlot,
  hasCollision,
  layoutRows,
  migrateFields,
  type FormGridLayout,
} from '@/utils/form/layout';
import { renameExprRefs } from '@/composables/useFormExpr';
import { defaultIdentity } from '@/utils/form/identity';
import { i18n } from '@/i18n';
import { useFormExpr } from '@/composables/useFormExpr';

let fieldSeq = 0;

/**
 * 取一条文案。
 *
 * vue-i18n 的 `global.t` 在本仓 vue-tsc 下被解析成不可调用（`api/http.ts:131`
 * 有同一个 TS2349，是既有类型缺口）。这里显式取一次可调用签名，
 * 免得业务代码里到处散 any。
 */
const tr = (key: string): string => (i18n.global.t as unknown as (k: string) => string)(key);

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
    // 条件逻辑与选项源一律显式留空：写清楚「这些开关存在但没配」，
    // 免得属性面板分不清 undefined（没配）和 ''（用户清空了）。
    visibleWhen: undefined,
    disabledWhen: undefined,
    requiredWhen: undefined,
    optionsSource: undefined,
  };
  if (type === 'SELECT' || type === 'RADIO' || type === 'CHECKBOX') {
    base.options = [];
  }
  if (type === 'TABLE') {
    // 表格必须占整行：半行宽度下多列表格没法看
    base.fullWidth = true;
    base.defaultValue = '';
    base.columns = [
      { key: 'name', label: '名称', type: 'TEXT', required: true },
      { key: 'amount', label: '金额', type: 'NUMBER', required: false },
    ];
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

  /** 表单级栅格配置 */
  const grid = ref<FormGridConfig>({ ...DEFAULT_GRID });

  /** 画布占了几行 —— 供 FormCanvas 决定容器高度 */
  const gridRows = computed(() => layoutRows(fields.value, grid.value));

  /**
   * 加载表单。
   *
   * 关键：`migrateFields` 在这里跑一次，把老 schema 的 `fullWidth` 语义铺成
   * 栅格坐标。迁移后**每个字段都有 layout**，渲染层只需要一条路径 ——
   * 不做「有 layout 走新逻辑、没 layout 走老逻辑」的双分支。
   */
  function reset(item?: FormDesignItem) {
    meta.value = item ?? null;
    const raw = item?.fields ? structuredClone(item.fields) : [];
    fields.value = migrateFields(raw, grid.value);
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

  /** 除自己以外所有字段占的位（找空位用） */
  function occupied(excludeId?: string): FormGridLayout[] {
    return fields.value.filter(f => f.id !== excludeId && f.layout).map(f => f.layout!);
  }

  /** 字段类型的默认跨列数：整行元素占满，两个短字段并排占一半 */
  function defaultSpan(type: FieldType, cfg = grid.value): number {
    if (type === 'DIVIDER' || type === 'TABLE' || type === 'RICH_TEXT' || type === 'TEXTAREA') {
      return cfg.cols;
    }
    return Math.floor(cfg.cols / 2);
  }

  function addField(type: FieldType, insertAt?: number) {
    const f = makeDefaultField(type);
    // 显示名 / 标识 key 由系统按规则预填（utils/form/identity），不再留空。
    // 类型显示名走 i18n 单一数据源，不在 store 里另存一份中英文表。
    const ident = defaultIdentity(
      type,
      fields.value.map((x) => x.key),
      tr(`formDesigner.type.${type}`),
    );
    f.key = ident.key;
    f.label = ident.label;
    const span = defaultSpan(type);
    f.layout = findFreeSlot(occupied(), span, 1, grid.value);
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

  /**
   * 移动 / 缩放字段。
   *
   * @param commitNow 拖拽过程中传 false（只改内存），松手再 commit 一次 ——
   *                  否则鼠标每动一像素压一帧撤销栈，10 步就满了。
   */
  function setLayout(id: string, patch: Partial<FormGridLayout>, commitNow = true) {
    const f = fields.value.find(x => x.id === id);
    if (!f) return;
    f.layout = clampLayout({ ...(f.layout ?? { x: 0, y: 0, w: 24, h: 1 }), ...patch }, grid.value);
    if (commitNow) commit();
  }

  /**
   * 移动字段到指定栅格位，重叠则挤开下方字段。
   *
   * 挤开而不是拒绝：拖拽时撞到别人直接弹回去手感很差。
   * `pushDown` 逐轮把重叠的字段推到下方，直到无重叠为止。
   */
  function moveTo(id: string, target: Partial<FormGridLayout>) {
    const f = fields.value.find(x => x.id === id);
    if (!f) return;
    const moving = clampLayout({ ...(f.layout ?? { x: 0, y: 0, w: 24, h: 1 }), ...target }, grid.value);
    f.layout = moving;

    // 最多推 20 轮，防止互相顶死时死循环
    for (let round = 0; round < 20; round++) {
      const hit = hasCollision(fields.value, id, moving);
      if (!hit) break;
      hit.layout = { ...hit.layout!, y: moving.y + moving.h };
    }
    commit();
  }

  function select(id: string | null) { selectedId.value = id; }

  /**
   * 大纲里的上下移动。
   *
   * 栅格模型下列表顺序与空间位置是两件事：字段数组决定渲染层级与大纲顺序，
   * layout 决定画布落位。这里只改数组顺序，**保留原坐标**。
   */
  function moveField(id: string, direction: 'up' | 'down') {
    const idx = fields.value.findIndex(f => f.id === id);
    if (idx < 0) return;
    const target = direction === 'up' ? idx - 1 : idx + 1;
    if (target < 0 || target >= fields.value.length) return;
    const [it] = fields.value.splice(idx, 1);
    fields.value.splice(target, 0, it);
    commit();
  }

  // 注：删除字段后**不**自动上移下方字段。栅格是用户刻意排出来的，
  // 留个洞让用户自己拖，比系统自作主张重排更可预期。

  /**
   * 复制字段。
   *
   * 除了改 key，还要把表达式里对旧 key 的引用一并改写 —— 否则
   * label 写成 `{{$form.amount}}` 的字段被复制后，新字段仍指向原字段，
   * 用户看不出任何异常，但数据是错的。表达式属性清单见 EXPR_ATTRS。
   */
  function duplicate(id: string) {
    const src = fields.value.find(f => f.id === id);
    if (!src) return;
    const idx = fields.value.findIndex(f => f.id === id);
    const copy = JSON.parse(JSON.stringify(src));
    copy.id = newFieldId();
    const newKey = `${copy.key}_copy`;
    renameExprRefs(copy, src.key, newKey);
    copy.key = newKey;
    copy.label = `${copy.label} (copy)`;
    // 复制件必须落到一个空位，不能沿用原坐标 —— 否则会和原件重叠
    copy.layout = findFreeSlot(occupied(id), src.layout?.w ?? defaultSpan(src.type), src.layout?.h ?? 1, grid.value);
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
          && !f.optionsSource
          && (!f.options || f.options.length === 0)) {
        return `字段 "${f.label}" (${f.type}) 必须配置至少一个选项，或开启动态选项源`;
      }
      // 动态选项源在设计期只能校验「填全了」，列名对不对要等运行时取数才知道
      if (f.optionsSource) {
        const s = f.optionsSource;
        if (!s.queryId || s.queryId <= 0) {
          return `字段 "${f.label}" 的动态选项源未选择查询`;
        }
        if (!s.labelField?.trim()) return `字段 "${f.label}" 的动态选项源未填写文本列`;
        if (!s.valueField?.trim()) return `字段 "${f.label}" 的动态选项源未填写值列`;
      }
    // 栅格重叠：两个字段压在同一块区域，填表人会看到一个盖住另一个
      if (f.layout) {
        const hit = hasCollision(fields.value, f.id, f.layout);
        if (hit) {
          return `字段 "${f.label}" 与「${hit.label || hit.key}」在画布上重叠`;
        }
      }
    }

    // 表达式校验：未定义字段引用 + 语法错误。
    // 两类都是静态可判定的（不依赖用户填了什么），所以保存前就能拦下，
    // 不必等到运行时表单渲染才发现。空上下文即可 —— 这里只做静态扫描。
    const exprIssues = useFormExpr({}).issues(fields.value);
    if (exprIssues.length) {
      const it = exprIssues[0];
      const detail = it.kind === 'undefined-ref'
        ? `{{$form.${it.detail}}} 引用了不存在的字段 "${it.detail}"`
        : it.detail;
      return `表达式错误 (${it.slotLabel}): ${detail}`;
    }

    return null;
  }

  return {
    // data
    meta, fields, selectedId, selectedField, dirty, loading,
    // designer state
    designerMode, deviceMode, canvasWidth, zoom,
    // 栅格
    grid, gridRows,
    history, historyIndex, canUndo, canRedo,
    // ops
    reset, setLoading, markDirty, clearDirty,
    setMode, setDevice, setZoom,
    commit, saveHistory, undo, redo,
    addField, removeField, updateField, select, moveField, duplicate,
    setLayout, moveTo, defaultSpan,
    validate,
  };
});

export type { FormFieldSchema, FormDesignItem, FieldType } from '@/api/formDesign';