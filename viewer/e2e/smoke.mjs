// End-to-end check of the viewer against a running `pylon map/serve` on the fixtures.
//   bin/pylon index --service search=fixtures/search-3 --service legacy=fixtures/search-213
//   bin/pylon serve --port 7777 &
//   npm --prefix viewer run e2e            (PYLON_URL and SHOTS_DIR are optional)
import { chromium } from 'playwright'
import { existsSync, mkdirSync } from 'node:fs'

const base = process.env.PYLON_URL ?? 'http://127.0.0.1:7777'
const shots = process.env.SHOTS_DIR ?? new URL('./screenshots/', import.meta.url).pathname
mkdirSync(shots, { recursive: true })

const executablePath = ['/opt/pw-browsers/chromium'].find((p) => existsSync(p))
const browser = await chromium.launch(executablePath ? { executablePath } : {})
const page = await browser.newPage({ viewport: { width: 1500, height: 860 } })
const errors = []
page.on('pageerror', (e) => errors.push(e.message))

const step = (i) => page.locator('.react-flow__node').filter({ has: page.locator('.box-step') }).nth(i)
const callee = (owner) => page.locator('.box-callee').filter({ has: page.locator('.box-owner', { hasText: new RegExp(`^${owner}$`) }) })
const settle = () => page.waitForTimeout(450)
const expectText = async (locator, text, what) => {
  await locator.filter({ hasText: text }).first().waitFor({ timeout: 5000 }).catch(() => {
    throw new Error(`expected ${what} to contain "${text}"`)
  })
}
let n = 0
const shot = async (name) => {
  await settle()
  await page.screenshot({ path: `${shots}/${String(++n).padStart(2, '0')}-${name}.png` })
}

// 1. Start on the trait method: it is a fork, so the box asks for an implementation.
await page.goto(`${base}/?sym=${encodeURIComponent('com/acme/search/ProviderTrait#search().')}&mode=down&view=chain`)
await expectText(page.locator('.box-step'), 'ProviderTrait', 'root box')
await expectText(page.locator('.box-step .impls'), 'ProviderA', 'implementation list')
await expectText(page.locator('.box-step .impls'), 'ProviderB', 'implementation list')
await shot('trait-fork')

// 2. Pick ProviderA: the box becomes ProviderA and its calls fan out with labelled arrows.
await page.locator('.box-step .impl', { hasText: 'ProviderA' }).click()
await expectText(page.locator('.box-step'), 'implements', 'chosen step')
await callee('SearchServiceA').waitFor()
await callee('Ranker').waitFor()
await expectText(page.locator('.react-flow__edge-text'), 'search ⑂', 'edge label')
await shot('providerA-calls')

// 3. Hover the SearchServiceA fork, follow into ElasticSearchServiceA.
await callee('SearchServiceA').locator('.fork-chip').hover()
await shot('fork-popover')
await callee('SearchServiceA').locator('.impl', { hasText: 'ElasticSearchServiceA' }).click()
await step(1).waitFor()
await expectText(step(1), 'ElasticSearchServiceA', 'second step')
await callee('HttpClient').waitFor()
await expectText(page.locator('.breadcrumb'), 'ElasticSearchServiceA', 'breadcrumb')
// Selecting ProviderA highlights the line where it calls the next step.
await step(0).click()
await expectText(page.locator('.src-call'), 'service.search', 'highlighted call site in ProviderA source')
await shot('chain-two-steps')

// 4. Change the first choice to ProviderB: the chain after it is rebuilt.
await step(0).locator('.fork-chip').hover()
await step(0).locator('.popover .impl', { hasText: 'ProviderB' }).click()
await callee('Tokenizer').waitFor()
await callee('InMemoryIndex').waitFor()
if ((await page.locator('.box-step').count()) !== 1) throw new Error('chain was not truncated after changing a choice')
await shot('switched-to-providerB')

// 5. Back button restores the previous walk.
await page.goBack()
await callee('HttpClient').waitFor()

// 6. Callers: climb from ElasticSearchServiceA.search.
await page.goto(`${base}/?sym=${encodeURIComponent('com/acme/search/ElasticSearchServiceA#search().')}&mode=up&view=chain`)
await expectText(page.locator('.box-callee'), 'ProviderA', 'callers')
await expectText(page.locator('.react-flow__edge-text'), 'via SearchServiceA', 'via label')
await page.locator('.box-callee', { hasText: 'ProviderA' }).click()
await expectText(page.locator('.box-callee'), 'SearchController', 'callers of ProviderA.search')
await shot('callers')

// 7. Paths to entrypoints, opened as a down chain with the forks pre-chosen.
await page.locator('.box-step', { hasText: 'ElasticSearchServiceA' }).click()
await expectText(page.locator('.details-title'), 'ElasticSearchServiceA.search', 'details panel')
await page.locator('.details-section button', { hasText: 'Find every call chain' }).click()
await page.locator('.path').first().waitFor()
await shot('entrypoint-paths')
await page.locator('.path').filter({ hasNotText: 'Cached' }).first().click()
await page.locator('.segmented button.on', { hasText: 'Calls' }).waitFor()
await expectText(page.locator('.breadcrumb'), 'ElasticSearchServiceA', 'opened path')
await shot('path-as-chain')

// 8. Endpoints: the landing page lists them; start a walk from an http4s route.
await page.goto(`${base}/?view=chain`)
await page.locator('.catalogue-item').first().waitFor()
await shot('endpoint-catalogue')
await page.locator('.catalogue-head input').fill('http4s')
await page.locator('.catalogue-item', { hasText: '/api/search' }).click()
await expectText(page.locator('.box-step'), '/api/search', 'endpoint box')
await callee('SearchService').waitFor()
await expectText(page.locator('.react-flow__edge-text'), 'search ⑂', 'edge from endpoint')
await settle() // let the camera finish fitting before hovering
await callee('SearchService').locator('.fork-chip').hover()
await callee('SearchService').locator('.impl', { hasText: 'ElasticSearchService' }).click()
await expectText(page.locator('.breadcrumb'), 'ElasticSearchService', 'breadcrumb after fork')
await shot('endpoint-walk')

// 9. Callers of the implementation climb back to the endpoint, which is an entrypoint.
await page.locator('.segmented button', { hasText: 'Callers' }).click()
await expectText(page.locator('.box-callee'), '/api/search', 'endpoint among callers')
await page.locator('.box-callee', { hasText: '/api/search' }).click()
await expectText(page.locator('.box-note'), 'Entrypoint: HTTP GET /api/search', 'entrypoint note')
await shot('callers-to-endpoint')

// 10. Across services: gateway endpoint -> client method -> gRPC call -> catalog endpoint -> implementation.
const box = (text) => page.locator('.box-callee', { hasText: text }).first()
await page.goto(`${base}/?view=chain`)
await page.locator('.catalogue-head input').fill('checkout')
await page.locator('.catalogue-item', { hasText: '/checkout/' }).click()
await box('itemViaGrpc').click()
await box('→ gRPC').click()
await expectText(page.locator('.react-flow__edge-text'), 'gRPC', 'link edge label')
await expectText(page.locator('.box-callee'), 'catalog.CatalogService/getItem', 'catalog gRPC endpoint')
await shot('cross-service-down')
await box('catalog.CatalogService/getItem').click()
await box('CatalogServiceImpl').waitFor()
await expectText(page.locator('.breadcrumb'), 'GRPC catalog.CatalogService/getItem', 'breadcrumb across services')

// 11. Up from the catalog endpoint: the gateway's HTTP clients call it, with match confidence.
await page.goto(`${base}/?sym=${encodeURIComponent('pylon:endpoint/catalog/http4s/GET /catalog/items/{id}')}&mode=up&view=chain`)
await expectText(page.locator('.box-callee'), '→ HTTP', 'clients among callers')
await expectText(page.locator('.react-flow__edge-text'), 'HTTP · 0.9', 'confidence on link edge')
await page.locator('.box-callee', { hasText: 'sttp' }).first().click()
await expectText(page.locator('.box-callee'), 'itemViaSttp', 'gateway method calling the client')
await shot('cross-service-up')

// 12. Package map (the default view): classes sit inside their package boxes, arrows go from method to method.
const pkg = (name) => page.locator('.react-flow__node-package').filter({ has: page.locator('.pkg-name', { hasText: new RegExp(`^${name}$`) }) })
const klass = (title) => page.locator('.react-flow__node-klass').filter({ has: page.locator('.klass-name', { hasText: new RegExp(`^${title}$`) }) })
const row = (title, method) => klass(title).locator('.row', { hasText: `.${method}` }).first()
await page.goto(`${base}/?sym=${encodeURIComponent('com/acme/search/ProviderTrait#search().')}&mode=down`)
await pkg('com.acme.search').waitFor()
await klass('ProviderTrait').waitFor()
await row('ProviderB', 'search').waitFor() // an implementation to pick, in its own class box
await page.locator('.react-flow__edge.pm-edge-candidate').first().waitFor({ state: 'attached' })
await shot('packages-fork')
await row('ProviderA', 'search').click()
await row('SearchServiceA', 'search').waitFor()
await row('Ranker', 'rank').waitFor()
await page.locator('.react-flow__edge.pm-edge-impl').first().waitFor({ state: 'attached' })
if (await klass('ProviderB').count()) throw new Error('the implementations not picked should be gone')
await shot('packages-calls')
await row('SearchServiceA', 'search').locator('.fork-chip').hover()
await row('SearchServiceA', 'search').locator('.impl', { hasText: 'ElasticSearchServiceA' }).click()
await row('HttpClient', 'get').waitFor()
await expectText(page.locator('.breadcrumb'), 'ElasticSearchServiceA', 'breadcrumb in the package map')

// Across services and packages: the gateway endpoint's calls reach classes in other packages.
await page.goto(`${base}/?sym=${encodeURIComponent('pylon:endpoint/gateway/http4s/GET /checkout/{id}')}&mode=down`)
await pkg('endpoints').first().waitFor()
await pkg('gateway').waitFor()
await row('CatalogClients', 'itemViaGrpc').click()
await page.locator('.react-flow__node-package .pkg-name', { hasText: 'calls to other services' }).waitFor()
await page.locator('.row', { hasText: '→ gRPC' }).first().click()
await page.locator('.react-flow__edge.pm-edge-link').first().waitFor({ state: 'attached' })
await pkg('catalog.app').waitFor().catch(() => undefined)
await shot('packages-across-services')

// Boxes can be dragged, and stay where they were put when the walk goes on.
await page.locator('.react-flow__controls-fitview').click()
await settle()
const gateway = pkg('gateway')
const before = await gateway.boundingBox()
await page.mouse.move(before.x + 60, before.y + 16)
await page.mouse.down()
await page.mouse.move(before.x + 160, before.y + 216, { steps: 8 })
await page.mouse.up()
const after = await gateway.boundingBox()
if (Math.abs(after.y - before.y) < 50) throw new Error(`package was not dragged (${before.y} -> ${after.y})`)
const cls = klass('CatalogClients')
const c0 = await cls.boundingBox()
await page.mouse.move(c0.x + 40, c0.y + 14)
await page.mouse.down()
await page.mouse.move(c0.x + 40, c0.y + 154, { steps: 8 })
await page.mouse.up()
const c1 = await cls.boundingBox()
if (c1.y - c0.y < 60) throw new Error('class box was not dragged')
await shot('packages-dragged')
// Node transforms are in flow coordinates, independent of the zoom.
const flowPos = async (locator) => {
  const t = await locator.evaluate((el) => el.style.transform)
  const [, x, y] = t.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px\)/)
  return { x: Number(x), y: Number(y) }
}
const offset = async () => {
  const [c, g] = await Promise.all([flowPos(klass('CatalogClients')), flowPos(gateway)])
  return { x: c.x - g.x, y: c.y - g.y }
}
const moved = await offset()
await page.locator('.crumb').first().click()
await settle()
const kept = await offset()
if (Math.abs(kept.x - moved.x) > 1 || Math.abs(kept.y - moved.y) > 1) throw new Error(`dragged class box moved after the walk changed: ${JSON.stringify({ moved, kept })}`)
await page.locator('.pm-tools button', { hasText: 'Reset layout' }).click()
await shot('packages-reset')

if (errors.length) throw new Error(`page errors:\n${errors.join('\n')}`)
await browser.close()
console.log(`e2e ok — screenshots in ${shots}`)
