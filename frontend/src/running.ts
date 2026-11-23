export type MileageDay = { day: number; date: Date; miles: number; total: number };
export type MileageRange = { startDate: string; endDate: string };
const METERS_PER_MILE = 1609.344;
const MAX_DAYS = 36_600;

function dateValue(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function defaultMileageRange(now = new Date()): MileageRange {
  const start = new Date(now);
  start.setDate(start.getDate() - 364);
  return { startDate: dateValue(start), endDate: dateValue(now) };
}

function parseDate(value: unknown): Date | null {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : null;
}

function dayCount(start: Date, end: Date): number {
  return (Date.UTC(end.getFullYear(), end.getMonth(), end.getDate())
    - Date.UTC(start.getFullYear(), start.getMonth(), start.getDate())) / 86_400_000 + 1;
}

export function mileageRangeError(range: MileageRange): string | null {
  if (!range.startDate || !range.endDate) return 'Choose both a start and end date.';
  const start = parseDate(range.startDate);
  const end = parseDate(range.endDate);
  if (!start || !end) return 'Enter valid start and end dates.';
  const count = dayCount(start, end);
  if (count < 1) return 'Start date must be on or before end date.';
  if (range.endDate > defaultMileageRange().endDate) return 'End date cannot be in the future.';
  if (count > MAX_DAYS) return 'Choose a date range of 36,600 days or fewer.';
  return null;
}

export async function loadRunningMileage(signal: AbortSignal, range: MileageRange): Promise<MileageDay[]> {
  const query = new URLSearchParams({ timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, ...range });
  const response = await fetch(`/api/running/mileage?${query}`, {
    signal, cache: 'no-store', headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error('Your mileage could not be loaded. Please try again.');
  const data: unknown = await response.json();
  const invalid = () => new Error('The running data response was not recognized.');
  if (!data || typeof data !== 'object') throw invalid();
  const mileage = data as Record<string, unknown>;
  const start = parseDate(mileage.startDate);
  const end = parseDate(mileage.endDate);
  if (!start || !end || mileage.startDate !== range.startDate || mileage.endDate !== range.endDate
    || !Array.isArray(mileage.days)) throw invalid();
  const count = dayCount(start, end);
  if (count < 1 || count > MAX_DAYS || mileage.days.length !== count) throw invalid();
  let total = 0;
  const days = mileage.days.map((value: unknown, index: number): MileageDay => {
    if (!value || typeof value !== 'object') throw invalid();
    const item = value as Record<string, unknown>;
    const date = parseDate(item.date);
    const expected = new Date(start);
    expected.setDate(expected.getDate() + index);
    if (!date || date.getTime() !== expected.getTime() || typeof item.distanceMeters !== 'number'
      || !Number.isFinite(item.distanceMeters) || item.distanceMeters < 0) throw invalid();
    const miles = item.distanceMeters / METERS_PER_MILE;
    total += miles;
    if (!Number.isFinite(total)) throw invalid();
    return { day: index + 1, date, miles, total };
  });
  return days;
}
