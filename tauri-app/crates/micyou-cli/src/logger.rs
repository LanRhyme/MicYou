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

//! Minimal stderr logger for the `log` facade used by micyou-core.
//!
//! MicYou crates log at `default` (overridable with `RUST_LOG=<level>`, which
//! then applies to every crate); third-party crates stay at `warn` so mdns
//! and audio backends do not drown the output.

use log::{Level, LevelFilter, Log, Metadata, Record};
use std::io::Write;

struct StderrLogger {
    own: LevelFilter,
    deps: LevelFilter,
}

impl StderrLogger {
    fn enabled_for(&self, target: &str, level: Level) -> bool {
        let filter = if target.starts_with("micyou") {
            self.own
        } else {
            self.deps
        };
        level <= filter
    }
}

impl Log for StderrLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        self.enabled_for(metadata.target(), metadata.level())
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let level = record.level().as_str().to_ascii_lowercase();
        let _ = writeln!(std::io::stderr().lock(), "[{level}] {}", record.args());
    }

    fn flush(&self) {
        let _ = std::io::stderr().flush();
    }
}

/// Install the logger. Call once, before any other work.
pub fn init(default: LevelFilter) {
    let (own, deps) = match std::env::var("RUST_LOG")
        .ok()
        .and_then(|value| value.trim().parse::<LevelFilter>().ok())
    {
        Some(level) => (level, level),
        None => (default, LevelFilter::Warn.min(default)),
    };
    let logger: &'static StderrLogger = Box::leak(Box::new(StderrLogger { own, deps }));
    if log::set_logger(logger).is_ok() {
        log::set_max_level(own.max(deps));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn third_party_crates_are_capped_at_warn() {
        let logger = StderrLogger {
            own: LevelFilter::Info,
            deps: LevelFilter::Warn,
        };
        assert!(logger.enabled_for("micyou_core::server::output", Level::Info));
        assert!(!logger.enabled_for("micyou_core::server::output", Level::Debug));
        assert!(!logger.enabled_for("mdns_sd::service_daemon", Level::Info));
        assert!(logger.enabled_for("mdns_sd::service_daemon", Level::Warn));
    }
}
