import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from './api.js'
import AuthScreen from './AuthScreen.jsx'
import ProfileScreen from './ProfileScreen.jsx'
import History from './History.jsx'
import DemoBar from './DemoBar.jsx'
import Home from './Home.jsx'
import { HomeRequest, SchoolRequest } from './RequestFlow.jsx'
import { PartyDetail, PartyScreen } from './PartyScreens.jsx'
import { Collect, Done, PayerInput, Transfer } from './SettleScreens.jsx'
import { NoShow, Unmatched, Waiting } from './RequestStatus.jsx'
import { Brand, Btn, Confirm, Item, LoadingScreen, Say, Sheet, Toaster, toast, useWide } from './ui.jsx'
import { User } from 'lucide-react'

const POLL_MS = 5000
const LIVE_POLL_MS = 30000

/** 넓은 화면의 상단. 휴대폰에서는 화면마다 있는 상단 바를 쓴다. */
function Nav({ tab, onTab, onMenu }) {
  return (
    <header className="nav">
      <Brand />
      <button type="button" className={tab === 'home' ? 'on' : ''} onClick={() => onTab('home')}>이용하기</button>
      <button type="button" className={tab === 'history' ? 'on' : ''} onClick={() => onTab('history')}>이용 내역</button>
      <span className="gap" />
      <button type="button" className="icon-btn" aria-label="내 정보" onClick={onMenu}><User size={22} /></button>
    </header>
  )
}

export default function App() {
  const [config, setConfig] = useState(null)
  const [me, setMe] = useState(undefined)
  const [state, setState] = useState(null)
  const location = useLocation()
  const navigate = useNavigate()
  const [down, setDown] = useState(false)
  const [offline, setOffline] = useState(false)
  const [menu, setMenu] = useState(false)
  const [leaving, setLeaving] = useState(false)

  const session = useRef(0)
  const latestRead = useRef(0)
  const wide = useWide()
  const tab = location.pathname === '/history' ? 'history' : 'home'
  const setTab = useCallback((next) => navigate(next === 'history' ? '/history' : '/'), [navigate])

  const refresh = useCallback(async () => {
    const current = session.current
    latestRead.current += 1
    const read = latestRead.current
    try {
      const next = await api.state()
      // 늦게 온 응답이 최신 상태를 덮지 않게 한다.
      if (current === session.current && read === latestRead.current) {
        setState(next)
        setOffline(false)
      }
    } catch (e) {
      if (current !== session.current) return
      if (e.status === 401) setMe(null)
      else setOffline(true)
    }
  }, [])

  const signOut = useCallback(async (request) => {
    session.current += 1
    setState(null)
    navigate('/')
    setMenu(false)
    await request()
    setMe(null)
  }, [navigate])

  useEffect(() => {
    api.config().then(setConfig).catch(() => toast('서버에 연결하지 못했습니다. 잠시 뒤 다시 열어 주세요.', 'bad'))
    api.me().then(setMe).catch((e) => (e.status === 401 ? setMe(null) : setDown(true)))
  }, [])

  useEffect(() => {
    if (!me || me.needsProfile) return undefined
    refresh()
    // 서버가 바뀐 순간 알려 주면 그때 읽고, 연결이 끊기면 주기적으로 읽는다.
    let live = false
    let soon = null
    let last = Date.now()
    const source = new EventSource('/api/events')
    source.onopen = () => { live = true }
    source.onerror = () => { live = false }
    source.addEventListener('state', () => {
      clearTimeout(soon)
      soon = setTimeout(refresh, 150)
    })
    const timer = setInterval(() => {
      if (!live || Date.now() - last >= LIVE_POLL_MS) {
        last = Date.now()
        refresh()
      }
    }, POLL_MS)
    const wake = () => document.visibilityState === 'visible' && refresh()
    document.addEventListener('visibilitychange', wake)
    return () => {
      source.close()
      clearTimeout(soon)
      clearInterval(timer)
      document.removeEventListener('visibilitychange', wake)
    }
  }, [me, refresh])

  /** 서버 동작을 하고 상태를 다시 읽는다. done은 성공 안내 문구다. */
  const run = useCallback(
    async (action, done) => {
      try {
        const result = await action()
        if (done) toast(typeof done === 'function' ? done(result) : done)
      } catch (e) {
        toast(e.message, 'bad')
      }
      await refresh()
    },
    [refresh],
  )

  const openMenu = useCallback(() => setMenu(true), [])

  return (
    <main className="app">
      <Toaster />
      {me === undefined && !down && <LoadingScreen />}
      {down && (
        <div className="pad">
          <Say tone="stop-tone" title="서버에 연결하지 못했습니다">인터넷 연결을 확인한 뒤 다시 시도해 주세요.</Say>
          <Btn onClick={() => window.location.reload()}>다시 시도</Btn>
        </div>
      )}
      {me === null && <AuthScreen config={config} onLogin={() => api.me().then(setMe)} />}
      {me?.needsProfile && <ProfileScreen onDone={() => api.me().then(setMe)} />}
      {me && !me.needsProfile && (
        <>
          {wide && <Nav tab={tab} onTab={setTab} onMenu={openMenu} />}
          {offline && <Say tone="stop-tone">연결이 끊겼습니다. 다시 연결하는 중입니다.</Say>}
          {config?.simulatedRoute && <Say tone="calm">모의 경로로 동작 중입니다. 금액과 시간은 실제 값이 아닙니다.</Say>}
          {tab === 'history' && <History onBack={() => setTab('home')} />}
          {tab === 'home' && !state && <LoadingScreen />}
          {tab === 'home' && state && (
            <Flow state={state} config={config} myId={me.id} run={run} onMenu={openMenu} onHistory={() => setTab('history')} />
          )}
          {config?.demo && <DemoBar now={state?.now} run={run} />}
          {menu && (
            <Sheet title={me.nickname} className="pop" onClose={() => setMenu(false)}>
              <p className="sub">{me.email}</p>
              <div className="list">
                <Item title="이용 내역" onClick={() => { setMenu(false); setTab('history') }} />
                <Item title="로그아웃" onClick={() => signOut(api.logout)} />
                <Item title="모든 기기에서 로그아웃" danger onClick={() => setLeaving(true)} />
              </div>
            </Sheet>
          )}
          {leaving && (
            <Confirm title="모든 기기에서 로그아웃할까요?" action="로그아웃" onClose={() => setLeaving(false)}
              onConfirm={() => signOut(api.logoutAll)}>
              다른 기기에서도 학교 메일 코드로 다시 로그인해야 합니다.
            </Confirm>
          )}
        </>
      )}
    </main>
  )
}

/** 서버 상태에서 지금 보여 줄 한 화면을 고른다. */
function Flow({ state, config, myId, run, onMenu, onHistory }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [hidden, setHidden] = useState([])
  const [seenRequest, setSeenRequest] = useState(null)
  const detailId = Number(location.pathname.match(/^\/parties\/(\d+)$/)?.[1]) || null
  const requestPath = location.pathname.match(/^\/request\/(school|home)$/)?.[1]
  const draft = requestPath ? { direction: requestPath === 'school' ? 'TO_SCHOOL' : 'FROM_HUB', initial: location.state?.initial } : null
  const startRequest = (next) => navigate(`/request/${next.direction === 'TO_SCHOOL' ? 'school' : 'home'}`, { state: { initial: next.initial } })
  const [correctingId, setCorrectingId] = useState(null)

  const party = state.parties.find((p) => !hidden.includes(p.id))
  const hide = (id) => setHidden((list) => [...list, id])
  const latest = state.latestRequest || null

  if (party) {
    if (detailId === party.id) return <PartyDetail party={party} myId={myId} onBack={() => navigate('/')} />
    if (party.canSettle || (correctingId === party.id && party.settlement?.canCorrect)) {
      return (
        <PayerInput party={party} run={run} onDone={() => setCorrectingId(null)}
          onLater={() => (party.canSettle ? hide(party.id) : setCorrectingId(null))} />
      )
    }
    if (party.settlement) {
      if (party.payer) {
        return <Collect party={party} run={run} onMenu={onMenu} onCorrect={() => setCorrectingId(party.id)} onHome={() => hide(party.id)} />
      }
      const transfer = party.settlement.transfers.find((t) => t.senderId === myId)
      return transfer.status === 'REQUESTED'
        ? <Transfer party={party} transfer={transfer} run={run} onMenu={onMenu} onHome={() => hide(party.id)} />
        : <Done party={party} transfer={transfer} onMenu={onMenu} onHome={() => hide(party.id)} />
    }
    return <PartyScreen party={party} now={state.now} myId={myId} run={run} onMenu={onMenu} onDetail={() => navigate(`/parties/${party.id}`)} onHome={() => hide(party.id)} />
  }
  if (latest?.status === 'WAITING') return <Waiting latest={latest} config={config} run={run} onMenu={onMenu} />
  if (draft) {
    const Request = draft.direction === 'TO_SCHOOL' ? SchoolRequest : HomeRequest
    return <Request initial={draft.initial} config={config} now={state.now} run={run} onClose={() => navigate('/', { replace: true })} />
  }
  const left = latest?.endReason === 'NO_SHOW'
  if ((left || latest?.status === 'UNMATCHED') && seenRequest !== latest.id) {
    const Ended = left ? NoShow : Unmatched
    return (
      <Ended latest={latest} onMenu={onMenu} onHome={() => setSeenRequest(latest.id)}
        onRetry={() => {
          setSeenRequest(latest.id)
          startRequest({ direction: latest.direction, initial: latest })
        }} />
    )
  }
  return (
    <Home state={state} onStart={startRequest} onHistory={onHistory} onMenu={onMenu}
      onReopen={(id) => setHidden((list) => list.filter((item) => item !== id))} />
  )
}
