# Desktop Network Server Layer

## OVERVIEW
Desktop network layer: TCP server (Ktor sockets), UDP audio channel, protocol handling.

## WHERE TO LOOK
| File | Role |
|------|------|
| NetworkServer.kt | TCP/UDP server lifecycle, accept loop |
| ConnectionHandler.kt | Protocol framing, message dispatch |

## CONVENTIONS
- TCP: Ktor sockets with coroutine-based I/O
- UDP: DatagramSocket for audio data transmission
- Clear separation: NetworkServer = transport, ConnectionHandler = protocol
- Logging via project Logger abstraction
- Resource safety: close sockets/streams in finally blocks

## ANTI-PATTERNS
- Do not block I/O on non-network threads
- Never hard-code addresses/ports - use settings
- Do not leak sockets or streams
- Never swallow errors in accept loops