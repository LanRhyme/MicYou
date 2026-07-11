<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue';

const props = defineProps<{
  level: number; // 0 to 100
}>();

const smoothedLevel = ref(0);
let animFrame: number | null = null;

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

const animate = () => {
  const target = Math.max(0, Math.min(100, props.level)) / 100;
  smoothedLevel.value = lerp(smoothedLevel.value, target, 0.12);
  if (Math.abs(smoothedLevel.value - target) > 0.001) {
    animFrame = requestAnimationFrame(animate);
  } else {
    smoothedLevel.value = target;
    animFrame = null;
  }
};

watch(() => props.level, () => {
  if (animFrame === null) {
    animFrame = requestAnimationFrame(animate);
  }
}, { immediate: true });

onUnmounted(() => {
  if (animFrame !== null) cancelAnimationFrame(animFrame);
});

const dotX = computed(() => 50 + 35 * Math.cos(smoothedLevel.value * Math.PI * 2));
const dotY = computed(() => 50 + 35 * Math.sin(smoothedLevel.value * Math.PI * 2));
</script>

<template>
  <div class="relative w-full h-full flex items-center justify-center">
    <svg class="absolute inset-0 w-full h-full -rotate-90" viewBox="0 0 100 100" overflow="visible">
      <!-- Background ring -->
      <circle cx="50" cy="50" r="35" fill="none" stroke="currentColor" stroke-width="2" class="opacity-20 text-primary" />
      
      <!-- Animated Arc -->
      <circle cx="50" cy="50" r="35" fill="none" stroke="currentColor" stroke-width="2" 
        class="text-primary"
        stroke-linecap="round"
        stroke-dasharray="219.9" 
        :stroke-dashoffset="219.9 * (1 - smoothedLevel)" />

      <!-- End Dot -->
      <circle 
        v-if="smoothedLevel > 0.05"
        :cx="dotX" 
        :cy="dotY" 
        r="1.6" fill="currentColor" class="text-primary" />

      <!-- Ticks -->
      <g :style="{ '--level': smoothedLevel }">
        <g v-for="i in 60" :key="i" :transform="`rotate(${90 + (i - 1) * 6} 50 50)`">
          <line 
            x1="50" y1="13" 
            x2="50" :y2="(i - 1) % 5 === 0 ? 9 : 11" 
            stroke="currentColor" 
            :stroke-width="(i - 1) % 5 === 0 ? 1 : 0.5"
            class="text-primary"
            :style="{
              '--index': i - 1,
              opacity: 'clamp(0.1, calc((var(--level) * 60 - var(--index) + 0.001) * 9999), 0.6)'
            }" />
        </g>
      </g>
    </svg>
    
    <!-- Slot for the central element -->
    <div class="relative z-10 flex items-center justify-center">
      <slot></slot>
    </div>
  </div>
</template>
