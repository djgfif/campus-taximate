import React from 'react'
import { Btn, Say } from './ui.jsx'

/** 화면 코드가 실패해도 흰 화면 대신 다시 여는 길을 준다. */
export default class ErrorBoundary extends React.Component {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <main className="app">
        <div className="pad">
          <Say tone="stop-tone" title="화면을 표시하지 못했습니다">새로고침하면 대부분 해결됩니다. 입력한 요청과 정산은 서버에 그대로 있습니다.</Say>
          <Btn onClick={() => window.location.assign('/')}>처음 화면으로</Btn>
        </div>
      </main>
    )
  }
}
