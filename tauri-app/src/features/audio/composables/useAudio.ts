import { ref, onMounted, onUnmounted } from 'vue';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { useStorage } from '@vueuse/core';

/**
 * Interface representing the real-time audio statistics from the audio engine
 */
export interface AudioMetrics {
  bitrate: number;
  sampleRate: number;
  latencyMs: number;
  networkLatencyMs: number;
  packetLossRate: number;
  jitterMs: number;
  bufferDurationMs: number;
}

/**
 * Composable for managing audio status, mute state, audio level, metrics and warning dialogs
 */
export function useAudio() {
  // Real-time audio input volume level (0-100)
  const audioLevel = ref(0);
  
  // Audio mute status
  const isMuted = ref(false);
  
  // Real-time audio performance and network statistics
  const audioMetrics = ref<AudioMetrics | null>(null);
  
  // Real-time audio earback / monitoring status (listening to own voice via speaker)
  const isMonitoringEnabled = ref(false);
  
  // Flag showing/hiding the monitoring panel
  const showMonitoringPanel = ref(false);
  
  // Flag indicating if the UDP port fallback warning should be displayed
  const showUdpWarning = ref(false);

  // Flag and storage for earback / monitoring warning dialog
  const showMonitoringWarning = ref(false);
  const dontShowMonitoringWarning = useStorage('micyou_dont_show_monitoring_warning', false);

  let unlistenAudioLevel: UnlistenFn | null = null;
  let unlistenAudioMetrics: UnlistenFn | null = null;
  let unlistenMuteState: UnlistenFn | null = null;
  let unlistenMonitoringState: UnlistenFn | null = null;
  let unlistenUdpWarning: UnlistenFn | null = null;
  let unlistenDeviceDisconnected: UnlistenFn | null = null;
  let unlistenServerStopped: UnlistenFn | null = null;

  /**
   * Toggles the mute state of the server-side audio engine
   */
  async function toggleMute() {
    const newVal = !isMuted.value;
    isMuted.value = newVal;
    try {
      await invoke('set_mute_state', { isMuted: newVal });
    } catch (e) {
      console.error('set_mute_state failed:', e);
      isMuted.value = !newVal;
    }
  }

  /**
   * Direct execution of monitoring state change
   */
  async function executeSetMonitoring(enabled: boolean) {
    isMonitoringEnabled.value = enabled;
    try {
      await invoke('set_monitoring', { enabled });
    } catch (e) {
      console.error('set_monitoring failed:', e);
      isMonitoringEnabled.value = !enabled;
    }
  }

  /**
   * Toggles real-time audio monitoring / earback with warning prompt on enable
   */
  function toggleMonitoringEnabled() {
    if (isMonitoringEnabled.value) {
      void executeSetMonitoring(false);
    } else {
      if (dontShowMonitoringWarning.value) {
        void executeSetMonitoring(true);
      } else {
        showMonitoringWarning.value = true;
      }
    }
  }

  function handleMonitoringWarningConfirm(dontAskAgain: boolean) {
    if (dontAskAgain) {
      dontShowMonitoringWarning.value = true;
    }
    showMonitoringWarning.value = false;
    void executeSetMonitoring(true);
  }

  function handleMonitoringWarningCancel() {
    showMonitoringWarning.value = false;
  }

  /**
   * Toggles the visibility of the monitoring panel
   */
  function toggleMonitoring() {
    showMonitoringPanel.value = !showMonitoringPanel.value;
  }

  onMounted(async () => {
    // Listen for real-time audio amplitude updates from backend
    unlistenAudioLevel = await listen<number>('audio-level', (event) => {
      audioLevel.value = event.payload;
      if (event.payload > 0 && showUdpWarning.value) {
        showUdpWarning.value = false;
      }
    });
    
    // Listen for performance metrics updates from backend
    unlistenAudioMetrics = await listen<AudioMetrics>('audio-metrics', (event) => {
      audioMetrics.value = event.payload;
    });
    
    // Listen for mute state synchronizations from other surfaces
    unlistenMuteState = await listen<boolean>('mute-state-changed', (event) => {
      isMuted.value = event.payload;
    });
    
    // Listen for audio monitoring state synchronizations
    unlistenMonitoringState = await listen<boolean>('monitoring-enabled-changed', (event) => {
      isMonitoringEnabled.value = event.payload;
    });

    // Listen for warnings if UDP traffic is blocked and falls back to TCP
    unlistenUdpWarning = await listen('udp_audio_warning', () => {
      if (!isMuted.value) {
        showUdpWarning.value = true;
      }
    });

    // Automatically dismiss warning if client disconnects or server stops
    unlistenDeviceDisconnected = await listen('device-disconnected', () => {
      showUdpWarning.value = false;
    });

    unlistenServerStopped = await listen('server-stopped', () => {
      showUdpWarning.value = false;
    });
  });

  onUnmounted(() => {
    if (unlistenAudioLevel) unlistenAudioLevel();
    if (unlistenAudioMetrics) unlistenAudioMetrics();
    if (unlistenMuteState) unlistenMuteState();
    if (unlistenMonitoringState) unlistenMonitoringState();
    if (unlistenUdpWarning) unlistenUdpWarning();
    if (unlistenDeviceDisconnected) unlistenDeviceDisconnected();
    if (unlistenServerStopped) unlistenServerStopped();
  });

  return {
    audioLevel,
    isMuted,
    isMonitoringEnabled,
    audioMetrics,
    showMonitoringPanel,
    showUdpWarning,
    showMonitoringWarning,
    toggleMute,
    toggleMonitoringEnabled,
    handleMonitoringWarningConfirm,
    handleMonitoringWarningCancel,
    toggleMonitoring,
  };
}

