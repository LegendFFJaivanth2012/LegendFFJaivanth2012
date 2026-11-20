#pragma once
#include <Arduino.h>

static const char AEROLINK_WEB_UI[] PROGMEM = R"rawliteral(
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#06101c">
<title>AEROLINK · Local Node</title>
<style>
:root{color-scheme:dark;--bg:#06101c;--panel:#0c1c2d;--line:#244058;--text:#eaf4ff;--muted:#90a8bd;--cyan:#69e7ff;--green:#61e6a4}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 30% 0,#173753 0,#06101c 52%,#030812 100%);color:var(--text);font:15px system-ui,-apple-system,Segoe UI,sans-serif;min-height:100vh;display:grid;place-items:center;padding:22px}
main{width:min(560px,100%);border:1px solid var(--line);border-radius:22px;background:linear-gradient(145deg,#10253a,#081523);padding:25px;box-shadow:0 25px 70px #0008}
.brand{font-weight:850;letter-spacing:.2em;color:var(--cyan);font-size:12px}
h1{font-size:clamp(26px,7vw,38px);line-height:1.05;margin:13px 0 7px;letter-spacing:-.04em}
p{color:var(--muted);line-height:1.6;font-size:13px}
.badge{display:inline-flex;align-items:center;gap:8px;border:1px solid #2c6558;color:var(--green);background:#102f2b;border-radius:99px;padding:7px 11px;font-size:10px;letter-spacing:.12em;font-weight:800}
.dot{width:7px;height:7px;background:var(--green);border-radius:50%;box-shadow:0 0 12px var(--green)}
.stats{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin:21px 0}
.stat{padding:14px;background:#071522;border:1px solid #203a51;border-radius:13px;min-width:0}
.stat span{display:block;color:#7793aa;font-size:9px;font-weight:800;letter-spacing:.14em}.stat strong{display:block;margin-top:7px;font-size:17px;overflow-wrap:anywhere}
.actions{display:grid;gap:10px;margin-top:16px}
a,button{display:block;text-align:center;text-decoration:none;border:1px solid #34536b;background:#142c41;color:var(--text);border-radius:12px;padding:14px;font-weight:750;font-size:12px}
a.primary,button.primary{background:var(--cyan);color:#03101b;border-color:var(--cyan)}
small{color:#7089a0;font-size:10px;display:block;margin-top:19px;line-height:1.6}
#message{font-family:ui-monospace,monospace;color:#a9c5d8;font-size:11px;white-space:pre-wrap;margin-top:12px}
</style>
</head>
<body><main>
<div class="brand">AEROLINK · TWO-NODE LOCAL NETWORK</div>
<h1 id="title">ESP32 NODE</h1>
<div class="badge"><span class="dot"></span><span id="state">CHECKING NODE</span></div>
<p id="summary">This page is being served directly by the ESP32. It does not need the internet.</p>
<div class="stats">
 <div class="stat"><span>NODE ROLE</span><strong id="role">—</strong></div>
 <div class="stat"><span>PHONE CONNECTIONS</span><strong id="clients">—</strong></div>
 <div class="stat"><span>UART BRIDGE</span><strong id="uart">—</strong></div>
 <div class="stat"><span>PACKETS RECEIVED</span><strong id="packets">—</strong></div>
 <div class="stat"><span>TRANSFER</span><strong id="transfer">—</strong></div>
 <div class="stat"><span>FILE</span><strong id="filename">—</strong></div>
</div>
<div class="actions">
 <button class="primary" onclick="refreshNode()">REFRESH NODE STATUS</button>
 <a id="download" href="/api/file" style="display:none">SAVE RECEIVED FILE</a>
 <button onclick="resetNode()">RESET RECEIVED FILE</button>
</div>
<div id="message">Connecting to local ESP32…</div>
<small>Phone sender connects to AEROLINK-TX. Phone receiver connects to AEROLINK-RX. The two ESP32 boards are linked by TX, RX and GND wires. No cloud server or room code is used.</small>
</main>
<script>
async function refreshNode(){
 try{
  const r=await fetch('/api/status',{cache:'no-store'});
  if(!r.ok) throw new Error('HTTP '+r.status);
  const d=await r.json();
  document.querySelector('#role').textContent=(d.role||'node').toUpperCase();
  document.querySelector('#title').textContent=(d.role||'ESP32')+' NODE';
  document.querySelector('#clients').textContent=d.clients??'—';
  document.querySelector('#uart').textContent=d.uart?'CONNECTED':'READY';
  document.querySelector('#packets').textContent=(d.packets??0)+' / '+(d.total??0);
  document.querySelector('#transfer').textContent=d.complete?'COMPLETE':(d.receiving?'RECEIVING':'IDLE');
  document.querySelector('#filename').textContent=d.name||'—';
  document.querySelector('#state').textContent=d.complete?'FILE READY':'NODE ONLINE';
  document.querySelector('#download').style.display=d.complete?'block':'none';
  document.querySelector('#message').textContent=JSON.stringify(d,null,2);
 }catch(e){document.querySelector('#state').textContent='NOT REACHABLE';document.querySelector('#message').textContent=String(e);}
}
async function resetNode(){
 try{await fetch('/api/reset',{method:'POST'});refreshNode();}
 catch(e){document.querySelector('#message').textContent=String(e);}
}
refreshNode();setInterval(refreshNode,1800);
</script>
</body></html>
)rawliteral";
