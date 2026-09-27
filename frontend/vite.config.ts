import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { fileViewerRenderers } from '@file-viewer/vite-plugin';
import path from 'node:path';

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
    fileViewerRenderers({
      preset: 'office',
      // 扩展 office 之外的格式：图片 / 代码 / 文本 / Markdown / patch / Git bundle
      // renderer-image (jpg/png/gif/svg/tiff/heic/...) + renderer-text (md/json/xml/yaml/sql/py/...)
      // patch + Git bundle 由 capability-text-tools 在 runtime 注册
      formats: ['image', 'text'],
      copyAssets: true,
      chunkStrategy: 'renderer',
    }),
  ],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500
  }
});