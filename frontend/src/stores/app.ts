import { defineStore } from 'pinia';
import type { MenuNode } from '@/types';

interface AppState {
  collapsed: boolean;
  dynamicAdded: boolean;
}

export const useAppStore = defineStore('app', {
  state: (): AppState => ({
    collapsed: false,
    dynamicAdded: false
  }),
  actions: {
    toggle() {
      this.collapsed = !this.collapsed;
    }
  }
});

function walkMenus(nodes: MenuNode[], parentPath = ''): MenuNode[] {
  return nodes.map((n) => {
    const fullPath = n.path?.startsWith('/') ? n.path : `${parentPath}/${n.path || ''}`;
    return { ...n, path: fullPath, children: n.children ? walkMenus(n.children, fullPath) : [] };
  });
}