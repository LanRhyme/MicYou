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

//! alsa-lib prints diagnostics straight to stderr, which garbles terminal
//! frontends. Route stderr through a pipe that drops those lines.

pub fn filter_alsa_stderr() {
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
