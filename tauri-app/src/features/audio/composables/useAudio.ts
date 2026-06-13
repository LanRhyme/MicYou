import { ref, onMounted, onUnmounted } from 'vue';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';

export function useAudio() {
  const audioLevel = ref(0);
  const isMuted = ref(false);
  const audioMetrics = ref<any>(null);
  const showMonitoringPanel = ref(false);
  const showUdpWarning = ref(false);

  let unlistenAudioLevel: UnlistenFn | null = null;
  let unlistenAudioMetrics: UnlistenFn | null = null;
  let unlistenMuteState: UnlistenFn | null = null;
  let unlistenUdpWarning: UnlistenFn | null = null;

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

  function toggleMonitoring() {
    showMonitoringPanel.value = !showMonitoringPanel.value;
  }

  onMounted(async () => {
    unlistenAudioLevel = await listen<number>('audio-level', (event) => {
      audioLevel.value = event.payload;
    });
    unlistenAudioMetrics = await listen<any>('audio-metrics', (event) => {
      audioMetrics.value = event.payload;
    });
    unlistenMuteState = await listen<boolean>('mute-state-changed', (event) => {
      isMuted.value = event.payload;
    });
    unlistenUdpWarning = await listen('udp_audio_warning', () => {
      showUdpWarning.value = true;
    });
  });

  onUnmounted(() => {
    if (unlistenAudioLevel) unlistenAudioLevel();
    if (unlistenAudioMetrics) unlistenAudioMetrics();
    if (unlistenMuteState) unlistenMuteState();
    if (unlistenUdpWarning) unlistenUdpWarning();
  });

  return {
    audioLevel, isMuted, audioMetrics, showMonitoringPanel, showUdpWarning,
    toggleMute, toggleMonitoring,
  };
}
