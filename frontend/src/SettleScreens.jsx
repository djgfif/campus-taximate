import React, { useEffect, useState } from 'react'
import { Camera } from 'lucide-react'
import { api } from './api.js'
import { PartyChat, RideConfirmationCard } from './PartyScreens.jsx'
import PaymentCard, { PaymentRecord } from './PaymentCard.jsx'
import { formatDateTime, isPaymentParty, partyLabel, remaining, TRANSFER_LABEL, won } from './format.js'
import { BackBar, Btn, Paper, Rows, Say, Screen, Sheet, SidePlain, TopBar } from './ui.jsx'

function ReceiptField({ file, onChange }) {
  const [preview, setPreview] = useState(null)
  useEffect(() => {
    if (!file) { setPreview(null); return undefined }
    const url = URL.createObjectURL(file); setPreview(url)
    return () => URL.revokeObjectURL(url)
  }, [file])
  return <label className="photo">
    <input aria-label="영수증 사진" type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => onChange(e.target.files[0] || null)} />
    {preview ? <img src={preview} alt="올린 영수증 사진" /> : <span className="ph"><Camera size={22} aria-hidden="true" /></span>}
    <span>{file ? '사진 바꾸기' : '영수증 사진 올리기'}<small>카드번호는 가리고 올려 주세요</small></span>
  </label>
}

function ReceiptPaper({ party }) {
  return <Paper title="영수증">
    {party.settlement?.receiptAvailable ? <a href={'/api/parties/' + party.id + '/receipt'} target="_blank" rel="noreferrer">영수증 사진 보기</a>
      : <p className="note">보관 중인 영수증 사진이 없어요. 정산 기록은 남아 있어요.</p>}
  </Paper>
}

export function PayerInput({ party, now, run, onDone, onLater, onUpdated }) {
  const correcting = party.settlement != null
  const [amount, setAmount] = useState(correcting ? String(party.settlement.paidAmount) : '')
  const [receipt, setReceipt] = useState(null)
  const [preview, setPreview] = useState({})
  const [error, setError] = useState('')
  const value = Number(amount)
  const locked = correcting && !party.settlement.canCorrect
  useEffect(() => {
    if (!value || !Number.isSafeInteger(value)) return undefined
    let closed = false
    const timer = setTimeout(() => api.settlementPreview(party.id, value).then((shares) => !closed && setPreview({ value, shares }))
      .catch((e) => !closed && setPreview({ value, error: e.message })), 300)
    return () => { closed = true; clearTimeout(timer) }
  }, [party.id, value])
  const shares = preview.value === value ? preview.shares : null
  const difference = party.expectedTotal > 0 ? (value - party.expectedTotal) / party.expectedTotal : 0
  const unusual = value > 0 && Math.abs(difference) > 0.15
  const ready = value > 0 && value <= 10000000 && Number.isSafeInteger(value) && receipt && !locked
  const submit = async () => {
    setError('')
    try {
      await (correcting ? api.correctSettlement : api.submitSettlement)(party.id, { amount: value, receipt })
      await run(async () => {}, '정산 금액을 올렸어요'); onDone?.()
    } catch (e) { setError(e.message) }
  }
  if (!isPaymentParty(party)) return <LegacySettlement party={party} onHome={onLater} />
  return <Screen aside={<SidePlain><Paper title="이렇게 나눠요">{shares ? <Rows items={shares.map((s) => [s.payer ? '나 (결제자)' : s.nickname, won(s.amount)])} /> : <p>금액을 입력하면 각자 몫을 보여 드려요.</p>}</Paper></SidePlain>}
    foot={<><Btn disabled={!ready} onClick={submit}>{correcting ? '금액 수정하기' : '일행에게 정산 요청'}</Btn><Btn className="quiet" onClick={onLater}>{correcting ? '고치지 않기' : '나중에 입력'}</Btn></>}>
    <BackBar onBack={onLater} title="실제 금액 입력" />
    <div className="pad"><RideConfirmationCard party={party} onUpdated={onUpdated} /><h1 className="head">얼마를 결제했나요?</h1>
      <label className="field amount">실제 결제 금액<input aria-label="실제 결제 금액" inputMode="numeric" value={amount ? Number(amount).toLocaleString() : ''} placeholder="0"
        onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} /><em>원</em></label>
      {unusual ? <Say tone="stop-tone" title={'예상 요금보다 ' + Math.round(Math.abs(difference) * 100) + '% ' + (difference > 0 ? '높아요' : '낮아요')}>금액과 영수증을 다시 확인해 주세요. 금액이 맞으면 그대로 입력할 수 있어요.</Say>
        : <p>예상 요금은 {won(party.expectedTotal)}이에요.</p>}
      <ReceiptField file={receipt} onChange={setReceipt} />
      {shares && <Rows items={shares.map((s) => [s.payer ? '나 (결제자)' : s.nickname, won(s.amount)])} />}
      {locked && <Say>이의가 있거나 수정할 수 있는 시간이 지났어요.</Say>}
      {(error || preview.value === value && preview.error) && <p role="alert">{error || preview.error}</p>}
      <p className="note">입력 뒤 24시간은 요금 이의 기간이에요. 정산 확정 뒤 환불·추가 결제를 처리하고 받을 돈을 앱에 기록해요(실제 송금 없음).</p>
      <p className="note">이의가 없고 이의 기간 안일 때만 수정할 수 있어요. 수정하면 이의 기간 24시간을 다시 세요. 사진은 올린 날부터 7일 뒤 지워요.</p>
    </div>
    <PartyChat partyId={party.id} now={now} status={party.status} />
  </Screen>
}

/** 현재 응답에 있는 금융 기록만 표시한다. 앱 정산 기록상 받을 돈(실제 송금 없음)은 현금 입금으로 표현하지 않는다. */
export function FinancialDetails({ party }) {
  const settlement = party.settlement
  if (!settlement) return <p className="note">아직 정산 기록이 없어요.</p>
  const ownId = party.members?.find((m) => m.requestId === party.myRequestId)?.memberId
  const mine = settlement.shares?.find((s) => s.memberId === ownId)
  return <div className="financial-details">
    <Rows items={[
      ['실제 결제 금액', won(settlement.paidAmount)], mine && ['내 몫', won(mine.amount), 'big'],
      settlement.finalizedAt && ['정산 확정', formatDateTime(settlement.finalizedAt)],
    ]} />
    {settlement.shares?.length > 0 && <Paper title="각자 몫"><Rows items={settlement.shares.map((s) => [
      (s.memberId === ownId ? '나' : party.members?.find((m) => m.memberId === s.memberId)?.nickname || '일행') + (s.payer ? ' (결제자)' : ''), won(s.amount),
    ])} /></Paper>}
    {settlement.payments?.map((order) => <section key={order.orderId} aria-label={order.purpose === 'ADDITIONAL' ? '추가 결제 기록' : '선결제 기록'}>
      <h3>{order.purpose === 'ADDITIONAL' ? '추가 결제 기록' : '선결제 기록'}</h3><PaymentRecord order={order} />
      {(order.refundRequestedAmount > 0 || order.refundedAmount > 0) && <p className="note">환불은 카드 반영까지 3~4영업일 정도 걸려요.</p>}
    </section>)}
    {party.payer && settlement.finalizedAt && settlement.mockPayoutAmount != null && <Say tone="calm" title={'앱 정산 기록상 받을 돈(실제 송금 없음) ' + won(settlement.mockPayoutAmount)}>실제 송금 없음</Say>}
  </div>
}

export function SettlementScreen({ party, myId, now, config, onUpdated, onMenu, onHome, onCorrect, onDetail }) {
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  if (!isPaymentParty(party)) return <LegacySettlement party={party} onHome={onHome} onMenu={onMenu} />
  const settlement = party.settlement
  const deadline = settlement?.objectionDeadlineAt
  const open = settlement && ['OBJECTION_WINDOW', 'REVIEW_PENDING'].includes(party.status) && new Date(now) < new Date(deadline)
  const send = async () => {
    setError('')
    try { await api.objectToFare(party.id, reason.trim()); setAsking(false); await onUpdated?.() }
    catch (e) { setError(e.message) }
  }
  return <Screen foot={<><Btn className="line" onClick={onHome}>홈으로</Btn></>}>
    <TopBar onMenu={onMenu} /><div className="pad"><h1 className="head">{partyLabel(party.status)}</h1>
      {settlement?.warning && <Say tone="stop-tone">실제 금액이 예상과 {Math.abs(settlement.differencePercent)}% 달라요. 영수증을 확인해 주세요.</Say>}
      {open && <Say title={'요금 이의 기간 ' + remaining(deadline, now) + ' 남음'}>{formatDateTime(deadline)}까지 이의를 낼 수 있어요. {party.status === 'REVIEW_PENDING' ? '접수된 이의는 관리자 검토를 기다려요.' : '이의가 없으면 정산을 확정해요.'}</Say>}
      {party.status === 'REVIEW_PENDING' && <Say>{party.reviewReason==='RIDE_DISPUTE'?'운행 답변이 엇갈려 관리자 검토 중이에요. 환불과 추가 결제, 받을 돈의 정산을 보류해요.':'요금 이의가 있어 관리자 검토 중이에요. 환불과 추가 결제, 받을 돈의 정산을 보류해요.'}</Say>}
      <RideConfirmationCard party={party} onUpdated={onUpdated} />
      <FinancialDetails party={party} />
      {onDetail && <Btn className="line" onClick={onDetail}>일행과 경로 자세히</Btn>}
      {open && <Btn className="line" onClick={() => setAsking(true)}>요금 이의</Btn>}
      {settlement?.canCorrect && onCorrect && <Btn className="line" onClick={onCorrect}>금액 고치기</Btn>}
      {settlement?.finalizedAt && settlement.payments?.filter((o) => o.purpose === 'ADDITIONAL').map((order) =>
        <PaymentCard key={order.orderId} knownOrder={order} partyId={party.id} purpose="ADDITIONAL" myId={myId} now={now} config={config} onUpdated={onUpdated} />)}
      {settlement && <ReceiptPaper party={party} />}
    </div>
    <PartyChat partyId={party.id} now={now} status={party.status} />
    {asking && <Sheet title="요금 이의" onClose={() => setAsking(false)}>
      <label className="field">이의 사유<textarea value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} /></label>
      {error && <p role="alert">{error}</p>}<Btn disabled={!reason.trim()} onClick={send}>이의 보내기</Btn>
    </Sheet>}
  </Screen>
}

export function LegacySettlement({ party, onHome, onMenu }) {
  const settlement = party.settlement
  return <Screen foot={<Btn onClick={onHome}>홈으로</Btn>}><TopBar onMenu={onMenu} />
    <div className="pad"><h1 className="head">이전 정산 기록</h1><p>이전 송금 방식의 기록은 읽기만 할 수 있어요.</p>
      {settlement && <><Rows items={[
        ['실제 결제 금액', won(settlement.paidAmount)],
        ...((settlement.transfers || []).map((t) => [t.senderNickname, won(t.amount) + ' · ' + (TRANSFER_LABEL[t.status] || '상태 확인 중')])),
      ]} /><ReceiptPaper party={party} /></>}
    </div></Screen>
}

export const Collect = (props) => <LegacySettlement {...props} />
export const Transfer = (props) => <LegacySettlement {...props} />
export const Done = (props) => <LegacySettlement {...props} />
