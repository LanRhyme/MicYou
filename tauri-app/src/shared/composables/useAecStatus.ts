import { computed, ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';

/**
 * AEC availability reported by the backend.
 *
 * The backend decides what the platform can do (`unsupported_os` on systems
 * without a capture path, `permission_denied` when the bundle is not allowed to
 * record system audio), so no component should guess from the operating system
 * it happens to detect.
 */
interface AecStatusPayload {
  available: boolean;
  enabled: boolean;
  reason?: string | null;
}

/** Reasons that mean AEC can never run here, as opposed to a capture that stopped. */
const CAPABILITY_REASONS = new Set(['unsupported_os', 'permission_denied']);

// Module-level singleton: every component shares one answer and one listener, so
// the UI cannot end up with two disagreeing AEC states.
const aecAvailable = ref(true);
const aecReason = ref<string | null>(null);
let initialised = false;
let unlisten: UnlistenFn | null = null;

function apply(status: AecStatusPayload) {
  aecAvailable.value = status.available;
  aecReason.value = status.reason ?? null;
}

async function refresh() {
  try {
    apply(await invoke<AecStatusPayload>('get_aec_status'));
  } catch (error) {
    console.error('get_aec_status failed:', error);
  }
}

/** Whether this system can run AEC at all. */
const aecSupported = computed(() => !CAPABILITY_REASONS.has(aecReason.value ?? ''));

export function useAecStatus() {
  if (!initialised) {
    initialised = true;
    void refresh();
    void listen<AecStatusPayload>('aec-status-changed', (event) => apply(event.payload)).then(
      (stop) => {
        unlisten = stop;
      },
    );
  }

  return { aecAvailable, aecSupported, aecReason, refresh };
}

/** Releases the shared listener; only used by tests and hot reloads. */
export function disposeAecStatus() {
  unlisten?.();
  unlisten = null;
  initialised = false;
}
