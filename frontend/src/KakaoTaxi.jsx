import React, { useState } from 'react'

export default function KakaoTaxi() {
  const [guide, setGuide] = useState(false)
  return <>
    <a className="btn line" href="kakaot://launch" onClick={() => setGuide(true)}>카카오T 열기</a>
    {guide && <p className="note">앱이 열리지 않으면 카카오T를 설치하거나 직접 열어 주세요. <a href="https://www.kakaomobility.com/service-kakaot/taxi" target="_blank" rel="noreferrer">카카오T 웹 안내</a></p>}
  </>
}
