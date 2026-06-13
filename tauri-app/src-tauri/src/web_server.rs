use rcgen::{CertificateParams, KeyPair, SanType};
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use tokio_util::sync::CancellationToken;

pub const DEFAULT_WEB_PORT: u16 = 8443;

pub struct WebServer {
    cancel_token: CancellationToken,
    client_count: Arc<AtomicUsize>,
    running: Arc<AtomicBool>,
}

pub struct GeneratedCert {
    pub cert_pem: String,
    pub key_pem: String,
}

pub fn cert_cache_dir() -> PathBuf {
    let dir = std::env::temp_dir().join("micyou_web_cert");
    std::fs::create_dir_all(&dir).ok();
    dir
}

pub fn get_lan_ips() -> Vec<String> {
    let mut ips = Vec::new();
    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        for (_, ip) in interfaces {
            if ip.is_loopback() || !ip.is_ipv4() {
                continue;
            }
            let ip_str = ip.to_string();
            if ip_str.starts_with("198.18.") || ip_str.starts_with("169.254.") {
                continue;
            }
            ips.push(ip_str);
        }
    }
    ips
}

pub fn generate_self_signed_cert_pem() -> Result<GeneratedCert, String> {
    let lan_ips = get_lan_ips();

    let mut params = CertificateParams::new(vec!["localhost".to_string()])
        .map_err(|e| format!("Failed to create cert params: {}", e))?;

    params.subject_alt_names.push(SanType::IpAddress(IpAddr::V4(std::net::Ipv4Addr::LOCALHOST)));
    for ip_str in &lan_ips {
        if let Ok(ip) = ip_str.parse::<IpAddr>() {
            params.subject_alt_names.push(SanType::IpAddress(ip));
        }
    }

    let key_pair = KeyPair::generate()
        .map_err(|e| format!("Failed to generate key pair: {}", e))?;
    let cert = params.self_signed(&key_pair)
        .map_err(|e| format!("Failed to sign certificate: {}", e))?;

    Ok(GeneratedCert {
        cert_pem: cert.pem(),
        key_pem: key_pair.serialize_pem(),
    })
}

pub fn load_or_generate_cert_pem() -> Result<GeneratedCert, String> {
    let cache_dir = cert_cache_dir();
    let cert_path = cache_dir.join("cert.pem");
    let key_path = cache_dir.join("key.pem");

    if cert_path.exists() && key_path.exists() {
        if let (Ok(cert_pem), Ok(key_pem)) = (std::fs::read_to_string(&cert_path), std::fs::read_to_string(&key_path)) {
            if !cert_pem.is_empty() && !key_pem.is_empty() {
                return Ok(GeneratedCert { cert_pem, key_pem });
            }
        }
    }

    let cert = generate_self_signed_cert_pem()?;
    std::fs::write(&cert_path, &cert.cert_pem).ok();
    std::fs::write(&key_path, &cert.key_pem).ok();
    Ok(cert)
}

pub fn float32_to_pcm16(float32_bytes: &[u8]) -> Vec<u8> {
    let num_floats = float32_bytes.len() / 4;
    let mut pcm = Vec::with_capacity(num_floats * 2);
    for i in 0..num_floats {
        let offset = i * 4;
        let sample = f32::from_le_bytes([
            float32_bytes[offset],
            float32_bytes[offset + 1],
            float32_bytes[offset + 2],
            float32_bytes[offset + 3],
        ]);
        let clamped = sample.clamp(-1.0, 1.0);
        let pcm_sample = (clamped * 32767.0) as i16;
        pcm.extend_from_slice(&pcm_sample.to_le_bytes());
    }
    pcm
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_self_signed_cert_pem() {
        let cert = generate_self_signed_cert_pem();
        assert!(cert.is_ok(), "Cert generation should succeed: {:?}", cert.err());
        let c = cert.unwrap();
        assert!(c.cert_pem.contains("BEGIN CERTIFICATE"));
        assert!(c.key_pem.contains("PRIVATE KEY"));
    }

    #[test]
    fn test_get_lan_ips() {
        let ips = get_lan_ips();
        for ip in &ips {
            assert!(ip.parse::<IpAddr>().is_ok(), "Invalid IP: {}", ip);
        }
    }

    #[test]
    fn test_cert_cache_dir_exists() {
        let dir = cert_cache_dir();
        assert!(dir.exists());
    }

    #[test]
    fn test_float32_to_pcm16_one() {
        let input = 1.0f32.to_le_bytes();
        let pcm = float32_to_pcm16(&input);
        assert_eq!(pcm.len(), 2);
        let sample = i16::from_le_bytes([pcm[0], pcm[1]]);
        assert_eq!(sample, 32767);
    }

    #[test]
    fn test_float32_to_pcm16_neg_one() {
        let input = (-1.0f32).to_le_bytes();
        let pcm = float32_to_pcm16(&input);
        let sample = i16::from_le_bytes([pcm[0], pcm[1]]);
        assert_eq!(sample, -32767);
    }

    #[test]
    fn test_float32_to_pcm16_zero() {
        let input = 0.0f32.to_le_bytes();
        let pcm = float32_to_pcm16(&input);
        let sample = i16::from_le_bytes([pcm[0], pcm[1]]);
        assert_eq!(sample, 0);
    }
}

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse};
use axum::routing::get;
use axum::serve::Listener;
use axum::Router;
use rustls::pki_types::CertificateDer;
use rustls::ServerConfig;
use std::io::BufReader;
use std::net::SocketAddr;
use tauri::{AppHandle, Emitter};
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::{server::TlsStream, TlsAcceptor};

const WEB_CLIENT_HTML: &str = include_str!("../resources/web_client.html");
const ALPINE_JS: &str = include_str!("../resources/alpine.min.js");

fn is_valid_origin(origin: Option<&str>) -> bool {
    match origin {
        None => true,
        Some(o) => {
            let o = o.to_lowercase();
            o.contains("localhost")
                || o.contains("127.0.0.1")
                || get_lan_ips().iter().any(|ip| o.contains(ip))
        }
    }
}

async fn handle_websocket(
    ws: WebSocketUpgrade,
    headers: HeaderMap,
    axum::extract::State(state): axum::extract::State<WebServerState>,
) -> impl IntoResponse {
    let origin = headers.get("origin").and_then(|v| v.to_str().ok());
    if !is_valid_origin(origin) {
        return (StatusCode::FORBIDDEN, "Invalid origin").into_response();
    }
    ws.on_upgrade(move |socket| handle_ws_socket(socket, state))
}

async fn handle_ws_socket(mut socket: WebSocket, state: WebServerState) {
    let count = state.client_count.fetch_add(1, Ordering::SeqCst) + 1;
    let _ = state.app_handle.emit("web-client-count", count as u32);
    log::info!("Web client connected (total: {})", count);

    if count == 1 {
        let _ = state.app_handle.emit("device-connected", serde_json::json!({
            "name": "Web Browser",
            "ip": "browser",
            "latency": 0
        }));
    }

    loop {
        match socket.recv().await {
            Some(Ok(Message::Binary(data))) => {
                if data.len() > 2 * 1024 * 1024 {
                    log::warn!("Web audio packet too large ({} bytes), dropping", data.len());
                    continue;
                }
                if data.len() % 4 != 0 {
                    log::warn!("Web audio packet not aligned to 4 bytes, dropping");
                    continue;
                }

                let pcm = float32_to_pcm16(&data);
                let packet = micyou_protocol::micyou::AudioPacketMessage {
                    buffer: pcm,
                    sample_rate: 48000,
                    channel_count: 1,
                    audio_format: 2,
                };
                if state.audio_tx.send(packet).await.is_err() {
                    log::warn!("Audio channel full, dropping web packet");
                }
            }
            Some(Ok(Message::Close(_))) | None => break,
            Some(Err(e)) => {
                log::warn!("WebSocket error: {}", e);
                break;
            }
            _ => {}
        }
    }

    let remaining = state.client_count.fetch_sub(1, Ordering::SeqCst) - 1;
    let _ = state.app_handle.emit("web-client-count", remaining as u32);
    log::info!("Web client disconnected (remaining: {})", remaining);

    if remaining == 0 {
        let _ = state.app_handle.emit("device-disconnected", ());
    }
}

async fn serve_html() -> impl IntoResponse {
    Html(WEB_CLIENT_HTML)
}

async fn serve_alpine_js() -> impl IntoResponse {
    ([("Content-Type", "application/javascript")], ALPINE_JS)
}

#[derive(Clone)]
pub struct WebServerState {
    pub app_handle: AppHandle,
    pub audio_tx: tokio::sync::mpsc::Sender<micyou_protocol::micyou::AudioPacketMessage>,
    pub client_count: Arc<AtomicUsize>,
}

struct TlsListener {
    tcp: TcpListener,
    acceptor: TlsAcceptor,
}

impl Listener for TlsListener {
    type Io = TlsStream<TcpStream>;
    type Addr = SocketAddr;

    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        loop {
            match self.tcp.accept().await {
                Ok((stream, addr)) => {
                    match self.acceptor.accept(stream).await {
                        Ok(tls) => return (tls, addr),
                        Err(e) => {
                            log::debug!("TLS handshake failed: {}", e);
                            continue;
                        }
                    }
                }
                Err(e) => {
                    log::warn!("TCP accept error: {}", e);
                    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                }
            }
        }
    }

    fn local_addr(&self) -> std::io::Result<SocketAddr> {
        self.tcp.local_addr()
    }
}

impl WebServer {
    pub fn new() -> Self {
        Self {
            cancel_token: CancellationToken::new(),
            client_count: Arc::new(AtomicUsize::new(0)),
            running: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn client_count(&self) -> usize {
        self.client_count.load(Ordering::SeqCst)
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }

    pub async fn start(
        &self,
        port: u16,
        app_handle: AppHandle,
        audio_tx: tokio::sync::mpsc::Sender<micyou_protocol::micyou::AudioPacketMessage>,
    ) -> Result<(), String> {
        if self.running.load(Ordering::SeqCst) {
            return Err("Web server is already running".to_string());
        }

        let state = WebServerState {
            app_handle,
            audio_tx,
            client_count: self.client_count.clone(),
        };

        let app = Router::new()
            .route("/", get(serve_html))
            .route("/alpine.min.js", get(serve_alpine_js))
            .route("/ws", get(handle_websocket))
            .with_state(state);

        // Load TLS certificate
        let cert = load_or_generate_cert_pem()?;
        let cert_chain: Vec<CertificateDer<'static>> = rustls_pemfile::certs(&mut BufReader::new(cert.cert_pem.as_bytes()))
            .filter_map(|r| r.ok())
            .map(CertificateDer::from)
            .collect();

        let private_key = rustls_pemfile::private_key(&mut BufReader::new(cert.key_pem.as_bytes()))
            .map_err(|e| format!("Failed to read private key: {}", e))?
            .ok_or("No private key found in PEM")?;

        let mut tls_config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(cert_chain, private_key)
            .map_err(|e| format!("TLS config error: {}", e))?;

        tls_config.alpn_protocols = vec![b"http/1.1".to_vec()];

        let acceptor = TlsAcceptor::from(Arc::new(tls_config));

        let addr: SocketAddr = format!("0.0.0.0:{}", port).parse()
            .map_err(|e| format!("Invalid address: {}", e))?;

        let tcp = TcpListener::bind(addr).await
            .map_err(|e| format!("Web server bind error: {}", e))?;

        let tls_listener = TlsListener { tcp, acceptor };

        log::info!("Web server listening on https://0.0.0.0:{}", port);

        let cancel = self.cancel_token.clone();
        let running = self.running.clone();
        let client_count = self.client_count.clone();

        running.store(true, Ordering::SeqCst);

        tokio::spawn(async move {
            axum::serve(tls_listener, app)
                .with_graceful_shutdown(async move { cancel.cancelled().await; })
                .await
                .ok();

            running.store(false, Ordering::SeqCst);
            client_count.store(0, Ordering::SeqCst);
        });

        Ok(())
    }

    pub fn stop(&self) {
        self.cancel_token.cancel();
        self.running.store(false, Ordering::SeqCst);
        self.client_count.store(0, Ordering::SeqCst);
    }
}
