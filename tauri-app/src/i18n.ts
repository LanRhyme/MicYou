import { createI18n } from "vue-i18n";

import en from "./shared/locales/en.json";
import zh from "./shared/locales/zh.json";
import zhHk from "./shared/locales/zh-hk.json";
import zhTw from "./shared/locales/zh-tw.json";
import zhSs from "./shared/locales/zh-ss.json";
import cat from "./shared/locales/cat.json";
import lzh from "./shared/locales/lzh.json";

const getSystemLocale = () => {
  return navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en";
};

/** Maps the stored language setting ("system" or a locale) to a locale. */
export const resolveLocale = (saved: string | null) => {
  const value = saved || "system";
  if (value === "en" || value === "English") return "en";
  if (value === "zh" || value === "简体中文") return "zh";
  if (value === "cat" || value === "喵喵语") return "cat";
  if (value === "zh-hk" || value === "繁體中文（香港）") return "zh-hk";
  if (value === "zh-tw" || value === "繁體中文（台灣）") return "zh-tw";
  if (value === "zh-ss" || value === "中国人（坚硬）") return "zh-ss";
  if (value === "lzh" || value === "文言") return "lzh";
  return getSystemLocale();
};

export type AppLocale = ReturnType<typeof resolveLocale>;

export const i18n = createI18n({
  legacy: false,
  locale: resolveLocale(localStorage.getItem("micyou_language")),
  fallbackLocale: "en",
  messages: { en, zh, "zh-hk": zhHk, "zh-tw": zhTw, "zh-ss": zhSs, cat, lzh },
});

export function setLocale(setting: string) {
  i18n.global.locale.value = resolveLocale(setting) as typeof i18n.global.locale.value;
}

// Windows share localStorage; follow a language change made in another window.
window.addEventListener("storage", (event) => {
  if (event.key === "micyou_language") setLocale(event.newValue ?? "system");
});
