import type { PathStep, Site } from './api'
import { api } from './api'
import { useAsync } from './hooks'
import { useState } from 'react'

interface Props {
  sym: string
  /** Call sites to highlight in the source (calls leading to the next step of the walk). */
  highlight: Site[]
  onSelect(sym: string): void
  onWalkDown(sym: string): void
  onWalkUp(sym: string): void
  onOpenPath(path: PathStep[]): void
}

export function Details(props: Props) {
  const details = useAsync(`node|${props.sym}`, () => api.node(props.sym))
  const source = useAsync(`src|${props.sym}`, () => api.source(props.sym).catch(() => null))
  const [showPaths, setShowPaths] = useState<string | null>(null)

  if (details.status === 'loading') return <aside className="details muted">Loading…</aside>
  if (details.status === 'error') return <aside className="details error">{details.error}</aside>

  const { node, implementations, overrides, subtypes } = details.value
  const isMethod = node.kind === 'method' || node.kind === 'constructor' || node.kind === 'val'
  const highlighted = new Set(props.highlight.filter((h) => source.status === 'ready' && source.value?.file === h.file).map((h) => h.line))

  return (
    <aside className="details">
      <div className="details-kind">{node.abstract && node.kind === 'method' ? 'abstract method' : node.kind}</div>
      <h2 className="details-title">{node.display}</h2>
      {node.signature && <code className="details-sig">{node.signature}</code>}
      <div className="details-where">
        {node.external ? 'library symbol' : `${node.service} · ${node.file}:${node.line}`}
      </div>

      {isMethod && !node.external && (
        <div className="details-actions">
          <button onClick={() => props.onWalkDown(node.symbol)}>What does it call? ↓</button>
          <button onClick={() => props.onWalkUp(node.symbol)}>Who calls it? ↑</button>
        </div>
      )}

      {overrides.length > 0 && (
        <Section title="Implements / overrides">
          {overrides.map((o) => (
            <SymLink key={o.symbol} label={o.display} onClick={() => props.onSelect(o.symbol)} />
          ))}
        </Section>
      )}
      {implementations.length > 0 && !(implementations.length === 1 && implementations[0].symbol === node.symbol) && (
        <Section title={`Implementations (${implementations.length})`}>
          {implementations.map((o) => (
            <SymLink key={o.symbol} label={o.display} onClick={() => props.onSelect(o.symbol)} />
          ))}
        </Section>
      )}
      {subtypes.length > 0 && (
        <Section title={`Subtypes (${subtypes.length})`}>
          {subtypes.map((o) => (
            <SymLink key={o.symbol} label={o.display} onClick={() => props.onSelect(o.symbol)} />
          ))}
        </Section>
      )}

      {isMethod && !node.external && (
        <Section title="Paths from entrypoints">
          {showPaths === node.symbol ? (
            <Paths sym={node.symbol} onOpen={props.onOpenPath} />
          ) : (
            <button className="link-btn" onClick={() => setShowPaths(node.symbol)}>
              Find every call chain that reaches it
            </button>
          )}
        </Section>
      )}

      {source.status === 'ready' && source.value && (
        <Section title="Source">
          <pre className="source">
            {source.value.lines.map((l, i) => {
              const n = source.value!.startLine + i
              const inDef = n >= source.value!.focusLine && n <= source.value!.endLine
              return (
                <div key={n} className={`src-line ${inDef ? 'src-def' : ''} ${highlighted.has(n) ? 'src-call' : ''}`}>
                  <span className="src-no">{n}</span>
                  <span className="src-text">{l || ' '}</span>
                </div>
              )
            })}
          </pre>
        </Section>
      )}
    </aside>
  )
}

function Section(props: { title: string; children: React.ReactNode }) {
  return (
    <section className="details-section">
      <h3>{props.title}</h3>
      {props.children}
    </section>
  )
}

function SymLink(props: { label: string; onClick(): void }) {
  return (
    <button className="sym-link" onClick={props.onClick}>
      {props.label}
    </button>
  )
}

function Paths(props: { sym: string; onOpen(path: PathStep[]): void }) {
  const paths = useAsync(`paths|${props.sym}`, () => api.paths(props.sym))
  if (paths.status === 'loading') return <div className="muted">Searching…</div>
  if (paths.status === 'error') return <div className="error">{paths.error}</div>
  if (paths.value.length === 0) return <div className="muted">No callers: this is an entrypoint.</div>
  return (
    <ol className="paths">
      {paths.value.map((p, i) => (
        <li key={i}>
          <button className="path" onClick={() => props.onOpen(p)} title="Open this chain in the map">
            {p.map((s, k) => (
              <span key={k} className="path-step">
                {k > 0 && <span className="path-arrow">→</span>}
                {s.node.display}
              </span>
            ))}
          </button>
        </li>
      ))}
    </ol>
  )
}
