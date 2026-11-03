export type MileageDay = { day: number; date: Date; miles: number; total: number };
const METERS_PER_MILE = 1609.344;

function parseDate(value: unknown): Date | null {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : null;
}

export async function loadRunningMileage(signal: AbortSignal): Promise<MileageDay[]> {
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const response = await fetch(`/api/running/mileage?timezone=${encodeURIComponent(timezone)}`, {
    signal, cache: 'no-store', headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error('Your mileage could not be loaded. Please try again.');
  const data: unknown = await response.json();
  const invalid = () => new Error('The running data response was not recognized.');
  if (!data || typeof data !== 'object') throw invalid();
  const mileage = data as Record<string, unknown>;
  const start = parseDate(mileage.startDate);
  const end = parseDate(mileage.endDate);
  if (!start || !end || !Array.isArray(mileage.days) || mileage.days.length !== 365) throw invalid();
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
  if (days[364].date.getTime() !== end.getTime()) throw invalid();
  return days;
}
