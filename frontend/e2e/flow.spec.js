import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

// 1x1 PNG. 서버가 파일 앞부분으로 형식을 확인한다.
const PNG = Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010806000000'
  + '1f15c4890000000d4944415478da63f8ffff3f0005fe02fea7d6a4c50000000049454e44ae426082', 'hex')

const ACCOUNTS = { pc: ['demo-f3', 'demo-f4'], phone: ['demo-m3', 'demo-m4'] }

async function login(page, id, { activeParty = false } = {}) {
  await page.goto('/')
  await page.getByLabel('학교 메일').fill(`${id}@daejin.ac.kr`)
  await page.getByLabel('비밀번호').fill('demo1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  // 홈은 늘 홈이다. 진행 중 일행은 홈 맨 위 '내 일행' 줄을 눌러 들어간다.
  if (activeParty) {
    await page.getByRole('list', { name: '내 일행' }).getByRole('button').first().click({ timeout: 15000 })
    await expect(page.getByRole('region', { name: '채팅 창' })).toBeVisible({ timeout: 15000 })
  }
  else await expect(homeDirections(page)).toBeVisible({ timeout: 15000 })
}

/** 일행 정보. 휴대폰과 넓은 화면 모두 방 머리의 (i) 단추로 창을 연다. 확인이 끝나면 창을 닫는다. */
async function withDetails(page, check) {
  const open = page.getByRole('button', { name: '일행 정보 열기' })
  const phone = await open.isVisible()
  if (phone) await open.click()
  await check(page.getByRole('region', { name: '일행 정보' }))
  if (phone) await page.getByRole('dialog', { name: '일행 정보' }).getByRole('button', { name: '닫기' }).click()
}

/** 결제자의 기사님 화면. 채팅 입력창 옆 + 메뉴에 있다. */
async function openDriver(page) {
  const more = page.getByRole('button', { name: '더 보기' })
  if (await more.isVisible()) {
    await more.click()
    await page.getByRole('menuitem', { name: '기사님께 보여 주기' }).click()
  } else await page.getByRole('button', { name: '기사님께 보여 줄 화면' }).click()
}

/** 홈의 등교·하교 전환. 홈이 떠 있는지 이것으로 확인한다. */
const homeDirections = (page) => page.getByRole('tablist', { name: '방향' })

/** 홈에서 하교를 고르고 목적지 입력으로 들어간다. */
async function startHomeRequest(page) {
  await homeDirections(page).getByRole('tab', { name: '하교', exact: true }).click()
  await page.getByRole('button', { name: '어디로 가세요?', exact: true }).click()
}

async function demo(page, action) {
  // 휴대폰은 '시연' 단추, 넓은 화면은 단추 없이 D 키로 연다.
  const fab = page.getByRole('button', { name: '시연', exact: true })
  if (await fab.isVisible()) await fab.click()
  else await page.keyboard.press('d')
  const sheet = page.getByRole('dialog', { name: '시연' })
  const button = sheet.getByRole('button', { name: action, exact: true })
  // 다른 일행 움직이기와 시각 이동은 '시간 넘기기', 갈래 장면은 '다른 장면 체험' 접힘 안에 있다.
  for (const fold of ['시간 넘기기', '다른 장면 체험']) {
    if (!(await button.isVisible())) await sheet.getByText(fold, { exact: true }).click()
  }
  // 시연 동작이 서버에서 중간에 실패해도 창에는 경고가 안 남을 수 있어 응답 코드를 직접 확인한다.
  const response = page.waitForResponse((r) => r.url().includes('/api/demo/') && r.request().method() === 'POST', { timeout: 60000 })
  await button.click()
  expect((await response).status(), `${action} 응답`).toBeLessThan(400)
  // 장면을 바꾸는 동작은 끝나면 창이 저절로 닫히고 그 일행방으로 간다(자막이 뜬다).
  // 시간 넘기기 같은 보조 동작은 창이 열린 채로 남으므로, 처리가 끝나 버튼이 다시 켜지면 직접 닫는다.
  // 2~3명은 모집 마감까지 슬롯을 돌리므로 CI에서는 몇 초 넘게 걸릴 수 있다.
  await expect(async () => {
    if (await sheet.isVisible()) {
      await expect(button).toBeEnabled()
      await expect(sheet.getByRole('alert')).toHaveCount(0)
      await page.keyboard.press('Escape')
    }
    await expect(sheet).toBeHidden()
  }).toPass({ timeout: 60000 })
}

async function currentParty(page) {
  const response = await page.request.get('/api/state')
  expect(response.ok(), '현재 일행 상태 조회').toBeTruthy()
  const state = await response.json()
  expect(state.parties).toHaveLength(1)
  return state.parties[0]
}

async function boardingState(page, partyId) {
  const response = await page.request.get(`/api/parties/${partyId}/boarding-code`)
  expect(response.ok(), '본인 탑승 인증 상태 조회').toBeTruthy()
  return response.json()
}

async function callTaxiWhenReady(page) {
  const button = page.getByRole('button', { name: '택시 불렀어요', exact: true })
  await expect(button).toBeVisible()
  for (let i = 0; i < 30 && !(await button.isEnabled()); i++) await demo(page, '5분 뒤')
  await expect(button).toBeEnabled()
  await button.click()
  await expect(page.getByRole('dialog', { name: '기사님께 보여 줄 화면' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '기사님께 보여 줄 화면' })).toBeHidden()
}

async function evidence(page, project, name, info) {
  const directory = new URL('../../output/playwright/', import.meta.url)
  await mkdir(directory, { recursive: true })
  const path = fileURLToPath(new URL(`${project}-${name}.png`, directory))
  await page.screenshot({ path, fullPage: true })
  await info.attach(name, { path, contentType: 'image/png' })
}

test('승차 지점 요청부터 이의 기간과 정산 완료까지 화면으로 끝낸다', async ({ page }, info) => {
  await login(page, ACCOUNTS[info.project.name][0])
  await startHomeRequest(page)
  await expect(page).toHaveURL(/\/request\/home$/)
  await page.getByLabel('시연 출발지').selectOption({ index: 1 })
  await page.getByLabel('시연 도착지').selectOption({ label: '역전 버스 정류장' })
  await page.getByRole('checkbox').first().check()
  await expect(page.getByText(/^약 [\d,]+원, \d+분$/)).toBeVisible()
  await page.locator('.foot').getByRole('button', { name: '일행 찾기' }).click()
  await expect(page.getByRole('heading', { name: '같이 탈 학생을 찾고 있어요' })).toBeVisible()

  await demo(page, '일행 만들기')
  await withDetails(page, () => expect(page.getByRole('dialog', { name: '일행 정보' }).getByText('내가 결제자')).toBeVisible())
  // 마감 시각은 일행 정보에 있다.
  await withDetails(page, (details) => expect(details.getByText('무료 취소 마감', { exact: true })).toBeVisible())
  // 샘플 회원의 선결제와 도착을 실제 회원과 같은 API로 처리한다.
  await demo(page, '다른 일행이 움직이기')
  await openDriver(page)
  await expect(page.getByRole('dialog', { name: '기사님께 보여 줄 화면' })).toBeVisible()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '도착했어요', exact: true }).click()

  // 도착만으로 운행이 시작되지 않는다. 호출·실제 탑승·명시적 출발을 각각 진행한다.
  expect((await currentParty(page)).startedAt).toBeNull()
  await callTaxiWhenReady(page)
  await page.getByRole('button', { name: '택시 탔어요 · 코드 열기', exact: true }).click()
  await demo(page, '다른 일행이 움직이기')
  await page.getByRole('button', { name: '운행 시작', exact: true }).click()

  const amount = page.getByLabel('실제 결제 금액')
  for (let i = 0; i < 30 && !(await amount.isVisible()); i++) await demo(page, '5분 뒤')
  await expect(amount).toBeVisible()
  await amount.fill('20000')
  await page.getByLabel('영수증 사진').setInputFiles({ name: 'r.png', mimeType: 'image/png', buffer: PNG })
  await expect(page.getByLabel('은행', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('받을 계좌', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: /정산 요청/ }).click()
  await expect(page.locator('.room-sub')).toContainText('요금 확인 기간')
  await expect(page.getByRole('button', { name: '요금 이의', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /^(보냄|받음)$/ })).toHaveCount(0)
  await expect(page.getByText(/앱 정산 기록상 받을 돈/)).toHaveCount(0)

  await demo(page, '하루 뒤')
  await demo(page, '5분 뒤')
  // 확정 후 추가 결제가 생긴 샘플도 모의 게이트웨이로 승인한다.
  await demo(page, '다른 일행이 움직이기')
  await demo(page, '5분 뒤')
  await page.goto('/history')
  await expect(page.getByText('정산 완료', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/앱 정산 기록상 받을 돈\(실제 송금 없음\) [\d,]+원/).first()).toBeVisible()
  await expect(page.getByText('실제 송금 없음').first()).toBeVisible()
  // 전체 요금 통계는 관리자에게만 보인다(11/12 변경). 학생 계정에는 없어야 한다.
  await expect(page.getByText(/전체 정산 \d+건 중/)).toHaveCount(0)
})

test('서로 다른 사용자 화면에서 번호 공개·탑승 인증·명시적 출발·정산 완료를 검증한다', async ({ page, browser }, info) => {
  const prefix = info.project.name === 'phone' ? 'demo-m' : 'demo-f'
  await login(page, `${prefix}1`)
  const problems = []
  const watch = (target, role) => {
    target.on('pageerror', (error) => problems.push(`${role}: ${error.message}`))
    target.on('console', (message) => {
      if (message.type() === 'error') problems.push(`${role}: ${message.text()}`)
    })
  }
  // 첫 로그인 화면의 정상적인 /me 401은 제외하고 인증된 사용자 흐름부터 기록한다.
  watch(page, '결제자')
  await startHomeRequest(page)
  await page.getByLabel('시연 출발지').selectOption({ index: 1 })
  await page.getByLabel('시연 도착지').selectOption({ label: '역전 버스 정류장' })
  await page.getByRole('checkbox').first().check()
  await page.locator('.foot').getByRole('button', { name: '일행 찾기' }).click()
  await expect(page.getByRole('heading', { name: '같이 탈 학생을 찾고 있어요' })).toBeVisible()
  await demo(page, '일행 만들기')
  await withDetails(page, () => expect(page.getByRole('dialog', { name: '일행 정보' }).getByText('내가 결제자')).toBeVisible())
  // 선결제·샘플 도착만 시연 도구로 준비한다. 실제 검증 대상 동작은 각 사용자 UI에서 수행한다.
  await demo(page, '다른 일행이 움직이기')
  await page.getByRole('button', { name: '도착했어요', exact: true }).click()
  const party = await currentParty(page)
  expect(party.payer).toBe(true)
  expect(party.startedAt).toBeNull()
  expect(party.boardedAt).toBeNull()
  expect((await boardingState(page, party.id)).code).toBeNull()
  await evidence(page, info.project.name, '01-arrival-is-not-boarding', info)

  const riderContext = await browser.newContext({
    baseURL: new URL(page.url()).origin,
    viewport: page.viewportSize(), locale: 'ko-KR', timezoneId: 'Asia/Seoul',
    ...(info.project.name === 'phone' ? { isMobile: true, hasTouch: true } : {}),
  })
  try {
    const rider = await riderContext.newPage()
    await login(rider, `${prefix}2`, { activeParty: true })
    watch(rider, '동승자')
    const riderParty = await currentParty(rider)
    expect(riderParty.id).toBe(party.id)
    expect(riderParty.payer).toBe(false)
    await expect(rider.getByRole('button', { name: '택시 탔어요 · 코드 열기', exact: true })).toHaveCount(0)
    await expect(rider.getByRole('button', { name: '운행 시작', exact: true })).toHaveCount(0)

    await callTaxiWhenReady(page)
    await page.getByRole('button', { name: '택시 탔어요 · 코드 열기', exact: true }).click()
    const payerPanel = page.getByRole('region', { name: '탑승 코드 확인' }).filter({ visible: true })
    const displayedCode = payerPanel.getByLabel('탑승 코드', { exact: true })
    await expect(displayedCode).toHaveText(/^\d{6}$/)
    const code = await displayedCode.innerText()
    const opened = await boardingState(page, party.id)
    expect(opened.status).toBe('OPEN')
    expect(opened.code).toBe(code)
    expect((await currentParty(page)).startedAt).toBeNull()
    await evidence(page, info.project.name, '02-payer-code-before-departure', info)

    // 독립된 세션에는 번호 원문이 전달되지 않으며 결제자 화면을 보고 직접 입력해야 한다.
    await rider.reload()
    const riderPanel = rider.getByRole('region', { name: '탑승 코드 확인' }).filter({ visible: true })
    await expect(riderPanel.getByLabel('6자리 탑승 코드', { exact: true })).toBeVisible()
    const hiddenCode = await boardingState(rider, party.id)
    expect(hiddenCode.code).toBeNull()
    expect(hiddenCode.canOpen).toBe(false)
    expect(hiddenCode.canStart).toBe(false)
    expect(hiddenCode.confirmed).toBe(false)
    expect(hiddenCode.generation).toBe(opened.generation)
    await expect(rider.getByLabel('탑승 코드', { exact: true })).toHaveCount(0)
    const confirmation = rider.waitForResponse((response) => response.url().endsWith(`/parties/${party.id}/boarding-code`)
      && response.request().method() === 'POST')
    await riderPanel.getByLabel('6자리 탑승 코드', { exact: true }).fill(code)
    await riderPanel.getByRole('button', { name: '탑승 확인', exact: true }).click()
    expect((await confirmation).status()).toBeLessThan(400)
    await expect(riderPanel.getByText('탑승 확인 완료', { exact: true })).toBeVisible()
    expect((await boardingState(rider, party.id)).confirmed).toBe(true)
    expect((await currentParty(rider)).startedAt).toBeNull()
    await evidence(rider, info.project.name, '03-rider-confirmed', info)

    // 남은 샘플만 준비한 뒤 출발 버튼은 결제자가 직접 누른다.
    await demo(page, '다른 일행이 움직이기')
    await page.getByRole('button', { name: '운행 시작', exact: true }).click()
    await expect.poll(async () => (await currentParty(page)).status).toBe('AWAITING_FARE')
    expect((await currentParty(page)).startedAt).not.toBeNull()
    const afterStart = await boardingState(rider, party.id)
    expect(afterStart.status).toBe('OPEN')
    expect(afterStart.deadlineAt).toBe(opened.deadlineAt)
    expect(afterStart.code).toBeNull()
    await expect(page.getByLabel('실제 결제 금액')).toHaveCount(0)
    await evidence(page, info.project.name, '04-explicit-start-window-open', info)

    await demo(page, '5분 뒤')
    const amount = page.getByLabel('실제 결제 금액')
    await expect(amount).toBeVisible()
    expect((await boardingState(rider, party.id)).status).toBe('EXPIRED')
    await amount.fill(String(party.expectedTotal))
    await page.getByLabel('영수증 사진').setInputFiles({ name: 'demo-receipt.png', mimeType: 'image/png', buffer: PNG })
    await page.getByRole('button', { name: /정산 요청/ }).click()
    await expect(page.locator('.room-sub')).toContainText('요금 확인 기간')
    await demo(page, '하루 뒤')
    await demo(page, '5분 뒤')
    await expect.poll(async () => {
      const response = await page.request.get(`/api/parties/${party.id}`)
      expect(response.ok()).toBeTruthy()
      return (await response.json()).status
    }).toBe('COMPLETED')
    await page.goto('/history')
    await expect(page.getByText('정산 완료', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('실제 송금 없음').first()).toBeVisible()
    await evidence(page, info.project.name, '05-settlement-completed', info)
    expect(problems, '인증된 다중 사용자 흐름의 콘솔·미처리 오류').toEqual([])
  } finally {
    await riderContext.close()
  }
})

test('주소로 이동하고 뒤로 가기가 앱 안에서 동작한다', async ({ page }, info) => {
  await page.goto('/history')
  await expect(page.getByLabel('학교 메일')).toBeVisible()

  await login(page, ACCOUNTS[info.project.name][1])
  await startHomeRequest(page)
  await expect(page).toHaveURL(/\/request\/home$/)
  await page.goBack()
  await expect(homeDirections(page)).toBeVisible()
  await page.goto('/history')
  await expect(page.getByText('아직 탄 기록이 없어요')).toBeVisible()
})
