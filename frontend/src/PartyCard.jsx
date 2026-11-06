import React, { useState } from 'react'
import { api } from './api.js'
import { TRANSFER_LABEL, formatDateTime, won } from './format.js'

export default function PartyCard({ party, myId, run }) {
  const mine = party.members.find((m) => m.memberId === myId)
  return (
    <section className="card">
      <h2>{party.settlement ? '정산' : '일행 확정'}</h2>
      <p><strong>{formatDateTime(party.departureAt)}</strong> {party.hubName} 출발</p>
      <p className="muted">
        {party.payer ? '내가 택시를 잡고 결제합니다.' : `택시와 결제는 ${party.payerNickname}님이 맡습니다.`}
      </p>
      <ol className="members">
        {party.members.map((m) => (
          <li key={m.requestId} className={m.memberId === myId ? 'me' : ''}>
            <span className="order">{m.dropoffOrder}</span>
            <div>
              <strong>{m.nickname}</strong> {m.arrived && <em>도착</em>}
              <div className="muted">{m.dropoffName}{m.memo ? ` · ${m.memo}` : ''}</div>
            </div>
            <span className="amount">{won(m.expectedShare)}</span>
          </li>
        ))}
      </ol>
      <p className="muted">예상 금액입니다. 실제 결제 금액을 같은 비율로 나눕니다.</p>

      {party.canArrive && <button onClick={() => run(() => api.arrive(party.id))}>도착했어요</button>}
      {party.canCancel && (
        <button className="secondary" onClick={() => run(() => api.cancelRequest(party.myRequestId))}>
          취소 (일행 전체가 취소됩니다)
        </button>
      )}
      {mine && !mine.arrived && !party.canCancel && !party.settlement && (
        <p className="muted">출발 처리 중입니다.</p>
      )}
      {party.canSettle && <SettleForm party={party} run={run} />}
      {party.settlement && <SettlementView party={party} myId={myId} run={run} />}
    </section>
  )
}

function SettleForm({ party, run }) {
  const [amount, setAmount] = useState('')
  const [receipt, setReceipt] = useState(null)
  const submit = (event) => {
    event.preventDefault()
    run(() => api.submitSettlement(party.id, amount, receipt))
  }
  return (
    <form onSubmit={submit}>
      <h3>결제 금액 입력</h3>
      <label>실제 결제 금액
        <input type="number" min="1" required inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label>결제 내역 사진 (일행에게 공개됩니다)
        <input type="file" accept="image/*" required onChange={(e) => setReceipt(e.target.files[0])} />
      </label>
      <button type="submit">송금 요청 만들기</button>
    </form>
  )
}

function SettlementView({ party, myId, run }) {
  const { settlement } = party
  const [amount, setAmount] = useState('')
  return (
    <div className="settlement">
      <h3>결제 금액 {won(settlement.paidAmount)}</h3>
      {settlement.warning && <p className="error">예상 {won(party.expectedTotal)}과 15% 넘게 다릅니다. 결제 내역 사진을 확인해 주세요.</p>}
      <a href={`/api/parties/${party.id}/receipt`} target="_blank" rel="noreferrer">결제 내역 사진 보기</a>
      <ul className="transfers">
        {settlement.transfers.map((t) => (
          <li key={t.id}>
            <span>{t.senderNickname} {won(t.amount)}</span>
            <span className="muted">{TRANSFER_LABEL[t.status]}</span>
            {t.senderId === myId && t.status === 'REQUESTED' && (
              <button onClick={() => run(() => api.sent(t.id, settlement.revision))}>보냈어요</button>
            )}
            {party.payer && t.status === 'SENT' && (
              <button onClick={() => run(() => api.received(t.id))}>받았어요</button>
            )}
          </li>
        ))}
      </ul>
      {!party.payer && <p className="muted">은행 앱으로 {party.payerNickname}님에게 보낸 뒤 눌러 주세요.</p>}
      {settlement.canCorrect && (
        <form onSubmit={(e) => { e.preventDefault(); run(() => api.correctSettlement(party.id, Number(amount))) }}>
          <label>금액 고치기 (아무도 보내기 전까지)
            <input type="number" min="1" required value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <button type="submit" className="secondary">금액 고치기</button>
        </form>
      )}
    </div>
  )
}
