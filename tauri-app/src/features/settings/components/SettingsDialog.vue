<template>
  <Transition name="dialog">
    <div
      v-if="isOpen"
      class="fixed inset-0 z-50 flex items-center justify-center p-8 bg-black/60 backdrop-blur-sm"
      @click.self="$emit('close')"
    >
      <div class="settings-panel relative backdrop-blur-2xl">
        <!-- Close Button -->
        <button
          @click="$emit('close')"
          class="absolute top-4 right-4 z-40 w-10 h-10 rounded-full bg-surface-variant/40 hover:bg-surface-variant/80 flex items-center justify-center transition-colors"
        >
          <X class="w-5 h-5 text-on-surface" />
        </button>

        <!-- Left Sidebar -->
        <div class="settings-nav space-y-2">
          <div class="px-4 py-4 mb-4 flex items-center gap-3">
            <SettingsIcon class="w-6 h-6 text-primary" />
            <h2 class="text-xl font-bold text-primary">{{ $t('settings.title') }}</h2>
          </div>

          <template v-for="section in sections" :key="section.id">
            <div v-if="section.divider" class="my-2 mx-3 h-px bg-border/70"></div>
            <button
              v-else
              @click="currentSection = section.id"
              class="settings-nav-item"
              :class="
                currentSection === section.id
                  ? 'bg-secondary-container/80 text-on-secondary-container shadow-sm scale-[1.02]'
                  : 'hover:bg-surface-variant/30 text-on-surface-variant'
              "
            >
              <component
                v-if="!section.panelIcon"
                :is="section.icon"
                class="w-5 h-5"
                :class="currentSection === section.id ? 'text-primary' : ''"
              />
              <span
                v-else
                class="w-5 h-5 text-center text-sm leading-5"
                :class="currentSection === section.id ? 'text-primary' : ''"
                >{{ section.panelIcon }}</span
              >
              <span class="font-medium text-sm">{{
                section.nameKey ? $t(section.nameKey) : section.name
              }}</span>
            </button>
          </template>
        </div>

        <!-- Right Content -->
        <div
          ref="contentRef"
          class="settings-scrollbar flex-1 bg-surface-container-lowest/50 p-8 overflow-y-auto overscroll-contain"
        >
          <div class="max-w-2xl mx-auto space-y-8">
            <h3 class="text-3xl font-bold text-primary mb-6">{{ currentSectionName }}</h3>

            <!-- SECTIONS -->
            <Transition name="fade-slide" mode="out-in">
              <!-- GENERAL SECTION -->
              <div v-if="currentSection === 'general'" class="space-y-6" key="general">
                <!-- Run Mode -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 shadow-sm border border-white/5"
                >
                  <div class="flex items-center justify-between">
                    <div>
                      <h4 class="font-bold text-on-surface">{{ $t('settings.runMode.title') }}</h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.runMode.desc') }}
                      </p>
                    </div>
                    <div class="flex items-center gap-2">
                      <span
                        class="px-3 py-1 rounded-full text-xs font-semibold"
                        :class="
                          modeStatus.mode !== 'gui' &&
                          modeStatus.mode !== 'none' &&
                          modeStatus.running
                            ? 'bg-warning-container/40 text-warning'
                            : 'bg-primary-container/40 text-primary'
                        "
                      >
                        {{ modeLabel }}
                      </span>
                    </div>
                  </div>
                  <p
                    v-if="modeStatus.mode === 'cli' && modeStatus.running"
                    class="mt-2 text-xs text-warning"
                  >
                    {{ $t('settings.runMode.cliRunning', { pid: modeStatus.pid }) }}
                  </p>
                  <p
                    v-if="modeStatus.mode === 'tui' && modeStatus.running"
                    class="mt-2 text-xs text-warning"
                  >
                    {{ $t('settings.runMode.tuiRunning', { pid: modeStatus.pid }) }}
                  </p>
                  <div class="mt-3 grid grid-cols-2 gap-2">
                    <button
                      @click="switchToCli"
                      class="w-full rounded-xl bg-primary px-4 py-2.5 text-sm font-semibold text-on-primary hover:opacity-90 transition-opacity"
                    >
                      {{ $t('settings.runMode.switchButton') }}
                    </button>
                    <button
                      @click="switchToTui"
                      class="w-full rounded-xl bg-secondary px-4 py-2.5 text-sm font-semibold text-on-secondary hover:opacity-90 transition-opacity"
                    >
                      {{ $t('settings.runMode.switchTuiButton') }}
                    </button>
                  </div>
                </div>

                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('settings.language.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">
                      {{ $t('settings.language.desc') }}
                    </p>
                  </div>
                  <Select v-model="currentLanguage">
                    <SelectTrigger
                      class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium"
                    >
                      <SelectValue placeholder="Language" />
                    </SelectTrigger>
                    <SelectContent
                      class="border-surface-variant/20 rounded-lg bg-surface shadow-lg"
                    >
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
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('closeBehavior.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('closeBehavior.desc') }}</p>
                  </div>
                  <Select v-model="closeBehavior">
                    <SelectTrigger
                      class="w-[160px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium"
                    >
                      <SelectValue :placeholder="$t('closeBehavior.ask')" />
                    </SelectTrigger>
                    <SelectContent
                      class="border-surface-variant/20 rounded-lg bg-surface shadow-lg"
                    >
                      <SelectGroup>
                        <SelectItem value="ask">{{ $t('closeBehavior.ask') }}</SelectItem>
                        <SelectItem value="hide">{{ $t('closeBehavior.hide') }}</SelectItem>
                        <SelectItem value="exit">{{ $t('closeBehavior.exit') }}</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </div>

                <!-- Start Minimized -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('startMinimized.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('startMinimized.desc') }}</p>
                  </div>
                  <button
                    @click="startMinimized = !startMinimized"
                    class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                    :class="
                      startMinimized
                        ? 'border-primary bg-primary'
                        : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                    "
                  >
                    <div
                      class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                      :class="startMinimized ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                    >
                      <span
                        class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                        :class="
                          startMinimized
                            ? 'h-6 w-6 bg-on-primary'
                            : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                        "
                      />
                    </div>
                  </button>
                </div>

                <!-- Run at Startup -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('autostart.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('autostart.desc') }}</p>
                  </div>
                  <button
                    @click="toggleAutostart"
                    class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                    :class="
                      autostartEnabled
                        ? 'border-primary bg-primary'
                        : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                    "
                  >
                    <div
                      class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                      :class="autostartEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                    >
                      <span
                        class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                        :class="
                          autostartEnabled
                            ? 'h-6 w-6 bg-on-primary'
                            : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                        "
                      />
                    </div>
                  </button>
                </div>

                <!-- Desktop Floating Window -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('floatingWindow.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('floatingWindow.desc') }}</p>
                  </div>
                  <button
                    @click="toggleFloatingWindow"
                    class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                    :class="
                      floatingWindowEnabled
                        ? 'border-primary bg-primary'
                        : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                    "
                  >
                    <div
                      class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                      :class="floatingWindowEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                    >
                      <span
                        class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                        :class="
                          floatingWindowEnabled
                            ? 'h-6 w-6 bg-on-primary'
                            : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                        "
                      />
                    </div>
                  </button>
                </div>

                <!-- Notifications -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('notifications.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('notifications.desc') }}</p>
                  </div>
                  <button
                    @click="notificationsEnabled = !notificationsEnabled"
                    class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                    :class="
                      notificationsEnabled
                        ? 'border-primary bg-primary'
                        : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                    "
                  >
                    <div
                      class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                      :class="notificationsEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                    >
                      <span
                        class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                        :class="
                          notificationsEnabled
                            ? 'h-6 w-6 bg-on-primary'
                            : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                        "
                      />
                    </div>
                  </button>
                </div>

                <!-- Auto Stream -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('autoStream.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('autoStream.desc') }}</p>
                  </div>
                  <button
                    @click="autoStream = !autoStream"
                    class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                    :class="
                      autoStream
                        ? 'border-primary bg-primary'
                        : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                    "
                  >
                    <div
                      class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                      :class="autoStream ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                    >
                      <span
                        class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                        :class="
                          autoStream
                            ? 'h-6 w-6 bg-on-primary'
                            : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                        "
                      />
                    </div>
                  </button>
                </div>

                <!-- Pocket Mode -->
                <div
                  class="bg-surface-bright/60 backdrop-blur-lg rounded-2xl p-4 flex items-center justify-between shadow-sm border border-white/5"
                >
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('settings.pocketMode.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">
                      {{ $t('settings.pocketMode.desc') }}
                    </p>
                  </div>
                  <button
                    @click="pocketMode = !pocketMode"
                    class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                    :class="
                      pocketMode
                        ? 'border-primary bg-primary'
                        : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                    "
                  >
                    <div
                      class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                      :class="pocketMode ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                    >
                      <!-- State layer (hover halo) -->

                      <span
                        class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                        :class="
                          pocketMode
                            ? 'h-6 w-6 bg-on-primary'
                            : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                        "
                      />
                    </div>
                  </button>
                </div>

                <!-- Output Device -->
                <div class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
                  <div>
                    <h4 class="font-bold text-on-surface">
                      {{ $t('settings.audioOutput.title') }}
                    </h4>
                    <p class="text-xs text-on-surface-variant">
                      {{ $t('settings.audioOutput.desc') }}
                    </p>
                  </div>
                  <div class="relative">
                    <Select v-model="settings.audioDevice">
                      <SelectTrigger
                        class="w-full bg-surface-container border-none shadow-none rounded-xl h-12 px-4 font-medium text-sm"
                      >
                        <SelectValue :placeholder="$t('settings.audioOutput.auto')" />
                      </SelectTrigger>
                      <SelectContent
                        class="border-surface-variant/20 rounded-xl bg-surface shadow-lg max-h-[40vh]"
                      >
                        <SelectGroup>
                          <SelectItem value="auto">{{
                            $t('settings.audioOutput.auto')
                          }}</SelectItem>
                          <SelectItem v-for="dev in audioDevices" :key="dev" :value="dev">{{
                            dev
                          }}</SelectItem>
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  </div>
                  <!-- Status / Routing Active -->
                  <div
                    v-if="isVirtualDeviceSelected"
                    class="flex items-center gap-1.5 text-xs text-green-400 font-medium"
                  >
                    <CheckCircle2 class="w-3.5 h-3.5 shrink-0" />
                    <span>{{ $t('settings.audioOutput.routingActive') }}</span>
                  </div>

                  <!-- Explicit Physical Speaker Warning -->
                  <div
                    v-else-if="isExplicitPhysicalDevice"
                    class="rounded-xl bg-amber-500/10 border border-amber-500/20 p-3 space-y-1 text-xs text-amber-400"
                  >
                    <div class="flex items-center gap-1.5 font-bold">
                      <AlertTriangle class="w-4 h-4 shrink-0 text-amber-400" />
                      <span>{{ $t('settings.audioOutput.physicalWarningTitle') }}</span>
                    </div>
                    <p class="text-on-surface-variant leading-relaxed">
                      {{ $t('settings.audioOutput.physicalWarningDesc') }}
                    </p>
                  </div>

                  <!-- Auto Fallback to Physical Speaker Warning -->
                  <div
                    v-else-if="isAutoFallbackToPhysical"
                    class="rounded-xl bg-amber-500/10 border border-amber-500/20 p-3 space-y-1 text-xs text-amber-400"
                  >
                    <div class="flex items-center gap-1.5 font-bold">
                      <AlertTriangle class="w-4 h-4 shrink-0 text-amber-400" />
                      <span>{{ $t('settings.audioOutput.noVirtualDeviceTitle') }}</span>
                    </div>
                    <p class="text-on-surface-variant leading-relaxed">
                      {{ $t('settings.audioOutput.noVirtualDeviceDesc') }}
                    </p>
                  </div>
                </div>

                <!-- Virtual Audio Device Management (macOS: BlackHole, Windows: VB-Cable) -->
                <div v-if="isMacOS" class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
                  <div class="flex items-center justify-between">
                    <h4 class="font-bold text-on-surface text-lg">BlackHole</h4>
                    <span
                      class="text-xs font-medium px-2 py-1 rounded-md"
                      :class="
                        hasBlackHole
                          ? 'bg-green-500/20 text-green-400'
                          : 'bg-red-500/20 text-red-400'
                      "
                    >
                      {{
                        hasBlackHole
                          ? $t('settings.blackhole.installed')
                          : $t('settings.blackhole.notDetected')
                      }}
                    </span>
                  </div>
                  <p class="text-xs text-on-surface-variant">
                    {{ $t('settings.blackhole.desc') }}
                  </p>
                  <div
                    v-if="blackholeStatus.switch_audio_source"
                    class="text-xs text-on-surface-variant flex items-center gap-1.5"
                  >
                    <span class="inline-block w-1.5 h-1.5 rounded-full bg-green-400"></span>
                    SwitchAudioSource {{ $t('settings.blackhole.available') }}
                  </div>
                  <div
                    v-else-if="hasBlackHole"
                    class="text-xs text-orange-400 flex items-center gap-1.5"
                  >
                    <span class="inline-block w-1.5 h-1.5 rounded-full bg-orange-400"></span>
                    {{ $t('settings.blackhole.switchAudioSourceMissing') }}
                  </div>
                  <div v-if="!hasBlackHole" class="space-y-2">
                    <p
                      class="text-xs text-on-surface-variant font-mono bg-surface-container rounded-lg p-3 select-all"
                    >
                      brew install blackhole-2ch
                    </p>
                    <button
                      @click="openBlackHoleDownload"
                      class="w-full py-2 bg-surface-variant hover:bg-surface-variant/80 rounded-xl text-sm font-bold flex items-center justify-center gap-2 transition-colors"
                    >
                      <Download class="w-4 h-4" /> {{ $t('settings.blackhole.download') }}
                    </button>
                  </div>
                </div>
                <div
                  v-else-if="isLinux"
                  class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm"
                >
                  <div class="flex items-center justify-between">
                    <h4 class="font-bold text-on-surface text-lg">PipeWire</h4>
                    <span
                      class="text-xs font-medium px-2 py-1 rounded-md"
                      :class="
                        pipewireStatus.available
                          ? pipewireStatus.device_exists
                            ? 'bg-green-500/20 text-green-400'
                            : 'bg-yellow-500/20 text-yellow-400'
                          : 'bg-red-500/20 text-red-400'
                      "
                    >
                      {{
                        pipewireStatus.available
                          ? pipewireStatus.device_exists
                            ? $t('settings.pipewire.active')
                            : $t('settings.pipewire.available')
                          : $t('settings.pipewire.notAvailable')
                      }}
                    </span>
                  </div>
                  <p class="text-xs text-on-surface-variant">
                    {{ $t('settings.pipewire.desc') }}
                  </p>
                  <div
                    v-if="!pipewireStatus.available"
                    class="text-xs text-on-surface-variant font-mono bg-surface-container rounded-lg p-3 select-all"
                  >
                    sudo apt install pipewire pipewire-pulse
                  </div>
                </div>
                <div v-else class="bg-surface-bright rounded-2xl p-4 space-y-4 shadow-sm">
                  <div class="flex items-center justify-between">
                    <h4 class="font-bold text-on-surface text-lg">
                      {{ $t('settings.vbcable.title') }}
                    </h4>
                    <span
                      class="text-xs font-medium px-2 py-1 rounded-md"
                      :class="
                        hasVBCable ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'
                      "
                    >
                      {{
                        hasVBCable
                          ? $t('settings.vbcable.installed')
                          : $t('settings.vbcable.notDetected')
                      }}
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
                      {{
                        vbcableInstalling
                          ? vbcableInstallProgress || $t('vbcableInstall.installing')
                          : $t('vbcableDetect.autoInstall')
                      }}
                    </button>
                    <button
                      @click="openVBCableDownload"
                      class="w-full py-2 bg-surface-variant hover:bg-surface-variant/80 rounded-xl text-sm font-bold flex items-center justify-center gap-2 transition-colors"
                    >
                      <Download class="w-4 h-4" /> {{ $t('settings.vbcable.download') }}
                    </button>
                  </div>
                </div>

                <!-- Restore default settings -->
                <div class="rounded-2xl bg-surface-variant/20 border border-outline/10 p-4">
                  <div class="flex items-center justify-between gap-3">
                    <div class="min-w-0">
                      <p class="text-sm font-bold text-foreground">{{ $t('settings.restoreDefaults.label') }}</p>
                      <p class="text-xs text-on-surface-variant mt-0.5">{{ $t('settings.restoreDefaults.desc') }}</p>
                    </div>
                    <button
                      class="shrink-0 rounded-full bg-error/10 px-4 py-2 text-sm font-medium text-error transition-colors hover:bg-error/20"
                      @click="restoreDefaultSettings"
                    >
                      {{ $t('settings.restoreDefaults.button') }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- APPEARANCE SECTION -->
              <div v-else-if="currentSection === 'appearance'" class="space-y-6" key="appearance">
                <!-- Theme Mode Settings -->
                <div class="surface-card flex items-center justify-between">
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('settings.theme.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('settings.theme.desc') }}</p>
                  </div>
                  <Select v-model="colorMode">
                    <SelectTrigger
                      class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium"
                    >
                      <SelectValue :placeholder="$t('settings.theme.auto')" />
                    </SelectTrigger>
                    <SelectContent
                      class="border-surface-variant/20 rounded-lg bg-surface shadow-lg"
                    >
                      <SelectGroup>
                        <SelectItem value="auto">{{ $t('settings.theme.auto') }}</SelectItem>
                        <SelectItem value="light">{{ $t('settings.theme.light') }}</SelectItem>
                        <SelectItem value="dark">{{ $t('settings.theme.dark') }}</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </div>

                <div class="relative space-y-6">
                  <!-- Theme Color Source -->
                  <div class="surface-card flex items-center justify-between">
                    <div>
                      <h4 class="font-bold text-on-surface">
                        {{ $t('settings.theme.modeTitle') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.theme.modeDesc') }}
                      </p>
                    </div>
                    <Select v-model="themeMode" :disabled="themePackageActive">
                      <SelectTrigger
                        class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium"
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent
                        class="border-surface-variant/20 rounded-lg bg-surface shadow-lg"
                      >
                        <SelectGroup>
                          <SelectItem value="system">{{
                            $t('settings.theme.systemColor')
                          }}</SelectItem>
                          <SelectItem value="preset">{{
                            $t('settings.theme.presetColor')
                          }}</SelectItem>
                          <SelectItem value="custom">{{
                            $t('settings.theme.customColor')
                          }}</SelectItem>
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  </div>

                  <!-- Theme Color Settings -->
                  <div
                    class="surface-card relative flex items-center justify-between overflow-hidden"
                  >
                    <div class="flex-shrink-0 mr-4">
                      <h4 class="font-bold text-on-surface">
                        {{ $t('settings.themeColor.title') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.themeColor.desc') }}
                      </p>
                    </div>
                    <div class="flex justify-end">
                      <ThemeSelector
                        v-model="themeColor"
                        :custom-h="customH"
                        :custom-s="customS"
                        :custom-l="customL"
                        :disabled="themePackageActive"
                        @update:model-value="themeMode = 'preset'"
                        @open-custom="
                          themeMode = 'custom';
                          showColorPicker = true;
                        "
                      />
                    </div>

                    <div
                      v-if="!themePackageActive && themeMode === 'system'"
                      class="absolute inset-0 z-10 flex items-center justify-center gap-3 bg-surface-bright/80 px-4 backdrop-blur-sm"
                    >
                      <span
                        class="h-8 w-8 shrink-0 rounded-full border border-outline/30 shadow-sm"
                        :style="{ backgroundColor: systemAccent.hex }"
                      ></span>
                      <div class="min-w-0">
                        <p class="text-sm font-medium text-on-surface">
                          {{
                            systemAccent.supported
                              ? $t('settings.theme.systemReady')
                              : $t('settings.theme.systemUnavailable')
                          }}
                        </p>
                        <p class="text-xs text-on-surface-variant">
                          {{ $t('settings.theme.systemSource', { source: systemAccent.source }) }}
                        </p>
                      </div>
                    </div>
                  </div>

                  <!-- Theme Generation Variant Settings -->
                  <div class="surface-card flex items-center justify-between">
                    <div>
                      <h4 class="font-bold text-on-surface">
                        {{ $t('settings.customColor.variant') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.customColor.variantDesc') }}
                      </p>
                    </div>
                    <Select v-model="customVariant" :disabled="themePackageActive">
                      <SelectTrigger
                        class="w-[160px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium"
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent
                        class="border-surface-variant/20 rounded-lg bg-surface shadow-lg"
                      >
                        <SelectGroup>
                          <SelectItem value="TonalSpot">{{
                            $t('settings.customColor.variants.tonalSpot')
                          }}</SelectItem>
                          <SelectItem value="Neutral">{{
                            $t('settings.customColor.variants.neutral')
                          }}</SelectItem>
                          <SelectItem value="Vibrant">{{
                            $t('settings.customColor.variants.vibrant')
                          }}</SelectItem>
                          <SelectItem value="Expressive">{{
                            $t('settings.customColor.variants.expressive')
                          }}</SelectItem>
                          <SelectItem value="Rainbow">{{
                            $t('settings.customColor.variants.rainbow')
                          }}</SelectItem>
                          <SelectItem value="FruitSalad">{{
                            $t('settings.customColor.variants.fruitSalad')
                          }}</SelectItem>
                          <SelectItem value="Monochrome">{{
                            $t('settings.customColor.variants.monochrome')
                          }}</SelectItem>
                          <SelectItem value="Fidelity">{{
                            $t('settings.customColor.variants.fidelity')
                          }}</SelectItem>
                          <SelectItem value="Content">{{
                            $t('settings.customColor.variants.content')
                          }}</SelectItem>
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  </div>

                  <div
                    v-if="themePackageActive"
                    class="absolute inset-0 z-20 !mt-0 flex min-h-full items-center justify-center rounded-2xl bg-surface-bright/90 p-6 text-center shadow-lg backdrop-blur-md"
                  >
                    <div class="flex max-w-sm flex-col items-center gap-3">
                      <div
                        class="flex h-12 w-12 items-center justify-center rounded-full bg-primary/15 text-primary"
                      >
                        <Palette class="h-6 w-6" />
                      </div>
                      <p class="text-sm font-semibold text-on-surface">
                        {{ $t('settings.theme.packageActive') }}
                      </p>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.theme.packageActiveDesc', { id: installedThemeId }) }}
                      </p>
                      <button
                        class="mt-1 rounded-full bg-primary px-4 py-2 text-sm font-medium text-on-primary transition-opacity hover:opacity-90"
                        @click="deactivateInstalledTheme"
                      >
                        {{ $t('settings.theme.deactivate') }}
                      </button>
                    </div>
                  </div>
                </div>

                <!-- UI Style Settings -->
                <div class="surface-card flex items-center justify-between">
                  <div>
                    <h4 class="font-bold text-on-surface">{{ $t('settings.uiStyle.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">{{ $t('settings.uiStyle.desc') }}</p>
                  </div>
                  <Select v-model="uiStyle">
                    <SelectTrigger
                      class="w-[140px] bg-surface-container border-none shadow-none rounded-lg text-sm font-medium"
                    >
                      <SelectValue :placeholder="$t('settings.uiStyle.glass')" />
                    </SelectTrigger>
                    <SelectContent
                      class="border-surface-variant/20 rounded-lg bg-surface shadow-lg"
                    >
                      <SelectGroup>
                        <SelectItem value="style-default">{{
                          $t('settings.uiStyle.default')
                        }}</SelectItem>
                        <SelectItem value="style-glass">{{
                          $t('settings.uiStyle.glass')
                        }}</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </div>

                <!-- Custom CSS -->
                <div class="surface-card flex items-center justify-between">
                  <div class="mr-4 flex-1">
                    <h4 class="font-bold text-on-surface">{{ $t('settings.customCss.title') }}</h4>
                    <p class="text-xs text-on-surface-variant">
                      {{ $t('settings.customCss.desc') }}
                    </p>
                  </div>
                  <button
                    @click="showCustomCssDialog = true"
                    class="flex-shrink-0 rounded-full bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/20"
                  >
                    {{ $t('settings.customCss.editBtn') }}
                  </button>
                </div>

                <div class="surface-card flex items-center justify-between">
                  <div class="flex-1 mr-4">
                    <h4 class="font-bold text-on-surface">
                      {{ $t('settings.theme.catalogTitle') }}
                    </h4>
                    <p class="text-xs text-on-surface-variant">
                      {{ $t('settings.theme.catalogDesc') }}
                    </p>
                  </div>
                  <button
                    class="rounded-full bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/20"
                    @click="showThemeCatalogDialog = true"
                  >
                    {{ $t('settings.theme.catalogButton') }}
                  </button>
                </div>

                <!-- Restore default theme -->
                <div class="rounded-2xl bg-surface-variant/20 border border-outline/10 p-4">
                  <div class="flex items-center justify-between gap-3">
                    <div class="min-w-0">
                      <p class="text-sm font-bold text-foreground">{{ $t('settings.restoreTheme.label') }}</p>
                      <p class="text-xs text-on-surface-variant mt-0.5">{{ $t('settings.restoreTheme.desc') }}</p>
                    </div>
                    <button
                      class="shrink-0 rounded-full bg-error/10 px-4 py-2 text-sm font-medium text-error transition-colors hover:bg-error/20"
                      @click="restoreDefaultTheme"
                    >
                      {{ $t('settings.restoreTheme.button') }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- AUDIO SECTION -->
              <div v-else-if="currentSection === 'audio'" class="space-y-6" key="audio">
                <!-- Spectrum Analyzer / Real-time Monitoring -->
                <div class="haze-surface p-4 space-y-3">
                  <div class="flex justify-between items-center mb-2">
                    <h4 class="font-bold text-on-surface text-sm">
                      {{ $t('settings.spectrum.title') }}
                    </h4>
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
                  <span class="text-sm font-medium text-on-surface whitespace-nowrap">{{
                    $t('settings.audioParams.gain')
                  }}</span>
                  <MD3Slider :min="-50" :max="50" v-model="settings.gain" />
                  <span class="text-xs w-12 text-right"
                    >{{ settings.gain > 0 ? '+' : '' }}{{ settings.gain }} dB</span
                  >
                </div>

                <!-- Acoustic Echo Cancellation (AEC) -->
                <div class="bg-surface-bright rounded-2xl p-4 shadow-sm">
                  <div
                    class="flex justify-between items-center"
                    :class="isAecSupported && aecRuntimeAvailable ? 'cursor-pointer' : ''"
                    @click="
                      isAecSupported &&
                      aecRuntimeAvailable &&
                      (settings.aecEnabled = !settings.aecEnabled)
                    "
                  >
                    <div>
                      <span class="font-medium text-on-surface">{{
                        $t('settings.audioParams.aec')
                      }}</span>
                      <p class="text-xs text-on-surface-variant mt-0.5">
                        {{ $t('settings.audioParams.aecDesc') }}
                      </p>
                      <p
                        v-if="!isAecSupported"
                        class="text-xs text-on-surface-variant mt-1 flex items-center gap-1"
                      >
                        <Ban class="w-3 h-3 shrink-0" />
                        {{ $t('settings.audioParams.aecUnavailable') }}
                      </p>
                      <p
                        v-else-if="!aecRuntimeAvailable"
                        class="text-xs text-on-surface-variant mt-1 flex items-center gap-1"
                      >
                        <Ban class="w-3 h-3 shrink-0" />
                        {{ $t('settings.audioParams.aecRuntimeUnavailable') }}
                      </p>
                    </div>
                    <button
                      :disabled="!isAecSupported || !aecRuntimeAvailable"
                      class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
                      :class="
                        settings.aecEnabled
                          ? 'border-primary bg-primary'
                          : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                      "
                    >
                      <div
                        class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                        :class="settings.aecEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                      >
                        <span
                          class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                          :class="
                            settings.aecEnabled
                              ? 'h-6 w-6 bg-on-primary'
                              : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                          "
                        />
                      </div>
                    </button>
                  </div>
                </div>

                <!-- Noise Suppression -->
                <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
                  <div
                    class="flex justify-between items-center cursor-pointer"
                    @click="settings.nsEnabled = !settings.nsEnabled"
                  >
                    <span class="font-medium text-on-surface">{{
                      $t('settings.audioParams.noiseSuppression')
                    }}</span>
                    <button
                      class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                      :class="
                        settings.nsEnabled
                          ? 'border-primary bg-primary'
                          : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                      "
                    >
                      <div
                        class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                        :class="settings.nsEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                      >
                        <span
                          class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                          :class="
                            settings.nsEnabled
                              ? 'h-6 w-6 bg-on-primary'
                              : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                          "
                        />
                      </div>
                    </button>
                  </div>
                  <div
                    v-if="settings.nsEnabled"
                    class="space-y-4 pt-2 border-t border-surface-variant/20"
                  >
                    <div class="flex gap-2 mt-4">
                      <button
                        v-for="type in [
                          { id: 'PureVox', label: 'PureVox (ONNX)' },
                          { id: 'RNNoise', label: 'RNNoise' },
                          { id: 'Speexdsp', label: 'Speexdsp' },
                        ]"
                        :key="type.id"
                        @click="settings.nsType = type.id"
                        class="px-3 py-1 rounded-full text-xs font-medium transition-colors"
                        :class="
                          settings.nsType === type.id
                            ? 'bg-primary text-on-primary'
                            : 'bg-surface-container text-on-surface'
                        "
                      >
                        {{ type.label }}
                      </button>
                    </div>
                    <div class="flex items-center gap-4">
                      <span class="text-xs text-on-surface-variant whitespace-nowrap">{{
                        $t('settings.audioParams.intensity')
                      }}</span>
                      <MD3Slider :min="0" :max="100" v-model="settings.nsIntensity" />
                      <span class="text-xs w-8 text-right">{{ settings.nsIntensity }}%</span>
                    </div>
                  </div>
                </div>

                <!-- Dereverb -->
                <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
                  <div
                    class="flex justify-between items-center cursor-pointer"
                    @click="settings.dereverbEnabled = !settings.dereverbEnabled"
                  >
                    <span class="font-medium text-on-surface">{{
                      $t('settings.audioParams.dereverb')
                    }}</span>
                    <button
                      class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                      :class="
                        settings.dereverbEnabled
                          ? 'border-primary bg-primary'
                          : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                      "
                    >
                      <div
                        class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                        :class="
                          settings.dereverbEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'
                        "
                      >
                        <span
                          class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                          :class="
                            settings.dereverbEnabled
                              ? 'h-6 w-6 bg-on-primary'
                              : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                          "
                        />
                      </div>
                    </button>
                  </div>
                  <div
                    v-if="settings.dereverbEnabled"
                    class="flex items-center gap-4 pt-4 border-t border-surface-variant/20"
                  >
                    <span class="text-xs text-on-surface-variant whitespace-nowrap">{{
                      $t('settings.audioParams.level')
                    }}</span>
                    <MD3Slider :min="0" :max="100" v-model="settings.dereverbLevel" />
                    <span class="text-xs w-8 text-right">{{ settings.dereverbLevel }}%</span>
                  </div>
                </div>

                <!-- Auto Gain Control -->
                <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
                  <div
                    class="flex justify-between items-center cursor-pointer"
                    @click="settings.agcEnabled = !settings.agcEnabled"
                  >
                    <span class="font-medium text-on-surface">{{
                      $t('settings.audioParams.agc')
                    }}</span>
                    <button
                      class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                      :class="
                        settings.agcEnabled
                          ? 'border-primary bg-primary'
                          : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                      "
                    >
                      <div
                        class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                        :class="settings.agcEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                      >
                        <span
                          class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                          :class="
                            settings.agcEnabled
                              ? 'h-6 w-6 bg-on-primary'
                              : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                          "
                        />
                      </div>
                    </button>
                  </div>
                  <div
                    v-if="settings.agcEnabled"
                    class="space-y-4 pt-4 border-t border-surface-variant/20"
                  >
                    <div class="flex items-center gap-4">
                      <span class="text-xs text-on-surface-variant w-20">{{
                        $t('settings.audioParams.target')
                      }}</span>
                      <MD3Slider :min="0" :max="32767" v-model="settings.agcTarget" />
                      <span class="text-xs w-10 text-right">{{ settings.agcTarget }}</span>
                    </div>
                    <div class="flex items-center gap-4">
                      <span class="text-xs text-on-surface-variant w-20">{{
                        $t('settings.audioParams.attack')
                      }}</span>
                      <MD3Slider :min="1" :max="100" v-model="settings.agcAttack" />
                      <span class="text-xs w-10 text-right">{{
                        (settings.agcAttack / 1000).toFixed(3)
                      }}</span>
                    </div>
                    <div class="flex items-center gap-4">
                      <span class="text-xs text-on-surface-variant w-20">{{
                        $t('settings.audioParams.decay')
                      }}</span>
                      <MD3Slider :min="1" :max="100" v-model="settings.agcDecay" />
                      <span class="text-xs w-10 text-right">{{
                        (settings.agcDecay / 10000).toFixed(4)
                      }}</span>
                    </div>
                  </div>
                </div>

                <!-- Voice Activity Detection -->
                <div class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-4">
                  <div
                    class="flex justify-between items-center cursor-pointer"
                    @click="settings.vadEnabled = !settings.vadEnabled"
                  >
                    <span class="font-medium text-on-surface">{{
                      $t('settings.audioParams.vad')
                    }}</span>
                    <button
                      class="group relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-300 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 active:scale-95"
                      :class="
                        settings.vadEnabled
                          ? 'border-primary bg-primary'
                          : 'border-on-surface-variant bg-transparent hover:bg-on-surface-variant/10'
                      "
                    >
                      <div
                        class="relative flex items-center justify-center transition-transform duration-300 ease-out"
                        :class="settings.vadEnabled ? 'translate-x-[26px]' : 'translate-x-[4px]'"
                      >
                        <span
                          class="pointer-events-none block rounded-full shadow-sm ring-0 transition-all duration-300 ease-out"
                          :class="
                            settings.vadEnabled
                              ? 'h-6 w-6 bg-on-primary'
                              : 'h-4 w-4 bg-on-surface-variant group-hover:h-5 group-hover:w-5'
                          "
                        />
                      </div>
                    </button>
                  </div>
                  <div
                    v-if="settings.vadEnabled"
                    class="flex items-center gap-4 pt-4 border-t border-surface-variant/20"
                  >
                    <span class="text-xs text-on-surface-variant whitespace-nowrap">{{
                      $t('settings.audioParams.threshold')
                    }}</span>
                    <MD3Slider :min="-100" :max="0" v-model="settings.vadThreshold" />
                    <span class="text-xs w-12 text-right">{{ settings.vadThreshold }} dB</span>
                  </div>
                </div>

                <!-- Audio Processing Chain -->
                <div
                  @click="showAudioChain = true"
                  class="bg-surface-bright rounded-2xl p-4 shadow-sm space-y-3 cursor-pointer hover:bg-surface-variant transition-colors group"
                >
                  <div class="flex items-center justify-between">
                    <div>
                      <h4 class="font-bold text-on-surface">
                        {{ $t('settings.audioChain.title') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant mt-0.5">
                        {{ $t('settings.audioChain.descPopup') }}
                      </p>
                    </div>
                    <div
                      class="w-8 h-8 rounded-full bg-surface-container flex items-center justify-center group-hover:bg-primary group-hover:text-on-primary transition-colors"
                    >
                      <ChevronRight
                        class="w-4 h-4 text-on-surface-variant group-hover:text-on-primary transition-colors"
                      />
                    </div>
                  </div>

                  <div
                    class="flex items-center gap-2 overflow-hidden text-xs text-on-surface-variant font-medium opacity-80 pt-1"
                  >
                    <template v-for="(item, index) in displayChain" :key="item">
                      <span class="whitespace-nowrap">{{ $t(`settings.audioChain.${item}`) }}</span>
                      <ArrowRight v-if="index < displayChain.length - 1" class="w-3 h-3 shrink-0" />
                    </template>
                  </div>
                </div>

                <!-- Output Buffer Size -->
                <div class="bg-surface-bright rounded-2xl p-4 shadow-sm">
                  <div class="flex items-center justify-between">
                    <div>
                      <span class="font-medium text-on-surface">{{
                        $t('settings.audioParams.bufferSize')
                      }}</span>
                      <p class="text-xs text-on-surface-variant mt-0.5">
                        {{ $t('settings.audioParams.bufferSizeDesc') }}
                      </p>
                    </div>
                    <span
                      class="text-xs w-14 text-right text-on-surface-variant font-medium whitespace-nowrap"
                      >{{ settings.outputBufferMs }} ms</span
                    >
                  </div>
                  <div class="flex items-center gap-3">
                    <span class="text-[10px] text-on-surface-variant shrink-0">{{
                      $t('settings.audioParams.bufferLow')
                    }}</span>
                    <MD3Slider
                      :min="100"
                      :max="1200"
                      :step="100"
                      v-model="settings.outputBufferMs"
                    />
                    <span class="text-[10px] text-on-surface-variant shrink-0">{{
                      $t('settings.audioParams.bufferHigh')
                    }}</span>
                  </div>
                </div>
              </div>

              <!-- EQUALIZER SECTION -->
              <div
                v-else-if="currentSection === 'equalizer'"
                class="space-y-6 h-[600px]"
                key="equalizer"
              >
                <EqualizerPanel :config="settings.equalizer" />
              </div>

              <!-- PLUGINS SECTION -->
              <div v-else-if="currentSection === 'plugins'" class="space-y-6" key="plugins">
                <PluginsPanel />
              </div>

              <!-- PLUGIN CUSTOM PANEL (sandbox iframe + postMessage bridge) -->
              <div
                v-else-if="currentSection.startsWith('panel:')"
                class="space-y-4"
                key="plugin-panel"
              >
                <div
                  v-if="panelLoading"
                  class="flex items-center gap-2 text-sm text-on-surface-variant"
                >
                  <span
                    class="animate-spin inline-block w-4 h-4 border-2 border-primary border-t-transparent rounded-full"
                  ></span>
                  {{ $t('plugins.loading') }}
                </div>
                <div
                  v-else-if="panelError"
                  class="text-sm text-red-400 bg-red-500/10 rounded-xl p-4 font-mono break-all"
                >
                  {{ panelError }}
                </div>
                <div v-else class="space-y-3">
                  <iframe
                    ref="panelFrame"
                    :srcdoc="panelHtml"
                    sandbox="allow-scripts allow-popups"
                    class="w-full h-[600px] rounded-2xl border border-border"
                    style="background: hsl(var(--surface))"
                  ></iframe>
                </div>
              </div>

              <!-- ABOUT -->
              <div v-else-if="currentSection === 'about'" class="space-y-4 pb-12" key="about">
                <div
                  class="bg-surface-bright rounded-2xl overflow-hidden shadow-sm flex flex-col border border-border"
                >
                  <div
                    class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-default"
                  >
                    <User class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                    <div class="flex-1">
                      <h4 class="text-sm font-medium text-on-surface">Developer</h4>
                      <p class="text-xs text-on-surface-variant">LanRhyme、ChinsaaWei、ChouChiu</p>
                    </div>
                  </div>
                  <div class="h-px bg-border mx-4"></div>

                  <a
                    href="https://github.com/LanRhyme/MicYou"
                    target="_blank"
                    class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer group"
                  >
                    <Globe class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                    <div class="flex-1">
                      <h4 class="text-sm font-medium text-on-surface">GitHub Repository</h4>
                      <p class="text-xs text-primary group-hover:underline">
                        https://github.com/LanRhyme/MicYou
                      </p>
                    </div>
                  </a>
                  <div class="h-px bg-border mx-4"></div>

                  <div
                    @click="openDialog('Contributors')"
                    class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer"
                  >
                    <Users class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                    <div class="flex-1">
                      <h4 class="text-sm font-medium text-on-surface">
                        {{ $t('settings.about.contributorsBtn') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.about.contributorsDesc') }}
                      </p>
                    </div>
                  </div>
                  <div class="h-px bg-border mx-4"></div>

                  <div
                    @click="openDialog('Sponsors')"
                    class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer"
                  >
                    <Heart class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                    <div class="flex-1">
                      <h4 class="text-sm font-medium text-on-surface">
                        {{ $t('settings.about.sponsorsBtn') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.about.sponsorsDesc') }}
                      </p>
                    </div>
                  </div>
                  <div class="h-px bg-border mx-4"></div>

                  <div
                    class="flex items-center justify-between p-4 hover:bg-surface-variant transition-colors cursor-default"
                  >
                    <div class="flex items-center gap-4">
                      <Info class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                      <div>
                        <h4 class="text-sm font-medium text-on-surface">
                          {{ $t('settings.about.version') }}
                        </h4>
                        <p class="text-xs text-on-surface-variant">{{ appVersion }}</p>
                      </div>
                    </div>
                    <button
                      @click="openDialog('Update')"
                      class="px-3 py-1.5 text-xs font-medium text-on-primary bg-primary hover:opacity-90 rounded-full transition-opacity"
                    >
                      {{ $t('settings.about.updatesBtn') }}
                    </button>
                  </div>
                  <div class="h-px bg-border mx-4"></div>

                  <div
                    @click="openDialog('Licenses')"
                    class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer"
                  >
                    <FileText class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                    <div class="flex-1">
                      <h4 class="text-sm font-medium text-on-surface">
                        {{ $t('settings.about.licensesBtn') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.about.licensesDesc') }}
                      </p>
                    </div>
                  </div>
                  <div class="h-px bg-border mx-4"></div>

                  <div
                    @click="exportLog"
                    class="flex items-center gap-4 p-4 hover:bg-surface-variant transition-colors cursor-pointer"
                  >
                    <Download class="w-6 h-6 text-on-surface-variant flex-shrink-0" />
                    <div class="flex-1">
                      <h4 class="text-sm font-medium text-on-surface">
                        {{ $t('settings.about.logsBtn') }}
                      </h4>
                      <p class="text-xs text-on-surface-variant">
                        {{ $t('settings.about.logsDesc') }}
                      </p>
                    </div>
                  </div>
                </div>

                <div class="bg-secondary-container/50 rounded-2xl p-6 mt-4">
                  <h3 class="text-base font-bold text-on-secondary-container mb-2">
                    {{ $t('settings.about.introTitle') }}
                  </h3>
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

  <!-- Restore defaults: confirm dialog (mirrors App.vue IP switch confirm style) -->
  <Transition
    enter-active-class="transition ease-out duration-200"
    enter-from-class="opacity-0"
    enter-to-class="opacity-100"
    leave-active-class="transition ease-in duration-150"
    leave-from-class="opacity-100"
    leave-to-class="opacity-0"
  >
    <div v-if="restoreConfirmTarget" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div class="bg-surface rounded-2xl shadow-2xl border border-outline/10 p-6 w-80">
        <h3 class="text-sm font-bold text-foreground mb-2">
          {{ $t(restoreConfirmTarget === 'settings' ? 'settings.restoreDefaults.title' : 'settings.restoreTheme.title') }}
        </h3>
        <p class="text-xs text-on-surface-variant mb-5">
          {{ $t(restoreConfirmTarget === 'settings' ? 'settings.restoreDefaults.confirmDesc' : 'settings.restoreTheme.confirmDesc') }}
        </p>
        <div class="flex justify-end gap-2">
          <button
            class="px-4 py-2 text-xs font-medium text-on-surface-variant hover:bg-surface-variant/50 rounded-lg transition-colors"
            @click="restoreConfirmTarget = null"
          >
            {{ $t('dialogs.cancel') }}
          </button>
          <button
            class="px-4 py-2 text-xs font-medium text-on-primary bg-primary hover:bg-primary/90 rounded-lg transition-colors"
            @click="confirmRestore"
          >
            {{ $t('dialogs.confirm') }}
          </button>
        </div>
      </div>
    </div>
  </Transition>

  <!-- Restore defaults: result dialog -->
  <Transition
    enter-active-class="transition ease-out duration-200"
    enter-from-class="opacity-0"
    enter-to-class="opacity-100"
    leave-active-class="transition ease-in duration-150"
    leave-from-class="opacity-100"
    leave-to-class="opacity-0"
  >
    <div v-if="restoreResult" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div class="bg-surface rounded-2xl shadow-2xl border border-outline/10 p-6 w-80">
        <h3 class="text-sm font-bold text-foreground mb-2">{{ restoreResult.title }}</h3>
        <p class="text-xs text-on-surface-variant mb-5">{{ restoreResult.message }}</p>
        <div class="flex justify-end gap-2">
          <button
            class="px-4 py-2 text-xs font-medium text-on-primary bg-primary hover:bg-primary/90 rounded-lg transition-colors"
            @click="restoreResult = null"
          >
            {{ $t('dialogs.close') }}
          </button>
        </div>
      </div>
    </div>
  </Transition>

  <ContributorsDialog :isOpen="showContributors" @close="showContributors = false" />
  <SponsorsDialog :isOpen="showSponsors" @close="showSponsors = false" />
  <LicensesDialog :isOpen="showLicenses" @close="showLicenses = false" />
  <AudioChainDialog
    :isOpen="showAudioChain"
    :chain="settings.processingChain"
    @update:chain="updateProcessingChain"
    @close="showAudioChain = false"
  />
  <CustomCssDialog :isOpen="showCustomCssDialog" @close="showCustomCssDialog = false" />
  <ThemeCatalogDialog :isOpen="showThemeCatalogDialog" @close="showThemeCatalogDialog = false" />
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
import MD3Slider from '@/shared/components/ui/slider/MD3Slider.vue';
import { ref, computed, watch, reactive, onMounted, onUnmounted, nextTick } from 'vue';
import { useI18n } from 'vue-i18n';
import { useColorMode, useStorage } from '@vueuse/core';
import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';
import {
  isEnabled as isAutostartEnabled,
  enable as enableAutostart,
  disable as disableAutostart,
} from '@tauri-apps/plugin-autostart';
import {
  Settings as SettingsIcon,
  X,
  Mic,
  Info,
  Download,
  Loader2,
  User,
  Globe,
  Users,
  Heart,
  FileText,
  ChevronRight,
  ArrowRight,
  SlidersHorizontal,
  Palette,
  Ban,
  Puzzle,
  LayoutPanelTop,
  AlertTriangle,
  CheckCircle2,
} from '@lucide/vue';
import ContributorsDialog from './ContributorsDialog.vue';
import SponsorsDialog from './SponsorsDialog.vue';
import LicensesDialog from './LicensesDialog.vue';
import AudioChainDialog from '@/features/audio/components/AudioChainDialog.vue';
import CustomCssDialog from '@/features/theme/components/CustomCssDialog.vue';
import ThemeCatalogDialog from '@/features/theme/components/ThemeCatalogDialog.vue';
import EqualizerPanel from '@/features/audio/components/EqualizerPanel.vue';
import PluginsPanel from '@/features/plugins/components/PluginsPanel.vue';
import { usePlugins } from '@/features/plugins/composables/usePlugins';
import { usePluginPanelBridge } from '@/shared/composables/usePluginPanelBridge';
import ThemeSelector from '@/features/theme/components/ThemeSelector.vue';
import CustomColorPicker from '@/features/theme/components/CustomColorPicker.vue';
import { useTheme, DEFAULT_THEME } from '@/features/theme/composables/useTheme';

onMounted(() => {
  window.addEventListener('message', onPanelMessage);
  // 侧边栏插件面板项依赖插件列表，对话框挂载即刷新（未进插件页也可见）
  pluginsState.refresh().then(() => loadPanelIcons());
});
onUnmounted(() => window.removeEventListener('message', onPanelMessage));
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/components/ui/select';

const props = defineProps<{
  isOpen: boolean;
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

const {
  themeMode,
  themeColor,
  uiStyle,
  customH,
  customS,
  customL,
  customVariant,
  systemAccent,
  installedThemeId,
  installedThemeControlsColor,
  clearInstalledTheme,
  resetThemeToDefaults,
} = useTheme();
const themePackageActive = computed(() =>
  Boolean(installedThemeId.value && installedThemeControlsColor.value),
);
const showColorPicker = ref(false);
const pocketMode = useStorage('micyou_pocket_mode', false);
const closeBehavior = useStorage<'ask' | 'hide' | 'exit' | null>(
  'micyou_remember_close_action',
  null,
);
const startMinimized = useStorage<boolean>('micyou_start_minimized', false);
const floatingWindowEnabled = useStorage<boolean>('micyou_floating_window_enabled', false);
const notificationsEnabled = useStorage<boolean>('micyou_notifications', true);
const autoStream = useStorage<boolean>('micyou_auto_stream', false);
const autostartEnabled = ref(false);

const toggleFloatingWindow = async () => {
  const newVal = !floatingWindowEnabled.value;
  floatingWindowEnabled.value = newVal;
  try {
    if (newVal) {
      await invoke('show_floating_window');
    } else {
      await invoke('hide_floating_window');
    }
  } catch (e) {
    console.error('toggle_floating_window failed:', e);
  }
};

const applyCustomColor = (color: { h: number; s: number; l: number }) => {
  customH.value = color.h;
  customS.value = color.s;
  customL.value = color.l;
  themeColor.value = 'theme-custom';
  themeMode.value = 'custom';
};

const deactivateInstalledTheme = async () => {
  const themeId = installedThemeId.value;
  if (!themeId) return;
  clearInstalledTheme();
  try {
    await invoke('remove_installed_theme', { themeId });
  } catch (error) {
    console.warn('Failed to remove installed theme:', error);
  }
};

// --- Run mode (GUI / CLI / TUI) ---
interface ModeStatus {
  mode: 'gui' | 'cli' | 'tui' | 'none';
  pid: number | null;
  running: boolean;
}

const modeStatus = ref<ModeStatus>({ mode: 'none', pid: null, running: false });

const modeLabel = computed(() => {
  if (modeStatus.value.mode === 'cli' && modeStatus.value.running) {
    return t('settings.runMode.cliRunningShort');
  }
  if (modeStatus.value.mode === 'tui' && modeStatus.value.running) {
    return t('settings.runMode.tuiRunningShort');
  }
  return t('settings.runMode.guiCurrent');
});

async function refreshModeStatus() {
  try {
    modeStatus.value = await invoke<ModeStatus>('get_mode_status');
  } catch (e) {
    console.error('get_mode_status failed:', e);
  }
}

async function switchToCli() {
  const ok = confirm(t('settings.runMode.confirmSwitch'));
  if (!ok) return;
  try {
    await invoke('switch_to_cli');
    await invoke('exit_app');
  } catch (e) {
    console.error('switch_to_cli failed:', e);
    alert(`${t('settings.runMode.switchFailed')}: ${e}`);
    refreshModeStatus();
  }
}

async function switchToTui() {
  const ok = confirm(t('settings.runMode.confirmSwitchTui'));
  if (!ok) return;
  try {
    await invoke('switch_to_tui');
    await invoke('exit_app');
  } catch (e) {
    console.error('switch_to_tui failed:', e);
    alert(`${t('settings.runMode.switchFailed')}: ${e}`);
    refreshModeStatus();
  }
}

interface SettingsSection {
  id: string;
  /** 静态名称（插件面板等非 i18n 来源）；base 分区改用 nameKey 以支持热切换语言 */
  name?: string;
  nameKey?: string;
  icon: typeof SettingsIcon;
  panelIcon?: string;
  pluginId?: string;
  panelId?: string;
  divider?: boolean;
}

const baseSections: SettingsSection[] = [
  { id: 'general', nameKey: 'settings.categories.general', icon: SettingsIcon },
  { id: 'appearance', nameKey: 'settings.categories.appearance', icon: Palette },
  { id: 'audio', nameKey: 'settings.categories.audio', icon: Mic },
  { id: 'equalizer', nameKey: 'settings.equalizer.title', icon: SlidersHorizontal },
  { id: 'plugins', nameKey: 'settings.categories.plugins', icon: Puzzle },
  { id: 'about', nameKey: 'settings.categories.about', icon: Info },
];

// 插件面板：每个声明 ui.panels 的插件在侧边栏拥有专属页面
const pluginsState = usePlugins();
const panelIcons = ref<Record<string, string>>({});

async function loadPanelIcons() {
  const icons: Record<string, string> = {};
  for (const plugin of pluginsState.plugins.value) {
    if (!plugin.enabled || !plugin.ui?.panels?.length) continue;
    try {
      const got = await invoke<Record<string, string>>('get_plugin_panel_icons', {
        id: plugin.id,
      });
      for (const [pid, icon] of Object.entries(got)) {
        icons[`${plugin.id}:${pid}`] = icon;
      }
    } catch {
      /* ignore */
    }
  }
  panelIcons.value = icons;
}

const panelSections = computed(() => {
  const out: SettingsSection[] = [];
  for (const plugin of pluginsState.plugins.value) {
    if (!plugin.enabled) continue; // 禁用插件的页面不显示
    for (const panel of plugin.ui?.panels ?? []) {
      if (panel.sidebar === false) continue; // 仅窗口页面由插件自主开窗
      out.push({
        id: `panel:${plugin.id}:${panel.id}`,
        name: `${plugin.name} · ${panel.label}`,
        icon: LayoutPanelTop,
        panelIcon: panelIcons.value[`${plugin.id}:${panel.id}`],
        pluginId: plugin.id,
        panelId: panel.id,
      });
    }
  }
  return out;
});

const sections = computed(() => {
  const out: SettingsSection[] = [];
  for (const s of baseSections) {
    out.push(s);
  }
  // 插件专属页面：放在「关于」之下，前面加一条分隔线
  if (panelSections.value.length > 0) {
    out.push({ id: '__divider__', name: '', icon: SettingsIcon, divider: true });
    out.push(...panelSections.value);
  }
  return out;
});

const contentRef = ref<HTMLElement | null>(null);
const currentSection = ref('general');
const currentSectionName = computed(() => {
  const s = sections.value.find((x) => x.id === currentSection.value);
  if (!s) return '';
  return s.nameKey ? t(s.nameKey) : (s.name ?? '');
});

// ── 插件面板（sandbox iframe + postMessage bridge）─────────────────────
const panelHtml = ref('');
const panelLoading = ref(false);
const panelError = ref<string | null>(null);
const panelFrame = ref<HTMLIFrameElement | null>(null);

function activePanel() {
  const section = sections.value.find((s) => s.id === currentSection.value);
  if (section && typeof section.pluginId === 'string' && typeof section.panelId === 'string') {
    return { pluginId: section.pluginId as string, panelId: section.panelId as string };
  }
  return null;
}

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

async function loadPanel(pluginId: string, panelId: string) {
  panelLoading.value = true;
  panelError.value = null;
  try {
    const html = await invoke<string>('get_plugin_panel', { pluginId, panelId });
    // 注入当前主题 CSS 变量，使插件页面与软件整体风格一致并自动跟随主题
    // 同时 hook console，把面板的 log/warn/error 转发为插件日志（利于开发者调试面板）
    const consoleHook = `<script>
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
    panelHtml.value = `<style>:root{${collectThemeVars()}}</style>${consoleHook}${html}`;
  } catch (e) {
    panelError.value = String(e);
    panelHtml.value = '';
  } finally {
    panelLoading.value = false;
  }
}

function onPanelMessage(e: MessageEvent) {
  const section = sections.value.find((s) => s.id === currentSection.value);
  if (!section || typeof section.pluginId !== 'string') return;
  // bridge 实例的 pluginId 在切换面板时可能过期，直接重建
  const bridge = usePluginPanelBridge(section.pluginId as string);
  bridge.handleMessage(e);
}

watch(currentSection, async () => {
  const panel = activePanel();
  if (panel) {
    await loadPanel(panel.pluginId, panel.panelId);
  } else {
    panelHtml.value = '';
  }
  contentRef.value?.scrollTo({ top: 0 });
});

// 主题切换时刷新插件面板（跟随主题）
watch(
  () => colorMode.value,
  async () => {
    if (currentSection.value.startsWith('panel:')) {
      const panel = activePanel();
      if (panel) await loadPanel(panel.pluginId, panel.panelId);
    }
  },
);

watch(currentSection, () => {
  contentRef.value?.scrollTo({ top: 0 });
  if (isMounted) handleMonitoringState();
});

let stored = localStorage.getItem('micyou_language') || 'system';
if (stored === 'English') stored = 'en';
if (stored === '简体中文') stored = 'zh';

const currentLanguage = ref(stored);
function saveUiPrefs() {
  const effective =
    currentLanguage.value === 'system'
      ? navigator.language.toLowerCase().startsWith('zh')
        ? 'zh'
        : 'en'
      : currentLanguage.value;
  void invoke('save_ui_prefs', {
    language: effective,
    themeColor: themeMode.value === 'system' ? 'theme-system' : themeColor.value,
  }).catch((e) => console.error('save_ui_prefs failed:', e));
}
watch(currentLanguage, (newLang) => {
  localStorage.setItem('micyou_language', newLang);
  if (newLang === 'system') {
    locale.value = navigator.language.toLowerCase().startsWith('zh') ? 'zh' : 'en';
  } else {
    locale.value = newLang;
  }
  // Share the language with the CLI via ui.json
  saveUiPrefs();
  // 语言变化时刷新插件面板（面板按宿主 locale 重新渲染）
  if (currentSection.value.startsWith('panel:')) {
    const panel = activePanel();
    if (panel) void loadPanel(panel.pluginId, panel.panelId);
  }
});

// Reactive Settings State
// Single source of truth for audio / DSP default values. Both the `settings`
// reactive init and "restore defaults" reference this — add a field here and
// both stay in sync, no second copy to keep aligned by hand.
const DEFAULT_AUDIO_SETTINGS = () => ({
  audioDevice: 'auto',
  gain: 0,
  aecEnabled: false,
  nsEnabled: false,
  nsType: 'PureVox',
  nsIntensity: 100,
  dereverbEnabled: false,
  dereverbLevel: 50,
  agcEnabled: false,
  agcTarget: 16000,
  agcAttack: 50,
  agcDecay: 50,
  vadEnabled: false,
  vadThreshold: -40,
  outputBufferMs: 300,
  processingChain: ['AEC', 'NoiseReduction', 'Dereverb', 'Equalizer', 'Amplifier', 'AGC', 'VAD'],
  equalizer: {
    enabled: false,
    preAmp: 0,
    gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  },
});

const settings = reactive(DEFAULT_AUDIO_SETTINGS());

const audioDevices = ref<string[]>([]);
const hasVBCable = computed(() =>
  audioDevices.value.some((d) => d.toLowerCase().includes('cable')),
);
const hasBlackHole = computed(() =>
  audioDevices.value.some((d) => d.toLowerCase().includes('blackhole')),
);
const isMacOS =
  typeof navigator !== 'undefined' &&
  /Mac/.test(navigator.platform || navigator.userAgent) &&
  !/iPhone|iPad|iPod/.test(navigator.userAgent);
const isLinux =
  typeof navigator !== 'undefined' &&
  /Linux/.test(navigator.platform || navigator.userAgent) &&
  !/Android/.test(navigator.userAgent);
const isWindows = !isMacOS && !isLinux;

const isVirtualDeviceSelected = computed(() => {
  if (!settings.audioDevice || settings.audioDevice === 'auto') {
    if (isWindows) return hasVBCable.value;
    if (isMacOS) return hasBlackHole.value;
    if (isLinux) return pipewireStatus.value.available;
    return false;
  }
  const dev = settings.audioDevice.toLowerCase();
  return (
    dev.includes('cable input') ||
    dev.includes('cable') ||
    dev.includes('blackhole') ||
    dev.includes('micyou')
  );
});

const isExplicitPhysicalDevice = computed(() => {
  if (!settings.audioDevice || settings.audioDevice === 'auto') return false;
  return !isVirtualDeviceSelected.value;
});

const isAutoFallbackToPhysical = computed(() => {
  if (settings.audioDevice && settings.audioDevice !== 'auto') return false;
  return !isVirtualDeviceSelected.value;
});

const isAecSupported = !isMacOS;
const aecRuntimeAvailable = ref(true);
const pipewireStatus = ref<{ available: boolean; setup: boolean; device_exists: boolean }>({
  available: false,
  setup: false,
  device_exists: false,
});
const vbcableInstalling = ref(false);
const vbcableInstallProgress = ref('');
let unlistenVbcableProgress: UnlistenFn | null = null;
let unlistenAecStatus: UnlistenFn | null = null;

interface BlackHoleStatus {
  installed: boolean;
  switch_audio_source: boolean;
  device_name: string | null;
}
const blackholeStatus = ref<BlackHoleStatus>({
  installed: false,
  switch_audio_source: false,
  device_name: null,
});
const blackholeChecking = ref(false);
const audioLevel = ref(0);
let unlistenLevel: UnlistenFn | null = null;
let unlistenSpectrum: UnlistenFn | null = null;
let monitoringGeneration = 0;

const spectrumCanvas = ref<HTMLCanvasElement | null>(null);
let animationFrameId: number | null = null;

// Real spectrum data from backend
const rawSpectrum = ref<number[]>(new Array(64).fill(0));
const processedSpectrum = ref<number[]>(new Array(64).fill(0));

interface SpectrumPayload {
  raw: number[];
  processed: number[];
}

function canDrawSpectrum() {
  return props.isOpen && currentSection.value === 'audio';
}

async function setBackendSpectrumStreaming(enabled: boolean) {
  await invoke('set_spectrum_streaming', { enabled });
}

function stopSpectrumAnimation() {
  if (animationFrameId !== null) {
    cancelAnimationFrame(animationFrameId);
    animationFrameId = null;
  }
}

function scheduleSpectrumFrame() {
  if (!canDrawSpectrum() || animationFrameId !== null) return;
  animationFrameId = requestAnimationFrame(drawSpectrum);
}

function startSpectrumAnimation() {
  if (!canDrawSpectrum()) {
    stopSpectrumAnimation();
    return;
  }
  scheduleSpectrumFrame();
}

function drawSpectrum() {
  animationFrameId = null;
  if (!canDrawSpectrum()) return;
  if (!spectrumCanvas.value) {
    scheduleSpectrumFrame();
    return;
  }

  const canvas = spectrumCanvas.value;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  const targetWidth = Math.max(1, Math.round(rect.width * dpr));
  const targetHeight = Math.max(1, Math.round(rect.height * dpr));
  if (canvas.width !== targetWidth || canvas.height !== targetHeight) {
    canvas.width = targetWidth;
    canvas.height = targetHeight;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
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
      ctx.roundRect(i * barWidth + gap / 2, height - rawH, effectiveBarWidth, rawH, 2);
      ctx.fill();
    }

    if (procH > 0.5) {
      ctx.fillStyle = primaryColor;
      ctx.beginPath();
      ctx.roundRect(i * barWidth + gap / 2, height - procH, effectiveBarWidth, procH, 2);
      ctx.fill();
    }
  }

  scheduleSpectrumFrame();
}

const showContributors = ref(false);
const showSponsors = ref(false);
const showLicenses = ref(false);
const showAudioChain = ref(false);
const showCustomCssDialog = ref(false);
const showThemeCatalogDialog = ref(false);
const appVersion = ref('0.1.0');

const updateProcessingChain = (newChain: string[]) => {
  settings.processingChain = newChain;
};

const displayChain = computed(() =>
  isAecSupported ? settings.processingChain : settings.processingChain.filter((i) => i !== 'AEC'),
);

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
        alert(t('dialogs.update.failed', { error: 'HTTP ' + res.status }));
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
    const result = await invoke<{ success: boolean; error_type?: string; message?: string }>(
      'install_vbcable',
    );
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
    pipewireStatus.value = await invoke<{
      available: boolean;
      setup: boolean;
      device_exists: boolean;
    }>('check_pipewire');
  } catch (e) {
    console.error('Failed to check PipeWire status:', e);
  }
}

// Lifecycle
let isMounted = false;

onMounted(async () => {
  isMounted = true;
  handleOpenState(props.isOpen);
  refreshModeStatus();
  saveUiPrefs();
  // Refresh from the shared settings.json so CLI-side changes show up
  try {
    await loadSettings();
  } catch (e) {
    console.error('Failed to reload settings on open:', e);
  }
  try {
    appVersion.value = await invoke('get_app_version');
  } catch (e) {
    console.error('Failed to get version', e);
  }
  try {
    autostartEnabled.value = await isAutostartEnabled();
  } catch (e) {
    console.error('Failed to read autostart state:', e);
  }
  unlistenVbcableProgress = await listen<string>('vbcable-install-progress', (event) => {
    vbcableInstallProgress.value = event.payload;
  });
  unlistenAecStatus = await listen<{
    available: boolean;
    enabled: boolean;
    reason?: string | null;
  }>('aec-status-changed', (event) => {
    aecRuntimeAvailable.value = event.payload.available;
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
  isMounted = false;
  stopAudioMonitoring();
  if (unlistenVbcableProgress) unlistenVbcableProgress();
  if (unlistenAecStatus) unlistenAecStatus();
});

const fetchDevices = async () => {
  try {
    audioDevices.value = await invoke<string[]>('get_audio_devices');
  } catch (e) {
    console.error('Failed to fetch audio devices', e);
  }
  checkBlackHoleStatus();
  checkPipeWireStatus();
};

function loadLocalAudioSettings() {
  const saved = localStorage.getItem('micyou_audio_settings');
  if (!saved) return;

  try {
    Object.assign(settings, JSON.parse(saved));
  } catch (error) {
    console.error('Failed to parse settings', error);
  }
}

const loadSettings = async () => {
  // Prefer the shared settings.json (written by update_audio_settings, also used
  // by the CLI) so GUI and CLI stay in sync; localStorage stays as a fallback.
  try {
    const backend = await invoke<Record<string, unknown>>('get_audio_settings');
    if (Object.keys(backend).length > 0) {
      Object.assign(settings, backend);
      localStorage.setItem('micyou_audio_settings', JSON.stringify(settings));
    } else {
      loadLocalAudioSettings();
    }
  } catch (error) {
    console.error('get_audio_settings failed, using localStorage', error);
    loadLocalAudioSettings();
  }

  if (!['PureVox', 'RNNoise', 'Speexdsp'].includes(settings.nsType)) {
    settings.nsType = 'PureVox';
  }

  // AEC 在 Linux/Windows 可用，在 macOS 禁用；可用平台强制置顶
  const savedChain = settings.processingChain ?? [];
  settings.processingChain = isAecSupported
    ? ['AEC', ...savedChain.filter((i) => i !== 'AEC')]
    : savedChain.filter((i) => i !== 'AEC');
  if (!isAecSupported) settings.aecEnabled = false;

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
        aecEnabled: isAecSupported ? settings.aecEnabled : false,
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
        outputBufferMs: settings.outputBufferMs,
        processingChain: isAecSupported
          ? settings.processingChain
          : settings.processingChain.filter((i) => i !== 'AEC'),
        equalizer: settings.equalizer,
      },
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

// Autosave: any change to `settings` persists + syncs to backend. Suppressed
// during "restore defaults" so a single batched mutation produces exactly one
// save (see doRestoreDefaultSettings).
const suppressAutosave = ref(false);
watch(
  settings,
  () => {
    if (suppressAutosave.value) return;
    saveSettings();
  },
  { deep: true },
);

// In-app confirm + result dialog state for restore actions (mirrors the
// IP switch confirm dialog style in App.vue — no native confirm()/alert()).
const restoreConfirmTarget = ref<'settings' | 'theme' | null>(null);
const restoreResult = ref<{ title: string; message: string } | null>(null);

function restoreDefaultSettings() {
  restoreConfirmTarget.value = 'settings';
}

function restoreDefaultTheme() {
  restoreConfirmTarget.value = 'theme';
}

async function confirmRestore() {
  const target = restoreConfirmTarget.value;
  restoreConfirmTarget.value = null;
  if (target === 'settings') {
    await doRestoreDefaultSettings();
  } else if (target === 'theme') {
    await doRestoreDefaultTheme();
  }
}

async function doRestoreDefaultSettings() {
  let autostartFailed = false;
  try {
    // Reset UI preferences (persisted via useStorage -> localStorage)
    closeBehavior.value = null;
    startMinimized.value = false;
    notificationsEnabled.value = true;
    autoStream.value = false;
    pocketMode.value = false;

    // Batch the settings mutation under suppressAutosave; release AFTER the
    // deep watcher's batched flush (nextTick) so it runs as a no-op, yielding
    // exactly one real save below. Releasing in `finally` before nextTick
    // would let the watcher fire a second save (race fixed per review).
    suppressAutosave.value = true;
    const defaults = DEFAULT_AUDIO_SETTINGS();
    // Full reset: drop keys no longer in defaults (avoid stale residue from
    // renamed/removed fields), then overwrite. Init and reset share one source.
    Object.keys(settings).forEach((k) => {
      if (!(k in defaults)) delete (settings as Record<string, unknown>)[k];
    });
    Object.assign(settings, defaults);
    // Platform normalization (AEC pinning is runtime, not a "default")
    settings.processingChain = isAecSupported
      ? ['AEC', ...settings.processingChain.filter((i) => i !== 'AEC')]
      : settings.processingChain.filter((i) => i !== 'AEC');
    if (!isAecSupported) settings.aecEnabled = false;
    await nextTick(); // watcher flushes while still suppressed → no-op
    suppressAutosave.value = false;
    saveSettings(); // exactly one real persist + backend sync

    // Disable OS-level autostart if currently enabled; surface failure
    // (previously swallowed → UI falsely showed full success)
    if (autostartEnabled.value) {
      try {
        await disableAutostart();
        autostartEnabled.value = false;
      } catch (e) {
        autostartFailed = true;
        console.error('Failed to disable autostart on reset:', e);
      }
    }

    restoreResult.value = {
      title: t('settings.restoreDefaults.title'),
      message: autostartFailed
        ? t('settings.restoreDefaults.autostartFailed')
        : t('settings.restoreDefaults.success'),
    };
  } catch (e) {
    suppressAutosave.value = false; // release on failure too (avoid leak)
    console.error('Failed to restore default settings:', e);
    restoreResult.value = {
      title: t('settings.restoreDefaults.title'),
      message: t('settings.restoreDefaults.failed'),
    };
  }
}

async function doRestoreDefaultTheme() {
  try {
    // colorMode lives in SettingsDialog (useColorMode); theme fields live in
    // useTheme. resetThemeToDefaults reuses DEFAULT_THEME so no second copy.
    colorMode.value = DEFAULT_THEME.colorMode as typeof colorMode.value;
    // Await backend removal of an installed theme package; resetThemeToDefaults
    // returns true if that removal failed (frontend already cleared) so we can
    // surface a partial-success instead of a silent inconsistency.
    const themeRemoveFailed = await resetThemeToDefaults();
    // Sync ui.json (themeColor preset) — saveUiPrefs reads the freshly reset
    // themeMode/themeColor, so call it after resetThemeToDefaults.
    saveUiPrefs();

    restoreResult.value = {
      title: t('settings.restoreTheme.title'),
      message: themeRemoveFailed
        ? t('settings.restoreTheme.partialSuccess')
        : t('settings.restoreTheme.success'),
    };
  } catch (e) {
    console.error('Failed to restore default theme:', e);
    restoreResult.value = {
      title: t('settings.restoreTheme.title'),
      message: t('settings.restoreTheme.failed'),
    };
  }
}

function resetMonitoringData() {
  audioLevel.value = 0;
  rawSpectrum.value = new Array(64).fill(0);
  processedSpectrum.value = new Array(64).fill(0);
}

function stopAudioMonitoring() {
  monitoringGeneration++;
  void setBackendSpectrumStreaming(false).catch((e) => {
    console.error('Failed to stop spectrum streaming:', e);
  });
  stopSpectrumAnimation();
  if (unlistenLevel) {
    unlistenLevel();
    unlistenLevel = null;
  }
  if (unlistenSpectrum) {
    unlistenSpectrum();
    unlistenSpectrum = null;
  }
  resetMonitoringData();
}

function isMonitoringCurrent(token: number) {
  return token === monitoringGeneration && isMounted && props.isOpen;
}

async function startAudioMonitoring() {
  stopAudioMonitoring();
  if (!canDrawSpectrum()) return;
  const token = monitoringGeneration;
  let levelListener: UnlistenFn | null = null;
  let spectrumListener: UnlistenFn | null = null;

  try {
    await fetchDevices();
    if (!isMonitoringCurrent(token)) return;

    await loadSettings();
    if (!isMonitoringCurrent(token)) return;

    // Sync existing settings to backend on open
    await syncSettingsToBackend();
    if (!isMonitoringCurrent(token)) return;

    levelListener = await listen<number>('audio-level', (event) => {
      if (isMonitoringCurrent(token)) audioLevel.value = event.payload;
    });
    if (!isMonitoringCurrent(token)) return;

    spectrumListener = await listen<SpectrumPayload>('audio-spectrum', (event) => {
      if (isMonitoringCurrent(token)) {
        rawSpectrum.value = event.payload.raw;
        processedSpectrum.value = event.payload.processed;
      }
    });
    if (!isMonitoringCurrent(token)) return;

    await setBackendSpectrumStreaming(true);
    if (!isMonitoringCurrent(token) || currentSection.value !== 'audio') return;

    if (unlistenLevel) unlistenLevel();
    if (unlistenSpectrum) unlistenSpectrum();
    unlistenLevel = levelListener;
    unlistenSpectrum = spectrumListener;
    levelListener = null;
    spectrumListener = null;
    startSpectrumAnimation();
  } catch (e) {
    if (isMonitoringCurrent(token)) {
      console.error('Failed to start audio monitoring:', e);
      void setBackendSpectrumStreaming(false).catch((stopError) => {
        console.error('Failed to roll back spectrum streaming:', stopError);
      });
    }
  } finally {
    levelListener?.();
    spectrumListener?.();
    if (!isMonitoringCurrent(token)) {
      void setBackendSpectrumStreaming(canDrawSpectrum()).catch((e) => {
        console.error('Failed to reconcile spectrum streaming state:', e);
      });
    }
  }
}

function handleMonitoringState() {
  if (canDrawSpectrum()) {
    void startAudioMonitoring();
  } else {
    stopAudioMonitoring();
  }
}

function handleOpenState(_isOpen: boolean) {
  handleMonitoringState();
}

watch(
  () => props.isOpen,
  () => {
    if (isMounted) handleMonitoringState();
  },
);
</script>
