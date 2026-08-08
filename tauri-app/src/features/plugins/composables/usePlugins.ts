import { ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { open } from '@tauri-apps/plugin-dialog';

export interface PluginDependency {
  id: string;
  version?: string;
  optional?: boolean;
}

export interface PluginUpdate {
  id: string;
  currentVersion: string;
  latestVersion: string;
  updateUrl: string;
}

export interface PluginPreview {
  id: string;
  name: string;
  version: string;
  author?: string | null;
  description?: string | null;
  runtime: string;
  kind: string;
  capabilities: string[];
  license?: string | null;
  homepage?: string | null;
}

export interface PluginView {
  id: string;
  name: string;
  version: string;
  author?: string | null;
  description?: string | null;
  runtime: string; // native | wasm
  kind: string; // dsp | utility | ui | bridge
  platforms: string[];
  capabilities: string[];
  ui?: {
    route: string;
    label?: string;
    entry?: string | null;
    panels?: Array<{ id: string; label: string; entry: string; sidebar?: boolean }>;
  } | null;
  enabled: boolean;
  loaded: boolean;
  dspNode: boolean;
  error?: string | null;
  nameI18n?: Record<string, string>;
  descriptionI18n?: Record<string, string>;
  dependencies?: PluginDependency[];
  configSchema?: {
    fields: Array<{
      key: string;
      fieldType: string;
      label?: string | null;
      description?: string | null;
      default?: unknown;
      min?: number;
      max?: number;
      step?: number;
      options?: Array<{ value: string; label?: string | null }>;
    }>;
  };
}

export interface PluginSyncStatus {
  deviceConnected: boolean;
  transportReady: boolean;
}

// 模块级单例：设置对话框与（曾经的）独立对话框共享同一份状态
const plugins = ref<PluginView[]>([]);
const syncStatus = ref<PluginSyncStatus>({ deviceConnected: false, transportReady: false });
const loading = ref(false);
const busyId = ref<string | null>(null);
const error = ref<string | null>(null);

export function usePlugins() {
  async function refresh() {
    loading.value = true;
    error.value = null;
    try {
      plugins.value = await invoke<PluginView[]>('list_plugins');
      syncStatus.value = await invoke<PluginSyncStatus>('get_plugin_sync_status');
    } catch (e) {
      error.value = String(e);
    } finally {
      loading.value = false;
    }
  }

  async function toggle(plugin: PluginView) {
    busyId.value = plugin.id;
    error.value = null;
    try {
      await invoke('set_plugin_enabled', { id: plugin.id, enabled: !plugin.enabled });
      await refresh();
    } catch (e) {
      error.value = String(e);
      await refresh();
    } finally {
      busyId.value = null;
    }
  }

  async function uninstall(plugin: PluginView) {
    busyId.value = plugin.id;
    error.value = null;
    try {
      await invoke('uninstall_plugin', { id: plugin.id });
      await refresh();
    } catch (e) {
      error.value = String(e);
    } finally {
      busyId.value = null;
    }
  }

  async function saveConfig(plugin: PluginView | string, key: string, value: unknown) {
    try {
      const pluginId = typeof plugin === 'string' ? plugin : plugin.id;
      await invoke('set_plugin_config', { id: pluginId, key, value });
      return true;
    } catch (e) {
      error.value = String(e);
      return false;
    }
  }

  async function getConfig(plugin: PluginView | string): Promise<Record<string, unknown>> {
    try {
      const pluginId = typeof plugin === 'string' ? plugin : plugin.id;
      const v = await invoke<Record<string, unknown>>('get_plugin_config', { id: pluginId });
      return v ?? {};
    } catch {
      return {};
    }
  }

  async function logs(plugin: PluginView): Promise<string[]> {
    try {
      return await invoke<string[]>('get_plugin_logs', { id: plugin.id });
    } catch {
      return [];
    }
  }

  /** 触发插件 UI 动作（soundpad 按钮等）：topic ui:<action>，payload 为 JSON 字符串 */
  async function trigger(plugin: PluginView, action: string, payload?: string) {
    error.value = null;
    try {
      await invoke('plugin_trigger', { pluginId: plugin.id, action, payload: payload ?? null });
      return true;
    } catch (e) {
      error.value = String(e);
      return false;
    }
  }

  /** 打开系统文件管理器显示插件目录（目录由后端 open_plugins_dir 命令直接打开） */
  async function openDir(): Promise<boolean> {
    try {
      await invoke('open_plugins_dir');
      return true;
    } catch (e) {
      error.value = String(e);
      return false;
    }
  }

  /** 选择并导入插件压缩包（.zip） */
  // Peek a plugin zip's manifest so the UI can show a permission prompt
  // before the plugin is actually installed
  async function previewPlugin(path: string): Promise<PluginPreview> {
    return await invoke('preview_plugin_zip', { zipPath: path });
  }

  async function checkUpdates(): Promise<PluginUpdate[]> {
    try {
      return await invoke<PluginUpdate[]>('check_plugin_updates');
    } catch {
      return [];
    }
  }

  async function updatePlugin(id: string): Promise<boolean> {
    try {
      busyId.value = id + ':update';
      error.value = null;
      await invoke('update_plugin', { id });
      await refresh();
      return true;
    } catch (e) {
      error.value = String(e);
      return false;
    } finally {
      busyId.value = null;
    }
  }

  async function importPlugin(): Promise<boolean> {
    try {
      const picked = await open({
        multiple: false,
        directory: false,
        filters: [{ name: 'MicYou plugin', extensions: ['zip'] }],
      });
      if (!picked) return false; // 用户取消
      const path = String(picked);
      // Step 1: show the permission prompt (capabilities, author, license)
      const preview: PluginPreview = await previewPlugin(path);
      const confirmed = window.confirm(
        `安装插件？\n\n名称: ${preview.name} (${preview.id})\n版本: ${preview.version}${preview.author ? `\n作者: ${preview.author}` : ''}${preview.license ? `\n许可: ${preview.license}` : ''}\n类型: ${preview.runtime}\n\n请求的能力:\n${preview.capabilities.length ? preview.capabilities.map((c: string) => `  · ${c}`).join('\n') : '  (无)'}\n\n⚠ 请确认来源可信后安装（插件可获得所声明的能力）`,
      );
      if (!confirmed) return false;
      busyId.value = 'import';
      error.value = null;
      await invoke('import_plugin', { source: path });
      await refresh();
      return true;
    } catch (e) {
      error.value = String(e);
      return false;
    } finally {
      busyId.value = null;
    }
  }

  return {
    plugins,
    syncStatus,
    loading,
    busyId,
    error,
    refresh,
    toggle,
    uninstall,
    saveConfig,
    getConfig,
    logs,
    trigger,
    openDir,
    previewPlugin,
    importPlugin,
    checkUpdates,
    updatePlugin,
  };
}
