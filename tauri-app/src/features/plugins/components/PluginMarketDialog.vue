<template>
  <Transition name="dialog" appear>
  <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4">
    <Transition name="mask" appear>
      <div class="absolute inset-0 bg-black/50" @click="emit('close')" />
    </Transition>
    <div
      class="relative w-full max-w-2xl max-h-[80vh] flex flex-col rounded-2xl border border-surface-variant/40 bg-surface shadow-2xl"
    >
      <!-- Header -->
      <div class="flex items-center justify-between px-5 py-4 border-b border-surface-variant/30">
        <div class="flex items-center gap-2.5">
          <Store class="w-5 h-5 text-primary" />
          <h2 class="text-base font-semibold">{{ $t('plugins.marketTitle') }}</h2>
          <span class="text-xs text-on-surface-variant">MicYou-Plugins</span>
        </div>
        <div class="flex items-center gap-1">
          <button
            class="w-8 h-8 rounded-full hover:bg-surface-variant/40 flex items-center justify-center"
            :title="$t('plugins.refresh')"
            @click="load(); void refreshInstalled()"
          >
            <Loader2 v-if="isLoading" class="w-4 h-4 animate-spin text-on-surface-variant" />
            <RefreshCw v-else class="w-4 h-4 text-on-surface-variant" />
          </button>
          <button
            class="w-8 h-8 rounded-full hover:bg-surface-variant/40 flex items-center justify-center"
            @click="emit('close')"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- Body -->
      <div class="flex-1 overflow-y-auto p-4 space-y-3">
        <div v-if="!isLoading && !loadError && catalog.plugins.length" class="space-y-3">
          <div class="relative">
            <Search class="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-on-surface-variant/60" />
            <input
              v-model="marketQuery"
              type="text"
              :placeholder="$t('plugins.marketSearch')"
              class="w-full h-10 pl-9 pr-3 rounded-full bg-surface-variant/20 text-sm text-on-surface outline-none placeholder:text-on-surface-variant/60 focus:ring-1 focus:ring-primary/40"
            />
          </div>
          <!-- Kind filter tabs -->
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="k in ['all', 'dsp', 'utility', 'ui']"
              :key="k"
              class="px-3 py-1 rounded-full text-xs font-medium transition-colors duration-150 active:scale-95"
              :class="
                kindFilter === k
                  ? 'bg-primary/20 text-primary'
                  : 'bg-surface-variant/30 text-on-surface-variant/80 hover:bg-surface-variant/50'
              "
              @click="kindFilter = k"
            >
              {{ k === 'all' ? $t('plugins.marketAll') : $t('plugins.kind.' + k) }}
            </button>
          </div>
        </div>
        <p v-if="loadError" class="text-sm text-error px-2 py-2">
          {{ $t('plugins.marketFailed', { error: loadError }) }}
          <button class="underline ml-2" @click="load">{{ $t('plugins.retry') }}</button>
        </p>

        <div v-else-if="isLoading" class="py-16 text-center text-sm text-on-surface-variant">
          <Loader2 class="w-5 h-5 animate-spin mx-auto mb-2" />
          {{ $t('plugins.marketLoading') }}
        </div>

        <div
          v-else-if="catalog.plugins.length === 0"
          class="py-16 text-center text-sm text-on-surface-variant"
        >
          {{ $t('plugins.marketEmpty') }}
        </div>
        <div
          v-else-if="filteredCatalog.length === 0"
          class="py-16 text-center text-sm text-on-surface-variant"
        >
          {{ $t('plugins.noPlugins') }}
        </div>

        <!-- Plugin cards -->
        <div
          v-for="plugin in filteredCatalog"
          :key="plugin.id"
          class="rounded-xl border border-surface-variant/30 bg-surface-bright p-4 transition-all duration-200 hover:border-primary/30 hover:shadow-lg hover:shadow-black/20"
        >
          <div class="flex gap-3">
            <img
              v-if="plugin.previewUrl"
              :src="plugin.previewUrl"
              alt=""
              class="w-24 h-16 rounded-lg object-cover shrink-0 bg-surface-variant/30"
              @error="onPreviewError"
            />
            <div class="min-w-0 flex-1">
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-semibold text-sm">{{
                      marketPluginName(plugin, locale)
                    }}</span>
                    <span
                      class="text-xs px-2 py-0.5 rounded-full bg-primary/15 text-primary font-medium"
                    >
                      {{ plugin.version }}
                    </span>
                    <span
                      class="text-xs px-2 py-0.5 rounded-full"
                      :class="
                        plugin.runtime === 'wasm'
                          ? 'bg-emerald-500/15 text-emerald-400'
                          : 'bg-amber-500/15 text-amber-400'
                      "
                    >
                      {{ plugin.runtime === 'wasm' ? 'WASM' : 'Native' }}
                    </span>
                    <span
                      class="text-xs px-2 py-0.5 rounded-full"
                      :class="
                        plugin.kind === 'dsp'
                          ? 'bg-sky-500/15 text-sky-400'
                          : 'bg-surface-variant/50 text-on-surface-variant'
                      "
                    >
                      {{ $t('plugins.kind.' + plugin.kind) }}
                    </span>
                  </div>
                  <p class="text-xs text-on-surface-variant mt-1 truncate">{{ plugin.id }}</p>
                  <p class="text-sm text-on-surface-variant mt-1 line-clamp-2">
                    {{ plugin.description || '—' }}
                  </p>
                  <div class="flex flex-wrap gap-1.5 mt-2">
                    <span
                      v-for="cap in plugin.capabilities"
                      :key="cap"
                      class="text-[11px] px-2 py-0.5 rounded-full bg-surface-variant/40 text-on-surface-variant"
                    >
                      {{ cap }}
                    </span>
                    <span
                      v-for="p in plugin.platforms || []"
                      :key="'p' + p"
                      class="text-[11px] px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-400"
                    >
                      {{ p }}
                    </span>
                    <span
                      v-for="a in plugin.arches || []"
                      :key="'a' + a"
                      class="text-[11px] px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-400"
                    >
                      {{ a }}
                    </span>
                  </div>
                  <p
                    v-if="plugin.author || plugin.license"
                    class="text-[11px] text-on-surface-variant mt-2"
                  >
                    {{ plugin.author ? plugin.author : '' }}
                    <span v-if="plugin.author && plugin.license" class="mx-1">·</span>
                    <span v-if="plugin.license">{{ plugin.license }}</span>
                  </p>
                </div>
                <button
                  class="shrink-0 inline-flex items-center gap-1.5 px-4 py-2 rounded-full text-xs font-medium transition-colors disabled:opacity-50"
                  :class="
                    installedIds.includes(plugin.id)
                      ? 'bg-surface-variant/40 text-on-surface-variant cursor-default'
                      : 'bg-primary text-on-primary hover:bg-primary/90'
                  "
                  :disabled="installedIds.includes(plugin.id) || installingId === plugin.id"
                  @click="install(plugin)"
                >
                  <Loader2 v-if="installingId === plugin.id" class="w-3.5 h-3.5 animate-spin" />
                  <Check v-else-if="installedIds.includes(plugin.id)" class="w-3.5 h-3.5" />
                  <span>
                    {{
                      installingId === plugin.id
                        ? $t('plugins.marketInstalling')
                        : installedIds.includes(plugin.id)
                          ? $t('plugins.marketInstalled')
                          : $t('plugins.marketInstall')
                    }}
                  </span>
                </button>
              </div>

              <!-- inline permission confirm -->
              <div
                v-if="confirmingId === plugin.id && preview"
                class="mt-3 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3"
              >
                <p class="text-xs font-medium text-amber-300">{{ $t('plugins.marketConfirm') }}</p>
                <div class="flex flex-wrap gap-1.5 mt-2">
                  <span
                    v-for="cap in preview.capabilities"
                    :key="cap"
                    class="text-[11px] px-2 py-0.5 rounded-full bg-amber-500/20 text-amber-300"
                  >
                    {{ cap }}
                  </span>
                </div>
                <p class="text-[11px] text-amber-200/80 mt-2">
                  {{ $t('plugins.marketConfirmText') }}
                </p>
                <div class="flex gap-2 mt-3">
                  <button
                    class="px-3 py-1.5 rounded-full text-xs bg-amber-500 text-amber-950 font-medium hover:bg-amber-400"
                    @click="confirmInstall(plugin)"
                  >
                    {{ $t('plugins.marketInstall') }}
                  </button>
                  <button
                    class="px-3 py-1.5 rounded-full text-xs bg-surface-variant/40 hover:bg-surface-variant"
                    @click="cancelConfirm"
                  >
                    {{ $t('plugins.cancel') }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
  </Transition>
</template>

<style scoped>
.dialog-enter-active {
  transition: opacity 0.18s ease;
}
.dialog-enter-from {
  opacity: 0;
}
.dialog-leave-active {
  transition: opacity 0.12s ease;
}
.dialog-leave-to {
  opacity: 0;
}
.mask-enter-active,
.mask-leave-active {
  transition: opacity 0.18s ease;
}
.mask-enter-from,
.mask-leave-to {
  opacity: 0;
}
</style>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { useI18n } from 'vue-i18n';
import { Check, Loader2, RefreshCw, Search, Store, X } from '@lucide/vue';
import { loadPluginCatalog, marketPluginName, type MarketPlugin } from '../market';
import { usePlugins } from '../composables/usePlugins';

defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{ close: [] }>();

const { locale } = useI18n();
const pluginsState = usePlugins();

const catalog = ref<{ plugins: MarketPlugin[] }>({ plugins: [] });
const marketQuery = ref('');
const kindFilter = ref<string>('all');
const filteredCatalog = computed(() => {
  const q = marketQuery.value.trim().toLowerCase();
  return catalog.value.plugins.filter((pl) => {
    if (kindFilter.value !== 'all' && pl.kind !== kindFilter.value) return false;
    if (!q) return true;
    return (
      pl.name.toLowerCase().includes(q) ||
      pl.id.toLowerCase().includes(q) ||
      (pl.description ?? '').toLowerCase().includes(q)
    );
  });
});
const isLoading = ref(false);
const loadError = ref<string | null>(null);
const installedIds = ref<string[]>([]);
const installingId = ref<string | null>(null);
const confirmingId = ref<string | null>(null);
const preview = ref<{ capabilities: string[] } | null>(null);

interface PluginPreview {
  id: string;
  name: string;
  version: string;
  capabilities: string[];
  runtime: string;
  kind: string;
}

function onPreviewError(e: Event) {
  const img = e.target as HTMLImageElement;
  img.style.display = 'none';
}

async function load() {
  isLoading.value = true;
  loadError.value = null;
  try {
    catalog.value = await loadPluginCatalog();
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    isLoading.value = false;
  }
}

async function refreshInstalled() {
  await pluginsState.refresh();
  installedIds.value = pluginsState.plugins.value.map((p) => p.id);
}

function cancelConfirm() {
  confirmingId.value = null;
  preview.value = null;
}

async function install(plugin: MarketPlugin) {
  if (confirmingId.value === plugin.id) return;
  confirmingId.value = plugin.id;
  preview.value = null;
  try {
    const p = await invoke<PluginPreview>('preview_plugin_from_url', {
      manifestUrl: plugin.manifestUrl,
    });
    preview.value = { capabilities: p.capabilities };
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause);
    confirmingId.value = null;
  }
}

async function confirmInstall(plugin: MarketPlugin) {
  installingId.value = plugin.id;
  try {
    await invoke<string>('install_plugin_from_url', { zipUrl: plugin.downloadUrl });
    if (!installedIds.value.includes(plugin.id)) installedIds.value.push(plugin.id);
    void refreshInstalled();
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    installingId.value = null;
    cancelConfirm();
  }
}

onMounted(() => {
  void load();
  void refreshInstalled();
});
</script>
