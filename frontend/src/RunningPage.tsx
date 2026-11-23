import { useCallback, useEffect, useRef, useState } from 'react';
import { defaultMileageRange, loadRunningMileage, mileageRangeError, type MileageDay, type MileageRange } from './running';

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1, minimumFractionDigits: 1 });
const dateLabel = (date: Date) => date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
type MileageState = { state: 'loading' | 'error'; message?: string } | { state: 'ready'; days: MileageDay[] };

function MileageChart({ days }: { days: MileageDay[] }) {
  const [selected, setSelected] = useState(days.length - 1);
  const [compact, setCompact] = useState(() => globalThis.matchMedia('(max-width: 620px)').matches);
  useEffect(() => {
    const query = globalThis.matchMedia('(max-width: 620px)');
    const update = () => setCompact(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  const width = compact ? 600 : 900;
  const right = width - 28;
  const count = days.length;
  const selectedIndex = Math.min(selected, count - 1);
  const current = days[selectedIndex];
  const total = days[count - 1].total;
  const top = Math.max(10, Math.ceil(total / 50) * 50);
  const x = (index: number) => 76 + (count === 1 ? 0.5 : index / (count - 1)) * (right - 76);
  const y = (miles: number) => 290 - miles / top * 240;
  const line = days.map((day, index) => `${index === 0 ? 'M' : 'L'}${x(index)},${y(day.total)}`).join(' ');
  const selectDay = (event: React.PointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const plotX = (event.clientX - bounds.left) / bounds.width * width;
    setSelected(Math.max(0, Math.min(count - 1, Math.round((plotX - 76) / (right - 76) * (count - 1)))));
  };

  return <section className="mileage-panel" aria-labelledby="mileage-title">
    <div className="mileage-heading">
      <div><div className="eyebrow">ONE RUN AT A TIME</div><h2 id="mileage-title">Cumulative mileage</h2></div>
      <span className="period-pill">{count.toLocaleString()} {count === 1 ? 'DAY' : 'DAYS'}</span>
    </div>
    <div className="mileage-summary"><strong>{number.format(total)}<span> mi</span></strong><p>Total running mileage <span>·</span> {dateLabel(days[0].date)} – {dateLabel(days[count - 1].date)}</p></div>
    <svg className="mileage-chart" viewBox={`0 0 ${width} 350`} role="img" aria-labelledby="chart-title chart-description"
      onPointerDown={selectDay} onPointerMove={event => { if (event.pointerType === 'mouse' || event.buttons) selectDay(event); }}>
      <title id="chart-title">Running mileage for the selected date window</title>
      <desc id="chart-description">Days 1 through {count} on the horizontal axis; cumulative miles on the vertical axis. Total {number.format(total)} miles. Use the day slider below to inspect each day.</desc>
      <defs><linearGradient id="mileage-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#aa7759" stopOpacity=".17" /><stop offset="100%" stopColor="#aa7759" stopOpacity=".01" /></linearGradient></defs>
      <text x="76" y="23" className="axis-title">TOTAL MILEAGE (MI)</text>
      {[0, 1, 2, 3, 4].map(tick => <g key={tick}><line x1="76" x2={right} y1={y(top * tick / 4)} y2={y(top * tick / 4)} className="chart-grid" /><text x="62" y={y(top * tick / 4) + 4} textAnchor="end">{new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(top * tick / 4)}</text></g>)}
      {count > 1 && <path d={`${line} L${right},290 L76,290 Z`} fill="url(#mileage-fill)" />}
      <path d={line} className="mileage-line" />
      {[...new Set(Array.from({ length: 5 }, (_, index) => Math.round(index * (count - 1) / 4) + 1))].map(day => <text key={day} x={x(day - 1)} y="313" textAnchor="middle">{day}</text>)}
      <text x={(76 + right) / 2} y="342" textAnchor="middle" className="axis-title">DAYS</text>
      <line x1={x(selectedIndex)} x2={x(selectedIndex)} y1="50" y2="290" className="chart-guide" />
      <circle cx={x(selectedIndex)} cy={y(current.total)} r="5" fill="#aa7759" stroke="#fffefb" strokeWidth="3" />
    </svg>
    <div className="day-inspector"><label htmlFor="mileage-day">Day {current.day} <span>· {dateLabel(current.date)}</span></label><output htmlFor="mileage-day">{number.format(current.total)} mi <span>total</span></output></div>
    <input id="mileage-day" className="day-slider" type="range" min="1" max={count} value={selectedIndex + 1} disabled={count === 1} aria-label="Inspect day"
      aria-valuetext={`Day ${current.day}, ${dateLabel(current.date)}, ${number.format(current.total)} total miles, ${number.format(current.miles)} miles run`}
      onChange={event => setSelected(Number(event.target.value) - 1)} />
    <p className="mileage-note">{total === 0 ? 'No runs recorded in this period. Your miles will appear here after your next Strava import.' : 'Your miles add up from day 1. Days without a run keep the total steady.'}</p>
  </section>;
}

export default function RunningPage() {
  const [range, setRange] = useState<MileageRange>(() => defaultMileageRange());
  const [draft, setDraft] = useState(range);
  const [rangeError, setRangeError] = useState<string | null>(null);
  const [mileage, setMileage] = useState<MileageState>({ state: 'loading' });
  const activeRequest = useRef<AbortController | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);

  const load = useCallback(async () => {
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    setMileage({ state: 'loading' });
    const timeout = globalThis.setTimeout(() => controller.abort(), 30_000);
    try {
      const days = await loadRunningMileage(controller.signal, range);
      if (activeRequest.current === controller) {
        setMileage({ state: 'ready', days });
        heading.current?.focus();
      }
    } catch (error) {
      if (activeRequest.current === controller) setMileage({ state: 'error', message: controller.signal.aborted
        ? 'Loading your mileage took too long. Please try again.'
        : error instanceof Error ? error.message : 'Your mileage could not be loaded. Please try again.' });
    } finally { globalThis.clearTimeout(timeout); }
  }, [range]);

  useEffect(() => {
    void load();
    return () => { activeRequest.current?.abort(); activeRequest.current = null; };
  }, [load]);

  return <div className="running-page">
    <section className="hero running-hero" aria-labelledby="running-title">
      <div className="eyebrow"><span className="little-line" /> YOUR WORKSPACE / 02</div>
      <h1 id="running-title" tabIndex={-1} ref={heading}>See the miles <span>add up.</span></h1>
      <p>Your running, one day at a time.<br />Choose a date window and see your cumulative mileage.</p>
    </section>
    <form className="date-window" aria-label="Mileage date window" noValidate onSubmit={event => {
      event.preventDefault();
      const error = mileageRangeError(draft);
      setRangeError(error);
      if (!error) setRange({ ...draft });
    }}>
      <div className="date-field"><label htmlFor="mileage-start">Start date</label><input id="mileage-start" type="date" required max={defaultMileageRange().endDate} value={draft.startDate} onChange={event => { setDraft({ ...draft, startDate: event.target.value }); setRangeError(null); }} /></div>
      <div className="date-field"><label htmlFor="mileage-end">End date</label><input id="mileage-end" type="date" required max={defaultMileageRange().endDate} value={draft.endDate} onChange={event => { setDraft({ ...draft, endDate: event.target.value }); setRangeError(null); }} /></div>
      <button className="primary-button" type="submit">Search mileage ↗</button>
      <button className="text-button" type="button" onClick={() => { const dates = defaultMileageRange(); setDraft(dates); setRange(dates); setRangeError(null); }}>Last 365 days</button>
      {rangeError && <p className="date-window-error" role="alert">{rangeError}</p>}
    </form>
    {mileage.state === 'ready' ? <>
      <MileageChart days={mileage.days} />
      <div className="running-actions"><span>Imported from Strava · Runs only · Dates in your local time</span><button type="button" className="text-button" onClick={() => void load()}>Refresh mileage ↻</button></div>
    </> : <section className="running-access" aria-labelledby="access-title" aria-busy={mileage.state === 'loading'}>
      <span className="module-icon" aria-hidden="true">↗</span>
      <h2 id="access-title">Your miles, in one place.</h2>
      {mileage.state === 'loading' ? <p role="status">Loading your imported mileage…</p> : <>
        <p role="alert" className="running-error">{mileage.message}</p>
        <button className="primary-button retry-mileage" type="button" onClick={() => void load()}>Try again ↻</button>
      </>}
    </section>}
  </div>;
}
