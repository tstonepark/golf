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
    /* Backswing time and the tempo ratio are style, not faults, for a golfer who swings back slowly and waits at the top on purpose:
       only "rushed" is still flagged. Slow is shown as a number. */
    add({key:"back",area:"템포",sev:po.back<0.55?"warn":po.back>1.4?"na":"ok",title:po.back<0.55?"백스윙이 급합니다":po.back>1.4?"참고: 백스윙 "+f2(po.back)+"초(천천히 올리는 편)":"백스윙 속도 적정",
      value:f2(po.back)+"초",ref:"투어 평균 0.7~1.1초",conf:"높음",why:"테이크백 시작(손이 어드레스 자리를 떠나는 순간)부터 손이 최고점에 닿을 때까지의 시간입니다. 천천히 올리는 것은 결점으로 보지 않고 숫자만 보여 줍니다.",drill:po.back<0.55?"하나-둘-셋(백스윙) · 넷(다운) 박자로 연습.":""});
    add({key:"down",area:"템포",sev:po.down>0.36?"warn":"ok",title:po.down>0.36?"다운스윙이 느립니다":"다운스윙 시간 적정",
      value:f2(po.down)+"초",ref:"0.22~0.32초",conf:"높음",why:"탑에서 임팩트까지의 시간입니다.",drill:""});
    add({key:"ratio",area:"템포",sev:ratio<2.2?"warn":ratio>4.5?"na":"ok",title:(ratio>4.5?"참고: ":"")+"템포 비율 "+f1(ratio)+" : 1",value:f1(ratio)+" : 1",ref:"투어 평균 2.5~3.5 : 1",conf:"높음",
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
  /* WHY the head moves. Face-on, when the centre of the shoulders travels almost as far as the head, the head is not the fault:
     the whole upper body slides away from the target instead of turning. Then "keep the head still" is the wrong cue - it stiffens
     the turn. Say so on the head item, with the three shifts and the order to fix them in. */
  if(view!=="dtl"&&fo&&fo.shTop!=null&&fo.headTop!=null){ const h=it.find(x=>x.key==="headTop"), hd=fo.headTop, sh=fo.shTop, hp=fo.hipTop;
    if(h&&h.sev!=="ok"&&hd>8&&sh>=0.6*hd){ h.title="백스윙에서 상체가 통째로 오른쪽으로 밀립니다(머리만의 문제가 아님)";
      h.value="머리 "+r0(hd)+"cm · 어깨 중심 "+r0(sh)+"cm · 골반 "+r0(hp)+"cm (타깃 반대쪽)"; h.ref="머리 5cm 이내 · 어깨 중심·골반은 거의 제자리";
      h.why="머리가 움직인 만큼 어깨 중심도 같이 갔습니다. 머리만 돈 것이 아니라 몸통이 회전 대신 옆으로 이동한 것입니다. 머리를 억지로 고정하려 하면 몸이 굳어 백스윙이 더 답답해지니, 회전은 그대로 두고 옆으로 가는 양만 줄이는 것이 목표입니다. (어깨너비 40cm 가정)";
      h.drill="순서: ① 오른쪽 골반을 뒤로 빼며 돌리기(오른쪽 엉덩이가 벽 쪽으로) → ② 그 위에서 몸통 회전 → ③ 하프 백스윙에서 멈춰 머리·골반 위치 확인 후 천천히 탑까지(10회 × 3세트). 머리 고정 연습은 나중에."; h.cause="body"; } }
  /* Down the line: in the backswing the trail hip should go back (away from the ball) to make room. Positive = the pelvis came
     toward the ball instead. One tour reference measured -2 with the same code. */
  if(view==="dtl"&&R.M&&R.M.top&&R.M.top.hipX!=null){ const v=R.M.top.hipX;
    add({key:"hipDepth",area:"자세",sev:v>8?"bad":v>3?"warn":"ok",title:v>3?"백스윙 때 골반이 공 쪽으로 나옵니다(오른쪽 골반이 뒤로 빠지지 않음)":"백스윙 골반 깊이 양호",
      value:(v>=0?"공 쪽 ":"뒤로 ")+r0(Math.abs(v))+"%",ref:"제자리 또는 뒤로 (기준 프로 영상: 뒤로 2%)",conf:"보통",
      why:"탑에서 골반 중심이 어드레스보다 공 쪽으로 왔는지(몸통 길이 대비)입니다. 오른쪽 골반이 뒤로 돌아 빠져야 팔이 내려올 공간이 생깁니다. 공 쪽으로 나오면 다운스윙에서 엉덩이가 더 나오고 상체가 일어서기 쉽습니다. 카메라 1대 추정이라 오차가 있습니다.",
      drill:"엉덩이를 벽(또는 의자 등받이)에 살짝 댄 채 백스윙: 오른쪽 엉덩이가 벽을 더 누르도록 돌리기. 하프 백스윙에서 멈춰 확인."}); }
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
  /* ---- root cause first ----
     These are RULES written in advance, not something discovered in the video: "when A and B are both off, A is the likely cause of B".
     Each chain carries a confidence and is worded as a candidate. At most two chains are offered to work on at a time. */
  const get=k=>it.find(x=>x.key===k), off=x=>!!x&&(x.sev==="bad"||x.sev==="warn"), chains=[];
  const on=k=>R.st4&&R.st4.onset&&R.st4.onset[k]? "시작 "+R.st4.onset[k].t.toFixed(2)+"초 뒤("+R.st4.onset[k].phase+" 구간)부터" : null;
  if(view==="dtl"){ const hd=get("hipDepth"), ee=get("ee"), rise=get("rise"), hu=get("headUp"), tk=get("takeaway"), en=get("entry");
    if(off(hd)&&(off(ee)||off(rise))) chains.push({root:hd,results:[ee,rise].filter(off),conf:"보통",when:on("hip"),hist:"hipTop",unit:"%",
      link:"백스윙에서 오른쪽 골반이 뒤로 빠지지 않아 팔이 내려올 공간이 없고, 그 공간을 만들려고 임팩트 때 엉덩이가 공 쪽으로 나오며 상체가 일어서는 것으로 봅니다.",
      one:"오른쪽 엉덩이를 뒤로 보내면서 가슴을 돌린다",
      prep:"엉덩이를 벽이나 의자 등받이에 살짝 대고 어드레스(클럽 없이 팔짱).",act:"오른쪽 엉덩이가 벽을 더 누르도록 돌리며 올라가, 하프 백스윙에서 2초 멈춰 확인한 뒤 탑까지.",
      reps:"공 없이 10회 × 3세트 → 그다음 50% 속도로 공 10개.",caution:"머리를 붙잡지 말 것. 무릎을 펴며 일어서지 말 것."});
    else if(off(ee)) chains.push({root:ee,results:[rise,hu].filter(off),conf:"보통",when:null,hist:"ee",unit:"%",
      link:"임팩트 때 엉덩이가 공 쪽으로 나오면 상체가 일어서고 머리 높이가 변합니다. 백스윙 골반 깊이에서는 뚜렷한 원인이 보이지 않았습니다.",
      one:"엉덩이를 뒤에 둔 채 왼쪽 엉덩이를 뒤로 돌린다",
      prep:"엉덩이를 벽에 살짝 대고 어드레스.",act:"다운스윙에서 왼쪽 엉덩이가 벽을 스치도록 돌리며 임팩트 자세에서 2초 정지.",
      reps:"공 없이 10회 × 3세트 → 하프 스윙으로 공 10개.",caution:"공을 맞히려고 몸을 공 쪽으로 밀지 말 것."});
    if(off(tk)&&off(en)) chains.push({root:tk,results:[en],conf:"낮음",when:null,hist:null,
      link:"테이크백에서 헤드를 바깥으로 들면, 내려올 때 그 반대로 안쪽 아래로 떨어뜨려 길을 맞추게 됩니다. 화면(2D)에서 본 판정이라 카메라 위치에 따라 달라질 수 있습니다.",
      one:"테이크백은 손과 헤드가 함께, 샤프트 수평에서 헤드가 손을 가린다",
      prep:"폰을 타깃 라인 정후방(손 높이)에 두고 어드레스.",act:"샤프트가 수평이 될 때까지만 올리고 멈춰, 「테이크백」 장면에서 헤드가 손과 겹치는지 확인.",
      reps:"10회 × 3세트.",caution:"손목으로 먼저 들어 올리지 말 것."}); }
  else { const ht=get("headTop"), sw=get("sway"), sf=get("shift"), hu=get("headUp");
    if(ht&&ht.cause==="body") chains.push({root:ht,results:[sw,sf,hu].filter(off),conf:"보통",when:on("sh")||on("head"),hist:"shTop",unit:"cm",
      link:"백스윙에서 몸통이 회전 대신 타깃 반대쪽으로 통째로 이동하고, 머리는 그 결과로 따라갑니다. 골반 이동과 임팩트 때의 체중·머리 위치도 여기서 이어지는 것으로 봅니다.",
      one:"오른쪽 엉덩이를 옆이 아니라 뒤로 돌린다",
      prep:"오른발 바깥쪽에 공 바구니나 스틱을 세우고 어드레스.",act:"오른 무릎·엉덩이가 그것에 닿지 않게 돌리며 올라가, 하프 백스윙에서 2초 멈춰 머리·골반 위치를 확인한 뒤 탑까지.",
      reps:"공 없이 10회 × 3세트 → 50% 속도로 공 10개.",caution:"머리를 억지로 고정하지 말 것(몸이 굳습니다). 옆으로 가는 양만 줄입니다."}); }
  for(const c of chains.slice(0,2)){ c.root.tier=1; for(const r of c.results){ if(r.tier!==1){ r.tier=2; r.resultOf=c.root.title; } } }
  const cf={"높음":0,"보통":1,"낮음":2};
  const issues=it.filter(x=>x.sev==="bad"||x.sev==="warn").sort((a,b)=>(a.tier||3)-(b.tier||3)||rank[a.sev]-rank[b.sev]||cf[a.conf]-cf[b.conf]);
  const plan=chains.slice(0,2).map(c=>({key:c.root.key,title:c.root.title,value:c.root.value,ref:c.root.ref,sev:c.root.sev,conf:c.conf,when:c.when,link:c.link,results:c.results.map(r=>r.title+" ("+r.value+")"),
    one:c.one,prep:c.prep,act:c.act,reps:c.reps,caution:c.caution,hist:c.hist,unit:c.unit||""}));
  return {items:it,issues,plan,good:it.filter(x=>x.sev==="ok")};
}
// compact record for the swing history
export function summary(R,D){
  const po=R.po||{}, bd=R.bd, ok=bd&&bd.top&&bd.top.ok;
  return {t:Date.now(),name:R.name||"",view:R.view,fps:Math.round(R.fps),back:po.back,hold:po.hold,down:po.down,
    entry:R.pl&&R.pl.finalZone||null,takeawayOver:R.pl?R.pl.takeawayOver:null,chest:ok?Math.abs(bd.top.chest):null,pelvis:ok?Math.abs(bd.top.pelvis):null,
    ee:po.hipFwdImp??null,headUp:po.headImp?-po.headImp[1]:null,hipTop:R.M&&R.M.top&&R.M.top.hipX!=null?R.M.top.hipX:null,shTop:R.fo&&R.fo.shTop!=null?R.fo.shTop:null,headTopCm:R.fo&&R.fo.headTop!=null?R.fo.headTop:null,issues:D.issues.map(x=>x.key)};
}
