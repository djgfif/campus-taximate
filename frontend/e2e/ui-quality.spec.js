import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const VIEWPORTS = [
  [320, 568],
  [360, 800],
  [390, 844],
  [430, 932],
  [768, 1024],
  [1024, 768],
  [1280, 800],
  [1440, 900],
  [1920, 1080],
  [2560, 1440],
]

async function signIn(page, account = 'demo-f1') {
  await page.goto('/')
  await page.getByLabel('학교 메일').fill(`${account}@daejin.ac.kr`)
  await page.getByLabel('비밀번호').fill('demo1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('tablist', { name: '방향' })).toBeVisible({ timeout: 15000 })
}

async function saveShot(page, name) {
  const dir = new URL('../../output/playwright/ui-audit/', import.meta.url)
  await mkdir(dir, { recursive: true })
  const path = fileURLToPath(new URL(`${name}.png`, dir))
  await page.screenshot({ path, fullPage: true })
}

async function assertNoOverflow(page, label) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }))
  expect(scrollWidth, `${label} horizontal overflow`).toBeLessThanOrEqual(innerWidth)
}

test('SPA 경로 직접 진입 및 새로고침 시 404 없이 정상 렌더링한다', async ({ page }, info) => {
  test.skip(info.project.name !== 'pc', 'PC 프로젝트에서 SPA 라우팅 일괄 검증')
  await signIn(page, 'demo-admin')
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

  for (const route of ['/app/find', '/app/messages', '/app/account/payments', '/history', '/rides', '/demo', '/admin']) {
    const res = await page.goto(route)
    expect(res?.status(), `${route} HTTP status`).toBe(200)
    await expect(page.locator('#root')).not.toBeEmpty()
    await expect(page.locator('body')).not.toContainText('요청을 처리하지 못했습니다.')
    await page.reload()
    await expect(page.locator('#root')).not.toBeEmpty()
    await assertNoOverflow(page, `SPA ${route}`)
  }
  expect(errors).toEqual([])
})

test('10개 해상도에서 가로 넘침과 치명적 콘솔 오류 없이 표시된다', async ({ page }, info) => {
  test.skip(info.project.name !== 'pc', 'PC 프로젝트에서 10개 해상도 순회 검증')
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/')
  await expect(page.getByLabel('학교 메일')).toBeVisible()
  await saveShot(page, 'landing-desktop-1280')

  await page.keyboard.press('Tab')
  const hasActive = await page.evaluate(() => document.activeElement !== document.body)
  expect(hasActive).toBe(true)

  for (const [width, height] of VIEWPORTS) {
    await page.setViewportSize({ width, height })
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))))
    await assertNoOverflow(page, `landing ${width}x${height}`)
  }

  await page.setViewportSize({ width: 1280, height: 800 })
  await signIn(page, 'demo-admin')
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  await saveShot(page, 'home-desktop-1280')

  const studentRoutes = [
    ['/', () => expect(page.getByRole('tablist', { name: '방향' })).toBeVisible()],
    ['/app/find', () => expect(page.getByRole('heading', { name: '동승 찾기' })).toBeVisible()],
    ['/request/home', () => expect(page.getByRole('textbox', { name: /출발지/ })).toBeVisible()],
    ['/app/account/payments', () => expect(page.getByRole('heading', { name: '결제수단 관리' })).toBeVisible()],
    ['/app/messages', () => expect(page.getByText(/참여 중인 일행 대화가 없어요|일행 메시지/).first()).toBeVisible()],
    ['/history', () => expect(page.getByRole('heading', { name: '이용 내역' }).first()).toBeVisible()],
    ['/admin', () => expect(page.getByRole('heading', { name: '노쇼 검토' })).toBeVisible()],
  ]

  for (const [route, ready] of studentRoutes) {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto(route)
    await ready()
    for (const [width, height] of VIEWPORTS) {
      await page.setViewportSize({ width, height })
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))))
      await assertNoOverflow(page, `${route} ${width}x${height}`)
      if (width === 390 && route === '/') await saveShot(page, 'home-mobile-390')
      if (width === 1440 && route === '/admin') await saveShot(page, 'admin-desktop-1440')
    }
  }
  expect(errors).toEqual([])
})
