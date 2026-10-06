import { ReactFlowProvider } from '@xyflow/react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PathStep, Site, SymbolNode } from './api'
import { api } from './api'
import { ActionsContext, EndpointLabel, type Actions } from './Box'
import { Canvas } from './Canvas'
import { PackageCanvas } from './PackageMap'
import { Details } from './Details'
import { useAsync } from './hooks'
import { loadDown, loadUp, type CanvasModel, type DownModel, type UpModel } from './model'
import { effective, readState, startAt, writeState, type DownStep, type ViewState } from './state'

export function App() {
  const [state, setStateRaw] = useState<ViewState>(readState)

  // Every change is a history entry, so the browser's back button undoes a choice.
  const setState = useCallback((next: ViewState | ((s: ViewState) => ViewState), replace = false) => {
    setStateRaw((prev) => {
      const value = typeof next === 'function' ? next(prev) : next
      writeState(value, replace)
      return value
    })
  }, [])

  useEffect(() => {
    writeState(state, true)
    const onPop = () => setStateRaw(readState())
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const key = JSON.stringify({ m: state.mode, d: state.mode === 'down' ? state.down : state.up, x: state.showExternal })
  const model = useAsync<CanvasModel | null>(key, () => {
    if (state.mode === 'down') return state.down.length ? loadDown(state) : Promise.resolve(null)
    return state.up.length ? loadUp(state) : Promise.resolve(null)
  })

  const actions: Actions = useMemo(
    () => ({
      select: (sym) => setState((s) => ({ ...s, selected: sym }), true),
      choose: (index, impl) =>
        setState((s) => ({ ...s, down: [...s.down.slice(0, index), { sym: s.down[index].sym, choice: impl }], selected: impl })),
      follow: (callee, impl) =>
        setState((s) => {
          const choice = impl ?? callee.sole?.symbol
          return { ...s, down: [...s.down, { sym: callee.target.symbol, choice }], selected: choice ?? callee.target.symbol }
        }),
      branchFrom: (index) => setState((s) => ({ ...s, down: s.down.slice(0, index + 1), selected: effective(s.down[index]) })),
      startFrom: (sym) => setState((s) => ({ ...s, down: [{ sym }], selected: sym })),
      climb: (caller) => setState((s) => ({ ...s, up: [...s.up, caller.caller.symbol], selected: caller.caller.symbol })),
      upTo: (index) => setState((s) => ({ ...s, up: s.up.slice(0, index + 1), selected: s.up[index] })),
    }),
    [setState],
  )

  const walkDown = (sym: string) => setState((s) => ({ ...s, mode: 'down', down: [{ sym }], selected: sym }))
  const walkUp = (sym: string) => setState((s) => ({ ...s, mode: 'up', up: [sym], selected: sym }))
  const openPath = (path: PathStep[]) =>
    setState((s) => ({ ...s, mode: 'down', down: pathToSteps(path), selected: path[path.length - 1]?.node.symbol }))

  // While the next walk loads, the current one stays on screen (and so do the boxes the user moved).
  const shownModel = useRef<CanvasModel | null>(null)
  if (model.status !== 'loading') shownModel.current = model.status === 'ready' ? model.value : null
  const ready = shownModel.current
  const selected = state.selected ?? (ready ? defaultSelection(ready) : undefined)
  const highlight = useHighlight(state, ready, selected)

  return (
    <ActionsContext.Provider value={actions}>
      <div className="app">
        <header className="toolbar">
          <div className="brand">
            <span className="brand-mark">▲</span> Pylon
          </div>
          <Search onPick={(n) => setState((s) => startAt(n.symbol, s.mode, s.view))} />
          <div className="segmented" role="tablist">
            <button
              role="tab"
              aria-selected={state.mode === 'down'}
              className={state.mode === 'down' ? 'on' : ''}
              onClick={() => setState((s) => ({ ...s, mode: 'down', down: downFor(s) }))}
            >
              Calls ↓
            </button>
            <button
              role="tab"
              aria-selected={state.mode === 'up'}
              className={state.mode === 'up' ? 'on' : ''}
              onClick={() => setState((s) => ({ ...s, mode: 'up', up: upFor(s) }))}
            >
              Callers ↑
            </button>
          </div>
          <div className="segmented" role="tablist" aria-label="Layout">
            <button
              role="tab"
              aria-selected={state.view !== 'chain'}
              className={state.view !== 'chain' ? 'on' : ''}
              title="Classes and traits drawn inside their packages"
              onClick={() => setState((s) => ({ ...s, view: undefined }), true)}
            >
              Packages
            </button>
            <button
              role="tab"
              aria-selected={state.view === 'chain'}
              className={state.view === 'chain' ? 'on' : ''}
              title="One box per step, in columns"
              onClick={() => setState((s) => ({ ...s, view: 'chain' }), true)}
            >
              Chain
            </button>
          </div>
          {state.mode === 'down' && (
            <label className="toggle">
              <input type="checkbox" checked={state.showExternal} onChange={(e) => setState((s) => ({ ...s, showExternal: e.target.checked }), true)} />
              library calls
            </label>
          )}
        </header>

        {ready && <Breadcrumb model={ready} state={state} setState={setState} />}

        <main className="main">
          <div className="canvas">
            {model.status === 'loading' && state.down.length + state.up.length > 0 && <div className="overlay muted">Loading…</div>}
            {model.status === 'error' && <div className="overlay error">{model.error}</div>}
            {model.status === 'ready' && !ready && <Welcome onPick={(n) => setState((s) => startAt(n.symbol, 'down', s.view))} />}
            {ready && (
              <ReactFlowProvider key={state.view ?? 'packages'}>
                {state.view === 'chain' ? <Canvas model={ready} selected={selected} /> : <PackageCanvas model={ready} selected={selected} />}
              </ReactFlowProvider>
            )}
          </div>
          {selected && (
            <Details sym={selected} highlight={highlight} onSelect={actions.select} onWalkDown={walkDown} onWalkUp={walkUp} onOpenPath={openPath} />
          )}
        </main>
      </div>
    </ActionsContext.Provider>
  )
}

/** Calls ↓ continues from what is in focus: keeps the walk if it already goes through it. */
function downFor(s: ViewState): DownStep[] {
  const focus = s.selected ?? s.up[s.up.length - 1]
  if (!focus || s.down.some((step) => effective(step) === focus)) return s.down
  return [{ sym: focus }]
}

/** Callers ↑ starts from what is in focus: keeps the climb if it already contains it. */
function upFor(s: ViewState): string[] {
  const focus = s.selected ?? (s.down.length ? effective(s.down[s.down.length - 1]) : undefined)
  if (!focus || s.up.includes(focus)) return s.up
  return [focus]
}

/** Converts a root-first entrypoint path into down steps, keeping the implementation chosen at each fork. */
export function pathToSteps(path: PathStep[]): DownStep[] {
  return path.map((step, i) => {
    if (i === 0 || !step.via || step.via.symbol === step.node.symbol) return { sym: step.node.symbol }
    return { sym: step.via.symbol, choice: step.node.symbol }
  })
}

function defaultSelection(model: CanvasModel): string | undefined {
  if (model.mode === 'down') return model.steps[model.steps.length - 1]?.effective.symbol
  return model.chain[0]?.node.symbol
}

/** Call sites in the selected method that lead to the next element of the walk. */
function useHighlight(state: ViewState, model: CanvasModel | null, selected: string | undefined): Site[] {
  const key = `hl|${state.mode}|${selected}|${JSON.stringify(state.mode === 'down' ? state.down : state.up)}`
  const result = useAsync<Site[]>(key, async () => {
    if (!model || !selected) return []
    if (model.mode === 'down') {
      const i = model.steps.findIndex((s) => s.effective.symbol === selected)
      const next = model.steps[i + 1]
      if (i < 0 || !next) return []
      const callees = await api.callees(selected, true)
      return callees.find((c) => c.target.symbol === next.step.sym)?.sites ?? []
    }
    const up = model as UpModel
    const i = up.chain.findIndex((d) => d.node.symbol === selected)
    return i > 0 ? (up.links[i]?.sites ?? []) : []
  })
  return result.status === 'ready' ? result.value : []
}

function Breadcrumb({ model, state, setState }: { model: CanvasModel; state: ViewState; setState: (f: (s: ViewState) => ViewState) => void }) {
  if (model.mode === 'down') {
    const m = model as DownModel
    return (
      <nav className="breadcrumb" aria-label="Call chain">
        {m.steps.map((s, i) => (
          <span key={i} className="crumb-wrap">
            {i > 0 && <span className="crumb-sep">›</span>}
            <button
              className={`crumb ${i === m.steps.length - 1 ? 'crumb-last' : ''}`}
              title={i === m.steps.length - 1 ? s.effective.display : `Go back to ${s.effective.display}`}
              onClick={() => setState((st) => ({ ...st, down: st.down.slice(0, i + 1), selected: s.effective.symbol }))}
            >
              {s.chosen ? (
                <>
                  <span className="crumb-abstract">{s.called.node.display}</span> <span className="crumb-choice">{s.chosen.node.ownerDisplay}</span>
                </>
              ) : (
                s.effective.display
              )}
            </button>
          </span>
        ))}
      </nav>
    )
  }
  const m = model as UpModel
  const chain = [...m.chain].reverse()
  return (
    <nav className="breadcrumb" aria-label="Caller chain">
      {chain.map((d, i) => (
        <span key={i} className="crumb-wrap">
          {i > 0 && <span className="crumb-sep">›</span>}
          <button className={`crumb ${i === chain.length - 1 ? 'crumb-last' : ''}`} onClick={() => setState((st) => ({ ...st, selected: d.node.symbol }))}>
            {d.node.display}
          </button>
        </span>
      ))}
      {m.chain.length > 1 && (
        <button
          className="link-btn crumb-action"
          onClick={() =>
            setState((st) => ({ ...st, mode: 'down', down: upChainToSteps(m), selected: m.chain[0].node.symbol }))
          }
        >
          open as call chain ↓
        </button>
      )}
      {state.up.length > 0 && <span className="crumb-hint">click a caller on the left to climb</span>}
    </nav>
  )
}

/** The up chain read top-down, with the implementation reached at each trait call kept as the choice. */
function upChainToSteps(m: UpModel): DownStep[] {
  const steps: DownStep[] = []
  for (let i = m.chain.length - 1; i >= 0; i--) {
    const node = m.chain[i].node
    const via = i < m.chain.length - 1 ? m.links[i + 1]?.via : undefined
    steps.push(via && via.symbol !== node.symbol ? { sym: via.symbol, choice: node.symbol } : { sym: node.symbol })
  }
  return steps
}

function Search({ onPick }: { onPick(n: SymbolNode): void }) {
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const results = useAsync(`search|${q}`, () => (q.trim().length > 1 ? api.search(q.trim()) : Promise.resolve([])))
  const items = results.status === 'ready' ? results.value : []
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [])

  const pick = (n: SymbolNode) => {
    onPick(n)
    setOpen(false)
    setQ('')
  }

  return (
    <div className="search" ref={ref}>
      <input
        placeholder="Find a method or type… (e.g. ProviderTrait.search)"
        value={q}
        onChange={(e) => {
          setQ(e.target.value)
          setOpen(true)
          setActive(0)
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') setActive((a) => Math.min(a + 1, items.length - 1))
          else if (e.key === 'ArrowUp') setActive((a) => Math.max(a - 1, 0))
          else if (e.key === 'Enter' && items[active]) pick(items[active])
          else if (e.key === 'Escape') setOpen(false)
        }}
      />
      {open && items.length > 0 && (
        <ul className="search-results">
          {items.map((n, i) => (
            <li key={n.symbol}>
              <button className={i === active ? 'active' : ''} onMouseEnter={() => setActive(i)} onClick={() => pick(n)}>
                <span className={`tag tag-${n.abstract ? 'abstract' : n.kind}`}>{n.abstract && n.kind === 'method' ? 'abstract' : n.kind}</span>
                <span className="result-name">{n.kind === 'endpoint' ? <EndpointLabel display={n.display} /> : n.display}</span>
                <span className="result-sig">{n.signature}</span>
                <span className="result-where">{n.external ? 'library' : n.service}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function Welcome({ onPick }: { onPick(n: SymbolNode): void }) {
  const endpoints = useAsync('endpoints', () => api.endpoints())
  const [filter, setFilter] = useState('')
  const list = endpoints.status === 'ready' ? endpoints.value : []
  const f = filter.trim().toLowerCase()
  const shown = list.filter((e) => !f || `${e.display} ${e.signature} ${e.service}`.toLowerCase().includes(f))
  const byService = new Map<string, SymbolNode[]>()
  shown.forEach((e) => byService.set(e.service ?? '', [...(byService.get(e.service ?? '') ?? []), e]))

  return (
    <div className="welcome">
      <h1>Where do you want to start?</h1>
      <p>
        Pick an endpoint below, or search for any method or trait above. In <b>Calls ↓</b> you follow what it calls; at each trait call,
        pick the implementation to walk into. In <b>Callers ↑</b> you climb back up to the endpoints.
      </p>
      {list.length > 0 && (
        <section className="catalogue">
          <div className="catalogue-head">
            <h2>Endpoints ({list.length})</h2>
            <input placeholder="Filter: path, verb, framework…" value={filter} onChange={(e) => setFilter(e.target.value)} />
          </div>
          {[...byService.entries()].map(([service, eps]) => (
            <div key={service} className="catalogue-service">
              <h3>{service}</h3>
              <ul>
                {eps.map((e) => (
                  <li key={e.symbol}>
                    <button className="catalogue-item" onClick={() => onPick(e)} title={`${e.file}:${e.line}`}>
                      <EndpointLabel display={e.display} />
                      <span className="catalogue-fw">{e.signature}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          {shown.length === 0 && <p className="muted">No endpoint matches “{filter}”.</p>}
        </section>
      )}
      {endpoints.status === 'ready' && list.length === 0 && (
        <p className="muted">
          No HTTP endpoints were found in the index (Play, Tapir, http4s, ZIO HTTP, Akka/Pekko HTTP are recognised). Tip:{' '}
          <code>pylon map ProviderTrait.search</code> opens this page directly on a method.
        </p>
      )}
    </div>
  )
}
