import { ref, watch } from 'vue';
import { setLocale } from '@/i18n';
import { saveUiPrefs } from '@/features/theme/composables/useTheme';

let stored = localStorage.getItem('micyou_language') || 'system';
if (stored === 'English') stored = 'en';
if (stored === '简体中文') stored = 'zh';

const currentLanguage = ref(stored);

watch(currentLanguage, (language) => {
  localStorage.setItem('micyou_language', language);
  setLocale(language);
  // Share the language with the CLI via ui.json
  saveUiPrefs(language);
});

/** The language setting: "system" or a locale key. */
export function useLanguageSetting() {
  return {
    currentLanguage,
    saveUiPrefs: () => saveUiPrefs(currentLanguage.value),
  };
}
