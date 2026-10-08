<script setup lang="ts">
/**
 * 笔记正文渲染（只读）。
 *
 * 只用 utils/markdown.ts 的 renderMarkdown —— 它过 DOMPurify，
 * 不要在这里自己 v-html 原始 markdown，那等于开 XSS。
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { renderMarkdown, extractOutline } from '@/utils/markdown';

const props = withDefaults(defineProps<{
  content?: string | null;
  /** 是否在右侧显示标题大纲 */
  showOutline?: boolean;
  /** 是否把正文渲染成一张「纸」（白底 + 边框 + 投影）。
   *  编辑页需要它来跟灰底页面区分；历史版本抽屉里是嵌在弹层中的，
   *  再套一层纸反而多一道边，所以那边不传。 */
  paper?: boolean;
}>(), { content: '', showOutline: true, paper: false });

const emit = defineEmits<{ (e: 'jump', id: string): void }>();
const { t } = useI18n();

const html = computed(() => renderMarkdown(props.content));
const outline = computed(() => (props.showOutline ? extractOutline(html.value) : []));

function jump(id: string) {
  emit('jump', id);
}
</script>

<template>
  <div class="note-viewer" :class="{ 'is-paper': paper }">
    <div class="md-body" v-html="html" />
    <aside v-if="showOutline && outline.length > 1" class="md-outline">
      <div class="outline-title">{{ t('note.outline') }}</div>
      <div
        v-for="item in outline"
        :key="item.id"
        class="outline-item"
        :class="`lv-${item.level}`"
        @click="jump(item.id)"
      >
        {{ item.text }}
      </div>
    </aside>
  </div>
</template>

<style scoped>
.note-viewer {
  display: flex;
  gap: 24px;
  width: 100%;
}
/* 与 NoteEditor 同一张纸面：宽度走同一个全局变量，两种状态正文不会左右跳 */
.md-body {
  flex: 1;
  min-width: 0;
  max-width: var(--note-paper-width);
  margin: 0 auto;
  font-size: 15px;
  line-height: 1.8;
  color: var(--color-text);
  word-break: break-word;
}

/* 纸面模式：与 NoteEditor.paper-inner 同一套视觉 —— 拉伸通高、白底、左右细线。
 * 高度靠 min-height:100% + 默认的 align-items:stretch 把纸铺满整条可视区，
 * 内容超出时由 Edit.vue 的 .read-pane 滚动。 */
.note-viewer.is-paper {
  min-height: 100%;
}
.note-viewer.is-paper .md-body {
  padding: 40px;
  background: var(--color-bg-elev);
  border-left: 1px solid var(--color-border-soft);
  border-right: 1px solid var(--color-border-soft);
  box-shadow: var(--note-paper-shadow);
}

.md-outline {
  flex: 0 0 180px;
  position: sticky;
  top: 0;
  max-height: 80vh;
  overflow-y: auto;
  border-left: 1px solid var(--color-border-soft);
  padding-left: 12px;
}
.outline-title {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}
.outline-item {
  font-size: 13px;
  color: var(--color-text-secondary);
  padding: 3px 0;
  cursor: pointer;
  border-radius: 3px;
}
.outline-item:hover {
  color: var(--color-primary);
}
.outline-item.lv-2 { padding-left: 12px; }
.outline-item.lv-3 { padding-left: 24px; color: var(--color-text-muted); }

/* v-html 出来的内容拿不到 scoped 属性名，必须 :deep */
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  font-weight: 700;
  color: var(--color-text);
  margin: 20px 0 10px;
  scroll-margin-top: 80px;
}
.md-body :deep(h1) { font-size: 28px; margin: 24px 0 10px; }
.md-body :deep(h2) { font-size: 22px; margin: 20px 0 8px; }
.md-body :deep(h3) { font-size: 18px; margin: 16px 0 8px; }
.md-body :deep(p) { margin: 8px 0; }
.md-body :deep(ul),
.md-body :deep(ol) { padding-left: 22px; margin: 8px 0; }
.md-body :deep(li) { margin: 3px 0; }
.md-body :deep(li::marker) { color: var(--color-text-muted); }

.md-body :deep(code) {
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
  font-size: 13px;
  background: var(--color-primary-softer);
  color: var(--color-text);
  padding: 1px 5px;
  border-radius: 3px;
}
.md-body :deep(pre) {
  background: var(--color-bg);
  border: 1px solid var(--color-border-soft);
  border-radius: 6px;
  padding: 12px 14px;
  overflow-x: auto;
  margin: 10px 0;
}
.md-body :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: 13px;
  line-height: 1.6;
}

.md-body :deep(blockquote) {
  margin: 10px 0;
  padding: 4px 14px;
  border-left: 3px solid var(--color-border-strong);
  color: var(--color-text-secondary);
}

.md-body :deep(a) {
  color: var(--color-primary);
  text-decoration: none;
}
.md-body :deep(a:hover) { text-decoration: underline; }

.md-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
  font-size: 13px;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--color-border);
  padding: 6px 10px;
  text-align: left;
}
.md-body :deep(th) {
  background: var(--color-bg);
  font-weight: 600;
}

.md-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--color-border);
  margin: 18px 0;
}
.md-body :deep(img) { max-width: 100%; }
.md-body :deep(input[type='checkbox']) {
  margin-right: 6px;
  pointer-events: none;
}
</style>