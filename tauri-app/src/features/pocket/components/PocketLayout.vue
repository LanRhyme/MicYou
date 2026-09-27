<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue';
import { getCurrentWindow, LogicalPosition, LogicalSize, currentMonitor } from '@tauri-apps/api/window';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';
import {
  Link, Unlink, RefreshCw, Minus, X,
  Globe, ChevronDown, MoreHorizontal,
  VolumeX, Volume2, Headphones, Loader2
} from '@lucide/vue';

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
  isMonitoringEnabled: boolean;
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
  'toggleMonitoringEnabled',
  'toggleMonitoring',
  'openSettings',
  'minimize',
  'update:popupOpen',
]);

const appWindow = getCurrentWindow();
const moreMenuOpen = ref(false);
const activeOverlay = ref<string | null>(null);

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
  unlisteners: (() => void)[];
}

const OVERLAY_W = 220;
const OVERLAY_H = 250;      // default popup height
const OVERLAY_OFFSET_Y = 2; // gap kept between the bar and the popup
const OVERLAY_MARGIN = 8;   // gap kept between the popup and the work area edges
const OVERLAY_LABEL_PREFIX = 'pocket-overlay-';

const overlays: Record<string, OverlayHandle> = {};

const getOverlay = (id: string): OverlayHandle => {
  if (!overlays[id]) overlays[id] = { window: null, unlisteners: [] };
  return overlays[id];
};

// Overlay placement is expressed in LOGICAL pixels: the Tauri window APIs
// (WebviewWindow options, setPosition) take logical units, while
// outerPosition()/outerSize() return physical ones, so the raw values have to be
// converted first -- on a HiDPI display using them as-is scales the popup off
// screen. The result is then clamped into the work area of the monitor the bar
// sits on, flipping above the bar when there is no room left below.
const positionOverlay = async (align: 'right' | 'left' = 'right', height = OVERLAY_H) => {
  const scale = (await appWindow.scaleFactor()) || 1;
  const mainPos = (await appWindow.outerPosition()).toLogical(scale);
  const mainSize = (await appWindow.outerSize()).toLogical(scale);

  let x = align === 'right'
    ? mainPos.x + mainSize.width - OVERLAY_W
    : mainPos.x + 12; // left padding of the bar
  let y = mainPos.y + mainSize.height + OVERLAY_OFFSET_Y;

  try {
    const monitor = await currentMonitor();
    if (monitor) {
      const areaPos = monitor.workArea.position.toLogical(monitor.scaleFactor);
      const areaSize = monitor.workArea.size.toLogical(monitor.scaleFactor);
      const minX = areaPos.x + OVERLAY_MARGIN;
      const minY = areaPos.y + OVERLAY_MARGIN;
      const maxX = areaPos.x + areaSize.width - OVERLAY_W - OVERLAY_MARGIN;
      const maxY = areaPos.y + areaSize.height - height - OVERLAY_MARGIN;
      x = Math.min(Math.max(x, minX), Math.max(minX, maxX));

      const above = mainPos.y - OVERLAY_OFFSET_Y - height;
      if (y + height > areaPos.y + areaSize.height - OVERLAY_MARGIN && above >= minY) {
        y = above;
      }
      y = Math.min(Math.max(y, minY), Math.max(minY, maxY));
    }
  } catch (error) {
    // Without a monitor the popup still opens where it would have gone; log it so
    // a monitor query that genuinely broke is visible instead of silently unclamped.
    console.warn('pocket overlay: could not read the monitor bounds:', error);
  }

  return { x: Math.round(x), y: Math.round(y) };
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

  // Both callers run hideAllOverlays() first, which closes the window and clears
  // the handle, so there is never a live overlay to reuse here.
  const { x, y } = await positionOverlay(opts?.align, opts?.height ?? OVERLAY_H);
  h.window = new WebviewWindow(OVERLAY_LABEL_PREFIX + id, {
    url,
    title: '',
    width: OVERLAY_W,
    height: opts?.height ?? OVERLAY_H,
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
      activeOverlay.value = null;
    })
  );

  opts?.onCreated?.(h);
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
        await h.window.close();
      } catch {}
    }
    h.unlisteners.forEach(fn => fn());
    h.unlisteners = [];
    h.window = null;
  }
  moreMenuOpen.value = false;
  activeOverlay.value = null;
  emit('update:popupOpen', false);
};

const destroyAllOverlays = async () => {
  for (const id of Object.keys(overlays)) {
    const h = overlays[id];
    h.unlisteners.forEach(fn => fn());
    h.unlisteners = [];
    if (h.window) {
      try { await h.window.close(); } catch {}
      h.window = null;
    }
  }
};

// ---- IP popup ----

const showIpPopup = async () => {
  if (activeOverlay.value === 'ip') {
    await hideAllOverlays();
    return;
  }
  await hideAllOverlays();
  activeOverlay.value = 'ip';
  await syncAndShow('ip', '#/popup/ip', () => {
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
          const { x, y } = await positionOverlay('left', contentH + 16);
          await h.window.setPosition(new LogicalPosition(x, y));
        } catch {}
      }
    });
  }});
};

// ---- More menu popup ----

const showMoreMenu = async () => {
  if (activeOverlay.value === 'more') {
    await hideAllOverlays();
    return;
  }
  await hideAllOverlays();
  moreMenuOpen.value = true;
  activeOverlay.value = 'more';
  await syncAndShow('more', '#/popup/more-menu', () => {
    localStorage.setItem('popup_connectionMode', props.connectionMode);
    localStorage.setItem('popup_serverPort', String(props.serverPort));
  }, { height: 210, align: 'right', onCreated: (h) => {
    h.window?.listen('popup-update', (event: any) => {
      const { key, value } = event.payload;
      switch (key) {
        case 'popup_connectionMode': emit('updateMode', value); break;
        case 'popup_serverPort': emit('updatePort', Number(value)); break;
        case 'popup_openSettings': closePopup(); emit('openSettings'); break;
      }
    });
  }});
};

onUnmounted(() => {
  destroyAllOverlays();
});

defineExpose({ closePopup });
</script>

<template>
  <!-- w-max: 宽度由内容决定，窗口 resize 不会改变内容宽度，是自动缩放无循环的关键 -->
  <div class="w-max h-full flex items-center haze-surface rounded-2xl px-3 gap-2">
    <!-- macOS uses the native window controls; keep their footprint clear -->
    <div
      v-if="isMacOS"
      class="macos-titlebar-spacer-pocket flex-shrink-0"
    />

    <!-- Status Dot -->
    <div class="w-2 h-2 rounded-full flex-shrink-0 pointer-events-none" :class="statusColor" />

    <!-- Connect Button -->
    <button
      @click="emit('toggleStream')"
      class="h-8 px-3 rounded-lg text-xs font-bold text-on-primary flex items-center gap-1.5 transition-all duration-300 hover:scale-105 active:scale-95 hover:shadow-md flex-shrink-0"
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
      class="flex items-center gap-1 px-2 py-1 rounded-md hover:bg-surface-variant/50 transition-all duration-300 hover:shadow-sm active:scale-95 flex-shrink-0 max-w-[120px]"
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
      class="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-surface-variant/60 transition-all duration-300 hover:scale-110 active:scale-90 flex-shrink-0"
      :title="isMuted ? $t('app.status.unmute') : $t('app.status.mute')"
    >
      <VolumeX v-if="isMuted" class="w-4 h-4 text-error" />
      <Volume2 v-else class="w-4 h-4 text-on-surface-variant" />
    </button>

    <!-- Separator -->
    <div class="w-px h-4 bg-outline/20 flex-shrink-0 pointer-events-none" />

    <!-- Earback / Monitoring -->
    <button
      @click="emit('toggleMonitoringEnabled')"
      class="w-8 h-8 rounded-lg flex items-center justify-center transition-all duration-300 hover:scale-110 active:scale-90 flex-shrink-0"
      :class="isMonitoringEnabled ? 'bg-primary/20 text-primary' : 'hover:bg-surface-variant/60 text-on-surface-variant'"
      :title="isMonitoringEnabled ? $t('app.status.disableEarback') : $t('app.status.enableEarback')"
    >
      <Headphones class="w-4 h-4" />
    </button>

    <!-- Separator -->
    <div class="w-px h-4 bg-outline/20 flex-shrink-0 pointer-events-none" />

    <!-- More Menu -->
    <button
      @click="showMoreMenu"
      class="w-8 h-8 rounded-lg flex items-center justify-center transition-all duration-300 hover:scale-110 active:scale-90 flex-shrink-0"
      :class="moreMenuOpen ? 'bg-surface-variant/60' : 'hover:bg-surface-variant/50'"
    >
      <MoreHorizontal class="w-4 h-4 text-on-surface-variant" />
    </button>

    <!-- Window Controls (non-macOS: right) -->
    <template v-if="!isMacOS">
      <button @click="emit('minimize')" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors flex-shrink-0">
        <Minus class="w-3.5 h-3.5 text-on-surface" />
      </button>
      <button @click="appWindow.close()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-error/20 hover:text-error transition-colors flex-shrink-0">
        <X class="w-3.5 h-3.5 text-on-surface" />
      </button>
    </template>
  </div>
</template>
