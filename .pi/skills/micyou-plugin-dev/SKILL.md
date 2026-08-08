---
name: micyou-plugin-dev
description: 开发、调试、打包 MicYou 桌面端插件（Native dylib + WASM 双运行时）时使用。覆盖从 create 骨架、开发循环、manifest 校验、zip 打包、市场发布到常见陷阱排查的完整流程
---

# MicYou 插件开发

MicYou 插件系统：双运行时（Native cdylib + WASM wasmi 沙箱），宿主在桌面应用（Tauri + Rust）

## 架构速览

- 宿主核心：`tauri-app/crates/micyou-plugin/`（manifest / host / native / wasm / manager / bus / dsp）
- 宿主接线：`tauri-app/src-tauri/src/plugins.rs`（PluginHost + PluginHostApi + dispatcher + HotkeyService + WindowService）、`src-tauri/src/commands/plugins.rs`（Tauri 命令）
- ABI 定义：`crates/micyou-plugin/include/micyou_plugin_abi.h`（C 头）与 `src/abi.rs`（Rust 镜像）
- 文档：`docs/plugins/`（overview / development-guide / api-reference / user-guide / architecture-extensibility / README）
- 示例：`plugins/examples/`（native-soundpad、wasm-voicechanger、wasm-audioinspector）
- 市场：`MicYou-Dev/MicYou-Plugins` 仓库（llqqnt 模式：只维护 plugin.json + 封面元数据，zip 由插件仓库 CI 发布 release）

## 开发工具（micyou-cli plugin 子命令）

```bash
# 从 tauri-app/ 运行
cargo run -p micyou-cli -- plugin create dev.micyou.myplugin --runtime wasm --kind utility --capabilities config.read,config.write
cargo run -p micyou-cli -- plugin validate <dir>          # manifest + 入口产物校验
cargo run -p micyou-cli -- plugin package <dir> -o out.zip # 打包（zip 根含 plugin.json，可导入）
cargo run -p micyou-cli -- plugin install <dir>           # 一键部署到 ~/.config/micyou/plugins/<id>/
cargo run -p micyou-cli -- plugin dev <dir>               # 监听变更自动重装（开发循环）
cargo run -p micyou-cli -- plugin bump <dir> [版本]        # manifest 版本递增（默认 patch +1）
```

开发循环：`create` → 改代码 → `dev <dir>`（保存即重装）→ 重启应用在设置-插件页启用测试

## Manifest 要点

```json
{
  "id": "dev.micyou.myplugin",        // 反向域名，必须含点
  "runtime": "wasm",                  // wasm 优先；native 需 arches
  "entry": "main.wasm",               // 构建产物
  "kind": "utility",                  // utility | dsp（处理链节点）| ui
  "capabilities": ["config.read", "config.write"],
  "ui": { "label": "...", "panels": [{ "id": "p", "label": "...", "entry": "panel.html", "sidebar": true }] },
  "dsp": { "insertAfter": "AEC" },    // kind=dsp 时
  "config": { "pitch": 1.3 },         // 默认配置
  "configSchema": { "fields": [...] } // 自动表单（number 滑杆 / boolean 开关 / string / select）
}
```

## Host API（24+ 项）

log / get_config / set_config（热更新）/ plugin_dir / play_sound（虚拟麦克风输出）/ audio_state /
connected_devices / register_hotkey（仅 X11，Wayland 会报错）/ open_window / fs_read / fs_write（沙箱到插件目录）/
set_timeout / clear_timeout / http_request（异步）/ set_interval / clear_interval / open_url / notify /
locale（宿主 UI 语言）/ host_info / clipboard_read / clipboard_write / set_panel_icon

能力要求：http_request 需 network.io，open_url 需 open.url，剪贴板需 clipboard.read/write，fs 需 fs.read/write；
定时器 / notify / locale / host_info / open_window / set_panel_icon 无需能力

## 运行时差异（务必区分）

| | Native (cdylib) | WASM (wasmi) |
| --- | --- | --- |
| handle_message | 收 (source, topic, payload) | 只收 (payload bytes)，**无 topic**，payload 须自描述 |
| 能力 | 全权限进程内代码 | 沙箱 + 燃料计量（100k/调用） |
| 适用 | 实时 DSP、系统集成 | 逻辑 / 面板 / 定时 / 网络 / 文件 |
| 语言 | Rust + C ABI | Rust（wasm32-unknown-unknown，推荐，create 默认生成并自动编译）；WAT 仅高级场景（--lang wat） |

宿主触发插件动作时：`plugin_trigger` 命令在 payload 为空时自动注入 `{"action":"<action>"}`，
WASM 插件用字符串包含判断即可

## 已知陷阱（开发必读）

1. **WAT 栈必须干净**：wasmi 1.1 对全部代码路径做类型校验（含 unreachable），block 声明 result 就必须留值；
   `call $emit_event` 等返回 i32 的调用要 `(drop ...)` 包裹，否则 `module parse: type mismatch`
2. **WASM import 必须被宿主注册**：改 HostApi 时同步注册 wasm.rs `register_host_functions`
   （按 `WASM_IMPORT_MODULE` 常量名，字符串 'micyou' 不匹配会静默失败）——宿主漏注册会报
   `cannot find definition for import`
3. **process 签名**：`(data_ptr, samples, channels, queued_ms: f64) -> i32`，queued_ms 必须是 f64，
   否则 `get_typed_func` 静默返回 None → 插件被旁路
4. **配置热更新**：宿主 set_plugin_config 后发 `config:changed` 消息（payload JSON {key, value}），
   WASM 插件解析 value 时布尔用 'true'/'false' 文本 needle（数值扫描找不到布尔）
5. **帧缓冲复用**：WASM DSP 的 frame_buf 由宿主分配一次复用；process 返回 1（bypass）时宿主**不会**读回缓冲
6. **多声道**：DSP 数据是交织的，按 `k*channels` 索引，单声道处理会毁掉立体声
7. **面板是 iframe srcdoc 单文件**：不能引相对资源；主题变量用宿主注入的 `hsl(var(--primary))` 等；
   语言用 `call('locale')` 自行本地化；状态在切换页面后重置，需 `get_config` 恢复
8. **native 宿主表 ABI**：字段只能**追加在 ctx 之后**（append-only），模板见 `micyou plugin create --runtime native`
9. **脚本化编辑陷阱**：python 字符串替换对引号/锚点不敏感时静默失败（曾有 4 次未落地）——
   改完必须 `git diff` 确认 + 构建产物 `strings` 验证；pi-lens 格式化会重写 .vue/.html，提交前 diff 剔除纯格式噪音
10. **验证要打安装目录**：end-to-end 验证器加载 `~/.config/micyou/plugins/<id>/` 的副本，
    重编译后必须同步复制到安装目录，否则测的是旧产物
11. **i18n 7 语言**：新增 UI key 必须同步 en/zh/zh-hk/zh-tw/zh-ss/cat/lzh
12. **设置页插件空白排查**：先确认组件真的 import 了（vue-tsc 不检查未注册组件）

## 测试与验证

```bash
# 单元/集成测试（宿主核心）
cargo test -p micyou-plugin
# 端到端链路回归（真实 PluginHost：enable -> trigger -> config 落盘）
cargo test -p micyou-app --lib soundpad_trigger_end_to_end
# 独立验证器模式：~/tmp/<name>scan 项目，依赖 micyou-plugin，MockHost 实现 HostApi trait，
# 直接 load_native_instance / load_wasm_instance 验证行为（记着 serde_json 要直接依赖）
```

## 发布到市场（MicYou-Plugins 仓库）

1. `micyou plugin validate <dir>` 通过
2. 插件 zip 由插件仓库 CI 打包发布 GitHub Release（wasm 插件 wat2wasm/Rust 构建后 zip 上传，参考 `MicYou-Dev/MicYou-Plugins/.github/workflows/release-plugins.yml`：workflow_dispatch 或 plugins-* tag 触发，打包每个 plugin/*/ 上传 release）
3. 推送 `MicYou-Dev/MicYou-Plugins` 的 `plugin/<id>/plugin.json`：含 downloadUrl（指向 release 资产）+ manifestUrl + preview.png（封面），**不提交 zip**
4. manifest 里 `updateUrl` 指向市场的 raw plugin.json（应用内检查更新即对接市场）
5. 修改后运行 `node --experimental-strip-types scripts/generate_catalog.ts` 重新生成 index.json（updatedAt 固定值，勿加时间戳否则 CI auto-commit 循环冲突）

## 参考

- 完整 API 参考：`docs/plugins/api-reference.md`
- 开发指南（含 manifest 全字段 + 面板工作流）：`docs/plugins/development-guide.md`
- 架构与 Android 路线：`docs/plugins/architecture-extensibility.md`
- 标准示例（可直接抄）：`plugins/examples/wasm-audioinspector/`（纯 WAT，市场展示用）
