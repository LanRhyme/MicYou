<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue';
import { getCurrentWindow, LogicalPosition, LogicalSize } from '@tauri-apps/api/window';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';
import {
  Link, Unlink, RefreshCw, Minus, X,
  Globe, ChevronDown, MoreHorizontal,
  VolumeX, Volume2, Loader2
} from 'lucide-vue-next';

interface NetworkInterface {
  ip: string;
  interface_name: string;
}

const props = defineProps<{
  serverState: string;
  connectionMode: string;
  serverPort: number;
  displayIp: string;
  isAutoBind: boolean;
  selectedIp: string;
  networkInterfaces: NetworkInterface[];
  isMuted: boolean;
  showMonitoringPanel: boolean;
  audioLevel: number;
  outputDevice: string;
  audioMetrics: any;
  popupOpen: boolean;
}>();

const emit = defineEmits([
  'toggleStream',
  'selectIp',
  'updateMode',
  'updatePort',
  'toggleMute',
  'toggleMonitoring',
  'openSettings',
  'update:popupOpen',
]);

const appWindow = getCurrentWindow();
const moreMenuOpen = ref(false);

const isMacOS = typeof navigator !== 'undefined' &&
  /Mac/.test(navigator.platform || navigator.userAgent) &&
  !/iPhone|iPad|iPod/.test(navigator.userAgent) &&
  !(navigator.maxTouchPoints && navigator.maxTouchPoints > 2);

const statusColor = computed(() => {
  switch (props.serverState) {
    case 'streaming': return 'bg-primary';
    case 'starting': return 'bg-secondary animate-pulse';
    case 'connecting': return 'bg-tertiary animate-pulse';
    default: return 'bg-on-surface-variant';
  }
});

const buttonColor = computed(() => {
  switch (props.serverState) {
    case 'streaming': return 'bg-error hover:bg-error/90';
    case 'starting': return 'bg-secondary hover:bg-secondary/90';
    case 'connecting': return 'bg-tertiary hover:bg-tertiary/90';
    default: return 'bg-primary hover:bg-primary/90';
  }
});

// ---- Generic overlay helpers ----

interface OverlayHandle {
  window: WebviewWindow | null;
  created: boolean;
  unlisteners: (() => void)[];
}

const OVERLAY_W = 220;
const OVERLAY_LABEL_PREFIX = 'pocket-overlay-';

const overlays: Record<string, OverlayHandle> = {};

const getOverlay = (id: string): OverlayHandle => {
  if (!overlays[id]) overlays[id] = { window: null, created: false, unlisteners: [] };
  return overlays[id];
};

const positionOverlay = async (align: 'right' | 'left' = 'right', offsetY = 2) => {
  const mainPos = await appWindow.outerPosition();
  const mainSize = await appWindow.outerSize();
  const x = align === 'right'
    ? Math.round(mainPos.x + mainSize.width - OVERLAY_W)
    : Math.round(mainPos.x + 12); // left padding of the bar
  const y = Math.round(mainPos.y + mainSize.height + offsetY);
  return { x, y };
};

const showOverlay = async (h: OverlayHandle) => {
  if (!h.window) return;
  try {
    // Prepare: set isShowing guard + reset state before show()
    await h.window.emit('popup-prepare');
    await h.window.show();
    await h.window.setFocus();
    // Delay to ensure the popup's animateIn listener is registered
    setTimeout(() => { h.window?.emit('popup-animate-in'); }, 50);
  } catch {}
};

const syncAndShow = async (id: string, url: string, syncFn: () => void, opts?: { height?: number; align?: 'right' | 'left'; onCreated?: (h: OverlayHandle) => void }) => {
  const h = getOverlay(id);
  emit('update:popupOpen', true);
  syncFn();

  if (!h.created || !h.window) {
    const { x, y } = await positionOverlay(opts?.align);
    h.window = new WebviewWindow(OVERLAY_LABEL_PREFIX + id, {
      url,
      title: '',
      width: OVERLAY_W,
      height: opts?.height ?? 250,
      x, y,
      parent: 'main',
      decorations: false,
      transparent: true,
      resizable: false,
      skipTaskbar: true,
      visible: false,
      focus: false,
    });
    // Register popup-ready BEFORE other async ops to avoid missing the event
    h.unlisteners.push(
      await h.window.listen('popup-ready', () => {
        showOverlay(h);
      })
    );

    try { await h.window.setBackgroundColor([0, 0, 0, 0]); } catch {}

    h.unlisteners.push(
      await h.window.listen('popup-closing', () => {
        emit('update:popupOpen', false);
        moreMenuOpen.value = false;
      })
    );

    opts?.onCreated?.(h);
    h.created = true;
  } else {
    const { x, y } = await positionOverlay(opts?.align);
    try { await h.window.setPosition(new LogicalPosition(x, y)); } catch {}
    try { await h.window.emit('popup-refresh'); } catch {}
    await showOverlay(h);
  }
};

const closePopup = async () => {
  for (const id of Object.keys(overlays)) {
    const h = overlays[id];
    if (h.window) {
      try { await h.window.emit('popup-animate-out'); } catch {}
    }
  }
  moreMenuOpen.value = false;
};

// Close all overlays (destroy & reset) — used when switching between popups
const hideAllOverlays = async () => {
  for (const id of Object.keys(overlays)) {
    const h = overlays[id];
    if (h.window) {
      try {
        await h.window.emit('popup-closing');
        h.window.close();
      } catch {}
    }
    h.unlisteners.forEach(fn => fn());
    h.unlisteners = [];
    h.window = null;
    h.created = false;
  }
  moreMenuOpen.value = false;
  emit('update:popupOpen', false);
};

const destroyAllOverlays = () => {
  for (const id of Object.keys(overlays)) {
    const h = overlays[id];
    h.unlisteners.forEach(fn => fn());
    h.unlisteners = [];
    if (h.window) {
      try { h.window.close(); } catch {}
      h.window = null;
      h.created = false;
    }
  }
};

// ---- IP popup ----

const showIpPopup = async () => {
  await hideAllOverlays();
  syncAndShow('ip', '#/popup/ip', () => {
    localStorage.setItem('popup_ip', props.isAutoBind ? '0.0.0.0' : props.selectedIp);
    localStorage.setItem('popup_isAutoBind', String(props.isAutoBind));
    localStorage.setItem('popup_interfaces', JSON.stringify(props.networkInterfaces));
  }, { height: 256, align: 'left', onCreated: (h) => {
    h.window?.listen('ip-selected', (event: any) => {
      const { ip, autoSelect } = event.payload;
      emit('selectIp', ip, autoSelect);
    });
    h.window?.listen('popup-content-height', async (event: any) => {
      const contentH = event.payload as number;
      if (h.window && contentH > 0) {
        try {
          await h.window.setSize(new LogicalSize(OVERLAY_W, contentH + 16));
          const { x, y } = await positionOverlay('left');
          await h.window.setPosition(new LogicalPosition(x, y));
        } catch {}
      }
    });
  }});
};

// ---- More menu popup ----

const showMoreMenu = async () => {
  await hideAllOverlays();
  moreMenuOpen.value = true;
  syncAndShow('more', '#/popup/more-menu', () => {
    localStorage.setItem('popup_connectionMode', props.connectionMode);
    localStorage.setItem('popup_serverPort', String(props.serverPort));
  }, { height: 210, align: 'right' });

  const h = getOverlay('more');
  h.window?.listen('popup-update', (event: any) => {
    const { key, value } = event.payload;
    switch (key) {
      case 'popup_connectionMode': emit('updateMode', value); break;
      case 'popup_serverPort': emit('updatePort', Number(value)); break;
      case 'popup_openSettings': closePopup(); emit('openSettings'); break;
    }
  });
};

onUnmounted(() => {
  destroyAllOverlays();
});

defineExpose({ closePopup });
</script>

<template>
  <div class="w-full h-full flex items-center haze-surface rounded-2xl px-3 gap-2">
    <!-- Window Controls (macOS: left) -->
    <template v-if="isMacOS">
      <button @click="appWindow.minimize()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors flex-shrink-0">
        <Minus class="w-3.5 h-3.5 text-on-surface" />
      </button>
      <button @click="appWindow.close()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-error/20 hover:text-error transition-colors flex-shrink-0">
        <X class="w-3.5 h-3.5 text-on-surface" />
      </button>
    </template>

    <!-- Status Dot -->
    <div class="w-2 h-2 rounded-full flex-shrink-0 pointer-events-none" :class="statusColor" />

    <!-- Connect Button -->
    <button
      @click="emit('toggleStream')"
      class="h-8 px-3 rounded-lg text-xs font-bold text-on-primary flex items-center gap-1.5 transition-colors flex-shrink-0"
      :class="buttonColor"
    >
      <RefreshCw v-if="serverState === 'connecting'" class="w-3.5 h-3.5 animate-spin" />
      <Loader2 v-else-if="serverState === 'starting'" class="w-3.5 h-3.5 animate-spin" />
      <Unlink v-else-if="serverState === 'streaming'" class="w-3.5 h-3.5" />
      <Link v-else class="w-3.5 h-3.5" />
      <span>{{ serverState === 'streaming' ? $t('app.status.stateStreaming') : (serverState === 'connecting' ? $t('app.status.stateConnecting') : (serverState === 'starting' ? $t('app.status.stateStarting') : $t('app.start'))) }}</span>
    </button>

    <!-- IP Display -->
    <button
      @click="showIpPopup"
      class="flex items-center gap-1 px-2 py-1 rounded-md hover:bg-surface-variant/40 transition-colors flex-shrink-0 max-w-[120px]"
    >
      <Globe class="w-3 h-3 text-primary flex-shrink-0" />
      <span class="text-xs font-medium text-on-surface truncate">{{ displayIp }}</span>
      <ChevronDown class="w-3 h-3 text-on-surface-variant/50 flex-shrink-0" />
    </button>

    <!-- Separator -->
    <div class="w-px h-4 bg-outline/20 flex-shrink-0 pointer-events-none" />

    <!-- Mute -->
    <button
      @click="emit('toggleMute')"
      class="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-surface-variant/40 transition-colors flex-shrink-0"
    >
      <VolumeX v-if="isMuted" class="w-4 h-4" />
      <Volume2 v-else class="w-4 h-4 text-on-surface-variant" />
    </button>

    <!-- Separator -->
    <div class="w-px h-4 bg-outline/20 flex-shrink-0 pointer-events-none" />

    <!-- More Menu -->
    <button
      @click="showMoreMenu"
      class="w-8 h-8 rounded-lg flex items-center justify-center transition-colors flex-shrink-0"
      :class="moreMenuOpen ? 'bg-surface-variant/60' : 'hover:bg-surface-variant/40'"
    >
      <MoreHorizontal class="w-4 h-4 text-on-surface-variant" />
    </button>

    <!-- Window Controls (non-macOS: right) -->
    <template v-if="!isMacOS">
      <button @click="appWindow.minimize()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors flex-shrink-0">
        <Minus class="w-3.5 h-3.5 text-on-surface" />
      </button>
      <button @click="appWindow.close()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-error/20 hover:text-error transition-colors flex-shrink-0">
        <X class="w-3.5 h-3.5 text-on-surface" />
      </button>
    </template>
  </div>
</template>
