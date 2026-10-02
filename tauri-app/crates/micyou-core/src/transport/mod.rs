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

//! Phone ↔ desktop transports: TCP control, UDP audio, the browser
//! WebSocket server and the packet reordering in between.

pub mod jitter_buffer;
pub mod net_bind;
pub mod opus;
pub mod session;
pub mod tcp;
pub mod udp;
#[cfg(feature = "web-server")]
pub mod web;
