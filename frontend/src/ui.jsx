import React, { useEffect, useState } from 'react'
import { AlertTriangle, Check, ChevronLeft, ChevronRight, Info, User } from 'lucide-react'

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

/** 위는 내용, 아래는 지금 할 행동을 고정한다. aside는 넓은 화면의 오른쪽 패널이다. */
export function Screen({ children, foot, aside }) {
  const wide = useWide()
  return (
    <div className={aside === undefined ? 'screen solo' : 'screen'}>
      <div className="pane">
        <div className="body">{children}</div>
        {foot && <div className="foot">{foot}</div>}
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

export function SideEmpty({ title, children }) {
  return <div className="side-empty"><div><b>{title}</b><p>{children}</p></div></div>
}

export function Brand() {
  return <span className="brand"><i aria-hidden="true" />택시메이트</span>
}

export function TopBar({ dark, onMenu }) {
  return (
    <header className={dark ? 'bar dark' : 'bar'}>
      <Brand />
      {onMenu && <button type="button" className="icon-btn" aria-label="내 정보" onClick={onMenu}><User size={22} /></button>}
    </header>
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

export const Money = ({ value }) => <>{Number(value).toLocaleString()}<small>원</small></>

export function Meter({ label, value, detail, tag, children }) {
  return (
    <section className="meter">
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <em>{detail}</em>}
      {children}
      {tag && <span className="tag">{tag}</span>}
    </section>
  )
}

/** 비동기 동작 중에는 스피너를 보이고 중복 누름을 막는다. */
export function Btn({ onClick, className = '', disabled, children, type = 'button' }) {
  const [busy, setBusy] = useState(false)
  const press = async () => {
    if (busy || !onClick) return
    setBusy(true)
    try {
      await onClick()
    } finally {
      setBusy(false)
    }
  }
  return (
    <button type={type} className={`btn ${className}`} disabled={disabled || busy} onClick={press}>
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
      {items.filter(Boolean).map(([label, value, tone]) => (
        <div key={label}><span className="k">{label}</span><span className={`v ${tone || ''}`}>{value}</span></div>
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
          <div className="stop"><b>{item.name}</b>{item.detail && <small>{item.detail}</small>}</div>
          {item.time && <span className="time">{item.time}</span>}
          {item.state && <span className={item.ok ? 'state ok' : 'state'}>{item.state}</span>}
        </li>
      ))}
    </ol>
  )
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

export function Sheet({ title, onClose, children, className = '' }) {
  useEffect(() => {
    const key = (event) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [onClose])
  return (
    <>
      <div className={`scrim ${className}`} onClick={onClose} />
      <section className={`sheet ${className}`} role="dialog" aria-modal="true" aria-label={title}>
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

export function Toaster() {
  const [items, setItems] = useState([])
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
    <div className="toasts" role="status" aria-live="polite">
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
