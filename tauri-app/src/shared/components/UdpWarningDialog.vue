<template>
  <Transition name="dialog">
    <div v-if="show" class="fixed inset-0 z-[100] flex items-center justify-center p-4">
      <!-- Backdrop -->
      <div class="absolute inset-0 bg-background/60 backdrop-blur-md" @click="close"></div>
      
      <!-- Dialog Panel -->
      <div class="relative w-full max-w-md bg-surface-bright/95 backdrop-blur-2xl rounded-3xl overflow-hidden shadow-2xl border border-white/10 flex flex-col transform transition-all">
        
        <!-- Decorative Glow -->
        <div class="absolute -top-32 -right-32 w-64 h-64 bg-error/20 rounded-full blur-[80px] pointer-events-none"></div>
        <div class="absolute -bottom-32 -left-32 w-64 h-64 bg-error/10 rounded-full blur-[80px] pointer-events-none"></div>

        <!-- Header Icon -->
        <div class="pt-8 pb-4 flex justify-center items-center relative z-10">
          <div class="relative">
            <div class="absolute inset-0 bg-error/20 rounded-full blur-xl animate-pulse"></div>
            <div class="w-20 h-20 rounded-2xl bg-gradient-to-br from-error/20 to-error/5 border border-error/20 flex items-center justify-center shadow-inner relative z-10">
              <ShieldAlertIcon class="w-10 h-10 text-error" stroke-width="1.5" />
            </div>
            <!-- Floating particles -->
            <div class="absolute -top-1 -right-1 w-3 h-3 rounded-full bg-error/50 blur-sm"></div>
            <div class="absolute bottom-2 -left-2 w-2 h-2 rounded-full bg-error/40 blur-sm"></div>
          </div>
        </div>

        <!-- Content -->
        <div class="px-8 pb-6 text-center space-y-4 relative z-10">
          <h3 class="text-xl font-extrabold text-on-surface tracking-wide">{{ $t('dialogs.udpWarning.title') }}</h3>
          
          <div class="bg-surface-container-highest/30 rounded-xl p-4 text-sm text-on-surface-variant text-left leading-relaxed border border-white/5 shadow-inner">
            {{ $t('dialogs.udpWarning.desc', { port: port }) }}
          </div>
        </div>

        <!-- Actions -->
        <div class="px-8 pb-8 flex flex-col gap-3 relative z-10">
          <button 
            @click="close" 
            class="w-full py-3.5 rounded-xl bg-error hover:bg-error/90 text-on-error font-bold shadow-lg shadow-error/20 hover:shadow-error/40 transition-all duration-300 hover:scale-[0.98] active:scale-95"
          >
            {{ $t('dialogs.close') }}
          </button>
        </div>
        
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ShieldAlert as ShieldAlertIcon } from '@lucide/vue'

defineProps<{
  show: boolean
  port: number
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const close = () => {
  emit('close')
}
</script>

<style scoped>
.dialog-enter-active,
.dialog-leave-active {
  transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-from .relative,
.dialog-leave-to .relative {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}
</style>
