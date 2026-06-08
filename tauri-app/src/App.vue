<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watchEffect } from 'vue';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow, LogicalSize } from '@tauri-apps/api/window';
import { useStorage } from '@vueuse/core';

// Icons
import { Mic, Wifi, RadioTower, Globe, ChevronDown, CheckCircle2, Settings, Link, Unlink, RefreshCw, Scan, ActivitySquare as MonitoringIcon, X, Minus } from 'lucide-vue-next';
import { useI18n } from 'vue-i18n';
import CustomBackground from './components/CustomBackground.vue';
import SettingsDialog from './components/SettingsDialog.vue';
import AudioRing from './components/AudioRing.vue';
import MonitoringPanel from './components/MonitoringPanel.vue';
import UdpWarningDialog from './components/UdpWarningDialog.vue';
import PocketLayout from './components/PocketLayout.vue';

const serverState = ref<'idle' | 'connecting' | 'streaming'>('idle');
const connectionMode = ref<'wifi' | 'usb' | 'web'>('wifi');
const serverPort = ref(6000);
const audioLevel = ref(0);
const networkInfo = ref<{ ips: string[], port: number } | null>(null);
const selectedIp = ref<string>('0.0.0.0');
const networkInterfaces = ref<{ ip: string, interface_name: string }[]>([]);
const showIpMenu = ref(false);
const showIpSwitchConfirm = ref(false);
const pendingIp = ref('');
const pendingAutoSelect = ref(false);
const isAutoBind = ref(true);

// The IP shown in the topbar: when auto-bind, show the preferred (first) IP; otherwise show the selected one
const displayIp = computed(() => {
  if (isAutoBind.value) {
    return networkInterfaces.value.length > 0 ? networkInterfaces.value[0].ip : '...';
  }
  return selectedIp.value;
});

const isSettingsOpen = ref(false);
const showMonitoringPanel = ref(false);
const showUdpWarning = ref(false);
const audioMetrics = ref<any>(null);
const outputDevice = ref<string>(localStorage.getItem('micyou_output_device') || '');
const isMuted = ref(false);

const pocketMode = useStorage('micyou_pocket_mode', false);
const pocketPopupOpen = ref(false);
const pocketLayoutRef = ref<InstanceType<typeof PocketLayout> | null>(null);
const { t } = useI18n();

// Window Management
const appWindow = getCurrentWindow();
const minimizeWindow = () => appWindow.minimize();
const closeWindow = () => appWindow.close();

// Resize window when pocket mode changes
watchEffect(async () => {
  const isPocket = pocketMode.value;
  // Skip resize if settings dialog is open in pocket mode
  if (isPocket && isSettingsOpen.value) return;
  try {
    if (isPocket) {
      await appWindow.setSize(new LogicalSize(420, 52));
    } else {
      await appWindow.setSize(new LogicalSize(800, 600));
    }
  } catch (e) {
    console.error('Failed to resize window:', e);
  }
});

// Resize for settings dialog in pocket mode
watchEffect(async () => {
  if (pocketMode.value && isSettingsOpen.value) {
    try {
      await appWindow.setSize(new LogicalSize(800, 600));
    } catch (e) {
      console.error('Failed to resize window for settings:', e);
    }
  }
});

// Global Theme and UI Style Management
const themeColor = useStorage('micyou_theme_color', 'theme-blue');
const uiStyle = useStorage('micyou_ui_style', 'style-glass');

const customH = useStorage('micyou_custom_h', 215);
const customS = useStorage('micyou_custom_s', 35);
const customL = useStorage('micyou_custom_l', 55);

// User Custom CSS
const customCss = useStorage('micyou_custom_css', '');

watchEffect(() => {
  if (typeof document !== 'undefined') {
    let userStyle = document.getElementById('micyou-user-custom-css');
    if (!userStyle) {
      userStyle = document.createElement('style');
      userStyle.id = 'micyou-user-custom-css';
      document.head.appendChild(userStyle);
    }
    userStyle.innerHTML = customCss.value || '';
  }
});

watchEffect(() => {
  if (typeof document !== 'undefined') {
    const themes = ['theme-blue', 'theme-green', 'theme-rose', 'theme-purple', 'theme-orange', 'theme-amber', 'theme-teal', 'theme-cyan', 'theme-custom'];
    document.documentElement.classList.remove(...themes, 'style-default', 'style-glass');
    
    if (themeColor.value) document.documentElement.classList.add(themeColor.value);
    if (uiStyle.value) {
      document.documentElement.classList.add(uiStyle.value);
      
      // UI style applied via CSS class
    }
    
    let dynamicStyle = document.getElementById('micyou-custom-theme');
    if (!dynamicStyle) {
      dynamicStyle = document.createElement('style');
      dynamicStyle.id = 'micyou-custom-theme';
      document.head.appendChild(dynamicStyle);
    }

    if (themeColor.value === 'theme-custom') {
      const h = customH.value;
      const s = customS.value;
      const l = customL.value;
      const lDark = Math.min(l + 10, 80); // Lighter for dark mode
      
      dynamicStyle.innerHTML = `
        :root, .theme-custom {
          --background: ${h} 15% 96%;
          --foreground: ${h} 15% 25%;
          --surface: ${h} 15% 98%;
          --on-surface: ${h} 15% 25%;
          --surface-bright: ${h} 15% 98%;
          --surface-container: ${h} 15% 92%;
          --surface-container-low: ${h} 15% 94%;
          --surface-variant: ${h} 15% 88%;
          --on-surface-variant: ${h} 15% 45%;
          --outline: ${h} 15% 80%;
          --border: ${h} 15% 80%;
          
          --primary: ${h} ${s}% ${l}%;
          --on-primary: ${h} ${s}% 92%;
          --primary-container: ${h} ${s}% 85%;
          --on-primary-container: ${h} ${s}% 25%;
          
          --secondary: ${h} 20% 90%;
          --on-secondary: ${h} 20% 25%;
          --secondary-container: ${h} 20% 90%;
          --on-secondary-container: ${h} 20% 25%;
          --tertiary: ${h} 20% 90%;
          --on-tertiary: ${h} 20% 25%;
          --error: 0 40% 55%;
          --on-error: 0 40% 92%;
        }

        .dark.theme-custom, .theme-custom .dark {
          --background: ${h} 15% 8%;
          --foreground: ${h} 15% 85%;
          --surface: ${h} 15% 10%;
          --on-surface: ${h} 15% 85%;
          --surface-bright: ${h} 15% 14%;
          --surface-container: ${h} 15% 16%;
          --surface-container-low: ${h} 15% 12%;
          --surface-variant: ${h} 15% 22%;
          --on-surface-variant: ${h} 15% 60%;
          --outline: ${h} 15% 20%;
          --border: ${h} 15% 20%;
          
          --primary: ${h} ${s}% ${lDark}%;
          --on-primary: ${h} ${s}% 20%;
          --primary-container: ${h} ${s}% 25%;
          --on-primary-container: ${h} ${s}% 85%;
          
          --secondary: ${h} 20% 16%;
          --on-secondary: ${h} 20% 85%;
          --secondary-container: ${h} 20% 16%;
          --on-secondary-container: ${h} 20% 85%;
          --tertiary: ${h} 20% 16%;
          --on-tertiary: ${h} 20% 85%;
          --error: 0 40% 65%;
          --on-error: 0 40% 20%;
        }
      `;
    } else {
      dynamicStyle.innerHTML = '';
    }
  }
});



let unlistenAudioLevel: UnlistenFn | null = null;
let unlistenDeviceConnected: UnlistenFn | null = null;
let unlistenDeviceDisconnected: UnlistenFn | null = null;
let unlistenAudioMetrics: UnlistenFn | null = null;
let unlistenUdpWarning: UnlistenFn | null = null;

onMounted(async () => {

  try {
    networkInfo.value = await invoke<{ ips: string[], port: number }>('get_network_info');
    if (networkInfo.value && networkInfo.value.ips.length > 0) {
      selectedIp.value = networkInfo.value.ips[0];
    }
  } catch (e) {
    console.error("Failed to get network info:", e);
  }

  try {
    networkInterfaces.value = await invoke<{ ip: string, interface_name: string }[]>('get_network_interfaces');
  } catch (e) {
    console.error("Failed to get network interfaces:", e);
  }

  unlistenAudioLevel = await listen<number>('audio-level', (event) => {
    audioLevel.value = event.payload;
  });

  unlistenDeviceConnected = await listen('device-connected', () => {
    serverState.value = 'streaming';
  });

  unlistenDeviceDisconnected = await listen('device-disconnected', () => {
    if (serverState.value === 'streaming') {
      serverState.value = 'connecting'; // Go back to waiting for device
      audioLevel.value = 0;
    }
  });

  unlistenAudioMetrics = await listen<any>('audio-metrics', (event) => {
    audioMetrics.value = event.payload;
  });

  unlistenUdpWarning = await listen('udp_audio_warning', () => {
    showUdpWarning.value = true;
  });
});

onUnmounted(() => {
  if (unlistenAudioLevel) unlistenAudioLevel();
  if (unlistenDeviceConnected) unlistenDeviceConnected();
  if (unlistenDeviceDisconnected) unlistenDeviceDisconnected();
  if (unlistenAudioMetrics) unlistenAudioMetrics();
  if (unlistenUdpWarning) unlistenUdpWarning();
});

const toggleStreaming = async () => {
  if (serverState.value !== 'idle') {
    try {
      await invoke('stop_server');
      serverState.value = 'idle';
      audioLevel.value = 0;
    } catch (e) {
      console.error(e);
    }
  } else {
    try {
      const bindAddress = isAutoBind.value ? null : selectedIp.value;
      await invoke('start_server', {
        port: Number(serverPort.value),
        mode: connectionMode.value,
        bindAddress: bindAddress,
        outputDevice: outputDevice.value || null
      });
      serverState.value = 'connecting';
      if (connectionMode.value === 'usb') {
        await invoke('enable_usb_mode', { port: Number(serverPort.value) });
      }
    } catch (e) {
      console.error(e);
    }
  }
};

const selectIp = (ip: string, autoSelect: boolean) => {
  // Already selected?
  if (autoSelect && isAutoBind.value) {
    showIpMenu.value = false;
    return;
  }
  if (!autoSelect && !isAutoBind.value && selectedIp.value === ip) {
    showIpMenu.value = false;
    return;
  }
  if (serverState.value === 'streaming' || serverState.value === 'connecting') {
    pendingIp.value = ip;
    pendingAutoSelect.value = autoSelect;
    showIpSwitchConfirm.value = true;
    showIpMenu.value = false;
  } else {
    applyIpSelection(ip, autoSelect);
    showIpMenu.value = false;
  }
};

const applyIpSelection = (ip: string, autoSelect: boolean) => {
  if (autoSelect) {
    isAutoBind.value = true;
    selectedIp.value = '0.0.0.0';
  } else {
    isAutoBind.value = false;
    selectedIp.value = ip;
  }
};

const confirmIpSwitch = async () => {
  applyIpSelection(pendingIp.value, pendingAutoSelect.value);
  showIpSwitchConfirm.value = false;
  // Restart server if streaming
  if (serverState.value === 'streaming' || serverState.value === 'connecting') {
    try {
      await invoke('stop_server');
      serverState.value = 'idle';
      audioLevel.value = 0;
      const bindAddress = isAutoBind.value ? null : selectedIp.value;
      await invoke('start_server', {
        port: Number(serverPort.value),
        mode: connectionMode.value,
        bindAddress: bindAddress,
        outputDevice: outputDevice.value || null
      });
      serverState.value = 'connecting';
      if (connectionMode.value === 'usb') {
        await invoke('enable_usb_mode', { port: Number(serverPort.value) });
      }
    } catch (e) {
      console.error(e);
    }
  }
};

const toggleMute = () => {
  isMuted.value = !isMuted.value;
};

const toggleMonitoring = () => {
  showMonitoringPanel.value = !showMonitoringPanel.value;
};

const micScale = computed(() => {
  return 1 + (audioLevel.value / 100) * 0.5;
});
</script>

<template>
  <div class="relative w-full h-screen overflow-hidden text-foreground bg-transparent">
    <CustomBackground />

    <!-- Pocket Mode -->
    <div v-if="pocketMode" class="absolute inset-0 flex items-center p-1.5" data-tauri-drag-region>
      <!-- Backdrop when popup is open -->
      <div
        v-if="pocketPopupOpen"
        class="absolute inset-0 z-10"
        @click="pocketLayoutRef?.closePopup()"
      />

      <PocketLayout
        ref="pocketLayoutRef"
        class="flex-1 relative z-20"
        :serverState="serverState"
        :connectionMode="connectionMode"
        :serverPort="serverPort"
        :displayIp="displayIp"
        :isAutoBind="isAutoBind"
        :selectedIp="selectedIp"
        :networkInterfaces="networkInterfaces"
        :isMuted="isMuted"
        :showMonitoringPanel="showMonitoringPanel"
        :audioLevel="audioLevel"
        :outputDevice="outputDevice"
        :audioMetrics="audioMetrics"
        :popupOpen="pocketPopupOpen"
        @toggleStream="toggleStreaming"
        @selectIp="(ip, auto) => selectIp(ip, auto)"
        @updateMode="m => connectionMode = m"
        @updatePort="p => serverPort = p"
        @toggleMute="toggleMute"
        @toggleMonitoring="toggleMonitoring"
        @openSettings="isSettingsOpen = true"
        @update:popupOpen="v => pocketPopupOpen = v"
      />
    </div>

    <!-- Full Mode -->
    <div v-else class="absolute inset-0 flex flex-col p-3 gap-3">
      <!-- Header Section -->
      <div data-tauri-drag-region class="haze-surface rounded-2xl flex justify-between items-center px-4 py-2 flex-shrink-0 cursor-grab active:cursor-grabbing">
        <div data-tauri-drag-region class="flex items-center gap-3">
          <div data-tauri-drag-region class="w-9 h-9 rounded-lg bg-primary-container flex items-center justify-center shadow-sm border border-primary/20 pointer-events-none">
            <RadioTower class="w-5 h-5 text-primary" />
          </div>
          <div class="flex flex-col">
            <span class="text-sm font-extrabold text-primary">MicYou Desktop</span>
            <span class="text-[11px] text-on-surface-variant font-medium">Server</span>
          </div>
        </div>

        <div class="flex items-center gap-4">
          <!-- Network Selector -->
          <div class="relative">
            <div
              class="flex items-center bg-surface-variant/30 hover:bg-surface-variant/50 transition-colors px-3 py-1.5 rounded-lg cursor-pointer border border-white/5"
              @click="showIpMenu = !showIpMenu"
            >
              <Globe class="w-3.5 h-3.5 text-primary mr-2 pointer-events-none" />
              <span class="text-xs font-medium mr-1 select-none pointer-events-none">{{ displayIp }}</span>
              <ChevronDown class="w-4 h-4 text-on-surface-variant/60 pointer-events-none transition-transform" :class="{ 'rotate-180': showIpMenu }" />
            </div>

            <!-- Invisible backdrop to close dropdown -->
            <div v-if="showIpMenu" class="fixed inset-0 z-40" @click="showIpMenu = false" />

            <!-- IP Dropdown Menu -->
            <Transition
              enter-active-class="transition ease-out duration-150"
              enter-from-class="opacity-0 scale-95 -translate-y-1"
              enter-to-class="opacity-100 scale-100 translate-y-0"
              leave-active-class="transition ease-in duration-100"
              leave-from-class="opacity-100 scale-100 translate-y-0"
              leave-to-class="opacity-0 scale-95 -translate-y-1"
            >
              <div
                v-if="showIpMenu"
                class="absolute right-0 top-full mt-1 w-64 bg-surface border border-outline/20 rounded-xl shadow-xl z-50 overflow-hidden"
              >
                <div class="max-h-64 overflow-y-auto py-1">
                  <!-- All Interfaces option -->
                  <button
                    class="w-full flex items-center gap-3 px-3 py-2.5 hover:bg-surface-variant/50 transition-colors text-left"
                    @click="selectIp('', true)"
                  >
                    <div class="flex-1 min-w-0">
                      <div class="text-xs font-medium text-foreground">{{ t('app.ipSelector.allInterfaces') }}</div>
                      <div class="text-[10px] text-on-surface-variant mt-0.5">{{ t('app.ipSelector.allInterfacesDesc') }}</div>
                    </div>
                    <CheckCircle2 v-if="isAutoBind" class="w-4 h-4 text-primary flex-shrink-0" />
                  </button>

                  <!-- Individual interfaces -->
                  <button
                    v-for="iface in networkInterfaces"
                    :key="iface.ip"
                    class="w-full flex items-center gap-3 px-3 py-2.5 hover:bg-surface-variant/50 transition-colors text-left"
                    @click="selectIp(iface.ip, false)"
                  >
                    <div class="flex-1 min-w-0">
                      <div class="text-xs font-medium text-foreground">{{ iface.ip }}</div>
                      <div class="text-[10px] text-on-surface-variant mt-0.5 truncate">{{ iface.interface_name }}</div>
                    </div>
                    <CheckCircle2 v-if="!isAutoBind && selectedIp === iface.ip" class="w-4 h-4 text-primary flex-shrink-0" />
                  </button>
                </div>
              </div>
            </Transition>
          </div>

          <!-- Window Controls -->
          <div class="flex items-center gap-1 ml-1">
            <button @click="minimizeWindow" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors">
              <Minus class="w-4 h-4 text-on-surface" />
            </button>
            <button @click="closeWindow" class="w-7 h-7 flex items-center justify-center rounded-full hover:bg-error/20 hover:text-error transition-colors">
              <X class="w-4 h-4 text-on-surface" />
            </button>
          </div>
        </div>
      </div>

      <!-- Main Content -->
      <div class="flex flex-1 gap-3 min-h-0">
        <!-- Left Panel -->
        <div class="flex flex-col gap-3 transition-all duration-300" :class="showMonitoringPanel ? 'w-[28%]' : 'w-[38%]'">
          <!-- Mode Card -->
          <div class="haze-surface rounded-2xl p-3 flex flex-col gap-2">
            <span class="text-xs text-on-surface-variant font-medium">{{ $t('app.connectionMode') }}</span>
            <div class="flex gap-1.5">
              <button 
                @click="connectionMode = 'wifi'"
                class="flex-1 flex flex-col items-center justify-center py-2 rounded-xl transition-colors duration-200"
                :class="connectionMode === 'wifi' ? 'bg-primary text-on-primary' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/60'"
              >
                <Wifi class="w-4 h-4 mb-1" />
                <span class="text-[10px] font-medium">Wi-Fi</span>
              </button>
              <button 
                @click="connectionMode = 'usb'"
                class="flex-1 flex flex-col items-center justify-center py-2 rounded-xl transition-colors duration-200"
                :class="connectionMode === 'usb' ? 'bg-primary text-on-primary' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/60'"
              >
                <Mic class="w-4 h-4 mb-1" />
                <span class="text-[10px] font-medium">USB</span>
              </button>
              <button 
                @click="connectionMode = 'web'"
                class="flex-1 flex flex-col items-center justify-center py-2 rounded-xl transition-colors duration-200"
                :class="connectionMode === 'web' ? 'bg-primary text-on-primary' : 'bg-surface-variant/40 text-on-surface-variant hover:bg-surface-variant/60'"
              >
                <Globe class="w-4 h-4 mb-1" />
                <span class="text-[10px] font-medium">Web</span>
              </button>
            </div>
          </div>

          <!-- Port Card -->
          <div v-if="connectionMode !== 'web'" class="haze-surface rounded-2xl p-3 flex flex-col gap-2">
            <span class="text-xs text-on-surface-variant font-medium">{{ $t('app.port') }}</span>
            <input 
              v-model="serverPort"
              type="number" 
              class="w-full bg-surface-variant/40 border border-white/5 rounded-xl px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary transition-all"
            />
          </div>

          <!-- Web QR Card -->
          <div v-else class="haze-surface rounded-2xl p-3 flex flex-col items-center justify-center gap-2">
            <span class="text-xs text-on-surface-variant font-medium self-start">Web Connection</span>
            <div class="w-24 h-24 bg-white rounded-xl flex items-center justify-center border border-surface-variant/50 shadow-inner">
              <Scan class="w-10 h-10 text-surface-variant" />
            </div>
            <span class="text-[10px] text-on-surface-variant text-center leading-tight">Scan to connect<br/>(Coming Soon)</span>
          </div>

          <!-- Status Card -->
          <div class="haze-surface rounded-2xl p-4 flex-1 flex flex-col items-center justify-center text-center gap-3">
            <div class="w-12 h-12 rounded-full flex items-center justify-center transition-colors duration-500" 
                 :class="serverState === 'streaming' ? 'bg-primary/20 text-primary' : (serverState === 'connecting' ? 'bg-tertiary/20 text-tertiary' : 'bg-surface-variant/50 text-on-surface-variant')">
              <CheckCircle2 v-if="serverState === 'streaming'" class="w-6 h-6 animate-pulse" />
              <RadioTower v-else class="w-6 h-6" :class="{ 'animate-spin-slow': serverState === 'connecting' }" />
            </div>
            <div>
              <h3 class="text-sm font-bold">{{ serverState === 'streaming' ? $t('app.status.streaming') : (serverState === 'connecting' ? $t('app.status.connecting') : $t('app.status.ready')) }}</h3>
              <p class="text-xs text-on-surface-variant mt-1 max-w-[200px] mx-auto">
                {{ serverState === 'streaming' ? $t('app.status.streamingDesc') : (serverState === 'connecting' ? $t('app.status.connectingDesc', { port: serverPort }) : $t('app.status.readyDesc')) }}
              </p>
            </div>
          </div>
        </div>

        <!-- Center Panel -->
        <div class="haze-surface rounded-2xl flex flex-col items-center justify-center relative overflow-hidden group transition-all duration-300" :class="showMonitoringPanel ? 'w-[44%]' : 'w-[62%]'">
          <!-- Central Visualizer & Action Button -->
          <div class="relative w-64 h-64 flex items-center justify-center transition-transform duration-200 absolute-center"
               :style="{ transform: `scale(${serverState === 'streaming' ? micScale : 1})` }">
            <AudioRing v-if="serverState === 'streaming'" :level="audioLevel">
              <!-- Central Button When Streaming -->
              <div class="relative flex items-center justify-center">
                <!-- Breathing Glow Background -->
                <div class="absolute inset-0 bg-error/30 rounded-full blur-md animate-pulse scale-125"></div>
                <!-- Button -->
                <button @click="toggleStreaming" class="relative z-10 w-[72px] h-[72px] rounded-full bg-error flex items-center justify-center shadow-lg hover:scale-95 transition-all duration-300 group-hover:bg-error/90 border border-white/10">
                  <Unlink class="w-7 h-7 text-on-error" stroke-width="2.5" />
                </button>
              </div>
            </AudioRing>
            
            <div v-else class="relative w-full h-full flex items-center justify-center">
              <!-- Central Button When Not Streaming -->
              <button @click="toggleStreaming" class="relative z-10 w-16 h-16 rounded-full flex items-center justify-center shadow-lg transition-all duration-300 hover:scale-95 border border-white/5"
                      :class="serverState === 'connecting' ? 'bg-tertiary shadow-tertiary/20 text-on-tertiary' : 'bg-primary shadow-primary/20 text-on-primary'">
                <RefreshCw v-if="serverState === 'connecting'" class="w-7 h-7 animate-spin-slow" stroke-width="2.5" />
                <Link v-else class="w-7 h-7" stroke-width="2.5" />
              </button>
            </div>
          </div>
          
          <!-- Status Text (Positioned Absolutely to avoid pushing button off-center) -->
          <div class="absolute top-[calc(50%+4rem)] flex items-center gap-2">
            <span class="text-sm font-bold tracking-wider" 
                  :class="serverState === 'streaming' ? 'text-error' : (serverState === 'connecting' ? 'text-tertiary' : 'text-primary')">
              {{ serverState === 'streaming' ? $t('app.status.stateStreaming') : (serverState === 'connecting' ? $t('app.status.stateConnecting') : 'CLICK TO START') }}
            </span>
            <div v-if="serverState === 'streaming'" class="bg-error px-1.5 py-0.5 rounded text-[10px] font-black text-on-error uppercase tracking-widest shadow-sm">
              LIVE
            </div>
          </div>
        </div>

        <!-- Right Panel (Monitoring) -->
        <div v-if="showMonitoringPanel" class="w-[28%] transition-all duration-300 min-w-0">
          <MonitoringPanel :serverState="serverState" :audioLevel="audioLevel" :metrics="audioMetrics" />
        </div>
      </div>

      <!-- Bottom Bar -->
      <div class="haze-surface rounded-2xl p-2 flex justify-between items-center flex-shrink-0">
        <div class="flex items-center px-3">
          <div class="w-2 h-2 rounded-full mr-2" :class="serverState === 'streaming' ? 'bg-primary animate-pulse shadow-[0_0_8px_hsl(var(--primary))]' : (serverState === 'connecting' ? 'bg-tertiary animate-pulse shadow-[0_0_8px_hsl(var(--tertiary))]' : 'bg-on-surface-variant')"></div>
          <span class="text-xs font-bold uppercase tracking-wider text-on-surface-variant">{{ serverState === 'streaming' ? $t('app.status.stateStreaming') : (serverState === 'connecting' ? $t('app.status.stateConnecting') : $t('app.status.stateIdle')) }}</span>
        </div>
        
        <div class="flex items-center gap-2 pr-1">
          <button @click="showMonitoringPanel = !showMonitoringPanel" class="w-10 h-10 rounded-full flex items-center justify-center transition-colors" :class="showMonitoringPanel ? 'bg-primary/20 text-primary' : 'bg-surface-variant/40 hover:bg-surface-variant/80 text-on-surface-variant'">
            <MonitoringIcon class="w-4 h-4" />
          </button>

          <button @click="isSettingsOpen = true" class="w-10 h-10 rounded-full bg-surface-variant/40 hover:bg-surface-variant/80 flex items-center justify-center transition-colors">
            <Settings class="w-4 h-4 text-on-surface-variant" />
          </button>
        </div>
      </div>
    </div>
    
    <SettingsDialog 
      :isOpen="isSettingsOpen" 
      @close="isSettingsOpen = false" 
      @updateDevice="dev => outputDevice = dev" 
    />

    <UdpWarningDialog
      :show="showUdpWarning"
      :port="Number(serverPort) + 1"
      @close="showUdpWarning = false"
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
      <div v-if="showIpSwitchConfirm" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
        <div class="bg-surface rounded-2xl shadow-2xl border border-outline/10 p-6 w-80">
          <h3 class="text-sm font-bold text-foreground mb-2">{{ t('app.ipSelector.switchConfirmTitle') }}</h3>
          <p class="text-xs text-on-surface-variant mb-5">{{ t('app.ipSelector.switchConfirmMessage') }}</p>
          <div class="flex justify-end gap-2">
            <button
              class="px-4 py-2 text-xs font-medium text-on-surface-variant hover:bg-surface-variant/50 rounded-lg transition-colors"
              @click="showIpSwitchConfirm = false"
            >
              {{ t('app.ipSelector.cancel') }}
            </button>
            <button
              class="px-4 py-2 text-xs font-medium text-on-primary bg-primary hover:bg-primary/90 rounded-lg transition-colors"
              @click="confirmIpSwitch"
            >
              {{ t('app.ipSelector.continue') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>