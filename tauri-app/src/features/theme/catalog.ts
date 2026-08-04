import type { ThemeCatalogIndex, ThemeCatalogProvider, ThemeManifest } from './types';

/**
 * Public catalog repository. Only the catalog index and preview images are
 * read by the browser; theme packages are not downloaded or executed here.
 */
export const THEME_CATALOG_INDEX_FILE = 'index.json';
export const THEME_CATALOG_REPOSITORY_URL = 'https://github.com/MicYou-Dev/MicYou-Themes';
export const THEME_CATALOG_BASE_URL = 'https://raw.githubusercontent.com/MicYou-Dev/MicYou-Themes/main';
export const THEME_CATALOG_INDEX_URL = `${THEME_CATALOG_BASE_URL}/${THEME_CATALOG_INDEX_FILE}`;

export const emptyThemeCatalog: ThemeCatalogIndex = {
  version: 1,
  themes: [],
};

export const unavailableThemeCatalogProvider: ThemeCatalogProvider = {
  async load() {
    return emptyThemeCatalog;
  },
};

export function isThemeManifest(value: unknown): value is ThemeManifest {
  if (!value || typeof value !== 'object') return false;
  const manifest = value as Partial<ThemeManifest>;
  return typeof manifest.id === 'string'
    && typeof manifest.name === 'string'
    && typeof manifest.version === 'string'
    && typeof manifest.author === 'string'
    && typeof manifest.description === 'string';
}

async function fetchThemeAsset(url: string, label: string): Promise<Response> {
  const response = await fetch(url, { cache: 'no-store' });
  if (!response.ok) throw new Error(`${label}: HTTP ${response.status}`);
  return response;
}

export async function downloadThemePackage(theme: ThemeManifest): Promise<{ manifest: ThemeManifest; css: string }> {
  const manifestUrl = resolveThemeAssetUrl(theme, 'manifest.json');
  if (!manifestUrl) throw new Error('Theme manifest URL is missing');

  const manifestResponse = await fetchThemeAsset(manifestUrl, 'manifest');
  const downloadedManifest: unknown = await manifestResponse.json();
  if (!isThemeManifest(downloadedManifest)) throw new Error('Invalid theme manifest');
  if (downloadedManifest.id !== theme.id) throw new Error('Theme manifest id does not match catalog');

  const manifest: ThemeManifest = {
    ...theme,
    ...downloadedManifest,
    resourceUrl: downloadedManifest.resourceUrl || theme.resourceUrl,
  };
  const entry = manifest.entry || 'theme.css';
  if (!entry.toLowerCase().endsWith('.css')) throw new Error('Theme entry must be a CSS file');

  const cssUrl = resolveThemeAssetUrl(manifest, entry);
  if (!cssUrl) throw new Error('Theme CSS URL is missing');
  const cssResponse = await fetchThemeAsset(cssUrl, 'theme css');
  const css = await cssResponse.text();
  if (!css.trim()) throw new Error('Theme CSS is empty');
  return { manifest, css };
}

export function resolveThemeAssetUrl(theme: ThemeManifest, asset?: string): string | undefined {
  if (!asset) return undefined;
  if (/^https?:\/\//i.test(asset)) return asset;

  const resourceUrl = theme.resourceUrl?.trim() || `theme/${theme.id}`;
  const resourceBase = /^https?:\/\//i.test(resourceUrl)
    ? resourceUrl
    : `${THEME_CATALOG_BASE_URL}/${resourceUrl.replace(/^\/+|\/+$/g, '')}`;

  return new URL(asset.replace(/^\/+/, ''), `${resourceBase.replace(/\/+$/, '')}/`).toString();
}

export function themeRepositoryUrl(theme: ThemeManifest): string {
  return `${THEME_CATALOG_REPOSITORY_URL}/tree/main/theme/${encodeURIComponent(theme.id)}`;
}

export const githubThemeCatalogProvider: ThemeCatalogProvider = {
  async load() {
    const response = await fetch(THEME_CATALOG_INDEX_URL, { cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    const payload: unknown = await response.json();
    if (!payload || typeof payload !== 'object') throw new Error('Invalid theme catalog');

    const catalog = payload as Partial<ThemeCatalogIndex>;
    const themes = Array.isArray(catalog.themes) ? catalog.themes.filter(isThemeManifest) : [];
    return {
      version: typeof catalog.version === 'number' ? catalog.version : 1,
      themes,
    };
  },
};
