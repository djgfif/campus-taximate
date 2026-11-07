import { defineConfig, devices } from '@playwright/test'

// 시연 프로필 서버를 띄워 실제 화면으로 흐름을 끝까지 누른다. 서버 jar와 화면 빌드가 먼저 있어야 한다.
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  timeout: 120_000,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL: 'http://localhost:8080', trace: 'retain-on-failure', locale: 'ko-KR', timezoneId: 'Asia/Seoul' },
  projects: [
    { name: 'pc', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } },
    { name: 'phone', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'java -jar ../server/target/server-0.1.0.jar --spring.profiles.active=demo --app.upload-dir=../server/target/e2e-uploads --app.kakao.rest-key=',
    url: 'http://localhost:8080/actuator/health',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
