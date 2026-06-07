<template>
  <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-8 bg-black/40 backdrop-blur-sm">
    <div class="bg-surface-bright w-full max-w-5xl h-full max-h-[80vh] rounded-3xl flex overflow-hidden shadow-2xl relative border border-white/10">
      
      <!-- Close Button -->
      <button @click="$emit('close')" class="absolute top-4 right-4 z-10 w-10 h-10 rounded-full bg-surface-variant/40 hover:bg-surface-variant/80 flex items-center justify-center transition-colors">
        <X class="w-5 h-5 text-on-surface" />
      </button>

      <!-- Left Sidebar -->
      <div class="w-64 bg-surface-container-low border-r border-surface-variant/30 flex flex-col p-4 space-y-2 overflow-y-auto">
        <div class="px-4 py-4 mb-4 flex items-center gap-3">
          <SettingsIcon class="w-6 h-6 text-primary" />
          <h2 class="text-xl font-bold text-primary">{{ $t('settings.title') }}</h2>
        </div>

        <button v-for="section in sections" :key="section.id" 
                @click="currentSection = section.id"
                class="flex items-center gap-3 px-4 py-3 rounded-2xl transition-all duration-300 w-full text-left"
                :class="currentSection === section.id ? 'bg-secondary-container text-on-secondary-container shadow-sm scale-[1.02]' : 'hover:bg-surface-variant/30 text-on-surface-variant'">
          <component :is="section.icon" class="w-5 h-5" :class="currentSection === section.id ? 'text-primary' : ''" />
          <span class="font-medium text-sm">{{ section.name }}</span>
        </button>
      </div>

      <!-- Right Content -->
      <div class="flex-1 bg-surface-container-lowest p-8 overflow-y-auto">
        <div class="max-w-2xl mx-auto space-y-8">
          <h3 class="text-3xl font-bold text-primary mb-6">{{ currentSectionName }}</h3>

          <!-- GENERAL SECTION -->
          <div v-if="currentSection === 'general'" class="space-y-6">
            <div class="bg-surface-bright rounded-2xl p-4 flex items-center justify-between shadow-sm">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('settings.language.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.language.desc') }}</p>
              </div>
              <Select v-model="currentLanguage">
                <SelectTrigger class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium">
                  <SelectValue placeholder="Language" />
                </SelectTrigger>
                <SelectContent class="border-surface-variant/20 rounded-lg bg-surface shadow-lg">
                  <SelectGroup>
                    <SelectItem value="system">{{ $t('settings.language.system') }}</SelectItem>
                    <SelectItem value="en">English</SelectItem>
                    <SelectItem value="zh">简体中文</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>

            <!-- Output Device -->
            <div class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('settings.audioOutput.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.audioOutput.desc') }}</p>
              </div>
              <div class="relative">
                <Select v-model="settings.audioDevice">
                  <SelectTrigger class="w-full bg-surface-container border-none shadow-none rounded-xl h-12 px-4 font-medium text-sm">
                    <SelectValue :placeholder="$t('settings.audioOutput.systemDefault')" />
                  </SelectTrigger>
                  <SelectContent class="border-surface-variant/20 rounded-xl bg-surface shadow-lg max-h-[40vh]">
                    <SelectGroup>
                      <SelectItem value="default">{{ $t('settings.audioOutput.systemDefault') }}</SelectItem>
                      <SelectItem v-for="dev in audioDevices" :key="dev" :value="dev">{{ dev }}</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
              <p v-if="settings.audioDevice.includes('CABLE Input')" class="text-xs text-green-400 font-medium">
                {{ $t('settings.audioOutput.routingActive') }}
              </p>
            </div>

            <!-- VB-CABLE Management -->
            <div class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
              <div class="flex items-center justify-between">
                <h4 class="font-bold text-on-surface text-lg">{{ $t('settings.vbcable.title') }}</h4>
                <span class="text-xs font-medium px-2 py-1 rounded-md" :class="hasVBCable ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'">
                  {{ hasVBCable ? $t('settings.vbcable.installed') : $t('settings.vbcable.notDetected') }}
                </span>
              </div>
              <p class="text-xs text-on-surface-variant">
                {{ $t('settings.vbcable.desc') }}
              </p>
              <button v-if="!hasVBCable" class="w-full py-2 bg-surface-variant hover:bg-surface-variant/80 rounded-xl text-sm font-bold flex items-center justify-center gap-2 transition-colors">
                <Download class="w-4 h-4" /> {{ $t('settings.vbcable.download') }}
              </button>
            </div>
          </div>

          <!-- AUDIO SECTION -->
          <div v-if="currentSection === 'audio'" class="space-y-6">
            

            <!-- Spectrum Analyzer / Real-time Monitoring -->
            <div class="bg-surface-bright rounded-2xl p-4 space-y-3 shadow-sm">
              <div class="flex justify-between items-center mb-2">
                <h4 class="font-bold text-on-surface text-sm">{{ $t('settings.spectrum.title') }}</h4>
                <div class="flex gap-4">
                  <div class="flex items-center gap-2">
                    <div class="w-3 h-3 rounded-sm bg-surface-variant/50"></div>
                    <span class="text-[10px] text-on-surface-variant">原始 (Raw)</span>
                  </div>
                  <div class="flex items-center gap-2">
                    <div class="w-3 h-3 rounded-sm bg-primary"></div>
                    <span class="text-[10px] text-on-surface-variant">处理后 (Processed)</span>
                  </div>
                </div>
              </div>
              <div class="w-full h-32 bg-surface-container/30 rounded-xl overflow-hidden relative">
                <canvas ref="spectrumCanvas" class="w-full h-full"></canvas>
              </div>
            </div>
            <!-- Amplifier (Gain) -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm flex items-center gap-4">
              <span class="text-sm font-medium text-on-surface whitespace-nowrap">{{ $t('settings.audioParams.gain') }}</span>
              <input type="range" min="-50" max="50" v-model.number="settings.gain" class="w-full accent-primary">
              <span class="text-xs w-12 text-right">{{ settings.gain > 0 ? '+' : '' }}{{ settings.gain }} dB</span>
            </div>

            <!-- Noise Suppression -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.nsEnabled = !settings.nsEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.noiseSuppression') }}</span>
                <div class="w-10 h-5 rounded-full relative transition-colors" :class="settings.nsEnabled ? 'bg-primary' : 'bg-surface-variant'">
                  <div class="w-5 h-5 bg-white rounded-full absolute shadow-sm transition-all" :class="settings.nsEnabled ? 'right-0' : 'left-0'"></div>
                </div>
              </div>
              <div v-if="settings.nsEnabled" class="space-y-4 pt-2 border-t border-surface-variant/20">
                <div class="flex gap-2 mt-4">
                  <button v-for="type in [{id: 'Ulunas', label: 'Ulunas (ONNX)'}, {id: 'RNNoise', label: 'RNNoise'}, {id: 'Speexdsp', label: 'Speexdsp'}, {id: 'Lightweight', label: 'Lightweight'}]" :key="type.id"
                          @click="settings.nsType = type.id"
                          class="px-3 py-1 rounded-full text-xs font-medium transition-colors"
                          :class="settings.nsType === type.id ? 'bg-primary text-on-primary' : 'bg-surface-container text-on-surface'">
                    {{ type.label }}
                  </button>
                </div>
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant whitespace-nowrap">{{ $t('settings.audioParams.intensity') }}</span>
                  <input type="range" min="0" max="100" v-model.number="settings.nsIntensity" class="w-full accent-primary">
                  <span class="text-xs w-8 text-right">{{ settings.nsIntensity }}%</span>
                </div>
              </div>
            </div>

            <!-- Dereverb -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.dereverbEnabled = !settings.dereverbEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.dereverb') }}</span>
                <div class="w-10 h-5 rounded-full relative transition-colors" :class="settings.dereverbEnabled ? 'bg-primary' : 'bg-surface-variant'">
                  <div class="w-5 h-5 bg-white rounded-full absolute shadow-sm transition-all" :class="settings.dereverbEnabled ? 'right-0' : 'left-0'"></div>
                </div>
              </div>
              <div v-if="settings.dereverbEnabled" class="flex items-center gap-4 pt-4 border-t border-surface-variant/20">
                <span class="text-xs text-on-surface-variant whitespace-nowrap">{{ $t('settings.audioParams.level') }}</span>
                <input type="range" min="0" max="100" v-model.number="settings.dereverbLevel" class="w-full accent-primary">
                <span class="text-xs w-8 text-right">{{ settings.dereverbLevel }}%</span>
              </div>
            </div>

            <!-- Auto Gain Control -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.agcEnabled = !settings.agcEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.agc') }}</span>
                <div class="w-10 h-5 rounded-full relative transition-colors" :class="settings.agcEnabled ? 'bg-primary' : 'bg-surface-variant'">
                  <div class="w-5 h-5 bg-white rounded-full absolute shadow-sm transition-all" :class="settings.agcEnabled ? 'right-0' : 'left-0'"></div>
                </div>
              </div>
              <div v-if="settings.agcEnabled" class="space-y-4 pt-4 border-t border-surface-variant/20">
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant w-20">{{ $t('settings.audioParams.target') }}</span>
                  <input type="range" min="0" max="32767" v-model.number="settings.agcTarget" class="w-full accent-primary">
                  <span class="text-xs w-10 text-right">{{ settings.agcTarget }}</span>
                </div>
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant w-20">{{ $t('settings.audioParams.attack') }}</span>
                  <input type="range" min="1" max="100" v-model.number="settings.agcAttack" class="w-full accent-primary">
                  <span class="text-xs w-10 text-right">{{ (settings.agcAttack / 1000).toFixed(3) }}</span>
                </div>
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant w-20">{{ $t('settings.audioParams.decay') }}</span>
                  <input type="range" min="1" max="100" v-model.number="settings.agcDecay" class="w-full accent-primary">
                  <span class="text-xs w-10 text-right">{{ (settings.agcDecay / 10000).toFixed(4) }}</span>
                </div>
              </div>
            </div>

            <!-- Voice Activity Detection -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.vadEnabled = !settings.vadEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.vad') }}</span>
                <div class="w-10 h-5 rounded-full relative transition-colors" :class="settings.vadEnabled ? 'bg-primary' : 'bg-surface-variant'">
                  <div class="w-5 h-5 bg-white rounded-full absolute shadow-sm transition-all" :class="settings.vadEnabled ? 'right-0' : 'left-0'"></div>
                </div>
              </div>
              <div v-if="settings.vadEnabled" class="flex items-center gap-4 pt-4 border-t border-surface-variant/20">
                <span class="text-xs text-on-surface-variant whitespace-nowrap">{{ $t('settings.audioParams.threshold') }}</span>
                <input type="range" min="-100" max="0" v-model.number="settings.vadThreshold" class="w-full accent-primary">
                <span class="text-xs w-12 text-right">{{ settings.vadThreshold }} dB</span>
              </div>
            </div>

            <!-- Audio Processing Chain -->
            <div @click="showAudioChain = true" class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-3 cursor-pointer hover:bg-surface-variant/30 transition-colors group">
              <div class="flex items-center justify-between">
                <div>
                  <h4 class="font-bold text-on-surface">{{ $t('settings.audioChain.title') }}</h4>
                  <p class="text-xs text-on-surface-variant mt-0.5">{{ $t('settings.audioChain.descPopup') }}</p>
                </div>
                <div class="w-8 h-8 rounded-full bg-surface-container flex items-center justify-center group-hover:bg-primary/20 group-hover:text-primary transition-colors">
                  <ChevronRight class="w-4 h-4 text-on-surface-variant group-hover:text-primary transition-colors" />
                </div>
              </div>
              
              <div class="flex items-center gap-2 overflow-hidden text-xs text-on-surface-variant font-medium opacity-80 pt-1">
                <template v-for="(item, index) in settings.processingChain" :key="item">
                  <span class="whitespace-nowrap">{{ $t(`settings.audioChain.${item}`) }}</span>
                  <ArrowRight v-if="index < settings.processingChain.length - 1" class="w-3 h-3 shrink-0" />
                </template>
              </div>
            </div>
          </div>

          <!-- EQUALIZER SECTION -->
          <div v-if="currentSection === 'equalizer'" class="space-y-6 h-[600px]">
            <EqualizerPanel :config="settings.equalizer" />
          </div>
          
          <!-- PLUGINS (TODO placeholder) -->
          <div v-if="currentSection === 'plugins'" class="flex flex-col items-center justify-center py-12 text-center opacity-50">
            <Construction class="w-16 h-16 mb-4 text-on-surface-variant" />
            <h4 class="text-lg font-bold">{{ $t('settings.plugins.underConstruction') }}</h4>
            <p class="text-sm">{{ $t('settings.plugins.portedDesc') }}</p>
          </div>

          <!-- ABOUT -->
          <div v-if="currentSection === 'about'" class="space-y-4 pb-12">
            <div class="bg-surface-bright rounded-2xl overflow-hidden shadow-sm flex flex-col">
              <div class="flex items-center gap-4 p-4 hover:bg-surface-variant/30 transition-colors cursor-default">
                <User class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">Developer</h4>
                  <p class="text-xs text-on-surface-variant">LanRhyme、ChinsaaWei、ChouChiu</p>
                </div>
              </div>
              <div class="h-px bg-surface-variant/20 mx-4"></div>
              
              <a href="https://github.com/LanRhyme/MicYou" target="_blank" class="flex items-center gap-4 p-4 hover:bg-surface-variant/30 transition-colors cursor-pointer group">
                <Globe class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">GitHub Repository</h4>
                  <p class="text-xs text-primary group-hover:underline">https://github.com/LanRhyme/MicYou</p>
                </div>
              </a>
              <div class="h-px bg-surface-variant/20 mx-4"></div>

              <div @click="openDialog('Contributors')" class="flex items-center gap-4 p-4 hover:bg-surface-variant/30 transition-colors cursor-pointer">
                <Users class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.contributorsBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.contributorsDesc') }}</p>
                </div>
              </div>
              <div class="h-px bg-surface-variant/20 mx-4"></div>

              <div @click="openDialog('Sponsors')" class="flex items-center gap-4 p-4 hover:bg-surface-variant/30 transition-colors cursor-pointer">
                <Heart class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.sponsorsBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.sponsorsDesc') }}</p>
                </div>
              </div>
              <div class="h-px bg-surface-variant/20 mx-4"></div>

              <div class="flex items-center justify-between p-4 hover:bg-surface-variant/30 transition-colors cursor-default">
                <div class="flex items-center gap-4">
                  <Info class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                  <div>
                    <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.version') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ appVersion }}</p>
                  </div>
                </div>
                <button @click="openDialog('Update')" class="px-3 py-1.5 text-xs font-medium text-primary bg-primary/10 hover:bg-primary/20 rounded-full transition-colors">
                  {{ $t('settings.about.updatesBtn') }}
                </button>
              </div>
              <div class="h-px bg-surface-variant/20 mx-4"></div>

              <div @click="openDialog('Licenses')" class="flex items-center gap-4 p-4 hover:bg-surface-variant/30 transition-colors cursor-pointer">
                <FileText class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.licensesBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.licensesDesc') }}</p>
                </div>
              </div>
              <div class="h-px bg-surface-variant/20 mx-4"></div>

              <div @click="exportLog" class="flex items-center gap-4 p-4 hover:bg-surface-variant/30 transition-colors cursor-pointer">
                <Download class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.logsBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.logsDesc') }}</p>
                </div>
              </div>
            </div>

            <div class="bg-secondary-container/50 rounded-2xl p-6 mt-4">
              <h3 class="text-base font-bold text-on-secondary-container mb-2">{{ $t('settings.about.introTitle') }}</h3>
              <p class="text-sm text-on-secondary-container/80 leading-relaxed">
                {{ $t('settings.about.introText') }}
              </p>
            </div>
          </div>

        </div>
      </div>
    </div>
  </div>
  
  <ContributorsDialog :isOpen="showContributors" @close="showContributors = false" />
  <SponsorsDialog :isOpen="showSponsors" @close="showSponsors = false" />
  <LicensesDialog :isOpen="showLicenses" @close="showLicenses = false" />
  <AudioChainDialog :isOpen="showAudioChain" :chain="settings.processingChain" @update:chain="updateProcessingChain" @close="showAudioChain = false" />
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';
import {
  Settings as SettingsIcon, 
  X, 
  Mic, 
  Puzzle, 
  Info,
  Download,
  Construction,
  User,
  Globe,
  Users,
  Heart,
  FileText,
  ChevronLeft,
  ChevronRight,
  ArrowRight,
  SlidersHorizontal
} from 'lucide-vue-next';
import ContributorsDialog from './ContributorsDialog.vue';
import SponsorsDialog from './SponsorsDialog.vue';
import LicensesDialog from './LicensesDialog.vue';
import AudioChainDialog from './AudioChainDialog.vue';
import EqualizerPanel from './EqualizerPanel.vue';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from './ui/select';

const props = defineProps<{
  isOpen: boolean
}>();

const emit = defineEmits(['close', 'updateDevice']);

const { t, locale } = useI18n();

const sections = computed(() => [
  { id: 'general', name: t('settings.categories.general'), icon: SettingsIcon },
  { id: 'audio', name: t('settings.categories.audio'), icon: Mic },
  { id: 'equalizer', name: t('settings.equalizer.title'), icon: SlidersHorizontal },
  { id: 'plugins', name: t('settings.categories.plugins'), icon: Puzzle },
  { id: 'about', name: t('settings.categories.about'), icon: Info },
]);

const currentSection = ref('general');
const currentSectionName = computed(() => sections.value.find(s => s.id === currentSection.value)?.name);

let stored = localStorage.getItem('micyou_language') || 'system';
if (stored === 'English') stored = 'en';
if (stored === '简体中文') stored = 'zh';

const currentLanguage = ref(stored);
watch(currentLanguage, (newLang) => {
  localStorage.setItem('micyou_language', newLang);
  if (newLang === 'system') {
    locale.value = navigator.language.toLowerCase().startsWith('zh') ? 'zh' : 'en';
  } else {
    locale.value = newLang;
  }
});

// Reactive Settings State
const settings = reactive({
  audioDevice: 'default',
  gain: 0,
  nsEnabled: false,
  nsType: 'RNNoise',
  nsIntensity: 50,
  dereverbEnabled: false,
  dereverbLevel: 50,
  agcEnabled: false,
  agcTarget: 16000,
  agcAttack: 50,
  agcDecay: 50,
  vadEnabled: false,
  vadThreshold: -40,
  processingChain: ['NoiseReduction', 'Dereverb', 'Equalizer', 'Amplifier', 'AGC', 'VAD'],
  equalizer: {
    enabled: false,
    preAmp: 0,
    gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
  }
});

const audioDevices = ref<string[]>([]);
const hasVBCable = computed(() => audioDevices.value.some(d => d.toLowerCase().includes('cable')));
const audioLevel = ref(0);
let unlistenLevel: UnlistenFn | null = null;
let unlistenSpectrum: UnlistenFn | null = null;

const spectrumCanvas = ref<HTMLCanvasElement | null>(null);
let animationFrameId: number;

// Real spectrum data from backend
const rawSpectrum = ref<number[]>(new Array(64).fill(0));
const processedSpectrum = ref<number[]>(new Array(64).fill(0));

interface SpectrumPayload {
  raw: number[];
  processed: number[];
}

const drawSpectrum = () => {
  if (!spectrumCanvas.value) {
    animationFrameId = requestAnimationFrame(drawSpectrum);
    return;
  }
  
  const canvas = spectrumCanvas.value;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  if (canvas.width !== rect.width * dpr || canvas.height !== rect.height * dpr) {
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);
  }

  const width = rect.width;
  const height = rect.height;
  
  ctx.clearRect(0, 0, width, height);

  const raw = rawSpectrum.value;
  const proc = processedSpectrum.value;
  const barCount = raw.length;
  const gap = 2;
  const barWidth = width / barCount;
  const effectiveBarWidth = barWidth - gap;

  for (let i = 0; i < barCount; i++) {
    const rawH = (raw[i] || 0) * height;
    const procH = (proc[i] || 0) * height;

    if (rawH > 0.5) {
      ctx.fillStyle = 'rgba(150, 150, 150, 0.25)';
      ctx.beginPath();
      ctx.roundRect(i * barWidth + gap/2, height - rawH, effectiveBarWidth, rawH, 2);
      ctx.fill();
    }

    if (procH > 0.5) {
      const gradient = ctx.createLinearGradient(0, height - procH, 0, height);
      gradient.addColorStop(0, 'rgba(74, 103, 45, 0.7)');
      gradient.addColorStop(1, 'rgba(74, 103, 45, 1)');
      
      ctx.fillStyle = gradient;
      ctx.beginPath();
      ctx.roundRect(i * barWidth + gap/2, height - procH, effectiveBarWidth, procH, 2);
      ctx.fill();
    }
  }

  animationFrameId = requestAnimationFrame(drawSpectrum);
};



const showContributors = ref(false);
const showSponsors = ref(false);
const showLicenses = ref(false);
const showAudioChain = ref(false);
const appVersion = ref('0.1.0');

const updateProcessingChain = (newChain: string[]) => {
  settings.processingChain = newChain;
};

const openDialog = async (name: string) => {
  if (name === 'Contributors') showContributors.value = true;
  else if (name === 'Sponsors') showSponsors.value = true;
  else if (name === 'Licenses') showLicenses.value = true;
  else if (name === 'Update') {
    try {
      const res = await fetch('https://api.github.com/repos/LanRhyme/MicYou/releases/latest');
      if (res.ok) {
        const data = await res.json();
        const latestVersion = data.tag_name.replace(/^v/, '');
        if (latestVersion !== appVersion.value) {
          if (confirm(t('dialogs.update.available', { version: data.tag_name }))) {
            window.open(data.html_url, '_blank');
          }
        } else {
          alert(t('dialogs.update.latest'));
        }
      } else {
        alert(t('dialogs.update.failed', { error: "HTTP " + res.status }));
      }
    } catch (e: any) {
      alert(t('dialogs.update.failed', { error: e.message }));
    }
  }
};

const exportLog = async () => {
  try {
    await invoke('export_log');
    alert(t('dialogs.logs.success'));
  } catch (e: any) {
    alert(t('dialogs.logs.failed', { error: e.toString() }));
  }
};

// Lifecycle
onMounted(async () => {
  try {
    appVersion.value = await invoke('get_app_version');
  } catch (e) {
    console.error("Failed to get version", e);
  }
  animationFrameId = requestAnimationFrame(drawSpectrum);
});

onUnmounted(() => {
  cancelAnimationFrame(animationFrameId);
});

const fetchDevices = async () => {
  try {
    audioDevices.value = await invoke<string[]>('get_audio_devices');
  } catch (e) {
    console.error("Failed to fetch audio devices", e);
  }
};

const loadSettings = () => {
  const saved = localStorage.getItem('micyou_audio_settings');
  if (saved) {
    try {
      Object.assign(settings, JSON.parse(saved));
    } catch (e) {
      console.error("Failed to parse settings", e);
    }
  }
  
  // Legacy support
  const savedDevice = localStorage.getItem('micyou_output_device');
  if (savedDevice && !settings.audioDevice) {
    settings.audioDevice = savedDevice;
  }
  
  if (settings.audioDevice) {
    emit('updateDevice', settings.audioDevice);
  }
};

const syncSettingsToBackend = async () => {
  try {
    await invoke('update_audio_settings', {
      settings: {
        gain: settings.gain,
        nsEnabled: settings.nsEnabled,
        nsIntensity: settings.nsIntensity,
        dereverbEnabled: settings.dereverbEnabled,
        dereverbLevel: settings.dereverbLevel,
        agcEnabled: settings.agcEnabled,
        agcTarget: settings.agcTarget,
        agcAttack: settings.agcAttack,
        agcDecay: settings.agcDecay,
        vadEnabled: settings.vadEnabled,
        vadThreshold: settings.vadThreshold,
      }
    });
  } catch (e) {
    console.error('Failed to sync DSP settings to backend:', e);
  }
};

const saveSettings = () => {
  localStorage.setItem('micyou_audio_settings', JSON.stringify(settings));
  localStorage.setItem('micyou_output_device', settings.audioDevice);
  emit('updateDevice', settings.audioDevice);
  syncSettingsToBackend();
};

watch(settings, () => {
  saveSettings();
}, { deep: true });

watch(() => props.isOpen, async (newVal) => {
  if (newVal) {
    await fetchDevices();
    loadSettings();
    // Sync existing settings to backend on open
    await syncSettingsToBackend();
    unlistenLevel = await listen<number>('audio-level', (event) => {
      audioLevel.value = event.payload;
    });
    unlistenSpectrum = await listen<SpectrumPayload>('audio-spectrum', (event) => {
      rawSpectrum.value = event.payload.raw;
      processedSpectrum.value = event.payload.processed;
    });
  } else {
    if (unlistenLevel) {
      unlistenLevel();
      unlistenLevel = null;
    }
    if (unlistenSpectrum) {
      unlistenSpectrum();
      unlistenSpectrum = null;
    }
    audioLevel.value = 0;
    rawSpectrum.value = new Array(64).fill(0);
    processedSpectrum.value = new Array(64).fill(0);
  }
});
</script>
