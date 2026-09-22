import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import 'element-plus/dist/index.css';
import 'nprogress/nprogress.css';
import AppRoot from './AppRoot.vue';
import router from './router';
import { i18n, getElementPlusLocale } from './i18n';
import './styles/global.css';

/**
 * el-table 操作列 fixed-right 单元格遮罩
 * element-plus 给 fixed 列 cell 写 inline style="background: inherit"，
 * 父级 current-row / hover 是半透明 rgba，inherit 后透底。
 * 解法：监听 cell 的 style 属性变化，立刻强制覆盖回不透明色。
 */
const BG_BODY = '#ffffff';
const BG_HEADER = '#f5f7fb';
const HOVER_BG = 'rgba(37, 99, 235, 0.10)';

function paintFixedCells(box: HTMLElement) {
  box.style.setProperty('background', BG_BODY, 'important');
  box.querySelectorAll<HTMLElement>('tr').forEach((tr) => {
    const isHeader = tr.closest('thead') !== null;
    const isHover = tr.matches(':hover');
    let bg = isHeader ? BG_HEADER : BG_BODY;
    if (!isHeader && isHover) bg = HOVER_BG;
    tr.querySelectorAll<HTMLElement>('td.el-table-fixed-column--right, th.el-table-fixed-column--right').forEach((cell) => {
      cell.style.setProperty('background', bg, 'important');
    });
  });
}

function applyFixedColumnMask() {
  document.querySelectorAll<HTMLElement>('.el-table__fixed-right').forEach((box) => {
    paintFixedCells(box);
    box.addEventListener('mouseover', () => paintFixedCells(box));
    box.addEventListener('mouseout', () => paintFixedCells(box));
  });
}

// 监听：element-plus 给 cell 改 inline style 时立刻重设
const mo = new MutationObserver((muts) => {
  for (const m of muts) {
    if (m.type === 'attributes'
      && (m.target as HTMLElement).classList?.contains('el-table-fixed-column--right')) {
      const cell = m.target as HTMLElement;
      const isHeader = cell.tagName === 'TH';
      const tr = cell.closest('tr');
      const isHover = tr?.matches(':hover') ?? false;
      let bg = isHeader ? BG_HEADER : BG_BODY;
      if (!isHeader && isHover) bg = HOVER_BG;
      cell.style.setProperty('background', bg, 'important');
    } else if (m.type === 'childList') {
      // 表格行数变化 / 重新渲染：重跑 paint
      const added = Array.from(m.addedNodes).some((n) => {
        return (n as HTMLElement).nodeType === 1 &&
          ((n as HTMLElement).classList?.contains('el-table__fixed-right') ||
           (n as HTMLElement).querySelector?.('.el-table__fixed-right'));
      });
      if (added) applyFixedColumnMask();
    }
  }
});
mo.observe(document.body, {
  childList: true, subtree: true, attributes: true,
  attributeFilter: ['style', 'class'],
});

// 初始兜底
if (document.readyState === 'complete') applyFixedColumnMask();
else window.addEventListener('load', applyFixedColumnMask);

const app = createApp(AppRoot);
for (const [key, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, comp);
}
app.use(createPinia());
app.use(router);
app.use(i18n);
// 初始 Element Plus locale；切换语言时由 el-config-provider 实时换
app.use(ElementPlus, { locale: getElementPlusLocale() });

// 注意：不要在这里直接调 fetchMe / setupDynamicRoutes。
// 异步初始化会与 router 的初始 navigation 竞态，导致 Vue Router warn "no match"。
// 恢复动态路由统一由 router.beforeEach 完成（拿到 auth 后再恢复）。

// 字典缓存预加载：登录后由 Layout 在 afterEach 中触发；
// 未登录时跳过，store.load() 内部安全降级（HTTP 401/403 不影响其他页面）。
import { useDictStore } from './stores/dict';
import { useI18nStore } from './stores/i18n';
const dictStore = useDictStore();
const i18nStore = useI18nStore();
const token = localStorage.getItem('accessToken');
if (token) {
  dictStore.load().catch(() => { /* 静默失败，组件首次访问时再重试 */ });
  i18nStore.load().catch(() => { /* 静默失败，保留 ts 文件作 fallback */ });
}

app.mount('#app');