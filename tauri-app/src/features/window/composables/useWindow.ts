import { onMounted, onUnmounted, ref } from 'vue';
import { appWindow, command, type UnlistenFn } from '@/platform';

interface WindowAnimations {
  /** Plays the content exit animation before the window disappears. */
  exit: () => Promise<void>;
  /** Sets compositor effects while the window is still hidden. */
  prepare: () => Promise<void>;
  /** Plays the content enter animation after the window reappears. */
  enter: () => Promise<void>;
}

export function useWindow(animations?: WindowAnimations) {
  const isHidden = ref(localStorage.getItem('micyou_start_minimized') === 'true');
  const showCloseConfirm = ref(false);

  const REMEMBER_KEY = 'micyou_remember_close_action';

  async function minimizeWindow() {
    try {
      await command('minimize_main_window');
    } catch (e) {
      console.error('minimize_main_window failed:', e);
    }
  }

  // Effects first, so they appear with the window instead of a frame later.
  async function reveal() {
    await animations?.prepare();
    try {
      await command('show_main_window');
    } catch (e) {
      console.error('show_main_window failed:', e);
    }
    isHidden.value = false;
    await animations?.enter();
  }

  async function showMainWindow() {
    if (isHidden.value) {
      await reveal();
      return;
    }
    // Minimized: the content never left, the compositor restores the window.
    try {
      await command('show_main_window');
    } catch (e) {
      console.error('show_main_window failed:', e);
    }
  }

  /** Plays the launch animation, or hides the window when starting minimized. */
  async function launch() {
    if (!isHidden.value) {
      await reveal();
      return;
    }
    try {
      await command('hide_main_window');
    } catch (e) {
      console.error('hide_main_window failed:', e);
    }
  }

  // Closing animations already in flight; repeated clicks wait for them.
  let leaving: Promise<void> | null = null;
  function leave(action: () => Promise<void>): Promise<void> {
    leaving ??= (async () => {
      await animations?.exit();
      try {
        await action();
      } catch (e) {
        // The window stays: bring its content and effects back.
        await animations?.prepare();
        await animations?.enter();
        throw e;
      }
    })().finally(() => {
      leaving = null;
    });
    return leaving;
  }

  async function hideMainWindow() {
    try {
      await leave(() => command('hide_main_window'));
      isHidden.value = true;
    } catch (e) {
      console.error('hide_main_window failed:', e);
    }
  }

  async function exitApp() {
    try {
      await leave(() => command('exit_app'));
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

  // Compositor close requests (Alt+F4, taskbar) take the same path as the
  // close button: the remembered choice or the confirm dialog decides.
  let unlistenClose: UnlistenFn | null = null;
  onMounted(async () => {
    unlistenClose = await appWindow.onCloseRequested(requestClose);
  });
  onUnmounted(() => unlistenClose?.());

  return {
    isHidden, showCloseConfirm,
    minimizeWindow, showMainWindow, hideMainWindow, exitApp, launch,
    requestClose, handleCloseSelect,
  };
}
