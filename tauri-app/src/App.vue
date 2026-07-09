<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watchEffect } from 'vue';
import { useStorage, onClickOutside } from '@vueuse/core';
import { LogicalSize } from '@tauri-apps/api/window';
import { invoke } from '@tauri-apps/api/core';
import { useI18n } from 'vue-i18n';

// Icons
import { Mic, Wifi, RadioTower, Globe, ChevronDown, CheckCircle2, Settings, Link, Unlink, RefreshCw, ActivitySquare as MonitoringIcon, X, Minus, VolumeX, Volume2, QrCode as QrCodeIcon, Loader2 } from '@lucide/vue';

// Feature composables
import { useServer } from './features/connection/composables/useServer';
import { useAudio } from './features/audio/composables/useAudio';
import { useTheme } from './features/theme/composables/useTheme';
import { useWindow } from './shared/composables/useWindow';
import { useTray } from './shared/composables/useTray';

// Feature components
import ConnectionErrorDialog from './features/connection/components/ConnectionErrorDialog.vue';
import QrCodeDialog from './features/connection/components/QrCodeDialog.vue';
import AudioRing from './features/audio/components/AudioRing.vue';
import MonitoringPanel from './features/audio/components/MonitoringPanel.vue';
import SettingsDialog from './features/settings/components/SettingsDialog.vue';
import OnboardingWizard from './features/onboarding/components/OnboardingWizard.vue';
import PocketLayout from './features/pocket/components/PocketLayout.vue';
import CustomBackground from './shared/components/CustomBackground.vue';
import CloseConfirmDialog from './shared/components/CloseConfirmDialog.vue';
import UdpWarningDialog from './shared/components/UdpWarningDialog.vue';

import appIconSvg from './shared/assets/app_icon.svg?raw';
import anime from 'animejs';

// Detect macOS platform for native vibrancy (NSVisualEffectView)
const isMacOS = typeof navigator !== 'undefined' &&
  /Mac/.test(navigator.platform || navigator.userAgent) &&
  !/iPhone|iPad|iPod/.test(navigator.userAgent) &&
  !(navigator.maxTouchPoints && navigator.maxTouchPoints > 2);
if (isMacOS && typeof document !== 'undefined') {
  document.documentElement.classList.add('platform-macos');
}

const { t } = useI18n();

// Feature composables
const audio = useAudio();
const server = useServer({ audioLevel: audio.audioLevel, isMuted: audio.isMuted });
useTheme();
const win = useWindow();

// Window drag handling: use Rust-side Win32 drag loop on Windows, fallback on other platforms
const startDrag = async (e: MouseEvent) => {
  if (e.button !== 0) return;
  // Ignore clicks on interactive elements so buttons/inputs work normally
  const target = e.target as HTMLElement;
  if (target.closest('button, a, input, select, textarea, [role="button"]')) return;
  try {
    await invoke('start_window_drag');
  } catch {
    // Non-Windows fallback to Tauri's native startDragging
    try {
      await win.appWindow.startDragging();
    } catch (err) {
      console.error('Drag failed:', err);
    }
  }
};

// Animation refs
const centralBtnRef = ref<HTMLButtonElement | null>(null);
const glowRef = ref<HTMLDivElement | null>(null);
const statusDotRef = ref<HTMLDivElement | null>(null);

let breatheAnim: ReturnType<typeof anime> | null = null;
let dotPulseAnim: ReturnType<typeof anime> | null = null;

const showOnboarding = ref(localStorage.getItem('micyou_onboarding_completed') !== 'true');
const isSettingsOpen = ref(false);
const pocketMode = useStorage('micyou_pocket_mode', false);
const pocketPopupOpen = ref(false);
const pocketLayoutRef = ref<InstanceType<typeof PocketLayout> | null>(null);

const ipMenuRef = ref<HTMLDivElement | null>(null);
onClickOutside(ipMenuRef, () => {
  server.showIpMenu.value = false;
});

// Also close the IP menu when the window loses focus
onMounted(() => {
  window.addEventListener('blur', () => {
    server.showIpMenu.value = false;
  });
});
onUnmounted(() => {
  window.removeEventListener('blur', () => {
    server.showIpMenu.value = false;
  });
});

// Cross-feature: toggle streaming (server + animation)
const toggleStreaming = async () => {
  await server.toggleStreaming();
  if (centralBtnRef.value) {
    anime({
      targets: centralBtnRef.value,
      scale: [1, 0.9, 1.05, 1],
      duration: 600,
      easing: 'spring(1, 80, 10, 0)',
    });
  }
};

// Tray integration
const streamingRef = computed(() => server.isStreaming(server.serverState.value));
const visibilityRef = computed(() => !win.isHidden.value);

useTray(
  {
    onShow: async () => {
      if (win.isHidden.value) {
        await win.showMainWindow();
      } else {
        await win.hideMainWindow();
      }
    },
    onToggleStream: () => toggleStreaming(),
    onExit: () => win.exitApp(),
  },
  visibilityRef,
  streamingRef,
);

// Auto-hide window on startup if "start minimized" is enabled
onMounted(() => {
  if (win.isHidden.value) {
    void win.hideMainWindow();
  }
});

// Resize window when pocket mode changes
watchEffect(async () => {
  const isPocket = pocketMode.value;
  if (isPocket && isSettingsOpen.value) return;
  try {
    if (isPocket) {
      await win.appWindow.setSize(new LogicalSize(420, 52));
    } else {
      await win.appWindow.setSize(new LogicalSize(800, 600));
    }
  } catch (e) {
    console.error('Failed to resize window:', e);
  }
});

// Resize for settings dialog in pocket mode
watchEffect(async () => {
  if (pocketMode.value && isSettingsOpen.value) {
    try {
      await win.appWindow.setSize(new LogicalSize(800, 600));
    } catch (e) {
      console.error('Failed to resize window for settings:', e);
    }
  }
});

// Animation: button hover
const onCentralBtnHover = () => {
  if (centralBtnRef.value) {
    anime({
      targets: centralBtnRef.value,
      scale: 1.08,
      duration: 400,
      easing: 'easeOutExpo',
    });
  }
};

const onCentralBtnLeave = () => {
  if (centralBtnRef.value) {
    anime({
      targets: centralBtnRef.value,
      scale: 1,
      duration: 500,
      easing: 'easeOutExpo',
    });
  }
};

const micScale = computed(() => {
  return 1 + (audio.audioLevel.value / 100) * 0.5;
});

// Streaming breathing glow animation
watchEffect(() => {
  if (server.serverState.value === 'streaming' && glowRef.value) {
    if (!breatheAnim) {
      breatheAnim = anime({
        targets: glowRef.value,
        opacity: [0.3, 0.7],
        scale: [1.2, 1.35],
        duration: 2000,
        direction: 'alternate',
        loop: true,
        easing: 'easeInOutSine',
      });
    }
  } else {
    if (breatheAnim) {
      breatheAnim.pause();
      breatheAnim = null;
    }
    if (glowRef.value) {
      anime.set(glowRef.value, { opacity: 0.3, scale: 1.25 });
    }
  }
});

// Status dot pulse animation
watchEffect(() => {
  if (server.serverState.value === 'streaming' && statusDotRef.value) {
    if (!dotPulseAnim) {
      dotPulseAnim = anime({
        targets: statusDotRef.value,
        scale: [1, 1.4, 1],
        duration: 1500,
        loop: true,
        easing: 'easeInOutQuad',
      });
    }
  } else {
    if (dotPulseAnim) {
      dotPulseAnim.pause();
      dotPulseAnim = null;
    }
    if (statusDotRef.value) {
      anime.set(statusDotRef.value, { scale: 1 });
    }
  }
});

onUnmounted(() => {
  if (breatheAnim) breatheAnim.pause();
  if (dotPulseAnim) dotPulseAnim.pause();
});
</script>

<template>
  <OnboardingWizard :visible="showOnboarding" @complete="showOnboarding = false" />
  <div class="relative w-full h-screen overflow-hidden overscroll-none text-foreground bg-transparent">
    <CustomBackground />

    <!-- Pocket Mode -->
    <div v-if="pocketMode" class="absolute inset-0 flex items-center p-1.5 cursor-grab active:cursor-grabbing" @mousedown="startDrag">
      <div
        v-if="pocketPopupOpen"
        class="absolute inset-0 z-10"
        @click="pocketLayoutRef?.closePopup()"
      />

      <PocketLayout
        ref="pocketLayoutRef"
        class="flex-1 relative z-20"
        :serverState="server.serverState.value"
        :connectionMode="server.connectionMode.value"
        :serverPort="server.serverPort.value"
        :displayIp="server.displayIp.value"
        :isAutoBind="server.isAutoBind.value"
        :selectedIp="server.selectedIp.value"
        :networkInterfaces="server.networkInterfaces.value"
        :isMuted="audio.isMuted.value"
        :showMonitoringPanel="audio.showMonitoringPanel.value"
        :audioLevel="audio.audioLevel.value"
        :outputDevice="server.outputDevice.value"
        :audioMetrics="audio.audioMetrics.value"
        :popupOpen="pocketPopupOpen"
        @toggleStream="toggleStreaming"
        @selectIp="(ip, auto) => server.selectIp(ip, auto)"
        @updateMode="m => server.connectionMode.value = m"
        @updatePort="p => server.serverPort.value = p"
        @toggleMute="audio.toggleMute"
        @toggleMonitoring="audio.toggleMonitoring"
        @openSettings="isSettingsOpen = true"
        @update:popupOpen="v => pocketPopupOpen = v"
      />
    </div>

    <!-- Full Mode -->
    <div v-else class="absolute inset-0 flex flex-col p-3 gap-3">
      <!-- Header Section -->
      <div class="haze-surface rounded-2xl flex justify-between items-center px-4 py-2 flex-shrink-0 cursor-grab active:cursor-grabbing" @mousedown="startDrag">
        <div class="flex items-center gap-3">
          <!-- Window Controls (macOS: left) -->
          <div v-if="isMacOS" class="flex items-center gap-1 mr-1">
            <button @click="win.minimizeWindow()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors">
              <Minus class="w-4 h-4 text-on-surface" />
            </button>
            <button @click="win.requestClose()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-error/20 hover:text-error transition-colors">
              <X class="w-4 h-4 text-on-surface" />
            </button>
          </div>
          <div class="w-8 h-8 text-primary pointer-events-none [&>svg]:w-full [&>svg]:h-full" v-html="appIconSvg"></div>
          <div class="flex flex-col pointer-events-none select-none">
            <span class="text-sm font-extrabold text-primary">MicYou Desktop</span>
            <span class="text-[11px] text-on-surface-variant font-medium">Server</span>
          </div>
        </div>

        <div class="flex items-center gap-4">
          <!-- Network Selector -->
          <div class="relative" ref="ipMenuRef">
            <div
              class="flex items-center bg-surface-variant/30 hover:bg-surface-variant/50 transition-all duration-300 hover:-translate-y-0.5 active:scale-95 hover:shadow-sm px-3 py-1.5 rounded-lg cursor-pointer border border-white/5"
              role="button"
              @click="server.showIpMenu.value = !server.showIpMenu.value"
            >
              <Globe class="w-3.5 h-3.5 text-primary mr-2 pointer-events-none" />
              <span class="text-xs font-medium mr-1 select-none pointer-events-none">{{ server.displayIp.value }}</span>
              <ChevronDown class="w-4 h-4 text-on-surface-variant/60 pointer-events-none transition-transform" :class="{ 'rotate-180': server.showIpMenu.value }" />
            </div>

            <Transition
              enter-active-class="transition ease-out duration-150"
              enter-from-class="opacity-0 scale-95 -translate-y-1"
              enter-to-class="opacity-100 scale-100 translate-y-0"
              leave-active-class="transition ease-in duration-100"
              leave-from-class="opacity-100 scale-100 translate-y-0"
              leave-to-class="opacity-0 scale-95 -translate-y-1"
            >
              <div
                v-if="server.showIpMenu.value"
                class="absolute right-0 top-full mt-1 w-64 bg-surface border border-outline/20 rounded-xl shadow-xl z-50 overflow-hidden"
              >
                <div class="max-h-64 overflow-y-auto py-1">
                  <button
                    class="w-full flex items-center gap-3 px-3 py-2.5 hover:bg-surface-variant/50 transition-colors text-left"
                    @click="server.selectIp('', true)"
                  >
                    <div class="flex-1 min-w-0">
                      <div class="text-xs font-medium text-foreground">{{ t('app.ipSelector.allInterfaces') }}</div>
                      <div class="text-[10px] text-on-surface-variant mt-0.5">{{ t('app.ipSelector.allInterfacesDesc') }}</div>
                    </div>
                    <CheckCircle2 v-if="server.isAutoBind.value" class="w-4 h-4 text-primary flex-shrink-0" />
                  </button>

                  <button
                    v-for="iface in server.networkInterfaces.value"
                    :key="iface.ip"
                    class="w-full flex items-center gap-3 px-3 py-2.5 hover:bg-surface-variant/50 transition-colors text-left"
                    @click="server.selectIp(iface.ip, false)"
                  >
                    <div class="flex-1 min-w-0">
                      <div class="text-xs font-medium text-foreground">{{ iface.ip }}</div>
                      <div class="text-[10px] text-on-surface-variant mt-0.5 truncate">{{ iface.interface_name }}</div>
                    </div>
                    <CheckCircle2 v-if="!server.isAutoBind.value && server.selectedIp.value === iface.ip" class="w-4 h-4 text-primary flex-shrink-0" />
                  </button>
                </div>
              </div>
            </Transition>
          </div>

          <!-- Window Controls (non-macOS: right) -->
          <div v-if="!isMacOS" class="flex items-center gap-1 ml-1">
            <button @click="win.minimizeWindow()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors">
              <Minus class="w-4 h-4 text-on-surface" />
            </button>
            <button @click="win.requestClose()" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-error/20 hover:text-error transition-colors">
              <X class="w-4 h-4 text-on-surface" />
            </button>
          </div>
        </div>
      </div>

      <!-- Main Content -->
      <div class="flex flex-1 gap-3 min-h-0">
        <!-- Left Panel -->
        <div class="flex flex-col gap-3 transition-all duration-300" :class="audio.showMonitoringPanel.value ? 'w-[28%]' : 'w-[38%]'">
          <!-- Mode Card -->
          <div class="haze-surface rounded-2xl p-3 flex flex-col gap-2">
            <span class="text-xs text-on-surface-variant font-medium">{{ $t('app.connectionMode') }}</span>
            <div class="flex gap-1.5">
              <button
                @click="server.connectionMode.value = 'wifi'"
                class="flex-1 flex flex-col items-center justify-center py-2 rounded-xl transition-all duration-300 hover:-translate-y-0.5 active:scale-95"
                :class="server.connectionMode.value === 'wifi' ? 'bg-primary text-on-primary shadow-lg shadow-primary/30' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/70 hover:shadow-md'"
              >
                <Wifi class="w-4 h-4 mb-1" />
                <span class="text-[10px] font-medium">Wi-Fi</span>
              </button>
              <button
                @click="server.connectionMode.value = 'usb'"
                class="flex-1 flex flex-col items-center justify-center py-2 rounded-xl transition-all duration-300 hover:-translate-y-0.5 active:scale-95"
                :class="server.connectionMode.value === 'usb' ? 'bg-primary text-on-primary shadow-lg shadow-primary/30' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/70 hover:shadow-md'"
              >
                <Mic class="w-4 h-4 mb-1" />
                <span class="text-[10px] font-medium">USB</span>
              </button>
              <button
                @click="server.connectionMode.value = 'web'"
                class="flex-1 flex flex-col items-center justify-center py-2 rounded-xl transition-all duration-300 hover:-translate-y-0.5 active:scale-95"
                :class="server.connectionMode.value === 'web' ? 'bg-primary text-on-primary shadow-lg shadow-primary/30' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/70 hover:shadow-md'"
              >
                <Globe class="w-4 h-4 mb-1" />
                <span class="text-[10px] font-medium">Web</span>
              </button>
            </div>
          </div>

          <!-- Port Card -->
          <div v-if="server.connectionMode.value !== 'web'" class="haze-surface rounded-2xl p-3 flex flex-col gap-2">
            <span class="text-xs text-on-surface-variant font-medium">{{ $t('app.port') }}</span>
            <input
              v-model="server.serverPort.value"
              type="number"
              class="w-full bg-surface-variant/40 hover:bg-surface-variant/60 border border-white/5 rounded-xl px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:bg-surface-variant/60 transition-all duration-300"
            />
          </div>

          <!-- Web QR Card -->
          <div v-else class="haze-surface rounded-2xl p-3 flex flex-col items-center justify-center gap-2">
            <span class="text-xs text-on-surface-variant font-medium self-start">{{ $t('app.port') }}</span>
            <div v-if="server.serverState.value === 'idle'" class="w-full">
                <input v-model="server.webPort.value" type="number"
                    class="w-full bg-surface-variant/40 hover:bg-surface-variant/60 border border-white/5 rounded-xl px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:bg-surface-variant/60 transition-all duration-300" />
            </div>
            <button v-if="server.serverState.value !== 'idle'" @click="server.showQrDialog.value = true"
                class="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl bg-primary/10 text-primary text-sm font-medium hover:bg-primary/20 active:scale-[0.98] transition-all">
              <QrCodeIcon class="w-4 h-4" />
              <span>{{ server.qrDataUrl.value ? $t('app.web.scanToConnect') : $t('app.status.connectingDesc', { port: server.webPort.value }) }}</span>
            </button>
            <span v-if="server.serverState.value !== 'idle' && server.webClientCount.value > 0" class="text-xs text-primary font-medium">
              {{ $t('app.web.clientsConnected', { count: server.webClientCount.value }) }}
            </span>
          </div>

          <!-- Status Card -->
          <div class="haze-surface rounded-2xl p-4 flex-1 flex flex-col items-center justify-center text-center gap-3 group transition-all duration-300 hover:shadow-md hover:-translate-y-0.5">
            <div class="w-12 h-12 rounded-full flex items-center justify-center transition-all duration-500 group-hover:scale-110"
                 :class="server.serverState.value === 'streaming' ? 'bg-primary/20 text-primary' : (server.serverState.value === 'starting' ? 'bg-secondary/20 text-secondary' : (server.serverState.value === 'connecting' ? 'bg-tertiary/20 text-tertiary' : 'bg-surface-variant/50 text-on-surface-variant'))">
              <CheckCircle2 v-if="server.serverState.value === 'streaming'" class="w-6 h-6 animate-pulse" />
              <Loader2 v-else-if="server.serverState.value === 'starting'" class="w-6 h-6 animate-spin" />
              <RadioTower v-else class="w-6 h-6 transition-transform duration-500 group-hover:rotate-12" :class="{ 'animate-spin-slow': server.serverState.value === 'connecting' }" />
            </div>
            <div>
              <h3 class="text-sm font-bold">{{ server.serverState.value === 'streaming' ? $t('app.status.streaming') : (server.serverState.value === 'connecting' ? $t('app.status.connecting') : (server.serverState.value === 'starting' ? $t('app.status.starting') : $t('app.status.ready'))) }}</h3>
              <p class="text-xs text-on-surface-variant mt-1 max-w-[200px] mx-auto">
                {{ server.statusDescription.value }}
              </p>
            </div>
          </div>
        </div>

        <!-- Center Panel -->
        <div class="haze-surface rounded-2xl flex flex-col items-center justify-center relative overflow-hidden group transition-all duration-300" :class="audio.showMonitoringPanel.value ? 'w-[44%]' : 'w-[62%]'">
          <!-- Central Visualizer & Action Button -->
          <div class="relative w-64 h-64 flex items-center justify-center transition-transform duration-200 absolute-center"
               :style="{ transform: `scale(${server.serverState.value === 'streaming' ? micScale : 1})` }">
            <AudioRing v-if="server.serverState.value === 'streaming'" :level="audio.audioLevel.value">
              <!-- Central Button When Streaming -->
              <div class="relative flex items-center justify-center">
                <div ref="glowRef" class="absolute inset-0 bg-error/30 rounded-full blur-md scale-125"></div>
                <button ref="centralBtnRef" @click="toggleStreaming" @mouseenter="onCentralBtnHover" @mouseleave="onCentralBtnLeave" class="relative z-10 w-[72px] h-[72px] rounded-full bg-error flex items-center justify-center shadow-lg hover:scale-95 transition-all duration-300 group-hover:bg-error/90 border border-white/10 hover:shadow-lg hover:shadow-error/30">
                  <Unlink class="w-7 h-7 text-on-error" stroke-width="2.5" />
                </button>
              </div>
            </AudioRing>

            <div v-else class="relative w-full h-full flex items-center justify-center">
              <button ref="centralBtnRef" @click="toggleStreaming" @mouseenter="onCentralBtnHover" @mouseleave="onCentralBtnLeave" class="relative z-10 w-16 h-16 rounded-full flex items-center justify-center shadow-xl transition-all duration-300 hover:-translate-y-1 active:scale-95 border border-white/10 hover:shadow-2xl hover:shadow-primary/40"
                      :class="server.serverState.value === 'starting' ? 'bg-secondary shadow-secondary/30 text-on-secondary' : (server.serverState.value === 'connecting' ? 'bg-tertiary shadow-tertiary/30 text-on-tertiary' : 'bg-primary shadow-primary/30 text-on-primary')">
                <RefreshCw v-if="server.serverState.value === 'connecting'" class="w-7 h-7 animate-spin-slow" stroke-width="2.5" />
                <Loader2 v-else-if="server.serverState.value === 'starting'" class="w-7 h-7 animate-spin" stroke-width="2.5" />
                <Link v-else class="w-7 h-7" stroke-width="2.5" />
              </button>
            </div>
          </div>
        </div>

        <!-- Right Panel (Monitoring) -->
        <div v-if="audio.showMonitoringPanel.value" class="w-[28%] transition-all duration-300 min-w-0">
          <MonitoringPanel :serverState="server.serverState.value" :audioLevel="audio.audioLevel.value" :metrics="audio.audioMetrics.value" />
        </div>
      </div>

      <!-- Bottom Bar -->
      <div class="haze-surface rounded-2xl p-2 flex justify-between items-center flex-shrink-0">
        <div class="flex items-center px-3">
          <div ref="statusDotRef" class="w-2 h-2 rounded-full mr-2" :class="server.serverState.value === 'streaming' ? 'bg-primary shadow-[0_0_8px_hsl(var(--primary))]' : (server.serverState.value === 'starting' ? 'bg-secondary animate-pulse shadow-[0_0_8px_hsl(var(--secondary))]' : (server.serverState.value === 'connecting' ? 'bg-tertiary animate-pulse shadow-[0_0_8px_hsl(var(--tertiary))]' : 'bg-on-surface-variant'))"></div>
          <span class="text-xs font-bold uppercase tracking-wider text-on-surface-variant transition-colors duration-300">{{ server.serverState.value === 'streaming' ? $t('app.status.stateStreaming') : (server.serverState.value === 'connecting' ? $t('app.status.stateConnecting') : (server.serverState.value === 'starting' ? $t('app.status.stateStarting') : $t('app.status.stateIdle'))) }}</span>
        </div>

        <div class="flex items-center gap-2 pr-1">
          <button
            @click="audio.toggleMute()"
            class="w-10 h-10 rounded-full flex items-center justify-center transition-all duration-300 hover:scale-110 active:scale-90"
            :class="audio.isMuted.value ? 'bg-error/20 text-error hover:bg-error/30' : 'bg-surface-variant/40 hover:bg-surface-variant text-on-surface-variant'"
            :title="audio.isMuted.value ? $t('app.status.unmute') : $t('app.status.mute')"
          >
            <VolumeX v-if="audio.isMuted.value" class="w-4 h-4" />
            <Volume2 v-else class="w-4 h-4" />
          </button>

          <button @click="audio.showMonitoringPanel.value = !audio.showMonitoringPanel.value" class="w-10 h-10 rounded-full flex items-center justify-center transition-all duration-300 hover:scale-110 active:scale-90" :class="audio.showMonitoringPanel.value ? 'bg-primary/20 text-primary hover:bg-primary/30' : 'bg-surface-variant/40 hover:bg-surface-variant text-on-surface-variant'">
            <MonitoringIcon class="w-4 h-4" />
          </button>

          <button @click="isSettingsOpen = true" class="w-10 h-10 rounded-full bg-surface-variant/40 hover:bg-surface-variant flex items-center justify-center transition-all duration-300 hover:scale-110 active:scale-90">
            <Settings class="w-4 h-4 text-on-surface-variant" />
          </button>
        </div>
      </div>
    </div>

    <SettingsDialog
      :isOpen="isSettingsOpen"
      @close="isSettingsOpen = false"
      @updateDevice="dev => server.outputDevice.value = dev"
    />

    <UdpWarningDialog
      :show="audio.showUdpWarning.value"
      :port="Number(server.serverPort.value) + 1"
      @close="audio.showUdpWarning.value = false"
    />

    <CloseConfirmDialog v-model:show="win.showCloseConfirm.value" @select="win.handleCloseSelect" />

    <ConnectionErrorDialog
      :show="server.showErrorDialog.value"
      :details="server.errorDetails.value"
      @dismiss="server.showErrorDialog.value = false"
      @retry="server.showErrorDialog.value = false; toggleStreaming()"
    />

    <QrCodeDialog
      :show="server.showQrDialog.value"
      :qr-data-url="server.qrDataUrl.value"
      :web-url="server.webUrl.value"
      :client-count="server.webClientCount.value"
      @dismiss="server.showQrDialog.value = false"
    />

    <!-- IP Switch Confirmation Dialog -->
    <Transition
      enter-active-class="transition ease-out duration-200"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition ease-in duration-150"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div v-if="server.showIpSwitchConfirm.value" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
        <div class="bg-surface rounded-2xl shadow-2xl border border-outline/10 p-6 w-80">
          <h3 class="text-sm font-bold text-foreground mb-2">{{ t('app.ipSelector.switchConfirmTitle') }}</h3>
          <p class="text-xs text-on-surface-variant mb-5">{{ t('app.ipSelector.switchConfirmMessage') }}</p>
          <div class="flex justify-end gap-2">
            <button
              class="px-4 py-2 text-xs font-medium text-on-surface-variant hover:bg-surface-variant/50 rounded-lg transition-colors"
              @click="server.showIpSwitchConfirm.value = false"
            >
              {{ t('app.ipSelector.cancel') }}
            </button>
            <button
              class="px-4 py-2 text-xs font-medium text-on-primary bg-primary hover:bg-primary/90 rounded-lg transition-colors"
              @click="server.confirmIpSwitch()"
            >
              {{ t('app.ipSelector.continue') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- USB Device Selector Dialog -->
    <Transition
      enter-active-class="transition ease-out duration-200"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition ease-in duration-150"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div v-if="server.showDeviceSelector.value" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
        <div class="bg-surface rounded-2xl shadow-2xl border border-outline/10 p-6 w-96 max-h-[80vh] flex flex-col">
          <h3 class="text-sm font-bold text-foreground mb-2">{{ t('app.deviceSelector.title') }}</h3>
          <p class="text-xs text-on-surface-variant mb-4">{{ t('app.deviceSelector.desc') }}</p>

          <div class="flex-1 overflow-y-auto space-y-2 mb-4">
            <button
              v-for="device in server.adbDevices.value"
              :key="device.serial"
              class="w-full text-left px-4 py-3 rounded-xl bg-surface-variant/30 hover:bg-surface-variant/60 border border-outline/10 hover:border-primary/30 transition-all duration-200 group"
              @click="server.selectAdbDevice(device.serial)"
            >
              <div class="flex items-center justify-between">
                <div class="flex-1 min-w-0">
                  <div class="text-sm font-medium text-foreground truncate">
                    {{ device.description || device.serial }}
                  </div>
                  <div class="text-xs text-on-surface-variant mt-0.5">
                    {{ device.serial }}
                  </div>
                </div>
                <div class="ml-3 flex-shrink-0">
                  <div class="w-2 h-2 rounded-full bg-success animate-pulse"></div>
                </div>
              </div>
            </button>
          </div>

          <div class="flex justify-end">
            <button
              class="px-4 py-2 text-xs font-medium text-on-surface-variant hover:bg-surface-variant/50 rounded-lg transition-colors"
              @click="server.cancelDeviceSelection()"
            >
              {{ t('app.deviceSelector.cancel') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>
