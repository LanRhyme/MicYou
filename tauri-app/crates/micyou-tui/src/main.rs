/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

mod config;
mod events;
mod i18n;
mod serve;
mod theme;
mod tui;

use clap::Parser;

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
        let (read_fd, write_fd) = (fds[0], fds[1]);
        libc::dup2(write_fd, libc::STDERR_FILENO);
        libc::close(write_fd);
        std::thread::spawn(move || {
            let mut buf = vec![0u8; 4096];
            let mut pending: Vec<u8> = Vec::new();
            loop {
                let count = libc::read(read_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len());
                if count <= 0 {
                    break;
                }
                pending.extend_from_slice(&buf[..count as usize]);
                while let Some(pos) = pending.iter().position(|&byte| byte == b'\n') {
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
            libc::close(read_fd);
        });
    }
}

#[derive(Parser)]
#[command(
    name = "micyou-tui",
    version,
    about = "MicYou interactive terminal audio server"
)]
struct Args {
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
}

#[tokio::main]
async fn main() {
    #[cfg(target_os = "linux")]
    install_alsa_stderr_filter();

    let args = Args::parse();
    let result = serve::run(serve::ServeArgs {
        port: args.port,
        mode: args.mode,
        device: args.device,
        bind: args.bind,
    })
    .await;

    if let Err(error) = result {
        eprintln!("error: {error}");
        std::process::exit(1);
    }
}
