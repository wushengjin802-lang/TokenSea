
<template>
  <svg viewBox="0 0 24 24" class="nav-svg" aria-hidden="true" v-html="path"></svg>
</template>
<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ name?: string }>()
const map: Record<string,string> = {
  home:'<path d="M4 10.5 12 4l8 6.5"/><path d="M6.5 9.5V20h11V9.5"/><path d="M10 20v-5h4v5"/>',
  box:'<path d="M4 7.5 12 4l8 3.5-8 3.5z"/><path d="M4 7.5V16.5L12 20l8-3.5V7.5"/><path d="M12 11v9"/>',
  key:'<circle cx="8" cy="12" r="3.5"/><path d="M11.5 12H20"/><path d="M17 12v-2"/><path d="M19 12v-2"/>',
  users:'<circle cx="9" cy="9" r="3"/><circle cx="17" cy="10" r="2.5"/><path d="M4.5 19c.8-2.6 3-4 5.5-4s4.7 1.4 5.5 4"/><path d="M14.5 18c.5-1.9 2-3 4-3 1.1 0 2.1.3 3 .9"/>',
  wallet:'<path d="M4 7.5h12.5A2.5 2.5 0 0 1 19 10v7.5H6A2 2 0 0 1 4 15.5z"/><path d="M4 8V6.8A1.8 1.8 0 0 1 5.8 5H18"/><path d="M15 12h4"/>',
  route:'<circle cx="6" cy="6" r="2"/><circle cx="18" cy="7" r="2"/><circle cx="12" cy="18" r="2"/><path d="M8 6h8"/><path d="M7 7.5 11 16"/><path d="M17 8.5 13 16"/>',
  shield:'<path d="M12 4 18.5 6.5V12c0 4.2-2.5 6.9-6.5 8-4-1.1-6.5-3.8-6.5-8V6.5z"/><path d="m9.5 12 1.8 1.8 3.2-3.6"/>',
  code:'<path d="M8.5 8 5 12l3.5 4"/><path d="M15.5 8 19 12l-3.5 4"/><path d="M13 6 11 18"/>',
}
const path = computed(() => map[props.name || 'box'] || map.box)
</script>
