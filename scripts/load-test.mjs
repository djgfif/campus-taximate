// NFR-01 측정: 요청 등록·조회 API p95 ≤ 1초 (동시 10명, 1,000회).
// 시연 서버를 카카오 키 없이(모의 경로, 외부 API 응답 고정) 띄우고 실행한다.
//   RATE_LIMIT_ENABLED=false SPRING_PROFILES_ACTIVE=demo java -jar server/target/server-0.1.0.jar --app.kakao.rest-key=
//   node scripts/load-test.mjs [기본 http://localhost:8080] [총 요청 수, 기본 1000] [동시 사용자, 기본 10]
// 시연 계정 8개(demo-f1~f4, demo-m1~m4)는 등록→조회→취소를, 9번째부터는 같은 계정의 새 세션으로 조회만 한다
// (회원당 진행 중 요청은 1개라 같은 계정으로 동시에 등록할 수 없다).
const base = process.argv[2] || 'http://localhost:8080'
const total = Number(process.argv[3] || 1000)
const users = Number(process.argv[4] || 10)
const accounts = ['f1', 'f2', 'f3', 'f4', 'm1', 'm2', 'm3', 'm4'].map((id) => `demo-${id}@daejin.ac.kr`)

async function signIn(email) {
  const res = await fetch(`${base}/api/auth/signin`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Origin: base },
    body: JSON.stringify({ email, password: 'demo1234' }),
  })
  if (!res.ok) throw new Error(`로그인 실패 ${email} ${res.status}`)
  return res.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ')
}

const samples = {}
let sent = 0
let errors = 0
async function timed(name, cookie, path, init = {}) {
  const t0 = performance.now()
  const res = await fetch(`${base}${path}`, { ...init, headers: { Origin: base, Cookie: cookie, ...(init.headers || {}) } })
  const body = await res.text()
  const ms = performance.now() - t0
  ;(samples[name] ||= []).push(ms)
  if (!res.ok) { errors++; if (errors <= 5) console.error(`오류 ${name} ${res.status} ${body.slice(0, 120)}`) }
  return { res, body }
}

function requestBody(hub, now) {
  const start = new Date(Math.ceil((now + 40 * 60000) / 300000) * 300000)
  return JSON.stringify({
    direction: 'FROM_HUB', hubId: hub.id, mode: 'RESERVATION', timeBasis: 'DEPART',
    windowStart: start.toISOString(), windowEnd: new Date(start.getTime() + 3600000).toISOString(),
    pointName: '부하 측정 도착지', pointLat: hub.lat + 0.03, pointLon: hub.lon + 0.03,
  })
}

async function worker(index) {
  const writer = index < accounts.length
  const cookie = await signIn(accounts[index % accounts.length])
  const hubs = JSON.parse((await timed('GET /api/hubs', cookie, '/api/hubs')).body)
  const hub = hubs[0]
  const state = JSON.parse((await timed('GET /api/state', cookie, '/api/state')).body)
  const now = Date.parse(state.now)
  while (sent < total) {
    if (writer) {
      sent += 3
      const { res, body } = await timed('POST /api/requests', cookie, '/api/requests', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: requestBody(hub, now),
      })
      await timed('GET /api/state', cookie, '/api/state')
      if (res.ok) await timed('DELETE /api/requests/{id}', cookie, `/api/requests/${JSON.parse(body).id}`, { method: 'DELETE' })
    } else {
      sent += 1
      await timed('GET /api/state', cookie, '/api/state')
    }
  }
}

const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)]
const started = performance.now()
await Promise.all(Array.from({ length: users }, (_, i) => worker(i)))
const seconds = (performance.now() - started) / 1000
const all = Object.values(samples).flat().sort((a, b) => a - b)
console.log(`대상 ${base}, 동시 사용자 ${users}, 요청 ${all.length}회, ${seconds.toFixed(1)}초, 오류 ${errors}건`)
for (const [name, list] of [['전체', all], ...Object.entries(samples).map(([n, l]) => [n, l.sort((a, b) => a - b)])]) {
  console.log(`${name.padEnd(28)} n=${String(list.length).padStart(4)}  p50 ${pct(list, 0.5).toFixed(0)}ms  p95 ${pct(list, 0.95).toFixed(0)}ms  p99 ${pct(list, 0.99).toFixed(0)}ms  최대 ${list[list.length - 1].toFixed(0)}ms`)
}
process.exitCode = pct(all, 0.95) <= 1000 && errors === 0 ? 0 : 1
