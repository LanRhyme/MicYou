<script setup lang="ts">
/**
 * 插件面板独立窗口
 * 由 open_plugin_window 命令创建，hash 路由 #/plugin/:pluginId/:panelId
 * 复用设置对话框的面板渲染逻辑（沙箱 iframe + postMessage 桥 + 主题注入）
 */
import { ref, onMounted } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { usePluginPanelBridge } from '@/shared/composables/usePluginPanelBridge';

const hash = window.location.hash; // #/plugin/<pluginId>/<panelId>
const parts = hash.replace('#/plugin/', '').split('/');
const pluginId = decodeURIComponent(parts[0] ?? '');
const panelId = decodeURIComponent(parts[1] ?? '');

const panelHtml = ref('');
const loading = ref(true);
const error = ref<string | null>(null);
const { handleMessage } = usePluginPanelBridge(pluginId);

function collectThemeVars(): string {
  const style = getComputedStyle(document.documentElement);
  const vars: string[] = [];
  for (let i = 0; i < style.length; i++) {
    const name = style[i];
    if (name.startsWith('--')) {
      vars.push(`${name}: ${style.getPropertyValue(name)};`);
    }
  }
  return vars.join('\n');
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const html = await invoke<string>('get_plugin_panel', { pluginId, panelId });
    panelHtml.value = `<style>:root{${collectThemeVars()}}</style>${html}`;
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
}

function closeWindow() {
  window.close();
}

onMounted(() => {
  window.addEventListener('message', handleMessage);
  load();
});
</script>

<template>
  <div
    class="h-screen w-screen overflow-hidden flex flex-col"
    style="background: hsl(var(--surface))"
  >
    <header
      class="flex items-center justify-between px-4 py-2.5 shrink-0 border-b"
      style="border-color: hsl(var(--border))"
    >
      <div
        class="flex items-center gap-2 text-sm font-semibold"
        style="color: hsl(var(--on-surface))"
      >
        <span
          class="inline-block w-2 h-2 rounded-full"
          style="background: hsl(var(--primary))"
        ></span>
        {{ pluginId }} · {{ panelId }}
      </div>
      <button
        @click="closeWindow"
        class="text-xs px-2.5 py-1 rounded-lg transition-colors hover:opacity-80"
        style="background: hsl(var(--surface-variant)); color: hsl(var(--on-surface-variant))"
      >
        ✕
      </button>
    </header>
    <div class="flex-1 overflow-hidden p-4">
      <div v-if="loading" class="text-sm" style="color: hsl(var(--on-surface-variant))">
        加载中…
      </div>
      <div
        v-else-if="error"
        class="text-xs font-mono break-all rounded-xl p-4"
        style="
          color: hsl(var(--error));
          background: color-mix(in srgb, hsl(var(--error)) 10%, transparent);
        "
      >
        {{ error }}
      </div>
      <iframe
        v-else
        :srcdoc="panelHtml"
        sandbox="allow-scripts allow-popups"
        class="w-full h-full rounded-xl border"
        style="border-color: hsl(var(--border)); background: hsl(var(--surface))"
      ></iframe>
    </div>
  </div>
</template>
