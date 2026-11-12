import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { PartyChat } from './PartyScreens.jsx'
import { FinancialDetails } from './SettleScreens.jsx'
import { HistoryMemberActions } from './MemberSafety.jsx'
import { RequestMap } from './RequestStatus.jsx'
import { HISTORY_LABEL, formatDateTime, routeLabel, savedTotal, won } from './format.js'
import { BackBar, Bone, Btn, Paper, Rows, Screen, SideEmpty, SidePlain, useWide } from './ui.jsx'

function HistoryChat({ partyId }) {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <Btn className="small quiet" aria-expanded={open} onClick={() => setOpen((value) => !value)}>일행 채팅 보기</Btn>
      {open && <PartyChat key={partyId} partyId={partyId} />}
    </div>
  )
}

function Row({ item, selected, onSelect }) {
  const [label, tone] = HISTORY_LABEL[item.stage] || ['상태 확인 중', '']
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
  const [label] = HISTORY_LABEL[item.stage] || ['상태 확인 중']
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
      <HistoryFinance item={item} />
    </SidePlain>
  )
}

export default function History({ onBack, admin = false }) {
  const [items, setItems] = useState(null)
  const [selectedId, setSelectedId] = useState(null)
  const [accuracy, setAccuracy] = useState(null)
  const wide = useWide()
  useEffect(() => {
    api.history().then(setItems).catch(() => setItems([]))
    if (admin) api.fareAccuracy().then(setAccuracy).catch(() => setAccuracy(null))
  }, [admin])
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
          {admin && accuracy?.settled > 0 && (
            <section aria-label="관리자 요금 통계"><h2 className="block-title">관리자 요금 통계</h2><p className="note">전체 정산 {accuracy.settled}건 중 {accuracy.within}건이 예상 요금의 15% 이내였어요.</p></section>
          )}
          <div className="list">
            {items.map((item) => (
              <React.Fragment key={item.id}>
                <Row item={item} selected={item.id === selected?.id}
                  onSelect={() => setSelectedId(item.id)} />
                {!wide && item.id === selected?.id && <HistoryFinance item={item} />}
                {(item.partyIds || (item.partyId ? [item.partyId] : [])).map((id) => <React.Fragment key={id}><HistoryChat partyId={id} /><HistoryMemberActions partyId={id} /></React.Fragment>)}
              </React.Fragment>
            ))}
          </div>
        </div>
      )}
    </Screen>
  )
}

function HistoryFinance({ item }) {
  const id = item.partyId || item.partyIds?.[0]
  const [result, setResult] = useState({})
  const [reload, setReload] = useState(0)
  useEffect(() => {
    if (!id) return undefined
    let closed = false
    api.party(id).then((party) => !closed && setResult({ id, party }))
      .catch((e) => !closed && setResult({ id, error: e.message }))
    return () => { closed = true }
  }, [id, reload])
  if (!id) return <p className="note">이 요청에서 조회할 수 있는 결제 상세가 없어요.</p>
  const current = result.id === id ? result : {}
  return <section className="history-finance" aria-label="결제·환불 기록">
    <h3>결제·환불 기록</h3>
    {current.party ? <FinancialDetails party={current.party} />
      : current.error ? <p role="status">결제 상세를 읽지 못했어요. {current.error}</p> : <p role="status">결제 기록을 읽고 있어요.</p>}
    {current.party?.canConfirmRide && <a href={'/parties/' + id}>일행 화면에서 운행 답변하기</a>}
    <Btn className="small quiet" onClick={() => setReload((n) => n + 1)}>결제 기록 다시 확인</Btn>
  </section>
}
