<template>
  <Transition name="dialog">
  <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm" @click.self="close">
    <div class="bg-surface rounded-3xl w-full max-w-lg shadow-xl overflow-hidden flex flex-col max-h-[80vh]">
      <!-- Header -->
      <div class="flex justify-between items-center p-6 bg-surface border-b border-surface-variant/20">
        <div>
          <h2 class="text-xl font-bold text-primary">{{ $t('dialogs.licenses.title') }}</h2>
          <p class="text-xs text-on-surface-variant">{{ $t('settings.about.licensesDesc') }}</p>
        </div>
        <button @click="close" class="p-2 rounded-full hover:bg-surface-variant/50 transition-colors">
          <X class="w-5 h-5 text-on-surface" />
        </button>
      </div>
      
      <!-- Content -->
      <div class="flex-1 overflow-y-auto p-6 flex flex-col gap-4">
        <div v-for="lib in libraries" :key="lib.name" class="bg-surface rounded-xl p-4 border border-surface-variant/20">
          <div class="flex justify-between items-start mb-2">
            <div>
              <h3 class="text-sm font-bold text-on-surface">{{ lib.name }}</h3>
              <p class="text-xs text-on-surface-variant">Version: {{ lib.version }}</p>
            </div>
            <span class="text-xs font-medium px-2 py-1 bg-surface-variant/50 text-on-surface-variant rounded-md">
              {{ lib.license }}
            </span>
          </div>
          <a v-if="lib.url" :href="lib.url" target="_blank" class="text-xs text-primary hover:underline block truncate">
            {{ lib.url }}
          </a>
        </div>
      </div>
    </div>
  </div>
  </Transition>
</template>

<script setup lang="ts">
import { X } from '@lucide/vue';

defineProps<{ isOpen: boolean }>();
const emit = defineEmits(['close']);

const libraries = [
  { name: 'Tauri', version: '2.0', license: 'MIT/Apache-2.0', url: 'https://github.com/tauri-apps/tauri' },
  { name: 'Vue.js', version: '3.x', license: 'MIT', url: 'https://github.com/vuejs/core' },
  { name: 'Tailwind CSS', version: '3.4', license: 'MIT', url: 'https://github.com/tailwindlabs/tailwindcss' },
  { name: 'Lucide Icons', version: 'latest', license: 'ISC', url: 'https://github.com/lucide-icons/lucide' },
  { name: 'ONNX Runtime', version: '1.x', license: 'MIT', url: 'https://github.com/microsoft/onnxruntime' },
  { name: 'CPAL', version: '0.15', license: 'Apache-2.0', url: 'https://github.com/RustAudio/cpal' },
  { name: 'Rubato', version: '3.0', license: 'MIT', url: 'https://github.com/HEnquist/rubato' },
  { name: 'Audiopus', version: '0.2', license: 'BSD-3-Clause', url: 'https://github.com/RustAudio/audiopus' },
  { name: 'RustFFT', version: '6.4', license: 'MIT/Apache-2.0', url: 'https://github.com/ejmahler/RustFFT' },
];

const close = () => {
  emit('close');
};
</script>
