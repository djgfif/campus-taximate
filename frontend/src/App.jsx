import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import AuthScreen from './AuthScreen.jsx'
import ProfileScreen from './ProfileScreen.jsx'
import RequestForm from './RequestForm.jsx'
import PartyCard from './PartyCard.jsx'
import History from './History.jsx'
import DemoBar from './DemoBar.jsx'
import { REQUEST_LABEL, formatTime } from './format.js'

const POLL_MS = 5000

export default function App() {
  const [config, setConfig] = useState(null)
  const [me, setMe] = useState(undefined)
  const [state, setState] = useState(null)
  const [tab, setTab] = useState('home')
  const [error, setError] = useState('')

  const session = useRef(0)

  const refresh = useCallback(async () => {
    const current = session.current
    try {
      const next = await api.state()
      if (current === session.current) setState(next)
    } catch (e) {
      if (e.status === 401 && current === session.current) setMe(null)
    }
  }, [])

  const signOut = useCallback(async (request) => {
    session.current += 1
    setState(null)
    setTab('home')
    setError('')
    await request()
    setMe(null)
  }, [])
  const logout = useCallback(() => signOut(api.logout), [signOut])
  const logoutAll = useCallback(() => signOut(api.logoutAll), [signOut])

  useEffect(() => {
    api.config().then(setConfig).catch(() => setError('서버에 연결하지 못했습니다.'))
    api.me().then(setMe).catch(() => setMe(null))
  }, [])

  useEffect(() => {
    if (!me || me.needsProfile) return undefined
    refresh()
    const timer = setInterval(refresh, POLL_MS)
    return () => clearInterval(timer)
  }, [me, refresh])

  const run = useCallback(
    async (action) => {
      setError('')
      try {
        await action()
      } catch (e) {
        setError(e.message)
      }
      await refresh()
    },
    [refresh],
  )

  if (me === undefined) return <main className="app"><p className="muted">불러오는 중</p></main>

  if (!me) {
    return (
      <main className="app">
        <Header />
        <AuthScreen config={config} onLogin={() => api.me().then(setMe)} />
      </main>
    )
  }

  if (me.needsProfile) {
    return (
      <main className="app">
        <Header onLogout={logout} />
        <ProfileScreen onDone={() => api.me().then(setMe)} />
      </main>
    )
  }

  const latest = state?.latestRequest || null
  const waiting = latest?.status === 'WAITING'
  const inParty = latest?.status === 'MATCHED'
  const canRequest = state && !waiting && !inParty

  return (
    <main className="app">
      <Header nickname={me.nickname} onLogout={logout} />
      {config?.simulatedRoute && <p className="notice">모의 경로로 동작 중입니다. 금액과 시간은 실제 값이 아닙니다.</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <nav className="tabs">
        <button className={tab === 'home' ? 'active' : ''} onClick={() => setTab('home')}>내 탑승</button>
        <button className={tab === 'history' ? 'active' : ''} onClick={() => setTab('history')}>이용 내역</button>
      </nav>

      {tab === 'history' && <History onLogoutAll={logoutAll} />}

      {tab === 'home' && state && (
        <>
          {state.restricted && (
            <p className="error">24시간이 지나도 보내지 않은 송금이 있어 새 요청을 할 수 없습니다. 보낸 뒤 보냄을 눌러 주세요.</p>
          )}
          {state.parties.map((party) => (
            <PartyCard key={party.id} party={party} myId={me.id} run={run} />
          ))}
          {waiting && (
            <section className="card">
              <h2>일행을 찾는 중</h2>
              <p>{latest.hubName} → {latest.dropoffName}</p>
              <p className="muted">출발 가능 {formatTime(latest.windowStart)} ~ {formatTime(latest.windowEnd)}</p>
              <p className="muted">단독 이동 예상 {latest.soloFare.toLocaleString()}원, {latest.soloMinutes}분</p>
              <button className="secondary" onClick={() => run(() => api.cancelRequest(latest.id))}>요청 취소</button>
            </section>
          )}
          {canRequest && latest && latest.status !== 'COMPLETED' && (
            <p className="notice">지난 요청: {REQUEST_LABEL[latest.status]}</p>
          )}
          {canRequest && !state.restricted && <RequestForm config={config} now={state.now} run={run} />}
        </>
      )}

      {config?.demo && <DemoBar now={state?.now} run={run} />}
    </main>
  )
}

function Header({ nickname, onLogout }) {
  return (
    <header className="top">
      <h1>캠퍼스 택시메이트</h1>
      {nickname && (
        <div className="who">
          <span>{nickname}</span>
          <button className="link" onClick={onLogout}>로그아웃</button>
        </div>
      )}
    </header>
  )
}
