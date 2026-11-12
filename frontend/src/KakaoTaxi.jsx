import React, { useState } from 'react'

/** 카카오T 앱 열기. 아직 부를 때가 아니면 꺼 두어, 일찍 불러 규칙과 어긋나는 일을 막는다. */
export default function KakaoTaxi({ disabled = false }) {
  const [guide, setGuide] = useState(false)
  if (disabled) return <button type="button" className="btn line" disabled>카카오T 열기</button>
  return <>
    <a className="btn line" href="kakaot://launch" onClick={() => setGuide(true)}>카카오T 열기</a>
    {guide && <p className="note">앱이 열리지 않으면 카카오T를 설치하거나 직접 열어 주세요. <a href="https://www.kakaomobility.com/service-kakaot/taxi" target="_blank" rel="noreferrer">카카오T 웹 안내</a></p>}
  </>
}
