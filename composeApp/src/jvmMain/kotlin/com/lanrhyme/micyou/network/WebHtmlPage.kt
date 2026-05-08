package com.lanrhyme.micyou.network

object WebHtmlPage {
    private const val HOP_LENGTH = 256

    fun getHtml(): String = """<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>MicYou Web Audio</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #0d1117 0%, #161b22 100%);
            min-height: 100vh; min-height: 100dvh;
            display: flex; flex-direction: column;
            align-items: center; justify-content: center;
            color: #e6edf3; padding: 20px;
        }
        .container { text-align: center; max-width: 400px; width: 100%; }
        .logo {
            width: 64px; height: 64px; margin: 0 auto 12px;
            background: linear-gradient(135deg, #58a6ff, #3fb950);
            border-radius: 18px; display: flex;
            align-items: center; justify-content: center;
            font-size: 28px; font-weight: 700; color: #fff;
        }
        h1 { font-size: 1.4em; margin-bottom: 4px; color: #58a6ff; font-weight: 600; }
        .subtitle { color: #8b949e; font-size: 0.85em; margin-bottom: 20px; }
        .status {
            padding: 14px 24px; border-radius: 12px;
            margin: 16px 0; font-size: 1em; font-weight: 500;
            transition: all 0.3s ease;
        }
        .status.connected { background: #1a7f37; color: #dafbe1; }
        .status.disconnected { background: #cf222e; color: #ffd8d2; }
        .status.connecting { background: #9a6700; color: #fff2cc; }
        button {
            padding: 14px 38px; font-size: 1.1em; border: none;
            border-radius: 28px; cursor: pointer; margin: 8px;
            transition: all 0.25s ease; font-weight: 600;
            outline: none;
        }
        button:active { transform: scale(0.95); }
        .start-btn { background: linear-gradient(135deg, #58a6ff, #3fb950); color: #fff; }
        .start-btn:hover { opacity: 0.9; transform: scale(1.03); }
        .stop-btn { background: #cf222e; color: #fff; }
        .stop-btn:hover { background: #a40e26; transform: scale(1.03); }
        .audio-level {
            width: 100%; height: 6px; background: #21262d;
            border-radius: 3px; margin: 14px 0; overflow: hidden;
        }
        .audio-level-bar {
            height: 100%; background: linear-gradient(90deg, #3fb950, #58a6ff);
            width: 0%; transition: width 0.08s ease;
            border-radius: 3px;
        }
        .help-box {
            background: #161b22; border: 1px solid #30363d;
            padding: 14px; border-radius: 10px;
            margin: 14px 0; font-size: 0.82em;
            line-height: 1.7; text-align: left; color: #8b949e;
        }
        .help-box h3 { color: #58a6ff; margin-bottom: 8px; text-align: center; font-size: 0.95em; }
        .lang-switch { position: absolute; top: 12px; right: 12px; }
        .lang-switch button {
            padding: 5px 10px; font-size: 0.78em; margin: 2px;
            background: #21262d; color: #8b949e; border-radius: 6px;
        }
        .lang-switch button.active { background: #58a6ff; color: #fff; }
    </style>
</head>
<body>
    <div class="lang-switch">
        <button id="btnZh" onclick="setLang('zh')" class="active">中文</button>
        <button id="btnEn" onclick="setLang('en')">EN</button>
    </div>
    <div class="container">
        <div class="logo">M</div>
        <h1>MicYou</h1>
        <p class="subtitle" id="subtitle" data-zh="浏览器麦克风输入" data-en="Browser Microphone Input">浏览器麦克风输入</p>
        <div id="status" class="status disconnected" data-zh="未连接" data-en="Disconnected">未连接</div>
        <div class="audio-level"><div id="levelBar" class="audio-level-bar"></div></div>
        <button id="startBtn" class="start-btn" onclick="startStreaming()" data-zh="开始麦克风" data-en="Start Microphone">开始麦克风</button>
        <button id="stopBtn" class="stop-btn" onclick="stopStreaming()" style="display:none;" data-zh="停止" data-en="Stop">停止</button>

        <div class="help-box">
            <h3 id="helpTitle" data-zh="使用说明" data-en="Instructions">使用说明</h3>
            <div id="helpContent">
                <p data-zh="1. 点击上方按钮开始传输音频" data-en="1. Click the button above to start audio streaming">1. 点击上方按钮开始传输音频</p>
                <p data-zh="2. 浏览器会请求麦克风权限，请点击允许" data-en="2. Browser will request microphone permission, please allow it">2. 浏览器会请求麦克风权限，请点击允许</p>
                <p data-zh="3. 首次访问需信任自签名证书，点击「继续」即可" data-en="3. First visit requires trusting self-signed certificate, click 'Continue'">3. 首次访问需信任自签名证书，点击「继续」即可</p>
            </div>
        </div>
    </div>
    <script>
        const HOP_LENGTH = $HOP_LENGTH;
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
            document.querySelectorAll('[data-zh]').forEach(function(el) {
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
                    audio: { sampleRate: 48000, channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false }
                });
                audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
                const source = audioContext.createMediaStreamSource(stream);
                processor = audioContext.createScriptProcessor(HOP_LENGTH, 1, 1);

                const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
                ws = new WebSocket(wsUrl);
                ws.binaryType = 'arraybuffer';

                ws.onopen = function() {
                    setStatus('已连接 - 传输中', 'Connected - Streaming', 'connected');
                    startBtn.style.display = 'none';
                    stopBtn.style.display = 'inline-block';
                };

                ws.onmessage = function(e) {
                    if (e.data === 'SERVER_STOP') {
                        setStatus('服务已停止', 'Server Stopped', 'disconnected');
                        stopStreaming();
                    }
                };

                ws.onclose = function() {
                    setStatus('未连接', 'Disconnected', 'disconnected');
                    startBtn.style.display = 'inline-block';
                    stopBtn.style.display = 'none';
                };

                ws.onerror = function() {
                    setStatus('连接失败', 'Connection Error', 'disconnected');
                    stopStreaming();
                };

                processor.onaudioprocess = function(e) {
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
                let msgEn = 'Microphone access failed. Allow microphone permission in browser settings';
                setStatus(msgZh, msgEn, 'disconnected');
                console.error('MicYou WebAudio error:', err);
            }
        }

        function stopStreaming() {
            if (processor) { processor.disconnect(); processor = null; }
            if (audioContext) { audioContext.close(); audioContext = null; }
            if (stream) { stream.getTracks().forEach(function(t) { t.stop(); }); stream = null; }
            if (ws) { ws.close(); ws = null; }
            setStatus('未连接', 'Disconnected', 'disconnected');
            startBtn.style.display = 'inline-block';
            stopBtn.style.display = 'none';
            levelBar.style.width = '0%';
        }
    </script>
</body>
</html>"""
}
