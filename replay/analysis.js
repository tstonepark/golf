// Swing analysis: tracking, phases, plane check, body metrics. Pure functions (no DOM).
// Coordinates are in the 540x960 analysis frame.
const hyp=Math.hypot;
const dif=(a,b)=>Math.abs(((a-b+540)%360)-180);

// 1) remove fixed background objects (seen at the same spot in both the first and last 20% of the clip)
export function staticFilter(pts){
  const N=pts.length, cell=new Map();
  pts.forEach((p,i)=>p&&p.forEach(([c,x,y])=>{const k=Math.round(x/16)+","+Math.round(y/16);(cell.get(k)||cell.set(k,[]).get(k)).push([i,x,y]);}));
  const cen=[];
  for(const v of cell.values()){ const fr=v.map(z=>z[0]);
    if(v.length>0.015*N && Math.min(...fr)<0.2*N && Math.max(...fr)>0.8*N)
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
export function clean(tr){
  const n=tr.length;
  return tr.map((p,i)=>{ if(!p) return null; for(let j=Math.max(0,i-4);j<Math.min(n,i+5);j++){ if(j!==i&&tr[j]&&Math.abs(tr[j][0]-p[0])+Math.abs(tr[j][1]-p[1])<60) return p; } return null; });
}
// 4) club length check against hands (needs pose): head must be 0.45..1.25 L from the hands
export function clubLengthFilter(tr,hands){
  const ds=[]; tr.forEach((p,i)=>{ if(p&&hands[i]) ds.push(hyp(p[0]-hands[i][0],p[1]-hands[i][1])); });
  if(ds.length<10) return {tr,L:null};
  ds.sort((a,b)=>a-b); const L=ds[Math.floor(ds.length*0.85)];
  return {L,tr:tr.map((p,i)=>{ if(!p||!hands[i]) return p; const d=hyp(p[0]-hands[i][0],p[1]-hands[i][1]); return (d>0.45*L&&d<1.25*L)?p:null; })};
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
  let top=imp-Math.round(0.3*fps), lo=Math.max(0,imp-Math.round(0.5*fps)), hi=imp-Math.round(0.08*fps), m=1e9;
  for(let i=lo;i<hi;i++) if(tr[i]&&sp[i]>0&&sp[i]<m){m=sp[i];top=i;}
  if(hands){ // top = hands at their highest point before impact (same definition as the live coach)
    let hy=1e9,ht=-1; for(let i=Math.max(0,imp-Math.round(2.0*fps));i<imp-Math.round(0.1*fps);i++){ const h=hands[i]; if(h&&h[1]<hy){hy=h[1];ht=i;} }
    if(ht>=0){ top=ht; for(let i=ht;i<imp-Math.round(0.1*fps);i++){ const h=hands[i]; if(h&&h[1]<=hy+8) top=i; } } }   // end of the top hold
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
        if(fill&&g<=Math.round(0.06*fps)+1){ const v0=vel(prev.i,-1,g),v1=vel(i,1,g);
          for(let s=1;s<g;s++){ const u=s/g,h00=2*u**3-3*u**2+1,h10=u**3-2*u**2+u,h01=-2*u**3+3*u**2,h11=u**3-u**2;
            out.push({i:prev.i+s,x:h00*prev.x+h10*v0[0]+h01*p[0]+h11*v1[0],y:h00*prev.y+h10*v0[1]+h01*p[1]+h11*v1[1],est:true}); } }
        else out.push(null); }
      out.push({i,x:p[0],y:p[1],est:false}); prev={i,x:p[0],y:p[1]}; }
    return out; };
  // tangent (per-gap units) from the 3 frames on the far side of the gap
  const vel=(i,dir,g)=>{ const j=i+dir*3; if(!tr[j]) return [0,0];
    return dir<0? [(tr[i][0]-tr[j][0])/3*g,(tr[i][1]-tr[j][1])/3*g] : [(tr[j][0]-tr[i][0])/3*g,(tr[j][1]-tr[i][1])/3*g]; };
  return {back:seg(ph.start,ph.top,true),down:seg(ph.top,ph.imp,true),after:seg(ph.imp,ph.fin,false)};
}
// 7) plane check against two lines from the ball: address shaft line (ball->hands) and ball->shoulders
export function plane(tr,ph,pose,fps){
  const A=ph.start, P=pose[A]||nearest(pose,A); if(!P||!tr[A]) return null;
  const ball=tr[A], hands=P.hands, sh=P.sh;
  const side=(p,q,x)=>{ const dx=q[0]-p[0],dy=q[1]-p[1],l=hyp(dx,dy); return (dx*(x[1]-p[1])-dy*(x[0]-p[0]))/l; }; // >0: above/outside
  const L=hyp(hands[0]-ball[0],hands[1]-ball[1]);
  const cls=(i)=>{ const p=tr[i]; if(!p) return null; const a=side(ball,hands,p), b=side(ball,sh,p);
    return {i,shaft:a,shoulder:b,zone: b>0? "over" : a<0? "under" : "slot"}; };
  const pick=(a,b)=>{ const r=[]; for(let i=a;i<=b;i++){ const c=cls(i); if(c&&tr[i][1]>hands[1]-0.5*L) r.push(c); } return r; };
  const back=pick(ph.start,ph.top), down=pick(ph.top,ph.imp);
  const lastN=down.filter(c=>c.i>=ph.imp-Math.round(0.05*fps));
  const frac=(arr,z)=>arr.length? arr.filter(c=>c.zone===z).length/arr.length : 0;
  return {ball,hands,sh,L,back,down,
    takeawayOver:frac(back,"over"), downSlot:frac(down,"slot"), downOver:frac(down,"over"), downUnder:frac(down,"under"),
    finalZone: lastN.length? mode(lastN.map(c=>c.zone)) : null};
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
