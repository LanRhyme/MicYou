# MicYou Desktop ProGuard rules
# Used by compose desktop release build (proguardReleaseJars)
# Goal: maximize shrinking while preserving runtime correctness

# ============================================================
# 1. 插件系统 — 外部插件动态加载，保留完整 ABI
# ============================================================
-keep interface com.lanrhyme.micyou.plugin.** { *; }
-keep class com.lanrhyme.micyou.plugin.PluginManifest { *; }
-keep class com.lanrhyme.micyou.plugin.PluginInfo { *; }
-keep class com.lanrhyme.micyou.plugin.AudioConfig { *; }
-keep class com.lanrhyme.micyou.plugin.ConnectionInfo { *; }
-keep class com.lanrhyme.micyou.plugin.DataChannelConfig { *; }
-keep class com.lanrhyme.micyou.plugin.PluginHost$PlatformInfo { *; }
-keep class com.lanrhyme.micyou.plugin.PluginDataChannel$ReceivedPacket { *; }
-keep enum com.lanrhyme.micyou.plugin.** { *; }

# ============================================================
# 2. Kotlin 元数据 & 反射
# ============================================================
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

# kotlinx.serialization — 保留编译器生成的序列化器
-keepclassmembers class * {
    *** Companion;
    *** $serializer;
}
-keepnames class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# kotlinx.coroutines
-keepnames class kotlinx.coroutines.** { *; }

# ============================================================
# 3. Compose Multiplatform
#    编译器生成代码引用运行时类名，运行时类不能被移除
#    但不需要保留内部实现类的全部成员
# ============================================================
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }

# ============================================================
# 4. Ktor — 通过 service loader 和反射加载
#    只保留实际使用的引擎和插件
# ============================================================
# Service loader 入口
-keep class io.ktor.server.netty.internal.MainWithHookKt { *; }
-keep class io.ktor.server.netty.NettyApplicationEngine { *; }
-keep class io.ktor.server.engine.ApplicationEngineEnvironment { *; }
-keep class io.ktor.server.engine.ApplicationEngine { *; }

# Ktor 服务器核心（反射加载）
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.application.** { *; }
-keep class io.ktor.server.routing.** { *; }
-keep class io.ktor.server.response.** { *; }
-keep class io.ktor.server.plugins.cors.** { *; }
-keep class io.ktor.server.websocket.** { *; }

# Ktor 客户端（反射加载）
-keep class io.ktor.client.** { *; }

# Ktor 网络层
-keep class io.ktor.network.** { *; }
-keep class io.ktor.websocket.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.utils.io.** { *; }
-keep class io.ktor.serialization.** { *; }

# ============================================================
# 5. Netty — Ktor server 后端，通过 service loader 加载
#    保留 Ktor 实际使用的传输层
# ============================================================
# Channel 和 Bootstrap（Ktor 核心依赖）
-keep class io.netty.channel.** { *; }
-keep class io.netty.bootstrap.** { *; }
# NIO 传输（Windows/Linux 默认）
-keep class io.netty.channel.nio.** { *; }
-keep class io.netty.channel.socket.nio.** { *; }
# 编解码器
-keep class io.netty.handler.codec.** { *; }
# SSL/TLS
-keep class io.netty.handler.ssl.** { *; }
# Buffer（核心）
-keep class io.netty.buffer.** { *; }
# 调度器
-keep class io.netty.util.concurrent.** { *; }
-keep class io.netty.channel.DefaultEventLoopGroup { *; }
# 日志
-keep class io.netty.util.internal.logging.** { *; }

# ============================================================
# 6. ONNX Runtime — JNI native library
# ============================================================
-keep class ai.onnxruntime.** { *; }

# ============================================================
# 7. JNA — native 方法调用（Compose/SystemTray 可能间接使用）
# ============================================================
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# ============================================================
# 8. RNNoise — JNI native library
# ============================================================
-keep class de.maxhenkel.rnnoise4j.** { *; }

# ============================================================
# 9. BlueCove 蓝牙
# ============================================================
-keep class javax.bluetooth.** { *; }
-keep class com.intel.bluetooth.** { *; }

# ============================================================
# 10. 系统托盘 (ComposeNativeTray)
# ============================================================
-keep class com.github.nicholasgasior.composenativetray.** { *; }

# ============================================================
# 11. ZXing (QR code)
# ============================================================
-keep class com.google.zxing.** { *; }

# ============================================================
# 12. mDNS (JmDNS)
# ============================================================
-keep class javax.jmdns.** { *; }

# ============================================================
# 13. Haze (glass blur effect)
# ============================================================
-keep class dev.chrisbanes.haze.** { *; }

# ============================================================
# 14. FileKit
# ============================================================
-keep class io.github.vinceglb.filekit.** { *; }

# ============================================================
# 15. MaterialKolor
# ============================================================
-keep class com.materialkolor.** { *; }

# ============================================================
# 16. 入口点
# ============================================================
-keep class com.lanrhyme.micyou.MainKt { *; }
-keep class com.lanrhyme.micyou.** extends javax.swing.** { *; }

# ============================================================
# 17. 通用规则
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
# 18. 优化选项
# ============================================================
# 允许修改访问修饰符以优化性能
-allowaccessmodification
# 将非入口点类的类名缩短（即使不混淆整个项目）
-repackageclasses ''
# 移除日志调用
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void checkParameterIsNotNull(...);
}

# ============================================================
# 19. 警告抑制 — 只抑制已知无害的警告
# ============================================================
-dontwarn io.netty.**
-dontwarn com.sun.jna.**
-dontwarn javax.bluetooth.**
-dontwarn com.intel.bluetooth.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
# Kotlin 2.3.x 新增的原子类型（运行时由 JDK 提供）
-dontwarn kotlin.concurrent.atomics.**
# Compose/Ktor 引用的增强空安全注解
-dontwarn kotlin.jvm.internal.EnhancedNullability
# RNNoise/NativeUtils 使用的 javax 注解
-dontwarn javax.annotation.**
# Kotlin reflection 内部类型模型
-dontwarn kotlin.reflect.jvm.internal.impl.types.model.**
