/**
 * 插件市场目录（MicYou-Plugins 仓库 index.json）
 * 与主题市场（features/theme/catalog.ts）同构
 */

export interface MarketPlugin {
  id: string;
  name: string;
  nameI18n?: Record<string, string>;
  version: string;
  author?: string;
  description?: string;
  descriptionI18n?: Record<string, string>;
  runtime: string;
  kind: string;
  capabilities: string[];
  license?: string;
  homepage?: string;
  manifestUrl: string;
  downloadUrl: string;
  previewUrl?: string;
  pageUrl?: string;
  arches?: string[];
  platforms?: string[];
}

export interface PluginCatalog {
  plugins: MarketPlugin[];
  updatedAt?: string;
}

export const PLUGIN_MARKET_INDEX_URL =
  'https://micyou-dev.github.io/MicYou-Plugins/index.json';

export const emptyPluginCatalog: PluginCatalog = { plugins: [] };

/** 拉取市场目录（index.json） */
export async function loadPluginCatalog(): Promise<PluginCatalog> {
  // 加时间戳查询参数绕过 raw.githubusercontent 的 CDN 缓存，
  // 避免市场展示过期清单（旧 downloadUrl 指向已删除的 zip 导致 404）
  const response = await fetch(`${PLUGIN_MARKET_INDEX_URL}?t=${Date.now()}`, {
    cache: 'no-store',
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${PLUGIN_MARKET_INDEX_URL}`);
  }
  return (await response.json()) as PluginCatalog;
}

/** 按当前 locale 取本地化名称（与 PluginsPanel 的 displayName 一致） */
export function marketPluginName(p: MarketPlugin, locale: string): string {
  if (!p.nameI18n) return p.name;
  const l = locale.toLowerCase();
  const direct = p.nameI18n[l] || p.nameI18n[locale];
  if (direct) return direct;
  if (l.startsWith('zh')) {
    return p.nameI18n['zh-cn'] || p.nameI18n['zh'] || p.name;
  }
  return p.name;
}
