import { ref, watch } from 'vue';
import { isLocale, setLocale } from '@/i18n';
import { saveUiPrefs } from '@/features/theme/composables/useTheme';

const stored = localStorage.getItem('micyou_language');
const currentLanguage = ref(isLocale(stored) ? stored : 'system');

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
