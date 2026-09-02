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
      viewBox="0 0 36 36"
      class="floating-svg"
      xmlns="http://www.w3.org/2000/svg"
    >
      <!-- Base Background Contrast Disc -->
      <circle cx="18" cy="18" r="16.5" fill="#0f172a" fill-opacity="0.94" />

      <!-- 1. Muted State (KMP 1:1 MutedVisualizer) -->
      <g v-if="isMuted">
        <!-- Error Red Ring -->
        <circle
          cx="18"
          cy="18"
          r="13.5"
          fill="none"
          stroke="#ef4444"
          stroke-width="3"
          stroke-opacity="0.90"
        />
        <!-- Error Red Diagonal Slash -->
        <line
          x1="8.5"
          y1="8.5"
          x2="27.5"
          y2="27.5"
          stroke="#ef4444"
          stroke-width="2.6"
          stroke-linecap="round"
        />
      </g>

      <!-- 2. Active Visualizer (Idle & Streaming with Theme Color) -->
      <g v-else>
        <!-- Base Ring (Theme Color 35% alpha) -->
        <circle
          cx="18"
          cy="18"
          r="13.5"
          fill="none"
          :stroke="themeColorHex"
          stroke-width="3"
          stroke-opacity="0.35"
        />
        <!-- Dynamic Level Arc (Start -90 deg) -->
        <circle
          v-if="safeAudioLevel > 0.01"
          cx="18"
          cy="18"
          r="13.5"
          fill="none"
          :stroke="themeColorHex"
          stroke-width="3"
          stroke-linecap="round"
          :stroke-dasharray="84.82"
          :stroke-dashoffset="84.82 * (1 - safeAudioLevel)"
          transform="rotate(-90 18 18)"
        />
        <!-- Center Dot / Glow -->
        <circle
          cx="18"
          cy="18"
          :r="2.5 + 4.0 * safeAudioLevel"
          :fill="themeColorHex"
          :fill-opacity="0.3 + 0.4 * safeAudioLevel"
        />
      </g>
    </svg>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';

const targetAudioLevel = ref(0);
const smoothAudioLevel = ref(0);
const isMuted = ref(false);
const isStreaming = ref(true);
const themeColorHex = ref('#bca28f');

const safeAudioLevel = computed(() => Math.max(0, Math.min(1, smoothAudioLevel.value)));

let unlistenAudioLevel: UnlistenFn | null = null;
let unlistenMute: UnlistenFn | null = null;
let unlistenDeviceConnected: UnlistenFn | null = null;
let unlistenDeviceDisconnected: UnlistenFn | null = null;
let unlistenServerStopped: UnlistenFn | null = null;
let unlistenTheme: UnlistenFn | null = null;

let animationId = 0;

// Pointer Events Dragging & Click Handling
let isDragging = false;
let startX = 0;
let startY = 0;
let hasMoved = false;

let pendingDx = 0;
let pendingDy = 0;
let dragRafId = 0;

function flushDrag() {
  if (pendingDx !== 0 || pendingDy !== 0) {
    invoke('move_floating_window_delta', { deltaX: pendingDx, deltaY: pendingDy }).catch(() => {});
    pendingDx = 0;
    pendingDy = 0;
  }
  if (isDragging) {
    dragRafId = requestAnimationFrame(flushDrag);
  }
}

function handlePointerDown(e: PointerEvent) {
  if (e.button !== 0) return;
  const el = e.currentTarget as HTMLElement;
  try {
    el.setPointerCapture(e.pointerId);
  } catch {}
  isDragging = true;
  hasMoved = false;
  startX = e.clientX;
  startY = e.clientY;
  pendingDx = 0;
  pendingDy = 0;
  dragRafId = requestAnimationFrame(flushDrag);
}

function handlePointerMove(e: PointerEvent) {
  if (!isDragging) return;
  
  if (!hasMoved) {
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
      hasMoved = true;
      pendingDx += dx;
      pendingDy += dy;
    }
  } else {
    pendingDx += e.movementX;
    pendingDy += e.movementY;
  }
}

function handlePointerUp(e: PointerEvent) {
  if (!isDragging) return;
  isDragging = false;
  cancelAnimationFrame(dragRafId);
  
  if (pendingDx !== 0 || pendingDy !== 0) {
    invoke('move_floating_window_delta', { deltaX: pendingDx, deltaY: pendingDy }).catch(() => {});
    pendingDx = 0;
    pendingDy = 0;
  }
  
  const el = e.currentTarget as HTMLElement;
  try {
    el.releasePointerCapture(e.pointerId);
  } catch {}
  if (!hasMoved) {
    toggleMute();
  }
  hasMoved = false;
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
  // Fluid lerp tracking
  smoothAudioLevel.value = smoothAudioLevel.value + (targetAudioLevel.value - smoothAudioLevel.value) * 0.18;

  animationId = requestAnimationFrame(animate);
}

interface StreamingStatus {
  isServerRunning: boolean;
  isConnected: boolean;
  isMuted: boolean;
}

interface ThemeColors {
  primary: string;
  secondary: string;
  tertiary: string;
}

async function syncTheme() {
  try {
    const colors = await invoke<ThemeColors>('get_theme_colors');
    if (colors?.primary && colors.primary.startsWith('#')) {
      themeColorHex.value = colors.primary;
    }
  } catch (e) {
    console.error('get_theme_colors failed:', e);
  }
}

onMounted(async () => {
  await syncTheme();

  unlistenAudioLevel = await listen<number>('audio-level', (event) => {
    const raw = Math.min(1, Math.max(0, event.payload / 100));
    targetAudioLevel.value = raw;
    isStreaming.value = true;
  });

  unlistenMute = await listen<boolean>('mute-state-changed', (event) => {
    isMuted.value = event.payload;
  });

  unlistenDeviceConnected = await listen('device-connected', () => {
    isStreaming.value = true;
  });

  unlistenDeviceDisconnected = await listen('device-disconnected', () => {
    targetAudioLevel.value = 0;
    smoothAudioLevel.value = 0;
  });

  unlistenServerStopped = await listen('server-stopped', () => {
    isStreaming.value = false;
    targetAudioLevel.value = 0;
    smoothAudioLevel.value = 0;
  });

  unlistenTheme = await listen<ThemeColors>('theme-colors-changed', (event) => {
    if (event.payload?.primary && event.payload.primary.startsWith('#')) {
      themeColorHex.value = event.payload.primary;
    }
  });

  try {
    const status = await invoke<StreamingStatus>('get_streaming_status');
    isStreaming.value = status.isServerRunning || status.isConnected;
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
  if (unlistenTheme) unlistenTheme();
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
  width: 36px;
  height: 36px;
  display: block;
  user-select: none;
  pointer-events: none;
}
</style>
