import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { TRANSFER_LABEL, routeLabel, won } from './format.js'
import { Notice, Rows, Screen } from './ui.jsx'

const WARNING_RATE = 0.15
const REAL_NAME = '보낼 때 서로의 실명이 표시될 수 있습니다.'

export function PayerInput({ party, run, onDone, onLater }) {
  const correcting = party.settlement != null
  const [amount, setAmount] = useState('')
  const [receipt, setReceipt] = useState(null)
  const [bankName, setBankName] = useState(party.settlement?.bankName || '')
  const [accountNumber, setAccountNumber] = useState(party.settlement?.accountNumber || '')
  const [shares, setShares] = useState(null)
  const value = Number(amount)

  useEffect(() => {
    setShares(null)
    if (!value) return undefined
    let closed = false
    const timer = setTimeout(() => {
      api.settlementPreview(party.id, value).then((list) => !closed && setShares(list)).catch(() => {})
    }, 300)
    return () => {
      closed = true
      clearTimeout(timer)
    }
  }, [value])

  const difference = value ? (value - party.expectedTotal) / party.expectedTotal : 0
  const unusual = Math.abs(difference) > WARNING_RATE
  const send = correcting ? api.correctSettlement : api.submitSettlement
  const ready = value > 0 && receipt && bankName.trim() && accountNumber.trim()
  const submit = () => run(async () => {
    await send(party.id, { amount: value, receipt, bankName, accountNumber })
    onDone?.()
  })

  return (
    <Screen actions={(
      <>
        <button disabled={!ready} onClick={submit}>{unusual ? '이 금액으로 정산 요청' : '일행에게 정산 요청'}</button>
        <button className="plain" onClick={onLater}>{correcting ? '고치지 않기' : '나중에 입력'}</button>
      </>
    )}>
      <p className="muted">결제자 화면</p>
      <h2>실제 결제 금액을 알려 주세요</h2>
      <label>실제 결제 금액
        <input className="big" type="number" min="1" inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </label>
      {unusual ? (
        <Notice title={`예상 요금 ${won(party.expectedTotal)}보다 ${Math.round(Math.abs(difference) * 100)}% ${difference > 0 ? '높아요' : '낮아요'}`}>
          금액이 맞다면 그대로 요청하면 됩니다. 일행 화면에도 같은 안내와 결제 사진이 보입니다.
        </Notice>
      ) : (
        <p className="muted">예상 요금은 {won(party.expectedTotal)}입니다.</p>
      )}
      <label>결제 화면 사진
        <input type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => setReceipt(e.target.files[0])} />
      </label>
      <div className="two">
        <label>은행
          <input maxLength={30} value={bankName} onChange={(e) => setBankName(e.target.value)} placeholder="은행 이름" />
        </label>
        <label>받을 계좌
          <input inputMode="numeric" maxLength={40} value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} placeholder="숫자와 - 만" />
        </label>
      </div>
      {shares && (
        <Rows title="이렇게 나눠요 (단독 요금 비율)" items={shares.map((s) => [
          s.payer ? '나, 잔액' : s.nickname, won(s.amount), s.payer,
        ])} />
      )}
      <p className="muted">금액, 사진, 계좌는 이번 일행에게만 보입니다. 카드번호는 가리고 올려 주세요. 사진은 7일 뒤, 계좌는 모두 받음 처리되거나 7일이 지나면 지웁니다. 일행이 보내기 전까지는 고칠 수 있습니다. 송금할 때 서로의 실명이 표시될 수 있습니다.</p>
    </Screen>
  )
}

export function Collect({ party, run, onCorrect, onHome }) {
  const { settlement } = party
  const receiving = settlement.transfers.reduce((sum, t) => sum + t.amount, 0)
  const myPart = settlement.paidAmount - receiving
  return (
    <Screen actions={(
      <>
        {settlement.canCorrect && <button className="secondary" onClick={onCorrect}>금액 고치기</button>}
        <button className="secondary" onClick={onHome}>홈으로</button>
      </>
    )}>
      <p className="muted">결제자 화면</p>
      <h2>받은 돈을 확인해 주세요</h2>
      <div className="hero">
        <span>받을 돈</span>
        <strong>{won(receiving)}</strong>
        <em>결제 {won(settlement.paidAmount)}, 내 분담금 {won(myPart)}</em>
      </div>
      {settlement.warning && <Notice title={`결제 금액이 예상과 ${Math.abs(settlement.differencePercent)}% 달라요`}>일행 화면에도 같은 안내가 보입니다.</Notice>}
      <ul className="transfers">
        {settlement.transfers.map((t) => (
          <li key={t.id}>
            <div><b>{t.senderNickname}, {won(t.amount)}</b><small>{TRANSFER_LABEL[t.status]}</small></div>
            {t.status === 'SENT' && <button onClick={() => run(() => api.received(t.id))}>받음</button>}
          </li>
        ))}
      </ul>
      <Rows items={[
        [`단독 요금 ${won(party.mySoloFare)}`, `${won(party.mySoloFare - myPart)} 절약`, true],
      ]} />
      <p className="muted">은행 앱에서 입금을 확인한 뒤 받음을 눌러 주세요. 모두 받음이면 계좌를 지우고 완료합니다.</p>
    </Screen>
  )
}

export function Transfer({ party, transfer, run, onHome }) {
  const { settlement } = party
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    await navigator.clipboard.writeText(settlement.accountNumber)
    setCopied(true)
  }
  return (
    <Screen actions={(
      <>
        <button onClick={() => run(() => api.sent(transfer.id, settlement.revision))}>보냈어요</button>
        <button className="plain" onClick={onHome}>나중에 보내기</button>
      </>
    )}>
      <p className="muted">{party.payerNickname}님이 결제했어요</p>
      <div className="hero">
        <span>{party.payerNickname}님에게 보낼 돈</span>
        <strong>{won(transfer.amount)}</strong>
        <em>예상 {won(party.myShare)}, 실제 요금으로 다시 계산</em>
      </div>
      {settlement.warning && (
        <Notice title={`결제 금액이 예상보다 ${Math.abs(settlement.differencePercent)}% ${settlement.differencePercent > 0 ? '높아요' : '낮아요'}`}>
          할증이나 경로 차이일 수 있어요. 결제 사진을 확인해 보세요.
        </Notice>
      )}
      {settlement.accountNumber ? (
        <div className="account">
          <div><small>받는 계좌</small><b>{settlement.bankName} {settlement.accountNumber}</b></div>
          <button className="secondary" onClick={copy}>{copied ? '복사됨' : '복사'}</button>
        </div>
      ) : (
        <Notice title="계좌 보관 기간이 지났어요">{party.payerNickname}님이 금액을 다시 올리면 계좌가 다시 보입니다.</Notice>
      )}
      <Rows items={[
        ['실제 결제 금액', won(settlement.paidAmount)],
        ['내 단독 요금 비율', `${party.mySoloFare.toLocaleString()} / ${won(party.soloTotal)}`],
      ]} />
      {settlement.receiptAvailable
        ? <a className="linkrow" href={`/api/parties/${party.id}/receipt`} target="_blank" rel="noreferrer">결제 화면 사진 보기</a>
        : <p className="muted">결제 사진이 없습니다.</p>}
      <p className="muted">은행 앱에서 보낸 뒤 아래 버튼을 눌러 주세요. {REAL_NAME} 24시간 안에 보내 주세요.</p>
    </Screen>
  )
}

export function Done({ party, transfer, onHome }) {
  const mineName = party.members.find((m) => m.requestId === party.myRequestId)?.pointName
  return (
    <Screen actions={<button onClick={onHome}>홈으로</button>}>
      <h2>송금을 표시했어요</h2>
      <div className="hero">
        <span>이번에 아낀 금액</span>
        <strong>{won(party.mySoloFare - transfer.amount)}</strong>
        <em>단독 요금 {won(party.mySoloFare)}, 낸 돈 {won(transfer.amount)}</em>
      </div>
      <Rows items={[
        ['경로', routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: mineName })],
        ['실제 결제 금액', won(party.settlement.paidAmount)],
        ['일행', `${party.members.length}명`],
      ]} />
      <p className="muted">{party.payerNickname}님이 받음을 누르면 이 일행은 완료됩니다. 계좌는 모두 받음 처리되거나 7일이 지나면 지웁니다.</p>
    </Screen>
  )
}
