mod commands;
mod config;
mod events;
mod plugin_cmds;
mod serve;

use clap::{Parser, Subcommand};

/// On Linux, cpal/alsa-lib probes several PCM plugins (oss, dmix, route) while
/// enumerating devices and spams stderr with "ALSA lib ..." errors. This installs
/// a stderr filter that drops those lines while forwarding everything else.
#[cfg(target_os = "linux")]
fn install_alsa_stderr_filter() {
    use std::os::unix::io::RawFd;
    unsafe {
        let orig = libc::dup(libc::STDERR_FILENO);
        if orig < 0 {
            return;
        }
        let mut fds: [RawFd; 2] = [0; 2];
        if libc::pipe(fds.as_mut_ptr()) != 0 {
            libc::close(orig);
            return;
        }
        let (r, w) = (fds[0], fds[1]);
        libc::dup2(w, libc::STDERR_FILENO);
        libc::close(w);
        std::thread::spawn(move || {
            let mut buf = vec![0u8; 4096];
            let mut pending: Vec<u8> = Vec::new();
            loop {
                let n = libc::read(r, buf.as_mut_ptr() as *mut libc::c_void, buf.len());
                if n <= 0 {
                    break;
                }
                pending.extend_from_slice(&buf[..n as usize]);
                while let Some(pos) = pending.iter().position(|&b| b == b'\n') {
                    let line: Vec<u8> = pending.drain(..=pos).collect();
                    if !line.starts_with(b"ALSA lib ") {
                        libc::write(orig, line.as_ptr() as *const libc::c_void, line.len());
                    }
                }
            }
            if !pending.is_empty() && !pending.starts_with(b"ALSA lib ") {
                libc::write(orig, pending.as_ptr() as *const libc::c_void, pending.len());
            }
            libc::close(orig);
            libc::close(r);
        });
    }
}

#[derive(Parser)]
#[command(
    name = "micyou",
    version,
    about = "MicYou CLI - turn your Android device into a PC microphone",
    long_about = "MicYou CLI runs the audio server with minimal memory footprint.\n\
                  Use `micyou serve` to start the server, or `micyou --help` for all commands."
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// 启动音频服务（纯日志模式，适合终端、systemd 和脚本）
    Serve {
        /// 音频服务器端口（UDP 端口自动 +1，默认读共享 server.json）
        #[arg(long)]
        port: Option<u16>,
        /// 服务模式：wifi | usb | web（默认读共享 server.json）
        #[arg(long, value_parser = ["wifi", "usb", "web"])]
        mode: Option<String>,
        /// 指定输出音频设备名称
        #[arg(long)]
        device: Option<String>,
        /// 绑定地址
        #[arg(long)]
        bind: Option<String>,
    },
    /// 显示当前服务状态
    Status,
    /// 停止服务
    Stop,
    /// 列出音频输出设备
    Devices,
    /// 读取或修改 DSP 设置
    Settings {
        #[command(subcommand)]
        action: SettingsAction,
    },
    /// 处理链路管理
    Chain {
        #[command(subcommand)]
        action: ChainAction,
    },
    /// 平台虚拟麦克风状态（PipeWire / BlackHole / VB-Cable）
    Mics {
        /// 安装虚拟麦克风驱动（仅 Windows）
        #[arg(long)]
        install: bool,
    },
    /// 列出 ADB 设备
    AdbDevices,
    /// 读取或修改共享服务器连接设置（port / mode / bindAddress / autoBind / outputDevice）
    Server {
        #[command(subcommand)]
        action: ServerAction,
    },
    /// 显示配置文件路径
    Config,
    /// 插件开发工具：校验 / 打包 / 生成骨架
    Plugin {
        #[command(subcommand)]
        action: plugin_cmds::PluginAction,
    },
}

#[derive(Subcommand)]
enum SettingsAction {
    /// 读取设置（不指定 key 时输出全部）
    Get {
        /// 设置键名，如 gain / nsEnabled / outputBufferMs
        key: Option<String>,
    },
    /// 修改设置，如 `micyou settings set gain 10`
    Set {
        /// 设置键名
        key: String,
        /// 值（数字 / 布尔 / 字符串）
        value: String,
    },
    /// 输出当前设置的 JSON 路径
    Path,
}

#[derive(Subcommand)]
enum ChainAction {
    /// 显示当前处理链路
    List,
    /// 设置处理链路顺序，如 `micyou chain set AEC,NoiseReduction,Dereverb`
    Set {
        /// 逗号分隔的链路项
        chain: String,
    },
}

#[derive(Subcommand)]
enum ServerAction {
    /// 显示当前服务器连接设置
    Get,
    /// 修改服务器设置，如 `micyou server set port 8554` / `micyou server set mode usb`
    Set {
        /// 键名：port / webPort / mode / bindAddress / autoBind / outputDevice
        key: String,
        /// 值（数字 / 布尔 / 字符串）
        value: String,
    },
}

#[tokio::main]
async fn main() {
    #[cfg(target_os = "linux")]
    install_alsa_stderr_filter();

    let cli = Cli::parse();
    let result = match cli.command {
        Commands::Serve {
            port,
            mode,
            device,
            bind,
        } => {
            let args = serve::ServeArgs {
                port,
                mode,
                device,
                bind,
            };
            serve::run(args).await
        }
        Commands::Status => {
            commands::cmd_status();
            Ok(())
        }
        Commands::Stop => {
            commands::cmd_stop();
            Ok(())
        }
        Commands::Devices => {
            commands::cmd_devices();
            Ok(())
        }
        Commands::Settings { action } => match action {
            SettingsAction::Get { key } => commands::cmd_settings_get(key),
            SettingsAction::Set { key, value } => commands::cmd_settings_set(key, value),
            SettingsAction::Path => {
                println!("{}", config::settings_path().display());
                Ok(())
            }
        },
        Commands::Chain { action } => match action {
            ChainAction::List => {
                commands::cmd_chain_list();
                Ok(())
            }
            ChainAction::Set { chain } => {
                let items: Vec<String> = chain
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect();
                if items.is_empty() {
                    Err("empty chain".to_string())
                } else {
                    commands::cmd_chain_set(items)
                }
            }
        },
        Commands::Mics { install } => {
            if install {
                #[cfg(target_os = "windows")]
                {
                    commands::cmd_mics_install().await
                }
                #[cfg(not(target_os = "windows"))]
                {
                    Err("install is only supported on Windows (VB-CABLE)".to_string())
                }
            } else {
                commands::cmd_mics();
                Ok(())
            }
        }
        Commands::AdbDevices => {
            commands::cmd_adb_devices();
            Ok(())
        }
        Commands::Server { action } => match action {
            ServerAction::Get => {
                commands::cmd_server_get();
                Ok(())
            }
            ServerAction::Set { key, value } => commands::cmd_server_set(&key, &value),
        },
        Commands::Plugin { action } => {
            plugin_cmds::run(action)
        }
        Commands::Config => {
            commands::cmd_config_path();
            Ok(())
        }
    };

    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}
