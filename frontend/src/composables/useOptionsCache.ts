import { ref, shallowRef, onMounted, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import type { DeptItem } from '@/api/system';

export interface SelectOption {
  id: number | string;
  label: string;
}

/**
 * 模块级「一次性加载」缓存。
 *
 * <p>为什么需要：UserField / DeptField 各自在 onMounted 里拉接口。表格（TABLE）字段
 * 是「每行 × 每列」渲染一个控件实例 —— 10 行 × 1 个人员列就是 10 个 UserField 实例，
 * 会打 10 次一模一样的请求。缓存下沉到模块后，无论多少实例总共只拉一次。
 *
 * <p>失败语义：加载失败**不**标记为已加载，允许下一个实例重试；并发调用共享同一个
 * in-flight Promise，不会因为同时挂载而重复请求。
 */
function createOptionCache<T>(empty: T) {
  const data = shallowRef<T>(empty);
  const loading = ref(false);
  let loaded = false;
  let inflight: Promise<void> | null = null;

  async function ensure(loader: () => Promise<T>): Promise<void> {
    if (loaded) return;
    if (inflight) return inflight;
    loading.value = true;
    inflight = (async () => {
      try {
        data.value = await loader();
        loaded = true;
      } finally {
        loading.value = false;
        inflight = null;
      }
    })();
    return inflight;
  }

  /** 管理页改了数据后调用：下一次 ensure 会重新拉。 */
  function invalidate() {
    loaded = false;
    data.value = empty;
  }

  return { data, loading, ensure, invalidate, isLoaded: () => loaded };
}

export const userOptionsCache = createOptionCache<SelectOption[]>([]);
export const deptOptionsCache = createOptionCache<DeptItem[]>([]);

export function invalidateUserOptions() { userOptionsCache.invalidate(); }
export function invalidateDeptOptions() { deptOptionsCache.invalidate(); }

async function fetchUserOptions(): Promise<SelectOption[]> {
  const { http } = await import('@/api/http');
  const data = await http.get<any, any>('/system/users', { params: { page: 1, size: 200 } });
  const list: any[] = Array.isArray(data)
    ? data
    : ((data as any)?.records ?? (data as any)?.items ?? []);
  // displayName 是 UserItem 上的真实字段（此前这里读的是 nickname，接口根本不返回该字段，
  // 导致下拉里永远只有裸 username）。
  return list.map((u: any) => ({
    id: u.id,
    label: u.displayName ? `${u.username} (${u.displayName})` : u.username,
  }));
}

async function fetchDeptOptions(): Promise<DeptItem[]> {
  const { http } = await import('@/api/http');
  const data = await http.get<any, DeptItem[]>('/system/depts/tree');
  return Array.isArray(data) ? data : [];
}

/**
 * 表单「人员」字段的数据源。返回模块级共享的 ref —— 组件里直接 v-for 即可。
 *
 * <p>加载失败只提示一次（原本是每个实例各弹一次）。
 */
export function useUserOptions(): Ref<SelectOption[]> {
  const { t } = useI18n();
  onMounted(async () => {
    try {
      await userOptionsCache.ensure(fetchUserOptions);
    } catch {
      ElMessage.warning(t('formDesigner.warning.userLoadFailed'));
    }
  });
  return userOptionsCache.data as Ref<SelectOption[]>;
}

/** 表单「部门」字段的数据源（部门树）。 */
export function useDeptOptions(): Ref<DeptItem[]> {
  const { t } = useI18n();
  onMounted(async () => {
    try {
      await deptOptionsCache.ensure(fetchDeptOptions);
    } catch {
      ElMessage.warning(t('formDesigner.warning.deptLoadFailed'));
    }
  });
  return deptOptionsCache.data as Ref<DeptItem[]>;
}
