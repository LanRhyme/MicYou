import asyncio
import json
import os
import socket
import ssl
import struct
import threading
import queue
from http.server import HTTPServer, SimpleHTTPRequestHandler
from typing import Callable, Optional, List, Tuple

SAMPLE_RATE = 48000
FRAME_SIZE = 1024
HOP_LENGTH = 256

def get_local_ips() -> List[Tuple[str, str]]:
    """Get all local LAN IPs sorted by priority"""
    ips = []
    seen = set()
    
    try:
        hostname = socket.gethostname()
        local_ip = socket.gethostbyname(hostname)
        if local_ip and not local_ip.startswith("127."):
            seen.add(local_ip)
            ips.append((local_ip, False))
    except:
        pass
    
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None):
            family, socktype, proto, canonname, sockaddr = info
            if family == socket.AF_INET:
                ip = sockaddr[0]
                if ip and not ip.startswith("127.") and ip not in seen:
                    seen.add(ip)
                    ips.append((ip, False))
    except:
        pass
    
    try:
        import subprocess
        result = subprocess.run(['ipconfig'], capture_output=True, text=True, encoding='gbk', errors='ignore')
        lines = result.stdout.split('\n')
        for line in lines:
            if "IPv4" in line and "." in line:
                parts = line.split(":")
                if len(parts) >= 2:
                    ip = parts[1].strip()
                    if ip.count('.') == 3 and not ip.startswith("127.") and ip not in seen:
                        seen.add(ip)
                        ips.append((ip, False))
    except:
        pass
    
    def get_priority(item):
        ip, _ = item
        if ip.startswith("192.168.1."):
            return 0
        elif ip.startswith("192.168.0."):
            return 1
        elif ip.startswith("192.168."):
            return 2
        elif ip.startswith("192."):
            return 3
        elif ip.startswith("10."):
            return 4
        elif ip.startswith("172."):
            return 5
        else:
            return 10
    
    ips.sort(key=get_priority)
    return [(ip, f"priority_{get_priority((ip, False))}") for ip, _ in ips]

HTML_PAGE_TEMPLATE = '''<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI Mic Denoise</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh; display: flex; flex-direction: column;
            align-items: center; justify-content: center; color: #fff; padding: 20px;
        }
        .container { text-align: center; max-width: 400px; width: 100%; }
        h1 { font-size: 1.5em; margin-bottom: 10px; color: #4fc3f7; }
        .status { 
            padding: 15px 25px; border-radius: 10px; margin: 20px 0;
            font-size: 1.1em; font-weight: 500;
        }
        .status.connected { background: #2e7d32; }
        .status.disconnected { background: #c62828; }
        .status.connecting { background: #f57c00; }
        button {
            padding: 15px 40px; font-size: 1.2em; border: none;
            border-radius: 30px; cursor: pointer; margin: 10px;
            transition: all 0.3s ease; font-weight: 600;
        }
        .start-btn { background: #4fc3f7; color: #1a1a2e; }
        .start-btn:hover { background: #29b6f6; transform: scale(1.05); }
        .stop-btn { background: #ef5350; color: #fff; }
        .stop-btn:hover { background: #e53935; transform: scale(1.05); }
        .audio-level {
            width: 100%; height: 20px; background: #333;
            border-radius: 10px; margin: 15px 0; overflow: hidden;
        }
        .audio-level-bar {
            height: 100%; background: linear-gradient(90deg, #4fc3f7, #29b6f6);
            width: 0%; transition: width 0.1s ease;
        }
        .help-box {
            background: #1e3a5f; padding: 15px; border-radius: 10px;
            margin: 15px 0; font-size: 0.85em; line-height: 1.8;
            text-align: left;
        }
        .help-box h3 { color: #4fc3f7; margin-bottom: 10px; text-align: center; }
        .lang-switch { position: absolute; top: 10px; right: 10px; }
        .lang-switch button {
            padding: 5px 10px; font-size: 0.8em; margin: 2px;
            background: #333; color: #fff;
        }
        .lang-switch button.active { background: #4fc3f7; color: #1a1a2e; }
    </style>
</head>
<body>
    <div class="lang-switch">
        <button id="btnZh" onclick="setLang('zh')" class="active">中文</button>
        <button id="btnEn" onclick="setLang('en')">EN</button>
    </div>
    <div class="container">
        <h1>AI Mic Denoise</h1>
        <p id="subtitle" style="color: #aaa;">网络音频输入</p>
        <div id="status" class="status disconnected" data-zh="未连接" data-en="Disconnected">未连接</div>
        <div class="audio-level"><div id="levelBar" class="audio-level-bar"></div></div>
        <button id="startBtn" class="start-btn" onclick="startStreaming()" data-zh="开始麦克风" data-en="Start Microphone">开始麦克风</button>
        <button id="stopBtn" class="stop-btn" onclick="stopStreaming()" style="display:none;" data-zh="停止" data-en="Stop">停止</button>
        
        <div class="help-box">
            <h3 id="helpTitle" data-zh="使用说明" data-en="Instructions">使用说明</h3>
            <div id="helpContent">
                <p data-zh="1. 点击上方按钮开始传输音频" data-en="1. Click the button above to start audio streaming">1. 点击上方按钮开始传输音频</p>
                <p data-zh="2. 浏览器会请求麦克风权限，请点击允许" data-en="2. Browser will request microphone permission, please allow it">2. 浏览器会请求麦克风权限，请点击允许</p>
                <p data-zh="3. 如果没有弹出权限请求，请检查浏览器设置：" data-en="3. If no permission prompt appears, check browser settings:">3. 如果没有弹出权限请求，请检查浏览器设置：</p>
                <p style="color:#4fc3f7;margin-left:15px;" data-zh="Chrome: 设置 → 隐私和安全 → 网站设置 → 麦克风" data-en="Chrome: Settings → Privacy → Site Settings → Microphone">Chrome: 设置 → 隐私和安全 → 网站设置 → 麦克风</p>
                <p style="color:#4fc3f7;margin-left:15px;" data-zh="Safari: 设置 → 网站 → 麦克风" data-en="Safari: Settings → Websites → Microphone">Safari: 设置 → 网站 → 麦克风</p>
                <p style="margin-top:10px;" data-zh="首次访问可能需要信任证书，点击"继续"即可" data-en="First visit may require trusting the certificate, click 'Continue'">首次访问可能需要信任证书，点击"继续"即可</p>
            </div>
        </div>
    </div>
    <script>
        const HOP_LENGTH = {HOP_LENGTH};
        let ws = null;
        let audioContext = null;
        let processor = null;
        let stream = null;
        let currentLang = 'zh';
        
        const statusEl = document.getElementById('status');
        const levelBar = document.getElementById('levelBar');
        const startBtn = document.getElementById('startBtn');
        const stopBtn = document.getElementById('stopBtn');
        
        function setLang(lang) {
            currentLang = lang;
            document.getElementById('btnZh').className = lang === 'zh' ? 'active' : '';
            document.getElementById('btnEn').className = lang === 'en' ? 'active' : '';
            
            document.querySelectorAll('[data-zh]').forEach(el => {
                el.textContent = el.getAttribute('data-' + lang);
            });
        }
        
        function setStatus(textZh, textEn, cls) {
            statusEl.setAttribute('data-zh', textZh);
            statusEl.setAttribute('data-en', textEn);
            statusEl.textContent = currentLang === 'zh' ? textZh : textEn;
            statusEl.className = 'status ' + cls;
        }
        
        async function startStreaming() {
            setStatus('正在请求麦克风...', 'Requesting microphone...', 'connecting');
            
            try {
                stream = await navigator.mediaDevices.getUserMedia({
                    audio: { sampleRate: 48000, channelCount: 1, echoCancellation: false, noiseSuppression: false }
                });
                
                audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
                const source = audioContext.createMediaStreamSource(stream);
                
                processor = audioContext.createScriptProcessor(HOP_LENGTH, 1, 1);
                
                let wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/';
                ws = new WebSocket(wsUrl);
                
                ws.binaryType = 'arraybuffer';
                
                ws.onopen = () => {
                    setStatus('已连接 - 传输中', 'Connected - Streaming', 'connected');
                    startBtn.style.display = 'none';
                    stopBtn.style.display = 'inline-block';
                };
                
                ws.onmessage = (e) => {
                    if (e.data === 'SERVER_STOP') {
                        setStatus('服务已停止', 'Server Stopped', 'disconnected');
                        stopStreaming();
                    }
                };
                
                ws.onclose = () => {
                    setStatus('未连接', 'Disconnected', 'disconnected');
                    startBtn.style.display = 'inline-block';
                    stopBtn.style.display = 'none';
                };
                
                ws.onerror = (e) => {
                    setStatus('连接失败', 'Connection Error', 'disconnected');
                    stopStreaming();
                };
                
                processor.onaudioprocess = (e) => {
                    if (ws && ws.readyState === WebSocket.OPEN) {
                        const inputData = e.inputBuffer.getChannelData(0);
                        const float32Data = new Float32Array(inputData);
                        ws.send(float32Data.buffer);
                        let sum = 0;
                        for (let i = 0; i < inputData.length; i++) {
                            sum += inputData[i] * inputData[i];
                        }
                        const rms = Math.sqrt(sum / inputData.length);
                        const level = Math.min(100, rms * 500);
                        levelBar.style.width = level + '%';
                    }
                };
                
                source.connect(processor);
                processor.connect(audioContext.destination);
                
            } catch (err) {
                let msgZh = '麦克风访问失败，请在浏览器设置中允许麦克风权限';
                let msgEn = 'Microphone access failed. Please allow microphone permission in browser settings';
                setStatus(msgZh, msgEn, 'disconnected');
            }
        }
        
        function stopStreaming() {
            if (processor) { processor.disconnect(); processor = null; }
            if (audioContext) { audioContext.close(); audioContext = null; }
            if (stream) { stream.getTracks().forEach(t => t.stop()); stream = null; }
            if (ws) { ws.close(); ws = null; }
            setStatus('未连接', 'Disconnected', 'disconnected');
            startBtn.style.display = 'inline-block';
            stopBtn.style.display = 'none';
            levelBar.style.width = '0%';
        }
    </script>
</body>
</html>
'''
                
class WebAudioServer:
    def __init__(self, port: int = 0, audio_callback: Optional[Callable] = None, hop_length: int = None):
        self.port = port if port > 0 else self._find_free_port()
        self.audio_callback = audio_callback
        self.hop_length = HOP_LENGTH  # Force fixed hop length
        self.audio_queue = queue.Queue(maxsize=100)
        self.running = False
        self.server_thread = None
        self.ws_clients = set()
        self.loop = None
        self.ssl_context = None
        self.cert_file = None
        self.key_file = None
        self._generate_self_signed_cert()
        
    def _generate_self_signed_cert(self):
        try:
            import tempfile
            import datetime
            
            cert_dir = tempfile.gettempdir()
            self.cert_file = os.path.join(cert_dir, "ai_mic_denoise.crt")
            self.key_file = os.path.join(cert_dir, "ai_mic_denoise.key")
            
            if os.path.exists(self.cert_file) and os.path.exists(self.key_file):
                self._create_ssl_context()
                return
            
            try:
                from cryptography import x509
                from cryptography.x509.oid import NameOID
                from cryptography.hazmat.primitives import hashes, serialization
                from cryptography.hazmat.primitives.asymmetric import rsa
                from cryptography.hazmat.backends import default_backend
                
                key = rsa.generate_private_key(
                    public_exponent=65537,
                    key_size=2048,
                    backend=default_backend()
                )
                
                subject = issuer = x509.Name([
                    x509.NameAttribute(NameOID.COMMON_NAME, u"localhost"),
                ])
                
                cert = x509.CertificateBuilder().subject_name(
                    subject
                ).issuer_name(
                    issuer
                ).public_key(
                    key.public_key()
                ).serial_number(
                    x509.random_serial_number()
                ).not_valid_before(
                    datetime.datetime.utcnow()
                ).not_valid_after(
                    datetime.datetime.utcnow() + datetime.timedelta(days=365)
                ).sign(key, hashes.SHA256(), default_backend())
                
                with open(self.key_file, "wb") as f:
                    f.write(key.private_bytes(
                        encoding=serialization.Encoding.PEM,
                        format=serialization.PrivateFormat.TraditionalOpenSSL,
                        encryption_algorithm=serialization.NoEncryption()
                    ))
                
                with open(self.cert_file, "wb") as f:
                    f.write(cert.public_bytes(serialization.Encoding.PEM))
                
                self._create_ssl_context()
                return
                
            except ImportError:
                pass
            
            try:
                import subprocess
                subprocess.run([
                    'openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                    '-keyout', self.key_file, '-out', self.cert_file,
                    '-days', '365', '-subj', '/CN=localhost'
                ], capture_output=True, timeout=10)
                
                if os.path.exists(self.cert_file) and os.path.exists(self.key_file):
                    self._create_ssl_context()
            except:
                pass
                
        except Exception as e:
            print(f"Failed to generate SSL certificate: {e}")
            self.ssl_context = None
    
    def _create_ssl_context(self):
        try:
            self.ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            self.ssl_context.load_cert_chain(self.cert_file, self.key_file)
        except Exception as e:
            print(f"Failed to create SSL context: {e}")
            self.ssl_context = None
        
    def _find_free_port(self) -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(('', 0))
            return s.getsockname()[1]
    
    def get_url(self) -> str:
        ips = get_local_ips()
        protocol = "https" if self.ssl_context else "http"
        if ips:
            return f"{protocol}://{ips[0][0]}:{self.port}"
        return f"{protocol}://127.0.0.1:{self.port}"
    
    def get_all_ips(self) -> List[Tuple[str, str]]:
        return get_local_ips()
    
    def get_all_urls(self) -> List[str]:
        ips = get_local_ips()
        protocol = "https" if self.ssl_context else "http"
        return [f"{protocol}://{ip}:{self.port}" for ip, _ in ips]
    
    def start(self):
        self.running = True
        self.server_thread = threading.Thread(target=self._run_server, daemon=True)
        self.server_thread.start()
        
    def stop(self):
        self.running = False
        if self.loop and self.loop.is_running():
            asyncio.run_coroutine_threadsafe(self._stop_async(), self.loop)
            self.server_thread.join(timeout=2)
        
    async def _stop_async(self):
        for client in list(self.ws_clients):
            try:
                await client.send("SERVER_STOP")
                await client.close()
            except:
                pass
        self.ws_clients.clear()
        
        if hasattr(self, '_server') and self._server:
            self._server.close()
            await self._server.wait_closed()
        
    def _get_html_page(self):
        return HTML_PAGE_TEMPLATE.replace('{HOP_LENGTH}', str(self.hop_length))
    
    def _run_server(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        
        try:
            from websockets.server import serve
            
            async def handler(websocket, path=None):
                self.ws_clients.add(websocket)
                try:
                    async for message in websocket:
                        if isinstance(message, bytes):
                            num_floats = len(message) // 4
                            audio_data = struct.unpack(f'{num_floats}f', message)
                            try:
                                self.audio_queue.put_nowait(audio_data)
                            except queue.Full:
                                pass
                except Exception as e:
                    pass
                finally:
                    self.ws_clients.discard(websocket)
            
            html_content = self._get_html_page()
            async def http_handler(path, request_headers):
                if request_headers.get("Upgrade", "").lower() == "websocket":
                    return None
                return (200, [("Content-Type", "text/html; charset=utf-8")], html_content.encode('utf-8'))
            
            async def main():
                self._server = await serve(
                    handler,
                    "0.0.0.0",
                    self.port,
                    ssl=self.ssl_context,
                    process_request=http_handler
                )
                async with self._server:
                    await asyncio.Future()
            
            if self.ssl_context:
                self.loop.run_until_complete(main())
            else:
                print("No SSL context, falling back to HTTP")
                self._run_simple_server()
            
        except Exception as e:
            print(f"Server error: {e}")
            self._run_simple_server()
    
    def _run_simple_server(self):
        html_content = self._get_html_page()
        class Handler(SimpleHTTPRequestHandler):
            def __init__(h_self, *args, **kwargs):
                super().__init__(*args, directory=None, **kwargs)
            
            def do_GET(h_self):
                h_self.send_response(200)
                h_self.send_header('Content-Type', 'text/html; charset=utf-8')
                h_self.end_headers()
                h_self.wfile.write(html_content.encode('utf-8'))
            
            def log_message(h_self, format, *args):
                pass
        
        server = HTTPServer(('0.0.0.0', self.port), Handler)
        server.serve_forever()
    
    def get_audio_chunk(self, timeout: float = 0.1):
        try:
            return self.audio_queue.get(timeout=timeout)
        except queue.Empty:
            return None
    
    def has_clients(self) -> bool:
        return len(self.ws_clients) > 0
