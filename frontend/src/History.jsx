import React, { useEffect, useState } from 'react'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { api } from './api.js'
import { PartyChat } from './PartyScreens.jsx'
import { FinancialDetails } from './SettleScreens.jsx'
import { PaymentRecord } from './PaymentCard.jsx'
import { HistoryMemberActions } from './MemberSafety.jsx'
import { HISTORY_LABEL, formatDateTime, routeLabel, savedTotal, won } from './format.js'
import { Bone, Btn, Empty, Rows, Screen } from './ui.jsx'

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
      {onSelect && (selected ? <ChevronUp size={18} aria-hidden="true" /> : <ChevronDown size={18} aria-hidden="true" />)}
    </>
  )
  if (!onSelect) return <div className="item">{content}</div>
  return (
    <button type="button" className={selected ? 'item sel' : 'item'} aria-expanded={selected} onClick={onSelect}>
      {content}
    </button>
  )
}

function BoardingCaseLink({ item, onBoardingCases }) {
  if (!item.stage?.startsWith('BOARDING_')) return null
  return <div className="case-link">
    {item.stage === 'BOARDING_UNCONFIRMED' && <p className="note">번호 미입력만으로 노쇼를 확정하지 않아요. 24시간 안에 당시 상황을 소명할 수 있어요.</p>}
    <Btn className="small line" onClick={onBoardingCases || (() => window.location.assign('/boarding-cases'))}>탑승 확인</Btn>
  </div>
}

/**
 * 고른 이용 내역의 거래 명세. 위에서 아래로 실제 택시비 → 내가 낸 돈 → 혼자 탔다면 → 덜 낸 돈.
 * 정산 전이면 확정된 금액이 없다고 밝히고 예상 금액만 보인다. 결제와 환불 줄은 아래 결제 기록에 있다.
 */
function Detail({ item }) {
  const settled = item.paid != null
  return (
    <Rows items={[
      item.partySize != null && ['일행', `${item.partySize}명`],
      settled && item.partyPaid != null && ['실제 택시비', won(item.partyPaid)],
      settled && ['내가 낸 돈', won(item.paid), 'big'],
      settled ? ['혼자 탔다면', won(item.soloFare)] : ['혼자 타면(예상)', `약 ${won(item.soloFare)}`],
      settled && item.soloFare > item.paid && ['같이 타서 덜 낸 돈', won(item.soloFare - item.paid)],
      !settled && ['내가 낸 돈', '정산 전'],
    ]} />
  )
}

/** 아직 끝나지 않은 이용(선결제 전, 모이는 중 등)과 끝난 이용을 나눈다. */
const ongoing = (item) => (HISTORY_LABEL[item.stage] || [])[1] === 'warn'

/** 이용 내역. 휴대폰과 넓은 화면 모두 지도 없이 한 칸으로, 고른 줄 아래에 금액과 결제 기록을 펼친다. */
export default function History({ onHome, admin = false, onBoardingCases }) {
  const [items, setItems] = useState(null)
  const [selectedId, setSelectedId] = useState(undefined)
  const [accuracy, setAccuracy] = useState(null)
  useEffect(() => {
    api.history().then(setItems).catch(() => setItems([]))
    if (admin) api.fareAccuracy().then(setAccuracy).catch(() => setAccuracy(null))
  }, [admin])
  const saved = savedTotal(items || [])
  const activeId = selectedId === undefined ? items?.[0]?.id : selectedId
  const selected = activeId != null ? items?.find((item) => item.id === activeId) : null
  const toggleItem = (id) => setSelectedId((prev) => {
    const current = prev === undefined ? items?.[0]?.id : prev
    return current === id ? null : id
  })
  return (
    <Screen>
      <header className="bar"><h1 className="bar-title">이용 내역</h1></header>
      {!items && <div className="pad"><Bone height={60} /><Bone height={60} /><Bone height={60} /></div>}
      {items?.length === 0 && <div className="pad"><Empty title="아직 탄 기록이 없어요" action="길 올리기" onAction={onHome}>같이 타면 덜 낸 돈이 여기 쌓여요.</Empty></div>}
      {items?.length > 0 && (
        <div className="block">
          {saved > 0 && <div className="rows"><div><span className="k">같이 타서 덜 낸 돈</span><span className="v">{won(saved)}</span></div></div>}
          {admin && accuracy?.settled > 0 && (
            <section aria-label="관리자 요금 통계"><h2 className="block-title">관리자 요금 통계</h2><p className="note">전체 정산 {accuracy.settled}건 중 {accuracy.within}건이 예상 요금의 15% 이내였어요.</p></section>
          )}
          {[['진행 중', items.filter(ongoing)], ['지난 이용', items.filter((item) => !ongoing(item))]].filter(([, list]) => list.length > 0).map(([heading, list]) => <section key={heading} aria-label={heading}>
          <h2 className="block-title history-group">{heading}</h2>
          <div className="list">
            {list.map((item) => (
              <React.Fragment key={item.id}>
                <Row item={item} selected={item.id === selected?.id}
                  onSelect={() => toggleItem(item.id)} />
                {item.id === selected?.id && <div className="history-open">
                  <Detail item={item} />
                  <BoardingCaseLink item={item} onBoardingCases={onBoardingCases} />
                  <HistoryFinance item={item} />
                  {item.partyId && (item.partyIds || [item.partyId]).map((id) => <React.Fragment key={id}><HistoryChat partyId={id} /><HistoryMemberActions partyId={id} /></React.Fragment>)}
                </div>}
              </React.Fragment>
            ))}
          </div>
          </section>)}
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
  return <section className="history-finance" aria-label="결제와 환불 기록">
    <h3>결제와 환불 기록</h3>
    {own && item.stage?.startsWith('BOARDING_') && <p className="note">선결제는 조사 중 보류돼요. 노쇼가 아니면 전액, 노쇼면 보전 부담금을 뺀 나머지를 돌려받아요.</p>}
    {current.party ? <FinancialDetails party={current.party} />
      : current.orders ? <>
        {current.orders.length === 0 && <p className="note">내 결제 주문이 없어요.</p>}
        {current.orders.map((order) => <section key={order.orderId} aria-label={order.purpose === 'ADDITIONAL' ? '분담금 결제 기록' : '선결제 기록'}>
          <h4>{order.purpose === 'ADDITIONAL' ? '분담금 결제 기록' : '선결제 기록'}</h4>
          <PaymentRecord order={order} />
          {order.refundHeld && <p className="note">{order.holdReason || '환불 보류 중'}</p>}
          {order.simulated && <p className="note">시연용 모의 결제 기록이에요. 실제 돈은 나가지 않아요.</p>}
        </section>)}
      </>
      : current.error ? <p role="status">결제 상세를 읽지 못했어요. {current.error}</p> : <p role="status">결제 기록을 읽고 있어요.</p>}
    {current.party?.canConfirmRide && <a href={'/parties/' + id}>일행 화면에서 운행 답변하기</a>}
    {(current.error || current.party || current.orders?.length > 0) && <Btn className="small quiet" onClick={() => setReload((n) => n + 1)}>결제 기록 다시 확인</Btn>}
  </section>
}
