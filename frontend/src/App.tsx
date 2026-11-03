import RunningPage from './RunningPage';

const modules = [
  { id: 'budgeting', symbol: '$', title: 'Budgeting', detail: 'Give every dollar a direction.', description: 'A home for spending, budgets, and a clearer picture of your finances.' },
  { id: 'running', symbol: '↗', title: 'Running', detail: 'See the miles add up.', description: 'Bring your runs, training, and progress together in one place.' },
  { id: 'network', symbol: '⌘', title: 'Network', detail: 'Make room for the technical stuff.', description: 'Useful calculations and network tools, ready when you need them.' },
];

export default function App() {
  const running = window.location.pathname.replace(/\/$/, '') === '/running';

  return (
    <div className="shell">
      <header className="header">
        <a className="brand" href="/" aria-label="Personal OS home"><span className="brand-mark">p.</span>Personal OS</a>
        {running && <a className="header-link" href="/">Back to workspace <span aria-hidden="true">↗</span></a>}
      </header>
      <main>
        {running ? <RunningPage /> : <>
          <section className="hero" aria-labelledby="welcome-title">
            <div className="eyebrow"><span className="little-line" /> YOUR SPACE, TAKING SHAPE</div>
            <h1 id="welcome-title">A little more clarity.<br /><span>A place for everything.</span></h1>
            <p>Your finances, your miles, your everyday tools.<br className="desktop-break" /> One personal operating system, built a piece at a time.</p>
            <div className="hero-note"><span aria-hidden="true">✳</span> Starting small. Building something useful.</div>
          </section>
          <section className="workspace" aria-labelledby="workspace-title">
            <div className="section-heading"><h2 id="workspace-title">Your workspace</h2><span>THREE THINGS TO BUILD ON</span></div>
            <div className="module-grid">
              {modules.map((module, index) => {
                const Card = module.id === 'running' ? 'a' : 'article';
                return <Card className={'module module-' + module.id} key={module.id} href={module.id === 'running' ? '/running' : undefined}>
                  <div className="module-top"><span className="module-icon" aria-hidden="true">{module.symbol}</span><span className="module-number">0{index + 1}</span></div>
                  <h3>{module.title}</h3><p className="module-detail">{module.detail}</p><p className="module-description">{module.description}</p>
                  <div className="module-footer"><span className="planned-dot" /> {module.id === 'running' ? 'View your mileage' : 'Planned'} <span aria-hidden="true">↗</span></div>
                </Card>;
              })}
            </div>
          </section>
        </>}
      </main>
      <footer><span>Personal OS <span className="footer-divider">/</span> A work in progress, by design.</span><span>FIRST STEPS, FORWARD.</span></footer>
    </div>
  );
}
