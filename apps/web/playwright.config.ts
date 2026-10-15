import { defineConfig, devices } from "@playwright/test";
const baseURL = process.env.E2E_BASE_URL || "http://127.0.0.1:3017";
export default defineConfig({
  testDir: "test/e2e",
  outputDir: "test-results/browser",
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  reporter: [
    ["list"],
    ["junit", { outputFile: "test-results/e2e.xml" }],
    ["html", { open: "never" }],
  ],
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    timezoneId: "America/Sao_Paulo",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        channel: process.env.PLAYWRIGHT_CHANNEL || "chrome",
      },
    },
    {
      name: "mobile",
      use: {
        ...devices["iPhone 13"],
        defaultBrowserType: "chromium",
        channel: process.env.PLAYWRIGHT_CHANNEL || "chrome",
      },
    },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: "pnpm exec next start --hostname 127.0.0.1 --port 3017",
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 60000,
      },
});
