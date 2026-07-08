import { onMounted, onBeforeUnmount, watch, type Ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { useI18n } from "vue-i18n";

export interface TrayMenuStrings {
  tooltip: string;
  show: string;
  hide: string;
  start: string;
  stop: string;
  exit: string;
}

export interface TrayState {
  windowVisible: boolean;
  isStreaming: boolean;
}

export interface TrayCallbacks {
  onShow: () => void | Promise<void>;
  onToggleStream: () => void | Promise<void>;
  onExit: () => void | Promise<void>;
}

export function trayStringsFromI18n(
  t: (key: string) => string,
): TrayMenuStrings {
  return {
    tooltip: t("tray.tooltip"),
    show: t("tray.show"),
    hide: t("tray.hide"),
    start: t("tray.start"),
    stop: t("tray.stop"),
    exit: t("tray.exit"),
  };
}

export function useTray(
  callbacks: TrayCallbacks,
  visibility: Ref<boolean>,
  streaming: Ref<boolean>,
) {
  const { t, locale } = useI18n();
  let unlisten: UnlistenFn | null = null;
  let lastPushedStrings: string | null = null;

  async function pushStrings() {
    const strings = trayStringsFromI18n(t);
    const key = JSON.stringify(strings);
    if (key === lastPushedStrings) return;
    lastPushedStrings = key;
    try {
      await invoke("set_tray_strings", { strings });
    } catch (e) {
      console.error("set_tray_strings failed:", e);
    }
  }

  async function pushState() {
    try {
      await invoke("set_tray_state", {
        state: {
          windowVisible: visibility.value,
          isStreaming: streaming.value,
        },
      });
    } catch (e) {
      console.error("set_tray_state failed:", e);
    }
  }

  onMounted(async () => {
    unlisten = await listen<string>("tray-action", (event) => {
      const id = event.payload;
      switch (id) {
        case "show":
          void callbacks.onShow();
          break;
        case "toggle_stream":
          void callbacks.onToggleStream();
          break;
        case "exit":
          void callbacks.onExit();
          break;
        default:
          console.warn("Unknown tray-action id:", id);
      }
    });

    await pushStrings();
    await pushState();
  });

  watch(locale, () => {
    void pushStrings();
  });

  watch([visibility, streaming], () => {
    void pushState();
  });

  onBeforeUnmount(() => {
    if (unlisten) unlisten();
  });

  return {
    pushStrings,
    pushState,
  };
}
