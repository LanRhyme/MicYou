/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
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


//! Relays plugin bus messages to the connected phone over the TCP control
//! connection.

use micyou_plugin::bus::{PluginMessage, PluginSyncTransport};
use micyou_plugin::{PluginError, PluginResult};
use micyou_protocol::micyou::MessageWrapper;
use std::sync::Mutex;
use tokio::sync::mpsc::Sender;

#[derive(Default)]
pub struct TcpPluginSyncAdapter {
    sender: Mutex<Option<Sender<MessageWrapper>>>,
}

impl TcpPluginSyncAdapter {
    pub fn set_sender(
        &self,
        sender: Option<Sender<MessageWrapper>>,
    ) {
        if let Ok(mut slot) = self.sender.lock() {
            *slot = sender;
        }
    }

    pub fn clear_if(
        &self,
        tx: &Sender<MessageWrapper>,
    ) {
        if let Ok(mut slot) = self.sender.lock() {
            if slot.as_ref().is_some_and(|s| s.same_channel(tx)) {
                *slot = None;
            }
        }
    }
}

impl PluginSyncTransport for TcpPluginSyncAdapter {
    fn send(&self, msg: &PluginMessage) -> PluginResult<()> {
        let slot = self
            .sender
            .lock()
            .map_err(|_| PluginError::Runtime("sync sender poisoned".into()))?;
        let Some(tx) = slot.as_ref() else {
            return Err(PluginError::MessageDelivery(
                "no device connected".into(),
            ));
        };
        let wire = micyou_plugin::sync::to_wire(msg);
        let wrapper = MessageWrapper {
            plugin_message: Some(wire),
            ..Default::default()
        };
        tx.try_send(wrapper)
            .map_err(|e| PluginError::MessageDelivery(e.to_string()))?;
        Ok(())
    }

    fn is_connected(&self) -> bool {
        self.sender.lock().map(|g| g.is_some()).unwrap_or(false)
    }
}
