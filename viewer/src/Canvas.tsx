import { Background, Controls, MarkerType, ReactFlow, useReactFlow, type Edge } from '@xyflow/react'
import { useEffect, useMemo } from 'react'
import { Box, protocolOf, type BoxNode } from './Box'
import type { Link, SymbolNode } from './api'
import type { CanvasModel } from './model'

const COL = 480 // horizontal distance between columns (box is 300px; the rest is room for edge labels)
const ROW = 118 // vertical distance between stacked boxes

const nodeTypes = { box: Box }

function edge(
  id: string,
  source: string,
  target: string,
  label: string,
  opts: { dashed?: boolean; muted?: boolean; link?: boolean } = {},
): Edge {
  return {
    id,
    source,
    target,
    label,
    type: 'default',
    className: `${opts.dashed ? 'edge-dashed' : ''} ${opts.muted ? 'edge-muted' : ''} ${opts.link ? 'edge-link' : ''}`,
    markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 },
    labelBgPadding: [6, 3],
    labelBgBorderRadius: 4,
  }
}

/** Positions boxes stacked vertically around y = 0. */
function stack(count: number, k: number): number {
  return (k - (count - 1) / 2) * ROW
}

export function layout(model: CanvasModel, selected: string | undefined): { nodes: BoxNode[]; edges: Edge[] } {
  const nodes: BoxNode[] = []
  const edges: Edge[] = []

  if (model.mode === 'down') {
    model.steps.forEach((s, i) => {
      const id = `step-${i}`
      nodes.push({
        id,
        type: 'box',
        position: { x: i * COL, y: 0 },
        data: { variant: 'step', model: s, isLast: i === model.steps.length - 1, selected: selected === s.effective.symbol },
      })
      if (i > 0) {
        const prev = model.steps[i - 1].effective
        if (prev.kind === 'client') edges.push(edge(`e-step-${i}`, `step-${i - 1}`, id, protocolOf(prev), { link: true }))
        else edges.push(edge(`e-step-${i}`, `step-${i - 1}`, id, edgeName(s.called.node)))
      }
    })
    const last = model.steps[model.steps.length - 1]
    if (!last) return { nodes, edges }
    const col = model.steps.length * COL
    const from = `step-${model.steps.length - 1}`
    if (model.callees.length === 0 && !last.needsChoice && last.called.members.length === 0) {
      nodes.push({ id: 'note', type: 'box', position: { x: col, y: 0 }, data: { variant: 'note', text: 'No calls into indexed code.' } })
    }
    model.callees.forEach((c, k) => {
      const id = `callee-${k}`
      nodes.push({
        id,
        type: 'box',
        position: { x: col, y: stack(model.callees.length, k) },
        data: { variant: 'callee', callee: c, selected: selected === c.target.symbol },
      })
      const label = edgeName(c.target)
      if (c.link) edges.push(edge(`e-${id}`, from, id, linkLabel(last.effective.display, c.link), { link: true, muted: c.link.confidence < 0.85 }))
      else edges.push(edge(`e-${id}`, from, id, c.fork ? `${label} ⑂` : label, { dashed: c.synthetic, muted: c.target.external }))
    })
  } else {
    const n = model.chain.length
    // Callers on the far left, the investigated method on the right.
    model.chain.forEach((d, i) => {
      nodes.push({
        id: `chain-${i}`,
        type: 'box',
        position: { x: (n - i) * COL, y: 0 },
        data: { variant: 'chain', details: d, index: i, isLast: i === n - 1, selected: selected === d.node.symbol },
      })
      const link = model.links[i]
      if (i > 0) {
        if (link?.link) edges.push(edge(`e-chain-${i}`, `chain-${i}`, `chain-${i - 1}`, linkLabel(link.caller.display, link.link), { link: true }))
        else edges.push(edge(`e-chain-${i}`, `chain-${i}`, `chain-${i - 1}`, link ? viaLabel(link.via.name, link.via.display, model.chain[i - 1]) : ''))
      }
    })
    if (model.callers.length === 0) {
      const root = model.chain[n - 1].node
      const text = root.kind === 'endpoint' ? `Entrypoint: HTTP ${root.display} (${root.signature})` : 'Nobody calls this: it is an entrypoint.'
      nodes.push({ id: 'note', type: 'box', position: { x: 0, y: 0 }, data: { variant: 'note', text } })
    }
    model.callers.forEach((c, k) => {
      const id = `caller-${k}`
      nodes.push({
        id,
        type: 'box',
        position: { x: 0, y: stack(model.callers.length, k) },
        data: { variant: 'caller', caller: c, selected: selected === c.caller.symbol },
      })
      if (c.link) edges.push(edge(`e-${id}`, id, `chain-${n - 1}`, linkLabel(c.caller.display, c.link), { link: true, muted: c.link.confidence < 0.85 }))
      else edges.push(edge(`e-${id}`, id, `chain-${n - 1}`, viaLabel(c.via.name, c.via.display, model.chain[n - 1])))
    })
  }
  return { nodes, edges }
}

/** Label of a call arrow: the method name; client boxes already say what they call. */
function edgeName(n: SymbolNode): string {
  if (n.kind === 'client') return 'calls'
  return n.name === '<init>' ? 'new' : n.name
}

/** `HTTP · 0.9` for a link from a client to an endpoint (exact matches show no number). */
function linkLabel(clientDisplay: string, link: Link): string {
  const protocol = clientDisplay.replace(/^→\s*/, '').split(' ')[0]
  return link.confidence >= 1 ? protocol : `${protocol} · ${link.confidence.toFixed(1)}`
}

/** `search`, or `via ProviderTrait` when the call goes through the method it overrides. */
function viaLabel(name: string, viaDisplay: string, target: { node: SymbolNode }): string {
  if (viaDisplay === target.node.display) return edgeName(target.node)
  const owner = name === target.node.name && viaDisplay.endsWith(`.${name}`) ? viaDisplay.slice(0, -(name.length + 1)) : viaDisplay
  return `via ${owner}`
}

/** Columns kept in view: on long chains the camera follows the end of the walk; pan to see the rest. */
const VISIBLE_COLUMNS = 3

function FitOnChange({ signature, focus }: { signature: string; focus: string[] }) {
  const { fitView } = useReactFlow()
  useEffect(() => {
    const t = window.setTimeout(
      () => fitView({ padding: 0.18, duration: 250, maxZoom: 1.05, nodes: focus.map((id) => ({ id })) }),
      30,
    )
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature, fitView])
  return null
}

/** Nodes in the columns nearest to where the walk is happening (rightmost in down mode, leftmost in up mode). */
function focusIds(nodes: BoxNode[]): string[] {
  const xs = [...new Set(nodes.map((n) => n.position.x))].sort((a, b) => a - b)
  const down = nodes.some((n) => n.data.variant === 'step')
  const keep = new Set(down ? xs.slice(-VISIBLE_COLUMNS) : xs.slice(0, VISIBLE_COLUMNS))
  return nodes.filter((n) => keep.has(n.position.x)).map((n) => n.id)
}

export function Canvas({ model, selected }: { model: CanvasModel; selected?: string }) {
  const { nodes, edges } = useMemo(() => layout(model, selected), [model, selected])
  // Re-fit when the walk changes, not when only the selection does.
  const signature =
    model.mode === 'down'
      ? `down|${model.steps.map((s) => s.effective.symbol).join('|')}|${model.callees.length}`
      : `up|${model.chain.map((d) => d.node.symbol).join('|')}|${model.callers.length}`
  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      minZoom={0.15}
      maxZoom={1.6}
      proOptions={{ hideAttribution: true }}
      fitView
    >
      <Background gap={24} size={1} />
      <Controls showInteractive={false} />
      <FitOnChange signature={signature} focus={focusIds(nodes)} />
    </ReactFlow>
  )
}
