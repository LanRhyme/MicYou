import { nextTick, reactive, ref, watch } from 'vue';
import { command, emitToWindow, onEvent, type UnlistenFn } from '@/platform';
import { isMacOS } from '@/shared/lib/os';

// AEC runs on Linux and Windows; macOS has no backend for it.
export const isAecSupported = !isMacOS;

// Single source of truth for audio / DSP defaults: both the initial state and
// "restore defaults" use it, so a new field only has to be added here.
const DEFAULT_AUDIO_SETTINGS = () => ({
  audioDevice: 'auto',
  gain: 0,
  aecEnabled: false,
  nsEnabled: false,
  nsType: 'PureVox',
  nsIntensity: 100,
  dereverbEnabled: false,
  dereverbLevel: 50,
  agcEnabled: false,
  agcTarget: 16000,
  agcAttack: 50,
  agcDecay: 50,
  vadEnabled: false,
  vadThreshold: -40,
  outputBufferMs: 300,
  processingChain: ['AEC', 'NoiseReduction', 'Dereverb', 'Equalizer', 'Amplifier', 'AGC', 'VAD'],
  equalizer: {
    enabled: false,
    preAmp: 0,
    gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  },
});

// One settings window per webview, so module state is the window's state.
const settings = reactive(DEFAULT_AUDIO_SETTINGS());
const aecRuntimeAvailable = ref(true);
const suppressAutosave = ref(false);

/** The main window follows the output device chosen here. */
function notifyOutputDevice(device: string) {
  void emitToWindow('main', 'output-device-changed', device).catch((e) =>
    console.warn('Failed to notify the main window of the output device:', e),
  );
}

/** AEC is pinned first where it is supported and dropped elsewhere. */
function normalizeAec() {
  const chain = settings.processingChain ?? [];
  settings.processingChain = isAecSupported
    ? ['AEC', ...chain.filter((i) => i !== 'AEC')]
    : chain.filter((i) => i !== 'AEC');
  if (!isAecSupported) settings.aecEnabled = false;
}

function loadLocalAudioSettings() {
  const saved = localStorage.getItem('micyou_audio_settings');
  if (!saved) return;
  try {
    Object.assign(settings, JSON.parse(saved));
  } catch (error) {
    console.error('Failed to parse settings', error);
  }
}

async function loadSettings() {
  // Prefer the shared settings.json (written by update_audio_settings, also used
  // by the CLI) so GUI and CLI stay in sync; localStorage stays as a fallback.
  try {
    const backend = await command('get_audio_settings');
    if (Object.keys(backend).length > 0) {
      Object.assign(settings, backend);
      localStorage.setItem('micyou_audio_settings', JSON.stringify(settings));
    } else {
      loadLocalAudioSettings();
    }
  } catch (error) {
    console.error('get_audio_settings failed, using localStorage', error);
    loadLocalAudioSettings();
  }

  if (!['PureVox', 'RNNoise', 'Speexdsp'].includes(settings.nsType)) {
    settings.nsType = 'PureVox';
  }
  normalizeAec();

  // Legacy support
  const savedDevice = localStorage.getItem('micyou_output_device');
  if (savedDevice && !settings.audioDevice) {
    settings.audioDevice = savedDevice === 'default' ? 'auto' : savedDevice;
  }

  if (settings.audioDevice) {
    notifyOutputDevice(settings.audioDevice);
  }
}

async function syncSettingsToBackend() {
  try {
    await command('update_audio_settings', {
      settings: {
        gain: settings.gain,
        aecEnabled: isAecSupported ? settings.aecEnabled : false,
        nsEnabled: settings.nsEnabled,
        nsType: settings.nsType,
        nsIntensity: settings.nsIntensity,
        dereverbEnabled: settings.dereverbEnabled,
        dereverbLevel: settings.dereverbLevel,
        agcEnabled: settings.agcEnabled,
        agcTarget: settings.agcTarget,
        agcAttack: settings.agcAttack,
        agcDecay: settings.agcDecay,
        vadEnabled: settings.vadEnabled,
        vadThreshold: settings.vadThreshold,
        outputBufferMs: settings.outputBufferMs,
        processingChain: isAecSupported
          ? settings.processingChain
          : settings.processingChain.filter((i) => i !== 'AEC'),
        equalizer: settings.equalizer,
      },
    });
  } catch (e) {
    console.error('Failed to sync DSP settings to backend:', e);
  }
}

function saveSettings() {
  localStorage.setItem('micyou_audio_settings', JSON.stringify(settings));
  localStorage.setItem('micyou_output_device', settings.audioDevice);
  notifyOutputDevice(settings.audioDevice);
  void syncSettingsToBackend();
}

// Autosave: any change persists and syncs to the backend. Suppressed while
// restoring defaults so the batched reset produces exactly one save.
watch(
  settings,
  () => {
    if (suppressAutosave.value) return;
    saveSettings();
  },
  { deep: true },
);

async function resetAudioSettings() {
  suppressAutosave.value = true;
  try {
    const defaults = DEFAULT_AUDIO_SETTINGS();
    // Drop keys that are no longer defaults (renamed or removed fields), then overwrite.
    Object.keys(settings).forEach((k) => {
      if (!(k in defaults)) delete (settings as Record<string, unknown>)[k];
    });
    Object.assign(settings, defaults);
    normalizeAec();
    // Let the deep watcher flush while still suppressed, so it is a no-op.
    await nextTick();
  } finally {
    suppressAutosave.value = false;
  }
  saveSettings();
}

/** Keeps the AEC availability reported by the running server up to date. */
async function trackAecStatus(): Promise<UnlistenFn> {
  return onEvent('aec-status-changed', (payload) => {
    aecRuntimeAvailable.value = payload.available;
  });
}

export function useAudioSettings() {
  return {
    settings,
    aecRuntimeAvailable,
    loadSettings,
    syncSettingsToBackend,
    resetAudioSettings,
    trackAecStatus,
  };
}
