import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from './api.js'
import AuthScreen from './AuthScreen.jsx'
import ProfileScreen from './ProfileScreen.jsx'
import PasswordScreen from './PasswordScreen.jsx'
import History from './History.jsx'
import DemoBar from './DemoBar.jsx'
import DemoAccounts from './DemoAccounts.jsx'
import Home from './Home.jsx'
import { HomeRequest, SchoolRequest } from './RequestFlow.jsx'
import { Joining } from './Board.jsx'
import { ProfileSheet } from './People.jsx'
import { PartyDetail, PartyScreen } from './PartyScreens.jsx'
import { PayerInput, SettlementScreen } from './SettleScreens.jsx'
import PaymentResult from './PaymentScreens.jsx'
import { AdminScreen, BoardingCasesScreen } from './ReviewScreens.jsx'
import { isPaymentParty } from './format.js'
import { NoShow, Unmatched, Waiting } from './RequestStatus.jsx'
import { Avatar, Brand, Btn, Confirm, Item, LoadingScreen, Say, Screen, Sheet, TabBar, Toaster, toast, useWide } from './ui.jsx'
import { formatTime, partyLabel, routeLabel } from './format.js'
import { ChevronRight, User } from 'lucide-react'
import { disablePush, enablePush, pushGranted, pushSupported } from './push.js'

const POLL_MS = 5000
const LIVE_POLL_MS = 30000

/** 넓은 화면의 상단. 휴대폰에서는 화면마다 있는 상단 바를 쓴다. */
function Nav({ tab, onTab, onMenu, admin }) {
  return (
    <header className="nav">
      <Brand />
      <button type="button" className={tab === 'home' ? 'on' : ''} onClick={() => onTab('home')}>이용하기</button>
      <button type="button" className={tab === 'history' ? 'on' : ''} onClick={() => onTab('history')}>이용 내역</button>
      {admin && <button type="button" className={tab === 'admin' ? 'on' : ''} onClick={() => onTab('admin')}>관리자</button>}
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
  const [editingProfile, setEditingProfile] = useState(false)

  const session = useRef(0)
  const latestRead = useRef(0)
  const wide = useWide()
  const tab = ({ '/history': 'history', '/rides': 'rides', '/me': 'me', '/admin': 'admin', '/boarding-cases': 'boarding-cases' })[location.pathname] || 'home'
  const paymentReturn = ['success', 'fail'].includes(new URLSearchParams(location.search).get('payment'))
  const setTab = useCallback((next) => navigate(next === 'home' ? '/' : `/${next}`), [navigate])

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
        window.dispatchEvent(new Event('taximate:state'))
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
    api.me().then(setMe).catch((e) => (e.status === 401 ? setMe(null) : setDown(e.status === 429 ? 'busy' : true)))
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
    // 새 채팅·읽음은 상태 전체를 다시 읽지 않고 그 일행 채팅 창에만 알린다.
    source.addEventListener('chat', (event) => {
      window.dispatchEvent(new CustomEvent('taximate:chat', { detail: Number(event.data) }))
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
  // 휴대폰 탭바: 내 정보는 탭 화면, 넓은 화면은 오른쪽 위 팝업이다.
  const unread = (state?.parties || []).reduce((sum, p) => sum + (p.unreadChat || 0), 0)
  const tabBar = <TabBar current={tab} onTab={setTab} unread={unread} />
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

  /** 내 정보 목록. 넓은 화면은 팝업, 휴대폰은 '내 정보' 탭 화면에서 같은 목록을 쓴다. close는 항목을 누른 뒤 팝업을 닫는다. */
  const meMenu = (close) => <>
    <div className="me-head"><Avatar name={me.nickname} size={44} /><div><b>{me.nickname}</b><small>{me.email}</small></div></div>
    <div className="list">
      <Item title="내 프로필" detail="학과·학번을 같이 타는 사람에게 보여 줘요" onClick={() => { close(); setEditingProfile(true) }} />
      <Item title="이용 내역" onClick={() => { close(); setTab('history') }} />
      <Item title="내 탑승 확인·소명" detail="탑승 조사와 소명 기한을 확인해요" onClick={() => { close(); setTab('boarding-cases') }} />
      {me.admin && <Item title="관리자" detail="노쇼·요금 검토, 신고·제한과 승차 지점 관리" onClick={() => { close(); setTab('admin') }} />}
      {pushSupported() && <Item title={pushOn ? '알림 끄기' : '알림 받기'} detail="앱을 닫아도 확정과 승차 안내를 받아요" onClick={togglePush} />}
      <Item title="로그아웃" onClick={() => signOut(api.logout)} />
      <Item title="모든 기기에서 로그아웃" danger onClick={() => setLeaving(true)} />
    </div>
    {config?.demo && <DemoAccounts onLogin={async () => { session.current += 1; setState(null); setResetting(false); close(); navigate('/'); setMe(await api.me()) }} />}
  </>

  return (
    <main className="app">
      <Toaster />
      {me === undefined && !down && <LoadingScreen />}
      {down && (
        <div className="pad">
          {down === 'busy'
            ? <Say tone="stop-tone" title="요청이 많아 잠시 멈췄어요">1분 뒤 다시 시도해 주세요.</Say>
            : <Say tone="stop-tone" title="서버에 연결하지 못했습니다">인터넷 연결을 확인한 뒤 다시 시도해 주세요.</Say>}
          <Btn onClick={() => window.location.reload()}>다시 시도</Btn>
        </div>
      )}
      {me === null && <AuthScreen config={config} onLogin={(verified) => { setResetting(verified); return api.me().then(setMe) }} />}
      {me && (me.needsPassword || resetting) && <PasswordScreen onDone={() => { setResetting(false); return api.me().then(setMe) }} />}
      {me && !me.needsPassword && !resetting && me.needsProfile && <ProfileScreen onDone={() => api.me().then(setMe)} />}
      {me && !me.needsPassword && !resetting && !me.needsProfile && (
        <>
          {wide && <Nav tab={tab} onTab={setTab} onMenu={openMenu} admin={me.admin} />}
          {offline && <Say tone="stop-tone">연결이 끊겼습니다. 다시 연결하는 중입니다.</Say>}
          {config?.simulatedRoute && <p className="demo-strip" role="note">시연 모드: 금액과 시간은 예시예요</p>}
          {paymentReturn && <PaymentResult key={me.id + location.search} search={location.search} myId={me.id} onUpdated={refresh}
            onReturn={(id) => navigate(id ? `/parties/${id}` : '/', { replace: true })} />}
          {!paymentReturn && tab === 'history' && <History admin={me.admin} onBack={() => setTab('home')} onBoardingCases={() => setTab('boarding-cases')} />}
          {!paymentReturn && tab === 'rides' && <Rides state={state} onOpen={(id) => navigate('/parties/' + id)} onHome={() => setTab('home')} tabBar={tabBar} />}
          {!paymentReturn && tab === 'me' && <Screen><header className="bar"><h1 className="bar-title">내 정보</h1></header><div className="pad me-page">{meMenu(() => {})}</div>{tabBar}</Screen>}
          {!paymentReturn && tab === 'history' && tabBar}
          {!paymentReturn && tab === 'boarding-cases' && <BoardingCasesScreen now={state?.now} onBack={() => setTab('home')} />}
          {!paymentReturn && tab === 'admin' && <AdminScreen allowed={me.admin} onBack={() => setTab('home')} />}
          {!paymentReturn && tab === 'home' && !state && <LoadingScreen />}
          {!paymentReturn && tab === 'home' && state && (
            <Flow state={state} config={config} myId={me.id} run={run} refresh={refresh} onMenu={openMenu} onPush={togglePush} pushOn={pushOn} onHistory={() => setTab('history')} tabBar={tabBar} />
          )}
          {config?.demo && <DemoBar now={state?.now} run={run} />}
          {menu && (
            <Sheet title={me.nickname} className="pop menu" onClose={() => setMenu(false)}>
              {meMenu(() => setMenu(false))}
            </Sheet>
          )}
          {editingProfile && <ProfileSheet onClose={() => setEditingProfile(false)} />}
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
function Flow({ state, config, myId, run, refresh, onMenu, onHistory, onPush, pushOn, tabBar }) {
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
  if (latest?.status === 'JOINING') return <Joining latest={latest} config={config} now={state.now} myId={myId} run={run} onUpdated={refresh} onMenu={onMenu} />
  if (latest?.status === 'WAITING') return <Waiting latest={latest} now={state.now} config={config} run={run} onMenu={onMenu} onPush={pushOn ? null : onPush} />
  if (requestPath && !state.restricted) {
    const Request = requestPath === 'school' ? SchoolRequest : HomeRequest
    return <Request initial={location.state?.initial} config={config} now={state.now} run={run} onClose={() => navigate('/', { replace: true })} />
  }
  const left = latest?.endReason === 'NO_SHOW'
  const routeExpired = latest?.endReason === 'ROUTE_VERIFICATION_EXPIRED'
  if ((left || routeExpired || latest?.status === 'UNMATCHED') && seenRequest !== latest.id) {
    const Ended = left ? NoShow : Unmatched
    return <Ended latest={latest} onMenu={onMenu} onHome={() => setSeenRequest(latest.id)} onRetry={() => {
      setSeenRequest(latest.id); startRequest({ direction: latest.direction, initial: latest })
    }} />
  }
  return <Home state={state} myId={myId} config={config} onChanged={refresh} onStart={startRequest} onHistory={onHistory} tabBar={tabBar}
    onReopen={(id) => { setHidden((old) => old.filter((p) => p !== id)); navigate('/parties/' + id) }} />
}

/** 내 일행: 진행 중인 일행과 최근 정산 일행. 누르면 그 일행의 채팅방으로 간다. */
function Rides({ state, onOpen, onHome, tabBar }) {
  if (!state) return <LoadingScreen />
  const parties = state.parties || []
  return (
    <Screen>
      <header className="bar"><h1 className="bar-title">내 일행</h1></header>
      <div className="pad">
        {parties.length === 0 && !state.settledParty && (
          <div className="empty">
            <b>진행 중인 일행이 없어요</b>
            <p>홈에서 가는 길을 올리면 같은 방향 학생과 이어 드려요.</p>
            <Btn className="line" onClick={onHome}>일행 찾으러 가기</Btn>
          </div>
        )}
        <ul className="ride-list">
          {parties.map((party) => (
            <li key={party.id}>
              <button type="button" className="ride-row" onClick={() => onOpen(party.id)}>
                <span className="ride-main">
                  <b>{routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: party.meetingPoint })}</b>
                  <small>{party.departureAt ? `${formatTime(party.departureAt)} 출발, ` : ''}{partyLabel(party.status)}</small>
                </span>
                {party.unreadChat > 0 && <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>{party.unreadChat > 99 ? '99+' : party.unreadChat}</span>}
                <ChevronRight size={18} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
        {state.settledParty && <Item title="최근 정산 내역 보기" onClick={() => onOpen(state.settledParty.id)} />}
      </div>
      {tabBar}
    </Screen>
  )
}
