import { ref, computed, onMounted, onUnmounted, type Ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { useStorage } from '@vueuse/core';
import { useI18n } from 'vue-i18n';
import QRCode from 'qrcode';
import { sendNotification, isPermissionGranted, requestPermission } from '@tauri-apps/plugin-notification';
import { analyzeError, generateErrorDetails, type ConnectionErrorDetails } from '../utils/connectionError';

// Connection modes supported by the application
export type ConnectionMode = 'wifi' | 'usb' | 'web';

// Backend server streaming states
export type ServerState = 'idle' | 'starting' | 'connecting' | 'streaming';

// Interface representing an ADB device discovered on the system
export interface AdbDevice {
  serial: string;
  state: string;
  description: string;
}

// Interface representing network details returned from the backend
export interface NetworkInfo {
  ips: string[];
  port: number;
}

// Interface representing a system network adapter interface
export interface NetworkInterfaceInfo {
  ip: string;
  interface_name: string;
}

/**
 * Composable for managing the connection server, IP configurations, modes, and device scanning
 */
export function useServer(options?: { audioLevel?: Ref<number>; isMuted?: Ref<boolean> }) {
  const { t } = useI18n();

  // Current server connection state
  const serverState = ref<ServerState>('idle');
  
  // Selected connection mode persisted in storage
  const connectionMode = useStorage<ConnectionMode>('micyou_connectionMode', 'wifi');
  
  // Port for streaming under Wi-Fi or USB modes
  const serverPort = useStorage<number>('micyou_serverPort', 8554);
  
  // Port for Web RTC / HTTPS server stream
  const webPort = useStorage<number>('micyou_webPort', 8443);
  
  // Number of clients currently connected to the Web interface
  const webClientCount = ref(0);
  
  // Complete HTTPS URL for the web stream
  const webUrl = ref('');
  
  // Data URI base64 of the QR code for easy connection scanning
  const qrDataUrl = ref('');
  
  // Flag indicating if desktop OS notification alerts are enabled
  const notificationsEnabled = useStorage<boolean>('micyou_notifications', true);
  
  // Cache of primary network information returned by backend
  const networkInfo = ref<NetworkInfo | null>(null);
  
  // Currently selected bind IP (if isAutoBind is false)
  const selectedIp = ref<string>('0.0.0.0');
  
  // List of active network adapters and interfaces detected by the OS
  const networkInterfaces = ref<NetworkInterfaceInfo[]>([]);
  
  // UI triggers for menus, confirm popups and dialogs
  const showIpMenu = ref(false);
  const showIpSwitchConfirm = ref(false);
  const pendingIp = ref('');
  const pendingAutoSelect = ref(false);
  
  // Whether to listen on all interfaces (auto-bind) or a selected static IP interface
  const isAutoBind = ref(true);
  
  // USB mode target select overlay triggers
  const showDeviceSelector = ref(false);
  const adbDevices = ref<AdbDevice[]>([]);
  const pendingUsbPort = ref<number>(0);
  
  // Error handling triggers
  const showErrorDialog = ref(false);
  const errorDetails = ref<ConnectionErrorDetails | null>(null);
  
  // Selected audio output device target (e.g. system default or virtual sound card)
  const outputDevice = ref<string>(localStorage.getItem('micyou_output_device') || '');
  const showQrDialog = ref(false);

  // Active configurations when the server is running
  const activeConnectionMode = ref<ConnectionMode | null>(null);
  const activePort = ref<number | null>(null);

  // Computes the display representation of the active bind IP address
  const displayIp = computed(() => {
    if (isAutoBind.value) {
      return networkInterfaces.value.length > 0 ? networkInterfaces.value[0].ip : '...';
    }
    return selectedIp.value;
  });

  // Dynamic text description showing the status of the connection
  const statusDescription = computed(() => {
    const mode = activeConnectionMode.value || connectionMode.value;
    const port = activePort.value || (mode === 'web' ? webPort.value : serverPort.value);

    if (serverState.value === 'streaming') {
      if (mode === 'web') {
        return t('app.web.clientsConnected', { count: webClientCount.value });
      }
      return t('app.status.streamingDesc');
    }
    if (serverState.value === 'connecting') {
      return t('app.status.connectingDesc', { port: port });
    }
    if (serverState.value === 'starting') return t('app.status.startingDesc');
    return t('app.status.readyDesc');
  });

  /**
   * Checks if the server is in any active (non-idle) states
   */
  function isStreaming(v: ServerState) {
    return v === 'streaming' || v === 'connecting' || v === 'starting';
  }

  /**
   * Sends a desktop push notification if permissions are granted
   */
  async function notify(body: string) {
    const granted = await isPermissionGranted();
    if (!granted) {
      await requestPermission();
    }
    sendNotification({ title: 'MicYou', body });
  }

  /**
   * Generates a base64 QR code image from a given target URL
   */
  async function generateQrCode(url: string) {
    try {
      qrDataUrl.value = await QRCode.toDataURL(url, {
        width: 200,
        margin: 1,
        color: { dark: '#000000', light: '#ffffff' }
      });
    } catch (e) {
      console.error('QR generation failed:', e);
      qrDataUrl.value = '';
    }
  }

  // OS detection for macOS visual behavior
  const isMacOS = typeof navigator !== 'undefined' && /Mac/.test(navigator.platform || navigator.userAgent) && !/iPhone|iPad|iPod/.test(navigator.userAgent);

  /**
   * Toggles the server state between started and stopped
   */
  const toggleStreaming = async () => {
    if (serverState.value !== 'idle') {
      try {
        await invoke('stop_server');
      } catch (e) {
        console.warn('Failed to stop server cleanly:', e);
      } finally {
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
        if (options?.audioLevel) options.audioLevel.value = 0;
        // Restore original input device on macOS when using BlackHole virtual audio
        if (isMacOS) {
          try { await invoke('restore_input_device'); } catch {}
        }
      }
    } else {
      try {
        serverState.value = 'starting';
        activeConnectionMode.value = connectionMode.value;
        activePort.value = connectionMode.value === 'web' ? Number(webPort.value) : Number(serverPort.value);
        const bindAddress = isAutoBind.value ? null : selectedIp.value;
        await invoke('start_server', {
          port: activePort.value,
          mode: activeConnectionMode.value,
          bindAddress: bindAddress,
          outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
        });
        // Auto-switch to BlackHole input on macOS for seamless virtual audio loopback
        if (isMacOS) {
          try { await invoke('set_blackhole_as_input'); } catch {}
        }
        if (activeConnectionMode.value === 'usb') {
          const result = await invoke<{ type: string; devices?: AdbDevice[] }>('enable_usb_mode', { port: activePort.value, deviceSerial: null });
          if (result.type === 'MultipleDevices') {
            try { await invoke('stop_server'); } catch {}
            adbDevices.value = result.devices || [];
            pendingUsbPort.value = activePort.value || Number(serverPort.value);
            showDeviceSelector.value = true;
            serverState.value = 'idle';
            activeConnectionMode.value = null;
            activePort.value = null;
            return;
          } else if (result.type === 'NoDevices') {
            try { await invoke('stop_server'); } catch {}
            serverState.value = 'idle';
            activeConnectionMode.value = null;
            activePort.value = null;
            const msg = 'No USB devices found. Please connect a device and enable USB debugging.';
            const type = analyzeError(msg);
            errorDetails.value = generateErrorDetails(type, msg, activeConnectionMode.value || connectionMode.value, activePort.value || Number(serverPort.value), selectedIp.value, t);
            showErrorDialog.value = true;
            return;
          }
        }
        if (activeConnectionMode.value === 'web') {
          const info = networkInfo.value;
          const ip = info && info.ips.length > 0 ? info.ips[0] : 'localhost';
          const url = `https://${ip}:${webPort.value}`;
          webUrl.value = url;
          generateQrCode(url);
        }
        serverState.value = 'connecting';
      } catch (e: any) {
        console.error(e);
        try { await invoke('stop_server'); } catch {}
        const msg = typeof e === 'string' ? e : e?.message ?? String(e);
        const type = analyzeError(msg);
        errorDetails.value = generateErrorDetails(type, msg, connectionMode.value, Number(serverPort.value), selectedIp.value, t);
        showErrorDialog.value = true;
        serverState.value = 'idle';
      }
    }
  };

  /**
   * Sets bind IP target or prompts user if they try to switch while server is active
   */
  const selectIp = (ip: string, autoSelect: boolean) => {
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

  /**
   * Sets active bind variables directly
   */
  const applyIpSelection = (ip: string, autoSelect: boolean) => {
    if (autoSelect) {
      isAutoBind.value = true;
      selectedIp.value = '0.0.0.0';
    } else {
      isAutoBind.value = false;
      selectedIp.value = ip;
    }
  };

  /**
   * Switches IP and restarts the server with the new bind target
   */
  const confirmIpSwitch = async () => {
    applyIpSelection(pendingIp.value, pendingAutoSelect.value);
    showIpSwitchConfirm.value = false;
    if (serverState.value === 'streaming' || serverState.value === 'connecting') {
      try {
        try { await invoke('stop_server'); } catch (e) { console.warn('stop_server during IP switch:', e); }
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
        if (options?.audioLevel) options.audioLevel.value = 0;
        const bindAddress = isAutoBind.value ? null : selectedIp.value;
        activeConnectionMode.value = connectionMode.value;
        activePort.value = connectionMode.value === 'web' ? Number(webPort.value) : Number(serverPort.value);
        await invoke('start_server', {
          port: activePort.value,
          mode: activeConnectionMode.value,
          bindAddress: bindAddress,
          outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
        });
        serverState.value = 'connecting';
        if (activeConnectionMode.value === 'usb') {
          const result = await invoke<{ type: string; devices?: AdbDevice[] }>('enable_usb_mode', { port: activePort.value, deviceSerial: null });
          if (result.type === 'MultipleDevices') {
            try { await invoke('stop_server'); } catch {}
            adbDevices.value = result.devices || [];
            pendingUsbPort.value = activePort.value || Number(serverPort.value);
            showDeviceSelector.value = true;
            serverState.value = 'idle';
            activeConnectionMode.value = null;
            activePort.value = null;
            return;
          } else if (result.type === 'NoDevices') {
            try { await invoke('stop_server'); } catch {}
            serverState.value = 'idle';
            activeConnectionMode.value = null;
            activePort.value = null;
            const msg = 'No USB devices found. Please connect a device and enable USB debugging.';
            const type = analyzeError(msg);
            errorDetails.value = generateErrorDetails(type, msg, activeConnectionMode.value || connectionMode.value, activePort.value || Number(serverPort.value), selectedIp.value, t);
            showErrorDialog.value = true;
            return;
          }
        }
      } catch (e: any) {
        console.error(e);
        try { await invoke('stop_server'); } catch {}
        const msg = typeof e === 'string' ? e : e?.message ?? String(e);
        const type = analyzeError(msg);
        errorDetails.value = generateErrorDetails(type, msg, activeConnectionMode.value || connectionMode.value, activePort.value || Number(serverPort.value), selectedIp.value, t);
        showErrorDialog.value = true;
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
      }
    }
  };

  /**
   * Targets a specific discovered USB/ADB device
   */
  const selectAdbDevice = async (serial: string) => {
    showDeviceSelector.value = false;
    try {
      serverState.value = 'starting';
      activeConnectionMode.value = 'usb';
      activePort.value = pendingUsbPort.value;
      const bindAddress = isAutoBind.value ? null : selectedIp.value;
      await invoke('start_server', {
        port: activePort.value,
        mode: activeConnectionMode.value,
        bindAddress: bindAddress,
        outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
      });
      await invoke('enable_usb_mode', { port: pendingUsbPort.value, deviceSerial: serial });
      serverState.value = 'connecting';
    } catch (e: any) {
      console.error(e);
      try { await invoke('stop_server'); } catch {}
      const msg = typeof e === 'string' ? e : e?.message ?? String(e);
      const type = analyzeError(msg);
      errorDetails.value = generateErrorDetails(type, msg, 'usb', pendingUsbPort.value, selectedIp.value, t);
      showErrorDialog.value = true;
      serverState.value = 'idle';
      activeConnectionMode.value = null;
      activePort.value = null;
    }
  };

  /**
   * Cancels ongoing ADB device selection flow
   */
  const cancelDeviceSelection = () => {
    showDeviceSelector.value = false;
    adbDevices.value = [];
    pendingUsbPort.value = 0;
  };

  let unlistenDeviceConnected: UnlistenFn | null = null;
  let unlistenDeviceDisconnected: UnlistenFn | null = null;
  let unlistenServerStopped: UnlistenFn | null = null;
  let unlistenWebClients: UnlistenFn | null = null;

  onMounted(async () => {
    try {
      networkInfo.value = await invoke<NetworkInfo>('get_network_info');
      if (networkInfo.value && networkInfo.value.ips.length > 0) {
        selectedIp.value = networkInfo.value.ips[0];
      }
    } catch (e) {
      console.error("Failed to get network info:", e);
    }

    try {
      networkInterfaces.value = await invoke<NetworkInterfaceInfo[]>('get_network_interfaces');
    } catch (e) {
      console.error("Failed to get network interfaces:", e);
    }

    // Listen for client connection successful event
    unlistenDeviceConnected = await listen('device-connected', () => {
      if (serverState.value === 'idle') return;
      serverState.value = 'streaming';
      if (notificationsEnabled.value) {
        notify(t('app.notify.connected'));
      }
    });

    // Listen for client disconnect events
    unlistenDeviceDisconnected = await listen('device-disconnected', async () => {
      if (serverState.value === 'streaming') {
        const mode = activeConnectionMode.value || connectionMode.value;
        if (mode === 'usb') {
          try { await invoke('stop_server'); } catch {}
          serverState.value = 'idle';
          activeConnectionMode.value = null;
          activePort.value = null;
          if (options?.audioLevel) options.audioLevel.value = 0;
          if (options?.isMuted) options.isMuted.value = false;
          if (notificationsEnabled.value) {
            notify(t('app.notify.usbDisconnected'));
          }
        } else {
          serverState.value = 'connecting';
          if (options?.audioLevel) options.audioLevel.value = 0;
          if (notificationsEnabled.value) {
            notify(t('app.notify.disconnected'));
          }
        }
      }
    });

    // Listen for general server stops triggered elsewhere
    unlistenServerStopped = await listen('server-stopped', () => {
      serverState.value = 'idle';
      activeConnectionMode.value = null;
      activePort.value = null;
      if (options?.audioLevel) options.audioLevel.value = 0;
      if (options?.isMuted) options.isMuted.value = false;
    });

    // Listen for clients joining/leaving the local web server
    unlistenWebClients = await listen<number>('web-client-count', (event) => {
      webClientCount.value = event.payload;
    });

    // Start streaming automatically if user configuration allows it
    if (localStorage.getItem('micyou_auto_stream') === 'true') {
      toggleStreaming();
    }
  });

  onUnmounted(() => {
    if (unlistenDeviceConnected) unlistenDeviceConnected();
    if (unlistenDeviceDisconnected) unlistenDeviceDisconnected();
    if (unlistenServerStopped) unlistenServerStopped();
    if (unlistenWebClients) unlistenWebClients();
  });

  return {
    serverState,
    connectionMode,
    serverPort,
    webPort,
    webClientCount,
    webUrl,
    qrDataUrl,
    networkInfo,
    selectedIp,
    networkInterfaces,
    showIpMenu,
    isAutoBind,
    displayIp,
    statusDescription,
    showDeviceSelector,
    adbDevices,
    pendingUsbPort,
    showErrorDialog,
    errorDetails,
    outputDevice,
    showQrDialog,
    notificationsEnabled,
    showIpSwitchConfirm,
    pendingIp,
    pendingAutoSelect,
    isStreaming,
    toggleStreaming,
    selectIp,
    applyIpSelection,
    confirmIpSwitch,
    selectAdbDevice,
    cancelDeviceSelection,
  };
}

