import { onMounted, onUnmounted, watch, type Ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import type { UnlistenFn } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';

interface BlurRect {
  x: number;
  y: number;
  width: number;
  height: number;
  radius: number;
}

interface WindowEffectsOptions {
  /** Blur the desktop behind the `data-blur-region` elements. */
  blur: Ref<boolean>;
  /** Corner radius of a native drop shadow around the whole window, or null. */
  shadowRadius: Ref<number | null>;
}

const REGION_SELECTOR = '[data-blur-region]';

/**
 * Compositor effects for the frameless, transparent windows. Blur follows the
 * elements marked `data-blur-region` so it matches rounded panels instead of
 * the window rectangle. Only KDE Plasma on Wayland supports blur and shadow;
 * elsewhere the backend reports false once and the CSS fallback stays.
 * Also marks the window inactive so glass styles can thicken when unfocused.
 */
export function useWindowEffects({ blur, shadowRadius }: WindowEffectsOptions) {
  const root = document.documentElement;
  let blurUnsupported = false;
  let shadowUnsupported = false;
  let lastBlur = '[]';
  let shadowPending = false;
  let frame = 0;
  const resizeObserver = new ResizeObserver(() => scheduleBlur());
  const mutationObserver = new MutationObserver(() => scheduleBlur());

  function collectRegions(): BlurRect[] {
    resizeObserver.disconnect();
    return Array.from(document.querySelectorAll<HTMLElement>(REGION_SELECTOR)).flatMap((el) => {
      resizeObserver.observe(el);
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return [];
      return [{
        x: rect.left,
        y: rect.top,
        width: rect.width,
        height: rect.height,
        radius: parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0,
      }];
    });
  }

  async function syncBlur() {
    frame = 0;
    if (blurUnsupported) return;
    const regions = blur.value ? collectRegions() : [];
    const payload = JSON.stringify(regions);
    if (payload === lastBlur) return;
    lastBlur = payload;
    try {
      const active = await invoke<boolean>('set_window_blur', { regions });
      if (!active && regions.length > 0) {
        if (await isHidden()) {
          // Stored and applied by the backend once the window is shown;
          // resend then so the result reflects real support.
          lastBlur = '';
          return;
        }
        blurUnsupported = true;
      }
      root.classList.toggle('native-blur', active);
    } catch (e) {
      blurUnsupported = true;
      root.classList.remove('native-blur');
      console.warn('Window blur is unavailable:', e);
    }
  }

  function scheduleBlur() {
    if (blurUnsupported || frame) return;
    frame = requestAnimationFrame(() => void syncBlur());
  }

  async function syncShadow() {
    if (shadowUnsupported) return;
    const radius = shadowRadius.value;
    shadowPending = false;
    try {
      const active = await invoke<boolean>('set_window_shadow', { radius });
      if (!active && radius !== null) {
        if (await isHidden()) {
          shadowPending = true;
          return;
        }
        shadowUnsupported = true;
      }
    } catch (e) {
      shadowUnsupported = true;
      console.warn('Window shadow is unavailable:', e);
    }
  }

  // A hidden window has no surface, so the backend reports false without
  // telling us anything about compositor support.
  async function isHidden() {
    return !(await getCurrentWindow().isVisible());
  }

  // Window focus, not document focus: plugin panels are iframes and taking
  // focus into one must not count as the window going inactive.
  let unlistenFocus: UnlistenFn | null = null;
  const setInactive = (inactive: boolean) => root.classList.toggle('window-inactive', inactive);

  watch(blur, scheduleBlur);
  watch(shadowRadius, () => void syncShadow());

  onMounted(async () => {
    // Panels are added and removed with v-if; sizes are tracked per element.
    mutationObserver.observe(document.body, { childList: true, subtree: true });
    window.addEventListener('resize', scheduleBlur);
    scheduleBlur();
    void syncShadow();
    const appWindow = getCurrentWindow();
    setInactive(!(await appWindow.isFocused()));
    unlistenFocus = await appWindow.onFocusChanged(({ payload }) => {
      setInactive(!payload);
      if (!payload) return;
      // Shown again (e.g. from the tray): retry what was deferred while hidden.
      scheduleBlur();
      if (shadowPending) void syncShadow();
    });
  });

  onUnmounted(() => {
    mutationObserver.disconnect();
    resizeObserver.disconnect();
    window.removeEventListener('resize', scheduleBlur);
    unlistenFocus?.();
    if (frame) cancelAnimationFrame(frame);
  });
}
