<template>
  <Transition name="dialog">
  <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-8 bg-black/60 backdrop-blur-sm" @click.self="$emit('close')">
    <div class="bg-surface-bright/85 backdrop-blur-2xl w-full max-w-5xl h-full max-h-[80vh] rounded-3xl flex overflow-hidden shadow-2xl relative border border-white/10">
      
      <!-- Close Button -->
      <button @click="$emit('close')" class="absolute top-4 right-4 z-10 w-10 h-10 rounded-full bg-surface-variant/40 hover:bg-surface-variant/80 flex items-center justify-center transition-colors">
        <X class="w-5 h-5 text-on-surface" />
      </button>

      <!-- Left Sidebar -->
      <div class="w-64 bg-surface-container-low/50 border-r border-surface-variant/30 flex flex-col p-4 space-y-2 overflow-y-auto">
        <div class="px-4 py-4 mb-4 flex items-center gap-3">
          <SettingsIcon class="w-6 h-6 text-primary" />
          <h2 class="text-xl font-bold text-primary">{{ $t('settings.title') }}</h2>
        </div>

        <button v-for="section in sections" :key="section.id" 
                @click="currentSection = section.id"
                class="flex items-center gap-3 px-4 py-3 rounded-2xl transition-all duration-300 w-full text-left"
                :class="currentSection === section.id ? 'bg-secondary-container/80 text-on-secondary-container shadow-sm scale-[1.02]' : 'hover:bg-surface-variant/30 text-on-surface-variant'">
          <component :is="section.icon" class="w-5 h-5" :class="currentSection === section.id ? 'text-primary' : ''" />
          <span class="font-medium text-sm">{{ section.name }}</span>
        </button>
      </div>

      <!-- Right Content -->
      <div ref="contentRef" class="flex-1 bg-surface-container-lowest/50 p-8 overflow-y-auto overscroll-contain">
        <div class="max-w-2xl mx-auto space-y-8">
          <h3 class="text-3xl font-bold text-primary mb-6">{{ currentSectionName }}</h3>

          <!-- SECTIONS -->
          <Transition name="fade-slide" mode="out-in">
            <!-- GENERAL SECTION -->
            <div v-if="currentSection === 'general'" class="space-y-6" key="general">
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
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
                    <SelectItem value="zh">简体中文</SelectItem>
                    <SelectItem value="en">English</SelectItem>
                    <SelectItem value="cat">喵喵语 (´,,•ω•,,)</SelectItem>
                    <SelectItem value="zh-hk">粤语</SelectItem>
                    <SelectItem value="zh-tw">繁體中文（台灣）</SelectItem>
                    <SelectItem value="zh-ss">中国人（坚硬）</SelectItem>
                    <SelectItem value="lzh">文言</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>

            <!-- Close Behavior -->
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('closeBehavior.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('closeBehavior.desc') }}</p>
              </div>
              <Select v-model="closeBehavior">
                <SelectTrigger class="w-[160px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium">
                  <SelectValue :placeholder="$t('closeBehavior.ask')" />
                </SelectTrigger>
                <SelectContent class="border-surface-variant/20 rounded-lg bg-surface shadow-lg">
                  <SelectGroup>
                    <SelectItem value="ask">{{ $t('closeBehavior.ask') }}</SelectItem>
                    <SelectItem value="hide">{{ $t('closeBehavior.hide') }}</SelectItem>
                    <SelectItem value="exit">{{ $t('closeBehavior.exit') }}</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>

            <!-- Start Minimized -->
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('startMinimized.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('startMinimized.desc') }}</p>
              </div>
              <button
                @click="startMinimized = !startMinimized"
                class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                :class="startMinimized ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
              >
                <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="startMinimized ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                  
                  <span
                    class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                    :class="startMinimized ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                  />
                </div>
              </button>
            </div>

            <!-- Run at Startup -->
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('autostart.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('autostart.desc') }}</p>
              </div>
              <button
                @click="toggleAutostart"
                class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                :class="autostartEnabled ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
              >
                <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="autostartEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                  
                  <span
                    class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                    :class="autostartEnabled ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                  />
                </div>
              </button>
            </div>

            <!-- Notifications -->
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('notifications.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('notifications.desc') }}</p>
              </div>
              <button
                @click="notificationsEnabled = !notificationsEnabled"
                class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                :class="notificationsEnabled ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
              >
                <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="notificationsEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                  
                  <span
                    class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                    :class="notificationsEnabled ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                  />
                </div>
              </button>
            </div>

            <!-- Auto Stream -->
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('autoStream.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('autoStream.desc') }}</p>
              </div>
              <button
                @click="autoStream = !autoStream"
                class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                :class="autoStream ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
              >
                <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="autoStream ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                  
                  <span
                    class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                    :class="autoStream ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                  />
                </div>
              </button>
            </div>

            <!-- Pocket Mode -->
            <div class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('settings.pocketMode.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.pocketMode.desc') }}</p>
              </div>
              <button
                @click="pocketMode = !pocketMode"
                class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                :class="pocketMode ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
              >
                <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="pocketMode ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                  <!-- State layer (hover halo) -->
                  
                  <span
                    class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                    :class="pocketMode ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                  />
                </div>
              </button>
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
                    <SelectValue :placeholder="$t('settings.audioOutput.auto')" />
                  </SelectTrigger>
                  <SelectContent class="border-surface-variant/20 rounded-xl bg-surface shadow-lg max-h-[40vh]">
                    <SelectGroup>
                      <SelectItem value="auto">{{ $t('settings.audioOutput.auto') }}</SelectItem>
                      <SelectItem v-for="dev in audioDevices" :key="dev" :value="dev">{{ dev }}</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
              <p v-if="settings.audioDevice === 'auto' || (settings.audioDevice && (settings.audioDevice.includes('CABLE Input') || settings.audioDevice.toLowerCase().includes('blackhole') || settings.audioDevice.toLowerCase().includes('micyou')))" class="text-xs text-green-400 font-medium">
                {{ $t('settings.audioOutput.routingActive') }}
              </p>
            </div>

            <!-- Virtual Audio Device Management (macOS: BlackHole, Windows: VB-Cable) -->
            <div v-if="isMacOS" class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
              <div class="flex items-center justify-between">
                <h4 class="font-bold text-on-surface text-lg">BlackHole</h4>
                <span class="text-xs font-medium px-2 py-1 rounded-md" :class="hasBlackHole ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'">
                  {{ hasBlackHole ? $t('settings.blackhole.installed') : $t('settings.blackhole.notDetected') }}
                </span>
              </div>
              <p class="text-xs text-on-surface-variant">
                {{ $t('settings.blackhole.desc') }}
              </p>
              <div v-if="blackholeStatus.switch_audio_source" class="text-xs text-on-surface-variant flex items-center gap-1.5">
                <span class="inline-block w-1.5 h-1.5 rounded-full bg-green-400"></span>
                SwitchAudioSource {{ $t('settings.blackhole.available') }}
              </div>
              <div v-else-if="hasBlackHole" class="text-xs text-orange-400 flex items-center gap-1.5">
                <span class="inline-block w-1.5 h-1.5 rounded-full bg-orange-400"></span>
                {{ $t('settings.blackhole.switchAudioSourceMissing') }}
              </div>
              <div v-if="!hasBlackHole" class="space-y-2">
                <p class="text-xs text-on-surface-variant font-mono bg-surface-container rounded-lg p-3 select-all">brew install blackhole-2ch</p>
                <button
                  @click="openBlackHoleDownload"
                  class="w-full py-2 bg-surface-variant hover:bg-surface-variant/80 rounded-xl text-sm font-bold flex items-center justify-center gap-2 transition-colors"
                >
                  <Download class="w-4 h-4" /> {{ $t('settings.blackhole.download') }}
                </button>
              </div>
            </div>
            <div v-else-if="isLinux" class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
              <div class="flex items-center justify-between">
                <h4 class="font-bold text-on-surface text-lg">PipeWire</h4>
                <span class="text-xs font-medium px-2 py-1 rounded-md" :class="pipewireStatus.available ? (pipewireStatus.device_exists ? 'bg-green-500/20 text-green-400' : 'bg-yellow-500/20 text-yellow-400') : 'bg-red-500/20 text-red-400'">
                  {{ pipewireStatus.available ? (pipewireStatus.device_exists ? $t('settings.pipewire.active') : $t('settings.pipewire.available')) : $t('settings.pipewire.notAvailable') }}
                </span>
              </div>
              <p class="text-xs text-on-surface-variant">
                {{ $t('settings.pipewire.desc') }}
              </p>
              <div v-if="!pipewireStatus.available" class="text-xs text-on-surface-variant font-mono bg-surface-container rounded-lg p-3 select-all">
                sudo apt install pipewire pipewire-pulse
              </div>
            </div>
            <div v-else class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
              <div class="flex items-center justify-between">
                <h4 class="font-bold text-on-surface text-lg">{{ $t('settings.vbcable.title') }}</h4>
                <span class="text-xs font-medium px-2 py-1 rounded-md" :class="hasVBCable ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'">
                  {{ hasVBCable ? $t('settings.vbcable.installed') : $t('settings.vbcable.notDetected') }}
                </span>
              </div>
              <p class="text-xs text-on-surface-variant">
                {{ $t('settings.vbcable.desc') }}
              </p>
              <div v-if="!hasVBCable" class="space-y-2">
                <button
                  @click="installVBCableFromSettings"
                  :disabled="vbcableInstalling"
                  class="w-full py-2 bg-primary disabled:opacity-50 rounded-xl text-sm font-bold text-on-primary flex items-center justify-center gap-2 transition-colors"
                >
                  <Loader2 v-if="vbcableInstalling" class="w-4 h-4 animate-spin" />
                  <Download v-else class="w-4 h-4" />
                  {{ vbcableInstalling ? vbcableInstallProgress || $t('vbcableInstall.installing') : $t('vbcableDetect.autoInstall') }}
                </button>
                <button
                  @click="openVBCableDownload"
                  class="w-full py-2 bg-surface-variant hover:bg-surface-variant/80 rounded-xl text-sm font-bold flex items-center justify-center gap-2 transition-colors"
                >
                  <Download class="w-4 h-4" /> {{ $t('settings.vbcable.download') }}
                </button>
              </div>
            </div>
          </div>

          <!-- APPEARANCE SECTION -->
          <div v-else-if="currentSection === 'appearance'" class="space-y-6" key="appearance">
            <!-- Theme Mode Settings -->
            <div class="bg-surface-bright rounded-2xl p-4 flex items-center justify-between shadow-sm">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('settings.theme.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.theme.desc') }}</p>
              </div>
              <Select v-model="colorMode">
                <SelectTrigger class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium">
                  <SelectValue :placeholder="$t('settings.theme.auto')" />
                </SelectTrigger>
                <SelectContent class="border-surface-variant/20 rounded-lg bg-surface shadow-lg">
                  <SelectGroup>
                    <SelectItem value="auto">{{ $t('settings.theme.auto') }}</SelectItem>
                    <SelectItem value="light">{{ $t('settings.theme.light') }}</SelectItem>
                    <SelectItem value="dark">{{ $t('settings.theme.dark') }}</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>

            <!-- Theme Color Settings -->
            <div class="bg-surface-bright rounded-2xl p-4 flex items-center justify-between shadow-sm">
              <div class="flex-shrink-0 mr-4">
                <h4 class="font-bold text-on-surface">{{ $t('settings.themeColor.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.themeColor.desc') }}</p>
              </div>
              <div class="flex justify-end">
                <ThemeSelector 
                  v-model="themeColor" 
                  :custom-h="customH"
                  :custom-s="customS"
                  :custom-l="customL"
                  @open-custom="showColorPicker = true"
                />
              </div>
            </div>

            <!-- Theme Generation Variant Settings -->
            <div class="bg-surface-bright rounded-2xl p-4 flex items-center justify-between shadow-sm">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('settings.customColor.variant') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.customColor.variantDesc') }}</p>
              </div>
              <Select v-model="customVariant">
                <SelectTrigger class="w-[160px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent class="border-surface-variant/20 rounded-lg bg-surface shadow-lg">
                  <SelectGroup>
                    <SelectItem value="TonalSpot">{{ $t('settings.customColor.variants.tonalSpot') }}</SelectItem>
                    <SelectItem value="Neutral">{{ $t('settings.customColor.variants.neutral') }}</SelectItem>
                    <SelectItem value="Vibrant">{{ $t('settings.customColor.variants.vibrant') }}</SelectItem>
                    <SelectItem value="Expressive">{{ $t('settings.customColor.variants.expressive') }}</SelectItem>
                    <SelectItem value="Rainbow">{{ $t('settings.customColor.variants.rainbow') }}</SelectItem>
                    <SelectItem value="FruitSalad">{{ $t('settings.customColor.variants.fruitSalad') }}</SelectItem>
                    <SelectItem value="Monochrome">{{ $t('settings.customColor.variants.monochrome') }}</SelectItem>
                    <SelectItem value="Fidelity">{{ $t('settings.customColor.variants.fidelity') }}</SelectItem>
                    <SelectItem value="Content">{{ $t('settings.customColor.variants.content') }}</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>

            <!-- UI Style Settings -->
            <div class="bg-surface-bright rounded-2xl p-4 flex items-center justify-between shadow-sm">
              <div>
                <h4 class="font-bold text-on-surface">{{ $t('settings.uiStyle.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.uiStyle.desc') }}</p>
              </div>
              <Select v-model="uiStyle">
                <SelectTrigger class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium">
                  <SelectValue :placeholder="$t('settings.uiStyle.glass')" />
                </SelectTrigger>
                <SelectContent class="border-surface-variant/20 rounded-lg bg-surface shadow-lg">
                  <SelectGroup>
                    <SelectItem value="style-default">{{ $t('settings.uiStyle.default') }}</SelectItem>
                    <SelectItem value="style-glass">{{ $t('settings.uiStyle.glass') }}</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>

            <!-- Custom CSS -->
            <div class="bg-surface-bright rounded-2xl p-4 flex items-center justify-between shadow-sm">
              <div class="flex-1 mr-4">
                <h4 class="font-bold text-on-surface">{{ $t('settings.customCss.title') }}</h4>
                <p class="text-xs text-on-surface-variant">{{ $t('settings.customCss.desc') }}</p>
              </div>
              <button @click="showCustomCssDialog = true" class="px-4 py-2 rounded-full bg-primary/10 hover:bg-primary/20 text-primary font-medium text-sm transition-colors flex-shrink-0">
                {{ $t('settings.customCss.editBtn') }}
              </button>
            </div>
          </div>

          <!-- AUDIO SECTION -->
          <div v-else-if="currentSection === 'audio'" class="space-y-6" key="audio">
            

            <!-- Spectrum Analyzer / Real-time Monitoring -->
            <div class="bg-surface-bright rounded-2xl p-4 space-y-3 shadow-sm">
              <div class="flex justify-between items-center mb-2">
                <h4 class="font-bold text-on-surface text-sm">{{ $t('settings.spectrum.title') }}</h4>
                <div class="flex gap-4">
                  <div class="flex items-center gap-2">
                    <div class="w-3 h-3 rounded-sm bg-surface-variant"></div>
                    <span class="text-[10px] text-on-surface-variant">原始 (Raw)</span>
                  </div>
                  <div class="flex items-center gap-2">
                    <div class="w-3 h-3 rounded-sm bg-primary"></div>
                    <span class="text-[10px] text-on-surface-variant">处理后 (Processed)</span>
                  </div>
                </div>
              </div>
              <div class="w-full h-32 bg-surface-container rounded-xl overflow-hidden relative">
                <canvas ref="spectrumCanvas" class="w-full h-full"></canvas>
              </div>
            </div>
            <!-- Amplifier (Gain) -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm flex items-center gap-4">
              <span class="text-sm font-medium text-on-surface whitespace-nowrap">{{ $t('settings.audioParams.gain') }}</span>
              <MD3Slider :min="-50" :max="50" v-model="settings.gain" />
              <span class="text-xs w-12 text-right">{{ settings.gain > 0 ? '+' : '' }}{{ settings.gain }} dB</span>
            </div>

            <!-- Noise Suppression -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.nsEnabled = !settings.nsEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.noiseSuppression') }}</span>
                <button
                  class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                  :class="settings.nsEnabled ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
                >
                  <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="settings.nsEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                    
                    <span
                      class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                      :class="settings.nsEnabled ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                    />
                  </div>
                </button>
              </div>
              <div v-if="settings.nsEnabled" class="space-y-4 pt-2 border-t border-surface-variant/20">
                <div class="flex gap-2 mt-4">
                  <button v-for="type in [{id: 'DPDFNet', label: 'DPDFNet (ONNX)'}, {id: 'Ulunas', label: 'Ulunas (ONNX)'}, {id: 'RNNoise', label: 'RNNoise'}, {id: 'Speexdsp', label: 'Speexdsp'}, {id: 'Lightweight', label: 'Lightweight'}]" :key="type.id"
                          @click="settings.nsType = type.id"
                          class="px-3 py-1 rounded-full text-xs font-medium transition-colors"
                          :class="settings.nsType === type.id ? 'bg-primary text-on-primary' : 'bg-surface-container text-on-surface'">
                    {{ type.label }}
                  </button>
                </div>
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant whitespace-nowrap">{{ $t('settings.audioParams.intensity') }}</span>
                  <MD3Slider :min="0" :max="100" v-model="settings.nsIntensity" />
                  <span class="text-xs w-8 text-right">{{ settings.nsIntensity }}%</span>
                </div>
              </div>
            </div>

            <!-- Dereverb -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.dereverbEnabled = !settings.dereverbEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.dereverb') }}</span>
                <button
                  class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                  :class="settings.dereverbEnabled ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
                >
                  <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="settings.dereverbEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                    
                    <span
                      class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                      :class="settings.dereverbEnabled ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                    />
                  </div>
                </button>
              </div>
              <div v-if="settings.dereverbEnabled" class="flex items-center gap-4 pt-4 border-t border-surface-variant/20">
                <span class="text-xs text-on-surface-variant whitespace-nowrap">{{ $t('settings.audioParams.level') }}</span>
                <MD3Slider :min="0" :max="100" v-model="settings.dereverbLevel" />
                <span class="text-xs w-8 text-right">{{ settings.dereverbLevel }}%</span>
              </div>
            </div>

            <!-- Auto Gain Control -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.agcEnabled = !settings.agcEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.agc') }}</span>
                <button
                  class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                  :class="settings.agcEnabled ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
                >
                  <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="settings.agcEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                    
                    <span
                      class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                      :class="settings.agcEnabled ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                    />
                  </div>
                </button>
              </div>
              <div v-if="settings.agcEnabled" class="space-y-4 pt-4 border-t border-surface-variant/20">
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant w-20">{{ $t('settings.audioParams.target') }}</span>
                  <MD3Slider :min="0" :max="32767" v-model="settings.agcTarget" />
                  <span class="text-xs w-10 text-right">{{ settings.agcTarget }}</span>
                </div>
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant w-20">{{ $t('settings.audioParams.attack') }}</span>
                  <MD3Slider :min="1" :max="100" v-model="settings.agcAttack" />
                  <span class="text-xs w-10 text-right">{{ (settings.agcAttack / 1000).toFixed(3) }}</span>
                </div>
                <div class="flex items-center gap-4">
                  <span class="text-xs text-on-surface-variant w-20">{{ $t('settings.audioParams.decay') }}</span>
                  <MD3Slider :min="1" :max="100" v-model="settings.agcDecay" />
                  <span class="text-xs w-10 text-right">{{ (settings.agcDecay / 10000).toFixed(4) }}</span>
                </div>
              </div>
            </div>

            <!-- Voice Activity Detection -->
            <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
              <div class="flex justify-between items-center cursor-pointer" @click="settings.vadEnabled = !settings.vadEnabled">
                <span class="font-medium text-on-surface">{{ $t('settings.audioParams.vad') }}</span>
                <button
                  class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                  :class="settings.vadEnabled ? 'border-primary bg-primary' : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'"
                >
                  <div class="relative flex items-center justify-center transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]" :class="settings.vadEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'">
                    
                    <span
                      class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
                      :class="settings.vadEnabled ? 'h-6 w-6 bg-on-primary' : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'"
                    />
                  </div>
                </button>
              </div>
              <div v-if="settings.vadEnabled" class="flex items-center gap-4 pt-4 border-t border-surface-variant/20">
                <span class="text-xs text-on-surface-variant whitespace-nowrap">{{ $t('settings.audioParams.threshold') }}</span>
                <MD3Slider :min="-100" :max="0" v-model="settings.vadThreshold" />
                <span class="text-xs w-12 text-right">{{ settings.vadThreshold }} dB</span>
              </div>
            </div>

            <!-- Audio Processing Chain -->
            <div @click="showAudioChain = true" class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-3 cursor-pointer hover:bg-surface-variant transition-colors group">
              <div class="flex items-center justify-between">
                <div>
                  <h4 class="font-bold text-on-surface">{{ $t('settings.audioChain.title') }}</h4>
                  <p class="text-xs text-on-surface-variant mt-0.5">{{ $t('settings.audioChain.descPopup') }}</p>
                </div>
                <div class="w-8 h-8 rounded-full bg-surface-container flex items-center justify-center group-hover:bg-primary group-hover:text-on-primary transition-colors">
                  <ChevronRight class="w-4 h-4 text-on-surface-variant group-hover:text-on-primary transition-colors" />
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
          <div v-else-if="currentSection === 'equalizer'" class="space-y-6 h-[600px]" key="equalizer">
            <EqualizerPanel :config="settings.equalizer" />
          </div>

          <!-- NETWORK SECTION -->
          
          <!-- PLUGINS (TODO placeholder) -->
          <div v-else-if="currentSection === 'plugins'" class="flex flex-col items-center justify-center py-12 text-center opacity-50" key="plugins">
            <Construction class="w-16 h-16 mb-4 text-on-surface-variant" />
            <h4 class="text-lg font-bold">{{ $t('settings.plugins.underConstruction') }}</h4>
            <p class="text-sm">{{ $t('settings.plugins.portedDesc') }}</p>
          </div>

          <!-- ABOUT -->
          <div v-else-if="currentSection === 'about'" class="space-y-4 pb-12" key="about">
            <div class="bg-surface-bright rounded-2xl overflow-hidden shadow-sm flex flex-col border border-border">
              <div class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-default">
                <User class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">Developer</h4>
                  <p class="text-xs text-on-surface-variant">LanRhyme、ChinsaaWei、ChouChiu</p>
                </div>
              </div>
              <div class="h-px bg-border mx-4"></div>
              
              <a href="https://github.com/LanRhyme/MicYou" target="_blank" class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer group">
                <Globe class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">GitHub Repository</h4>
                  <p class="text-xs text-primary group-hover:underline">https://github.com/LanRhyme/MicYou</p>
                </div>
              </a>
              <div class="h-px bg-border mx-4"></div>

              <div @click="openDialog('Contributors')" class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer">
                <Users class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.contributorsBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.contributorsDesc') }}</p>
                </div>
              </div>
              <div class="h-px bg-border mx-4"></div>

              <div @click="openDialog('Sponsors')" class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer">
                <Heart class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.sponsorsBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.sponsorsDesc') }}</p>
                </div>
              </div>
              <div class="h-px bg-border mx-4"></div>

              <div class="flex items-center justify-between p-4 hover:bg-surface-variant transition-colors cursor-default">
                <div class="flex items-center gap-4">
                  <Info class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                  <div>
                    <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.version') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ appVersion }}</p>
                  </div>
                </div>
                <button @click="openDialog('Update')" class="px-3 py-1.5 text-xs font-medium text-on-primary bg-primary hover:opacity-90 rounded-full transition-opacity">
                  {{ $t('settings.about.updatesBtn') }}
                </button>
              </div>
              <div class="h-px bg-border mx-4"></div>

              <div @click="openDialog('Licenses')" class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer">
                <FileText class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                <div class="flex-1">
                  <h4 class="text-sm font-medium text-on-surface">{{ $t('settings.about.licensesBtn') }}</h4>
                  <p class="text-xs text-on-surface-variant">{{ $t('settings.about.licensesDesc') }}</p>
                </div>
              </div>
              <div class="h-px bg-border mx-4"></div>

              <div @click="exportLog" class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer">
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
          </Transition>

        </div>
      </div>
    </div>
  </div>
  </Transition>
  
  <ContributorsDialog :isOpen="showContributors" @close="showContributors = false" />
  <SponsorsDialog :isOpen="showSponsors" @close="showSponsors = false" />
  <LicensesDialog :isOpen="showLicenses" @close="showLicenses = false" />
  <AudioChainDialog :isOpen="showAudioChain" :chain="settings.processingChain" @update:chain="updateProcessingChain" @close="showAudioChain = false" />
  <CustomCssDialog :isOpen="showCustomCssDialog" @close="showCustomCssDialog = false" />
  <CustomColorPicker 
    :is-open="showColorPicker" 
    :initial-h="customH"
    :initial-s="customS"
    :initial-l="customL"
    @close="showColorPicker = false"
    @apply="applyCustomColor"
  />
</template>

<script setup lang="ts">
import MD3Slider from "@/shared/components/ui/slider/MD3Slider.vue"
import { ref, computed, watch, reactive, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useColorMode, useStorage } from '@vueuse/core';
import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';
import { isEnabled as isAutostartEnabled, enable as enableAutostart, disable as disableAutostart } from '@tauri-apps/plugin-autostart';
import {
  Settings as SettingsIcon, 
  X, 
  Mic, 
  Puzzle, 
  Info,
  Download,
  Loader2,
  Construction,
  User,
  Globe,
  Users,
  Heart,
  FileText,
  ChevronRight,
  ArrowRight,
  SlidersHorizontal,
  Palette,
} from '@lucide/vue';
import ContributorsDialog from './ContributorsDialog.vue';
import SponsorsDialog from './SponsorsDialog.vue';
import LicensesDialog from './LicensesDialog.vue';
import AudioChainDialog from '@/features/audio/components/AudioChainDialog.vue';
import CustomCssDialog from '@/features/theme/components/CustomCssDialog.vue';
import EqualizerPanel from '@/features/audio/components/EqualizerPanel.vue';
import ThemeSelector from '@/features/theme/components/ThemeSelector.vue';
import CustomColorPicker from '@/features/theme/components/CustomColorPicker.vue';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/shared/components/ui/select';

const props = defineProps<{
  isOpen: boolean
}>();

const emit = defineEmits(['close', 'updateDevice']);

const { t, locale } = useI18n();

const colorMode = useColorMode({
  emitAuto: true,
  modes: {
    dark: 'dark',
    light: 'light',
  },
  attribute: 'class',
});

const themeColor = useStorage('micyou_theme_color', 'theme-blue');
const uiStyle = useStorage('micyou_ui_style', 'style-default');

const customH = useStorage('micyou_custom_h', 215);
const customS = useStorage('micyou_custom_s', 35);
const customL = useStorage('micyou_custom_l', 55);
const customVariant = useStorage('micyou_custom_variant', 'TonalSpot');
const showColorPicker = ref(false);
const pocketMode = useStorage('micyou_pocket_mode', false);
const closeBehavior = useStorage<'ask' | 'hide' | 'exit' | null>('micyou_remember_close_action', null);
const startMinimized = useStorage<boolean>('micyou_start_minimized', false);
const notificationsEnabled = useStorage<boolean>('micyou_notifications', true);
const autoStream = useStorage<boolean>('micyou_auto_stream', false);
const autostartEnabled = ref(false);

const applyCustomColor = (color: { h: number, s: number, l: number }) => {
  customH.value = color.h;
  customS.value = color.s;
  customL.value = color.l;
  themeColor.value = 'theme-custom';
};

const sections = computed(() => [
  { id: 'general', name: t('settings.categories.general'), icon: SettingsIcon },
  { id: 'appearance', name: t('settings.categories.appearance'), icon: Palette },
  { id: 'audio', name: t('settings.categories.audio'), icon: Mic },
  { id: 'equalizer', name: t('settings.equalizer.title'), icon: SlidersHorizontal },
  { id: 'plugins', name: t('settings.categories.plugins'), icon: Puzzle },
  { id: 'about', name: t('settings.categories.about'), icon: Info },
]);

const contentRef = ref<HTMLElement | null>(null);
const currentSection = ref('general');
const currentSectionName = computed(() => sections.value.find(s => s.id === currentSection.value)?.name);

watch(currentSection, () => {
  contentRef.value?.scrollTo({ top: 0 });
});

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
  audioDevice: 'auto',
  gain: 0,
  nsEnabled: false,
  nsType: 'RNNoise',
  nsIntensity: 100,
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
const hasBlackHole = computed(() => audioDevices.value.some(d => d.toLowerCase().includes('blackhole')));
const isMacOS = typeof navigator !== 'undefined' && /Mac/.test(navigator.platform || navigator.userAgent) && !/iPhone|iPad|iPod/.test(navigator.userAgent);
const isLinux = typeof navigator !== 'undefined' && /Linux/.test(navigator.platform || navigator.userAgent) && !/Android/.test(navigator.userAgent);
const pipewireStatus = ref<{ available: boolean; setup: boolean; device_exists: boolean }>({ available: false, setup: false, device_exists: false });
const vbcableInstalling = ref(false);
const vbcableInstallProgress = ref('');
let unlistenVbcableProgress: UnlistenFn | null = null;

interface BlackHoleStatus {
  installed: boolean;
  switch_audio_source: boolean;
  device_name: string | null;
}
const blackholeStatus = ref<BlackHoleStatus>({ installed: false, switch_audio_source: false, device_name: null });
const blackholeChecking = ref(false);
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

  const style = getComputedStyle(document.documentElement);
  const primaryColor = `hsl(${style.getPropertyValue('--primary').trim()})`;
  const variantColor = `hsl(${style.getPropertyValue('--surface-variant').trim()})`;

  for (let i = 0; i < barCount; i++) {
    const rawH = (raw[i] || 0) * height;
    const procH = (proc[i] || 0) * height;

    if (rawH > 0.5) {
      ctx.fillStyle = variantColor;
      ctx.beginPath();
      ctx.roundRect(i * barWidth + gap/2, height - rawH, effectiveBarWidth, rawH, 2);
      ctx.fill();
    }

    if (procH > 0.5) {
      ctx.fillStyle = primaryColor;
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
const showCustomCssDialog = ref(false);
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

async function installVBCableFromSettings() {
  vbcableInstalling.value = true;
  vbcableInstallProgress.value = '';
  try {
    const result = await invoke<{ success: boolean; error_type?: string; message?: string }>('install_vbcable');
    if (result.success) {
      audioDevices.value = await invoke<string[]>('get_audio_devices');
    }
  } catch (e) {
    console.error('VB-CABLE install failed:', e);
  } finally {
    vbcableInstalling.value = false;
    vbcableInstallProgress.value = '';
  }
}

function openVBCableDownload() {
  window.open('https://vb-audio.com/Cable/', '_blank');
}

function openBlackHoleDownload() {
  window.open('https://github.com/ExistentialAudio/BlackHole', '_blank');
}

async function checkBlackHoleStatus() {
  if (!isMacOS) return;
  blackholeChecking.value = true;
  try {
    blackholeStatus.value = await invoke<BlackHoleStatus>('check_blackhole');
  } catch (e) {
    console.error('Failed to check BlackHole status:', e);
  } finally {
    blackholeChecking.value = false;
  }
}

async function checkPipeWireStatus() {
  if (!isLinux) return;
  try {
    pipewireStatus.value = await invoke<{ available: boolean; setup: boolean; device_exists: boolean }>('check_pipewire');
  } catch (e) {
    console.error('Failed to check PipeWire status:', e);
  }
}

// Lifecycle
onMounted(async () => {
  try {
    appVersion.value = await invoke('get_app_version');
  } catch (e) {
    console.error("Failed to get version", e);
  }
  animationFrameId = requestAnimationFrame(drawSpectrum);
  try {
    autostartEnabled.value = await isAutostartEnabled();
  } catch (e) {
    console.error("Failed to read autostart state:", e);
  }
  unlistenVbcableProgress = await listen<string>('vbcable-install-progress', (event) => {
    vbcableInstallProgress.value = event.payload;
  });
  checkBlackHoleStatus();
});

async function toggleAutostart() {
  try {
    if (autostartEnabled.value) {
      await disableAutostart();
      autostartEnabled.value = false;
    } else {
      await enableAutostart();
      autostartEnabled.value = true;
    }
  } catch (e) {
    console.error('Failed to toggle autostart:', e);
  }
}

onUnmounted(() => {
  cancelAnimationFrame(animationFrameId);
  if (unlistenVbcableProgress) unlistenVbcableProgress();
});

const fetchDevices = async () => {
  try {
    audioDevices.value = await invoke<string[]>('get_audio_devices');
  } catch (e) {
    console.error("Failed to fetch audio devices", e);
  }
  checkBlackHoleStatus();
  checkPipeWireStatus();
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
    settings.audioDevice = savedDevice === 'default' ? 'auto' : savedDevice;
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
        nsType: settings.nsType,
        nsIntensity: settings.nsIntensity,
        dereverbEnabled: settings.dereverbEnabled,
        dereverbLevel: settings.dereverbLevel,
        agcEnabled: settings.agcEnabled,
        agcTarget: settings.agcTarget,
        agcAttack: settings.agcAttack,
        agcDecay: settings.agcDecay,
        vadEnabled: settings.vadEnabled,
        vadThreshold: settings.vadThreshold,
        processingChain: settings.processingChain,
        equalizer: settings.equalizer,
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
