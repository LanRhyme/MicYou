use micyou_protocol::micyou::AudioPacketMessageOrdered;

#[derive(Debug)]
pub enum AudioStreamEvent {
    SessionStarting,
    Packet(AudioPacketMessageOrdered),
}
