<template>
  <Transition name="dialog">
  <div v-if="isOpen" class="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm" @click.self="close">
    <div class="bg-surface rounded-3xl w-full max-w-sm shadow-xl overflow-hidden flex flex-col">
      <!-- Header -->
      <div class="flex justify-between items-center p-6 pb-2">
        <div>
          <h2 class="text-xl font-bold text-primary">{{ $t('settings.audioChain.title') }}</h2>
          <p class="text-xs text-on-surface-variant mt-1">{{ $t('settings.audioChain.descPopup') }}</p>
        </div>
        <div class="flex items-center gap-1">
          <button @click="resetChain" class="p-2 rounded-full hover:bg-surface-variant/50 transition-colors text-on-surface-variant hover:text-primary" :title="$t('settings.audioChain.reset')">
            <RotateCcw class="w-4 h-4" />
          </button>
          <button @click="close" class="p-2 rounded-full hover:bg-surface-variant/50 transition-colors">
            <X class="w-5 h-5 text-on-surface" />
          </button>
        </div>
      </div>

      <!-- Content -->
      <div class="p-6">
        <div class="flex flex-col gap-2 relative">
          <div v-for="(item, index) in localChain" :key="item"
               :data-index="index"
               class="flex items-center bg-surface-container rounded-xl p-3 border-2 transition-all shadow-sm group select-none relative"
               :class="draggedIndex === index ? 'opacity-40 border-primary scale-[0.98] pointer-events-none' : 'border-transparent hover:border-primary/30'">
            
            <div @pointerdown.prevent="onPointerDown(index)"
                 class="w-8 h-8 -ml-2 mr-1 flex items-center justify-center cursor-grab active:cursor-grabbing hover:bg-surface-variant/50 rounded-lg group-hover:text-primary transition-colors opacity-50 group-hover:opacity-100 touch-none">
              <GripVertical class="w-5 h-5 text-on-surface-variant" />
            </div>
            
            <div class="w-6 h-6 rounded-full bg-surface flex items-center justify-center text-xs font-bold text-on-surface-variant mr-3 shadow-inner border border-surface-variant/30 group-hover:text-primary group-hover:border-primary/50 transition-colors pointer-events-none">
              {{ index + 1 }}
            </div>
            
            <span class="text-sm font-bold text-on-surface flex-1 pointer-events-none">{{ $t(`settings.audioChain.${item}`) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { X, GripVertical, RotateCcw } from '@lucide/vue';

const props = defineProps<{ isOpen: boolean, chain: string[] }>();
const emit = defineEmits(['close', 'update:chain']);

const localChain = ref<string[]>([]);

watch(() => props.isOpen, (newVal) => {
  if (newVal) {
    localChain.value = [...props.chain];
  }
});

const draggedIndex = ref<number>(-1);

const onPointerDown = (index: number) => {
  draggedIndex.value = index;
  
  if (typeof window !== 'undefined') {
    window.addEventListener('pointermove', onPointerMove, { passive: false });
    window.addEventListener('pointerup', onPointerUp);
    window.addEventListener('pointercancel', onPointerUp);
  }
};

const onPointerMove = (e: PointerEvent) => {
  e.preventDefault(); // Prevent scrolling on touch devices
  if (draggedIndex.value === -1) return;
  
  // Find the element under the pointer (since dragged item has pointer-events-none, it pierces through)
  const el = document.elementFromPoint(e.clientX, e.clientY);
  if (!el) return;
  
  const row = el.closest('[data-index]');
  if (row) {
    const hoverIndex = parseInt(row.getAttribute('data-index') || '-1', 10);
    if (hoverIndex !== -1 && hoverIndex !== draggedIndex.value) {
      // Swap instantly
      const newChain = [...localChain.value];
      const draggedItem = newChain[draggedIndex.value];
      newChain.splice(draggedIndex.value, 1);
      newChain.splice(hoverIndex, 0, draggedItem);
      
      localChain.value = newChain;
      draggedIndex.value = hoverIndex;
    }
  }
};

const onPointerUp = async () => {
  if (draggedIndex.value !== -1) {
    await invoke('save_audio_chain', { chain: localChain.value }).catch(() => {});
    emit('update:chain', localChain.value);
  }
  draggedIndex.value = -1;
  
  if (typeof window !== 'undefined') {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerUp);
  }
};

onUnmounted(() => {
  onPointerUp();
});

const close = () => {
  emit('close');
};

const resetChain = () => {
  const defaultChain = ['NoiseReduction', 'Dereverb', 'Equalizer', 'Amplifier', 'AGC', 'VAD'];
  localChain.value = [...defaultChain];
  emit('update:chain', localChain.value);
};
</script>
