import { watchEffect } from 'vue';
import { useStorage } from '@vueuse/core';

export function useTheme() {
  const themeColor = useStorage('micyou_theme_color', 'theme-blue');
  const uiStyle = useStorage('micyou_ui_style', 'style-glass');
  const customH = useStorage('micyou_custom_h', 215);
  const customS = useStorage('micyou_custom_s', 35);
  const customL = useStorage('micyou_custom_l', 55);
  const customCss = useStorage('micyou_custom_css', '');

  watchEffect(() => {
    if (typeof document !== 'undefined') {
      let userStyle = document.getElementById('micyou-user-custom-css');
      if (!userStyle) {
        userStyle = document.createElement('style');
        userStyle.id = 'micyou-user-custom-css';
        document.head.appendChild(userStyle);
      }
      userStyle.innerHTML = customCss.value || '';
    }
  });

  watchEffect(() => {
    if (typeof document !== 'undefined') {
      const themes = ['theme-blue', 'theme-green', 'theme-rose', 'theme-purple', 'theme-orange', 'theme-amber', 'theme-teal', 'theme-cyan', 'theme-custom'];
      document.documentElement.classList.remove(...themes, 'style-default', 'style-glass');

      if (themeColor.value) document.documentElement.classList.add(themeColor.value);
      if (uiStyle.value) {
        document.documentElement.classList.add(uiStyle.value);
      }

      let dynamicStyle = document.getElementById('micyou-custom-theme');
      if (!dynamicStyle) {
        dynamicStyle = document.createElement('style');
        dynamicStyle.id = 'micyou-custom-theme';
        document.head.appendChild(dynamicStyle);
      }

      if (themeColor.value === 'theme-custom') {
        const h = customH.value;
        const s = customS.value;
        const l = customL.value;
        const lDark = Math.min(l + 10, 80);

        dynamicStyle.innerHTML = `
          :root, .theme-custom {
            --background: ${h} 15% 96%;
            --foreground: ${h} 15% 25%;
            --surface: ${h} 15% 98%;
            --on-surface: ${h} 15% 25%;
            --surface-bright: ${h} 15% 98%;
            --surface-container: ${h} 15% 92%;
            --surface-container-low: ${h} 15% 94%;
            --surface-variant: ${h} 15% 88%;
            --on-surface-variant: ${h} 15% 45%;
            --outline: ${h} 15% 80%;
            --border: ${h} 15% 80%;

            --primary: ${h} ${s}% ${l}%;
            --on-primary: ${h} ${s}% 92%;
            --primary-container: ${h} ${s}% 85%;
            --on-primary-container: ${h} ${s}% 25%;

            --secondary: ${h} 20% 90%;
            --on-secondary: ${h} 20% 25%;
            --secondary-container: ${h} 20% 90%;
            --on-secondary-container: ${h} 20% 25%;
            --tertiary: ${h} 20% 90%;
            --on-tertiary: ${h} 20% 25%;
            --error: 0 40% 55%;
            --on-error: 0 40% 92%;
          }

          .dark.theme-custom, .theme-custom .dark {
            --background: ${h} 15% 8%;
            --foreground: ${h} 15% 85%;
            --surface: ${h} 15% 10%;
            --on-surface: ${h} 15% 85%;
            --surface-bright: ${h} 15% 14%;
            --surface-container: ${h} 15% 16%;
            --surface-container-low: ${h} 15% 12%;
            --surface-variant: ${h} 15% 22%;
            --on-surface-variant: ${h} 15% 60%;
            --outline: ${h} 15% 20%;
            --border: ${h} 15% 20%;

            --primary: ${h} ${s}% ${lDark}%;
            --on-primary: ${h} ${s}% 20%;
            --primary-container: ${h} ${s}% 25%;
            --on-primary-container: ${h} ${s}% 85%;

            --secondary: ${h} 20% 16%;
            --on-secondary: ${h} 20% 85%;
            --secondary-container: ${h} 20% 16%;
            --on-secondary-container: ${h} 20% 85%;
            --tertiary: ${h} 20% 16%;
            --on-tertiary: ${h} 20% 85%;
            --error: 0 40% 65%;
            --on-error: 0 40% 20%;
          }
        `;
      } else {
        dynamicStyle.innerHTML = '';
      }
    }
  });

  return {
    themeColor, uiStyle, customH, customS, customL, customCss,
  };
}
