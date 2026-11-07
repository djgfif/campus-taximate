import { defineConfig, devices } from '@playwright/test'
import base from './playwright.config.js'
// 로컬 전용: 여러 브라우저 호환 확인 (NFR-06). 계정이 프로젝트 이름(pc, phone)에 묶여 있어 실행마다 한 쌍씩 바꾼다.
const SETS = {
  edge: [{ ...devices['Desktop Edge'], channel: 'msedge' }, devices['iPhone 14']],
  firefox: [devices['Desktop Firefox'], devices['Galaxy S9+']],
  safari: [devices['Desktop Safari'], devices['iPhone SE']],
}
const [pc, phone] = SETS[process.env.BSET]
export default defineConfig({
  ...base,
  reporter: 'list',
  projects: [
    { name: 'pc', use: { ...pc, viewport: { width: 1280, height: 800 } } },
    { name: 'phone', use: phone },
  ],
})
