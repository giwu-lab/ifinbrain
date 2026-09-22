import { defineStore } from 'pinia';
import type { MenuNode } from '@/types';

interface PermState {
  menus: MenuNode[];
  permissions: string[];
}

export const usePermissionStore = defineStore('permission', {
  state: (): PermState => ({
    menus: [],
    permissions: []
  }),
  actions: {
    setMenus(menus: MenuNode[]) {
      this.menus = menus;
    },
    setPermissions(perms: string[]) {
      this.permissions = perms;
    }
  }
});