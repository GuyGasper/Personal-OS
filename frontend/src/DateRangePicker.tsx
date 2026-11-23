import { useEffect, useId, useRef, useState } from 'react';
import { defaultMileageRange, mileageDateValue, mileageRangeError, parseMileageDate, type MileageRange } from './running';
import './DateRangePicker.css';

const readableDate = (value: string) => parseMileageDate(value)?.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) ?? 'Choose date';
const monthStart = (date: Date) => new Date(date.getFullYear(), date.getMonth(), 1);
const addDays = (date: Date, amount: number) => { const next = new Date(date); next.setDate(next.getDate() + amount); return next; };
const addMonths = (date: Date, amount: number) => {
  const next = new Date(date.getFullYear(), date.getMonth() + amount, 1);
  next.setDate(Math.min(date.getDate(), new Date(next.getFullYear(), next.getMonth() + 1, 0).getDate()));
  return next;
};

export default function DateRangePicker({ value, onChange }: { value: MileageRange; onChange: (range: MileageRange) => void }) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(value);
  const [selecting, setSelecting] = useState<'start' | 'end'>('start');
  const [hoverDate, setHoverDate] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [anchor, setAnchor] = useState(() => monthStart(parseMileageDate(value.endDate) ?? new Date()));
  const [focusedDate, setFocusedDate] = useState(value.endDate);
  const [compact, setCompact] = useState(() => globalThis.matchMedia('(max-width: 620px)').matches);
  const dialog = useRef<HTMLDialogElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const moveFocus = useRef(false);
  const id = useId();
  const today = defaultMileageRange().endDate;
  const todayDate = parseMileageDate(today)!;
  const months = compact ? [anchor] : [addMonths(anchor, -1), anchor];

  useEffect(() => {
    const query = globalThis.matchMedia('(max-width: 620px)');
    const update = () => setCompact(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);

  useEffect(() => {
    if (open && dialog.current && !dialog.current.open) {
      dialog.current.showModal();
    }
    if (!open && dialog.current?.open) dialog.current.close();
  }, [open, focusedDate]);

  useEffect(() => {
    if (open && moveFocus.current) {
      dialog.current?.querySelector<HTMLButtonElement>(`button[data-date="${focusedDate}"]`)?.focus();
      moveFocus.current = false;
    }
  }, [open, focusedDate, anchor, compact]);

  const close = () => { dialog.current?.close(); setOpen(false); trigger.current?.focus(); };
  const apply = () => {
    const message = mileageRangeError(pending);
    setError(message);
    if (message) return;
    onChange({ ...pending });
    close();
  };
  const openCalendar = () => {
    moveFocus.current = false;
    setPending(value);
    setSelecting('start');
    setHoverDate(null);
    setError(null);
    setAnchor(monthStart(parseMileageDate(value.endDate) ?? todayDate));
    setFocusedDate(value.endDate);
    setOpen(true);
  };
  const updateField = (field: 'startDate' | 'endDate', next: string) => {
    setPending(previous => ({ ...previous, [field]: next }));
    setSelecting(field === 'startDate' ? 'start' : 'end');
    setHoverDate(null);
    setError(null);
    const date = parseMileageDate(next);
    if (date && next <= today) { setAnchor(monthStart(date)); setFocusedDate(next); }
  };
  const chooseDay = (date: string) => {
    if (selecting === 'start' || !parseMileageDate(pending.startDate)) {
      setPending({ startDate: date, endDate: '' });
      setSelecting('end');
    } else {
      setPending(date < pending.startDate ? { startDate: date, endDate: pending.startDate } : { ...pending, endDate: date });
      setSelecting('start');
    }
    setFocusedDate(date);
    setHoverDate(null);
    setError(null);
  };
  const navigateMonth = (amount: number) => {
    setAnchor(previous => monthStart(addMonths(previous, amount)));
    const date = addMonths(parseMileageDate(focusedDate) ?? anchor, amount);
    setFocusedDate(mileageDateValue(date > todayDate ? todayDate : date));
    setHoverDate(null);
  };
  const navigateDay = (event: React.KeyboardEvent<HTMLButtonElement>, date: Date) => {
    let next: Date;
    switch (event.key) {
      case 'ArrowLeft': next = addDays(date, -1); break;
      case 'ArrowRight': next = addDays(date, 1); break;
      case 'ArrowUp': next = addDays(date, -7); break;
      case 'ArrowDown': next = addDays(date, 7); break;
      case 'Home': next = addDays(date, -date.getDay()); break;
      case 'End': next = addDays(date, 6 - date.getDay()); break;
      case 'PageUp': next = addMonths(date, event.shiftKey ? -12 : -1); break;
      case 'PageDown': next = addMonths(date, event.shiftKey ? 12 : 1); break;
      default: return;
    }
    event.preventDefault();
    if (next > todayDate) next = todayDate;
    if (next.getFullYear() < 100) return;
    if (next < months[0] || next >= addMonths(anchor, 1)) setAnchor(monthStart(next));
    const iso = mileageDateValue(next);
    if (iso === focusedDate) return;
    moveFocus.current = true;
    setFocusedDate(iso);
  };
  const preset = (count: number) => {
    setPending({ startDate: mileageDateValue(addDays(todayDate, 1 - count)), endDate: today });
    setAnchor(monthStart(todayDate));
    setFocusedDate(today);
    setSelecting('start');
    setHoverDate(null);
    setError(null);
  };
  const first = pending.startDate;
  const last = selecting === 'end' && hoverDate ? hoverDate : pending.endDate;
  const [paintStart, paintEnd] = last && last < first ? [last, first] : [first, last];

  return <div className="date-range-picker">
    <span className="range-label" id={`${id}-label`}>Date range</span>
    <button type="button" className="range-trigger" ref={trigger} onClick={openCalendar} aria-label="Choose date range" aria-haspopup="dialog" aria-expanded={open} aria-controls={`${id}-dialog`}>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M7 3v4m10-4v4M3 11h18" /></svg>
      <span className="range-trigger-dates"><span>{readableDate(value.startDate)}</span><span className="range-separator" aria-hidden="true">—</span><span>{readableDate(value.endDate)}</span></span>
      <span className="range-chevron" aria-hidden="true">⌄</span>
    </button>
    <dialog className="range-dialog" ref={dialog} id={`${id}-dialog`} aria-labelledby={`${id}-title`} aria-describedby={`${id}-hint`}
      onCancel={event => { event.preventDefault(); close(); }} onClose={() => setOpen(false)}
      onClick={event => {
        if (event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close();
      }}
      onKeyDown={event => { if (event.key === 'Enter' && event.target instanceof HTMLInputElement) { event.preventDefault(); apply(); } }}>
      <header className="range-dialog-header"><div><div className="eyebrow">A LITTLE PERSPECTIVE</div><h2 id={`${id}-title`}>Choose your dates</h2></div><button type="button" className="calendar-nav-button" aria-label="Close calendar" onClick={close}>×</button></header>
      <div className="range-dialog-body">
        <div className="range-date-fields">
          <div className={'range-date-field ' + (selecting === 'start' ? 'active' : '')}><label htmlFor={`${id}-start`}>Start date</label><input id={`${id}-start`} type="text" placeholder="YYYY-MM-DD" autoComplete="off" spellCheck={false} value={pending.startDate} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : undefined} onFocus={() => { setSelecting('start'); setHoverDate(null); }} onChange={event => updateField('startDate', event.target.value)} /></div>
          <span className="range-field-arrow" aria-hidden="true">→</span>
          <div className={'range-date-field ' + (selecting === 'end' ? 'active' : '')}><label htmlFor={`${id}-end`}>End date</label><input id={`${id}-end`} type="text" placeholder="YYYY-MM-DD" autoComplete="off" spellCheck={false} value={pending.endDate} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : undefined} onFocus={() => { setSelecting('end'); setHoverDate(null); }} onChange={event => updateField('endDate', event.target.value)} /></div>
        </div>
        <div className="range-presets" aria-label="Quick date ranges">{[30, 90, 365].map(count => <button type="button" key={count} onClick={() => preset(count)} aria-label={`Last ${count} days`}>{count === 365 ? 'Last year' : `Last ${count} days`}</button>)}</div>
        <div className="calendar-navigation"><button type="button" className="calendar-nav-button" aria-label="Previous month" onClick={() => navigateMonth(-1)}>‹</button><p id={`${id}-hint`} aria-live="polite">{selecting === 'end' ? 'Now choose an end date.' : 'Choose a start date.'}</p><button type="button" className="calendar-nav-button" aria-label="Next month" disabled={anchor >= monthStart(todayDate)} onClick={() => navigateMonth(1)}>›</button></div>
        <div className="calendar-months" onMouseLeave={() => setHoverDate(null)}>
          {months.map(month => {
            const title = month.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
            const offset = month.getDay();
            const length = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
            return <section className="calendar-month" key={mileageDateValue(month)} aria-label={title}>
              <h3>{title}</h3>
              <div className="calendar-grid" role="grid" aria-label={title}>
                <div className="calendar-week" role="row">{['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map(day => <span role="columnheader" className="calendar-weekday" key={day}>{day}</span>)}</div>
                {Array.from({ length: 6 }, (_, week) => <div className="calendar-week" role="row" key={week}>
                  {Array.from({ length: 7 }, (_, weekday) => {
                    const day = week * 7 + weekday - offset + 1;
                    if (day < 1 || day > length) return <span role="gridcell" key={weekday} />;
                    const date = new Date(month.getFullYear(), month.getMonth(), day);
                    const iso = mileageDateValue(date);
                    const start = iso === paintStart;
                    const end = iso === paintEnd;
                    const inside = Boolean(paintEnd && iso >= paintStart && iso <= paintEnd);
                    return <div role="gridcell" aria-selected={inside || start} className={'calendar-cell' + (inside ? ' in-range' : '') + (start ? ' range-start' : '') + (end ? ' range-end' : '')} key={weekday}>
                      <button type="button" data-date={iso} className={'calendar-day' + (start || end ? ' range-endpoint' : '') + (iso === today ? ' calendar-today' : '')} aria-label={date.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })} aria-current={iso === today ? 'date' : undefined} tabIndex={iso === focusedDate ? 0 : -1} disabled={iso > today} onClick={() => chooseDay(iso)} onPointerEnter={() => { if (selecting === 'end' && iso <= today) setHoverDate(iso); }} onKeyDown={event => navigateDay(event, date)}>{day}</button>
                    </div>;
                  })}
                </div>)}
              </div>
            </section>;
          })}
        </div>
        {error && <p className="range-picker-error" role="alert" id={`${id}-error`}>{error}</p>}
      </div>
      <footer className="range-dialog-footer"><span>Start and end dates are included.</span><div><button type="button" className="range-cancel" onClick={close}>Cancel</button><button type="button" className="primary-button" onClick={apply}>Apply dates ↗</button></div></footer>
    </dialog>
  </div>;
}
