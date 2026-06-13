import { ref, computed, onMounted, onUnmounted, type Ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { useStorage } from '@vueuse/core';
import { useI18n } from 'vue-i18n';
import { analyzeError, generateErrorDetails, type ConnectionErrorDetails } from '../utils/connectionError';
import { sendNotification, isPermissionGranted, requestPermission } from '@tauri-apps/plugin-notification';
import QRCode from 'qrcode';

export function useServer(options?: { audioLevel?: Ref<number>; isMuted?: Ref<boolean> }) {
  const { t } = useI18n();

  const serverState = ref<'idle' | 'starting' | 'connecting' | 'streaming'>('idle');
  const connectionMode = useStorage<'wifi' | 'usb' | 'web'>('micyou_connectionMode', 'wifi');
  const serverPort = useStorage('micyou_serverPort', 8554);
  const webPort = useStorage('micyou_webPort', 8443);
  const webClientCount = ref(0);
  const webUrl = ref('');
  const qrDataUrl = ref('');
  const notificationsEnabled = useStorage<boolean>('micyou_notifications', true);
  const networkInfo = ref<{ ips: string[], port: number } | null>(null);
  const selectedIp = ref<string>('0.0.0.0');
  const networkInterfaces = ref<{ ip: string, interface_name: string }[]>([]);
  const showIpMenu = ref(false);
  const showIpSwitchConfirm = ref(false);
  const pendingIp = ref('');
  const pendingAutoSelect = ref(false);
  const isAutoBind = ref(true);
  const showDeviceSelector = ref(false);
  const adbDevices = ref<{ serial: string; state: string; description: string }[]>([]);
  const pendingUsbPort = ref<number>(0);
  const showErrorDialog = ref(false);
  const errorDetails = ref<ConnectionErrorDetails | null>(null);
  const outputDevice = ref<string>(localStorage.getItem('micyou_output_device') || '');
  const showQrDialog = ref(false);

  const displayIp = computed(() => {
    if (isAutoBind.value) {
      return networkInterfaces.value.length > 0 ? networkInterfaces.value[0].ip : '...';
    }
    return selectedIp.value;
  });

  const statusDescription = computed(() => {
    if (serverState.value === 'streaming') {
      if (connectionMode.value === 'web') {
        return t('app.web.clientsConnected', { count: webClientCount.value });
      }
      return t('app.status.streamingDesc');
    }
    if (serverState.value === 'connecting') {
      if (connectionMode.value === 'web') {
        return t('app.status.connectingDesc', { port: webPort.value });
      }
      return t('app.status.connectingDesc', { port: serverPort.value });
    }
    if (serverState.value === 'starting') return t('app.status.startingDesc');
    return t('app.status.readyDesc');
  });

  function isStreaming(v: typeof serverState.value) {
    return v === 'streaming' || v === 'connecting' || v === 'starting';
  }

  async function notify(body: string) {
    const granted = await isPermissionGranted();
    if (!granted) {
      await requestPermission();
    }
    sendNotification({ title: 'MicYou', body });
  }

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

  const toggleStreaming = async () => {
    if (serverState.value !== 'idle') {
      try {
        await invoke('stop_server');
        serverState.value = 'idle';
        if (options?.audioLevel) options.audioLevel.value = 0;
      } catch (e) {
        console.error(e);
      }
    } else {
      try {
        serverState.value = 'starting';
        const bindAddress = isAutoBind.value ? null : selectedIp.value;
        await invoke('start_server', {
          port: connectionMode.value === 'web' ? Number(webPort.value) : Number(serverPort.value),
          mode: connectionMode.value,
          bindAddress: bindAddress,
          outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
        });
        serverState.value = 'connecting';
        if (connectionMode.value === 'usb') {
          const result = await invoke<{ type: string; devices?: { serial: string; state: string; description: string }[] }>('enable_usb_mode', { port: Number(serverPort.value), deviceSerial: null });
          if (result.type === 'MultipleDevices') {
            try { await invoke('stop_server'); } catch {}
            adbDevices.value = result.devices || [];
            pendingUsbPort.value = Number(serverPort.value);
            showDeviceSelector.value = true;
            serverState.value = 'idle';
            return;
          } else if (result.type === 'NoDevices') {
            try { await invoke('stop_server'); } catch {}
            serverState.value = 'idle';
            const msg = 'No USB devices found. Please connect a device and enable USB debugging.';
            const type = analyzeError(msg);
            errorDetails.value = generateErrorDetails(type, msg, connectionMode.value, Number(serverPort.value), selectedIp.value, t);
            showErrorDialog.value = true;
            return;
          }
        }
        if (connectionMode.value === 'web') {
          const info = networkInfo.value;
          const ip = info && info.ips.length > 0 ? info.ips[0] : 'localhost';
          const url = `https://${ip}:${webPort.value}`;
          webUrl.value = url;
          generateQrCode(url);
        }
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
    if (serverState.value === 'streaming' || serverState.value === 'connecting') {
      try {
        await invoke('stop_server');
        serverState.value = 'idle';
        if (options?.audioLevel) options.audioLevel.value = 0;
        const bindAddress = isAutoBind.value ? null : selectedIp.value;
        await invoke('start_server', {
          port: Number(serverPort.value),
          mode: connectionMode.value,
          bindAddress: bindAddress,
          outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
        });
        serverState.value = 'connecting';
        if (connectionMode.value === 'usb') {
          const result = await invoke<{ type: string; devices?: { serial: string; state: string; description: string }[] }>('enable_usb_mode', { port: Number(serverPort.value), deviceSerial: null });
          if (result.type === 'MultipleDevices') {
            try { await invoke('stop_server'); } catch {}
            adbDevices.value = result.devices || [];
            pendingUsbPort.value = Number(serverPort.value);
            showDeviceSelector.value = true;
            serverState.value = 'idle';
            return;
          } else if (result.type === 'NoDevices') {
            try { await invoke('stop_server'); } catch {}
            serverState.value = 'idle';
            const msg = 'No USB devices found. Please connect a device and enable USB debugging.';
            const type = analyzeError(msg);
            errorDetails.value = generateErrorDetails(type, msg, connectionMode.value, Number(serverPort.value), selectedIp.value, t);
            showErrorDialog.value = true;
            return;
          }
        }
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

  const selectAdbDevice = async (serial: string) => {
    showDeviceSelector.value = false;
    try {
      serverState.value = 'starting';
      const bindAddress = isAutoBind.value ? null : selectedIp.value;
      await invoke('start_server', {
        port: pendingUsbPort.value,
        mode: 'usb',
        bindAddress: bindAddress,
        outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
      });
      serverState.value = 'connecting';
      await invoke('enable_usb_mode', { port: pendingUsbPort.value, deviceSerial: serial });
    } catch (e: any) {
      console.error(e);
      try { await invoke('stop_server'); } catch {}
      const msg = typeof e === 'string' ? e : e?.message ?? String(e);
      const type = analyzeError(msg);
      errorDetails.value = generateErrorDetails(type, msg, 'usb', pendingUsbPort.value, selectedIp.value, t);
      showErrorDialog.value = true;
      serverState.value = 'idle';
    }
  };

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

    unlistenDeviceConnected = await listen('device-connected', () => {
      serverState.value = 'streaming';
      if (notificationsEnabled.value) {
        notify(t('app.notify.connected'));
      }
    });

    unlistenDeviceDisconnected = await listen('device-disconnected', async () => {
      if (serverState.value === 'streaming') {
        if (connectionMode.value === 'usb') {
          try { await invoke('stop_server'); } catch {}
          serverState.value = 'idle';
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

    unlistenServerStopped = await listen('server-stopped', () => {
      serverState.value = 'idle';
      if (options?.audioLevel) options.audioLevel.value = 0;
      if (options?.isMuted) options.isMuted.value = false;
    });

    unlistenWebClients = await listen<number>('web-client-count', (event) => {
      webClientCount.value = event.payload;
    });

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
    serverState, connectionMode, serverPort, webPort, webClientCount, webUrl, qrDataUrl,
    networkInfo, selectedIp, networkInterfaces, showIpMenu, isAutoBind, displayIp,
    statusDescription, showDeviceSelector, adbDevices, pendingUsbPort,
    showErrorDialog, errorDetails, outputDevice, showQrDialog, notificationsEnabled,
    showIpSwitchConfirm, pendingIp, pendingAutoSelect,
    isStreaming,
    toggleStreaming, selectIp, applyIpSelection, confirmIpSwitch,
    selectAdbDevice, cancelDeviceSelection,
  };
}
