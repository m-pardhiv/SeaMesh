// SeaMesh shore dashboard page, served by the gateway boat at /
// (kept out of the .ino so the Arduino preprocessor doesn't misparse it)
#pragma once
#include <Arduino.h>

const char PAGE[] PROGMEM = R"rawliteral(<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SeaMesh Shore Dashboard</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#0b1d2e;color:#e6eef5}
header{padding:10px 14px;background:#102a43;display:flex;justify-content:space-between;align-items:center}
h1{font-size:18px;margin:0}#gw{font-size:13px;opacity:.8}
#sos{display:none;background:#c62828;color:#fff;padding:10px 14px;font-weight:700;animation:f 1s infinite}
@keyframes f{50%{opacity:.6}}
#map{height:55vh}#side{padding:10px 14px}
.boat{background:#16324f;border-radius:8px;padding:8px 10px;margin-bottom:8px;font-size:14px}
.boat.S{border-left:5px solid #ef5350}.boat.P{border-left:5px solid #42a5f5}
.boat.stale{border-left-color:#9e9e9e;opacity:.85}
.tag{font-size:11px;font-weight:700;padding:1px 6px;border-radius:4px;background:#ffb300;color:#000;margin-left:6px}
#legend{font-size:12px;opacity:.8;margin-bottom:8px}
#log{font-family:monospace;font-size:12px;opacity:.85;white-space:pre-wrap}
</style></head><body>
<header><h1>SeaMesh Shore Dashboard</h1><span id="gw"></span></header>
<div id="sos"></div><div id="map"></div>
<div id="side"><div id="legend">&#9679; <span style="color:#42a5f5">live</span> &nbsp; &#9679; <span style="color:#ef5350">SOS</span> &nbsp; &#9679; <span style="color:#9e9e9e">last known (no contact)</span> &nbsp; green circle = approx. cellular coverage</div>
<div id="list"></div><h3>Event log</h3><div id="log"></div></div>
<script>
var STALE=60,map=null,markers={},trails={},fittedCount=0,d_gw='';
if(window.L){map=L.map('map').setView([9.96,76.1],10);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'&copy; OpenStreetMap'}).addTo(map);
L.circle([9.9312,76.2673],{radius:20000,color:'#66bb6a',weight:1,dashArray:'6',fillOpacity:.06,interactive:false}).addTo(map);}
else{document.getElementById('map').innerHTML='<p style="padding:14px">Map needs internet - showing list only.</p>';}
function ago(s){return s<60?s+'s':Math.floor(s/60)+'m '+(s%60)+'s';}
function route(b){return b.id==d_gw?'own position':b.hops==0?'direct':b.hops+' relay hop(s), last via '+b.via;}
function stale(b){return b.age>STALE;}
function color(b){return b.type=='S'?'#ef5350':stale(b)?'#9e9e9e':'#42a5f5';}
function tick(){fetch('/data').then(r=>r.json()).then(d=>{
 d_gw=d.gw;
 document.getElementById('gw').textContent='Gateway: boat '+d.gw+(d.online?' (online)':' (offline)');
 var sos=d.boats.filter(b=>b.type=='S');var s=document.getElementById('sos');
 s.style.display=sos.length?'block':'none';
 s.textContent=sos.map(b=>'SOS - Boat '+b.id+' '+(stale(b)?'LAST KNOWN':'at')+' '+b.lat.toFixed(4)+', '+b.lon.toFixed(4)+' ('+b.trig+'), '+ago(b.age)+' ago, '+route(b)).join(' | ');
 document.getElementById('list').innerHTML=d.boats.map(b=>'<div class="boat '+b.type+(stale(b)?' stale':'')+'"><b>Boat '+b.id+'</b> - '+(b.type=='S'?'SOS ('+b.trig+')':'normal')+
  (stale(b)?'<span class="tag">LAST KNOWN - no contact for '+ago(b.age)+'</span>':'')+
  '<br>'+b.lat.toFixed(5)+', '+b.lon.toFixed(5)+'<br>'+route(b)+' &middot; msg #'+b.msg+' &middot; heard '+ago(b.age)+' ago'+(b.rssi?' &middot; RSSI '+b.rssi:'')+'</div>').join('')||'No boats heard yet.';
 document.getElementById('log').textContent=d.events.slice().reverse().join('\n');
 if(!map)return;
 d.boats.forEach(b=>{var c=color(b),st=stale(b);
  var tip='Boat '+b.id+(b.type=='S'?' SOS':'')+(st?' (last known, '+ago(b.age)+' ago)':'');
  if(!trails[b.id])trails[b.id]=L.polyline([],{weight:2,opacity:.6}).addTo(map);
  trails[b.id].setLatLngs(b.trail).setStyle({color:c,dashArray:st?'4':null});
  if(!markers[b.id])markers[b.id]=L.circleMarker([b.lat,b.lon],{radius:10}).addTo(map).bindTooltip(tip,{permanent:true});
  markers[b.id].setLatLng([b.lat,b.lon]).setStyle({color:c,fillColor:c,fillOpacity:st?.35:.85,dashArray:st?'4':null}).setTooltipContent(tip);});
 if(d.boats.length!=fittedCount){fittedCount=d.boats.length;
  map.fitBounds(L.latLngBounds(d.boats.map(b=>[b.lat,b.lon])).pad(0.6),{maxZoom:11});}
}).catch(()=>{});}
tick();setInterval(tick,2000);
</script></body></html>)rawliteral";
