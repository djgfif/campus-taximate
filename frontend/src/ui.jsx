import React from 'react'

/** 위는 내용, 아래는 지금 할 행동을 고정한다. */
export function Screen({ children, actions }) {
  return (
    <div className="screen">
      <div className="screen-body">{children}</div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  )
}

export function BackBar({ onBack, step }) {
  return (
    <div className="backbar">
      <button type="button" className="link" onClick={onBack}>뒤로</button>
      <span className="muted">{step}</span>
    </div>
  )
}

export function Hero({ label, value, detail }) {
  return (
    <div className="hero">
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <em>{detail}</em>}
    </div>
  )
}

export function Todo({ title, children }) {
  return <div className="todo"><strong>{title}</strong><span>{children}</span></div>
}

export function Notice({ title, children }) {
  return <div className="soft"><strong>{title}</strong><span>{children}</span></div>
}

export function Rows({ title, items }) {
  return (
    <div className="rows">
      {title && <strong>{title}</strong>}
      {items.filter(Boolean).map(([label, value, strong]) => (
        <div key={label}><span>{label}</span><span className={strong ? 'strong' : ''}>{value}</span></div>
      ))}
    </div>
  )
}

export function MoreLink({ label, detail, onClick }) {
  return (
    <button type="button" className="more" onClick={onClick}>
      <span>{label}</span><span className="muted">{detail}</span>
    </button>
  )
}

export function Chips({ options, value, onChange }) {
  return (
    <div className="chips">
      {options.map((option) => (
        <button type="button" key={option.value} className={option.value === value ? 'on' : ''}
          onClick={() => onChange(option.value)}>{option.label}</button>
      ))}
    </div>
  )
}

export function Stops({ items }) {
  return (
    <ol className="stops">
      {items.map((item) => (
        <li key={item.key} className={item.me ? 'me' : ''}>
          <span className="order">{item.badge}</span>
          <div><b>{item.name}</b>{item.detail && <small>{item.detail}</small>}</div>
          {item.right && <span className="right">{item.right}</span>}
        </li>
      ))}
    </ol>
  )
}
