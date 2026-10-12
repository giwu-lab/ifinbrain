import { ref, shallowRef, onMounted, computed, type Ref, type ComputedRef } from 'vue';
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

/* ===================== 部门 - 人员 两级树 ===================== */

/**
 * 三级树节点。value 用带前缀的字符串，避免部门 id 与用户 id 撞值
 * （两者都是自增 bigint，不加前缀 `value: '1'` 既是部门 1 也可能是用户 1）。
 */
export interface OrgNode {
  value: string;
  label: string;
  kind: 'dept' | 'unassigned' | 'user';
  /** 部门 / 未分配节点不可选 —— 人员才是有效选择 */
  disabled: boolean;
  /** 仅 kind === 'user'：用户主键 */
  userId?: number;
  /** 仅 kind === 'user'：displayName || username，提交时存这个值（见 ReimburseDialog） */
  userName?: string;
  children?: OrgNode[];
}

export const orgTreeCache = createOptionCache<OrgNode[]>([]);

export function invalidateOrgTree() { orgTreeCache.invalidate(); }

/** 单页拉人上限：后端 PageReq.MAX_SIZE 硬封顶 200，超了会被静默夹回。 */
const ORG_USER_PAGE_SIZE = 200;
/** 翻页安全上限：20 页 × 200 = 4000 人，够绝大多数企业；防 total 异常时死循环。 */
const ORG_USER_MAX_PAGES = 20;

/** 拉全部用户（翻页直到取完）。单页 200 条，不翻页会在超过 200 人时静默漏人。 */
async function fetchAllUsers(): Promise<any[]> {
  const { http } = await import('@/api/http');
  const out: any[] = [];
  for (let page = 1; page <= ORG_USER_MAX_PAGES; page++) {
    const data = await http.get<any, any>('/system/users', {
      params: { page, size: ORG_USER_PAGE_SIZE },
    });
    const list: any[] = Array.isArray(data)
      ? data
      : ((data as any)?.items ?? (data as any)?.records ?? []);
    if (!Array.isArray(list) || list.length === 0) break;
    out.push(...list);
    const total = Number((data as any)?.total ?? 0);
    if (total > 0 && out.length >= total) break;
  }
  return out;
}

/**
 * 组装部门 - 人员两级树：部门（/system/depts/tree 的原有层级，保留全部嵌套）
 * → 人员（按 deptId 挂到所属部门下）。
 *
 * <p>顶层就是部门，不额外套一层「组织」合成根 —— 多一层只会逼用户多点一次展开。
 *
 * <p>未归属部门、或 deptId 指向已删除部门的用户，统一收进「未分配部门」桶，
 * 避免这些人从选择器里凭空消失。
 */
async function fetchOrgTree(): Promise<OrgNode[]> {
  const depts = await fetchDeptOptions();
  const users = await fetchAllUsers();

  // 深拷贝成 OrgNode 形状：deptOptionsCache 里的 DeptItem[] 同时被 DeptField 使用，
  // 原地加字段会污染别的组件的数据源。
  const deptIndex = new Map<number, OrgNode>();
  const convert = (d: DeptItem): OrgNode => {
    const node: OrgNode = {
      value: `dept-${d.id}`,
      label: d.name,
      kind: 'dept',
      disabled: true,
      children: [],
    };
    deptIndex.set(d.id, node);
    node.children = (d.children || []).map(convert);
    return node;
  };
  const deptRoots = depts.map(convert);

  const unassigned: OrgNode[] = [];
  for (const u of users) {
    const userId = u?.id;
    if (userId === undefined || userId === null) continue;
    const name = u.displayName || u.username;
    const node: OrgNode = {
      value: `user-${userId}`,
      label: name,
      kind: 'user',
      disabled: false,
      userId: Number(userId),
      userName: name,
    };
    const parent = u.deptId != null ? deptIndex.get(Number(u.deptId)) : undefined;
    (parent ? parent.children! : unassigned).push(node);
  }

  const roots = [...deptRoots];
  if (unassigned.length) {
    roots.push({
      value: 'unassigned',
      label: 'Unassigned',
      kind: 'unassigned',
      disabled: true,
      children: unassigned,
    });
  }
  return roots;
}

/**
 * 部门 - 人员两级树的数据源 + 加载态。
 *
 * <p>「未分配部门」是本地合成的节点，文案不来自后端，所以在 computed 里按当前语言
 * 重新贴标签 —— 否则切语言后这个节点仍是旧文案（模块级缓存不随 locale 失效）。
 */
export function useOrgTree(): { tree: ComputedRef<OrgNode[]>; loading: Ref<boolean> } {
  const { t } = useI18n();
  onMounted(async () => {
    try {
      await orgTreeCache.ensure(fetchOrgTree);
    } catch {
      ElMessage.warning(t('orgTree.loadFailed'));
    }
  });
  const tree = computed<OrgNode[]>(() => {
    const relabel = (n: OrgNode): OrgNode => {
      const label = n.kind === 'unassigned' ? t('orgTree.unassigned') : n.label;
      return n.children ? { ...n, label, children: n.children.map(relabel) } : { ...n, label };
    };
    return orgTreeCache.data.value.map(relabel);
  });
  return { tree, loading: orgTreeCache.loading };
}
