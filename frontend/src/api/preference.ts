import { http } from './http';

export interface UserPreference {
  userId: number;
  themeJson: string | null;
  updatedAt: string | null;
}

export const preferenceApi = {
  get: () => http.get<unknown, UserPreference>('/preference'),
  update: (themeJson: string) =>
    http.put<unknown, UserPreference>('/preference', { themeJson }),
  reset: () => http.delete<unknown, void>('/preference'),
};