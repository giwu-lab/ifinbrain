import { defineStore } from 'pinia';
import { ref } from 'vue';
import { archiveApi, type ArchiveLibrary } from '@/api/document';

/**
 * 档案库列表缓存（按 slug → archive）。
 * 仅供顶部 tab 标签条按 slug 解析档案库名（labelOf(tab)）使用。
 * 完整档案库管理页（documents/archive/Index.vue）继续用自己的 rows，不复用本 store。
 */
export const useArchiveStore = defineStore('archive', () => {
  const items = ref<ArchiveLibrary[]>([]);
  const loaded = ref(false);
  const loading = ref(false);

  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return;
    if (loading.value) return;
    loading.value = true;
    try {
      items.value = await archiveApi.list();
      loaded.value = true;
    } catch (e) {
      console.warn('[archive] load failed:', e);
    } finally {
      loading.value = false;
    }
  }

  function bySlug(slug: string): ArchiveLibrary | undefined {
    return items.value.find((a) => a.slug === slug);
  }

  return { items, loaded, loading, load, bySlug };
});