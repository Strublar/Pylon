import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { createContext, useContext } from 'react'
import type { Callee, Caller, NodeDetails, SymbolNode } from './api'
import type { StepModel } from './model'

/** What a box can ask the app to do. */
export interface Actions {
  select(sym: string): void
  /** Down mode: pick (or switch) the implementation of step `index`. */
  choose(index: number, impl: string): void
  /** Down mode: walk into a callee, optionally straight into one of its implementations. */
  follow(callee: Callee, impl?: string): void
  /** Down mode: drop the steps after `index`. */
  branchFrom(index: number): void
  /** Down mode: a type was queried; start from one of its methods. */
  startFrom(sym: string): void
  /** Up mode: add a caller to the chain. */
  climb(caller: Caller): void
  /** Up mode: drop the chain after `index`. */
  upTo(index: number): void
}

export const ActionsContext = createContext<Actions | null>(null)

export const useActions = (): Actions => {
  const a = useContext(ActionsContext)
  if (!a) throw new Error('ActionsContext missing')
  return a
}

export type BoxData =
  | { variant: 'step'; model: StepModel; isLast: boolean; selected: boolean }
  | { variant: 'callee'; callee: Callee; selected: boolean }
  | { variant: 'chain'; details: NodeDetails; index: number; isLast: boolean; selected: boolean }
  | { variant: 'caller'; caller: Caller; selected: boolean }
  | { variant: 'note'; text: string }

export type BoxNode = Node<BoxData, 'box'>

export function where(n: SymbolNode): string {
  if (n.external) return 'library'
  const file = n.file ? n.file.split('/').pop() : ''
  const loc = `${file}${n.line ? `:${n.line}` : ''}`
  return n.kind === 'endpoint' || n.kind === 'client' ? `${n.signature} · ${loc}` : loc
}

/** `HTTP`, `gRPC` or `Kafka` from a client's display (`→ HTTP GET /items/{}`). */
export function protocolOf(n: SymbolNode): string {
  return n.display.replace(/^→\s*/, '').split(' ')[0] ?? ''
}

/** `→ HTTP GET /items/{}` as a protocol badge and the call. */
export function ClientLabel({ node }: { node: SymbolNode }) {
  const rest = node.display.replace(/^→\s*/, '')
  const protocol = rest.split(' ')[0]
  return (
    <span className="endpoint">
      <span className={`proto proto-${protocol.toLowerCase()}`}>→ {protocol}</span>
      <span className="endpoint-path">{rest.slice(protocol.length + 1)}</span>
    </span>
  )
}

/** `GET /api/items/{id}` as a verb badge and a path. */
export function EndpointLabel({ display }: { display: string }) {
  const space = display.indexOf(' ')
  const verb = space > 0 ? display.slice(0, space) : 'ANY'
  const path = space > 0 ? display.slice(space + 1) : display
  return (
    <span className="endpoint">
      <span className={`verb verb-${verb.toLowerCase()}`}>{verb}</span>
      <span className="endpoint-path">{path}</span>
    </span>
  )
}

function kindLabel(n: SymbolNode): string {
  if (n.kind === 'constructor') return 'constructor'
  if (n.kind === 'method') return n.abstract ? 'abstract' : 'method'
  return n.kind
}

/** Title line: the type, or the package for top-level functions. */
function Title({ node, accent }: { node: SymbolNode; accent?: string }) {
  if (node.kind === 'endpoint' || node.kind === 'client')
    return (
      <div className="box-title">
        {node.kind === 'endpoint' ? <EndpointLabel display={node.display} /> : <ClientLabel node={node} />}
        {node.service && <span className="service-tag" title="service">{node.service}</span>}
      </div>
    )
  const owner = node.kind === 'constructor' ? node.ownerDisplay : node.ownerDisplay || node.display
  return (
    <div className="box-title">
      <span className="box-owner" title={node.display}>
        {owner}
      </span>
      <span className={`tag tag-${node.abstract ? 'abstract' : node.kind}`}>{accent ?? kindLabel(node)}</span>
    </div>
  )
}

function Member({ node }: { node: SymbolNode }) {
  if (node.kind === 'constructor') return <div className="box-member">new{node.signature.replace(/:.*$/, '')}</div>
  if (node.kind === 'trait' || node.kind === 'class' || node.kind === 'object' || node.kind === 'endpoint' || node.kind === 'client') return null
  return (
    <div className="box-member" title={`${node.name}${node.signature}`}>
      <span className="member-name">.{node.name}</span>
      <span className="member-sig">{node.signature}</span>
    </div>
  )
}

export function ImplList(props: { impls: SymbolNode[]; current?: string; onPick: (sym: string) => void; label: string }) {
  const services = new Set(props.impls.map((i) => i.service))
  return (
    <div className="impls">
      <div className="impls-label">{props.label}</div>
      {props.impls.map((impl) => (
        <button
          key={impl.symbol}
          className={`impl nodrag ${impl.symbol === props.current ? 'impl-current' : ''}`}
          onClick={(e) => {
            e.stopPropagation()
            props.onPick(impl.symbol)
          }}
          title={impl.display}
        >
          <span className="impl-arrow">▸</span>
          <span className="impl-name">{impl.ownerDisplay || impl.display}</span>
          {services.size > 1 && impl.service && <span className="impl-service">{impl.service}</span>}
        </button>
      ))}
    </div>
  )
}

function StepBox({ data }: { data: Extract<BoxData, { variant: 'step' }> }) {
  const actions = useActions()
  const { model, isLast, selected } = data
  const called = model.called.node
  const chosen = model.chosen?.node
  const impls = model.called.implementations
  const typeRoot = called.kind === 'trait' || called.kind === 'class' || called.kind === 'object'

  return (
    <div
      className={`box box-step ${isLast ? 'box-active' : ''} ${selected ? 'box-selected' : ''} ${model.needsChoice ? 'box-fork' : ''}`}
      onClick={() => actions.select(model.effective.symbol)}
    >
      {chosen ? (
        <>
          <Title node={chosen} accent="implementation" />
          <Member node={chosen} />
          <div className="box-via">
            implements <b>{called.display}</b>
          </div>
        </>
      ) : (
        <>
          <Title node={called} />
          <Member node={called} />
        </>
      )}

      {typeRoot && model.called.members.length > 0 && (
        <ImplList
          label="Start from a method"
          impls={model.called.members.filter((m) => m.kind !== 'val')}
          onPick={(sym) => actions.startFrom(sym)}
        />
      )}

      {model.needsChoice && (
        <ImplList label={`${impls.length} implementations — pick one`} impls={impls} onPick={(sym) => actions.choose(model.index, sym)} />
      )}

      {!model.needsChoice && impls.length > 1 && (
        <div className="fork-chip nodrag" onClick={(e) => e.stopPropagation()}>
          ⑂ {impls.length} implementations
          <div className="popover">
            <ImplList
              label="Switch implementation"
              impls={impls}
              current={model.effective.symbol}
              onPick={(sym) => actions.choose(model.index, sym)}
            />
          </div>
        </div>
      )}

      <div className="box-foot">
        <span className="box-where">{where(model.effective)}</span>
        {!isLast && (
          <button
            className="link-btn nodrag"
            title="Drop the steps after this one"
            onClick={(e) => {
              e.stopPropagation()
              actions.branchFrom(model.index)
            }}
          >
            explore from here
          </button>
        )}
      </div>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  )
}

function CalleeBox({ data }: { data: Extract<BoxData, { variant: 'callee' }> }) {
  const actions = useActions()
  const { callee, selected } = data
  const t = callee.target
  const clickable = !t.external
  return (
    <div
      className={`box box-callee ${t.external ? 'box-external' : ''} ${callee.fork ? 'box-fork' : ''} ${selected ? 'box-selected' : ''}`}
      onClick={() => clickable && actions.follow(callee)}
      title={clickable ? `Follow ${t.display}` : t.display}
    >
      <Title node={t} accent={callee.synthetic ? 'implicit' : undefined} />
      <Member node={t} />
      {callee.fork && (
        <div className="fork-chip nodrag" onClick={(e) => e.stopPropagation()}>
          ⑂ {callee.candidates.length} implementations
          <div className="popover">
            <ImplList label="Follow into" impls={callee.candidates} onPick={(sym) => actions.follow(callee, sym)} />
          </div>
        </div>
      )}
      {callee.sole && <div className="box-via">only implementation: {callee.sole.ownerDisplay}</div>}
      <div className="box-foot">
        <span className="box-where">{where(t)}</span>
        {clickable && (
          <button
            className="link-btn nodrag"
            onClick={(e) => {
              e.stopPropagation()
              actions.select(t.symbol)
            }}
          >
            source
          </button>
        )}
      </div>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  )
}

function ChainBox({ data }: { data: Extract<BoxData, { variant: 'chain' }> }) {
  const actions = useActions()
  const n = data.details.node
  return (
    <div
      className={`box box-step ${data.index === 0 ? 'box-active' : ''} ${data.selected ? 'box-selected' : ''}`}
      onClick={() => actions.select(n.symbol)}
    >
      <Title node={n} />
      <Member node={n} />
      {data.details.overrides.length > 0 && (
        <div className="box-via">
          implements <b>{data.details.overrides.map((o) => o.display).join(', ')}</b>
        </div>
      )}
      <div className="box-foot">
        <span className="box-where">{where(n)}</span>
        {!data.isLast && (
          <button
            className="link-btn nodrag"
            onClick={(e) => {
              e.stopPropagation()
              actions.upTo(data.index)
            }}
          >
            climb from here
          </button>
        )}
      </div>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  )
}

function CallerBox({ data }: { data: Extract<BoxData, { variant: 'caller' }> }) {
  const actions = useActions()
  const c = data.caller.caller
  return (
    <div className={`box box-callee ${data.selected ? 'box-selected' : ''}`} onClick={() => actions.climb(data.caller)} title={`Climb to ${c.display}`}>
      <Title node={c} />
      <Member node={c} />
      <div className="box-foot">
        <span className="box-where">{where(c)}</span>
        <button
          className="link-btn nodrag"
          onClick={(e) => {
            e.stopPropagation()
            actions.select(c.symbol)
          }}
        >
          source
        </button>
      </div>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  )
}

export function Box({ data }: NodeProps<BoxNode>) {
  switch (data.variant) {
    case 'step':
      return <StepBox data={data} />
    case 'callee':
      return <CalleeBox data={data} />
    case 'chain':
      return <ChainBox data={data} />
    case 'caller':
      return <CallerBox data={data} />
    case 'note':
      return (
        <div className="box box-note">
          {data.text}
          <Handle type="target" position={Position.Left} isConnectable={false} />
          <Handle type="source" position={Position.Right} isConnectable={false} />
        </div>
      )
  }
}
