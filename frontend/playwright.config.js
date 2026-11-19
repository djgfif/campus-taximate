import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.TAXIMATE_E2E_PORT || 8080)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('TAXIMATE_E2E_PORT must be a valid local port')
const baseURL = `http://localhost:${port}`
const channel = process.env.TAXIMATE_E2E_CHROME === '1' ? 'chrome' : undefined

// 시연 프로필 서버를 띄워 실제 화면으로 흐름을 끝까지 누른다. 서버 jar와 화면 빌드가 먼저 있어야 한다.
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  timeout: 120_000,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL, channel, trace: 'retain-on-failure', locale: 'ko-KR', timezoneId: 'Asia/Seoul', video: process.env.TAXIMATE_E2E_VIDEO === '1' ? 'on' : 'off' },
  projects: [
    { name: 'pc', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } },
    { name: 'phone', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: `java -jar ../server/target/server-0.1.0.jar --spring.profiles.active=demo --server.port=${port} --app.upload-dir=../server/target/e2e-uploads-${port} --app.kakao.rest-key= --app.tmap.app-key=`,
    url: `${baseURL}/actuator/health`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
