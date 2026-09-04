<template>
  <div
    class="floating-container"
    @pointerdown="handlePointerDown"
    @pointermove="handlePointerMove"
    @pointerup="handlePointerUp"
    @pointercancel="handlePointerUp"
    @dblclick="handleDoubleClick"
  >
    <svg
      viewBox="0 0 40 40"
      class="floating-svg"
      xmlns="http://www.w3.org/2000/svg"
    >
      <!-- Base Background Contrast Disc -->
      <circle cx="20" cy="20" r="18" fill="#0f172a" fill-opacity="0.92" />

      <!-- 1. Muted State: Error Red Ring & Diagonal Slash -->
      <g v-if="isMuted">
        <circle
          cx="20"
          cy="20"
          r="15"
          fill="none"
          stroke="#ef4444"
          stroke-width="3"
          stroke-opacity="0.95"
        />
        <line
          x1="9.5"
          y1="9.5"
          x2="30.5"
          y2="30.5"
          stroke="#ef4444"
          stroke-width="3"
          stroke-linecap="round"
        />
      </g>

      <!-- 2. Active Volume Ring (Clean ring, no extra dots or wave bars) -->
      <g v-else>
        <!-- Background Track Ring (Theme Primary 25% Opacity) -->
        <circle
          cx="20"
          cy="20"
          r="15"
          fill="none"
          stroke="currentColor"
          class="text-primary"
          stroke-width="3"
          stroke-opacity="0.25"
        />
        <!-- Dynamic Volume Arc (Starts at top -90deg, sweeps clockwise) -->
        <circle
          v-if="safeAudioLevel > 0.005"
          cx="20"
          cy="20"
          r="15"
          fill="none"
          stroke="currentColor"
          class="text-primary"
          stroke-width="3"
          stroke-linecap="round"
          :stroke-dasharray="94.25"
          :stroke-dashoffset="94.25 * (1 - safeAudioLevel)"
          transform="rotate(-90 20 20)"
        />
      </g>
    </svg>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';
import { PhysicalPosition } from '@tauri-apps/api/dpi';
import { useTheme } from '@/features/theme/composables/useTheme';

// Activate theme synchronization for the floating window webview
useTheme();

const appWindow = getCurrentWebviewWindow();

const targetAudioLevel = ref(0);
const smoothAudioLevel = ref(0);
const isMuted = ref(false);

const safeAudioLevel = computed(() => Math.max(0, Math.min(1, smoothAudioLevel.value)));

let unlistenAudioLevel: UnlistenFn | null = null;
let unlistenMute: UnlistenFn | null = null;
let unlistenDeviceConnected: UnlistenFn | null = null;
let unlistenDeviceDisconnected: UnlistenFn | null = null;
let unlistenServerStopped: UnlistenFn | null = null;

let animationId = 0;

// Pointer Dragging & Click Handling
let isPointerDown = false;
let hasDragged = false;
let startScreenX = 0;
let startScreenY = 0;
let initialWinX = 0;
let initialWinY = 0;

async function handlePointerDown(e: PointerEvent) {
  if (e.button !== 0) return;
  isPointerDown = true;
  hasDragged = false;
  startScreenX = e.screenX;
  startScreenY = e.screenY;

  try {
    const pos = await appWindow.outerPosition();
    initialWinX = pos.x;
    initialWinY = pos.y;
  } catch (err) {
    console.error('get outerPosition failed:', err);
  }

  const el = e.currentTarget as HTMLElement;
  try {
    el.setPointerCapture(e.pointerId);
  } catch {}
}

async function handlePointerMove(e: PointerEvent) {
  if (!isPointerDown) return;
  const dx = e.screenX - startScreenX;
  const dy = e.screenY - startScreenY;

  if (!hasDragged && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) {
    hasDragged = true;
    try {
      await appWindow.startDragging();
      return;
    } catch {
      // Fallback to manual setPosition if startDragging is not available
    }
  }

  if (hasDragged) {
    const scale = window.devicePixelRatio || 1;
    const targetX = Math.round(initialWinX + dx * scale);
    const targetY = Math.round(initialWinY + dy * scale);
    try {
      await appWindow.setPosition(new PhysicalPosition(targetX, targetY));
    } catch {}
  }
}

function handlePointerUp(e: PointerEvent) {
  if (!isPointerDown) return;
  isPointerDown = false;

  const el = e.currentTarget as HTMLElement;
  try {
    el.releasePointerCapture(e.pointerId);
  } catch {}

  if (!hasDragged) {
    toggleMute();
  }
  hasDragged = false;
}

function handleDoubleClick() {
  invoke('show_main_window').catch((err) => console.error('show_main_window failed:', err));
}

async function toggleMute() {
  const targetMute = !isMuted.value;
  isMuted.value = targetMute;
  try {
    await invoke('set_mute_state', { isMuted: targetMute });
  } catch (e) {
    console.error('set_mute_state failed:', e);
  }
}

function animate() {
  // Smooth lerp tracking
  const diff = targetAudioLevel.value - smoothAudioLevel.value;
  smoothAudioLevel.value += diff * 0.2;
  animationId = requestAnimationFrame(animate);
}

interface StreamingStatus {
  isServerRunning: boolean;
  isConnected: boolean;
  isMuted: boolean;
}

onMounted(async () => {
  unlistenAudioLevel = await listen<number>('audio-level', (event) => {
    targetAudioLevel.value = Math.min(1, Math.max(0, event.payload / 100));
  });

  unlistenMute = await listen<boolean>('mute-state-changed', (event) => {
    isMuted.value = event.payload;
  });

  unlistenDeviceConnected = await listen('device-connected', () => {
    // Device connected
  });

  unlistenDeviceDisconnected = await listen('device-disconnected', () => {
    targetAudioLevel.value = 0;
    smoothAudioLevel.value = 0;
  });

  unlistenServerStopped = await listen('server-stopped', () => {
    targetAudioLevel.value = 0;
    smoothAudioLevel.value = 0;
  });

  try {
    const status = await invoke<StreamingStatus>('get_streaming_status');
    isMuted.value = status.isMuted;
  } catch (e) {
    console.error('get_streaming_status failed:', e);
  }

  animationId = requestAnimationFrame(animate);
});

onUnmounted(() => {
  if (animationId) cancelAnimationFrame(animationId);
  if (unlistenAudioLevel) unlistenAudioLevel();
  if (unlistenMute) unlistenMute();
  if (unlistenDeviceConnected) unlistenDeviceConnected();
  if (unlistenDeviceDisconnected) unlistenDeviceDisconnected();
  if (unlistenServerStopped) unlistenServerStopped();
});
</script>

<style>
/* Global resets for transparent floating window */
html, body, #app {
  background: transparent !important;
  background-color: transparent !important;
  margin: 0 !important;
  padding: 0 !important;
  width: 100% !important;
  height: 100% !important;
  overflow: hidden !important;
  user-select: none !important;
  touch-action: none !important;
}

.floating-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  background: transparent;
  overflow: hidden;
  user-select: none;
  touch-action: none;
}

.floating-container:active {
  cursor: grabbing;
}

.floating-svg {
  width: 40px;
  height: 40px;
  display: block;
  user-select: none;
  pointer-events: none;
}
</style>
