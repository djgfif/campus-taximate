import React, { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft, CreditCard } from 'lucide-react'
import { api } from '../api.js'
import { formatDateTime } from '../format.js'
import { useSafeRouter } from '../routerSafe.js'
import { Btn, Chips, Confirm, Empty, Say, Screen } from '../ui.jsx'

const PROVIDER_LABEL = { SIMULATED: '테스트', TOSS: '토스', TOSS_BILLING: '토스 자동결제' }
const DEFAULT_TEST_LABEL = '테스트 카드 ****1234'

/** 카드번호 원문(숫자 13자리 이상)이 섞였는지. 공백과 하이픈은 무시한다. 서버도 같은 이유로 거절한다. */
export const looksLikeCardNumber = (value) => /\d{13,}/.test(String(value || '').replace(/[\s-]/g, ''))

/** 모의 결제용 빌링 키. 카드번호처럼 보이는 연속 숫자가 생기지 않게 36진수 문자열로 만든다. */
const demoBillingKey = () => `demo-billing-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

/** 삭제·등록 오류를 사람이 이해할 이유로 바꾼다. */
function explain(error) {
  switch (error?.code) {
    case 'PAYMENT_OVERDUE':
      return { title: '미납된 결제가 있어 삭제할 수 없어요', text: '미납 금액을 먼저 결제한 뒤 다시 시도해 주세요.' }
    case 'ACTIVE_RIDE_REQUIRES_PAYMENT_METHOD':
      return { title: '진행 중인 요청이나 일행이 있어 마지막 결제수단은 삭제할 수 없어요', text: '다른 결제수단을 먼저 등록하거나, 이용이 끝난 뒤 삭제해 주세요.' }
    case 'INVALID_PAYMENT_METHOD':
      return { title: '결제수단 정보를 확인해 주세요', text: error.message || '입력한 값으로는 등록할 수 없어요.' }
    default:
      if (error?.status === 404) return { title: '이미 삭제된 결제수단이에요', text: '목록을 새로 불러왔어요.' }
      return { title: '처리하지 못했어요', text: error?.message || '잠시 뒤 다시 시도해 주세요.' }
  }
}

/**
 * 결제수단 관리 (/app/account/payments).
 * 후불 정산(v3)에 쓸 카드를 등록·삭제한다. 카드번호와 빌링 키는 화면에 다시 보이지 않고, 마스킹된 이름만 보인다.
 * 요청 화면에서 넘어온 경우(location.state.returnTo) 등록 뒤 바로 작성하던 요청으로 돌아갈 수 있다.
 */
export default function PaymentMethodsPage({ config, onBack, tabBar }) {
  const { navigate, location } = useSafeRouter()
  const returnTo = typeof location?.state?.returnTo === 'string' && location.state.returnTo.startsWith('/') ? location.state.returnTo : null
  const simulated = Boolean(config?.simulatedPayment || config?.demo)
  const [methods, setMethods] = useState(null)
  const [loadError, setLoadError] = useState('')
  const [notice, setNotice] = useState(null)
  const [registered, setRegistered] = useState(false)
  const [deleting, setDeleting] = useState(null)
  const [provider, setProvider] = useState(simulated ? 'SIMULATED' : 'TOSS_BILLING')
  const [testLabel, setTestLabel] = useState(DEFAULT_TEST_LABEL)
  const [billingKey, setBillingKey] = useState('')
  const [cardLabel, setCardLabel] = useState('')
  const alive = useRef(true)
  const latest = useRef(0)

  const chosenProvider = simulated ? provider : 'TOSS_BILLING'

  const load = useCallback(async () => {
    latest.current += 1
    const read = latest.current
    try {
      const list = await api.paymentMethods()
      if (!alive.current || read !== latest.current) return
      setMethods(Array.isArray(list) ? list : [])
      setLoadError('')
    } catch (e) {
      if (!alive.current || read !== latest.current) return
      setMethods((prev) => prev || [])
      setLoadError(e.message || '결제수단을 불러오지 못했어요.')
    }
  }, [])

  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, [load])

  const active = (methods || []).filter((m) => m && m.active !== false)

  const register = async (body) => {
    setNotice(null)
    try {
      await api.registerPaymentMethod(body)
      if (!alive.current) return
      setRegistered(true)
      setBillingKey('')
      setCardLabel('')
      setNotice(returnTo ? null : { tone: 'save-tone', title: '결제수단을 등록했어요', text: '다음 요청부터 이 카드로 후불 결제돼요.' })
      await load()
    } catch (e) {
      if (alive.current) setNotice({ tone: 'stop-tone', ...explain(e) })
    }
  }

  const registerTest = () => {
    const label = testLabel.trim() || DEFAULT_TEST_LABEL
    if (looksLikeCardNumber(label)) {
      setNotice({ tone: 'stop-tone', title: '카드 이름에 카드번호를 넣지 마세요', text: '뒤 4자리만 남긴 이름(예: ****1234)으로 적어 주세요.' })
      return undefined
    }
    return register({ provider: 'SIMULATED', billingKey: demoBillingKey(), cardLabel: label })
  }

  const keyIsCardNumber = looksLikeCardNumber(billingKey)
  const labelIsCardNumber = looksLikeCardNumber(cardLabel)
  const registerBilling = () => register({ provider: 'TOSS_BILLING', billingKey: billingKey.trim(), cardLabel: cardLabel.trim() || '등록 카드' })

  const remove = async (method) => {
    setNotice(null)
    try {
      await api.deletePaymentMethod(method.id)
      if (!alive.current) return
      setNotice({ tone: 'save-tone', title: '결제수단을 삭제했어요', text: method.cardLabel })
    } catch (e) {
      if (alive.current) setNotice({ tone: 'stop-tone', ...explain(e) })
    }
    await load()
  }

  const back = () => {
    if (returnTo) navigate(returnTo)
    else if (onBack) onBack()
    else navigate('/app/account')
  }

  return (
    <Screen tabBar={tabBar}>
      <div className="pad payments-page">
        <header className="payments-head">
          <button type="button" className="back-link" onClick={back}>
            <ChevronLeft size={16} aria-hidden="true" />
            <span>{returnTo ? '요청 화면으로 돌아가기' : '돌아가기'}</span>
          </button>
          <h1>결제수단 관리</h1>
          <p>하차 뒤 등록한 카드로 정산 금액이 자동 결제돼요.</p>
        </header>

        {notice && <Say tone={notice.tone} title={notice.title}>{notice.text}</Say>}

        {returnTo && registered && (
          <div className="payments-return" role="status">
            <Say tone="save-tone" title="결제수단을 등록했어요"
              action={<Btn onClick={() => navigate(returnTo)}>요청 이어서 작성</Btn>}>
              작성하던 경로와 시간을 그대로 불러와 드려요.
            </Say>
          </div>
        )}
        {returnTo && !registered && (
          <Say title="요청을 보내려면 결제수단이 필요해요">등록을 마치면 작성하던 요청으로 바로 돌아갈 수 있어요.</Say>
        )}

        <div className="surface-box surface-pad payments-surface">
          <section className="payments-section" aria-labelledby="payments-list-title">
            <div className="home-section-head">
              <h2 id="payments-list-title" className="section-title">등록된 결제수단</h2>
              {active.length > 0 && <span className="section-count">{active.length}</span>}
            </div>
            {methods === null && <p className="note" role="status">결제수단을 불러오는 중이에요.</p>}
            {loadError && <p role="alert" className="note">{loadError}</p>}
            {methods !== null && active.length === 0 && !loadError && (
              <Empty title="등록된 결제수단이 없어요">아래에서 카드를 등록하면 바로 요청할 수 있어요.</Empty>
            )}
            {active.length > 0 && (
              <ul className="payment-list">
                {active.map((method) => (
                  <li key={method.id} className="payment-row">
                    <span className="payment-icon" aria-hidden="true"><CreditCard size={18} /></span>
                    <span className="payment-main">
                      <b>{method.cardLabel || '등록 카드'}</b>
                      <small>
                        <span className="payment-provider">{PROVIDER_LABEL[method.provider] || method.provider}</span>
                        {method.registeredAt ? ` · ${formatDateTime(method.registeredAt)} 등록` : ''}
                      </small>
                    </span>
                    <button type="button" className="payment-delete" aria-label={`${method.cardLabel || '등록 카드'} 삭제`} onClick={() => setDeleting(method)}>삭제</button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="payments-section" aria-labelledby="payments-new-title">
            <h2 id="payments-new-title" className="section-title">새 결제수단 등록</h2>
            {simulated && (
              <Chips label="결제수단 종류" value={chosenProvider} onChange={setProvider}
                options={[{ value: 'SIMULATED', label: '테스트 카드' }, { value: 'TOSS_BILLING', label: '토스 자동결제' }]} />
            )}
            {chosenProvider === 'SIMULATED' ? (
              <div className="payment-form">
                <p className="note">시연용 모의 카드예요. 실제로 돈이 나가지 않아요.</p>
                <label className="field">
                  <span className="field-label">카드 이름</span>
                  <input value={testLabel} maxLength={40} onChange={(e) => setTestLabel(e.target.value)} />
                </label>
                <Btn onClick={registerTest}>테스트 카드 등록</Btn>
              </div>
            ) : (
              <div className="payment-form">
                <label className="field">
                  <span className="field-label">빌링 키</span>
                  <input value={billingKey} maxLength={200} autoComplete="off" spellCheck={false}
                    placeholder="토스페이먼츠에서 발급한 빌링 키" onChange={(e) => setBillingKey(e.target.value)} />
                </label>
                {keyIsCardNumber
                  ? <p className="payment-hint bad" role="alert">카드번호는 입력하지 마세요. 토스페이먼츠가 발급한 빌링 키만 넣어 주세요.</p>
                  : <p className="payment-hint">카드번호 원문은 저장하지 않으며, 발급받은 빌링 키만 입력해 주세요.</p>}
                <label className="field">
                  <span className="field-label">카드 이름 (선택)</span>
                  <input value={cardLabel} maxLength={40} placeholder="예: 국민카드 ****1234" onChange={(e) => setCardLabel(e.target.value)} />
                </label>
                {labelIsCardNumber && <p className="payment-hint bad" role="alert">카드 이름에는 뒤 4자리만 남겨 주세요.</p>}
                <Btn disabled={!billingKey.trim() || keyIsCardNumber || labelIsCardNumber} onClick={registerBilling}>결제수단 등록</Btn>
              </div>
            )}
          </section>
        </div>
      </div>
      {deleting && (
        <Confirm title="결제수단을 삭제할까요?" action="삭제하기" onClose={() => setDeleting(null)} onConfirm={() => remove(deleting)}>
          {deleting.cardLabel || '등록 카드'}를 삭제하면 이 카드로는 더 이상 결제되지 않아요.
        </Confirm>
      )}
    </Screen>
  )
}
