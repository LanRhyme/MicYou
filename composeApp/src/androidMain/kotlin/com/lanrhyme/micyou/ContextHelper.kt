package com.lanrhyme.micyou

import android.content.Context

/**
 * Android 应用上下文助手类。
 *
 * **安全性说明**：
 * 此类使用 `applicationContext` 而非 Activity Context，因此不会导致内存泄漏。
 * Application Context 的生命周期与应用进程相同，不会随 Activity 销毁而释放。
 *
 * **使用场景**：
 * - 获取应用资源
 * - 启动 Service
 * - 访问 SharedPreferences
 * - 获取系统服务
 *
 * **注意事项**：
 * - 不要用于显示 Dialog 或创建 View（需要 Activity Context）
 * - 不要用于注册 Activity 生命周期监听器
 */
object ContextHelper {
    private var applicationContext: Context? = null

    /**
     * 初始化上下文助手。
     * 应在 MainActivity.onCreate() 中调用。
     *
     * @param context 任意 Context（Activity 或 Application），内部会转换为 applicationContext
     */
    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * 获取应用上下文。
     *
     * @return Application Context，如果未初始化则返回 null
     */
    fun getContext(): Context? {
        return applicationContext
    }
}

