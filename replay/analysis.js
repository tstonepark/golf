// Swing analysis: tracking, phases, plane check, body metrics. Pure functions (no DOM).
// Coordinates are in the 540x960 analysis frame.
const hyp=Math.hypot;
const dif=(a,b)=>Math.abs(((a-b+540)%360)-180);

// 1) remove fixed background objects (seen at the same spot in both the first and last 20% of the clip)
export function staticFilter(pts){
  const N=pts.length, cell=new Map();
  pts.forEach((p,i)=>p&&p.forEach(([c,x,y])=>{const k=Math.round(x/16)+","+Math.round(y/16);(cell.get(k)||cell.set(k,[]).get(k)).push([i,x,y]);}));
  // judge "present from start to end" over the frames that were actually analysed (pass 2 covers only a window)
  let i0=-1,i1=-1,nd=0; pts.forEach((p,i)=>{ if(p&&p.length){ if(i0<0) i0=i; i1=i; nd++; } });
  const span=Math.max(1,i1-i0), cen=[];
  for(const v of cell.values()){ const fr=v.map(z=>z[0]);
    if(v.length>0.03*nd && Math.min(...fr)<i0+0.2*span && Math.max(...fr)>i1-0.2*span)
      cen.push([v.reduce((s,z)=>s+z[1],0)/v.length, v.reduce((s,z)=>s+z[2],0)/v.length]); }
  return pts.map(p=>p? p.filter(([c,x,y])=>c>=0.25 && cen.every(([a,b])=>hyp(x-a,y-b)>14)) : []);
}
// 2) greedy track from the most confident detection, both directions
export function track(pts,maxjump=80){
  const n=pts.length,out=new Array(n).fill(null); let seed=-1,best=0;
  pts.forEach((p,i)=>{ if(p.length&&p[0][0]>best){best=p[0][0];seed=i;} });
  if(seed<0) return out; out[seed]=[pts[seed][0][1],pts[seed][0][2]];
  for(const dir of [1,-1]){ let last=out[seed],gap=0;
    for(let i=seed+dir;i>=0&&i<n;i+=dir){ let b=null;
      for(const [c,x,y] of pts[i]){ const d=hyp(x-last[0],y-last[1]); if(d<maxjump*(1+gap)&&(!b||d<b[0])) b=[d,x,y]; }
      if(b){ out[i]=[b[1],b[2]]; last=out[i]; gap=0; } else gap++; } }
  return out;
}
// 3) drop isolated points
export function clean(tr,win=4,maxd=60){
  const n=tr.length;
  return tr.map((p,i)=>{ if(!p) return null; for(let j=Math.max(0,i-win);j<Math.min(n,i+win+1);j++){ if(j!==i&&tr[j]&&Math.abs(tr[j][0]-p[0])+Math.abs(tr[j][1]-p[1])<maxd) return p; } return null; });
}
// sampling gaps (frames we skipped on purpose) -> linear fill, not marked as estimated
export function fillGaps(tr,maxg){
  const o=tr.slice(); let last=-1;
  for(let i=0;i<o.length;i++){ if(!tr[i]) continue;
    if(last>=0&&i-last>1&&i-last<=maxg){ for(let k=last+1;k<i;k++){ const u=(k-last)/(i-last); o[k]=[tr[last][0]+(tr[i][0]-tr[last][0])*u,tr[last][1]+(tr[i][1]-tr[last][1])*u]; } }
    last=i; }
  return o;
}
// 4) club length check against hands (needs pose): head must be 0.45..1.25 L from the hands
export function clubLengthFilter(tr,hands){
  const ds=[]; tr.forEach((p,i)=>{ if(p&&hands[i]) ds.push(hyp(p[0]-hands[i][0],p[1]-hands[i][1])); });
  if(ds.length<10) return {tr,L:null};
  ds.sort((a,b)=>a-b); const L=ds[Math.floor(ds.length*0.85)];
  return {L,tr:tr.map((p,i)=>{ if(!p||!hands[i]) return p; const d=hyp(p[0]-hands[i][0],p[1]-hands[i][1]); return (d>0.1*L&&d<1.25*L)?p:null; })};   // lower bound is loose: seen from behind, the club points at the camera mid-downswing and looks short
}
// body-part false positives (shoulders, wrists): drop candidates sitting on them
export function bodyFilter(pts,pose){
  return pts.map((p,i)=>{ const P=pose[i]; if(!P||!p) return p;
    return p.filter(([c,x,y])=> P.joints.every(([jx,jy,r])=>hyp(x-jx,y-jy)>r)); });
}
// 5) phases from the head path
export function phases(tr,fps,hands){
  const n=tr.length,K=Math.max(3,Math.round(0.06*fps));
  let best=-1,k=-1;
  for(let i=K;i<n;i++){ const a=tr[i],b=tr[i-K]; if(!a||!b) continue;
    let miss=0,jump=0; for(let q=i-K+1;q<=i;q++){ if(!tr[q]) miss++; else if(tr[q-1]) jump=Math.max(jump,Math.abs(tr[q][1]-tr[q-1][1])); }
    if(miss>2||jump>60) continue; const dv=a[1]-b[1]; if(dv>best){best=dv;k=i;} }
  if(k<0) return null;
  let imp=k; for(let j=k-K;j<Math.min(n,k+2*K);j++) if(tr[j]&&(!tr[imp]||tr[j][1]>tr[imp][1])) imp=j;
  const sp=new Array(n).fill(0);
  for(let i=2;i<n-2;i++){ let ok=true,v=[]; for(let q=i-2;q<i+2;q++){ if(!tr[q]||!tr[q+1]){ok=false;break;} v.push(hyp(tr[q+1][0]-tr[q][0],tr[q+1][1]-tr[q][1])); } if(ok){ v.sort((a,b)=>a-b); sp[i]=v[1]; } }
  let top=imp-Math.round(0.3*fps), lo=Math.max(0,imp-Math.round(0.5*fps)), hi=imp-Math.round(0.18*fps), m=1e9;
  for(let i=lo;i<hi;i++) if(tr[i]&&sp[i]>0&&sp[i]<m){m=sp[i];top=i;}
  if(hands){ // top = hands at their highest point before impact (same definition as the live coach)
    let hy=1e9,ht=-1; for(let i=Math.max(0,imp-Math.round(2.0*fps));i<imp-Math.round(0.18*fps);i++){ const h=hands[i]; if(h&&h[1]<hy){hy=h[1];ht=i;} }
    if(ht>=0){ top=ht; for(let i=ht;i<imp-Math.round(0.18*fps);i++){ const h=hands[i]; if(h&&h[1]<=hy+8) top=i; } } }   // end of the top hold
  // address: last still stretch before the takeaway; takeaway = first frame >10px from the address position going toward the top
  const a0=Math.max(0,imp-Math.round(2.8*fps)); let addr=null;
  for(let i=a0;i<top;i++){ if(tr[i]){ addr=i; break; } }
  let start=addr;
  if(addr!=null){ // walk forward while the head stays near its resting spot
    const ref=tr[addr]; let anchor=ref;
    for(let i=addr;i<top;i++){ if(!tr[i]) continue; if(hyp(tr[i][0]-anchor[0],tr[i][1]-anchor[1])<10){ start=i; } else if(i-start>Math.round(0.15*fps)) break; }
  }
  const fin=Math.min(n-1,imp+Math.round(1.2*fps));
  return {addr:addr??a0,start:start??a0,top,imp,fin};
}
// 6) curves per phase; short gaps (<=0.06s) bridged with a velocity-matched cubic, marked as estimated
export function curves(tr,ph,fps){
  const seg=(a,b,fill)=>{ const out=[]; let prev=null;
    for(let i=a;i<=b;i++){ const p=tr[i]; if(!p) continue;
      if(prev&&i-prev.i>1){ const g=i-prev.i;
        if(fill&&g<=Math.round(0.06*fps)+1&&Math.hypot(p[0]-prev.x,p[1]-prev.y)<90){   /* only bridge short gaps; never draw a long guessed line */
          const v0=vel(prev.i,-1,g),v1=vel(i,1,g);
          for(let s=1;s<g;s++){ const u=s/g,h00=2*u**3-3*u**2+1,h10=u**3-2*u**2+u,h01=-2*u**3+3*u**2,h11=u**3-u**2;
            out.push({i:prev.i+s,x:h00*prev.x+h10*v0[0]+h01*p[0]+h11*v1[0],y:h00*prev.y+h10*v0[1]+h01*p[1]+h11*v1[1],est:true}); } }
        else out.push(null); }
      out.push({i,x:p[0],y:p[1],est:false}); prev={i,x:p[0],y:p[1]}; }
    return out; };
  // tangent (per-gap units) from the 3 frames on the far side of the gap
  const vel=(i,dir,g)=>{ const j=i+dir*3; if(!tr[j]) return [0,0];
    return dir<0? [(tr[i][0]-tr[j][0])/3*g,(tr[i][1]-tr[j][1])/3*g] : [(tr[j][0]-tr[i][0])/3*g,(tr[j][1]-tr[i][1])/3*g]; };
  // smooth each continuous run (5-point average) and drop tiny fragments so the trail reads as one clean line
  const tidy=(arr,minRun)=>{ const out=[]; let run=[];
    const flush=()=>{ if(run.length>=minRun){ const r=run.map((q,k)=>{ let sx=0,sy=0,c=0; for(let j=Math.max(0,k-2);j<=Math.min(run.length-1,k+2);j++){ sx+=run[j].x; sy+=run[j].y; c++; } return {...q,x:sx/c,y:sy/c}; });
        if(out.length) out.push(null); out.push(...r); } run=[]; };
    for(const q of arr){ if(q) run.push(q); else flush(); } flush(); return out; };
  const mr=Math.max(4,Math.round(0.05*fps));
  return {back:tidy(seg(ph.start,ph.top,true),mr),down:tidy(seg(ph.top,ph.imp,true),3),after:tidy(seg(ph.imp,Math.min(ph.fin,ph.imp+Math.round(0.7*fps)),false),mr)};
}
// 7) plane check against two lines from the ball: address shaft line (ball->hands) and ball->shoulders
// Checkpoint frames for the buttons, from the club itself rather than from body height:
//  p2 "takeaway"      = going back, the first moment the head is as high as the hands (shaft level with the ground)
//  p6 "mid-downswing" = coming down, the moment the head drops below the hands again
// Where the head was not followed there, fall back to how far the hands have travelled between address and top height.
export function clubCheckpoints(tr,ph,pose,fps){
  const near=i=>{ for(let d=0;d<=3;d++){ if(tr[i-d]) return tr[i-d]; if(tr[i+d]) return tr[i+d]; } return null; };
  const A=pose[ph.start], T=pose[ph.top]; let p2=null,p6=null,p3=null;
  for(let i=ph.start+1;i<=ph.top;i++){ const h=near(i), P=pose[i]; if(h&&P&&h[1]<=P.hands[1]){ p2=i; break; } }
  for(let i=ph.imp-1;i>=ph.top;i--){ const h=near(i), P=pose[i]; if(h&&P&&h[1]<=P.hands[1]){ p6=Math.min(ph.imp,i+1); break; } }
  if(A&&T){ const y0=A.hands[1], y1=T.hands[1], span=y0-y1;
    if(span>20){
      if(p2==null||p2-ph.start>0.7*(ph.top-ph.start)) for(let i=ph.start;i<=ph.top;i++){ const P=pose[i]; if(P&&P.hands[1]<=y0-0.2*span){ p2=i; break; } }
      if(p6==null||ph.imp-p6>0.6*(ph.imp-ph.top)) for(let i=ph.top;i<=ph.imp;i++){ const P=pose[i]; if(P&&P.hands[1]>=y1+0.75*span){ p6=i; break; } } } }
  /* p3 = halfway up the backswing: the hands have covered 45% of their rise (about chest height, lead arm near level) */
  if(A&&T){ const y0=A.hands[1], span=y0-T.hands[1]; if(span>20) for(let i=Math.max(ph.start,p2||0)+1;i<ph.top;i++){ const P=pose[i]; if(P&&P.hands[1]<=y0-0.45*span){ p3=i; break; } } }
  if(p3!=null&&p2!=null&&p3<=p2) p3=null;
  return {p2,p3,p6};
}
// Takeaway start from the hands, not the club head: the last moment before the top at which the hands were still at
// address height (within 6% of the torso length of their lowest level). Head-based starts moved by 0.2-0.4s between
// identical swings (waggles, a head that is found late), which made tempo and the "address" frame jump around.
// "Hands at rest" by speed does not work: many golfers keep the hands drifting (waggle, forward press) until they go.
export function refineStart(ph,pose,fps){
  const A0=Math.max(0,ph.imp-Math.round(2.8*fps));
  const P0=pose[ph.top]||nearest(pose,ph.top); if(!P0) return ph;
  const tl=Math.max(1,hyp(P0.sh[0]-P0.hip[0],P0.sh[1]-P0.hip[1]));
  const ys=[]; for(let i=A0;i<ph.top;i++) if(pose[i]) ys.push(pose[i].hands[1]);
  if(ys.length<8) return ph; ys.sort((a,b)=>a-b);
  const yAddr=ys[Math.floor(ys.length*0.95)], yTop=pose[ph.top]? pose[ph.top].hands[1] : ys[0];
  if(yAddr-yTop<0.6*tl) return ph;                       // not a real backswing: leave as is
  for(let i=ph.top;i>A0;i--){ const P=pose[i]; if(P&&P.hands[1]>=yAddr-0.06*tl){
      /* Seen face-on the hands first move sideways without rising, so the height rule alone put the start ~0.3 s late
         (club already at hip height) and every "vs address" figure was measured from a moving body.
         Walk back until the hands are also back at their address x (median of the quiet stretch before). */
      let st=i; const xs=[]; for(let j=Math.max(A0,i-Math.round(1.3*fps));j<=i-Math.round(0.45*fps);j++) if(pose[j]) xs.push(pose[j].hands[0]);
      if(xs.length>=6){ xs.sort((x,y)=>x-y); const xA=xs[xs.length>>1];
        if(Math.abs(P.hands[0]-xA)>0.06*tl) for(let j=i;j>=Math.max(A0,i-Math.round(0.9*fps));j--){ const Q=pose[j]; if(Q&&Math.abs(Q.hands[0]-xA)<=0.06*tl){ st=j; break; } } }
      return Object.assign({},ph,{start:st,addr:Math.max(A0,st-Math.round(0.25*fps))}); } }
  return ph;
}
// A club head is never still in the downswing. Detections that sit on one spot there (a logo, a ball, a screen icon)
// are false: drop them instead of drawing a path or judging the plane from them.
export function dropStill(tr,ph,fps){
  const out=tr.slice();
  const run=(a,b,minT,R=7)=>{ const g=Math.max(2,Math.round(minT*fps));
    for(let i=a;i<=b;i++){ const p=tr[i]; if(!p) continue;
      for(let j=a;j<=b;j++){ if(Math.abs(j-i)<g) continue; const q=tr[j]; if(q&&hyp(p[0]-q[0],p[1]-q[1])<R){ out[i]=null; break; } } } };
  run(ph.top+Math.round(0.04*fps),ph.imp-Math.round(0.012*fps),0.035);          // downswing: fast everywhere
  return out;
}
export function plane(tr,ph,pose,fps){
  const A=ph.start, P=pose[A]||nearest(pose,A); if(!P) return null;
  // ball = club head at impact (most reliable: the head is at the ball); fall back to the address head position
  let ball=null; for(let d=0;d<=Math.round(0.015*fps)&&!ball;d++) ball=tr[ph.imp-d]||tr[ph.imp+d]||null;
  if(!ball){ const q=[]; for(let i=Math.max(0,A-Math.round(0.2*fps));i<=A;i++) if(tr[i]) q.push(tr[i]); if(q.length){ q.sort((a,b)=>a[1]-b[1]); ball=q[q.length>>1]; } }
  const hands=P.hands, sh=P.sh;
  if(!ball||ball[1]<=hands[1]) return null;   // ball must be below the hands at address
  /* The two reference lines are the ADDRESS shaft line and the line to the shoulders, so they must start where the club head
     sat at address. They started at the impact point, which with a driver is 30~40 px from the address head (ball teed up, head
     behind it). That offset alone put the whole takeaway "outside the shoulder line" once the lines were wide enough to judge. */
  const hit=ball;
  { const q=[]; for(let i=Math.max(0,ph.addr-Math.round(0.1*fps));i<=Math.min(ph.start,ph.addr+Math.round(0.05*fps));i++) if(tr[i]) q.push(tr[i]);
    if(q.length>=5){ const md=k=>q.map(v=>v[k]).sort((x,y)=>x-y)[q.length>>1], a0=[md(0),md(1)];
      if(a0[1]>hands[1]&&hyp(a0[0]-hit[0],a0[1]-hit[1])<0.35*hyp(hands[0]-hit[0],hands[1]-hit[1])) ball=a0; } }
  /* The ball lies on the ground in front of the golfer. When the head model fails on a clip (other clothes, drawn overlays, outdoor light)
     the "ball" lands on a thigh or a logo; lines and plane verdicts built on it were shown as if they were real. No plane then. */
  if(P.ank&&P.hip){ const leg=P.ank[1]-P.hip[1], tl0=hyp(P.sh[0]-P.hip[0],P.sh[1]-P.hip[1]);
    if(leg>20&&(ball[1]<P.hip[1]+0.6*leg||Math.abs(ball[0]-P.hip[0])<0.3*tl0)) return null; }
  const side=(p,q,x)=>{ const dx=q[0]-p[0],dy=q[1]-p[1],l=hyp(dx,dy); return (dx*(x[1]-p[1])-dy*(x[0]-p[0]))/l; }; // >0: above/outside
  const L=hyp(hands[0]-ball[0],hands[1]-ball[1]);
  const cls=(i)=>{ const p=tr[i]; if(!p) return null; const a=side(ball,hands,p), b=side(ball,sh,p);
    return {i,shaft:a,shoulder:b,zone: b>0? "over" : a<0? "under" : "slot"}; };
  const pick=(a,b)=>{ const r=[]; for(let i=a;i<=b;i++){ const c=cls(i); if(c&&tr[i][1]>hands[1]-0.5*L) r.push(c); } return r; };
  const back=pick(ph.start,ph.top), down=pick(ph.top,ph.imp);
  // The verdict must come from where the lines are apart. Right at the ball all three lines meet, so the last few
  // frames sit within 1-3% of both lines and the "zone" there is just noise (it flipped over/slot/under between identical swings).
  const dist=c=>hyp(tr[c.i][0]-ball[0],tr[c.i][1]-ball[1]);
  const mid=down.filter(c=>{ const d=dist(c); return d>=0.35*L&&d<=1.8*L; });   // at 240fps the head crosses this stretch in only ~8 frames
  const widthDeg=Math.abs(Math.atan2(ball[1]-sh[1],ball[0]-sh[0])-Math.atan2(ball[1]-hands[1],ball[0]-hands[0]))*180/Math.PI;
  const M=0.04*L, zoneM=c=> c.shoulder>M? "over" : c.shaft<-M? "under" : (c.shoulder<-M&&c.shaft>M)? "slot" : null;
  const sure=mid.map(zoneM).filter(Boolean);
  // a plain number that does not depend on the zone borders: how far above(+)/below(-) the address shaft line the head travels mid-downswing, % of club length
  const ms=mid.map(c=>c.shaft).sort((a,b)=>a-b), midShaft=ms.length>=3? ms[ms.length>>1]/L*100 : null;
  const hold= widthDeg<6? "narrow" : mid.length<4? "few" : sure.length<Math.max(3,0.6*mid.length)? "close" : null;
  const frac=(arr,z)=>arr.length? arr.filter(c=>c.zone===z).length/arr.length : 0;
  // takeaway is judged only if the head was really followed: enough samples that actually travel (a spot that never moves is not a club head)
  const ext=arr=>{ let x0=1e9,x1=-1e9,y0=1e9,y1=-1e9; for(const c of arr){ const p=tr[c.i]; x0=Math.min(x0,p[0]); x1=Math.max(x1,p[0]); y0=Math.min(y0,p[1]); y1=Math.max(y1,p[1]); } return arr.length? hyp(x1-x0,y1-y0) : 0; };

  const backOk=back.length>=6&&ext(back)>=0.4*L&&widthDeg>=6;
  return {ball,hit,hands,sh,L,back,down,backOk,
    takeawayOver:frac(back,"over"), downSlot:frac(down,"slot"), downOver:frac(down,"over"), downUnder:frac(down,"under"),
    nDown:mid.length, widthDeg, hold, midShaft, finalZone: hold? null : mode(sure)};
}
const mode=a=>{ const m={}; a.forEach(x=>m[x]=(m[x]||0)+1); return Object.entries(m).sort((x,y)=>y[1]-x[1])[0][0]; };
function nearest(arr,i){ for(let d=1;d<30;d++){ if(arr[i-d]) return arr[i-d]; if(arr[i+d]) return arr[i+d]; } return null; }
// 8) body turn from 3D world landmarks (rotation of shoulder / hip line around the vertical axis)
export function body(pose,ph){
  const ang=(w,a,b)=>Math.atan2(w[a][2]-w[b][2],w[a][0]-w[b][0])*180/Math.PI;
  const A=pose[ph.start]||nearest(pose,ph.start); if(!A||!A.world) return null;
  const at=i=>{ const P=pose[i]||nearest(pose,i); if(!P||!P.world) return null;
    const t=(a,b)=>((ang(P.world,a,b)-ang(A.world,a,b)+540)%360)-180;
    const chest=t(11,12),pelvis=t(23,24); return {chest,pelvis,x:chest-pelvis,ok:Math.abs(chest)<=100&&Math.abs(pelvis)<=70}; };
  return {top:at(ph.top),imp:at(ph.imp)};
}
// coarse impact for the sparse first pass: the biggest fall of the head from high (above the shoulders) to ball height within 0.25s
export function coarseImpact(tr,fps){
  const ys=tr.filter(Boolean).map(p=>p[1]).sort((a,b)=>a-b); if(ys.length<10) return -1;
  const ballY=ys[Math.floor(ys.length*0.93)], win=Math.round(0.25*fps); let best=0,bi=-1;
  for(let i=0;i<tr.length;i++){ const p=tr[i]; if(!p||p[1]<ballY-90) continue;
    let mn=1e9; for(let j=Math.max(0,i-win);j<i;j++) if(tr[j]&&tr[j][1]<mn) mn=tr[j][1];
    const fall=p[1]-mn; if(fall>best+5&&ballY-mn>220){ best=fall; bi=i; } }
  return bi;
}
// ---------- pose-only helpers (work from any camera angle) ----------
// coarse impact from the hands: biggest fall of the hands within 0.4s ending near address height
export function handsImpact(hands,fps){
  const ys=hands.filter(Boolean).map(h=>h[1]).sort((a,b)=>a-b); if(ys.length<10) return -1;
  const lowY=ys[Math.floor(ys.length*0.85)], topY=ys[Math.floor(ys.length*0.02)], win=Math.round(0.3*fps); let best=0,bi=-1;
  for(let i=0;i<hands.length;i++){ const h=hands[i]; if(!h||h[1]<lowY-0.3*(lowY-topY)) continue;
    let mn=1e9; for(let j=Math.max(0,i-win);j<i;j++) if(hands[j]&&hands[j][1]<mn) mn=hands[j][1];
    const fall=h[1]-mn; if(fall>best+2){ best=fall; bi=i; } }
  return best>0.4*(lowY-topY)? bi : -1;
}
// camera view: at address the club head (ball) sits between the feet when filmed face-on,
// but well in front of the body when filmed from behind (down the line). Falls back to shoulder width.
export function viewOf(pose,tr,i0,i1){
  const bx=[],sr=[];
  for(let i=Math.max(0,i0);i<=i1;i++){ const P=pose[i]; if(!P||!P.hip) continue;
    const tl=Math.max(1,Math.hypot(P.sh[0]-P.hip[0],P.sh[1]-P.hip[1]));
    sr.push(Math.hypot(P.ls[0]-P.rs[0],P.ls[1]-P.rs[1])/tl);
    if(tr[i]) bx.push(Math.abs(tr[i][0]-P.hip[0])/tl); }
  const med=a=>a.length? a.slice().sort((x,y)=>x-y)[a.length>>1] : null;
  const b=med(bx), r=med(sr);
  /* Shoulder width decides first: it comes from the body, which is always found. Seen from behind the shoulders overlap
     (0.04~0.20 of the torso in every down-the-line clip so far); face-on they are 0.6+. The club-head rule below failed on a
     face-on clip where a false head detection on the floor put the "ball" far from the body and the swing was judged as DTL. */
  if(r!=null&&sr.length>=3){ if(r>=0.5) return {view:"face",ball:b,ratio:r}; if(r<0.3) return {view:"dtl",ball:b,ratio:r}; }
  if(b!=null&&bx.length>=3){ if(b<0.45) return {view:"face",ball:b,ratio:r}; if(b>0.6) return {view:"dtl",ball:b,ratio:r}; }
  if(r==null) return null;
  return {view:r>0.6?"face":r<0.35?"dtl":"angle",ball:b,ratio:r};
}
export function posePhases(pose,fps,ci){
  const hy=i=>pose[i]&&pose[i].hands[1];
  let imp=ci; for(let i=Math.max(0,ci-Math.round(0.15*fps));i<=Math.min(pose.length-1,ci+Math.round(0.15*fps));i++) if(hy(i)!=null&&hy(i)>(hy(imp)??-1)) imp=i;
  let top=-1,mn=1e9; for(let i=Math.max(0,imp-Math.round(2.2*fps));i<imp-Math.round(0.18*fps);i++){ const y=hy(i); if(y!=null&&y<mn){mn=y;top=i;} }
  if(top<0) return null;
  for(let i=top;i<imp-Math.round(0.18*fps);i++){ const y=hy(i); if(y!=null&&y<=mn+8) top=i; }
  const a0=Math.max(0,imp-Math.round(2.8*fps)); const ad=pose[a0]||pose.find(Boolean); if(!ad) return null;
  const tol=0.02*Math.max(...pose.filter(Boolean).map(p=>p.hands[1]));
  let start=a0;
  for(let i=top;i>a0;i--){ const P=pose[i]; if(P&&Math.hypot(P.hands[0]-ad.hands[0],P.hands[1]-ad.hands[1])<tol){ start=i; break; } }
  return {addr:a0,start,top,imp,fin:Math.min(pose.length-1,imp+Math.round(1.2*fps))};
}
// face-on: lateral head / pelvis shift (cm, using ~40cm shoulder width as the scale) and shaft lean at impact
export function faceOn(pose,ph,tr){
  const A=pose[ph.start], T=pose[ph.top], I=pose[ph.imp]; if(!A||!T||!I||!A.nose) return null;
  const cmPerPx=40/Math.max(1,Math.hypot(A.ls[0]-A.rs[0],A.ls[1]-A.rs[1]));
  const away=Math.sign(T.hands[0]-A.hands[0])||1;           // direction away from the target
  const cm=(p,q)=>(q[0]-p[0])*away*cmPerPx;                    // + = toward the back foot (away from target)
  let lean=null; const h=tr[ph.imp];
  if(h){ const dx=(I.hands[0]-h[0])*away*-1, dy=h[1]-I.hands[1]; if(dy>0) lean=Math.atan2(dx,dy)*180/Math.PI; } // + = hands ahead of the club head
  return {headTop:cm(A.nose,T.nose),shTop:cm(A.sh,T.sh) /* centre of the shoulders: where the upper body went */,headImp:cm(A.nose,I.nose),hipTop:cm(A.hip,T.hip),hipImp:cm(A.hip,I.hip),lean,handImp:-cm(A.hands,I.hands) /* + = hands nearer the target than at address */};
}
// ---------- posture / timing details for the diagnosis (2D, from the pose) ----------
export function posture(pose,ph,fps,view,TF){
  const A=pose[ph.start]||nearest(pose,ph.start), T=pose[ph.top]||nearest(pose,ph.top), I=pose[ph.imp]||nearest(pose,ph.imp);
  if(!A||!T||!I||!A.hip) return null;
  const TL=Math.max(1,Math.hypot(A.sh[0]-A.hip[0],A.sh[1]-A.hip[1]));          // torso length (px) = scale
  const pct=v=>v/TL*100;
  // top hold: time the hands stay within 8px of their highest point
  let hy=1e9; for(let i=ph.start;i<=ph.top;i++){ const P=pose[i]; if(P&&P.hands[1]<hy) hy=P.hands[1]; }
  let hs=ph.top; for(let i=ph.start;i<=ph.top;i++){ const P=pose[i]; if(P&&P.hands[1]<=hy+8){ hs=i; break; } }
  // P2 / P6: hands at hip height going back / coming down
  let p2=null,p6=null;
  for(let i=ph.start;i<=ph.top;i++){ const P=pose[i]; if(P&&P.hands[1]<=P.hip[1]){ p2=i; break; } }
  for(let i=ph.top;i<=ph.imp;i++){ const P=pose[i]; if(P&&P.hands[1]>=P.hip[1]){ p6=i; break; } }
  const tilt=P=>Math.atan2(Math.abs(P.sh[0]-P.hip[0]),P.hip[1]-P.sh[1])*180/Math.PI;     // torso angle from vertical
  const toBall=Math.sign(A.hands[0]-A.hip[0])||1;                                       // DTL: side of the ball
  const dT=(a,b)=>TF? TF(b)-TF(a) : (b-a)/fps;   // real frame times when known: recordings have gaps, so frames are not evenly spaced
  const o={TL,holdStart:hs,p2,p6,back:dT(ph.start,hs),hold:dT(hs,ph.top),down:dT(ph.top,ph.imp),
    headTop:[pct(T.nose[0]-A.nose[0]),pct(T.nose[1]-A.nose[1])], headImp:[pct(I.nose[0]-A.nose[0]),pct(I.nose[1]-A.nose[1])],
    tiltAddr:tilt(A),tiltTop:tilt(T),tiltImp:tilt(I)};
  if(view==="dtl"){ o.hipFwdImp=pct((I.hip[0]-A.hip[0])*toBall); o.hipFwdTop=pct((T.hip[0]-A.hip[0])*toBall); o.headFwdImp=pct((I.nose[0]-A.nose[0])*toBall); }
  return o;
}

// ---------- comparison metrics (pose only: works for any club / background) ----------
// All lengths are % of the golfer's own torso length, so two different bodies / zoom levels can be compared.
// dir: +1/-1 so that "+" always means toward the ball (down-the-line) or toward the target (face-on).
export function cmpMetrics(pose,ph,fps,view,TF){
  const g=i=>pose[i]||nearest(pose,i), A=g(ph.start), T=g(ph.top), I=g(ph.imp);
  if(!A||!T||!I||!A.hip||!A.nose) return null;
  const tl=Math.max(1,hyp(A.sh[0]-A.hip[0],A.sh[1]-A.hip[1]));
  const dir=view==="dtl"? (Math.sign(A.hands[0]-A.hip[0])||1) : (-Math.sign(T.hands[0]-A.hands[0])||1);
  const tilt=P=>Math.atan2((P.sh[0]-P.hip[0])*dir,P.hip[1]-P.sh[1])*180/Math.PI;
  const at=P=>({tilt:tilt(P),handH:(P.hip[1]-P.hands[1])/tl*100,handX:(P.hands[0]-P.hip[0])*dir/tl*100,
    headX:(P.nose[0]-A.nose[0])*dir/tl*100,headY:(A.nose[1]-P.nose[1])/tl*100,hipX:(P.hip[0]-A.hip[0])*dir/tl*100});
  const back=TF? TF(ph.top)-TF(ph.start) : (ph.top-ph.start)/fps, down=TF? TF(ph.imp)-TF(ph.top) : (ph.imp-ph.top)/fps;
  return {tempo:{back,down,ratio:down>0?back/down:null},addr:{tilt:tilt(A)},top:at(T),imp:at(I)};
}

// Backswing in four steps (address, takeaway, halfway, top): where the head, the centre of the shoulders, the pelvis and the hands
// are relative to address, in % of the torso length, and which of them left its address place first.
// x: face-on + = away from the target; down-the-line + = toward the ball. y: + = up. One camera: image-plane shifts, not 3D distances.
export function backswingSteps(pose,ph,po,view,TF,fps){
  const near=i=>{ for(let d=0;d<=6;d++){ if(pose[i-d]) return pose[i-d]; if(pose[i+d]) return pose[i+d]; } return null; };
  const A=near(ph.start), T=near(ph.top); if(!A||!T||!A.nose) return null;
  const tl=hyp(A.sh[0]-A.hip[0],A.sh[1]-A.hip[1]); if(!(tl>10)) return null;
  const dir=view==="dtl"? (Math.sign(A.hands[0]-A.hip[0])||1) : (Math.sign(T.hands[0]-A.hands[0])||1);
  const PARTS=[["head","nose"],["sh","sh"],["hip","hip"],["hands","hands"]];
  const off=P=>{ const o={}; for(const [k,src] of PARTS) o[k]=[(P[src][0]-A[src][0])*dir/tl*100,(A[src][1]-P[src][1])/tl*100]; return o; };
  const steps=[["어드레스",ph.start],["테이크백",po&&po.p2],["백스윙 중간",po&&po.p3],["탑",ph.top]].filter(q=>q[1]!=null).map(([name,i])=>{ const P=near(i); return P?Object.assign({name,i},off(P)):null; }).filter(Boolean);
  // first departure: the first pose sample after the start whose sideways shift exceeds TH and stays beyond it on the next two samples
  const TH=5, ix=[]; for(let i=ph.start;i<=ph.top;i++) if(pose[i]&&pose[i].nose) ix.push(i);
  const tOf=i=>TF? TF(i)-TF(ph.start) : (i-ph.start)/fps, phaseOf=i=>po&&po.p2!=null&&i<=po.p2?"테이크백": po&&po.p3!=null&&i<=po.p3?"백스윙 중간":"탑 직전";
  const onset={};
  for(const [k,src] of PARTS.slice(0,3)){ onset[k]=null; let end=0; for(const q of steps) if(Math.abs(q[k][0])>Math.abs(end)) end=q[k][0]; const sg=Math.sign(end)||1;   // direction of the largest shift of the four steps
    for(let j=0;j<ix.length;j++){ const v=q=>(pose[ix[q]][src][0]-A[src][0])*dir/tl*100*sg;
      if(v(j)>TH&&(j+1>=ix.length||v(j+1)>TH)&&(j+2>=ix.length||v(j+2)>TH)){ onset[k]={i:ix[j],t:tOf(ix[j]),phase:phaseOf(ix[j]),sign:sg}; break; } } }
  return {view,TH,steps,onset};
}

// A detection that stays in one place while the hands travel is not the club head: the head is on the end of the club, so it can
// only stand still when the hands do (address, the pause at the top). Catches wall banners, signs and lamps that the head model
// fires on for part of the clip only - staticFilter misses those because they are not present from start to end.
export function stillWhileHandsMove(pts,pose){
  const near=i=>{ for(let d=0;d<=8;d++){ if(pose[i-d]) return pose[i-d]; if(pose[i+d]) return pose[i+d]; } return null; };
  const cell=new Map();
  pts.forEach((p,i)=>p&&p.forEach(([c,x,y])=>{ for(const ox of [0,8]){ const k=ox+":"+Math.round((x+ox)/16)+","+Math.round((y+ox)/16); (cell.get(k)||cell.set(k,[]).get(k)).push([i,x,y]); } }));
  const cen=[];
  for(const v of cell.values()){ if(v.length<6) continue; let x0=1e9,x1=-1e9,y0=1e9,y1=-1e9,tl=0,m=0;
    for(const [i] of v){ const P=near(i); if(!P) continue; m++; x0=Math.min(x0,P.hands[0]); x1=Math.max(x1,P.hands[0]); y0=Math.min(y0,P.hands[1]); y1=Math.max(y1,P.hands[1]); tl=hyp(P.sh[0]-P.hip[0],P.sh[1]-P.hip[1]); }
    if(m>=6&&tl>10&&hyp(x1-x0,y1-y0)>0.5*tl) cen.push([v.reduce((a,z)=>a+z[1],0)/v.length,v.reduce((a,z)=>a+z[2],0)/v.length]); }
  if(!cen.length) return pts;
  return pts.map(p=>p? p.filter(([c,x,y])=>cen.every(([a,b])=>hyp(x-a,y-b)>14)) : p);
}
