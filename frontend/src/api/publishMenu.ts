/**
 * 「发布到菜单目录」的共享类型 —— 报表与大屏共用。
 *
 * <p>对应后端 `com.anomalyco.ifinbrain.web.common.PublishMenuReq` / `MenuPublisher#dirs`。
 * 两个模块的发布语义完全一致（选目录 → 生成侧边栏入口 → 按查看类权限码控角色可见性），
 * 类型也只留一份，免得改了一边忘了另一边。
 */

/** 发布对话框里的父目录候选（sys_menu.type='DIR' 且 status=1） */
export interface PublishDir {
  id: number;
  name: string;
  path?: string;
}

export interface PublishMenuBody {
  /** 挂到哪个目录下；不传 = 保留上次的（首次则 /bi） */
  parentId?: number;
  /** 侧边栏显示名；不传 = 保留上次的（首次则业务对象名） */
  menuName?: string;
  /** Element Plus 图标组件名；不传 = 保留上次的（首次则 'Tickets'） */
  icon?: string;
  /** 排序；不传 = 保留上次的（首次则 99） */
  sort?: number;
}