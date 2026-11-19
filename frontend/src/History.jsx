import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { PartyChat } from './PartyScreens.jsx'
import { FinancialDetails } from './SettleScreens.jsx'
import { PaymentRecord } from './PaymentCard.jsx'
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
function BoardingCaseLink({ item, onBoardingCases }) {
  if (!item.stage?.startsWith('BOARDING_')) return null
  return <div className="pad">
    {item.stage === 'BOARDING_UNCONFIRMED' && <p className="note">번호 미입력만으로 노쇼를 확정하지 않아요. 24시간 안에 당시 상황을 소명할 수 있어요.</p>}
    <Btn className="small line" onClick={onBoardingCases || (() => window.location.assign('/boarding-cases'))}>내 탑승 확인·소명</Btn>
  </div>
}

function Detail({ item, onBoardingCases }) {
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
      <BoardingCaseLink item={item} onBoardingCases={onBoardingCases} />
      <HistoryFinance item={item} />
    </SidePlain>
  )
}

export default function History({ onBack, admin = false, onBoardingCases }) {
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
    <Screen aside={selected ? <Detail item={selected} onBoardingCases={onBoardingCases} /> : <SideEmpty title="이용 내역이 여기에 쌓입니다">요청을 고르면 금액과 지점을 보여 드려요.</SideEmpty>}>
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
                {!wide && item.id === selected?.id && <><BoardingCaseLink item={item} onBoardingCases={onBoardingCases} /><HistoryFinance item={item} /></>}
                {item.partyId && (item.partyIds || [item.partyId]).map((id) => <React.Fragment key={id}><HistoryChat partyId={id} /><HistoryMemberActions partyId={id} /></React.Fragment>)}
              </React.Fragment>
            ))}
          </div>
        </div>
      )}
    </Screen>
  )
}

function HistoryFinance({ item }) {
  const id = item.partyId
  const own = !id
  const available = Boolean(id || item.partyIds?.length || item.endReason)
  const key = `${item.id}:${id || 'own'}`
  const [result, setResult] = useState({})
  const [reload, setReload] = useState(0)
  useEffect(() => {
    if (!available) return undefined
    let closed = false
    const load = own ? api.ownPaymentHistory(item.id) : api.party(id)
    load.then((data) => !closed && setResult(own ? { key, orders: data } : { key, party: data }))
      .catch((e) => !closed && setResult({ key, error: e.message }))
    return () => { closed = true }
  }, [id, item.id, own, available, key, reload])
  if (!available) return <p className="note">이 요청에서 조회할 수 있는 결제 상세가 없어요.</p>
  const current = result.key === key ? result : {}
  return <section className="history-finance" aria-label="결제·환불 기록">
    <h3>결제·환불 기록</h3>
    {own && <p className="note">일행에서 제외된 뒤에도 내 결제·환불 기록을 확인할 수 있어요.</p>}
    {own && item.stage?.startsWith('BOARDING_') && <p className="note">선결제는 조사 중 보류돼요. 조사와 요금 이의 처리가 끝나 정산이 확정되면, 노쇼로 확정된 경우 보전 부담금을 뺀 나머지를, 노쇼가 아니면 전액을 환불해요. 정산 금액 미입력으로 종료되면 전액 환불해요.</p>}
    {current.party ? <FinancialDetails party={current.party} />
      : current.orders ? <>
        {current.orders.length === 0 && <p className="note">내 결제 주문이 없어요.</p>}
        {current.orders.map((order) => <section key={order.orderId} aria-label={order.purpose === 'ADDITIONAL' ? '추가 결제 기록' : '선결제 기록'}>
          <h4>{order.purpose === 'ADDITIONAL' ? '추가 결제 기록' : '선결제 기록'}</h4>
          <PaymentRecord order={order} />
          {order.refundHeld && <p className="note">{order.holdReason || '환불 보류 중'}</p>}
          {order.simulated && <p className="note">시연용 모의 결제 기록이에요. 실제 돈은 나가지 않아요.</p>}
        </section>)}
      </>
      : current.error ? <p role="status">결제 상세를 읽지 못했어요. {current.error}</p> : <p role="status">결제 기록을 읽고 있어요.</p>}
    {current.party?.canConfirmRide && <a href={'/parties/' + id}>일행 화면에서 운행 답변하기</a>}
    <Btn className="small quiet" onClick={() => setReload((n) => n + 1)}>결제 기록 다시 확인</Btn>
  </section>
}
