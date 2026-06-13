<template>
  <Transition name="dialog">
    <div v-if="isOpen" class="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm" @click.self="close">
    <div class="bg-surface-bright/90 backdrop-blur-2xl rounded-3xl w-full max-w-3xl shadow-xl overflow-hidden flex flex-col h-[80vh] border border-white/10">
      <!-- Header -->
      <div class="flex justify-between items-center p-6 bg-surface/50 border-b border-surface-variant/20">
        <div>
          <h2 class="text-xl font-bold text-primary">{{ $t('settings.customCss.title') }}</h2>
          <p class="text-xs text-on-surface-variant">{{ $t('settings.customCss.desc') }}</p>
        </div>
        <div class="flex items-center gap-2">
          <button @click="triggerFileInput" class="px-4 py-2 rounded-full bg-primary/10 hover:bg-primary/20 text-primary font-medium text-sm transition-colors flex items-center gap-2">
            <Upload class="w-4 h-4" /> {{ $t('settings.customCss.loadFromFile') }}
          </button>
          <button @click="clearCss" class="px-4 py-2 rounded-full bg-error/10 hover:bg-error/20 text-error font-medium text-sm transition-colors flex items-center gap-2">
            <Trash2 class="w-4 h-4" /> {{ $t('settings.customCss.clear') }}
          </button>
          <button @click="close" class="p-2 ml-2 rounded-full hover:bg-surface-variant/50 transition-colors">
            <X class="w-5 h-5 text-on-surface" />
          </button>
        </div>
      </div>
      
      <!-- Content -->
      <div class="flex-1 overflow-hidden p-6 bg-surface-container-lowest/50 flex flex-col">
        <div class="flex-1 rounded-xl overflow-hidden border border-surface-variant/20 shadow-inner bg-surface">
          <Codemirror
            v-model="customCss"
            :placeholder="$t('settings.customCss.placeholder')"
            :style="{ height: '100%', width: '100%' }"
            :autofocus="true"
            :indent-with-tab="true"
            :tab-size="2"
            :extensions="extensions"
          />
        </div>
      </div>

      <!-- Hidden File Input -->
      <input 
        type="file" 
        ref="fileInput" 
        class="hidden" 
        accept=".css,.txt" 
        @change="handleFileChange" 
      />
    </div>
  </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useStorage } from '@vueuse/core';
import { X, Upload, Trash2 } from 'lucide-vue-next';
import { Codemirror } from 'vue-codemirror';
import { css } from '@codemirror/lang-css';
import { oneDark } from '@codemirror/theme-one-dark';

defineProps<{ isOpen: boolean }>();
const emit = defineEmits(['close']);

const extensions = [css(), oneDark];

const customCss = useStorage('micyou_custom_css', '');
const fileInput = ref<HTMLInputElement | null>(null);

const triggerFileInput = () => {
  fileInput.value?.click();
};

const handleFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = (e) => {
    const content = e.target?.result;
    if (typeof content === 'string') {
      customCss.value = content;
    }
  };
  reader.readAsText(file);
  
  // Reset input so the same file can be selected again
  target.value = '';
};

const clearCss = () => {
  customCss.value = '';
};

const close = () => {
  emit('close');
};
</script>
