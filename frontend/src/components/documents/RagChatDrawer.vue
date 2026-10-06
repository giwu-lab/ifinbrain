<template>
  <el-drawer
    v-model="visible"
    class="rag-drawer"
    direction="rtl"
    size="min(560px, 92vw)"
    :destroy-on-close="true"
  >
    <template #header>
      <div class="rag-header">
        <div class="rag-header-left">
          <el-icon class="rag-title-icon"><MagicStick /></el-icon>
          <span class="rag-title">{{ t('documents.rag.title') }}</span>
          <el-tag v-if="available && totalChunks > 0" size="small" type="info" round>
            {{ t('documents.rag.indexedBadge', { n: totalChunks }) }}
          </el-tag>
        </div>
        <el-button
          v-if="messages.length"
          size="small"
          link
          :icon="Delete"
          @click="clearAll"
        >
          {{ t('documents.rag.clear') }}
        </el-button>
      </div>
    </template>

    <!-- RAG 未启用（后端 ifinbrain.rag.enabled=false 时整组端点不存在） -->
    <div v-if="available === false" class="rag-unavailable">
      <el-icon class="big-icon"><Warning /></el-icon>
      <p>{{ t('documents.rag.unavailable') }}</p>
    </div>

    <div v-else class="rag-body">
      <!-- 空态 -->
      <div v-if="!messages.length && !asking" class="rag-empty">
        <el-icon class="empty-icon"><ChatDotRound /></el-icon>
        <h3 class="empty-title">{{ t('documents.rag.emptyTitle') }}</h3>
        <p class="empty-desc muted">{{ t('documents.rag.emptyDesc') }}</p>
        <el-alert
          v-if="available && totalChunks === 0"
          class="no-index-alert"
          type="warning"
          :closable="false"
          show-icon
          :title="t('documents.rag.noIndexHint')"
        />
        <div v-if="available && totalChunks === 0" class="examples">
          <span class="examples-label muted">{{ t('documents.rag.examples') }}</span>
          <el-tag
            v-for="q in examples"
            :key="q"
            class="example-tag"
            effect="plain"
            type="info"
            @click="useExample(q)"
          >{{ q }}</el-tag>
        </div>
      </div>

      <!-- 对话记录 -->
      <div v-else ref="scrollBox" class="rag-scroll">
        <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
          <div class="msg-avatar">
            <el-icon v-if="m.role === 'q'"><User /></el-icon>
            <el-icon v-else><MagicStick /></el-icon>
          </div>
          <div class="msg-main">
            <div class="msg-bubble">{{ m.text }}</div>

            <!-- 引用溯源 -->
            <div v-if="m.citations && m.citations.length" class="citations">
              <div class="citations-label muted">{{ t('documents.rag.citeTitle') }}</div>
              <div
                v-for="(c, i) in m.citations"
                :key="i"
                class="citation"
                role="button"
                tabindex="0"
                @click="openSource(c)"
                @keydown.enter="openSource(c)"
              >
                <el-icon class="cite-icon"><Document /></el-icon>
                <span class="cite-name" :title="c.filename || ''">{{ c.filename || t('documents.rag.unknownFile') }}</span>
                <span class="cite-meta mono">#{{ c.chunkIndex }}</span>
                <span class="cite-meta mono" :title="t('documents.rag.distanceHint')">d={{ c.distance.toFixed(3) }}</span>
                <el-tooltip placement="top" :show-after="200">
                  <template #content>
                    <div class="cite-snippet-tip">{{ c.snippet }}</div>
                  </template>
                  <el-icon class="cite-more"><View /></el-icon>
                </el-tooltip>
              </div>
            </div>

            <div v-if="m.elapsedMs != null" class="msg-elapsed mono muted">
              {{ t('documents.rag.elapsed', { ms: m.elapsedMs }) }}
            </div>
          </div>
        </div>

        <!-- 生成中 -->
        <div v-if="asking" class="msg a">
          <div class="msg-avatar"><el-icon><MagicStick /></el-icon></div>
          <div class="msg-main">
            <div class="msg-bubble thinking">
              <el-icon class="spin"><Loading /></el-icon>
              <span>{{ t('documents.rag.think') }}</span>
            </div>
          </div>
        </div>

        <!-- 内联错误 -->
        <el-alert
          v-if="errorMsg"
          class="rag-error"
          type="error"
          :closable="true"
          show-icon
          :title="errorMsg"
          @close="errorMsg = ''"
        />
      </div>

      <!-- 输入区 -->
      <div class="rag-input">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="500"
          show-word-limit
          :placeholder="t('documents.rag.placeholder')"
          :disabled="!available || asking"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          class="send-btn"
          type="primary"
          :icon="Promotion"
          :loading="asking"
          :disabled="!available || !question.trim()"
          @click="send"
        >
          {{ t('documents.rag.send') }}
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  ChatDotRound, Delete, Document, Loading, MagicStick, Promotion, User, View, Warning,
} from '@element-plus/icons-vue';
import { ragApi, type RagCitation } from '@/api/rag';

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  /** 点击引用 → 溯源回跳原档案（父组件负责取详情并打开预览） */
  (e: 'open-document', documentId: number): void;
}>();

const { t } = useI18n();

/** 示例问题走 i18n，避免硬编码中文 */
const EXAMPLE_KEYS = [
  'documents.rag.example1',
  'documents.rag.example2',
  'documents.rag.example3',
] as const;
const examples = computed<string[]>(() => EXAMPLE_KEYS.map((k) => t(k as string)));

interface Msg {
  id: number;
  role: 'q' | 'a';
  text: string;
  citations?: RagCitation[];
  elapsedMs?: number;
}

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
});

const messages = ref<Msg[]>([]);
const question = ref('');
const asking = ref(false);
const errorMsg = ref('');
const scrollBox = ref<HTMLElement | null>(null);
/** null = 探测中；false = RAG 未启用（端点不存在） */
const available = ref<boolean | null>(null);
const totalChunks = ref(0);
let seq = 0;

/** 打开时探测一次：RAG 关闭时后端不注册这些端点，stats 必然失败 */
watch(visible, async (v) => {
  if (!v || available.value !== null) return;
  try {
    const s = await ragApi.stats();
    totalChunks.value = s?.totalChunks ?? 0;
    available.value = true;
  } catch {
    available.value = false;
  }
});

function useExample(q: string) {
  question.value = q;
  send();
}

function clearAll() {
  messages.value = [];
  errorMsg.value = '';
}

async function scrollToBottom() {
  await nextTick();
  const el = scrollBox.value;
  if (el) el.scrollTop = el.scrollHeight;
}

function openSource(c: RagCitation) {
  if (c.documentId) emit('open-document', c.documentId);
}

async function send() {
  const q = question.value.trim();
  if (!q || asking.value || !available.value) return;

  errorMsg.value = '';
  messages.value.push({ id: ++seq, role: 'q', text: q });
  question.value = '';
  asking.value = true;
  await scrollToBottom();

  try {
    const r = await ragApi.ask(q);
    messages.value.push({
      id: ++seq,
      role: 'a',
      text: r.answer || '',
      citations: r.citations || [],
      elapsedMs: r.elapsedMs,
    });
  } catch (e: any) {
    errorMsg.value = e?.message ? t('documents.rag.askFail', { msg: e.message }) : t('documents.rag.askFail', { msg: '—' });
  } finally {
    asking.value = false;
    await scrollToBottom();
  }
}
</script>

<style scoped>
.rag-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.rag-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.rag-title-icon { color: var(--el-color-primary); }
.rag-title { font-weight: 600; }

.rag-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.rag-unavailable {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 48px 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.big-icon { font-size: 40px; color: var(--el-color-warning); }

.rag-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 20px;
  text-align: center;
}
.empty-icon { font-size: 36px; color: var(--el-color-primary); }
.empty-title { margin: 0; font-size: 15px; }
.empty-desc { margin: 0; font-size: 13px; line-height: 1.6; }
.no-index-alert { margin-top: 6px; width: 100%; }
.examples {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
}
.examples-label { font-size: 12px; }
.example-tag { cursor: pointer; }

.rag-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.msg {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.msg-avatar {
  flex: 0 0 26px;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
}
.msg.a .msg-avatar { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.msg-main { flex: 1; min-width: 0; }

.msg-bubble {
  padding: 9px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  line-height: 1.65;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.q .msg-bubble { background: var(--el-color-primary-light-9); }
.msg-bubble.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
}
.spin { animation: rag-spin 1s linear infinite; }
@keyframes rag-spin { to { transform: rotate(360deg); } }

.citations {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.citations-label { font-size: 11px; }
.citation {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}
.citation:hover { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.cite-icon { color: var(--el-text-color-secondary); flex: 0 0 auto; }
.cite-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cite-meta { color: var(--el-text-color-secondary); font-size: 11px; flex: 0 0 auto; }
.cite-more { color: var(--el-text-color-secondary); flex: 0 0 auto; }
.cite-snippet-tip { max-width: 320px; line-height: 1.6; white-space: pre-wrap; }

.msg-elapsed { margin-top: 4px; font-size: 11px; }
.rag-error { margin-top: 4px; }

.rag-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 10px;
  margin-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.send-btn { flex: 0 0 auto; }
</style>
