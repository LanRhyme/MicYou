<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { command } from '@/platform';
import { usePluginPanelBridge } from '@/features/plugins/composables/usePluginPanelBridge';
import { sharedColorMode } from '@/features/theme/composables/useTheme';

// The settings window keys this component by panel, so props never change
// while it is mounted.
const props = defineProps<{
  pluginId: string;
  panelId: string;
}>();

const { locale } = useI18n();
const colorMode = sharedColorMode();
const { handleMessage } = usePluginPanelBridge(props.pluginId);

const panelHtml = ref('');
const loading = ref(false);
const error = ref<string | null>(null);

// Forwards the panel's console output and uncaught errors to the plugin log,
// so plugin developers can debug their panels.
const CONSOLE_HOOK = `<script>
  (function () {
    var orig = { log: console.log.bind(console), warn: console.warn.bind(console), error: console.error.bind(console) };
    function send(level) {
      return function () {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) {
          var a = arguments[i];
          parts.push(typeof a === 'string' ? a : (function () { try { return JSON.stringify(a); } catch (e) { return String(a); } })());
        }
        try {
          window.parent.postMessage({
            __micyou: 1,
            id: 'console-' + Math.random().toString(36).slice(2),
            api: 'log',
            args: { level: level, message: parts.join(' ') }
          }, '*');
        } catch (e) {}
        orig[level === 'warn' ? 'warn' : level === 'error' ? 'error' : 'log'].apply(console, arguments);
      };
    }
    console.log = send('info');
    console.warn = send('warn');
    console.error = send('error');
    window.addEventListener('error', function (e) {
      try {
        window.parent.postMessage({
          __micyou: 1,
          id: 'console-' + Math.random().toString(36).slice(2),
          api: 'log',
          args: { level: 'error', message: 'uncaught: ' + (e.message || e.error) }
        }, '*');
      } catch (err) {}
    });
  })();
<\/script>`;

/** The host theme as CSS variables, so panels match and follow the app theme. */
function collectThemeVars(): string {
  const style = getComputedStyle(document.documentElement);
  const vars: string[] = [];
  for (let i = 0; i < style.length; i++) {
    const name = style[i];
    if (name.startsWith('--')) {
      vars.push(`${name}: ${style.getPropertyValue(name)};`);
    }
  }
  return vars.join('\n');
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const html = await command('get_plugin_panel', {
      pluginId: props.pluginId,
      panelId: props.panelId,
    });
    panelHtml.value = `<style>:root{${collectThemeVars()}}</style>${CONSOLE_HOOK}${html}`;
  } catch (e) {
    error.value = String(e);
    panelHtml.value = '';
  } finally {
    loading.value = false;
  }
}

// Panels render with the host theme and locale, so reload when either changes.
watch([colorMode, locale], () => void load());

onMounted(() => {
  window.addEventListener('message', handleMessage);
  void load();
});
onUnmounted(() => window.removeEventListener('message', handleMessage));
</script>

<template>
  <div class="space-y-4">
    <div v-if="loading" class="flex items-center gap-2 text-sm text-on-surface-variant">
      <span
        class="animate-spin inline-block w-4 h-4 border-2 border-primary border-t-transparent rounded-full"
      ></span>
      {{ $t('plugins.loading') }}
    </div>
    <div
      v-else-if="error"
      class="text-sm text-red-400 bg-red-500/10 rounded-xl p-4 font-mono break-all"
    >
      {{ error }}
    </div>
    <iframe
      v-else
      :srcdoc="panelHtml"
      sandbox="allow-scripts allow-popups"
      class="w-full h-[600px] rounded-2xl border border-border"
      style="background: hsl(var(--surface))"
    ></iframe>
  </div>
</template>
