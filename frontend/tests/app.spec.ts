import { expect, test } from '@playwright/test';

test.use({ timezoneId: 'America/Chicago' });
const fixedDate = new Date('2026-11-02T18:00:00Z');

function mileageResponse() {
  const days = Array.from({ length: 365 }, (_, index) => ({
    date: new Date(Date.UTC(2025, 10, 3 + index)).toISOString().slice(0, 10),
    distanceMeters: index === 0 ? 1609.344 : index === 363 ? 3218.688 : index === 364 ? 321868.8 : 0,
  }));
  return { timezone: 'America/Chicago', startDate: '2025-11-03', endDate: '2026-11-02', days };
}

async function mockMileage(page: import('@playwright/test').Page, response = mileageResponse()) {
  await page.route('**/api/running/mileage?*', async route => {
    expect(route.request().headers().authorization).toBeUndefined();
    expect(new URL(route.request().url()).searchParams.get('timezone')).toBe('America/Chicago');
    await route.fulfill({ json: response });
  });
}

test('keeps the workspace and opens mileage without a token prompt', async ({ page }) => {
  const errors: string[] = [];
  const apiRequests: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (request.url().includes('/api/')) apiRequests.push(request.url()); });
  await mockMileage(page);
  await page.goto('/');
  for (const name of ['Budgeting', 'Running', 'Network']) await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  await expect(page.getByText('API connection', { exact: true })).toHaveCount(0);
  await expect(page.getByText('THE FOUNDATION', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: /System status/ })).toHaveCount(0);
  expect(apiRequests).toEqual([]);
  await page.getByRole('link', { name: /02 Running/ }).click();
  await expect(page).toHaveURL(/\/running$/);
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  await expect(page.getByRole('textbox')).toHaveCount(0);
  await expect(page.getByLabel('Running access token')).toHaveCount(0);
  await page.getByRole('link', { name: /Back to workspace/ }).click();
  await expect(page.getByRole('heading', { name: /A little more clarity/ })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: /See the miles add up/ })).toBeVisible();
  expect(errors).toEqual([]);
});

test('loads the public daily totals automatically and supports inspection and refresh', async ({ page }) => {
  await page.clock.install({ time: fixedDate });
  await mockMileage(page);
  const requests: string[] = [];
  page.on('request', request => { if (request.url().includes('/api/')) requests.push(request.url()); });
  await page.goto('/running');
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  await expect(page.locator('.mileage-summary strong')).toHaveText('203.0 mi');
  await expect(page.locator('.mileage-summary p')).toContainText('Nov 3, 2025 – Nov 2, 2026');
  expect(requests).toHaveLength(1);
  expect(requests[0]).toContain('/api/running/mileage?');
  const slider = page.getByRole('slider', { name: 'Inspect day' });
  await slider.fill('1');
  await expect(page.locator('output')).toHaveText('1.0 mi total');
  await slider.press('ArrowRight');
  await expect(page.locator('output')).toHaveText('1.0 mi total');
  await slider.fill('364');
  await expect(page.locator('output')).toHaveText('3.0 mi total');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
  await page.getByRole('button', { name: 'Refresh mileage' }).click();
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  expect(requests).toHaveLength(2);
});

test('recovers from a failure without showing a partial total', async ({ page }) => {
  await page.route('**/api/running/mileage?*', route => route.fulfill({ status: 503, json: {} }));
  await page.goto('/running');
  await expect(page.getByRole('alert')).toHaveText('Your mileage could not be loaded. Please try again.');
  await expect(page.locator('.mileage-summary')).toHaveCount(0);
  await page.unroute('**/api/running/mileage?*');
  const response = mileageResponse();
  response.days.forEach(day => { day.distanceMeters = 0; });
  await mockMileage(page, response);
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.locator('.mileage-summary strong')).toHaveText('0.0 mi');
  await expect(page.getByText(/No runs recorded in this period/)).toBeVisible();
});

for (const failure of ['invalid response', 'missing day', 'duplicate day', 'invalid distance', 'wrong end date']) {
  test(`rejects ${failure}`, async ({ page }) => {
    const response = mileageResponse();
    if (failure === 'missing day') response.days.pop();
    if (failure === 'duplicate day') response.days[1].date = response.days[0].date;
    if (failure === 'invalid distance') response.days[1].distanceMeters = -1;
    if (failure === 'wrong end date') response.endDate = '2026-11-01';
    await page.route('**/api/running/mileage?*', route => route.fulfill({ json: failure === 'invalid response' ? {} : response }));
    await page.goto('/running');
    await expect(page.getByRole('alert')).toHaveText('The running data response was not recognized.');
    await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toHaveCount(0);
  });
}

test('client routes still serve the workspace', async ({ page }) => {
  await page.goto('/workspace');
  await expect(page.getByRole('heading', { name: /A little more clarity/ })).toBeVisible();
});

test('reads public mileage from the real database-backed API', async ({ request }) => {
  const response = await request.get('/api/running/mileage?timezone=America%2FChicago');
  expect(response.ok()).toBeTruthy();
  const data = await response.json();
  expect(data.days).toHaveLength(365);
  expect(Object.keys(data).sort()).toEqual(['days', 'endDate', 'startDate', 'timezone']);
  expect(Object.keys(data.days[0]).sort()).toEqual(['date', 'distanceMeters']);
  expect((await request.get('/api/strava/activities')).ok()).toBeFalsy();
});
