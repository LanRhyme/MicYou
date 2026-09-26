import { onMounted, onBeforeUnmount, watch, type Ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { useI18n } from "vue-i18n";

export type PredefinedName =
  | "services"
  | "hide"
  | "hideOthers"
  | "showAll"
  | "quit"
  | "undo"
  | "redo"
  | "cut"
  | "copy"
  | "paste"
  | "selectAll"
  | "minimize"
  | "maximize"
  | "fullscreen"
  | "closeWindow";

export type MenuNode =
  | { kind: "item"; id: string; label: string; enabled?: boolean; accelerator?: string }
  | { kind: "check"; id: string; label: string; checked: boolean; enabled?: boolean }
  | { kind: "separator" }
  | { kind: "submenu"; label: string; enabled?: boolean; items: MenuNode[] }
  | { kind: "predefined"; name: PredefinedName; label?: string };

export interface AppMenuState {
  windowVisible: boolean;
  isStreaming: boolean;
  isMuted: boolean;
  isMonitoring: boolean;
  pocketMode: boolean;
}

export interface AppMenuCallbacks {
  onAbout: () => void | Promise<void>;
  onSettings: () => void | Promise<void>;
  onToggleStream: () => void | Promise<void>;
  onToggleWindow: () => void | Promise<void>;
  onToggleMute: () => void | Promise<void>;
  onToggleMonitoring: () => void | Promise<void>;
  onTogglePocket: () => void | Promise<void>;
}

/**
 * The backend only forwards ids carrying this prefix, so every clickable item
 * has to use it or its click is dropped silently.
 */
export const APP_MENU_ID_PREFIX = "menu:";

export const MENU_ID_ABOUT = `${APP_MENU_ID_PREFIX}about`;
export const MENU_ID_SETTINGS = `${APP_MENU_ID_PREFIX}settings`;
export const MENU_ID_TOGGLE_STREAM = `${APP_MENU_ID_PREFIX}toggle_stream`;
export const MENU_ID_TOGGLE_WINDOW = `${APP_MENU_ID_PREFIX}toggle_window`;
export const MENU_ID_MUTE = `${APP_MENU_ID_PREFIX}mute`;
export const MENU_ID_MONITORING = `${APP_MENU_ID_PREFIX}monitoring`;
export const MENU_ID_POCKET = `${APP_MENU_ID_PREFIX}pocket`;

/**
 * Builds the macOS app menu from the current locale and app state.
 *
 * The Edit and Window entries stay `predefined` on purpose: they keep their
 * native selectors, which is what makes the standard shortcuts (⌘C/⌘V/⌘X/⌘A/⌘Z)
 * reach the text fields. They still carry a `label` because the backend falls
 * back to a hardcoded English title when none is given.
 */
export function appMenuFromI18n(
  t: (key: string) => string,
  state: AppMenuState,
): MenuNode[] {
  return [
    {
      kind: "submenu",
      label: "MicYou",
      items: [
        { kind: "item", id: MENU_ID_ABOUT, label: t("menu.about") },
        { kind: "separator" },
        {
          kind: "item",
          id: MENU_ID_SETTINGS,
          label: t("menu.settings"),
          accelerator: "CmdOrCtrl+,",
        },
        { kind: "separator" },
        { kind: "predefined", name: "services", label: t("menu.services") },
        { kind: "separator" },
        { kind: "predefined", name: "hide", label: t("menu.hide") },
        { kind: "predefined", name: "hideOthers", label: t("menu.hideOthers") },
        { kind: "predefined", name: "showAll", label: t("menu.showAll") },
        { kind: "separator" },
        { kind: "predefined", name: "quit", label: t("menu.quit") },
      ],
    },
    {
      kind: "submenu",
      label: t("menu.service"),
      items: [
        {
          kind: "item",
          id: MENU_ID_TOGGLE_STREAM,
          label: state.isStreaming ? t("tray.stop") : t("tray.start"),
        },
        {
          kind: "item",
          id: MENU_ID_TOGGLE_WINDOW,
          label: state.windowVisible ? t("tray.hide") : t("tray.show"),
        },
        { kind: "separator" },
        {
          kind: "check",
          id: MENU_ID_MUTE,
          label: t("menu.mute"),
          checked: state.isMuted,
        },
        {
          kind: "check",
          id: MENU_ID_MONITORING,
          label: t("menu.monitoring"),
          checked: state.isMonitoring,
        },
      ],
    },
    {
      kind: "submenu",
      label: t("menu.view"),
      items: [
        {
          kind: "check",
          id: MENU_ID_POCKET,
          label: t("menu.pocketMode"),
          checked: state.pocketMode,
        },
      ],
    },
    {
      kind: "submenu",
      label: t("menu.edit"),
      items: [
        { kind: "predefined", name: "undo", label: t("menu.undo") },
        { kind: "predefined", name: "redo", label: t("menu.redo") },
        { kind: "separator" },
        { kind: "predefined", name: "cut", label: t("menu.cut") },
        { kind: "predefined", name: "copy", label: t("menu.copy") },
        { kind: "predefined", name: "paste", label: t("menu.paste") },
        { kind: "predefined", name: "selectAll", label: t("menu.selectAll") },
      ],
    },
    {
      kind: "submenu",
      label: t("menu.window"),
      items: [
        { kind: "predefined", name: "minimize", label: t("menu.minimize") },
        { kind: "predefined", name: "maximize", label: t("menu.zoom") },
        { kind: "separator" },
        { kind: "predefined", name: "closeWindow", label: t("menu.closeWindow") },
      ],
    },
  ];
}

/**
 * Returns a description of the first node that cannot be expressed by the
 * backend (missing id prefix or empty label), or null when the menu is fine.
 * Such nodes would render as dead menu entries, so the menu is not pushed at all.
 */
export function findInvalidMenuNode(nodes: MenuNode[], path: string[] = []): string | null {
  for (const [index, node] of nodes.entries()) {
    const here = [...path, `${node.kind}[${index}]`];
    if (node.kind === "separator") continue;

    if (node.kind === "predefined") {
      // The label is optional on predefined entries; without it the backend
      // falls back to its built-in English title, which we never want.
      if (node.label !== undefined && !node.label.trim()) {
        return `${here.join(" › ")}: empty label`;
      }
      continue;
    }

    if (!node.label.trim()) {
      return `${here.join(" › ")}: empty label`;
    }
    if (node.kind === "item" || node.kind === "check") {
      if (!node.id.startsWith(APP_MENU_ID_PREFIX)) {
        return `${here.join(" › ")}: id "${node.id}" is missing the "${APP_MENU_ID_PREFIX}" prefix`;
      }
    } else {
      const nested = findInvalidMenuNode(node.items, here);
      if (nested) return nested;
    }
  }
  return null;
}

export function useAppMenu(callbacks: AppMenuCallbacks, state: Ref<AppMenuState>) {
  const { t, locale } = useI18n();
  let unlisten: UnlistenFn | null = null;
  let lastPushedMenu: string | null = null;

  async function push() {
    const menu = appMenuFromI18n(t, state.value);
    const invalid = findInvalidMenuNode(menu);
    if (invalid) {
      console.error("Refusing to push an invalid app menu:", invalid);
      return;
    }

    const key = JSON.stringify(menu);
    if (key === lastPushedMenu) return;
    lastPushedMenu = key;
    try {
      await invoke("set_app_menu", { menu });
    } catch (e) {
      lastPushedMenu = null;
      console.error("set_app_menu failed:", e);
    }
  }

  onMounted(async () => {
    unlisten = await listen<string>("app-menu-action", (event) => {
      const id = event.payload;
      switch (id) {
        case MENU_ID_ABOUT:
          void callbacks.onAbout();
          break;
        case MENU_ID_SETTINGS:
          void callbacks.onSettings();
          break;
        case MENU_ID_TOGGLE_STREAM:
          void callbacks.onToggleStream();
          break;
        case MENU_ID_TOGGLE_WINDOW:
          void callbacks.onToggleWindow();
          break;
        case MENU_ID_MUTE:
          void callbacks.onToggleMute();
          break;
        case MENU_ID_MONITORING:
          void callbacks.onToggleMonitoring();
          break;
        case MENU_ID_POCKET:
          void callbacks.onTogglePocket();
          break;
        default:
          console.warn("Unknown app-menu-action id:", id);
      }
    });

    await push();
  });

  watch([state, locale], () => {
    void push();
  });

  onBeforeUnmount(() => {
    if (unlisten) unlisten();
  });

  return {
    push,
  };
}
