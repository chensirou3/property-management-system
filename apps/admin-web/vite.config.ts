import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()], dts: 'src/auto-imports.d.ts' }),
    Components({ resolvers: [ElementPlusResolver()], dts: 'src/components.d.ts' }),
  ],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
    },
  },
  test: {
    include: ['src/**/*.spec.ts'],
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    server: {
      deps: { inline: ['element-plus'] },
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
    },
  },
})
