<script setup lang="ts">
import type { SliderRootEmits, SliderRootProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import { SliderRange, SliderRoot, SliderThumb, SliderTrack, useForwardPropsEmits } from "reka-ui"
import { cn } from "@/shared/lib/utils"

const props = defineProps<SliderRootProps & { class?: HTMLAttributes["class"] }>()
const emits = defineEmits<SliderRootEmits>()

const delegatedProps = reactiveOmit(props, "class")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <SliderRoot
    :class="cn(
      'group relative flex w-full touch-none select-none items-center data-[orientation=vertical]:flex-col data-[orientation=vertical]:w-2 data-[orientation=vertical]:h-full py-4',
      props.class,
    )"
    v-bind="forwarded"
  >
    <SliderTrack class="relative h-3 w-full data-[orientation=vertical]:w-3 grow overflow-hidden rounded-full bg-secondary/60 transition-colors duration-300 group-hover:bg-secondary">
      <SliderRange class="absolute h-full data-[orientation=vertical]:w-full bg-primary transition-all duration-300 ease-out" />
    </SliderTrack>
    <SliderThumb
      v-for="(_, key) in modelValue"
      :key="key"
      class="relative block h-5 w-5 rounded-full bg-primary shadow-md ring-offset-background transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] hover:scale-125 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-primary/30 focus-visible:scale-125 disabled:pointer-events-none disabled:opacity-50 border-0"
    >
      <!-- Hover Halo -->
      <div class="absolute inset-0 rounded-full bg-primary opacity-0 transition-opacity duration-300 group-hover:opacity-20 scale-[2.5] pointer-events-none -z-10"></div>
    </SliderThumb>
  </SliderRoot>
</template>
