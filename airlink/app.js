const $ = (s) => document.querySelector(s);
const logEl = $("#log");
const state = {
  file:null, channel:"light", packets:0, valid:0, retries:0, demo:false, connected:false
};

function log(message){
  const t = new Date().toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit"});
  const row = document.createElement("div");
  row.innerHTML = "<span>"+t+"</span> "+message;
  logEl.prepend(row);
}

function formatBytes(n){
  if(!n) return "0 B";
  const units=["B","KB","MB","GB"];
  const i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),3);
  return (n/Math.pow(1024,i)).toFixed(i?1:0)+" "+units[i];
}

$("#fileInput").addEventListener("change", e=>{
  const file=e.target.files[0];
  if(!file) return;
  state.file=file;
  $("#fileName").textContent=file.name;
  $("#fileSize").textContent=formatBytes(file.size)+" · "+file.type;
  $("#filePill").classList.remove("hidden");
  $("#routeText").textContent="Payload ready";
  log("Payload loaded: "+file.name+" ("+formatBytes(file.size)+").");
});

$("#clearFile").addEventListener("click",()=>{
  state.file=null; $("#fileInput").value=""; $("#filePill").classList.add("hidden");
  $("#routeText").textContent="Awaiting payload"; resetProgress();
});

function resetProgress(){
  $("#progressBar").style.width="0%"; $("#progressText").textContent="0%"; $("#speedText").textContent="—";
  $("#signal").style.width="0%";
}

document.querySelectorAll(".transport").forEach(btn=>{
  btn.addEventListener("click",()=>{
    document.querySelectorAll(".transport").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    state.channel=btn.dataset.channel;
    $("#modeChip").textContent=state.channel.toUpperCase();
    log("Manual transport selected: "+state.channel.toUpperCase()+".");
  });
});

$("#demoBtn").addEventListener("click", async()=>{
  if(!state.file){
    state.file=new File(["AEROLINK demo payload"],"AEROLINK-demo.txt",{type:"text/plain"});
    $("#fileName").textContent=state.file.name; $("#fileSize").textContent=formatBytes(state.file.size);
    $("#filePill").classList.remove("hidden");
  }
  state.demo=true; state.packets=0; state.valid=0; state.retries=0;
  updateMetrics();
  $("#routeText").textContent="Adaptive link negotiating…";
  $("#systemStatus").textContent="TRANSMITTING";
  $("#systemDot").style.background="var(--cyan)";
  log("Demo started. Probing available transports.");
  await sleep(500);
  const channels=["light","wifi","ble"];
  for(let i=0;i<channels.length;i++){
    await sleep(250);
    log("Channel probe: "+channels[i].toUpperCase()+" → acceptable.");
  }
  log("Optical path selected for primary transport.");
  $("#routeText").textContent="Optical link active";
  $("#modeChip").textContent="AUTO";
  await animateTransfer();
});

async function animateTransfer(){
  resetProgress();
  const total=32;
  for(let i=1;i<=total;i++){
    await sleep(55+Math.random()*55);
    state.packets++;
    state.valid++;
    $("#progressBar").style.width=(i/total*100)+"%";
    $("#progressText").textContent=Math.round(i/total*100)+"%";
    $("#speedText").textContent=(120+Math.round(Math.random()*80))+" kb/s";
    $("#signal").style.width=Math.min(100,i/total*100)+"%";
    updateMetrics();
    if(i===18){
      state.retries++;
      log("Packet 18 failed integrity check → retry.");
    }
  }
  $("#routeText").textContent="Transfer complete";
  $("#systemStatus").textContent="COMPLETE";
  $("#systemDot").style.background="var(--green)";
  log("All packets verified. File reconstructed successfully.");
}

function updateMetrics(){
  $("#packetMetric").textContent=state.packets;
  $("#validMetric").textContent=state.valid;
  $("#retryMetric").textContent=state.retries;
  $("#latencyMetric").textContent=state.packets?Math.round(7+Math.random()*5)+" ms":"—";
}

function sleep(ms){return new Promise(r=>setTimeout(r,ms));}

function checkBluetooth(){
  const supported=!!navigator.bluetooth;
  $("#bleSupport").textContent=supported?"AVAILABLE":"UNAVAILABLE";
  if(!supported) $("#bleSupport").style.color="#d99191";
  return supported;
}

$("#bleBtn").addEventListener("click",async()=>{
  if(!checkBluetooth()){
    log("Web Bluetooth is unavailable in this browser.");
    return;
  }
  try{
    $("#bleState").textContent="SCANNING…";
    log("Requesting AEROLINK BLE device…");
    const device=await navigator.bluetooth.requestDevice({
      filters:[{namePrefix:"AEROLINK"}],
      optionalServices:["0000a100-0000-1000-8000-00805f9b34fb"]
    });
    $("#bleState").textContent=device.name||"CONNECTED";
    state.connected=true;
    $("#systemStatus").textContent="BLE CONNECTED";
    log("Connected to "+(device.name||"AEROLINK device")+".");
  }catch(err){
    $("#bleState").textContent="NOT CONNECTED";
    if(err.name!=="NotFoundError") log("BLE connection error: "+err.message);
  }
});

checkBluetooth();
window.addEventListener("beforeinstallprompt",e=>{e.preventDefault();window._installPrompt=e;});
