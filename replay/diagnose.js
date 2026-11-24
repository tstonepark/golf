// Turns the measurements into a prioritized diagnosis. Pure function, no DOM.
// Every item: {key, area, sev: "bad"|"warn"|"ok"|"na", title, value, ref, why, drill, conf: "높음"|"보통"|"낮음"}
// Reference ranges are typical tour / teaching values; they are guides, not absolute rules.
const f1=x=>x.toFixed(1), f2=x=>x.toFixed(2), r0=x=>Math.round(x);
export function diagnose(R){
  const it=[], {ph,fps,pl,bd,po,fo,view}=R;
  const add=o=>it.push(o);
  // ---- tempo ----
  if(po){
    const ratio=po.back/po.down;
    /* The top pause is not judged: some teachers deliberately teach waiting at the top ("두 박자 쉬고 때리기"), tour swings barely pause.
       It was flagged as the #1 fault on every swing of a golfer who pauses on purpose. Shown as a number; consistency is what matters. */
    add({key:"hold",area:"템포",sev:"na",title:"참고: 탑 멈춤 "+f2(po.hold)+"초",
      value:f2(po.hold)+"초",ref:"",conf:"높음",
      why:"탑에서 손이 최고점 근처에 머문 시간입니다. 탑에서 기다렸다 치는 방식도 있어 좋고 나쁨은 판정하지 않습니다. 아래 '스윙 기록 비교'에서 스윙마다 비슷한지(일정한지)를 보세요.",
      drill:""});
    add({key:"back",area:"템포",sev:po.back>1.4?"warn":po.back<0.55?"warn":"ok",title:po.back>1.4?"백스윙이 느립니다":po.back<0.55?"백스윙이 급합니다":"백스윙 속도 적정",
      value:f2(po.back)+"초",ref:"0.7~1.1초",conf:"높음",why:"테이크백 시작부터 손이 최고점에 닿을 때까지의 시간입니다.",drill:"하나-둘-셋(백스윙) · 넷(다운) 박자로 연습."});
    add({key:"down",area:"템포",sev:po.down>0.36?"warn":"ok",title:po.down>0.36?"다운스윙이 느립니다":"다운스윙 시간 적정",
      value:f2(po.down)+"초",ref:"0.22~0.32초",conf:"높음",why:"탑에서 임팩트까지의 시간입니다.",drill:""});
    add({key:"ratio",area:"템포",sev:ratio>4.5?"warn":ratio<2.2?"warn":"ok",title:"템포 비율 "+f1(ratio)+" : 1",value:f1(ratio)+" : 1",ref:"2.5~3.5 : 1",conf:"높음",
      why:"백스윙 ÷ 다운스윙(탑에서 멈춘 시간은 빼고 계산).",drill:""});
  }
  // ---- plane (down-the-line only) ----
  if(view==="dtl"){
    if(pl){
      if(pl.backOk===false) add({key:"takeaway",area:"궤도",sev:"na",title:"테이크백 궤도 판정 보류",value:pl.hold==="narrow"?"두 선 간격 좁음":"헤드 추적 부족",ref:"",conf:"낮음",why:pl.hold==="narrow"?"샤프트선과 어깨선 사이가 좁아 이 영상에서는 궤도를 가를 수 없습니다.":"테이크백 구간에서 헤드를 충분히 따라가지 못했습니다. 판정하지 않습니다.",drill:""});
      else add({key:"takeaway",area:"궤도",sev:pl.takeawayOver>0.5?"warn":"ok",title:pl.takeawayOver>0.5?"테이크백을 바깥으로 들어 올립니다":"테이크백 궤도 양호",
        value:r0(pl.takeawayOver*100)+"% 구간이 어깨선 밖",ref:"두 선 사이",conf:"보통",
        why:"허리 높이까지 헤드가 공-어깨선보다 바깥(위)으로 올라갑니다. 손과 팔로 들어 올릴 때 생깁니다.",
        drill:"테이크백에서 헤드가 손보다 바깥에 있되 샤프트가 발끝 선과 평행이 되는지 확인. 정렬 스틱을 타깃 라인에 놓고 그 위로 헤드를 빼기."});
      if(pl.finalZone){ const z=pl.finalZone;
        add({key:"entry",area:"궤도",sev:z==="over"?"bad":z==="under"?"warn":"ok",
          title:z==="over"?"아웃-인(엎어 치기)으로 들어옵니다":z==="under"?"안쪽 아래에서 들어옵니다(인-아웃)":"임팩트 진입 궤도 양호",
          value:z==="over"?"어깨선 위":z==="under"?"샤프트선 아래":"두 선 사이",ref:"두 선 사이",conf:"보통",
          why:z==="over"?"다운스윙 헤드가 공-어깨선보다 위에서 내려옵니다. 슬라이스·당겨치기의 흔한 원인입니다.":z==="under"?"헤드가 어드레스 샤프트선보다 아래로 떨어져 안쪽에서 들어옵니다. 심하면 푸시·훅, 뒤땅이 나옵니다. 화면(2D) 기준이라 정도는 카메라 위치에 따라 달라집니다.":"다운스윙이 두 기준선 사이로 내려옵니다.",
          drill:z==="over"?"탑에서 오른 팔꿈치를 옆구리로 떨어뜨린 뒤 회전(펌프 드릴).":z==="under"?"하체 회전을 멈추지 않고 가슴이 타깃을 향할 때까지 돌리기. 임팩트 백을 몸 왼쪽에 두고 회전으로 치기.":""});
      } else { const off=pl.midShaft, offTxt=off==null?"":" 참고 수치: 다운스윙 중간에 헤드가 어드레스 샤프트선보다 "+Math.abs(off).toFixed(0)+"% "+(off>=0?"위":"아래")+"로 지나갔습니다(클럽 길이 대비). 스윙끼리 이 숫자를 비교해 보세요.";
        const why={narrow:"이 자세·각도에서는 샤프트선과 어깨선 사이가 "+Math.round(pl.widthDeg||0)+"°로 좁아, 헤드가 두 선 사이인지 밖인지를 오차 없이 가를 수 없습니다. 그래서 엎어 침/정상/인-아웃 판정은 내지 않습니다.",
          few:"다운스윙 중간(허리~가슴 높이)에서 헤드를 충분히 따라가지 못했습니다. 공 바로 앞에서는 세 선이 모여 판정이 무의미하므로 판정하지 않습니다.",
          close:"헤드가 선에 너무 가까이 지나가 어느 쪽인지 가를 수 없습니다(오차 범위)."}[pl.hold]||"헤드 추적 부족";
        add({key:"entry",area:"궤도",sev:"na",title:"임팩트 진입 궤도 판정 보류",value:off!=null?("샤프트선 "+(off>=0?"위 ":"아래 ")+Math.abs(off).toFixed(0)+"%"):(pl.hold==="narrow"?"두 선 간격 좁음":"헤드 "+(pl.nDown||0)+"장면"),ref:pl.hold==="narrow"?"두 선 사이 6° 이상 필요":"중간 구간 4장면 이상 필요",conf:"낮음",why:why+offTxt,drill:""}); }
    }
    if(po&&po.hipFwdImp!=null){ const v=po.hipFwdImp;
      add({key:"ee",area:"자세",sev:v>12?"bad":v>6?"warn":"ok",title:v>6?"임팩트 때 엉덩이가 공 쪽으로 나옵니다(얼리 익스텐션)":"엉덩이 위치 유지 양호",
        value:(v>=0?"공 쪽 ":"뒤로 ")+r0(Math.abs(v))+"%",ref:"공 쪽 6% 이내 (몸통 길이 대비)",conf:"보통",
        why:"어드레스 때 엉덩이 위치에서 공 쪽으로 밀려 나오면 팔이 지나갈 공간이 없어져 상체가 일어서고 손으로 맞추게 됩니다.",
        drill:"엉덩이를 벽이나 의자에 댄 채로, 임팩트까지 왼쪽 엉덩이가 벽에 닿아 있게 스윙."}); }
    if(po){ const d=po.tiltAddr-po.tiltImp;
      add({key:"rise",area:"자세",sev:d>12?"bad":d>6?"warn":"ok",title:d>6?"임팩트 때 상체가 일어섭니다":"상체 숙임 각도 유지 양호",
        value:(d>=0?"":"더 숙임 ")+r0(Math.abs(d))+"°"+(d>=0?" 일어섬":""),ref:"6° 이내",conf:"보통",
        why:"어드레스 때 만든 상체 숙임 각도가 임팩트에서 얼마나 달라졌는지입니다.",drill:"머리를 벽에 가볍게 대고 그 높이를 유지하며 연습 스윙."}); }
  }
  // ---- head ----
  if(po){ const hx=Math.abs(po.headTop[0]), up=-po.headImp[1];
    add({key:"headTop",area:"자세",sev:hx>18?"bad":hx>10?"warn":"ok",title:hx>10?"백스윙에서 머리가 많이 움직입니다":"백스윙 머리 고정 양호",
      value:r0(hx)+"%",ref:"10% 이내 (몸통 길이 대비, 약 5cm)",conf:"높음",why:"어드레스 대비 탑에서 머리의 좌우(화면 기준) 이동량입니다.",drill:"그림자나 거울로 머리 위치를 보며 하프 스윙."});
    add({key:"headUp",area:"자세",sev:up>14?"bad":(up>7||up<-10)?"warn":"ok",title:up>7?"임팩트 때 머리가 들립니다":up<-10?"임팩트 때 머리가 많이 내려갑니다":"임팩트 머리 높이 유지 양호",
      value:(up>=0?"위로 ":"아래로 ")+r0(Math.abs(up))+"%",ref:"위로 7% · 아래로 10% 이내",conf:"높음",why:"어드레스 대비 임팩트에서 머리 높이 변화입니다. 들리면 탑핑·방향 손실이, 많이 내려가면 뒤땅이 생깁니다.",drill:up<-10?"어드레스 때 무릎·허리 각도를 유지한 채 하프 스윙(주저앉지 않기).":"임팩트 후 1초간 공이 있던 자리를 보는 연습."}); }
  // ---- body rotation (single-camera 3D estimate) ----
  if(bd&&bd.top&&bd.top.ok){ const c=Math.abs(bd.top.chest), p=Math.abs(bd.top.pelvis), x=Math.abs(bd.top.x);
    add({key:"chest",area:"회전",sev:"na",title:"참고: 백스윙 어깨 회전 "+r0(c)+"°",value:r0(c)+"°",ref:"80~100°",conf:"낮음",why:"탑에서 가슴이 어드레스 대비 돌아간 각도입니다. 카메라 1대 추정값이라 같은 스윙끼리도 수십 도씩 달라집니다. 판정에 넣지 않고 숫자만 참고로 보여 줍니다.",drill:"클럽을 어깨에 걸치고 왼 어깨가 턱 밑까지 오도록 회전."});
    add({key:"pelvis",area:"회전",sev:"na",title:"참고: 백스윙 골반 회전 "+r0(p)+"°",value:r0(p)+"°",ref:"35~50°",conf:"낮음",why:"탑에서 골반이 돌아간 각도입니다. 카메라 1대 추정값이라 같은 스윙끼리도 수십 도씩 달라집니다. 판정에 넣지 않고 숫자만 참고로 보여 줍니다.",drill:""});
    add({key:"xf",area:"회전",sev:"na",title:"참고: X-팩터(상·하체 꼬임) "+r0(x)+"°",value:r0(x)+"°",ref:"35~50°",conf:"낮음",why:"가슴 회전 − 골반 회전. 꼬임이 클수록 힘을 모으기 좋습니다.",drill:""});
    if(bd.imp&&bd.imp.ok){ const sg=Math.sign(bd.top.chest)||-1, open=-bd.imp.pelvis*sg;      // + = open toward target
      add({key:"impPelvis",area:"회전",sev:"na",title:"참고: 임팩트 골반 "+(open>=0?r0(open)+"° 열림":r0(-open)+"° 닫힘"),
        value:open>=0?r0(open)+"° 열림":r0(-open)+"° 닫힘",ref:"30~45° 열림",conf:"낮음",
        why:"임팩트 순간 골반이 타깃 쪽으로 열린 각도입니다. 덜 열리면 하체가 리드하지 못하고 팔로 치는 스윙이 됩니다. 카메라 1대 추정값이라 같은 스윙끼리도 수십 도씩 달라집니다. 판정에 넣지 않고 숫자만 참고로 보여 줍니다.",
        drill:"다운스윙을 왼 무릎·왼 엉덩이가 먼저 여는 느낌으로 시작(스텝 드릴, 임팩트 자세 멈춤 연습)."}); }
  } else if(bd) add({key:"chest",area:"회전",sev:"na",title:"몸 회전 측정 이상",value:"-",ref:"",conf:"낮음",why:"3D 추정값이 사람 몸으로 불가능한 범위라 표시하지 않습니다.",drill:""});
  // ---- face-on ----
  if(view!=="dtl"&&fo){ const ok=v=>v!=null&&Math.abs(v)<=30;
    if(ok(fo.hipTop)) add({key:"sway",area:"자세",sev:fo.hipTop>8?"warn":"ok",title:fo.hipTop>8?"백스윙에서 골반이 뒤로 밀립니다(스웨이)":"백스윙 골반 좌우 유지 양호",value:r0(fo.hipTop)+"cm",ref:"6cm 이내",conf:"보통",why:"탑에서 골반이 타깃 반대쪽으로 이동한 거리(어깨너비 40cm 가정).",drill:"오른발 바깥에 공이나 스틱을 두고 오른 무릎이 닿지 않게 회전."});
    if(ok(fo.hipImp)) add({key:"shift",area:"자세",sev:fo.hipImp>0?"warn":"ok",title:fo.hipImp>0?"임팩트 때 체중이 뒤에 남습니다":"임팩트 체중 이동 양호",value:r0(Math.abs(fo.hipImp))+"cm "+(fo.hipImp>0?"뒤":"타깃 쪽"),ref:"타깃 쪽으로 이동",conf:"보통",why:"임팩트 때 골반 위치(어드레스 대비).",drill:"왼발에 체중 80%를 싣고 피니시에서 3초 멈추기."});
    /* shaft lean is an iron criterion; with a driver (or no club chosen) it is shown as a number only */
    if(fo.lean!=null){ const lv=r0(Math.abs(fo.lean))+"° "+(fo.lean>=0?"손이 앞":"헤드가 앞");
      if(R.club==="iron") add({key:"lean",area:"임팩트",sev:fo.lean<0?"warn":"ok",title:fo.lean<0?"임팩트 때 헤드가 손보다 앞섭니다(스쿠핑)":"핸드 퍼스트 임팩트",value:lv,ref:"아이언: 손이 5~10° 앞",conf:"보통",why:"임팩트 순간 샤프트 기울기입니다.",drill:"임팩트 백 치기, 펀치 샷."});
      else add({key:"lean",area:"임팩트",sev:"na",title:"참고: 임팩트 샤프트 기울기 "+lv,value:lv,ref:R.club==="driver"?"드라이버: 거의 수직(0° 근처)":"클럽을 고르면 판정",conf:"보통",why:"임팩트 순간 샤프트 기울기입니다. 아이언은 손이 앞서야 하고, 드라이버는 거의 수직으로 맞습니다. 정면에서 조금만 비스듬히 찍어도 값이 크게 달라져 숫자만 참고로 보여 줍니다.",drill:""}); }
  }
  /* impact positions that depend on the club (face-on). Thresholds are first guesses from lesson material, not calibrated. */
  if(view!=="dtl"&&fo){ const ok=v=>v!=null&&Math.abs(v)<=40, club=R.club, hd=fo.headImp, hn=fo.handImp;
    if(!club) add({key:"club",area:"임팩트",sev:"na",title:"클럽을 고르면 임팩트 머리·손 위치를 판정합니다",value:"클럽 선택 안 함",ref:"",conf:"보통",why:"아이언과 드라이버는 임팩트 때 머리·손 위치의 정답이 달라서, 영상 아래 「클럽」 버튼으로 골라야 판정할 수 있습니다.",drill:""});
    else{ const cn=club==="driver"?"드라이버":"아이언";
      if(ok(hd)){ const v=r0(Math.abs(hd))+"cm "+(hd>=0?"뒤":"타깃 쪽");
        if(club==="driver") add({key:"headBehind",area:"임팩트",sev:hd<-3?"warn":"ok",title:hd<-3?"임팩트 때 머리가 타깃 쪽으로 나갑니다":"임팩트 때 머리가 공 뒤에 남아 있습니다",value:v,ref:"드라이버: 어드레스 자리 또는 그 뒤",conf:"보통",why:"드라이버는 머리를 뒤에 둔 채 올려 쳐야 합니다. 머리가 타깃 쪽으로 따라 나가면 엎어 치거나 깎여 맞습니다. (기준은 잠정값)",drill:"고개를 살짝 오른쪽으로 돌려 둔 채, 임팩트까지 오른쪽 귀가 공 뒤에 있도록 스윙."});
        else add({key:"headBehind",area:"임팩트",sev:Math.abs(hd)>8?"warn":"ok",title:hd>8?"임팩트 때 머리가 뒤에 많이 남습니다":hd<-8?"임팩트 때 머리가 타깃 쪽으로 나갑니다":"임팩트 머리 위치 양호",value:v,ref:"아이언: 어드레스 자리 ±8cm",conf:"보통",why:"아이언은 머리가 어드레스 자리 근처에 있어야 공을 먼저 맞힙니다. 뒤에 많이 남으면 뒤땅·탑볼이 납니다. (기준은 잠정값)",drill:"왼발에 체중을 실은 채 하프 스윙으로 공 앞 잔디를 치는 연습."}); }
      if(ok(hn)){ const v=r0(Math.abs(hn))+"cm "+(hn>=0?"타깃 쪽":"뒤");
        if(club==="iron") add({key:"handFwd",area:"임팩트",sev:hn<3?"warn":"ok",title:hn<3?"임팩트 때 손이 앞서지 못합니다":"임팩트 때 손이 앞서 있습니다(핸드 퍼스트)",value:v,ref:"아이언: 어드레스보다 타깃 쪽 3cm 이상",conf:"보통",why:"아이언은 임팩트 때 손이 어드레스보다 타깃 쪽에 있어야 눌러 맞습니다. (기준은 잠정값)",drill:"임팩트 백 치기, 펀치 샷."});
        else add({key:"handFwd",area:"임팩트",sev:"na",title:"참고: 임팩트 손 위치 "+v,value:v,ref:"드라이버: 어드레스 자리 근처",conf:"보통",why:"드라이버는 손이 많이 앞서지 않고 어드레스 자리 근처에서 맞습니다. 숫자만 참고로 보여 줍니다.",drill:""}); } } }
  const rank={bad:0,warn:1,na:2,ok:3};
  const issues=it.filter(x=>x.sev==="bad"||x.sev==="warn").sort((a,b)=>rank[a.sev]-rank[b.sev]||({"높음":0,"보통":1,"낮음":2})[a.conf]-({"높음":0,"보통":1,"낮음":2})[b.conf]);
  return {items:it,issues,good:it.filter(x=>x.sev==="ok")};
}
// compact record for the swing history
export function summary(R,D){
  const po=R.po||{}, bd=R.bd, ok=bd&&bd.top&&bd.top.ok;
  return {t:Date.now(),name:R.name||"",view:R.view,fps:Math.round(R.fps),back:po.back,hold:po.hold,down:po.down,
    entry:R.pl&&R.pl.finalZone||null,takeawayOver:R.pl?R.pl.takeawayOver:null,chest:ok?Math.abs(bd.top.chest):null,pelvis:ok?Math.abs(bd.top.pelvis):null,
    ee:po.hipFwdImp??null,headUp:po.headImp?-po.headImp[1]:null,issues:D.issues.map(x=>x.key)};
}
