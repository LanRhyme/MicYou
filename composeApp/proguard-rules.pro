# MicYou Android release shrink rules
#
# 目标：
# 1) 保持宿主与外部插件(plugin.dex)之间的二进制兼容
# 2) 尽量避免过宽 keep，保留 R8 压缩收益

# 插件系统通过 DexClassLoader + manifest.mainClass 动态加载外部插件，
# 外部插件编译期依赖 plugin-api 的完整符号名与方法签名。
# 因此需要保留 plugin-api 对外 ABI（类名 + 成员签名）。
-keep interface com.lanrhyme.micyou.plugin.Plugin { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginContext { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginHost { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginUIProvider { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginSettingsProvider { *; }
-keep interface com.lanrhyme.micyou.plugin.AudioEffectPlugin { *; }
-keep interface com.lanrhyme.micyou.plugin.AudioEffectProvider { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginDataChannel { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginDataChannelProvider { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginLocalization { *; }
-keep interface com.lanrhyme.micyou.plugin.PluginLocalizationProvider { *; }

-keep class com.lanrhyme.micyou.plugin.PluginManifest { *; }
-keep class com.lanrhyme.micyou.plugin.PluginInfo { *; }
-keep class com.lanrhyme.micyou.plugin.AudioConfig { *; }
-keep class com.lanrhyme.micyou.plugin.ConnectionInfo { *; }
-keep class com.lanrhyme.micyou.plugin.DataChannelConfig { *; }
-keep class com.lanrhyme.micyou.plugin.PluginHost$PlatformInfo { *; }
-keep class com.lanrhyme.micyou.plugin.PluginDataChannel$ReceivedPacket { *; }

-keep enum com.lanrhyme.micyou.plugin.StreamState { *; }
-keep enum com.lanrhyme.micyou.plugin.ConnectionMode { *; }
-keep enum com.lanrhyme.micyou.plugin.NoiseReductionType { *; }
-keep enum com.lanrhyme.micyou.plugin.PluginPlatform { *; }
-keep enum com.lanrhyme.micyou.plugin.DataChannelMode { *; }
-keep enum com.lanrhyme.micyou.plugin.MobileUIMode { *; }

# 保留序列化/注解元数据（插件清单与协议对象使用 kotlinx.serialization）。
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 保留 kotlinx.serialization 生成的成员，避免序列化异常。
-keepclassmembers class * {
    *** Companion;
    *** $serializer;
}

# 可选：若第三方依赖在 release 下出现仅告警类缺失，再按需添加 dontwarn。
