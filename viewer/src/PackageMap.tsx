// Package map: packages as big boxes, the classes and traits of the walk inside them, arrows from method to
// method. Every box can be dragged; where the user puts a box is kept across walks until "Reset layout".
import {
  applyNodeChanges,
  Background,
  Controls,
  Handle,
  MarkerType,
  Panel,
  Position,
  ReactFlow,
  useReactFlow,
  useStore,
  type Edge,
  type Node,
  type NodeChange,
  type NodeProps,
  type XYPosition,
} from '@xyflow/react'
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { ClientLabel, EndpointLabel, ImplList, useActions } from './Box'
import type { SymbolNode } from './api'
import { useAsync } from './hooks'
import type { CanvasModel } from './model'
import { buildGraph, classIdOf, layoutGraph, PACKAGE_PADDING, type ClassBox, type PackageBox, type PackageGraph, type Placed, type Row } from './packages'

type PackageNode = Node<{ pkg: PackageBox }, 'package'>
type ClassNode = Node<{ box: ClassBox }, 'klass'>
type MapNode = PackageNode | ClassNode

/** Where the user dragged boxes (packages: absolute, classes: inside their package). */
const pinned = new Map<string, XYPosition>()

const SelectedContext = createContext<string | undefined>(undefined)

function PackageView({ data }: NodeProps<PackageNode>) {
  const p = data.pkg
  return (
    <div className={`pkg ${p.external ? 'pkg-external' : ''}`}>
      <div className="pkg-head">
        <span className="pkg-name" title={p.name}>
          {p.name}
        </span>
        {p.external ? <span className="tag">library</span> : p.service && <span className="service-tag">{p.service}</span>}
      </div>
    </div>
  )
}

function ClassView({ data }: NodeProps<ClassNode>) {
  const c = data.box
  const selected = useContext(SelectedContext)
  return (
    <div className={`klass klass-${c.kind.replace(' ', '-')} ${c.external ? 'klass-external' : ''}`} style={{ width: c.width }}>
      <div className="klass-head">
        <span className="klass-name" title={c.title}>
          {c.title}
        </span>
        <span className={`tag tag-${c.kind.replace(' ', '-')}`}>{c.kind}</span>
      </div>
      {c.rows.map((r) => (
        <RowView key={r.sym} row={r} selected={selected === r.sym} />
      ))}
    </div>
  )
}

function RowLabel({ node }: { node: SymbolNode }) {
  if (node.kind === 'endpoint') return <EndpointLabel display={node.display} />
  if (node.kind === 'client') return <ClientLabel node={node} />
  if (node.kind === 'constructor') return <span className="member-name">new{node.signature.replace(/:.*$/, '')}</span>
  return (
    <>
      <span className="member-name">.{node.name}</span>
      <span className="member-sig">{node.signature}</span>
    </>
  )
}

function ForkChip({ label, impls, current, onPick }: { label: string; impls: SymbolNode[]; current?: string; onPick(sym: string): void }) {
  return (
    <span className="fork-chip row-chip nodrag" onClick={(e) => e.stopPropagation()} title={`${impls.length} implementations`}>
      ⑂ {impls.length}
      <span className="popover">
        <ImplList label={label} impls={impls} current={current} onPick={onPick} />
      </span>
    </span>
  )
}

function RowButton({ title, onClick, children }: { title: string; onClick(): void; children: ReactNode }) {
  return (
    <button
      className="row-btn nodrag"
      title={title}
      onClick={(e) => {
        e.stopPropagation()
        onClick()
      }}
    >
      {children}
    </button>
  )
}

function RowView({ row, selected }: { row: Row; selected: boolean }) {
  const actions = useActions()
  const { node, role } = row
  const select = () => actions.select(node.symbol)
  const source = <RowButton title="Show the source" onClick={select}>{'</>'}</RowButton>
  let className = 'row'
  let onClick: (() => void) | undefined = select
  let hint = ''
  let extra: ReactNode = null

  switch (role.kind) {
    case 'step':
      className += role.last ? ' row-active' : ' row-walk'
      if (!role.last)
        extra = (
          <RowButton title="Drop the steps after this one" onClick={() => actions.branchFrom(role.index)}>
            from here
          </RowButton>
        )
      break
    case 'called':
      className += ' row-walk row-abstract'
      hint = 'called here; the arrow leads to the implementation walked into'
      if (role.impls.length > 1)
        extra = <ForkChip label="Switch implementation" impls={role.impls} onPick={(sym) => actions.choose(role.index, sym)} />
      break
    case 'fork':
      className += ' row-active row-fork'
      hint = 'pick one of the implementations it points to'
      extra = <span className="row-badge">pick ⇢</span>
      break
    case 'candidate':
      className += ' row-candidate'
      onClick = () => actions.choose(role.index, node.symbol)
      hint = 'click to walk into this implementation'
      extra = source
      break
    case 'callee': {
      const c = role.callee
      className += ` row-callee ${c.fork ? 'row-abstract' : ''}`
      onClick = node.external ? undefined : () => actions.follow(c)
      hint = node.external ? 'library call' : 'click to follow this call'
      extra = (
        <>
          {c.fork && <ForkChip label="Follow into" impls={c.candidates} onPick={(sym) => actions.follow(c, sym)} />}
          {!node.external && source}
        </>
      )
      break
    }
    case 'member':
      className += ' row-callee'
      onClick = () => actions.startFrom(node.symbol)
      hint = 'click to start from this method'
      extra = source
      break
    case 'chain':
      className += role.index === 0 ? ' row-active' : ' row-walk'
      if (!role.last)
        extra = (
          <RowButton title="Drop the callers above this one" onClick={() => actions.upTo(role.index)}>
            from here
          </RowButton>
        )
      break
    case 'via':
      className += ' row-abstract'
      hint = 'the trait method the caller goes through'
      break
    case 'caller':
      className += ' row-callee'
      onClick = () => actions.climb(role.caller)
      hint = 'click to climb to this caller'
      extra = source
      break
  }
  if (node.external) className += ' row-external'
  if (selected) className += ' row-selected'

  return (
    <div className={className} onClick={onClick} title={`${node.display}${node.kind === 'method' ? node.signature : ''}${hint ? `\n${hint}` : ''}`}>
      <span className="row-label">
        <RowLabel node={node} />
      </span>
      {extra && <span className="row-extra">{extra}</span>}
      <Handle type="target" id={`in:${row.sym}`} position={Position.Left} isConnectable={false} />
      <Handle type="source" id={`out:${row.sym}`} position={Position.Right} isConnectable={false} />
    </div>
  )
}

const nodeTypes = { package: PackageView, klass: ClassView }

const EDGE_COLORS: Record<string, string> = {
  call: 'var(--ink-3)',
  impl: 'var(--impl)',
  candidate: 'var(--impl)',
  link: 'var(--accent)',
}

function toEdges(graph: PackageGraph): Edge[] {
  const cls = classIdOf(graph)
  return graph.arrows.flatMap((a) => {
    const source = cls.get(a.from)
    const target = cls.get(a.to)
    if (!source || !target) return []
    return [
      {
        id: a.id,
        source,
        sourceHandle: `out:${a.from}`,
        target,
        targetHandle: `in:${a.to}`,
        label: a.label,
        className: `pm-edge pm-edge-${a.kind} ${a.synthetic ? 'edge-dashed' : ''} ${a.muted ? 'edge-muted' : ''}`,
        markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: a.muted ? 'var(--line-2)' : EDGE_COLORS[a.kind] },
        labelBgPadding: [6, 3] as [number, number],
        labelBgBorderRadius: 4,
      },
    ]
  })
}

/** Boxes at their laid-out place, or where the user dragged them; packages grow to hold their classes. */
function toNodes(graph: PackageGraph, placed: Map<string, Placed>): MapNode[] {
  const nodes: MapNode[] = []
  const pad = PACKAGE_PADDING
  for (const p of graph.packages) {
    const at = placed.get(p.id) ?? { x: 0, y: 0, width: 300, height: 200 }
    const children = p.classes.map((c) => {
      const laid = placed.get(c.id)
      return { c, pos: pinned.get(c.id) ?? { x: laid?.x ?? pad.side, y: laid?.y ?? pad.top } }
    })
    const shiftX = Math.max(0, pad.side - Math.min(...children.map((k) => k.pos.x)))
    const shiftY = Math.max(0, pad.top - Math.min(...children.map((k) => k.pos.y)))
    children.forEach((k) => (k.pos = { x: k.pos.x + shiftX, y: k.pos.y + shiftY }))
    const pos = pinned.get(p.id) ?? { x: at.x, y: at.y }
    nodes.push({
      id: p.id,
      type: 'package',
      position: { x: pos.x - shiftX, y: pos.y - shiftY },
      data: { pkg: p },
      width: Math.max(at.width + shiftX, ...children.map((k) => k.pos.x + k.c.width + pad.side)),
      height: Math.max(at.height + shiftY, ...children.map((k) => k.pos.y + k.c.height + pad.bottom)),
      zIndex: 0,
    })
    children.forEach(({ c, pos }) =>
      nodes.push({ id: c.id, type: 'klass', parentId: p.id, position: pos, data: { box: c }, expandParent: true, zIndex: 1 }),
    )
  }
  return nodes
}

const modelIds = new WeakMap<CanvasModel, number>()
let nextModelId = 0
function modelId(model: CanvasModel): number {
  let id = modelIds.get(model)
  if (id === undefined) modelIds.set(model, (id = nextModelId++))
  return id
}

export function PackageCanvas({ model, selected }: { model: CanvasModel; selected?: string }) {
  const laid = useAsync(`pm|${modelId(model)}`, async () => {
    const graph = await buildGraph(model)
    return { graph, placed: await layoutGraph(graph) }
  })
  const [nodes, setNodes] = useState<MapNode[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [shown, setShown] = useState<{ graph: PackageGraph; placed: Map<string, Placed> } | null>(null)
  const [fit, setFit] = useState(0)
  const { fitView, getNodesBounds } = useReactFlow()
  const viewport = useStore((s) => ({ width: s.width, height: s.height }), (a, b) => a.width === b.width && a.height === b.height)

  const ready = laid.status === 'ready' ? laid.value : null
  useEffect(() => {
    if (!ready) return
    setShown(ready)
    setNodes(toNodes(ready.graph, ready.placed))
    setEdges(toEdges(ready.graph))
    setFit((f) => f + 1)
  }, [ready])

  // Frame the whole map when it stays readable, else the packages where the walk is happening.
  useEffect(() => {
    if (!shown) return
    const t = window.setTimeout(() => {
      const all = shown.graph.packages.map((p) => p.id)
      const b = getNodesBounds(all)
      const readable = Math.min(viewport.width / b.width, viewport.height / b.height) / 1.24 >= 0.6
      fitView({ padding: 0.12, duration: 250, maxZoom: 1.05, nodes: (readable ? all : shown.graph.focus).map((id) => ({ id })) })
    }, 40)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fit])

  const onNodesChange = useCallback((changes: NodeChange<MapNode>[]) => {
    changes.forEach((c) => c.type === 'position' && c.position && pinned.set(c.id, c.position))
    setNodes((ns) => applyNodeChanges(changes, ns))
  }, [])

  const resetLayout = () => {
    pinned.clear()
    if (shown) setNodes(toNodes(shown.graph, shown.placed))
    setFit((f) => f + 1)
  }

  return (
    <SelectedContext.Provider value={selected}>
      <ReactFlow
        className="package-map"
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        nodesDraggable
        nodesConnectable={false}
        elementsSelectable={false}
        minZoom={0.1}
        maxZoom={1.6}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={24} size={1} />
        <Controls showInteractive={false} />
        {shown?.graph.note && (
          <Panel position="top-left" className="pm-note">
            {shown.graph.note}
          </Panel>
        )}
        <Panel position="top-right" className="pm-tools">
          <button onClick={resetLayout} title="Put every box back where the layout placed it">
            Reset layout
          </button>
        </Panel>
        <Panel position="bottom-right" className="pm-legend">
          <span>
            <i className="lg lg-call" /> calls
          </span>
          <span>
            <i className="lg lg-impl" /> implemented by
          </span>
          <span>
            <i className="lg lg-link" /> another service
          </span>
          <span className="muted">drag any box to rearrange</span>
        </Panel>
      </ReactFlow>
    </SelectedContext.Provider>
  )
}
