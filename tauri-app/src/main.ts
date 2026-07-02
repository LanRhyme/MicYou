import { createApp } from "vue";
import "./shared/assets/index.css";
import App from "./App.vue";
import PopupWindow from "./shared/components/PopupWindow.vue";
import IpPopup from "./features/connection/components/IpPopup.vue";
import { createI18n } from "vue-i18n";

import en from "./shared/locales/en.json";
import zh from "./shared/locales/zh.json";
import zhHk from "./shared/locales/zh-hk.json";
import zhTw from "./shared/locales/zh-tw.json";
import zhSs from "./shared/locales/zh-ss.json";
import cat from "./shared/locales/cat.json";
import lzh from "./shared/locales/lzh.json";

const savedLocale = localStorage.getItem("micyou_language") || "system";

const getSystemLocale = () => {
  return navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en";
};

let initialLocale = "en";
if (savedLocale === "en" || savedLocale === "English") {
  initialLocale = "en";
} else if (savedLocale === "zh" || savedLocale === "简体中文") {
  initialLocale = "zh";
} else if (savedLocale === "cat" || savedLocale === "喵喵语") {
  initialLocale = "cat";
} else if (savedLocale === "zh-hk" || savedLocale === "繁體中文（香港）") {
  initialLocale = "zh-hk";
} else if (savedLocale === "zh-tw" || savedLocale === "繁體中文（台灣）") {
  initialLocale = "zh-tw";
} else if (savedLocale === "zh-ss" || savedLocale === "中国人（坚硬）") {
  initialLocale = "zh-ss";
} else if (savedLocale === "lzh" || savedLocale === "文言") {
  initialLocale = "lzh";
} else {
  initialLocale = getSystemLocale();
}

const i18n = createI18n({
  legacy: false,
  locale: initialLocale,
  fallbackLocale: "en",
  messages: { en, zh, "zh-hk": zhHk, "zh-tw": zhTw, "zh-ss": zhSs, cat, lzh }
});

const hash = window.location.hash;
let RootComponent = App;

if (hash === '#/popup/ip') {
  RootComponent = IpPopup;
} else if (hash.startsWith('#/popup')) {
  RootComponent = PopupWindow;
}

const app = createApp(RootComponent);
app.use(i18n);
app.mount("#app");
