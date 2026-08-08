# MicYou 插件系统

MicYou 插件系统允许第三方为桌面端与（未来）安卓端扩展能力：实时 DSP 节点、工具逻辑、UI 面板与跨端同步桥

| 文档 | 说明 |
| --- | --- |
| [总览](overview.md) | 架构、双运行时、DSP 集成与跨端同步模型 |
| [开发指南](development-guide.md) | 编写 Native / WASM 插件、Manifest、Host API、实时安全规范 |
| [用户指南](user-guide.md) | 安装卸载、GUI 管理、配置与跨端同步使用 |
| [API 参考](api-reference.md) | Host API、Plugin API、消息协议、错误码与权限清单 |
| [架构与扩展](architecture-extensibility.md) | 安卓端扩展计划、版本兼容、安全模型 |

## 快速导航

- 想写插件：读 [开发指南](development-guide.md)
- 想装插件：读 [用户指南](user-guide.md)
- 想了解协议与权限：读 [API 参考](api-reference.md)
- 想知道安卓端怎么规划：读 [架构与扩展](architecture-extensibility.md)

## 示例插件

| 示例 | 运行时 | 类型 | 位置 |
| --- | --- | --- | --- |
| native-soundpad | Native (cdylib) | 音效板：按钮面板 + 专属设置页 + 快捷键 + 音频播放 + 插件自主开窗 | `plugins/examples/native-soundpad/` |
| wasm-voicechanger | WASM | 变声器（实时 DSP in wasmi）：专属设置页 + 配置热更新 | `plugins/examples/wasm-voicechanger/` |
| wasm-audioinspector | WASM | 音频状态监视器（interval 采样 + 面板实时显示） | `plugins/examples/wasm-audioinspector/` |

## Host API 能力总表

| 类别 | API | 能力 |
| --- | --- | --- |
| 日志 | `log` | 无 |
| 配置 | `get_config` / `set_config` | config.read / config.write |
| 事件 | `emit_event` | event.emit |
| 消息 | `send_message` | message.send |
| 音频 | `audio_state` / `play_sound` | audio.state / audio.play |
| 设备 | `connected_devices` | device.list |
| 文件 | `fs_read` / `fs_write`（插件目录沙箱） | fs.read / fs.write |
| 定时器 | `set_timeout` / `clear_timeout` / `set_interval` / `clear_interval` | 无 |
| 网络 | `http_request`（异步回调） | network.io |
| 浏览器 | `open_url` | open.url |
| 通知 | `notify` | 无 |
| 剪贴板 | `clipboard_read` / `clipboard_write` | clipboard.read / clipboard.write |
| 环境 | `locale` / `host_info` / `plugin_dir` | 无 |
| UI | `open_window` / 专属设置页（iframe 桥） | 无 |
| 快捷键 | `register_hotkey` | 无（仅 X11） |
| 宿主事件 | 设备连接/断开 → `handle_event` | 无 |
| 依赖联动 | `dependencies`（前置插件声明） | 无 |

## 安装与更新

- 导入 zip 前展示**权限预览**（能力清单/作者/许可），确认后才安装
- manifest 声明 `updateUrl` 后可**检查更新**（semver 对比）并一键更新
- manifest `configSchema` 声明字段后宿主**自动生成配置表单**（滑杆/开关/下拉）

## 开发工具

```bash
micyou plugin create dev.micyou.myplugin --kind utility --capabilities config.read   # wasm 骨架默认 Rust（自动编译）
micyou plugin create dev.micyou.myplugin --lang wat                                 # 高级场景才手写 WAT
micyou plugin create dev.micyou.mynative --runtime native --kind dsp
micyou plugin validate ./myplugin
micyou plugin install ./myplugin            # 一键部署到应用插件目录
micyou plugin dev ./myplugin                # 监听变更自动重装（开发循环）
micyou plugin package ./myplugin -o out.zip
micyou plugin bump ./myplugin               # 版本 patch +1
micyou plugin list                          # 列出已安装插件（id/版本/运行时/状态）

应用内：设置-插件 → 插件市场（浏览 MicYou-Plugins 仓库：封面图/运行时/能力/平台/架构，
安装前展示能力确认，一键安装；插件 zip 由各插件仓库 CI 打包发布 GitHub Release，
市场仓库只维护元数据 index.json（CI 自动生成并部署到 GitHub Pages，
不直接提交二进制）
```

## 代码结构

```text
crates/micyou-plugin/            # 插件框架（桌面 + 未来安卓共用）
├── src/manifest.rs              # 统一清单模型与校验
├── src/plugin.rs                # 统一插件抽象（双运行时）
├── src/native.rs                # Native 加载器（libloading + C ABI）
├── src/wasm.rs                  # WASM 运行时（wasmi 沙箱 + 燃料计量）
├── src/abi.rs                   # C ABI host 回调桥
├── src/dsp.rs                   # DSP 节点注册表与链桥
├── src/bus.rs                   # 消息总线（发布订阅 / RPC）
├── src/sync.rs                  # 跨端线协议编解码
├── include/micyou_plugin_abi.h  # Native 插件 ABI 头文件（v1）
├── fixtures/                    # 测试夹具（native cdylib + wasm）
└── tests/                       # 集成测试
src-tauri/src/plugins.rs         # 桌面宿主接线（PluginHost）
src-tauri/src/commands/plugins.rs# 前端管理命令
src/features/plugins/            # 前端管理界面（Vue）
plugins/examples/                # 示例插件
docs/plugins/                    # 本文档
```

## 状态

- [x] 统一接口抽象 + PluginManager
- [x] Native 插件加载（C ABI v1）
- [x] WASM 插件运行时（wasmi）
- [x] DSP 链路集成（合成链节点 `Plugins`）
- [x] 跨端消息同步协议（protobuf `PluginMessage`）
- [x] 前端插件管理界面
- [x] 音效板面板（ui.route=buttons + 虚拟麦克风混音播放）
- [x] 插件专属设置页（ui.panels + postMessage 桥，设置侧边栏动态渲染）
- [x] 全局快捷键（register_hotkey，热键消息投递）
- [x] 示例插件与文档（音效板 / 降噪引擎）
- [ ] 安卓端运行时（协议已就绪，见 [架构与扩展](architecture-extensibility.md)）
