import { useCallback, useEffect, useRef, useState } from 'react';

type ApiStatus = {
  service: string;
  status: 'UP';
  version: string;
  timestamp: string;
  modules: { id: string; name: string; status: string }[];
};
type Connection =
  | { state: 'checking' }
  | { state: 'connected'; data: ApiStatus; checkedAt: Date }
  | { state: 'unavailable'; message: string };

const modules = [
  { id: 'budgeting', symbol: '$', title: 'Budgeting', detail: 'Give every dollar a direction.', description: 'A home for spending, budgets, and a clearer picture of your finances.' },
  { id: 'running', symbol: '↗', title: 'Running', detail: 'See the miles add up.', description: 'Bring your runs, training, and progress together in one place.' },
  { id: 'network', symbol: '⌘', title: 'Network', detail: 'Make room for the technical stuff.', description: 'Useful calculations and network tools, ready when you need them.' },
];

function isApiStatus(value: unknown): value is ApiStatus {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  return item.service === 'Personal OS API' && item.status === 'UP'
    && typeof item.version === 'string' && typeof item.timestamp === 'string'
    && !Number.isNaN(Date.parse(item.timestamp)) && Array.isArray(item.modules)
    && item.modules.every(module => module && typeof module.id === 'string'
      && typeof module.name === 'string' && typeof module.status === 'string');
}

export default function App() {
  const [connection, setConnection] = useState<Connection>({ state: 'checking' });
  const activeRequest = useRef<AbortController | null>(null);

  const checkConnection = useCallback(async () => {
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    setConnection({ state: 'checking' });
    const timeout = window.setTimeout(() => controller.abort(), 8_000);
    try {
      const response = await fetch('/api/status', {
        signal: controller.signal, cache: 'no-store', headers: { Accept: 'application/json' },
      });
      if (!response.ok) throw new Error('The API returned HTTP ' + response.status + '.');
      const data: unknown = await response.json();
      if (!isApiStatus(data)) throw new Error('The API response was not recognized.');
      if (activeRequest.current === controller) {
        setConnection({ state: 'connected', data, checkedAt: new Date() });
      }
    } catch (error) {
      if (activeRequest.current === controller) {
        setConnection({ state: 'unavailable', message: controller.signal.aborted
          ? 'The API check timed out. Try again in a moment.'
          : error instanceof Error ? error.message : 'Could not reach the API.' });
      }
    } finally {
      window.clearTimeout(timeout);
    }
  }, []);

  useEffect(() => {
    void checkConnection();
    return () => { activeRequest.current?.abort(); activeRequest.current = null; };
  }, [checkConnection]);

  return (
    <div className="shell">
      <header className="header">
        <a className="brand" href="/" aria-label="Personal OS home"><span className="brand-mark">p.</span>Personal OS</a>
        <a className="header-link" href="#system">System status <span aria-hidden="true">↗</span></a>
      </header>
      <main>
        <section className="hero" aria-labelledby="welcome-title">
          <div className="eyebrow"><span className="little-line" /> YOUR SPACE, TAKING SHAPE</div>
          <h1 id="welcome-title">A little more clarity.<br /><span>A place for everything.</span></h1>
          <p>Your finances, your miles, your everyday tools.<br className="desktop-break" /> One personal operating system, built a piece at a time.</p>
          <div className="hero-note"><span aria-hidden="true">✳</span> Starting small. Building something useful.</div>
        </section>
        <section className="workspace" aria-labelledby="workspace-title">
          <div className="section-heading"><h2 id="workspace-title">Your workspace</h2><span>THREE THINGS TO BUILD ON</span></div>
          <div className="module-grid">
            {modules.map((module, index) => (
              <article className={'module module-' + module.id} key={module.id}>
                <div className="module-top"><span className="module-icon" aria-hidden="true">{module.symbol}</span><span className="module-number">0{index + 1}</span></div>
                <h3>{module.title}</h3><p className="module-detail">{module.detail}</p><p className="module-description">{module.description}</p>
                <div className="module-footer"><span className="planned-dot" /> Planned <span aria-hidden="true">↗</span></div>
              </article>
            ))}
          </div>
        </section>
        <section id="system" className="system" aria-labelledby="system-title">
          <div className="system-intro"><div className="eyebrow">THE FOUNDATION</div><h2 id="system-title">Small start. Solid connection.</h2><p>This first release checks the connection from your browser to the Java API.</p></div>
          <div className="connection-panel">
            <div className="connection-top"><h3>API connection</h3><span className={'status-pill ' + connection.state} aria-live="polite"><span />{connection.state === 'connected' ? 'Connected' : connection.state === 'checking' ? 'Checking…' : 'Unavailable'}</span></div>
            <div className="connection-details" aria-live="polite">
              {connection.state === 'connected' ? <><p>React → API <span>Request successful</span></p><p>API version <span>{connection.data.version}</span></p><p>Last checked <span>{connection.checkedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span></p></>
                : <p className="connection-message">{connection.state === 'checking' ? 'Sending a request to your backend…' : connection.message}</p>}
            </div>
            <button type="button" onClick={() => void checkConnection()} disabled={connection.state === 'checking'}><span aria-hidden="true">↻</span> Check connection</button>
          </div>
        </section>
      </main>
      <footer><span>Personal OS <span className="footer-divider">/</span> A work in progress, by design.</span><span>FIRST STEPS, FORWARD.</span></footer>
    </div>
  );
}
