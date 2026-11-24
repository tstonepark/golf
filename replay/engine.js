// Swing analysis engine (browser). All heavy lifting on the iPad; no server.
export let W=540,H=960;
// analysis frame: long side 960, short side 540, following the displayed orientation
export function setFrame(landscape){ if(landscape){W=960;H=540;} else {W=540;H=960;} }
let ortSess=null;
export let headErr="";
function loadScript(src){ return new Promise((res,rej)=>{ const e=document.createElement("script"); e.src=src; e.onload=res; e.onerror=()=>rej(new Error("load "+src)); document.head.appendChild(e); }); }
async function tryEps(base,eps,errs,dir="ort"){
  const ort=window.ort; ort.env.wasm.wasmPaths=new URL(base+"vendor/"+dir+"/",location.href).href; ort.env.wasm.numThreads=1; ort.env.wasm.proxy=false;
  for(const ep of eps){ try{ ortSess=await ort.InferenceSession.create(new URL(base+"vendor/models/clubhead416.onnx?v=8",location.href).href,{executionProviders:[ep]}); return ep; }
    catch(e){ errs.push(ep+": "+(e&&e.message||e)); console.warn("ep fail",ep,e); } }
  return null;
}
export async function loadHead(base){
  const force=new URLSearchParams(location.search).get("ep"), errs=[];
  // 1) WebGPU build (GPU first, then its own wasm)
  // iPad/iPhone Safari: ORT 1.20 only ships threaded wasm (4GB shared memory reservation) -> "Out of memory".
  // Use ORT 1.17 single-thread wasm there (and as the fallback everywhere).
  const ios=/iPad|iPhone|iPod/.test(navigator.userAgent)||(navigator.platform==="MacIntel"&&navigator.maxTouchPoints>1);
  let ep=null;
  if(!ios||force) ep=window.ort? await tryEps(base,force?[force]:(navigator.gpu?["webgpu","wasm"]:["wasm"]),errs) : null;
  if(!ep&&!force){ try{ window.ort=undefined; await loadScript(base+"vendor/ort117/ort.wasm.min.js"); ep=await tryEps(base,["wasm"],errs,"ort117"); if(ep) ep="wasm1.17"; }catch(e){ errs.push("ort1.17: "+(e&&e.message||e)); } }
  if(ep) return ep;
  headErr=errs.join(" | "); throw new Error("헤드 모델을 불러오지 못했습니다 ("+headErr.slice(0,300)+")");
}
const IN=416, lb=document.createElement("canvas"); lb.width=IN; lb.height=IN; const lbx=lb.getContext("2d",{willReadFrequently:true});
// src: analysis canvas WxH. crop {x,y,s}: square region around the golfer (may extend past the frame) -> candidates [conf,x,y] in W,H coords
export async function headInfer(src,crop){
  const c0=crop||{x:(W-Math.max(W,H))/2,y:(H-Math.max(W,H))/2,s:Math.max(W,H)}, k=IN/c0.s;
  lbx.fillStyle="rgb(114,114,114)"; lbx.fillRect(0,0,IN,IN); lbx.drawImage(src,-c0.x*k,-c0.y*k,W*k,H*k);
  const d=lbx.getImageData(0,0,IN,IN).data, N=IN*IN, f=new Float32Array(3*N);
  for(let i=0;i<N;i++){ f[i]=d[4*i]/255; f[N+i]=d[4*i+1]/255; f[2*N+i]=d[4*i+2]/255; }
  const ort=window.ort, t=new ort.Tensor("float32",f,[1,3,IN,IN]);
  const out=await ortSess.run({[ortSess.inputNames[0]]:t}); const T=out[ortSess.outputNames[0]], o=T.data, rows=(T.dims&&T.dims[1])||5, A=o.length/rows;   // rows: 4 box + 1 score per class (head, and since model 7: grip)
  const c=[]; for(let i=0;i<A;i++){ const cf=o[4*A+i]; if(cf>0.15) c.push([cf,o[i]/k+c0.x,o[A+i]/k+c0.y]); }
  c.sort((a,b)=>b[0]-a[0]); const keep=[];
  for(const q of c){ if(keep.every(z=>Math.hypot(z[1]-q[1],z[2]-q[2])>12)) keep.push(q); if(keep.length>=3) break; }
  // the hands on the grip (class 1): the single best one. Far more exact than the body-pose wrists (3.7px vs 11px median on held-out swings).
  if(rows>=6){ let bi=-1,bc=0.25; for(let i=0;i<A;i++){ const cf=o[5*A+i]; if(cf>bc){ bc=cf; bi=i; } } keep.grip= bi<0? null : [bc,o[bi]/k+c0.x,o[A+bi]/k+c0.y]; }
  return keep;
}
// ---- mp4 demux + decode (mp4box.js + WebCodecs) ----
export function demux(buf){
  return new Promise((res,rej)=>{
    const mp=MP4Box.createFile(); let info=null; const samples=[];
    mp.onError=rej;
    mp.onReady=i=>{ info=i; const tr=i.videoTracks[0]; mp.setExtractionOptions(tr.id,null,{nbSamples:1e9}); mp.start(); };
    mp.onSamples=(id,u,ss)=>{ for(const s of ss) samples.push(s); };
    buf.fileStart=0; mp.appendBuffer(buf); mp.flush();
    const tr=info.videoTracks[0], trak=mp.getTrackById(tr.id);
    let desc=null; for(const e of trak.mdia.minf.stbl.stsd.entries){ const b=e.avcC||e.hvcC; if(b){ const st=new DataStream(undefined,0,DataStream.BIG_ENDIAN); b.write(st); desc=new Uint8Array(st.buffer,8); } }
    const m=tr.matrix, rot=Math.round(Math.atan2(m[1],m[0])*180/Math.PI);
    const dur=samples.length? (samples[samples.length-1].cts-samples[0].cts)/tr.timescale : 0;
    res({codec:tr.codec,desc,w:tr.track_width,h:tr.track_height,cw:tr.video.width,ch:tr.video.height,rot,timescale:tr.timescale,samples,fps:(samples.length-1)/dur});
  });
}
// decode frames in [a,b] (sample index, presentation order) and call cb(i, canvas)
export async function decodeRange(dm,a,b,cb,rot,want){
  const cv=document.createElement("canvas"); cv.width=W; cv.height=H; const cx=cv.getContext("2d",{willReadFrequently:true});
  const order=dm.samples.map((s,k)=>[s.cts,k]).sort((x,y)=>x[0]-y[0]).map(z=>z[1]); // presentation order -> sample idx
  const pos=new Map(order.map((k,i)=>[dm.samples[k].cts,i]));
  let k0=order[a]; while(k0>0 && !dm.samples[k0].is_sync) k0--;          // start at keyframe (decode order)
  const lastDec=Math.max(...order.slice(a,b+1));
  const queue=[]; let err=null;
  const dec=new VideoDecoder({output:f=>queue.push(f),error:e=>err=e});
  dec.configure({codec:dm.codec,description:dm.desc,optimizeForLatency:true});
  const flushOut=async()=>{ while(queue.length){ const f=queue.shift(); const i=pos.get(f.timestamp);
      if(i>=a&&i<=b&&(!want||want(i))){ draw(cx,f,rot); await cb(i,cv); } f.close(); } };
  for(let k=k0;k<=lastDec+8&&k<dm.samples.length;k++){
    const s=dm.samples[k]; dec.decode(new EncodedVideoChunk({type:s.is_sync?"key":"delta",timestamp:s.cts,duration:s.duration,data:s.data}));
    { /* a phone's hardware decoder can stall (e.g. while the camera app holds the codec): fail with a message instead of hanging */
      const t0=performance.now(); while(dec.decodeQueueSize>8){ await new Promise(r=>setTimeout(r,1));
        if(performance.now()-t0>10000) throw new Error("영상 해독이 10초 넘게 멈췄습니다. 카메라 앱을 닫거나 화면을 새로고침한 뒤 다시 해 주세요."); } }
    await flushOut(); if(err) throw err;
  }
  /* flush() never resolves on some Android browsers once the last frames are out; that left the bar stuck at "98%".
     Wait at most 4 s, take whatever frames arrived, and go on. */
  await Promise.race([dec.flush().catch(()=>{}), new Promise(r=>setTimeout(r,4000))]);
  await flushOut(); try{ dec.close(); }catch(e){}
}
function draw(cx,f,rot){
  cx.save(); const r=((rot%360)+360)%360;
  if(r===90||r===270){ cx.translate(W/2,H/2); cx.rotate(r*Math.PI/180); cx.drawImage(f,-H/2,-W/2,H,W); }
  else if(r===180){ cx.translate(W/2,H/2); cx.rotate(Math.PI); cx.drawImage(f,-W/2,-H/2,W,H); }
  else cx.drawImage(f,0,0,W,H);
  cx.restore();
}
