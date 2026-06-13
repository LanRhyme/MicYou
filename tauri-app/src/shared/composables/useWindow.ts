import { ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';

export function useWindow() {
  const appWindow = getCurrentWindow();
  const isHidden = ref(localStorage.getItem('micyou_start_minimized') === 'true');
  const showCloseConfirm = ref(false);

  const REMEMBER_KEY = 'micyou_remember_close_action';

  function minimizeWindow() {
    appWindow.minimize();
  }

  async function showMainWindow() {
    try {
      await invoke('show_main_window');
    } catch (e) {
      console.error('show_main_window failed:', e);
    }
    isHidden.value = false;
  }

  async function hideMainWindow() {
    try {
      await invoke('hide_main_window');
    } catch (e) {
      console.error('hide_main_window failed:', e);
    }
    isHidden.value = true;
  }

  async function exitApp() {
    try {
      await invoke('exit_app');
    } catch (e) {
      console.error('exit_app failed:', e);
    }
  }

  function requestClose() {
    const remembered = localStorage.getItem(REMEMBER_KEY);
    if (remembered === 'hide') {
      void hideMainWindow();
      return;
    }
    if (remembered === 'exit') {
      void exitApp();
      return;
    }
    showCloseConfirm.value = true;
  }

  function handleCloseSelect(payload: { action: 'hide' | 'exit'; remember: boolean }) {
    if (payload.remember) {
      localStorage.setItem(REMEMBER_KEY, payload.action);
    } else {
      localStorage.removeItem(REMEMBER_KEY);
    }
    if (payload.action === 'hide') {
      void hideMainWindow();
    } else {
      void exitApp();
    }
  }

  return {
    appWindow, isHidden, showCloseConfirm,
    minimizeWindow, showMainWindow, hideMainWindow, exitApp,
    requestClose, handleCloseSelect,
  };
}
