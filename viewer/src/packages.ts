// Package map: the walk drawn as packages (big boxes) holding the classes and traits it goes through,
// one row per method, with arrows from row to row. Positions come from ELK (layered, nested).
import type { ELK, ElkExtendedEdge, ElkNode } from 'elkjs/lib/elk-api'
import type { Callee, Caller, Link, SymbolNode } from './api'
import { api } from './api'
import { isType, type CanvasModel } from './model'

/** What a row stands for in the walk, which decides what clicking it does. */
export type RowRole =
  /** Down: the method walked through at step `index`. */
  | { kind: 'step'; index: number; last: boolean }
  /** Down: the abstract method called at step `index`; an implementation of it is walked through. */
  | { kind: 'called'; index: number; impls: SymbolNode[] }
  /** Down: the abstract method called at step `index`, waiting for an implementation to be picked. */
  | { kind: 'fork'; index: number }
  /** Down: an implementation offered for the fork at step `index`. */
  | { kind: 'candidate'; index: number }
  /** Down: a call made by the last step. */
  | { kind: 'callee'; callee: Callee }
  /** Down: a method of the queried type. */
  | { kind: 'member' }
  /** Up: `index` 0 is the method investigated; each next one calls the previous. */
  | { kind: 'chain'; index: number; last: boolean }
  /** Up: the trait method a caller goes through. */
  | { kind: 'via' }
  /** Up: a caller of the top of the chain. */
  | { kind: 'caller'; caller: Caller }

export interface Row {
  sym: string
  node: SymbolNode
  role: RowRole
}

export type ClassKind = 'trait' | 'class' | 'object' | 'top level' | 'endpoints' | 'client'

export interface ClassBox {
  id: string
  title: string
  kind: ClassKind
  external: boolean
  rows: Row[]
  width: number
  height: number
}

export interface PackageBox {
  id: string
  name: string
  service: string | null
  external: boolean
  classes: ClassBox[]
}

export interface Arrow {
  id: string
  from: string
  to: string
  /** `call`: a call; `impl`: implemented by; `candidate`: an implementation still to pick; `link`: a call to another service. */
  kind: 'call' | 'impl' | 'candidate' | 'link'
  label?: string
  synthetic?: boolean
  muted?: boolean
}

export interface PackageGraph {
  packages: PackageBox[]
  arrows: Arrow[]
  /** Packages where the walk is happening: the camera frames them. */
  focus: string[]
  note?: string
}

// Box geometry (px). The rows have fixed heights so that ELK knows where the arrows attach.
export const HEADER_H = 34
export const ROW_H = 28
const FOOT_H = 6
const PKG_PAD = { top: 44, side: 18, bottom: 18 }

/** `com/acme/search` for `com/acme/search/ProviderA#search().`; '' for the empty package. */
export function packageOf(sym: string): string {
  let tick = false
  let last = -1
  for (let i = 0; i < sym.length; i++) {
    const c = sym[i]
    if (c === '`') tick = !tick
    else if (!tick) {
      if (c === '/') last = i
      else if (c === '#' || c === '.' || c === '(') break
    }
  }
  return last < 0 ? '' : sym.slice(0, last)
}

function packageName(pkg: string): string {
  const name = pkg.replace(/`/g, '').replace(/\//g, '.')
  return name === '' || name === '_empty_' ? '(default package)' : name
}

/** Where a node lives: its package box, and its class box inside it. */
function placeOf(n: SymbolNode): { pkg: string; pkgName: string; cls: string; title: string } {
  const svc = n.service ?? 'library'
  if (n.kind === 'endpoint') {
    return { pkg: `${svc}|:endpoints`, pkgName: 'endpoints', cls: `${svc}|:endpoints|${n.signature}`, title: n.signature }
  }
  if (n.kind === 'client') {
    return { pkg: `${svc}|:clients`, pkgName: 'calls to other services', cls: `${svc}|:clients|${n.signature}`, title: n.signature }
  }
  const owner = isType(n) ? n.symbol : n.owner
  const pkg = packageOf(owner || n.symbol)
  const title = isTopLevel(owner) ? `${n.file?.split('/').pop() ?? 'file'}` : isType(n) ? n.display : n.ownerDisplay || n.display
  return { pkg: `${svc}|${pkg}`, pkgName: packageName(pkg), cls: `${svc}|${owner || n.symbol}`, title }
}

/** Scala 3 top-level definitions live in a synthetic `File$package` object. */
function isTopLevel(owner: string): boolean {
  return owner.endsWith('$package.')
}

function classKindOf(n: SymbolNode, ownerKind: SymbolNode['kind'] | undefined): ClassKind {
  if (n.kind === 'endpoint') return 'endpoints'
  if (n.kind === 'client') return 'client'
  if (isTopLevel(n.owner)) return 'top level'
  const k = isType(n) ? n.kind : ownerKind
  if (k === 'trait' || k === 'class' || k === 'object') return k
  return n.owner.endsWith('#') ? 'class' : 'object'
}

/** `HTTP`, `gRPC` or `Kafka` from a client's display (`→ HTTP GET /items/{}`). */
function protocol(clientDisplay: string): string {
  return clientDisplay.replace(/^→\s*/, '').split(' ')[0]
}

/** `HTTP · 0.9` for an arrow into another service (exact matches show no number). */
function linkLabel(clientDisplay: string, link: Link): string {
  return link.confidence >= 1 ? protocol(clientDisplay) : `${protocol(clientDisplay)} · ${link.confidence.toFixed(1)}`
}

/** Approximate rendered width of a row, to size the class boxes. */
function rowChars(n: SymbolNode): number {
  if (n.kind === 'endpoint' || n.kind === 'client') return n.display.length + 4
  if (n.kind === 'constructor') return n.signature.replace(/:.*$/, '').length + 4
  return n.name.length + 1 + n.signature.length
}

/** The rows and arrows of a walk, before they are sorted into boxes. */
function collect(model: CanvasModel): { rows: Row[]; arrows: Arrow[]; focus: string[]; note?: string; types: SymbolNode[] } {
  const rows = new Map<string, Row>()
  const arrows = new Map<string, Arrow>()
  const focus: string[] = []
  const types: SymbolNode[] = []
  let note: string | undefined
  const add = (node: SymbolNode, role: RowRole) => {
    if (!rows.has(node.symbol)) rows.set(node.symbol, { sym: node.symbol, node, role })
  }
  const arrow = (a: Omit<Arrow, 'id'>) => {
    const id = `${a.kind}:${a.from}->${a.to}`
    if (!arrows.has(id)) arrows.set(id, { ...a, id })
  }

  if (model.mode === 'down') {
    const n = model.steps.length
    model.steps.forEach((s, i) => {
      const last = i === n - 1
      const called = s.called.node
      if (isType(called)) {
        types.push(called)
        s.called.members.filter((m) => m.kind !== 'val').forEach((m) => add(m, { kind: 'member' }))
        if (last) focus.push(called.symbol)
        return
      }
      if (s.needsChoice) {
        add(called, { kind: 'fork', index: i })
        s.called.implementations.forEach((impl) => {
          add(impl, { kind: 'candidate', index: i })
          arrow({ from: called.symbol, to: impl.symbol, kind: 'candidate' })
          if (last) focus.push(impl.symbol)
        })
      } else if (s.effective.symbol !== called.symbol) {
        add(called, { kind: 'called', index: i, impls: s.called.implementations })
        add(s.effective, { kind: 'step', index: i, last })
        arrow({ from: called.symbol, to: s.effective.symbol, kind: 'impl' })
      } else {
        add(called, { kind: 'step', index: i, last })
      }
      if (last) focus.push(called.symbol, s.effective.symbol)
      if (i > 0) {
        const prev = model.steps[i - 1].effective
        if (prev.kind === 'client') arrow({ from: prev.symbol, to: called.symbol, kind: 'link', label: protocol(prev.display) })
        else arrow({ from: prev.symbol, to: called.symbol, kind: 'call' })
      }
    })
    const lastStep = model.steps[n - 1]
    if (lastStep) {
      const from = lastStep.effective.symbol
      model.callees.forEach((c) => {
        add(c.target, { kind: 'callee', callee: c })
        focus.push(c.target.symbol)
        if (c.link) arrow({ from, to: c.target.symbol, kind: 'link', label: linkLabel(lastStep.effective.display, c.link), muted: c.link.confidence < 0.85 })
        else arrow({ from, to: c.target.symbol, kind: 'call', synthetic: c.synthetic, muted: c.target.external })
      })
      if (model.callees.length === 0 && !lastStep.needsChoice && !isType(lastStep.effective)) note = 'No calls into indexed code.'
    }
  } else {
    const n = model.chain.length
    const connect = (caller: SymbolNode, target: SymbolNode, link: Caller | null) => {
      if (link?.link) arrow({ from: caller.symbol, to: target.symbol, kind: 'link', label: linkLabel(caller.display, link.link), muted: link.link.confidence < 0.85 })
      else if (link && link.via.symbol !== target.symbol) {
        add(link.via, { kind: 'via' })
        arrow({ from: caller.symbol, to: link.via.symbol, kind: 'call' })
        arrow({ from: link.via.symbol, to: target.symbol, kind: 'impl' })
      } else arrow({ from: caller.symbol, to: target.symbol, kind: 'call' })
    }
    model.chain.forEach((d, i) => add(d.node, { kind: 'chain', index: i, last: i === n - 1 }))
    model.chain.forEach((d, i) => i > 0 && connect(d.node, model.chain[i - 1].node, model.links[i]))
    const top = model.chain[n - 1]
    if (top) {
      focus.push(top.node.symbol)
      model.callers.forEach((c) => {
        add(c.caller, { kind: 'caller', caller: c })
        focus.push(c.caller.symbol)
        connect(c.caller, top.node, c)
      })
      if (model.callers.length === 0) {
        note = top.node.kind === 'endpoint' ? `Entrypoint: HTTP ${top.node.display} (${top.node.signature})` : 'Nobody calls this: it is an entrypoint.'
      }
    }
  }
  return { rows: [...rows.values()], arrows: [...arrows.values()], focus, note, types }
}

/** Sorts the rows of a walk into class boxes and package boxes. */
export async function buildGraph(model: CanvasModel): Promise<PackageGraph> {
  const { rows, arrows, focus, note, types } = collect(model)

  // Trait, class or object: asked to the graph for the owners (library owners may be unknown).
  const owners = [...new Set(rows.filter((r) => !isType(r.node) && !r.node.external && r.node.owner && !isTopLevel(r.node.owner)).map((r) => r.node.owner))]
  const ownerKinds = new Map<string, SymbolNode['kind']>()
  await Promise.all(owners.map((o) => api.node(o).then((d) => ownerKinds.set(o, d.node.kind), () => undefined)))

  const packages = new Map<string, PackageBox>()
  const classes = new Map<string, ClassBox>()
  const place = (n: SymbolNode): ClassBox => {
    const p = placeOf(n)
    let pkg = packages.get(p.pkg)
    if (!pkg) {
      pkg = { id: p.pkg, name: p.pkgName, service: n.service, external: n.external, classes: [] }
      packages.set(p.pkg, pkg)
    }
    let cls = classes.get(p.cls)
    if (!cls) {
      cls = { id: p.cls, title: p.title, kind: classKindOf(n, ownerKinds.get(n.owner)), external: n.external, rows: [], width: 0, height: 0 }
      classes.set(p.cls, cls)
      pkg.classes.push(cls)
    }
    return cls
  }
  types.forEach(place)
  rows.forEach((r) => place(r.node).rows.push(r))

  for (const cls of classes.values()) {
    const chars = Math.max(cls.title.length * 1.2 + 10, ...cls.rows.map((r) => rowChars(r.node)))
    cls.width = Math.round(Math.min(380, Math.max(200, chars * 7.3 + 34)))
    cls.height = HEADER_H + cls.rows.length * ROW_H + FOOT_H
  }
  const bySym = new Map<string, SymbolNode>([...types, ...rows.map((r) => r.node)].map((n) => [n.symbol, n]))
  const focusPackages = focus.flatMap((s) => {
    const n = bySym.get(s)
    return n ? [placeOf(n).pkg] : []
  })
  return { packages: [...packages.values()], arrows, focus: [...new Set(focusPackages)], note }
}

/** Which class box holds a row. */
export function classIdOf(graph: PackageGraph): Map<string, string> {
  const m = new Map<string, string>()
  graph.packages.forEach((p) => p.classes.forEach((c) => c.rows.forEach((r) => m.set(r.sym, c.id))))
  return m
}

export interface Placed {
  x: number
  y: number
  width: number
  height: number
}

// ELK is large: it is loaded with the first package map.
let elk: Promise<ELK> | undefined
const loadElk = () => (elk ??= import('elkjs/lib/elk.bundled.js').then((m) => new m.default()))

/** Positions of the package boxes (absolute) and of the class boxes (inside their package). */
export async function layoutGraph(graph: PackageGraph): Promise<Map<string, Placed>> {
  const rowY = (i: number) => HEADER_H + i * ROW_H + ROW_H / 2
  const root: ElkNode = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
      'elk.layered.spacing.nodeNodeBetweenLayers': '80',
      'elk.spacing.nodeNode': '36',
      'elk.layered.spacing.edgeNodeBetweenLayers': '24',
      'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
    },
    children: graph.packages.map((p) => ({
      id: p.id,
      layoutOptions: {
        'elk.padding': `[top=${PKG_PAD.top},left=${PKG_PAD.side},bottom=${PKG_PAD.bottom},right=${PKG_PAD.side}]`,
        'elk.spacing.nodeNode': '22',
        'elk.layered.spacing.nodeNodeBetweenLayers': '60',
      },
      children: p.classes.map((c) => ({
        id: c.id,
        width: c.width,
        height: c.height,
        layoutOptions: { 'elk.portConstraints': 'FIXED_POS' },
        ports: c.rows.flatMap((r, i) => [
          { id: `in:${r.sym}`, x: 0, y: rowY(i), width: 1, height: 1, layoutOptions: { 'elk.port.side': 'WEST' } },
          { id: `out:${r.sym}`, x: c.width - 1, y: rowY(i), width: 1, height: 1, layoutOptions: { 'elk.port.side': 'EAST' } },
        ]),
      })),
    })),
    edges: graph.arrows.map((a): ElkExtendedEdge => ({ id: a.id, sources: [`out:${a.from}`], targets: [`in:${a.to}`] })),
  }
  const out = await (await loadElk()).layout(root)
  const placed = new Map<string, Placed>()
  out.children?.forEach((p) => {
    placed.set(p.id, { x: p.x ?? 0, y: p.y ?? 0, width: p.width ?? 0, height: p.height ?? 0 })
    p.children?.forEach((c) => placed.set(c.id, { x: c.x ?? 0, y: c.y ?? 0, width: c.width ?? 0, height: c.height ?? 0 }))
  })
  return placed
}

export const PACKAGE_PADDING = PKG_PAD
