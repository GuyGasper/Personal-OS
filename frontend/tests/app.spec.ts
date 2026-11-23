import { expect, test } from '@playwright/test';

test.use({ timezoneId: 'America/Chicago' });
const fixedDate = new Date('2026-11-23T18:00:00Z');
test.beforeEach(async ({ page }) => { await page.clock.install({ time: fixedDate }); });

function mileageResponse(startDate = '2025-11-24', endDate = '2026-11-23') {
  const first = Date.parse(startDate + 'T00:00:00Z');
  const count = (Date.parse(endDate + 'T00:00:00Z') - first) / 86_400_000 + 1;
  const days = Array.from({ length: count }, (_, index) => ({
    date: new Date(first + index * 86_400_000).toISOString().slice(0, 10),
    distanceMeters: index === 0 ? 1609.344 : index === count - 2 ? 3218.688 : index === count - 1 ? 321868.8 : 0,
  }));
  return { timezone: 'America/Chicago', startDate, endDate, days };
}

async function mockMileage(page: import('@playwright/test').Page, response?: ReturnType<typeof mileageResponse>) {
  await page.route('**/api/running/mileage?*', async route => {
    expect(route.request().headers().authorization).toBeUndefined();
    expect(new URL(route.request().url()).searchParams.get('timezone')).toBe('America/Chicago');
    const query = new URL(route.request().url()).searchParams;
    await route.fulfill({ json: response ?? mileageResponse(query.get('startDate')!, query.get('endDate')!) });
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
  await expect(page.getByLabel('Running access token')).toHaveCount(0);
  await page.getByRole('link', { name: /Back to workspace/ }).click();
  await expect(page.getByRole('heading', { name: /A little more clarity/ })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: /See the miles add up/ })).toBeVisible();
  expect(errors).toEqual([]);
});

test('loads the public daily totals automatically and supports inspection and refresh', async ({ page }) => {
  await mockMileage(page);
  const requests: string[] = [];
  page.on('request', request => { if (request.url().includes('/api/')) requests.push(request.url()); });
  await page.goto('/running');
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  await expect(page.locator('.mileage-summary strong')).toHaveText('203.0 mi');
  await expect(page.locator('.mileage-summary p')).toContainText('Nov 24, 2025 – Nov 23, 2026');
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
    if (failure === 'wrong end date') response.endDate = '2026-11-22';
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

test('searches a custom window, refreshes it, and resets to the last year', async ({ page }) => {
  const dates: string[] = [];
  await page.route('**/api/running/mileage?*', route => {
    const query = new URL(route.request().url()).searchParams;
    dates.push(`${query.get('startDate')}/${query.get('endDate')}`);
    const response = mileageResponse(query.get('startDate')!, query.get('endDate')!);
    response.days.forEach((day, index) => { day.distanceMeters = (index + 1) * 1609.344; });
    return route.fulfill({ json: response });
  });
  await page.goto('/running');
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  await page.getByLabel('Start date', { exact: true }).fill('2026-11-17');
  await page.getByLabel('End date', { exact: true }).fill('2026-11-23');
  await page.getByRole('button', { name: 'Search mileage' }).click();
  await expect(page.locator('.mileage-summary strong')).toHaveText('28.0 mi');
  await expect(page.locator('.period-pill')).toHaveText('7 DAYS');
  await expect(page.getByRole('slider', { name: 'Inspect day' })).toHaveAttribute('max', '7');
  await page.getByRole('slider', { name: 'Inspect day' }).fill('1');
  await expect(page.locator('output')).toHaveText('1.0 mi total');
  await page.getByRole('button', { name: 'Refresh mileage' }).click();
  await expect(page.locator('.mileage-summary strong')).toHaveText('28.0 mi');
  expect(dates.slice(-2)).toEqual(['2026-11-17/2026-11-23', '2026-11-17/2026-11-23']);
  await page.getByRole('button', { name: 'Last 365 days' }).click();
  await expect(page.getByLabel('Start date', { exact: true })).toHaveValue('2025-11-24');
  await expect(page.locator('.period-pill')).toHaveText('365 DAYS');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
});

test('supports a single day and a multi-year window', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await mockMileage(page);
  await page.goto('/running');
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  await page.getByLabel('Start date', { exact: true }).fill('2026-11-20');
  await page.getByLabel('End date', { exact: true }).fill('2026-11-20');
  await page.getByRole('button', { name: 'Search mileage' }).click();
  await expect(page.locator('.mileage-summary strong')).toHaveText('1.0 mi');
  await expect(page.locator('.period-pill')).toHaveText('1 DAY');
  await expect(page.getByRole('slider', { name: 'Inspect day' })).toBeDisabled();
  expect(await page.locator('.mileage-chart path').evaluateAll(paths => paths.every(path => !/NaN|Infinity/.test(path.getAttribute('d') ?? '')))).toBeTruthy();
  await page.getByLabel('Start date', { exact: true }).fill('2024-01-01');
  await page.getByLabel('End date', { exact: true }).fill('2025-12-31');
  await page.getByRole('button', { name: 'Search mileage' }).click();
  await expect(page.locator('.period-pill')).toHaveText('731 DAYS');
  await expect(page.getByRole('slider', { name: 'Inspect day' })).toHaveAttribute('max', '731');
  await expect(page.locator('.mileage-summary strong')).toHaveText('203.0 mi');
  expect(errors).toEqual([]);
});

test('validates dates without replacing the current graph or querying invalid ranges', async ({ page }) => {
  await mockMileage(page);
  let requests = 0;
  page.on('request', request => { if (request.url().includes('/api/running/mileage')) requests++; });
  await page.goto('/running');
  await expect(page.getByRole('heading', { name: 'Cumulative mileage' })).toBeVisible();
  for (const [start, end, message] of [
    ['2026-11-23', '2026-11-22', 'Start date must be on or before end date.'],
    ['2026-11-23', '2026-11-24', 'End date cannot be in the future.'],
    ['', '2026-11-23', 'Choose both a start and end date.'],
    ['1900-01-01', '2026-11-23', 'Choose a date range of 36,600 days or fewer.'],
  ]) {
    await page.getByLabel('Start date', { exact: true }).fill(start);
    await page.getByLabel('End date', { exact: true }).fill(end);
    await page.getByRole('button', { name: 'Search mileage' }).click();
    await expect(page.getByRole('alert')).toHaveText(message);
    await expect(page.locator('.mileage-summary strong')).toHaveText('203.0 mi');
  }
  expect(requests).toBe(1);
});
