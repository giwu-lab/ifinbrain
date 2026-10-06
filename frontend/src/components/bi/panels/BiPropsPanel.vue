<script setup lang="ts">
/**
 * 右侧属性面板。四个折叠段：
 *   基础（name / 尺寸 / 图层）/ 样式（标题 / 背景 / 边框）/ 数据（BiDataBindPanel）/ 组件专属（extra）
 *
 * 所有变更 emit('patch') 给 Designer，由它统一调 store —— 面板本身不碰 store，
 * 这样每次输入 = 一次 commit，撤销粒度与拖拽一致。
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { Top, Bottom } from '@element-plus/icons-vue';
import type { BiWidget } from '@/api/bi';
import { WIDGET_META } from '@/utils/bi/layout';
import BiDataBindPanel from './BiDataBindPanel.vue';

const props = defineProps<{
  widget: BiWidget | null;
  canvasCols: number;
  canvasRows: number;
}>();

const emit = defineEmits<{
  (e: 'patch', patch: Partial<BiWidget>): void;
  (e: 'style', patch: Partial<BiWidget['style']>): void;
  (e: 'extra', patch: Record<string, any>): void;
  (e: 'layer', op: 'front' | 'back'): void;
}>();

const { t } = useI18n();

const active = ref(['basic', 'style', 'data', 'extra']);

const meta = computed(() => (props.widget ? WIDGET_META[props.widget.type] : null));
const needsData = computed(() => meta.value?.needsData ?? false);
const extra = computed(() => props.widget?.extra || {});

/** 各组件类型的额外配置项 */
function onExtra(k: string, v: any) {
  emit('extra', { ...extra.value, [k]: v });
}

const textAligns = ['left', 'center', 'right'];
</script>

<template>
  <div class="bi-props">
    <div v-if="!widget" class="empty">
      <el-icon class="empty-icon"><Pointer /></el-icon>
      <p>{{ t('bi.props.selectHint') }}</p>
    </div>

    <template v-else>
      <!-- 基础 -->
      <el-collapse v-model="active">
        <el-collapse-item name="basic" :title="t('bi.props.basic')">
          <el-form label-width="84px" size="small" label-position="left">
            <el-form-item :label="t('bi.props.type')">
              <span class="type-hint">
                <el-icon><component :is="WIDGET_META[widget.type].icon" /></el-icon>
                {{ t(`bi.widget.${widget.type}`, WIDGET_META[widget.type].labelKey || widget.type) }}
              </span>
            </el-form-item>

            <el-form-item :label="t('bi.props.title')">
              <el-input
                :model-value="widget.style.title"
                @update:model-value="(v: string) => emit('style', { title: v })"
              />
            </el-form-item>

            <el-form-item :label="t('bi.props.showTitle')">
              <el-switch
                :model-value="widget.style.titleVisible"
                @update:model-value="(v: any) => emit('style', { titleVisible: v })"
              />
            </el-form-item>

            <el-form-item :label="t('bi.props.size')">
              <div class="size-grid">
                <el-input-number
                  :model-value="widget.x" :min="0" :max="canvasCols - 1" :step="1"
                  controls-position="right"
                  @update:model-value="(v: any) => emit('patch', { x: v ?? 0 })"
                />
                <el-input-number
                  :model-value="widget.y" :min="0" :max="canvasRows - 1" :step="1"
                  controls-position="right"
                  @update:model-value="(v: any) => emit('patch', { y: v ?? 0 })"
                />
                <el-input-number
                  :model-value="widget.w" :min="1" :max="canvasCols" :step="1"
                  controls-position="right"
                  @update:model-value="(v: any) => emit('patch', { w: v ?? 1 })"
                />
                <el-input-number
                  :model-value="widget.h" :min="1" :max="canvasRows" :step="1"
                  controls-position="right"
                  @update:model-value="(v: any) => emit('patch', { h: v ?? 1 })"
                />
              </div>
            </el-form-item>

            <el-form-item :label="t('bi.props.layer')">
              <div class="btn-row">
                <el-button size="small" :icon="Top" @click="emit('layer', 'front')">
                  {{ t('bi.props.toFront') }}
                </el-button>
                <el-button size="small" :icon="Bottom" @click="emit('layer', 'back')">
                  {{ t('bi.props.toBack') }}
                </el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-collapse-item>

        <!-- 样式 -->
        <el-collapse-item name="style" :title="t('bi.props.style')">
          <el-form label-width="84px" size="small" label-position="left">
            <el-form-item :label="t('bi.props.transparent')">
              <el-switch
                :model-value="!!widget.style.transparent"
                @update:model-value="(v: any) => emit('style', { transparent: v })"
              />
            </el-form-item>
            <el-form-item :label="t('bi.props.bg')">
              <el-input
                :model-value="widget.style.background"
                :placeholder="'var(--bi-panel)'"
                @update:model-value="(v: string) => emit('style', { background: v })"
              />
            </el-form-item>
            <el-form-item :label="t('bi.props.borderColor')">
              <el-input
                :model-value="widget.style.borderColor"
                :placeholder="'var(--bi-border)'"
                @update:model-value="(v: string) => emit('style', { borderColor: v })"
              />
            </el-form-item>
            <el-form-item :label="t('bi.props.radius')">
              <el-input-number
                :model-value="widget.style.radius" :min="0" :max="24" :step="1"
                controls-position="right" style="width: 100%"
                @update:model-value="(v: any) => emit('style', { radius: v ?? 0 })"
              />
            </el-form-item>
            <el-form-item :label="t('bi.props.padding')">
              <el-input-number
                :model-value="widget.style.padding" :min="0" :max="48" :step="2"
                controls-position="right" style="width: 100%"
                @update:model-value="(v: any) => emit('style', { padding: v ?? 0 })"
              />
            </el-form-item>
          </el-form>
        </el-collapse-item>

        <!-- 数据 -->
        <el-collapse-item v-if="needsData" name="data" :title="t('bi.props.data')">
          <BiDataBindPanel
            :widget="widget"
            @patch="(p) => emit('patch', p)"
          />
        </el-collapse-item>

        <!-- 组件专属 -->
        <el-collapse-item name="extra" :title="t('bi.props.advanced')">
          <el-form label-width="84px" size="small" label-position="left">
            <!-- KPI -->
            <template v-if="widget.type === 'kpi'">
              <el-form-item :label="t('bi.props.prefix')">
                <el-input :model-value="extra.prefix" @update:model-value="(v: string) => onExtra('prefix', v)" />
              </el-form-item>
              <el-form-item :label="t('bi.props.unit')">
                <el-input :model-value="extra.unit" @update:model-value="(v: string) => onExtra('unit', v)" />
              </el-form-item>
              <el-form-item :label="t('bi.props.decimals')">
                <el-input-number
                  :model-value="extra.decimals ?? 2" :min="0" :max="6" :step="1"
                  controls-position="right" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('decimals', v)"
                />
              </el-form-item>
            </template>

            <!-- 表格 -->
            <template v-else-if="widget.type === 'table'">
              <el-form-item :label="t('bi.props.maxRows')">
                <el-input-number
                  :model-value="extra.maxRows ?? 20" :min="1" :max="500" :step="5"
                  controls-position="right" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('maxRows', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.showIndex')">
                <el-switch
                  :model-value="extra.showIndex !== false"
                  @update:model-value="(v: any) => onExtra('showIndex', v)"
                />
              </el-form-item>
            </template>

            <!-- 文本 -->
            <template v-else-if="widget.type === 'text'">
              <el-form-item :label="t('bi.props.content')">
                <el-input
                  type="textarea" :rows="3"
                  :model-value="extra.content"
                  @update:model-value="(v: string) => onExtra('content', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.align')">
                <el-radio-group
                  :model-value="extra.align || 'center'" size="small"
                  @update:model-value="(v: any) => onExtra('align', v)"
                >
                  <el-radio-button v-for="a in textAligns" :key="a" :label="a">{{ t(`bi.props.align_${a}`) }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item :label="t('bi.props.fontSize')">
                <el-input-number
                  :model-value="extra.fontSize ?? 24" :min="10" :max="120" :step="2"
                  controls-position="right" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('fontSize', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.textColor')">
                <el-input
                  :model-value="extra.color || 'var(--bi-text)'"
                  @update:model-value="(v: string) => onExtra('color', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.bold')">
                <el-switch
                  :model-value="extra.bold !== false"
                  @update:model-value="(v: any) => onExtra('bold', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.letterSpacing')">
                <el-input-number
                  :model-value="extra.letterSpacing ?? 0" :min="0" :max="40" :step="1"
                  controls-position="right" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('letterSpacing', v)"
                />
              </el-form-item>
            </template>

            <!-- 图片 -->
            <template v-else-if="widget.type === 'image'">
              <el-form-item :label="t('bi.props.setImageUrl')">
                <el-input
                  :model-value="extra.src" :placeholder="'https://...'"
                  @update:model-value="(v: string) => onExtra('src', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.fit')">
                <el-select
                  :model-value="extra.fit || 'cover'" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('fit', v)"
                >
                  <el-option label="cover" value="cover" />
                  <el-option label="contain" value="contain" />
                  <el-option label="fill" value="fill" />
                  <el-option label="none" value="none" />
                </el-select>
              </el-form-item>
            </template>

            <!-- 滚动列表 -->
            <template v-else-if="widget.type === 'marquee'">
              <el-form-item :label="t('bi.props.speed')">
                <el-input-number
                  :model-value="extra.speed ?? 24" :min="2" :max="200" :step="2"
                  controls-position="right" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('speed', v)"
                />
              </el-form-item>
              <el-form-item :label="t('bi.props.maxRows')">
                <el-input-number
                  :model-value="extra.maxRows ?? 8" :min="1" :max="100" :step="1"
                  controls-position="right" style="width: 100%"
                  @update:model-value="(v: any) => onExtra('maxRows', v)"
                />
              </el-form-item>
            </template>
          </el-form>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
/*
 * 边距统一（AGENTS §7「容器样式统一」）：
 * 之前这里有三套各说各话的留白 —— el-collapse 自带的 header padding 0 20px、
 * .bi-props 只写了 padding-bottom（等于没有左右内边距）、子面板 .bi-bind 又自己
 * 加了 8px 10px。叠在一起的结果是分段标题和表单内容左边缘对不齐，输入框一路
 * 顶到面板右边缘（截图里「圆角 / 内边距 / 关联查询」那几行只差几个像素就贴边）。
 *
 * 这里定一套：面板负责左右 14px，collapse 标题 padding 清零让标题和内容左对齐，
 * 表单项纵向间距收到 12px。所有子面板继承，不再各自为政。
 */
.bi-props {
  height: 100%;
  overflow: auto;
  padding: 4px 14px 20px;
}

/* el-collapse 默认样式是给整页用的，挤在 300px 面板里头尾都太松 */
.bi-props :deep(.el-collapse) {
  border: none;
}
.bi-props :deep(.el-collapse-item__header) {
  padding: 0;
  height: 38px;
  line-height: 38px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  background: transparent;
  border-bottom: none;
}
.bi-props :deep(.el-collapse-item__arrow) {
  color: var(--color-text-muted);
}
.bi-props :deep(.el-collapse-item__header:hover) {
  color: var(--color-primary);
}
.bi-props :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}
.bi-props :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}

/* 表单项：默认 18px 行距在窄面板里太散，label 12px 与其它面板的字段一致 */
.bi-props :deep(.el-form-item) {
  margin-bottom: 12px;
}
.bi-props :deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--color-text-secondary);
  padding-right: 8px;
  line-height: 1.4;
}

.empty {
  padding: 40px 16px;
  text-align: center;
  color: var(--color-text-muted);
}
.empty-icon {
  font-size: 28px;
  margin-bottom: 8px;
}
.empty p {
  font-size: 12px;
  margin: 0;
}
.type-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
/* 2×2 的坐标/尺寸输入：列间距给足，否则 el-input-number 的增减按钮会贴到相邻列 */
.size-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  width: 100%;
}
.size-grid :deep(.el-input-number) {
  width: 100%;
}
.btn-row {
  display: flex;
  gap: 8px;
}
.btn-row :deep(.el-button) {
  flex: 1;
  margin-left: 0;
}
</style>