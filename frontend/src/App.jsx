import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from './api.js'
import AuthScreen from './AuthScreen.jsx'
import ProfileScreen from './ProfileScreen.jsx'
import PasswordScreen from './PasswordScreen.jsx'
import History from './History.jsx'
import DemoBar from './DemoBar.jsx'
import Home from './Home.jsx'
import { HomeRequest, SchoolRequest } from './RequestFlow.jsx'
import { PartyDetail, PartyScreen } from './PartyScreens.jsx'
import { PayerInput, SettlementScreen } from './SettleScreens.jsx'
import PaymentResult from './PaymentScreens.jsx'
import { isPaymentParty } from './format.js'
import { NoShow, Unmatched, Waiting } from './RequestStatus.jsx'
import { Brand, Btn, Confirm, Item, LoadingScreen, Say, Sheet, Toaster, toast, useWide } from './ui.jsx'
import { User } from 'lucide-react'
import { disablePush, enablePush, pushGranted, pushSupported } from './push.js'

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
  const [resetting, setResetting] = useState(false)
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
  const paymentReturn = ['success', 'fail'].includes(new URLSearchParams(location.search).get('payment'))
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
  const [pushOn, setPushOn] = useState(false)

  // 이미 허용한 기기는 다시 묻지 않고 등록을 맞춘다. 시연 서버는 실행마다 키가 바뀐다.
  useEffect(() => {
    if (!me || me.needsProfile || !pushGranted()) return
    enablePush({ ask: false }).then(() => setPushOn(true)).catch(() => setPushOn(false))
  }, [me])

  const togglePush = async () => {
    try {
      if (pushOn) {
        await disablePush()
        setPushOn(false)
        toast('알림을 껐어요')
      } else {
        await enablePush()
        setPushOn(true)
        toast('이 기기로 알림을 보내 드려요')
      }
    } catch (e) {
      toast(e.message, 'bad')
    }
  }

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
      {me === null && <AuthScreen config={config} onLogin={(verified) => { setResetting(verified); return api.me().then(setMe) }} />}
      {me && (me.needsPassword || resetting) && <PasswordScreen onDone={() => { setResetting(false); return api.me().then(setMe) }} />}
      {me && !me.needsPassword && !resetting && me.needsProfile && <ProfileScreen onDone={() => api.me().then(setMe)} />}
      {me && !me.needsPassword && !resetting && !me.needsProfile && (
        <>
          {wide && <Nav tab={tab} onTab={setTab} onMenu={openMenu} />}
          {offline && <Say tone="stop-tone">연결이 끊겼습니다. 다시 연결하는 중입니다.</Say>}
          {config?.simulatedRoute && <Say tone="calm">모의 경로로 동작 중입니다. 금액과 시간은 실제 값이 아닙니다.</Say>}
          {paymentReturn && <PaymentResult key={me.id + location.search} search={location.search} myId={me.id} onUpdated={refresh}
            onReturn={(id) => navigate(id ? `/parties/${id}` : '/', { replace: true })} />}
          {!paymentReturn && tab === 'history' && <History onBack={() => setTab('home')} />}
          {!paymentReturn && tab === 'home' && !state && <LoadingScreen />}
          {!paymentReturn && tab === 'home' && state && (
            <Flow state={state} config={config} myId={me.id} run={run} refresh={refresh} onMenu={openMenu} onPush={togglePush} pushOn={pushOn} onHistory={() => setTab('history')} />
          )}
          {config?.demo && <DemoBar now={state?.now} run={run} />}
          {menu && (
            <Sheet title={me.nickname} className="pop" onClose={() => setMenu(false)}>
              <p className="sub">{me.email}</p>
              <div className="list">
                <Item title="이용 내역" onClick={() => { setMenu(false); setTab('history') }} />
                {pushSupported() && <Item title={pushOn ? '알림 끄기' : '알림 받기'} detail="앱을 닫아도 확정과 승차 안내를 받아요" onClick={togglePush} />}
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

/** 상태 이름을 화면에 노출하지 않고, 현재 서버가 허용한 동작만 연결한다. */
function Flow({ state, config, myId, run, refresh, onMenu, onHistory, onPush, pushOn }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [hidden, setHidden] = useState([])
  const [seenRequest, setSeenRequest] = useState(null)
  const [correctingId, setCorrectingId] = useState(null)
  const [loaded, setLoaded] = useState({})
  const detailId = Number(location.pathname.match(/^\/parties\/(\d+)$/)?.[1]) || null
  const parties = state.parties || []
  const available = parties.find((p) => p.id === detailId) || (state.settledParty?.id === detailId ? state.settledParty : null)
  useEffect(() => {
    if (!detailId || available) return undefined
    let closed = false
    api.party(detailId).then((party) => !closed && setLoaded({ id: detailId, party }))
      .catch((e) => !closed && setLoaded({ id: detailId, error: e.message }))
    return () => { closed = true }
  }, [detailId, available, state.now])
  const party = detailId ? available || (loaded.id === detailId ? loaded.party : null) : parties.find((p) => !hidden.includes(p.id))
  const requestPath = location.pathname.match(/^\/request\/(school|home)$/)?.[1]
  const startRequest = (next) => navigate(next.direction === 'TO_SCHOOL' ? '/request/school' : '/request/home', { state: { initial: next.initial } })
  const home = () => { if (party) setHidden((old) => [...old, party.id]); navigate('/') }
  const latest = state.latestRequest || null
  if (detailId && !party) return loaded.id === detailId && loaded.error
    ? <div className="pad"><Say tone="stop-tone">{loaded.error}</Say><Btn onClick={() => navigate('/')}>홈으로</Btn></div> : <LoadingScreen />
  if (party) {
    if (new URLSearchParams(location.search).get('detail') === '1') return <PartyDetail party={party} myId={myId} now={state.now} onBack={() => navigate('/parties/' + party.id)} />
    if (isPaymentParty(party) && (party.canSettle || correctingId === party.id && party.settlement?.canCorrect)) {
      return <PayerInput key={party.id + ':' + correctingId} party={party} now={state.now} run={run} onUpdated={refresh}
        onDone={() => setCorrectingId(null)} onLater={() => party.canSettle ? home() : setCorrectingId(null)} />
    }
    if (party.settlement) return <SettlementScreen key={party.id} party={party} myId={myId} now={state.now} config={config}
      onUpdated={refresh} onMenu={onMenu} onCorrect={() => setCorrectingId(party.id)} onHome={home}
      onDetail={() => navigate('/parties/' + party.id + '?detail=1')} />
    return <PartyScreen party={party} now={state.now} myId={myId} config={config} run={run} onUpdated={refresh} onMenu={onMenu}
      onDetail={() => navigate('/parties/' + party.id + '?detail=1')} onHome={home} />
  }
  if (latest?.status === 'WAITING') return <Waiting latest={latest} config={config} run={run} onMenu={onMenu} onPush={pushOn ? null : onPush} />
  if (requestPath && !state.restricted) {
    const Request = requestPath === 'school' ? SchoolRequest : HomeRequest
    return <Request initial={location.state?.initial} config={config} now={state.now} run={run} onClose={() => navigate('/', { replace: true })} />
  }
  const left = latest?.endReason === 'NO_SHOW'
  if ((left || latest?.status === 'UNMATCHED') && seenRequest !== latest.id) {
    const Ended = left ? NoShow : Unmatched
    return <Ended latest={latest} onMenu={onMenu} onHome={() => setSeenRequest(latest.id)} onRetry={() => {
      setSeenRequest(latest.id); startRequest({ direction: latest.direction, initial: latest })
    }} />
  }
  return <Home state={state} myId={myId} onStart={startRequest} onHistory={onHistory} onMenu={onMenu}
    onReopen={(id) => { setHidden((old) => old.filter((p) => p !== id)); navigate('/parties/' + id) }} />
}
