import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import AuthScreen from './AuthScreen.jsx'
import ProfileScreen from './ProfileScreen.jsx'
import History from './History.jsx'
import DemoBar from './DemoBar.jsx'
import Home from './Home.jsx'
import { HomeRequest, SchoolRequest } from './RequestFlow.jsx'
import { PartyDetail, PartyScreen } from './PartyScreens.jsx'
import { Collect, Done, PayerInput, Transfer } from './SettleScreens.jsx'
import { Unmatched, Waiting } from './RequestStatus.jsx'

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

  return (
    <main className="app">
      <Header nickname={me.nickname} onLogout={logout} />
      {config?.demo && <DemoBar now={state?.now} run={run} />}
      {config?.simulatedRoute && <p className="notice">모의 경로로 동작 중입니다. 금액과 시간은 실제 값이 아닙니다.</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {tab === 'history' && <History onBack={() => setTab('home')} onLogoutAll={logoutAll} />}
      {tab === 'home' && state && <Flow state={state} config={config} myId={me.id} run={run} onHistory={() => setTab('history')} />}
    </main>
  )
}

/** 서버 상태에서 지금 보여 줄 한 화면을 고른다. */
function Flow({ state, config, myId, run, onHistory }) {
  const [draft, setDraft] = useState(null)
  const [hidden, setHidden] = useState([])
  const [seenRequest, setSeenRequest] = useState(null)
  const [detailId, setDetailId] = useState(null)
  const [correctingId, setCorrectingId] = useState(null)

  const party = state.parties.find((p) => !hidden.includes(p.id))
  const hide = (id) => setHidden((list) => [...list, id])
  const latest = state.latestRequest || null

  if (party) {
    if (detailId === party.id) return <PartyDetail party={party} myId={myId} onBack={() => setDetailId(null)} />
    if (party.canSettle || (correctingId === party.id && party.settlement?.canCorrect)) {
      return (
        <PayerInput party={party} run={run} onDone={() => setCorrectingId(null)}
          onLater={() => (party.canSettle ? hide(party.id) : setCorrectingId(null))} />
      )
    }
    if (party.settlement) {
      if (party.payer) {
        return <Collect party={party} run={run} onCorrect={() => setCorrectingId(party.id)} onHome={() => hide(party.id)} />
      }
      const transfer = party.settlement.transfers.find((t) => t.senderId === myId)
      return transfer.status === 'REQUESTED'
        ? <Transfer party={party} transfer={transfer} run={run} onHome={() => hide(party.id)} />
        : <Done party={party} transfer={transfer} onHome={() => hide(party.id)} />
    }
    return <PartyScreen party={party} now={state.now} myId={myId} run={run} onDetail={() => setDetailId(party.id)} onHome={() => hide(party.id)} />
  }
  if (latest?.status === 'WAITING') return <Waiting latest={latest} config={config} run={run} />
  if (draft) {
    const Request = draft.direction === 'TO_SCHOOL' ? SchoolRequest : HomeRequest
    return <Request initial={draft.initial} config={config} now={state.now} run={run} onClose={() => setDraft(null)} />
  }
  if (latest?.status === 'UNMATCHED' && seenRequest !== latest.id) {
    return (
      <Unmatched latest={latest} onHome={() => setSeenRequest(latest.id)}
        onRetry={() => {
          setSeenRequest(latest.id)
          setDraft({ direction: latest.direction, initial: latest })
        }} />
    )
  }
  return (
    <Home state={state} onStart={setDraft} onHistory={onHistory}
      onReopen={(id) => setHidden((list) => list.filter((item) => item !== id))} />
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
