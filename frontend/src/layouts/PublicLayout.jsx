import React from 'react'
import { ReceiptText, ShieldCheck, Sparkles } from 'lucide-react'
import { Mark } from '../ui.jsx'

const HIGHLIGHTS = [
  {
    icon: ShieldCheck,
    title: '학교 메일 인증 학생 전용',
    desc: '같은 캠퍼스 소속이 확인된 학생끼리만 동승 일행으로 연결돼요.',
  },
  {
    icon: Sparkles,
    title: '공정 기준 자동 매칭',
    desc: '우회 시간 15분 이내, 혼자 탈 때보다 10% 이상 절약될 때만 일행을 묶어요.',
  },
  {
    icon: ReceiptText,
    title: '영수증 기반 투명 정산',
    desc: '하차 후 영수증과 탑승 기록을 바탕으로 각자 몫을 투명하게 확인해요.',
  },
]

/** 비회원·로그인·가입용 공개 레이아웃. PC에서는 좌측 서비스 소개 + 우측 인증 카드 2단으로 구성한다. */
export default function PublicLayout({ children }) {
  return (
    <div className="public-shell">
      <div className="public-frame">
        <aside className="public-hero" aria-label="서비스 소개">
          <div className="public-brand">
            <Mark size={32} />
            <span>캠퍼스 택시메이트</span>
          </div>
          <h2 className="public-headline">
            캠퍼스 등·하교 택시,
            <br />
            같은 방향 학생과 나눠 타요
          </h2>
          <p className="public-lead">
            정류장에서 오래 기다리거나 오픈채팅으로 일일이 인원을 모으지 않아도, 시간과 방향이 맞는 학생을 자동으로 묶어 드려요.
          </p>
          <ul className="public-highlights">
            {HIGHLIGHTS.map(({ icon: Icon, title, desc }) => (
              <li key={title}>
                <span className="public-hi-icon" aria-hidden="true">
                  <Icon size={18} />
                </span>
                <div>
                  <strong>{title}</strong>
                  <p>{desc}</p>
                </div>
              </li>
            ))}
          </ul>
        </aside>
        <div className="public-card">{children}</div>
      </div>
    </div>
  )
}
