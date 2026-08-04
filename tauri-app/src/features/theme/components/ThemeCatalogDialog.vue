<template>
  <Transition name="dialog">
    <div
      v-if="isOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
      @click.self="close"
    >
      <div class="surface-dialog flex max-h-[85vh] w-full max-w-4xl flex-col">
        <header class="flex shrink-0 items-center justify-between border-b border-border/20 p-6">
          <div>
            <h2 class="text-xl font-bold text-primary">{{ $t('dialogs.themeCatalog.title') }}</h2>
            <p class="mt-1 text-xs text-on-surface-variant">
              {{ $t('dialogs.themeCatalog.count', { count: themes.length }) }}
            </p>
          </div>
          <button
            class="rounded-full p-2 text-on-surface-variant transition-colors hover:bg-surface-variant/50 hover:text-on-surface"
            :aria-label="$t('dialogs.close')"
            @click="close"
          >
            <X class="h-5 w-5" />
          </button>
        </header>

        <div class="settings-scrollbar min-h-0 flex-1 overflow-y-auto p-6">
          <div v-if="isLoading" class="flex flex-col items-center justify-center gap-4 py-16">
            <Loader2 class="h-8 w-8 animate-spin text-primary" />
            <p class="text-sm text-on-surface-variant">{{ $t('dialogs.themeCatalog.loading') }}</p>
          </div>

          <div v-else-if="loadError" class="flex flex-col items-center justify-center gap-4 py-16 text-center">
            <span class="text-4xl">⚠️</span>
            <p class="max-w-md text-sm text-error">
              {{ $t('dialogs.themeCatalog.loadFailed', { error: loadError }) }}
            </p>
            <button
              class="rounded-full bg-primary px-4 py-2 text-sm font-medium text-on-primary transition-opacity hover:opacity-90"
              @click="loadCatalog"
            >
              {{ $t('dialogs.themeCatalog.retry') }}
            </button>
          </div>

          <div v-else-if="themes.length === 0" class="flex flex-col items-center justify-center gap-3 py-16 text-center">
            <Palette class="h-10 w-10 text-on-surface-variant" />
            <p class="text-sm text-on-surface-variant">{{ $t('dialogs.themeCatalog.empty') }}</p>
          </div>

          <div v-else class="grid grid-cols-1 gap-5 md:grid-cols-2">
            <article
              v-for="theme in themes"
              :key="theme.id"
              class="theme-card overflow-hidden rounded-2xl border border-border/20 bg-surface-container/70 transition-colors hover:border-primary/40"
            >
              <div class="relative aspect-[16/9] overflow-hidden bg-surface-variant/40">
                <img
                  v-if="theme.preview && !failedPreviews.includes(theme.id)"
                  :src="resolveThemeAssetUrl(theme, theme.preview)"
                  :alt="theme.name"
                  class="h-full w-full object-cover"
                  loading="lazy"
                  @error="markPreviewFailed(theme.id)"
                />
                <div v-else class="flex h-full items-center justify-center bg-primary/10">
                  <Palette class="h-12 w-12 text-primary/70" />
                </div>
                <span class="absolute right-3 top-3 rounded-full bg-surface-bright/85 px-2.5 py-1 text-xs font-medium text-on-surface backdrop-blur-sm">
                  v{{ theme.version }}
                </span>
              </div>

              <div class="space-y-3 p-4">
                <div class="flex items-start justify-between gap-3">
                  <div class="min-w-0">
                    <h3 class="truncate font-bold text-on-surface">{{ theme.name }}</h3>
                    <p class="mt-1 text-xs text-on-surface-variant">
                      {{ $t('dialogs.themeCatalog.author', { author: theme.author }) }}
                    </p>
                  </div>
                  <a
                    :href="themeRepositoryUrl(theme)"
                    target="_blank"
                    rel="noreferrer"
                    class="shrink-0 rounded-full bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/20"
                  >
                    {{ $t('dialogs.themeCatalog.repository') }}
                  </a>
                </div>
                <p class="line-clamp-3 min-h-[3.75rem] text-sm leading-relaxed text-on-surface-variant">
                  {{ theme.description }}
                </p>
                <p v-if="installError" class="text-xs text-error">
                  {{ $t('dialogs.themeCatalog.downloadFailed', { error: installError }) }}
                </p>
                <button
                  class="flex w-full items-center justify-center gap-2 rounded-full px-4 py-2 text-sm font-medium transition-opacity hover:opacity-90 disabled:pointer-events-none disabled:opacity-60"
                  :class="isInstalled(theme.id) ? 'bg-error/10 text-error hover:bg-error/20' : 'bg-primary text-on-primary'"
                  :disabled="installingThemeId === theme.id || uninstallingThemeId === theme.id"
                  @click="isInstalled(theme.id) ? uninstallTheme(theme) : installTheme(theme)"
                >
                  <Loader2 v-if="installingThemeId === theme.id" class="h-4 w-4 animate-spin" />
                  <Loader2 v-else-if="uninstallingThemeId === theme.id" class="h-4 w-4 animate-spin" />
                  <span v-if="installingThemeId === theme.id">{{ $t('dialogs.themeCatalog.installing') }}</span>
                  <span v-else-if="uninstallingThemeId === theme.id">{{ $t('dialogs.themeCatalog.uninstalling') }}</span>
                  <span v-else-if="isInstalled(theme.id)">{{ $t('dialogs.themeCatalog.uninstall') }}</span>
                  <span v-else>{{ $t('dialogs.themeCatalog.install') }}</span>
                </button>
              </div>
            </article>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { Loader2, Palette, X } from '@lucide/vue';
import { invoke } from '@tauri-apps/api/core';
import {
  downloadThemePackage,
  githubThemeCatalogProvider,
  resolveThemeAssetUrl,
  themeRepositoryUrl,
} from '../catalog';
import { activateInstalledTheme, clearInstalledTheme } from '../composables/useTheme';
import type { ThemeManifest } from '../types';

const props = defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{ close: [] }>();

const themes = ref<ThemeManifest[]>([]);
const isLoading = ref(false);
const loadError = ref<string | null>(null);
const installError = ref<string | null>(null);
const failedPreviews = ref<string[]>([]);
const installedThemeIds = ref<string[]>([]);
const installingThemeId = ref<string | null>(null);
const uninstallingThemeId = ref<string | null>(null);

const loadCatalog = async () => {
  isLoading.value = true;
  loadError.value = null;
  try {
    const catalog = await githubThemeCatalogProvider.load();
    themes.value = catalog.themes;
    failedPreviews.value = [];
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    isLoading.value = false;
  }
};

const loadInstalledThemes = async () => {
  try {
    installedThemeIds.value = await invoke<string[]>('list_installed_themes');
  } catch (cause) {
    console.warn('Failed to load installed themes:', cause);
  }
};

const isInstalled = (themeId: string) => installedThemeIds.value.includes(themeId);

const installTheme = async (theme: ThemeManifest) => {
  if (installingThemeId.value || isInstalled(theme.id)) return;
  installingThemeId.value = theme.id;
  installError.value = null;
  try {
    const themePackage = await downloadThemePackage(theme);
    await invoke('install_theme', {
      themeId: theme.id,
      manifestJson: JSON.stringify(themePackage.manifest),
      css: themePackage.css,
    });
    activateInstalledTheme(
      theme.id,
      themePackage.css,
      themePackage.manifest.controlsThemeColor !== false,
    );
    installedThemeIds.value = [...installedThemeIds.value, theme.id];
  } catch (cause) {
    installError.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    installingThemeId.value = null;
  }
};

const uninstallTheme = async (theme: ThemeManifest) => {
  if (uninstallingThemeId.value || !isInstalled(theme.id)) return;
  uninstallingThemeId.value = theme.id;
  installError.value = null;
  try {
    await invoke('remove_installed_theme', { themeId: theme.id });
    installedThemeIds.value = installedThemeIds.value.filter((id) => id !== theme.id);
    if (localStorage.getItem('micyou_theme_v2_installed_id') === theme.id) clearInstalledTheme();
  } catch (cause) {
    installError.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    uninstallingThemeId.value = null;
  }
};

const markPreviewFailed = (themeId: string) => {
  if (!failedPreviews.value.includes(themeId)) {
    failedPreviews.value = [...failedPreviews.value, themeId];
  }
};

const close = () => emit('close');

watch(() => props.isOpen, (isOpen) => {
  if (isOpen) {
    if (themes.value.length === 0 && !isLoading.value) void loadCatalog();
    void loadInstalledThemes();
  }
});
</script>
