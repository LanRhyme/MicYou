import { ref } from 'vue';

/**
 * Whether the mute state is synchronized with the mobile client.
 *
 * Module-level singleton shared by `useServer` (loads/persists the value in
 * server.json as the `muteSync` field) and the settings dialog (renders the
 * toggle). The Rust core reads the same flag from server.json: when it is
 * off, mute changes are neither sent to the phone nor applied from it.
 * Defaults to true (sync on).
 */
export const muteSyncEnabled = ref<boolean>(true);

export function useMuteSync() {
  return { muteSyncEnabled };
}
