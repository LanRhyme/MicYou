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
  readmeUrl?: string;
  arches?: string[];
  platforms?: string[];
}

export interface PluginCatalog {
  plugins: MarketPlugin[];
  updatedAt?: string;
}

export const PLUGIN_MARKET_INDEX_URL =
  'https://micyou-dev.github.io/MicYou-Plugins/index.json';
export const PLUGIN_CONTRIBUTING_URL =
  'https://github.com/MicYou-Dev/MicYou-Plugins/blob/main/CONTRIBUTING.md';

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

/** locale 匹配（对大小写与区域后缀双向宽容）：
 *  1. 精确（原样 / 小写）；2. 语言前缀双向（宿主 locale `zh` 命中 `zh-CN`，
 *  插件键 `zh` 命中宿主 `zh-CN`）；均不命中回退调用方 fallback。
 *  宿主 locale 取值见 main.ts：zh / zh-hk / zh-tw / zh-ss / en / cat / lzh。 */
function pickI18n(map: Record<string, string> | undefined, locale: string): string | undefined {
  if (!map) return undefined;
  const l = locale.toLowerCase();
  const base = l.split('-')[0];
  const entries = Object.entries(map);
  const direct =
    map[locale] ?? entries.find(([k]) => k.toLowerCase() === l)?.[1];
  if (direct) return direct;
  const prefixed =
    map[base] ?? entries.find(([k]) => k.toLowerCase().split('-')[0] === base)?.[1];
  return prefixed;
}

/** 按当前 locale 取本地化名称（与 PluginsPanel 的 displayName 一致） */
export function marketPluginName(p: MarketPlugin, locale: string): string {
  return pickI18n(p.nameI18n, locale) ?? p.name;
}

/** 按当前 locale 取本地化描述；无本地化字段（旧插件）回退基础描述 */
export function marketPluginDescription(p: MarketPlugin, locale: string): string {
  return pickI18n(p.descriptionI18n, locale) ?? p.description ?? '';
}
