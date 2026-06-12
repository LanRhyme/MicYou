<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { useI18n } from 'vue-i18n';
import {
  Wifi, Mic, Globe, Settings
} from 'lucide-vue-next';

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

  const themeColor = localStorage.getItem('micyou_theme_color') || 'theme-blue';
  const uiStyle = localStorage.getItem('micyou_ui_style') || 'style-glass';

  // Detect macOS for native vibrancy
  if (/Mac|iPod|iPhone|iPad/.test(navigator.platform || navigator.userAgent)) {
    html.classList.add('platform-macos');
  }

  const themes = ['theme-blue', 'theme-green', 'theme-rose', 'theme-purple', 'theme-orange', 'theme-amber', 'theme-teal', 'theme-cyan', 'theme-custom'];
  html.classList.remove(...themes, 'style-default', 'style-glass');
  html.classList.add(themeColor);
  html.classList.add(uiStyle);

  if (themeColor === 'theme-custom') {
    const h = Number(localStorage.getItem('micyou_custom_h') || '215');
    const s = Number(localStorage.getItem('micyou_custom_s') || '35');
    const l = Number(localStorage.getItem('micyou_custom_l') || '55');
    const lDark = Math.min(l + 10, 80);

    let style = document.getElementById('popup-custom-theme');
    if (!style) {
      style = document.createElement('style');
      style.id = 'popup-custom-theme';
      document.head.appendChild(style);
    }
    style.innerHTML = `
      :root, .theme-custom {
        --primary: ${h} ${s}% ${l}%;
        --on-primary: ${h} ${s}% 92%;
        --surface: ${h} 15% 98%;
        --on-surface: ${h} 15% 25%;
        --surface-variant: ${h} 15% 88%;
        --on-surface-variant: ${h} 15% 45%;
        --outline: ${h} 15% 80%;
        --error: 0 40% 55%;
        --foreground: ${h} 15% 25%;
        --background: ${h} 15% 96%;
      }
      .dark.theme-custom, .theme-custom .dark {
        --primary: ${h} ${s}% ${lDark}%;
        --on-primary: ${h} ${s}% 20%;
        --surface: ${h} 15% 10%;
        --on-surface: ${h} 15% 85%;
        --surface-variant: ${h} 15% 22%;
        --on-surface-variant: ${h} 15% 60%;
        --outline: ${h} 15% 20%;
        --error: 0 40% 65%;
        --foreground: ${h} 15% 85%;
        --background: ${h} 15% 8%;
      }
    `;
  }
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

  // Prepare handler: set guard + reset state BEFORE show() — must be registered first
  unlisteners.push(await popupWindow.listen('popup-prepare', () => {
    isShowing = true;
    noTransition.value = true;
    animState.value = 'hidden';
  }));
  unlisteners.push(await popupWindow.listen('popup-refresh', refreshState));
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
  <div class="w-full h-full">
    <div
      class="haze-surface rounded-2xl shadow-xl border border-outline/10 overflow-hidden"
      :class="[noTransition ? '' : 'transition-all duration-200 ease-out', {
        'opacity-0 -translate-y-1.5 scale-95': animState === 'hidden' || animState === 'entering',
        'opacity-100 translate-y-0 scale-100': animState === 'visible',
        'opacity-0 -translate-y-1 scale-95': animState === 'leaving',
      }]"
    >
      <div class="py-1">
        <!-- Connection Mode -->
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
        <div class="border-t border-outline/10 py-1">
          <button
            @click="openSettings"
            class="w-full flex items-center gap-2.5 px-3 py-2 hover:bg-surface-variant/50 transition-colors text-left"
          >
            <Settings class="w-3.5 h-3.5 text-on-surface-variant" />
            <span class="text-xs text-on-surface">{{ t('settings.title') }}</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
