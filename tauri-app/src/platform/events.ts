import { emitTo, listen, type UnlistenFn } from '@tauri-apps/api/event';
import type { AecStatus, AudioMetrics, PluginDownloadProgress, SpectrumPayload } from './types';

export type { UnlistenFn };

/** Events the backend (or another window) emits, with their payloads. */
export interface AppEvents {
  'device-connected': unknown;
  'device-disconnected': unknown;
  'server-stopped': unknown;
  'audio-level': number;
  'audio-metrics': AudioMetrics;
  'audio-spectrum': SpectrumPayload;
  'aec-status-changed': AecStatus;
  'mute-state-changed': boolean;
  'monitoring-enabled-changed': boolean;
  'web-client-count': number;
  udp_audio_warning: unknown;
  'vbcable-install-progress': string;
  'plugin-download-progress': PluginDownloadProgress;
  'tray-action': string;
  /** Backend → settings window when the hidden window is shown again. */
  'settings-window-shown': unknown;
  /** Settings window → main window when the output device changes. */
  'output-device-changed': string;
}

export type EventName = keyof AppEvents;

export function onEvent<K extends EventName>(
  name: K,
  handler: (payload: AppEvents[K]) => void,
): Promise<UnlistenFn> {
  return listen<AppEvents[K]>(name, (event) => handler(event.payload));
}

export function emitToWindow<K extends EventName>(
  label: string,
  name: K,
  payload: AppEvents[K],
): Promise<void> {
  return emitTo(label, name, payload);
}
