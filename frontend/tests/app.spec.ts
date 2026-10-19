import { expect, test } from '@playwright/test';

test('connects to the real API and can recheck the connection', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByText('Connected', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Budgeting', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Running', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Network', exact: true })).toBeVisible();
  const nextResponse = page.waitForResponse(response => response.url().endsWith('/api/status'));
  await page.getByRole('button', { name: 'Check connection' }).click();
  const response = await nextResponse;
  expect(response.ok()).toBeTruthy();
  expect(await response.json()).toMatchObject({ service: 'Personal OS API', status: 'UP' });
  await expect(page.getByText('Connected', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  expect(errors).toEqual([]);
});

test('client routes connect to the real API', async ({ page }) => {
  await page.goto('/workspace');
  await expect(page.getByRole('heading', { name: /A little more clarity/ })).toBeVisible();
  await expect(page.getByText('Connected', { exact: true })).toBeVisible();
});

test('shows API failure and recovers on retry', async ({ page }) => {
  await page.route('**/api/status', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{}' }));
  await page.goto('/');
  await expect(page.getByText('Unavailable', { exact: true })).toBeVisible();
  await expect(page.getByText('The API returned HTTP 503.', { exact: true })).toBeVisible();
  await page.unroute('**/api/status');
  await page.getByRole('button', { name: 'Check connection' }).click();
  await expect(page.getByText('Connected', { exact: true })).toBeVisible();
});

test('rejects an unexpected API response', async ({ page }) => {
  await page.route('**/api/status', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await page.goto('/');
  await expect(page.getByText('The API response was not recognized.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Check connection' })).toBeEnabled();
});
