<script setup lang="ts">
import type { SwitchRootEmits, SwitchRootProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import {
  SwitchRoot,
  SwitchThumb,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from "@/shared/lib/utils"

const props = defineProps<SwitchRootProps & { class?: HTMLAttributes["class"] }>()

const emits = defineEmits<SwitchRootEmits>()

const checked = defineModel<boolean>()

const delegatedProps = reactiveOmit(props, "class")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <SwitchRoot
    v-bind="forwarded"
    :class="cn(
      'group peer inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=unchecked]:border-muted-foreground/30 data-[state=unchecked]:bg-secondary hover:data-[state=unchecked]:bg-muted-foreground/20 hover:data-[state=checked]:bg-primary/90 active:scale-95',
      props.class,
    )"
  >
    <div
      class="relative flex items-center justify-center transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] data-[state=checked]:translate-x-[26px] data-[state=unchecked]:translate-x-[4px]"
      :data-state="checked ? 'checked' : 'unchecked'"
    >
      <!-- State layer (hover halo) -->
      <div class="absolute inset-0 scale-0 rounded-full bg-current opacity-0 transition-all duration-300 group-hover:scale-150 group-hover:opacity-10 data-[state=checked]:text-background data-[state=unchecked]:text-foreground" :data-state="checked ? 'checked' : 'unchecked'"></div>
      
      <SwitchThumb
        :class="cn(
          'pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]',
          'data-[state=checked]:h-6 data-[state=checked]:w-6 data-[state=checked]:bg-background',
          'data-[state=unchecked]:h-4 data-[state=unchecked]:w-4 data-[state=unchecked]:bg-muted-foreground group-hover:data-[state=unchecked]:h-5 group-hover:data-[state=unchecked]:w-5',
        )"
      >
        <slot name="thumb" />
      </SwitchThumb>
    </div>
  </SwitchRoot>
</template>
