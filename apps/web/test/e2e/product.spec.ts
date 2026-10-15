import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
test("T-030 identity, navigation, modal focus and synthetic isolation", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: /Um espaço para/ }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Encontre seu atendimento" }),
  ).toHaveAttribute("href", "/profissionais");
  await page.screenshot({
    path: "test-results/landing-" + info.project.name + ".png",
    fullPage: true,
  });
  await page.goto("/demonstracao/dashboard");
  await expect(page.getByText("DEMONSTRAÇÃO · DADOS FICTÍCIOS")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Seu cuidado começa por aqui." }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/dashboard-" + info.project.name + ".png",
    fullPage: true,
  });
  await page.goto("/demonstracao/agenda");
  await expect(
    page.getByRole("heading", { name: "Tempo para cada encontro." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Novo atendimento" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByLabel("Paciente", { exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await page.screenshot({
    path: "test-results/agenda-" + info.project.name + ".png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Mês", exact: true }).click();
  await expect(page.locator(".month-grid")).toBeVisible();
  const monthly = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    monthly.violations.filter(
      (v) => v.impact === "critical" || v.impact === "serious",
    ),
  ).toEqual([]);
  if (info.project.name === "mobile") {
    await page.getByRole("button", { name: "Abrir menu" }).click();
    await expect(
      page.getByRole("link", { name: "Pacientes", exact: true }),
    ).toBeVisible();
    await page.getByRole("link", { name: "Pacientes", exact: true }).click();
  } else
    await page.getByRole("link", { name: "Pacientes", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Buscar pacientes" })
    .fill("não existe");
  await expect(
    page.getByRole("heading", { name: "Nenhum paciente encontrado" }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
test("T-030 principal screens have no critical or serious accessibility violations", async ({
  page,
}) => {
  for (const path of [
    "/",
    "/login",
    "/demonstracao/dashboard",
    "/demonstracao/agenda",
    "/demonstracao/pacientes",
    "/demonstracao/prontuario",
    "/demonstracao/financeiro",
    "/demonstracao/recibos",
    "/demonstracao/documentos",
    "/demonstracao/conteudos",
    "/demonstracao/configuracoes",
  ]) {
    await page.goto(path);
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
      .analyze();
    expect(
      result.violations.filter(
        (v) => v.impact === "critical" || v.impact === "serious",
      ),
      path,
    ).toEqual([]);
  }
});
test("SEC-011 anonymous private route requires login and health exposes no secrets", async ({
  page,
  request,
}) => {
  await page.goto("/app");
  await expect(page).toHaveURL(/\/login/);
  const response = await request.get("/api/health");
  expect(await response.json()).toEqual({
    status: "UP",
    service: "conhecerse-web",
  });
  const session = await request.get("/api/session");
  expect(await session.json()).toEqual({ authenticated: false });
});
