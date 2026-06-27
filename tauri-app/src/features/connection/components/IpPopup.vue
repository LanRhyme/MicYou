<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { useI18n } from 'vue-i18n';
import { Globe, CheckCircle2 } from 'lucide-vue-next';

interface NetworkInterface {
  ip: string;
  interface_name: string;
}

const { t } = useI18n();
const popupWindow = getCurrentWindow();

const animState = ref<'hidden' | 'entering' | 'visible' | 'leaving'>('hidden');
const noTransition = ref(false);
const selectedIp = ref(localStorage.getItem('popup_ip') || '0.0.0.0');
const isAutoBind = ref(localStorage.getItem('popup_isAutoBind') !== 'false');
const interfaces = ref<NetworkInterface[]>(JSON.parse(localStorage.getItem('popup_interfaces') || '[]'));
const contentRef = ref<HTMLElement | null>(null);

const mq = window.matchMedia('(prefers-color-scheme: dark)');
const handleSystemThemeChange = (e: MediaQueryListEvent) => {
  document.documentElement.classList.toggle('dark', e.matches);
};

const syncTheme = () => {
  const html = document.documentElement;
  html.classList.toggle('dark', mq.matches);

  const themeColor = localStorage.getItem('micyou_theme_color') || 'theme-blue';
  const uiStyle = localStorage.getItem('micyou_ui_style') || 'style-default';

  // Detect macOS for native vibrancy
  const isMacOS = /Mac/.test(navigator.platform || navigator.userAgent) &&
    !/iPhone|iPad|iPod/.test(navigator.userAgent) &&
    !(navigator.maxTouchPoints && navigator.maxTouchPoints > 2);
  if (isMacOS) {
    html.classList.add('platform-macos');
  }

  const themes = ['theme-blue', 'theme-green', 'theme-rose', 'theme-purple', 'theme-orange', 'theme-amber', 'theme-teal', 'theme-cyan', 'theme-custom'];
  html.classList.remove(...themes, 'style-default', 'style-glass');
  html.classList.add(themeColor);
  html.classList.add(uiStyle);
};

const selectIp = (ip: string, autoSelect: boolean) => {
  selectedIp.value = autoSelect ? '0.0.0.0' : ip;
  isAutoBind.value = autoSelect;
  localStorage.setItem('popup_ip', selectedIp.value);
  localStorage.setItem('popup_isAutoBind', String(autoSelect));
  popupWindow.emit('ip-selected', { ip, autoSelect });
  animateOut();
};

const refreshState = () => {
  syncTheme();
  selectedIp.value = localStorage.getItem('popup_ip') || '0.0.0.0';
  isAutoBind.value = localStorage.getItem('popup_isAutoBind') !== 'false';
  interfaces.value = JSON.parse(localStorage.getItem('popup_interfaces') || '[]');
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
    await popupWindow.emit('popup-closing');
    popupWindow.hide();
    animState.value = 'hidden';
  }, 150);
};

let isShowing = false;

const onBlur = () => {
  if (animState.value === 'leaving' || isShowing) return;
  animateOut();
};

let unlisteners: (() => void)[] = [];

onMounted(async () => {
  syncTheme();
  mq.addEventListener('change', handleSystemThemeChange);
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
  unlisteners.push(await popupWindow.listen('popup-refresh', refreshState));
  unlisteners.push(await popupWindow.listen('popup-animate-in', animateIn));
  unlisteners.push(await popupWindow.listen('popup-animate-out', animateOut));
  await popupWindow.emit('popup-ready');
  // Tell parent the real content height so it can resize the window
  await nextTick();
  if (contentRef.value) {
    const h = contentRef.value.offsetHeight;
    if (h > 0) popupWindow.emit('popup-content-height', h);
  }
  setTimeout(() => window.addEventListener('blur', onBlur), 500);
});

onUnmounted(() => {
  mq.removeEventListener('change', handleSystemThemeChange);
  window.removeEventListener('blur', onBlur);
  unlisteners.forEach(fn => fn());
});
</script>

<template>
  <div class="w-full h-full" style="background: hsl(var(--surface)); outline: none">
    <div
      class="haze-surface rounded-2xl shadow-xl border border-outline/10 overflow-hidden h-full"
      style="background: hsl(var(--surface)); backdrop-filter: none !important; -webkit-backdrop-filter: none !important"
      :class="[noTransition ? '' : 'transition-all duration-200 ease-out', {
        'opacity-0 -translate-y-1.5 scale-95': animState === 'hidden' || animState === 'entering',
        'opacity-100 translate-y-0 scale-100': animState === 'visible',
        'opacity-0 -translate-y-1 scale-95': animState === 'leaving',
      }]"
    >
      <div ref="contentRef" class="max-h-60 overflow-y-auto pt-1">
        <button
          class="w-full flex items-center gap-2 px-3 py-2 hover:bg-surface-variant/50 transition-colors text-left"
          @click="selectIp('', true)"
        >
          <Globe class="w-3.5 h-3.5 text-primary flex-shrink-0" />
          <div class="flex-1 min-w-0">
            <div class="text-xs font-medium text-foreground">{{ t('app.ipSelector.allInterfaces') }}</div>
          </div>
          <CheckCircle2 v-if="isAutoBind" class="w-3.5 h-3.5 text-primary flex-shrink-0" />
        </button>
        <button
          v-for="iface in interfaces"
          :key="iface.ip"
          class="w-full flex items-center gap-2 px-3 py-2 hover:bg-surface-variant/50 transition-colors text-left"
          @click="selectIp(iface.ip, false)"
        >
          <Globe class="w-3.5 h-3.5 text-on-surface-variant flex-shrink-0" />
          <div class="flex-1 min-w-0">
            <div class="text-xs font-medium text-foreground">{{ iface.ip }}</div>
            <div class="text-[10px] text-on-surface-variant truncate">{{ iface.interface_name }}</div>
          </div>
          <CheckCircle2 v-if="!isAutoBind && selectedIp === iface.ip" class="w-3.5 h-3.5 text-primary flex-shrink-0" />
        </button>
      </div>
    </div>
  </div>
</template>
