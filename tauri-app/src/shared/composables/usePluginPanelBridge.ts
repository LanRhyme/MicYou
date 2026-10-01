import { command } from '@/platform';

/**
 * 插件面板桥：沙箱 iframe 里的插件 HTML 通过 postMessage 与宿主通信
 *
 * 面板侧协议（面板 HTML 内联脚本）：
 * ```js
 * function call(api, args) {
 *   return new Promise((resolve, reject) => {
 *     const id = Math.random().toString(36).slice(2);
 *     const onMsg = (e) => {
 *       if (e.data && e.data.__micyou === 1 && e.data.id === id) {
 *         window.removeEventListener('message', onMsg);
 *         e.data.ok ? resolve(e.data.value) : reject(new Error(e.data.error));
 *       }
 *     };
 *     window.addEventListener('message', onMsg);
 *     window.parent.postMessage({ __micyou: 1, id, api, args }, '*');
 *   });
 * }
 * ```
 * 可用 api：get_config / set_config / trigger / play / open_window / log / get_logs / get_sync_status / locale
 */
export function usePluginPanelBridge(pluginId: string) {
  async function routeApi(api: string, args: Record<string, unknown>): Promise<unknown> {
    switch (api) {
      case 'get_config':
        return command('get_plugin_config', { id: pluginId });
      case 'set_config':
        return command('set_plugin_config', {
          id: pluginId,
          key: (args.key ?? '') as string,
          value: args.value,
        });
      case 'trigger':
        return command('plugin_trigger', {
          pluginId,
          action: (args.action ?? '') as string,
          payload: (args.payload ?? null) as string | null,
        });
      case 'open_window':
        return command('plugin_trigger', {
          pluginId,
          action: 'open_window',
          payload: JSON.stringify(args),
        });
      case 'play':
        return command('plugin_trigger', {
          pluginId,
          action: 'play',
          payload: JSON.stringify(args),
        });
      case 'log':
        return command('plugin_trigger', {
          pluginId,
          action: 'log',
          payload: JSON.stringify({
            level: args.level ?? 'info',
            message: args.message ?? '',
          }),
        });
      case 'get_logs':
        return command('get_plugin_logs', { id: pluginId });
      case 'get_sync_status':
        return command('get_plugin_sync_status');
      case 'locale':
        return command('get_app_locale');
      default:
        throw new Error(`unknown panel api: ${api}`);
    }
  }

  /** 宿主侧 message 监听器（settings 对话框 onMounted 注册） */
  function handleMessage(e: MessageEvent) {
    const d = e.data as Record<string, unknown> | null | undefined;
    if (!d || d.__micyou !== 1 || typeof d.id !== 'string' || typeof d.api !== 'string') {
      return;
    }
    const { id, api, args } = d;
    routeApi(api, (args as Record<string, unknown>) ?? {})
      .then((value) => {
        e.source?.postMessage({ __micyou: 1, id, ok: true, value }, { targetOrigin: '*' });
      })
      .catch((err) => {
        e.source?.postMessage(
          { __micyou: 1, id, ok: false, error: String(err) },
          { targetOrigin: '*' },
        );
      });
  }

  return { handleMessage };
}
