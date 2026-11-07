import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { RequestMap } from './RequestStatus.jsx'
import { HISTORY_LABEL, formatDateTime, routeLabel, savedTotal, won } from './format.js'
import { BackBar, Bone, Btn, Paper, Rows, Screen, SideEmpty, SidePlain, useWide } from './ui.jsx'

function Row({ item, selected, onSelect }) {
  const [label, tone] = HISTORY_LABEL[item.stage] || ['', '']
  const content = (
    <>
      <div>
        <b>{routeLabel(item)}</b>
        <small>{formatDateTime(item.arriveBy || item.windowStart)}</small>
      </div>
      <div className="money">
        <span className={`badge ${tone}`}>{label}</span>
        {item.paid != null && <span>{won(item.paid)}</span>}
      </div>
    </>
  )
  if (!onSelect) return <div className="item">{content}</div>
  return (
    <button type="button" className={selected ? 'item sel' : 'item'} aria-pressed={selected} onClick={onSelect}>
      {content}
    </button>
  )
}

/** 넓은 화면의 오른쪽. 경로 선은 저장하지 않으므로 지점만 표시한다. */
function Detail({ item }) {
  const [label] = HISTORY_LABEL[item.stage] || ['']
  return (
    <SidePlain>
      <div className="mini"><RequestMap item={item} edge={28} /></div>
      <Paper title={routeLabel(item)}>
        <Rows items={[
          item.partyPaid != null && ['실제 결제 금액', won(item.partyPaid)],
          item.paid != null && ['내가 낸 돈', won(item.paid), 'big'],
          item.paid != null && ['혼자 탈 때보다', `${won(Math.max(0, item.soloFare - item.paid))} 절약`, 'save'],
          item.paid == null && ['혼자 타면', `약 ${won(item.soloFare)}`],
          item.partySize != null && ['일행', `${item.partySize}명`],
          ['상태', label],
        ]} />
      </Paper>
    </SidePlain>
  )
}

export default function History({ onBack }) {
  const [items, setItems] = useState(null)
  const [selectedId, setSelectedId] = useState(null)
  const [accuracy, setAccuracy] = useState(null)
  const wide = useWide()
  useEffect(() => {
    api.history().then(setItems).catch(() => setItems([]))
    api.fareAccuracy().then(setAccuracy).catch(() => setAccuracy(null))
  }, [])
  const saved = savedTotal(items || [])
  const selected = items?.find((item) => item.id === selectedId) || items?.[0]
  return (
    <Screen aside={selected ? <Detail item={selected} /> : <SideEmpty title="이용 내역이 여기에 쌓입니다">요청을 고르면 금액과 지점을 보여 드려요.</SideEmpty>}>
      <BackBar onBack={onBack} title="이용 내역" />
      {!items && <div className="pad"><Bone height={60} /><Bone height={60} /><Bone height={60} /></div>}
      {items?.length === 0 && (
        <div className="pad">
          <h1 className="head">아직 이용 내역이 없어요</h1>
          <p className="sub">첫 요청을 만들면 여기에 쌓입니다.</p>
          <Btn onClick={onBack}>요청하러 가기</Btn>
        </div>
      )}
      {items?.length > 0 && (
        <div className="block">
          {saved > 0 && <p className="sub">지금까지 {won(saved)} 아꼈어요</p>}
          {accuracy?.settled > 0 && (
            <p className="note">전체 정산 {accuracy.settled}건 중 {accuracy.within}건이 예상 요금의 15% 이내였어요.</p>
          )}
          <div className="list">
            {items.map((item) => (
              <Row key={item.id} item={item} selected={item.id === selected?.id}
                onSelect={wide ? () => setSelectedId(item.id) : null} />
            ))}
          </div>
        </div>
      )}
    </Screen>
  )
}
