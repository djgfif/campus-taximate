import React, { useEffect, useMemo, useState } from 'react'
import { Camera, Copy } from 'lucide-react'
import { api } from './api.js'
import { TRANSFER_LABEL, routeLabel, won } from './format.js'
import { BackBar, Btn, Meter, Money, Paper, Rows, Say, Screen, SideEmpty, SidePlain, TopBar, toast, useWide } from './ui.jsx'

const WARNING_RATE = 0.15
const REAL_NAME = '보낼 때 서로의 실명이 표시될 수 있습니다.'

function ReceiptField({ file, onChange }) {
  const preview = useMemo(() => (file ? URL.createObjectURL(file) : null), [file])
  useEffect(() => () => preview && URL.revokeObjectURL(preview), [preview])
  return (
    <label className="photo">
      <input type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => onChange(e.target.files[0])} />
      {preview ? <img src={preview} alt="올린 결제 내역 사진" /> : <span className="ph"><Camera size={22} aria-hidden="true" /></span>}
      <span>{file ? '사진을 바꾸려면 눌러 주세요' : '결제 내역 사진 올리기'}<small>카드번호는 가리고 올려 주세요</small></span>
    </label>
  )
}

function ReceiptPaper({ party }) {
  return (
    <Paper title="결제 내역 사진">
      {party.settlement?.receiptAvailable
        ? <img className="receipt" src={`/api/parties/${party.id}/receipt`} alt="결제 내역 사진" />
        : <p className="note">결제 사진이 없습니다.</p>}
    </Paper>
  )
}

export function PayerInput({ party, run, onDone, onLater }) {
  const correcting = party.settlement != null
  const [amount, setAmount] = useState('')
  const [receipt, setReceipt] = useState(null)
  const [bankName, setBankName] = useState(party.settlement?.bankName || '')
  const [accountNumber, setAccountNumber] = useState(party.settlement?.accountNumber || '')
  const [shares, setShares] = useState(null)
  const value = Number(amount)
  const wide = useWide()

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
  }, '일행에게 정산을 요청했어요')

  const shareRows = shares && <Rows items={shares.map((s) => [s.payer ? '나 (잔액)' : s.nickname, won(s.amount), s.payer ? 'big' : ''])} />

  return (
    <Screen aside={shares ? <SidePlain><Paper title="이렇게 나눠요">{shareRows}</Paper></SidePlain> : <SideEmpty title="금액을 입력해 주세요">일행과 나눌 금액을 여기에 보여 드려요.</SideEmpty>}
      foot={(
      <>
        <Btn disabled={!ready} onClick={submit}>{unusual ? '이 금액으로 정산 요청' : '일행에게 정산 요청'}</Btn>
        <Btn className="quiet" onClick={onLater}>{correcting ? '고치지 않기' : '나중에 입력'}</Btn>
      </>
    )}>
      <BackBar onBack={onLater} title="결제 금액 입력" />
      <div className="pad">
        <label className="field amount">실제 결제 금액
          <input inputMode="numeric" value={amount ? Number(amount).toLocaleString() : ''} placeholder="0"
            onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} />
          <em>원</em>
        </label>
        {unusual ? (
          <Say tone="stop-tone" title={`예상 요금 ${won(party.expectedTotal)}보다 ${Math.round(Math.abs(difference) * 100)}% ${difference > 0 ? '높아요' : '낮아요'}`}>
            금액이 맞다면 그대로 요청하면 됩니다. 일행 화면에도 같은 안내와 사진이 보입니다.
          </Say>
        ) : (
          <p className="sub">예상 요금은 {won(party.expectedTotal)}입니다.</p>
        )}
        <ReceiptField file={receipt} onChange={setReceipt} />
        <div className="two">
          <label className="field">은행
            <input maxLength={30} value={bankName} onChange={(e) => setBankName(e.target.value)} placeholder="은행 이름" />
          </label>
          <label className="field">받을 계좌
            <input inputMode="numeric" maxLength={40} value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} placeholder="숫자와 - 만" />
          </label>
        </div>
      </div>
      {shares && !wide && (
        <>
          <div className="band" />
          <div className="block">
            <h2 className="block-title">이렇게 나눠요</h2>
            {shareRows}
          </div>
        </>
      )}
      <div className="block">
        <p className="note">금액, 사진, 계좌는 이번 일행에게만 보입니다. 사진은 7일 뒤, 계좌는 모두 받음 처리되거나 7일이 지나면 지웁니다. 일행이 보내기 전까지는 고칠 수 있습니다.</p>
      </div>
    </Screen>
  )
}

export function Collect({ party, run, onCorrect, onHome, onMenu }) {
  const { settlement } = party
  const receiving = settlement.transfers.reduce((sum, t) => sum + t.amount, 0)
  const myPart = settlement.paidAmount - receiving
  const wide = useWide()
  const saved = ['혼자 탈 때보다', `${won(party.mySoloFare - myPart)} 절약`, 'save']
  return (
    <Screen aside={(
      <SidePlain>
        <Paper title="나눈 금액">
          <Rows items={[['나 (잔액)', won(myPart), 'big'], ...settlement.transfers.map((t) => [t.senderNickname, won(t.amount)]), saved]} />
        </Paper>
        <ReceiptPaper party={party} />
      </SidePlain>
    )} foot={(
      <>
        {settlement.canCorrect && <Btn className="line" onClick={onCorrect}>금액 고치기</Btn>}
        <Btn className="quiet" onClick={onHome}>홈으로</Btn>
      </>
    )}>
      <TopBar dark onMenu={onMenu} />
      <Meter label="받을 돈" value={<Money value={receiving} />} detail={`결제 ${won(settlement.paidAmount)}, 내 분담금 ${won(myPart)}`} />
      <div className="block">
        {settlement.warning && <Say tone="stop-tone" title={`결제 금액이 예상과 ${Math.abs(settlement.differencePercent)}% 달라요`}>일행 화면에도 같은 안내가 보입니다.</Say>}
        <div className="list">
          {settlement.transfers.map((t) => (
            <div className="item" key={t.id}>
              <div><b>{t.senderNickname}</b><small>{won(t.amount)}</small></div>
              {t.status === 'SENT'
                ? <Btn className="small" onClick={() => run(() => api.received(t.id), `${t.senderNickname}님의 입금을 확인했어요`)}>받음</Btn>
                : <span className={t.status === 'RECEIVED' ? 'badge ok' : 'badge'}>{TRANSFER_LABEL[t.status]}</span>}
            </div>
          ))}
        </div>
        {!wide && <Rows items={[saved]} />}
        <p className="note">은행 앱에서 입금을 확인한 뒤 받음을 눌러 주세요. 모두 받음이면 계좌를 지우고 완료합니다.</p>
      </div>
    </Screen>
  )
}

export function Transfer({ party, transfer, run, onHome, onMenu }) {
  const { settlement } = party
  const wide = useWide()
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(settlement.accountNumber)
      toast('계좌번호를 복사했어요')
    } catch {
      toast('복사하지 못했습니다. 계좌번호를 직접 입력해 주세요.', 'bad')
    }
  }
  const reasons = [
    ['실제 결제 금액', won(settlement.paidAmount)],
    ['내 단독 요금 비율', `${party.mySoloFare.toLocaleString()} / ${won(party.soloTotal)}`],
  ]
  return (
    <Screen aside={(
      <SidePlain>
        <ReceiptPaper party={party} />
        <Paper title="왜 이 금액인가요">
          <Rows items={[...reasons, ['내가 보낼 돈', won(transfer.amount), 'big'], ['혼자 탈 때보다', `${won(party.mySoloFare - transfer.amount)} 절약`, 'save']]} />
        </Paper>
      </SidePlain>
    )} foot={(
      <>
        <Btn onClick={() => run(() => api.sent(transfer.id, settlement.revision), '보냄을 표시했어요')}>보냈어요</Btn>
        <Btn className="quiet" onClick={onHome}>나중에 보내기</Btn>
      </>
    )}>
      <TopBar dark onMenu={onMenu} />
      <Meter label={`${party.payerNickname}님에게 보낼 돈`} value={<Money value={transfer.amount} />} detail={`예상 분담금 ${won(party.myShare)}`} />
      <div className="block">
        {settlement.warning && (
          <Say tone="stop-tone" title={`결제 금액이 예상보다 ${Math.abs(settlement.differencePercent)}% ${settlement.differencePercent > 0 ? '높아요' : '낮아요'}`}>
            할증이나 경로 차이일 수 있어요. 결제 사진을 확인해 보세요.
          </Say>
        )}
        {settlement.accountNumber ? (
          <div className="item">
            <div><small>받는 계좌</small><b>{settlement.bankName} {settlement.accountNumber}</b></div>
            <Btn className="small line" onClick={copy}><Copy size={16} aria-hidden="true" />복사</Btn>
          </div>
        ) : (
          <Say tone="calm" title="계좌 보관 기간이 지났어요">{party.payerNickname}님이 금액을 다시 올리면 계좌가 다시 보입니다.</Say>
        )}
        {!wide && <Rows items={reasons} />}
        {!wide && (settlement.receiptAvailable
          ? <a className="map-open" href={`/api/parties/${party.id}/receipt`} target="_blank" rel="noreferrer">결제 내역 사진 보기</a>
          : <p className="note">결제 사진이 없습니다.</p>)}
        <p className="note">은행 앱에서 보낸 뒤 아래 버튼을 눌러 주세요. 24시간 안에 보내 주세요. {REAL_NAME}</p>
      </div>
    </Screen>
  )
}

export function Done({ party, transfer, onHome, onMenu }) {
  const mineName = party.members.find((m) => m.requestId === party.myRequestId)?.pointName
  return (
    <Screen aside={<SidePlain><ReceiptPaper party={party} /></SidePlain>} foot={<Btn className="line" onClick={onHome}>홈으로</Btn>}>
      <TopBar dark onMenu={onMenu} />
      <Meter label="이번에 아낀 금액" value={<Money value={party.mySoloFare - transfer.amount} />} detail={`혼자 타면 ${won(party.mySoloFare)}, 낸 돈 ${won(transfer.amount)}`} />
      <div className="block">
        <Rows items={[
          ['경로', routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: mineName })],
          ['실제 결제 금액', won(party.settlement.paidAmount)],
          ['일행', `${party.members.length}명`],
        ]} />
        <p className="note">{party.payerNickname}님이 받음을 누르면 이 일행은 완료됩니다. 계좌는 모두 받음 처리되거나 7일이 지나면 지웁니다.</p>
      </div>
    </Screen>
  )
}
