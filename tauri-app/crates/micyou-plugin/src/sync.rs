//! Cross-device sync: wire-codec mapping between the logical `PluginMessage`
//! and the protobuf `micyou::PluginMessage`, plus the transport abstraction.
//!
//! The wire format lives in `micyou-protocol` (`proto/network.proto`) and is
//! shared with the Android client, so a phone plugin and a desktop plugin
//! speak the same protocol; only the loading/runtime implementation differs.

use crate::bus::{error_message_for, PluginMessage};

/// Convert a logical message to the wire format.
pub fn to_wire(msg: &PluginMessage) -> micyou_protocol::micyou::PluginMessage {
    micyou_protocol::micyou::PluginMessage {
        source: msg.source.clone(),
        target: msg.target.clone(),
        topic: msg.topic.clone(),
        payload: msg.payload.clone(),
        correlation_id: msg.correlation_id,
        is_response: msg.is_response,
        error_code: msg.error_code,
        error_message: msg.error_message.clone(),
    }
}

/// Convert a wire message to the logical form, synthesizing the error message
/// from the code when the sender left it empty.
pub fn from_wire(wire: &micyou_protocol::micyou::PluginMessage) -> PluginMessage {
    PluginMessage {
        source: wire.source.clone(),
        target: wire.target.clone(),
        topic: wire.topic.clone(),
        payload: wire.payload.clone(),
        correlation_id: wire.correlation_id,
        is_response: wire.is_response,
        error_code: wire.error_code,
        error_message: if wire.error_message.is_empty() && wire.error_code != 0 {
            error_message_for(wire.error_code)
        } else {
            wire.error_message.clone()
        },
    }
}

/// Build a wire response for a request that failed locally.
pub fn to_wire_error(
    request: &micyou_protocol::micyou::PluginMessage,
    message: &str,
) -> micyou_protocol::micyou::PluginMessage {
    micyou_protocol::micyou::PluginMessage {
        source: request.target.clone(),
        target: request.source.clone(),
        topic: request.topic.clone(),
        payload: Vec::new(),
        correlation_id: request.correlation_id,
        is_response: true,
        error_code: 10, // PluginError::Runtime
        error_message: message.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_roundtrip_preserves_all_fields() {
        let logical = PluginMessage {
            source: "dev.micyou.sensor".into(),
            target: "dev.micyou.desktop.dsp".into(),
            topic: "sensor.accel".into(),
            payload: vec![0, 1, 2, 255],
            correlation_id: 42,
            is_response: true,
            error_code: 0,
            error_message: String::new(),
        };
        let wire = to_wire(&logical);
        assert_eq!(wire.correlation_id, 42);
        assert!(wire.is_response);
        let back = from_wire(&wire);
        assert_eq!(back, logical);
    }

    #[test]
    fn empty_error_message_is_synthesized_from_code() {
        let wire = micyou_protocol::micyou::PluginMessage {
            source: "a".into(),
            target: "b".into(),
            topic: "t".into(),
            payload: Vec::new(),
            correlation_id: 1,
            is_response: true,
            error_code: 8,
            error_message: String::new(),
        };
        let logical = from_wire(&wire);
        assert_eq!(logical.error_code, 8);
        assert_eq!(logical.error_message, "permission denied");
    }

    #[test]
    fn to_wire_error_is_a_response_to_the_requestor() {
        let request = micyou_protocol::micyou::PluginMessage {
            source: "caller".into(),
            target: "callee".into(),
            topic: "t".into(),
            payload: vec![1],
            correlation_id: 7,
            is_response: false,
            error_code: 0,
            error_message: String::new(),
        };
        let error = to_wire_error(&request, "boom");
        assert!(error.is_response);
        assert_eq!(error.target, "caller");
        assert_eq!(error.correlation_id, 7);
        assert_eq!(error.error_message, "boom");
    }
}
