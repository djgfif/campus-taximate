import React, { useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, ChevronLeft, ChevronRight, Home as HomeIcon, Info, MessageCircle, Receipt, User } from 'lucide-react'

const WIDE = '(min-width: 1024px)'

/** 왼쪽 작업과 오른쪽 패널을 나란히 놓을 수 있는 폭인지 알려 준다. */
export function useWide() {
  const [wide, setWide] = useState(() => window.matchMedia(WIDE).matches)
  useEffect(() => {
    const list = window.matchMedia(WIDE)
    const change = () => setWide(list.matches)
    list.addEventListener('change', change)
    return () => list.removeEventListener('change', change)
  }, [])
  return wide
}

/**
 * 위는 내용, 아래는 지금 할 행동을 고정한다.
 * 넓은 화면: aside가 있으면 왼쪽 판(400px) + 오른쪽 지도, 없으면 지도 없이 가운데 한 칸이다.
 */
export function Screen({ children, foot, aside, tabBar }) {
  const wide = useWide()
  const footer = foot && <div className="foot">{foot}</div>
  return (
    <div className={aside === undefined ? 'screen solo' : 'screen'}>
      <div className="pane">
        <div className="body">{children}</div>
        {/* 행동 줄과 탭바는 한 덩어리로 아래에 붙인다. 따로 붙이면 둘 다 bottom: 0이라 겹친다. */}
        {tabBar ? <div className="dock">{footer}{tabBar}</div> : footer}
      </div>
      {wide && aside !== undefined && <aside className="aside">{aside}</aside>}
    </div>
  )
}

/** 오른쪽 패널의 지도가 아닌 내용. */
export const SidePlain = ({ children }) => <div className="side-plain"><div className="col">{children}</div></div>

export function Paper({ title, children }) {
  return (
    <section className="paper">
      {title && <h2 className="block-title">{title}</h2>}
      {children}
    </section>
  )
}

/** 비어 있는 화면은 모두 같은 모양: 제목 한 줄, 설명 한 줄, 노란 버튼 하나. */
export function Empty({ title, children, action, onAction }) {
  return <div className="empty">
    <b>{title}</b>
    {children && <p>{children}</p>}
    {action && <Btn onClick={onAction}>{action}</Btn>}
  </div>
}

export function SideEmpty({ title, children }) {
  return <div className="side-empty"><div><b>{title}</b><p>{children}</p></div></div>
}

/** 두 사람이 한 차에: 노란 차창 안에 나란히 앉은 두 사람. */
export function Mark({ size = 24 }) {
  return (
    <svg className="mark" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <rect width="24" height="24" rx="7" fill="var(--amber)" />
      <circle cx="8.5" cy="10" r="2.6" fill="var(--ink)" />
      <circle cx="15.5" cy="10" r="2.6" fill="var(--ink)" />
      <path d="M4.5 18.5c0-2.4 1.8-4 4-4s4 1.6 4 4M11.5 18.5c0-2.4 1.8-4 4-4s4 1.6 4 4" fill="var(--ink)" />
    </svg>
  )
}

export function Brand() {
  return <span className="brand"><Mark />택시메이트</span>
}

export function TopBar({ onMenu, onHome, floating, actions }) {
  return (
    <header className={floating ? 'bar floating' : 'bar'}>
      {onHome ? <div className="bar-left">
        <button type="button" className="icon-btn home-btn" aria-label="홈으로" onClick={onHome}><ChevronLeft size={26} /></button>
        <Brand />
      </div> : <Brand />}
      <div className="bar-right">
        {actions}
        {onMenu && <button type="button" className="icon-btn" aria-label="내 정보" onClick={onMenu}><User size={22} /></button>}
      </div>
    </header>
  )
}

const TABS = [
  ['home', '홈', HomeIcon],
  ['rides', '내 일행', MessageCircle],
  ['history', '이용 내역', Receipt],
  ['me', '내 정보', User],
]

/** 휴대폰 아래 탭바. 넓은 화면은 위쪽 메뉴(.nav)가 같은 일을 한다. unread는 내 일행의 안 읽은 메시지 수다. */
export function TabBar({ current, onTab, unread = 0 }) {
  const wide = useWide()
  if (wide) return null
  return (
    <nav className="tabbar" aria-label="주요 메뉴">
      {TABS.map(([id, label, Icon]) => (
        <button key={id} type="button" className={current === id ? 'on' : ''} aria-current={current === id ? 'page' : undefined} onClick={() => onTab(id)}>
          <span className="tab-icon"><Icon size={22} aria-hidden="true" />
            {id === 'rides' && unread > 0 && <span className="unread-badge" aria-label={`안 읽은 메시지 ${unread}개`}>{unread > 99 ? '99+' : unread}</span>}
          </span>
          {label}
        </button>
      ))}
    </nav>
  )
}

export function BackBar({ onBack, title, step, total }) {
  return (
    <header className="bar back">
      <div className="bar-left">
        <button type="button" className="icon-btn" aria-label="뒤로" onClick={onBack}><ChevronLeft size={26} /></button>
        {title && <span className="bar-title">{title}</span>}
      </div>
      {total > 1 && (
        <div className="steps" role="img" aria-label={`${total}단계 중 ${step}단계`}>
          {Array.from({ length: total }, (_, i) => <span key={i} className={i < step ? 'on' : ''} />)}
        </div>
      )}
    </header>
  )
}

/** 혼자 탈 때와 같이 탈 때를 막대 두 개로 견준다. 이 서비스의 핵심 숫자다. */
export function FareCompare({ solo, share }) {
  const won = (n) => `${Number(n).toLocaleString()}원`
  const ratio = solo > 0 ? Math.max(0.06, Math.min(1, share / solo)) : 1
  return <section className="fare-compare" aria-label="아끼는 돈">
    <p className="fc-save"><b>{won(solo - share)}</b> 아껴요</p>
    <div className="fc-row"><span>혼자 타면</span><i className="fc-bar solo" /><em>{won(solo)}</em></div>
    <div className="fc-row"><span>같이 타면</span><i className="fc-bar shared" style={{ '--w': `${Math.round(ratio * 100)}%` }} /><em>{won(share)}</em></div>
  </section>
}

/**
 * 이 일행이 정해진 이유. 매칭의 두 기준(늘어나는 시간, 내 몫의 비율)을 내 숫자와 나란히 보이고,
 * 실제 요금을 어떻게 나누는지 한 줄로 말한다. 기준 값은 서버 설정(config.rules)을 따른다.
 */
export function FairnessNote({ delay, solo, share, rules, walkExcluded = false }) {
  const maxDelay = rules?.maxDelayMinutes ?? 15
  const maxShare = Math.round((1 - (rules?.minSavingRate ?? 0.1)) * 100)
  const pct = solo > 0 ? Math.round((share / solo) * 100) : 100
  return <section className="fairness" aria-label="이 일행이 정해진 이유">
    <h2>이 일행이 정해진 이유</h2>
    <div className="fair-rows">
      <div><span>같이 가서 늘어나는 시간</span><b>{delay}분</b><small>{maxDelay}분 이내만 묶어요{walkExcluded ? ', 걷는 시간 제외' : ''}</small></div>
      <div><span>혼자 요금 중 내가 내는 몫</span><b>{pct}%</b><small>{maxShare}% 이하일 때만 묶어요</small></div>
    </div>
    <p>확정 금액을 각자 혼자 탈 때 요금의 비율대로 나눠요. 멀리 가는 사람이 더 내고, 실제 요금이 더 나와도 더 내지 않아요.</p>
  </section>
}

export const Money = ({ value }) => <>{Number(value).toLocaleString()}<small>원</small></>

export function Meter({ label, value, detail, tag, children, compact = false }) {
  return (
    <section className={compact ? 'meter compact' : 'meter'}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <em>{detail}</em>}
      {children}
      {tag && <span className="tag">{tag}</span>}
    </section>
  )
}

/** 비동기 동작 중에는 스피너를 보이고 중복 누름을 막는다. 동기 동작은 불필요한 상태 전환 없이 바로 실행한다. */
export function Btn({ onClick, className = '', disabled, children, type = 'button', ...props }) {
  const [busy, setBusy] = useState(false)
  const press = () => {
    if (busy || !onClick) return
    const result = onClick()
    if (!result || typeof result.then !== 'function') return
    setBusy(true)
    const done = () => setBusy(false)
    result.then(done, done)
  }
  return (
    <button {...props} type={type} className={`btn ${className}`} disabled={disabled || busy} onClick={press}>
      {busy ? <span className="spin" aria-label="처리 중" /> : children}
    </button>
  )
}

export function Say({ tone = '', title, children }) {
  const Icon = tone === 'stop-tone' ? AlertTriangle : tone === 'save-tone' ? Check : Info
  return (
    <div className={`say ${tone}`}>
      <Icon size={18} aria-hidden="true" />
      <div>{title && <b>{title}</b>}{children}</div>
    </div>
  )
}

export function Rows({ items }) {
  return (
    <div className="rows">
      {items.filter(Boolean).map(([label, value, tone], i) => (
        <div key={`${label}:${i}`}><span className="k">{label}</span><span className={`v ${tone || ''}`}>{value}</span></div>
      ))}
    </div>
  )
}

export function Rail({ items }) {
  return (
    <ol className="rail">
      {items.map((item) => (
        <li key={item.key} className={[item.me && 'me', item.end && 'end', item.off && 'off'].filter(Boolean).join(' ')}>
          <span className="pin">{item.pin}</span>
          <div className="stop">{item.avatar ? <span className="who">{item.avatar}<b>{item.name}</b>{item.badge && <em className="badge">{item.badge}</em>}</span> : <b>{item.name}</b>}{item.detail && <small>{item.detail}</small>}{item.sub && <small className="sub">{item.sub}</small>}</div>
          {item.time && <span className="time">{item.time}</span>}
          {item.state && <span className={item.ok ? 'state ok' : 'state'}>{item.state}</span>}
        </li>
      ))}
    </ol>
  )
}

// 알록달록한 파스텔 대신 무채색 두 단계만 쓴다. 사람마다 색이 달라 보일 필요는 없다.
const AVATAR_TONES = ['#eceef1', '#e4e6ea']

/** 사람을 사람처럼: 이름 첫 글자를 담은 동그란 프로필. 같은 이름은 늘 같은 색이다. */
export function Avatar({ name = '', size = 32 }) {
  const text = String(name).trim()
  const tone = AVATAR_TONES[[...text].reduce((sum, ch) => sum + ch.codePointAt(0), 0) % AVATAR_TONES.length]
  return <span className="avatar" aria-hidden="true" style={{ width: size, height: size, fontSize: Math.round(size * 0.42), background: tone }}>{text.slice(0, 1) || '?'}</span>
}

export function Item({ title, detail, onClick, right, danger }) {
  return (
    <button type="button" className={danger ? 'item danger' : 'item'} onClick={onClick}>
      <div><b>{title}</b>{detail && <small>{detail}</small>}</div>
      {right || <ChevronRight size={18} aria-hidden="true" />}
    </button>
  )
}

export function Chips({ options, value, onChange, label }) {
  return (
    <div className="chips" role="group" aria-label={label}>
      {options.map((option) => (
        <button type="button" key={option.value} aria-pressed={option.value === value}
          className={option.value === value ? 'chip on' : 'chip'} onClick={() => onChange(option.value)}>
          {option.label}
        </button>
      ))}
    </div>
  )
}

const FOCUSABLE = 'button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'

/** 모달 다이얼로그 공통 접근성: 열릴 때 포커스 이동, Tab 순환(트랩), Escape 닫기, 닫힐 때 원래 포커스 복원. */
export function useDialog(box, onClose, enabled = true) {
  const closeRef = useRef(onClose)
  useEffect(() => { closeRef.current = onClose }, [onClose])
  useEffect(() => {
    if (!enabled) return undefined
    const key = (event) => {
      if (event.key === 'Escape') { closeRef.current?.(); return }
      if (event.key !== 'Tab' || !box.current) return
      const items = box.current.querySelectorAll(FOCUSABLE)
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      if (event.shiftKey && (document.activeElement === first || document.activeElement === box.current)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [box, enabled])
  useEffect(() => {
    if (!enabled) return undefined
    const before = document.activeElement
    box.current?.focus({ preventScroll: true })
    return () => before?.focus?.()
  }, [box, enabled])
}

export function Sheet({ title, onClose, children, className = '' }) {
  const box = useRef(null)
  useDialog(box, onClose)
  return (
    <>
      <div className={`scrim ${className}`} onClick={onClose} aria-hidden="true" />
      <section ref={box} tabIndex={-1} className={`sheet ${className}`} role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {children}
      </section>
    </>
  )
}

/** 되돌릴 수 없는 행동 앞에서 한 번 묻는다. */
export function Confirm({ title, children, action, onConfirm, onClose }) {
  return (
    <Sheet title={title} onClose={onClose}>
      <p className="sub">{children}</p>
      <div className="btns">
        <Btn className="line" onClick={onClose}>그대로 두기</Btn>
        <Btn className="danger" onClick={async () => { await onConfirm(); onClose() }}>{action}</Btn>
      </div>
    </Sheet>
  )
}

let push = () => {}

/** 화면 어디서든 부른다. tone은 bad 또는 비움. */
export function toast(message, tone = '') {
  push(message, tone)
}

/** 아래에 붙은 행동 영역(행동 줄·탭바·일행방 할 일 카드·입력창) 중 가장 위쪽 가장자리. 알림은 그 바로 위에 띄운다. */
const BOTTOM_STACK = '.dock, .foot, .tabbar, .chat-task, .chat-form'
export function bottomInset(doc = document, height = window.innerHeight) {
  let top = height
  for (const el of doc.querySelectorAll(BOTTOM_STACK)) {
    const rect = el.getBoundingClientRect()
    // 보이면서 위 가장자리가 화면 아래 2/3 안에 있는 것만 센다(화면 밖이나 위쪽에 있는 것은 뺀다).
    if (rect.height > 0 && rect.top > height / 3 && rect.top < height) top = Math.min(top, rect.top)
  }
  return Math.max(0, height - top)
}

export function Toaster() {
  const [items, setItems] = useState([])
  const [inset, setInset] = useState(0)
  // 알림이 떠 있는 동안 화면 구조·크기가 바뀔 때만 아래 행동 영역 높이를 다시 잰다(매 프레임 리플로우 방지).
  useEffect(() => {
    if (items.length === 0) return undefined
    let scheduled = null
    const measure = () => {
      scheduled = null
      const next = bottomInset()
      setInset((prev) => (prev === next ? prev : next))
    }
    const schedule = () => { if (scheduled == null) scheduled = requestAnimationFrame(measure) }
    measure()
    window.addEventListener('resize', schedule)
    const mutation = typeof MutationObserver === 'function' ? new MutationObserver(schedule) : null
    mutation?.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden'] })
    const resize = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null
    if (resize) document.querySelectorAll(BOTTOM_STACK).forEach((el) => resize.observe(el))
    return () => {
      if (scheduled != null) cancelAnimationFrame(scheduled)
      window.removeEventListener('resize', schedule)
      mutation?.disconnect()
      resize?.disconnect()
    }
  }, [items.length])
  useEffect(() => {
    let id = 0
    push = (message, tone) => {
      id += 1
      const key = id
      setItems((list) => [...list.filter((item) => item.message !== message), { key, message, tone }])
      setTimeout(() => setItems((list) => list.filter((item) => item.key !== key)), tone === 'bad' ? 5000 : 2800)
    }
    return () => { push = () => {} }
  }, [])
  return (
    <div className="toasts" role="status" aria-live="polite" style={{ '--toast-inset': inset + 'px' }}>
      {items.map((item) => (
        <div key={item.key} className={`toast ${item.tone}`}>
          {item.tone === 'bad' ? <AlertTriangle size={18} /> : <Check size={18} />}
          <span>{item.message}</span>
        </div>
      ))}
    </div>
  )
}

export function Bone({ width = '100%', height = 16 }) {
  return <span className="bone" style={{ width, height }} aria-hidden="true" />
}

export function LoadingScreen() {
  return (
    <div className="screen" aria-busy="true" aria-label="불러오는 중">
      <div className="pad">
        <Bone width="60%" height={30} />
        <Bone width="85%" />
        <Bone height={104} />
        <Bone height={104} />
      </div>
    </div>
  )
}
