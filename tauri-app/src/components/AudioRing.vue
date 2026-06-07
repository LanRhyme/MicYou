<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  level: number; // 0 to 100
}>();

const normalizedLevel = computed(() => Math.max(0, Math.min(100, props.level)) / 100);

const dotX = computed(() => 50 + 35 * Math.cos(normalizedLevel.value * Math.PI * 2));
const dotY = computed(() => 50 + 35 * Math.sin(normalizedLevel.value * Math.PI * 2));
</script>

<template>
  <div class="relative w-full h-full flex items-center justify-center">
    <svg class="absolute inset-0 w-full h-full -rotate-90" viewBox="0 0 100 100" overflow="visible">
      <!-- Background ring -->
      <circle cx="50" cy="50" r="35" fill="none" stroke="currentColor" stroke-width="2" class="opacity-20 text-primary" />
      
      <!-- Animated Arc -->
      <circle cx="50" cy="50" r="35" fill="none" stroke="currentColor" stroke-width="2" 
        class="text-primary transition-all duration-100 ease-linear"
        stroke-linecap="round"
        stroke-dasharray="219.9" 
        :stroke-dashoffset="219.9 * (1 - normalizedLevel)" />

      <!-- End Dot -->
      <circle 
        v-if="normalizedLevel > 0.05"
        :cx="dotX" 
        :cy="dotY" 
        r="1.6" fill="currentColor" class="text-primary transition-all duration-100 ease-linear" />

      <!-- Ticks -->
      <g v-for="i in 60" :key="i" :transform="`rotate(${i * 6} 50 50)`">
        <line 
          x1="50" y1="13" 
          x2="50" :y2="i % 5 === 0 ? 9 : 11" 
          stroke="currentColor" 
          :stroke-width="i % 5 === 0 ? 1 : 0.5"
          class="transition-colors duration-100"
          :class="(i / 60) <= normalizedLevel ? 'text-primary opacity-60' : 'text-primary opacity-10'" />
      </g>
    </svg>
    
    <!-- Slot for the central element -->
    <div class="relative z-10 flex items-center justify-center">
      <slot></slot>
    </div>
  </div>
</template>
