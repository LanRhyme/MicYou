<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { useI18n } from 'vue-i18n';
import {
  Wifi, Mic, Globe, Settings
} from '@lucide/vue';
import { BUILTIN_THEMES, generateThemeCSS, hexToHsl } from '@/features/theme/composables/useTheme';

const { t } = useI18n();
const popupWindow = getCurrentWindow();

// Animation state
const animState = ref<'hidden' | 'entering' | 'visible' | 'leaving'>('hidden');
const noTransition = ref(false);

// Sync theme
const syncTheme = () => {
  const html = document.documentElement;

  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  html.classList.toggle('dark', mq.matches);
  mq.addEventListener('change', (e) => html.classList.toggle('dark', e.matches));

  const themeMode = localStorage.getItem('micyou_theme_v2_mode') || 'system';
  const themeColor = localStorage.getItem('micyou_theme_v2_color') || 'theme-blue';
  const uiStyle = localStorage.getItem('micyou_theme_v2_ui_style') || 'style-default';

  // Detect macOS for native vibrancy
  const isMacOS = /Mac/.test(navigator.platform || navigator.userAgent) &&
    !/iPhone|iPad|iPod/.test(navigator.userAgent) &&
    !(navigator.maxTouchPoints && navigator.maxTouchPoints > 2);
  if (isMacOS) {
    html.classList.add('platform-macos');
  }

  const themes = Object.keys(BUILTIN_THEMES).concat('theme-custom', 'theme-system');
  html.classList.remove(...themes, 'theme-color-blue', 'theme-color-green', 'theme-color-rose', 'theme-color-purple', 'theme-color-orange', 'theme-color-amber', 'theme-color-teal', 'theme-color-cyan', 'theme-color-custom', 'theme-color-system', 'style-default', 'style-glass', 'theme-mode-system', 'theme-mode-preset', 'theme-mode-custom', 'system-accent-unavailable');
  const activeColor = themeMode === 'system' ? 'theme-system' : themeColor;
  html.classList.add(activeColor, `theme-color-${activeColor.replace('theme-', '')}`, `theme-mode-${themeMode}`, uiStyle);

  const baseColor = themeMode === 'system'
    ? hexToHsl(localStorage.getItem('micyou_theme_v2_system_hex') || '#5b7cfa')
    : themeMode === 'custom'
      ? {
          h: Number(localStorage.getItem('micyou_theme_v2_custom_h') || '215'),
          s: Number(localStorage.getItem('micyou_theme_v2_custom_s') || '35'),
          l: Number(localStorage.getItem('micyou_theme_v2_custom_l') || '55'),
        }
      : (BUILTIN_THEMES[themeColor] || BUILTIN_THEMES['theme-blue']);
  const variant = localStorage.getItem('micyou_theme_v2_variant') || 'TonalSpot';
  const style = document.getElementById('popup-theme-tokens') || document.createElement('style');
  style.id = 'popup-theme-tokens';
  style.textContent = `
    :root, :root[class] { ${generateThemeCSS(baseColor.h, baseColor.s, baseColor.l, variant, false)} }
    :root.dark, html.dark[class] { ${generateThemeCSS(baseColor.h, baseColor.s, baseColor.l, variant, true)} }
  `;
  if (!style.parentElement) document.head.appendChild(style);

  const packageStyle = document.getElementById('popup-theme-package-css') || document.createElement('style');
  packageStyle.id = 'popup-theme-package-css';
  packageStyle.textContent = localStorage.getItem('micyou_theme_v2_installed_css') || '';
  if (!packageStyle.parentElement) document.head.appendChild(packageStyle);

  const userStyle = document.getElementById('popup-user-custom-css') || document.createElement('style');
  userStyle.id = 'popup-user-custom-css';
  userStyle.textContent = localStorage.getItem('micyou_theme_v2_css_enabled') !== 'false'
    ? localStorage.getItem('micyou_theme_v2_css') || ''
    : '';
  if (!userStyle.parentElement) document.head.appendChild(userStyle);

};

const connectionMode = ref(localStorage.getItem('popup_connectionMode') || 'wifi');
const serverPort = ref(Number(localStorage.getItem('popup_serverPort')) || 8554);
const webPort = ref(Number(localStorage.getItem('popup_webPort')) || 8443);

const modes = [
  { value: 'wifi', icon: Wifi, label: 'Wi-Fi' },
  { value: 'usb', icon: Mic, label: 'USB' },
  { value: 'web', icon: Globe, label: 'Web' },
];

const emitUpdate = (key: string, value: string) => {
  localStorage.setItem(key, value);
  popupWindow.emit('popup-update', { key, value });
};

const updateMode = (mode: string) => {
  connectionMode.value = mode;
  emitUpdate('popup_connectionMode', mode);
};

const updatePort = (e: Event) => {
  const val = Number((e.target as HTMLInputElement).value);
  serverPort.value = val;
  emitUpdate('popup_serverPort', String(val));
};

const updateWebPort = (e: Event) => {
  const val = Number((e.target as HTMLInputElement).value);
  webPort.value = val;
  emitUpdate('popup_webPort', String(val));
};

const openSettings = () => {
  emitUpdate('popup_openSettings', 'true');
};

const refreshState = () => {
  connectionMode.value = localStorage.getItem('popup_connectionMode') || 'wifi';
  serverPort.value = Number(localStorage.getItem('popup_serverPort')) || 8554;
  webPort.value = Number(localStorage.getItem('popup_webPort')) || 8443;
};

// Blur = user clicked away → animate out then hide
let isShowing = false;

const onBlur = () => {
  if (animState.value === 'leaving' || isShowing) return;
  animateOut();
};

const animateIn = async () => {
  // popup-prepare already set isShowing=true, noTransition=true, animState='hidden'
  await nextTick();
  noTransition.value = false;
  animState.value = 'entering';
  setTimeout(() => { animState.value = 'visible'; isShowing = false; }, 200);
};

const animateOut = () => {
  if (animState.value === 'leaving') return;
  animState.value = 'leaving';
  setTimeout(async () => {
    // Notify main window before hiding
    await popupWindow.emit('popup-closing');
    popupWindow.hide();
    animState.value = 'hidden';
  }, 150);
};

let unlisteners: (() => void)[] = [];

onMounted(async () => {
  syncTheme();
  // Remove default focus outline on the popup window
  const style = document.createElement('style');
  style.textContent = 'html, body, *:focus { outline: none !important; }';
  document.head.appendChild(style);
  // Prepare handler: set guard + reset state BEFORE show() — must be registered first
  unlisteners.push(await popupWindow.listen('popup-prepare', () => {
    isShowing = true;
    noTransition.value = true;
    animState.value = 'hidden';
  }));
  unlisteners.push(await popupWindow.listen('popup-refresh', () => {
    syncTheme();
    refreshState();
  }));
  unlisteners.push(await popupWindow.listen('popup-animate-in', animateIn));
  unlisteners.push(await popupWindow.listen('popup-animate-out', animateOut));

  await popupWindow.emit('popup-ready');

  setTimeout(() => {
    window.addEventListener('blur', onBlur);
  }, 500);
});

onUnmounted(() => {
  window.removeEventListener('blur', onBlur);
  unlisteners.forEach(fn => fn());
});
</script>

<template>
  <div
    class="popup-panel w-full h-full"
    :class="[noTransition ? '' : 'transition-all duration-200 ease-out', {
      'opacity-0 -translate-y-1.5 scale-95': animState === 'hidden' || animState === 'entering',
      'opacity-100 translate-y-0 scale-100': animState === 'visible',
      'opacity-0 -translate-y-1 scale-95': animState === 'leaving',
    }]"
  >
    <div class="pt-1">
        <div class="px-3 py-2">
          <div class="text-[10px] text-on-surface-variant font-medium mb-1.5 uppercase tracking-wider">{{ t('app.connectionMode') }}</div>
          <div class="flex gap-1">
            <button
              v-for="mode in modes"
              :key="mode.value"
              @click="updateMode(mode.value)"
              class="flex-1 flex flex-col items-center py-1.5 rounded-lg transition-colors text-[10px] font-medium"
              :class="connectionMode === mode.value ? 'bg-primary text-on-primary' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/60'"
            >
              <component :is="mode.icon" class="w-3.5 h-3.5 mb-0.5" />
              {{ mode.label }}
            </button>
          </div>
        </div>

        <!-- Port -->
        <div class="px-3 py-2 border-t border-outline/10">
          <div class="text-[10px] text-on-surface-variant font-medium mb-1.5 uppercase tracking-wider">{{ t('app.port') }}</div>
          <input
            v-if="connectionMode !== 'web'"
            :value="serverPort"
            @input="updatePort"
            type="number"
            max="65534"
            class="w-full bg-surface-variant/40 border border-white/5 rounded-lg px-2.5 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          />
          <input
            v-else
            :value="webPort"
            @input="updateWebPort"
            type="number"
            class="w-full bg-surface-variant/40 border border-white/5 rounded-lg px-2.5 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          />
        </div>

        <!-- Actions -->
        <div class="border-t border-outline/10 pt-1">
          <button
            @click="openSettings"
            class="w-full flex items-center gap-2.5 px-3 py-2 hover:bg-surface-variant/60 transition-colors text-left"
          >
            <Settings class="w-3.5 h-3.5 text-on-surface-variant" />
            <span class="text-xs text-on-surface">{{ t('settings.title') }}</span>
          </button>
        </div>
      </div>
  </div>
</template>
