import { createApp } from "vue";
import "./shared/assets/index.css";
import App from "./App.vue";
import PluginPanelWindow from "./features/plugins/components/PluginPanelWindow.vue";
import SettingsWindow from "./features/settings/components/SettingsWindow.vue";
import { i18n } from "./i18n";

const hash = window.location.hash;
const RootComponent = hash.startsWith('#/plugin/')
  ? PluginPanelWindow
  : hash === '#/settings'
    ? SettingsWindow
    : App;

const app = createApp(RootComponent);
app.use(i18n);
app.mount("#app");
