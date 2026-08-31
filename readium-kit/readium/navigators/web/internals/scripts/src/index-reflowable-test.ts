//
//  Copyright 2024 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//

/**
 * Standalone browser harness for the physical pagination engine.
 *
 * Exposes `window.__paginator` and renders a control panel so the paged-mode
 * pagination + text selection can be exercised without the Android app.
 *
 * Run `pnpm bundle` and serve `dist/`, e.g. `python -m http.server -d dist 8000`,
 * then open http://localhost:8000/reflowable-test.html
 *
 * Notes on what is deliberately NOT exercised here:
 *  - Vertical text (CJK `writing-mode: vertical-rl`) never reaches paged mode:
 *    Readium forces `--USER__view: readium-scroll-on` for vertical text, so the
 *    paginator restores the document and stays out of the way.
 *  - RTL is handled by `direction: rtl` inheritance: the page container is a
 *    flex row that lays its children right-to-left automatically, and the native
 *    side's RTL progression mapping is unchanged. The [RTL] toggle below flips
 *    `dir` on `<html>`/`<body>` and re-paginates so you can verify page ordering.
 */

import { isPagedMode, paginate, unpaginate } from "./pagination/paginator"

const PAGES_CONTAINER_ID = "readium-physical-pages"
const PAGE_CLASS = "readium-physical-page"
const PAGE_CONTENT_CLASS = "readium-physical-page-content"

interface SelectionInfo {
  text: string
  anchorPage: number
}

interface PaginatorTestApi {
  paginate(): void
  unpaginate(): void
  isPagedMode(): boolean
  pageCount(): number
  currentPage(): number
  jumpToPage(n: number): void
  setView(scroll: boolean): void
  setFontSize(deltaPct: number): void
  setDirection(dir: "ltr" | "rtl"): void
  selectAcrossPages(fromPage: number, toPage: number): SelectionInfo | null
  pageText(page: number): string
  selectionInfo(): SelectionInfo | null
}

declare global {
  interface Window {
    __paginator: PaginatorTestApi
  }
}

function pages(): HTMLElement[] {
  return Array.from(
    document.querySelectorAll<HTMLElement>(`#${PAGES_CONTAINER_ID} .${PAGE_CLASS}`)
  )
}

function pageContent(page: number): HTMLElement | null {
  return pages()[page - 1]?.querySelector<HTMLElement>(`.${PAGE_CONTENT_CLASS}`) ?? null
}

function pageCount(): number {
  return pages().length
}

function currentPage(): number {
  const container = document.getElementById(PAGES_CONTAINER_ID)
  if (!container) return 0
  const width = window.innerWidth
  const scrollX = window.scrollX || document.documentElement.scrollLeft || 0
  return Math.floor(scrollX / width) + 1
}

function jumpToPage(n: number): void {
  window.scrollTo((n - 1) * window.innerWidth, 0)
  refreshStatus()
}

function setView(scroll: boolean): void {
  document.documentElement.style.setProperty(
    "--USER__view",
    scroll ? "readium-scroll-on" : "readium-paged-on",
    "important"
  )
  paginate(window)
}

function adjustFontSize(deltaPct: number): void {
  const current = parseFloat(document.documentElement.style.fontSize || "100") || 100
  const next = Math.max(50, Math.min(250, current + deltaPct))
  document.documentElement.style.setProperty("font-size", `${next}%`, "important")
  paginate(window)
}

function setDirection(dir: "ltr" | "rtl"): void {
  document.documentElement.setAttribute("dir", dir)
  document.body.setAttribute("dir", dir)
  unpaginate(window)
  paginate(window)
  refreshStatus()
}

function textNodes(root: Node): Text[] {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  let n: Node | null
  while ((n = walker.nextNode())) {
    nodes.push(n as Text)
  }
  return nodes.filter((t) => (t.textContent ?? "").trim().length > 0)
}

function selectAcrossPages(fromPage: number, toPage: number): SelectionInfo | null {
  const from = pageContent(fromPage)
  const to = pageContent(toPage)
  if (!from || !to) return null

  const fromNodes = textNodes(from)
  const toNodes = textNodes(to)
  if (fromNodes.length === 0 || toNodes.length === 0) return null

  const start = fromNodes[0]
  const end = toNodes[toNodes.length - 1]

  const range = document.createRange()
  range.setStart(start, 0)
  range.setEnd(end, end.textContent?.length ?? 0)

  const sel = window.getSelection()!
  sel.removeAllRanges()
  sel.addRange(range)

  refreshSelection()
  return selectionInfo()
}

function pageText(page: number): string {
  const content = pageContent(page)
  return content ? (content.textContent ?? "").replace(/\s+/g, " ").trim() : ""
}

function selectionInfo(): SelectionInfo | null {
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null
  const range = sel.getRangeAt(0)
  const anchorEl =
    range.startContainer.nodeType === Node.TEXT_NODE
      ? range.startContainer.parentElement
      : (range.startContainer as Element | null)
  const page = anchorEl?.closest(`.${PAGE_CLASS}`) ?? null
  const index = page ? pages().indexOf(page as HTMLElement) + 1 : 0
  return { text: sel.toString(), anchorPage: index }
}

function refreshStatus(): void {
  const el = document.getElementById("__status")
  if (el) {
    const dir = document.documentElement.getAttribute("dir") ?? "ltr"
    el.textContent =
      `模式: ${isPagedMode(window) ? "分页" : "滚动"} · 方向: ${dir.toUpperCase()} · ` +
      `页数: ${pageCount()} · 当前页: ${currentPage()}`
  }
}

function refreshSelection(): void {
  const el = document.getElementById("__selection")
  if (!el) return
  const info = selectionInfo()
  el.textContent = info
    ? `选区[第${info.anchorPage}页, ${info.text.length}字]: ${info.text.slice(0, 60)}${info.text.length > 60 ? "…" : ""}`
    : "选区: (无)"
}

function button(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button")
  b.textContent = label
  b.addEventListener("click", onClick)
  return b
}

function buildPanel(): void {
  const panel = document.createElement("div")
  panel.style.cssText =
    "position:fixed;top:0;left:0;right:0;z-index:99999;" +
    "background:rgba(20,20,20,.92);color:#eee;font:12px/1.5 monospace;" +
    "padding:8px 10px;display:flex;flex-wrap:wrap;gap:6px;align-items:center;"

  const status = document.createElement("span")
  status.id = "__status"
  status.style.marginRight = "auto"

  const sel = document.createElement("span")
  sel.id = "__selection"
  sel.style.cssText =
    "width:100%;color:#9fef9f;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"

  panel.appendChild(status)
  panel.appendChild(sel)
  panel.appendChild(button("重新分页", () => { unpaginate(window); paginate(window); refreshStatus() }))
  panel.appendChild(button("分页↔滚动", () => { setView(isPagedMode(window)); refreshStatus() }))
  panel.appendChild(button("字号-", () => { adjustFontSize(-10); refreshStatus() }))
  panel.appendChild(button("字号+", () => { adjustFontSize(10); refreshStatus() }))
  panel.appendChild(button("上一页", () => { jumpToPage(Math.max(1, currentPage() - 1)) }))
  panel.appendChild(button("下一页", () => { jumpToPage(currentPage() + 1) }))
  panel.appendChild(button("跨页选择", () => { selectAcrossPages(currentPage(), currentPage() + 1) }))
  panel.appendChild(button("RTL/LTR", () => {
    setDirection(document.documentElement.getAttribute("dir") === "rtl" ? "ltr" : "rtl")
  }))
  panel.appendChild(button("还原", () => { unpaginate(window); refreshStatus() }))

  document.documentElement.appendChild(panel)
}

function bootstrap(): void {
  buildPanel()
  paginate(window)
  refreshStatus()
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", bootstrap)
} else {
  bootstrap()
}

// Re-paginate once fonts / images settle.
window.addEventListener("load", () => {
  paginate(window)
  refreshStatus()
})

// Re-paginate when the viewport is resized (more/less pages).
let resizeTimer = 0
window.addEventListener("resize", () => {
  window.clearTimeout(resizeTimer)
  resizeTimer = window.setTimeout(() => {
    paginate(window)
    refreshStatus()
  }, 150)
})

document.addEventListener("selectionchange", refreshSelection)

window.__paginator = {
  paginate: () => { paginate(window) },
  unpaginate: () => { unpaginate(window) },
  isPagedMode: () => isPagedMode(window),
  pageCount,
  currentPage,
  jumpToPage,
  setView,
  setFontSize: adjustFontSize,
  setDirection,
  selectAcrossPages,
  pageText,
  selectionInfo,
}
