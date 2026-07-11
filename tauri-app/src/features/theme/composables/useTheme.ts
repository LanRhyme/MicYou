import { watchEffect } from 'vue';
import { useStorage } from '@vueuse/core';

// Represents an HSL color configuration
export interface HslColor {
  h: number;
  s: number;
  l: number;
}

// Built-in theme presets mapped by color key
const BUILTIN_THEMES: Record<string, HslColor> = {
  'theme-blue': { h: 215, s: 35, l: 55 },
  'theme-green': { h: 150, s: 30, l: 50 },
  'theme-rose': { h: 350, s: 40, l: 60 },
  'theme-purple': { h: 270, s: 30, l: 60 },
  'theme-orange': { h: 25, s: 40, l: 55 },
  'theme-amber': { h: 40, s: 40, l: 50 },
  'theme-teal': { h: 175, s: 30, l: 45 },
  'theme-cyan': { h: 190, s: 40, l: 45 },
};

/**
 * Dynamically generates a CSS block containing Material 3 compatible HSL color tokens.
 * Computes primary, secondary, tertiary, background, surface, and outline colors based on
 * the selected base HSL color, active variant style, and dark mode state.
 * 
 * @param baseH Base Hue (0 - 360)
 * @param baseS Base Saturation (0 - 100)
 * @param baseL Base Lightness (0 - 100)
 * @param variant Dynamic palette variant (e.g. Vibrant, Expressive, Monochrome)
 * @param isDark True for dark mode theme generation
 */
function generateThemeCSS(baseH: number, baseS: number, baseL: number, variant: string, isDark: boolean): string {
  let priH = baseH, priS = baseS, priL = baseL;
  let secH = baseH, secS = 20, secL = isDark ? 16 : 90;
  let terH = baseH, terS = 20, terL = isDark ? 16 : 90;
  let bgH = baseH, bgS = 15, bgL = isDark ? 8 : 96;
  let surH = baseH, surS = 15, surL = isDark ? 10 : 98;
  
  // Calculate variant-specific saturation and hue transformations
  switch (variant) {
    case 'Neutral':
      priS = Math.max(0, baseS - 15);
      secS = 10; terS = 10;
      bgS = 5; surS = 5;
      break;
    case 'Vibrant':
      priS = Math.min(100, baseS + 20);
      secS = 30; terS = 35;
      bgS = 25; surS = 25;
      break;
    case 'Expressive':
      secH = (baseH + 45) % 360;
      terH = (baseH + 90) % 360;
      surS = 20;
      break;
    case 'Rainbow':
      secH = (baseH + 120) % 360;
      terH = (baseH + 240) % 360;
      secS = 35; terS = 35;
      break;
    case 'FruitSalad':
      secH = (baseH + 60) % 360;
      terH = (baseH + 150) % 360;
      priS = Math.min(100, baseS + 10);
      secS = 30; terS = 30;
      break;
    case 'Monochrome':
      priS = 0; secS = 0; terS = 0;
      bgS = 0; surS = 0;
      break;
    case 'Fidelity':
      secS = Math.max(0, baseS - 10);
      terS = Math.max(0, baseS - 15);
      surS = Math.max(0, baseS - 20);
      bgS = Math.max(0, baseS - 25);
      break;
    case 'Content':
      secS = Math.max(0, baseS - 5);
      terS = Math.max(0, baseS - 10);
      surS = Math.max(0, baseS - 15);
      bgS = Math.max(0, baseS - 20);
      break;
    case 'TonalSpot':
    default:
      // Keep defaults as is
      break;
  }

  // Calculate lightness tokens based on target dark/light theme setting
  const fgL = isDark ? 85 : 25;
  const onPriL = isDark ? 20 : 92;
  const priContL = isDark ? 25 : 85;
  const onPriContL = isDark ? 85 : 25;

  const onSecL = isDark ? 85 : 25;
  const secContL = isDark ? 16 : 90;
  const onSecContL = isDark ? 85 : 25;

  const surBrightL = isDark ? 14 : 98;
  const surContL = isDark ? 16 : 92;
  const surContLowL = isDark ? 12 : 94;
  const surVarL = isDark ? 22 : 88;
  const onSurVarL = isDark ? 60 : 45;
  const outlineL = isDark ? 20 : 80;

  return `
    --background: ${bgH} ${bgS}% ${bgL}%;
    --foreground: ${surH} ${surS}% ${fgL}%;
    --surface: ${surH} ${surS}% ${surL}%;
    --on-surface: ${surH} ${surS}% ${fgL}%;
    --surface-bright: ${surH} ${surS}% ${surBrightL}%;
    --surface-container: ${surH} ${surS}% ${surContL}%;
    --surface-container-low: ${surH} ${surS}% ${surContLowL}%;
    --surface-variant: ${surH} ${surS}% ${surVarL}%;
    --on-surface-variant: ${surH} ${surS}% ${onSurVarL}%;
    --outline: ${surH} ${surS}% ${outlineL}%;
    --border: ${surH} ${surS}% ${outlineL}%;

    --primary: ${priH} ${priS}% ${isDark ? Math.min(priL + 10, 80) : priL}%;
    --on-primary: ${priH} ${priS}% ${onPriL}%;
    --primary-container: ${priH} ${priS}% ${priContL}%;
    --on-primary-container: ${priH} ${priS}% ${onPriContL}%;

    --secondary: ${secH} ${secS}% ${secL}%;
    --on-secondary: ${secH} ${secS}% ${onSecL}%;
    --secondary-container: ${secH} ${secS}% ${secContL}%;
    --on-secondary-container: ${secH} ${secS}% ${onSecContL}%;

    --tertiary: ${terH} ${terS}% ${terL}%;
    --on-tertiary: ${terH} ${terS}% ${onSecL}%;
    
    --error: 0 40% ${isDark ? 65 : 55}%;
    --on-error: 0 40% ${isDark ? 20 : 92}%;
  `;
}

/**
 * Composable for managing application theming.
 * Listens to configuration changes and applies class tags and dynamic custom CSS styles to document root.
 */
export function useTheme() {
  const themeColor = useStorage<string>('micyou_theme_color', 'theme-blue');
  const uiStyle = useStorage<string>('micyou_ui_style', 'style-default');
  const customH = useStorage<number>('micyou_custom_h', 215);
  const customS = useStorage<number>('micyou_custom_s', 35);
  const customL = useStorage<number>('micyou_custom_l', 55);
  const customVariant = useStorage<string>('micyou_custom_variant', 'TonalSpot');
  const customCss = useStorage<string>('micyou_custom_css', '');

  // Applies user custom CSS injector rules
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

  // Generates and injects the dynamic theme CSS palette
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

      let baseColor = BUILTIN_THEMES[themeColor.value];
      if (!baseColor) {
        baseColor = { h: customH.value, s: customS.value, l: customL.value };
      }

      const lightCSS = generateThemeCSS(baseColor.h, baseColor.s, baseColor.l, customVariant.value, false);
      const darkCSS = generateThemeCSS(baseColor.h, baseColor.s, baseColor.l, customVariant.value, true);

      dynamicStyle.innerHTML = `
        :root, :root[class] {
          ${lightCSS}
        }
        :root.dark, html.dark[class] {
          ${darkCSS}
        }
      `;
    }
  });

  return {
    themeColor,
    uiStyle,
    customH,
    customS,
    customL,
    customVariant,
    customCss,
  };
}

