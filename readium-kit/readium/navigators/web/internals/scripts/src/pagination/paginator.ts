//
//  Copyright 2024 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//

/**
 * Physical pagination for reflowable resources.
 *
 * Replaces the CSS multi-column layout (which fragments text nodes across column
 * boxes *without* DOM boundaries, breaking native text selection) with a
 * JS-measured set of explicit page `<div>`s.
 *
 * Each page holds clean, unsplit DOM subtrees, so within-page selection works
 * natively and a paragraph spanning a page boundary is split into two complete
 * elements — eliminating the Chromium bug where the selection jumped to the
 * chapter's first page.
 *
 * The outer model stays unchanged: the horizontal scroll extent becomes
 * `pageCount * pageWidth`, so the native side's pixel-based progression and
 * `move.getOffsetForLocation` keep working without modification.
 *
 * Containers (a `<div>`/`<section>`/`<article>` that holds block-level children)
 * are not treated as atomic: their children are flowed across pages, with a fresh
 * shallow clone of the container opened on each page so descendant selectors and
 * inherited styles keep applying. Vertical padding/margin is stripped from those
 * clones so it is not duplicated on every page.
 *
 * RTL needs no special handling: the page container is a flex row that inherits
 * `direction: rtl` and lays its children right-to-left automatically. Vertical
 * text (CJK `writing-mode`) never reaches paged mode (Readium forces scroll mode),
 * so this code is never invoked for it.
 */

import { log } from "../util/log"

const PAGES_CONTAINER_ID = "readium-physical-pages"
const PAGE_CLASS = "readium-physical-page"
const PAGE_CONTENT_CLASS = "readium-physical-page-content"

const OVERFLOW_TOLERANCE = 1 // px
const MIN_FRAGMENT_HEIGHT = 8 // px
const SEED_WINDOW = 256 // chars around the proportional estimate

const ATOMIC_SELECTOR = "img,video,audio,iframe,object,embed,svg,table,pre,hr,math"

interface Viewport {
  width: number
  height: number
}

/** Original direct children of `<body>`, kept intact for un-/re-pagination. */
let originalNodes: Node[] | null = null

/** Body line-length / gutter captured before the layout overrides, reapplied per page. */
let contentMetrics: { maxWidth: string; paddingLeft: string; paddingRight: string } | null = null

/** Signature of the last successful build, to skip redundant re-pagination. */
let lastViewportKey = ""
let lastContentKey = ""

function getViewport(wnd: Window): Viewport {
  const vv = wnd.visualViewport
  return {
    width: vv ? vv.width : wnd.innerWidth,
    height: vv ? vv.height : wnd.innerHeight,
  }
}

/** Paged mode is the default; scroll mode is signalled by `readium-scroll-on`. */
export function isPagedMode(wnd: Window): boolean {
  const view = wnd.document.documentElement.style.getPropertyValue("--USER__view")
  return view !== "readium-scroll-on"
}

function isPaginated(wnd: Window): boolean {
  return wnd.document.getElementById(PAGES_CONTAINER_ID) != null
}

/** Changes when content or user settings (font size, line length, theme) change. */
function contentSignature(wnd: Window): string {
  const doc = wnd.document
  const rootStyle = doc.documentElement.getAttribute("style") ?? ""
  const fontSize = wnd.getComputedStyle(doc.documentElement).fontSize
  return `${rootStyle}|${fontSize}|${doc.body.textContent?.length ?? 0}`
}

/**
 * Ensure the document is paginated (or restored) for the current mode and layout.
 * Returns `true` when the layout actually changed.
 */
export function paginate(wnd: Window): boolean {
  if (!isPagedMode(wnd)) {
    if (isPaginated(wnd)) {
      unpaginate(wnd)
    }
    return false
  }

  const viewport = getViewport(wnd)
  const viewportKey = `${viewport.width}x${viewport.height}`
  const contentKey = contentSignature(wnd)

  if (isPaginated(wnd) && viewportKey === lastViewportKey && contentKey === lastContentKey) {
    return false
  }

  try {
    build(wnd, viewport)
  } catch (e) {
    log("physical pagination failed", e)
    unpaginate(wnd)
    return false
  }

  lastViewportKey = viewportKey
  lastContentKey = contentKey
  return true
}

/** Restore the original document structure (removes all generated page divs). */
export function unpaginate(wnd: Window): void {
  const doc = wnd.document
  const container = doc.getElementById(PAGES_CONTAINER_ID)
  if (container) {
    container.remove()
  }

  if (originalNodes) {
    for (const node of originalNodes) {
      doc.body.appendChild(node)
    }
    originalNodes = null
  }

  restoreLayoutOverrides(doc)
  lastViewportKey = ""
  lastContentKey = ""
}

// --- layout overrides (single column + full-width body) ---------------------

const LAYOUT_OVERRIDES: Array<{ target: "root" | "body"; property: string; value: string }> = [
  { target: "root", property: "column-count", value: "1" },
  { target: "root", property: "column-width", value: "auto" },
  { target: "body", property: "max-width", value: "none" },
  { target: "body", property: "margin", value: "0" },
  { target: "body", property: "padding", value: "0" },
  { target: "body", property: "width", value: "100%" },
  { target: "body", property: "overflow", value: "visible" },
  { target: "body", property: "height", value: "100%" },
]

let savedLayoutOverrides: Array<{ target: "root" | "body"; property: string; value: string }> = []

function elementFor(doc: Document, target: "root" | "body"): HTMLElement {
  return target === "root" ? doc.documentElement : doc.body
}

function applyLayoutOverrides(doc: Document): void {
  savedLayoutOverrides = []
  for (const override of LAYOUT_OVERRIDES) {
    const el = elementFor(doc, override.target)
    savedLayoutOverrides.push({
      target: override.target,
      property: override.property,
      value: el.style.getPropertyValue(override.property),
    })
    el.style.setProperty(override.property, override.value, "important")
  }
}

function restoreLayoutOverrides(doc: Document): void {
  for (const saved of savedLayoutOverrides) {
    const el = elementFor(doc, saved.target)
    if (saved.value) {
      el.style.setProperty(saved.property, saved.value)
    } else {
      el.style.removeProperty(saved.property)
    }
  }
  savedLayoutOverrides = []
}

// --- build ---------------------------------------------------------------

function build(wnd: Window, viewport: Viewport): void {
  const doc = wnd.document

  unpaginate(wnd)
  originalNodes = Array.from(doc.body.childNodes)

  const bodyStyle = wnd.getComputedStyle(doc.body)
  contentMetrics = {
    maxWidth: bodyStyle.maxWidth,
    paddingLeft: bodyStyle.paddingLeft,
    paddingRight: bodyStyle.paddingRight,
  }

  applyLayoutOverrides(doc)

  const container = doc.createElement("div")
  container.id = PAGES_CONTAINER_ID
  container.style.cssText = "display:flex;flex-direction:row;width:100%;height:100%;"
  doc.body.appendChild(container)

  const firstPage = createPage(doc, viewport)
  container.appendChild(firstPage)

  flow(wnd, originalNodes, firstPage, viewport, container, [])

  removeTrailingEmptyPages(container)
}

function createPage(doc: Document, viewport: Viewport): HTMLDivElement {
  const page = doc.createElement("div")
  page.className = PAGE_CLASS
  // `overflow: clip` clips like `hidden` but does NOT create a scroll container,
  // so Chromium's native selection auto-scroll falls through to the viewport and
  // cross-page handle dragging keeps working. `hidden` is the fallback for older
  // WebViews (it still paginates correctly, just without cross-page selection).
  page.style.cssText =
    `flex:0 0 ${viewport.width}px;width:${viewport.width}px;height:${viewport.height}px;` +
    "overflow:hidden;overflow:clip;position:relative;box-sizing:border-box;"

  const content = doc.createElement("div")
  content.className = PAGE_CONTENT_CLASS
  // Re-apply the line length / gutter captured from the body.
  content.style.cssText = contentWidthCss() + "margin:0 auto;"
  page.appendChild(content)

  return page
}

function contentOf(page: HTMLDivElement): HTMLElement {
  return page.querySelector(`.${PAGE_CONTENT_CLASS}`) as HTMLElement
}

function contentWidthCss(): string {
  const m = contentMetrics
  const maxWidth =
    m?.maxWidth && m.maxWidth !== "none" ? m.maxWidth : "var(--RS__defaultLineLength)"
  const padLeft = m?.paddingLeft ?? "var(--RS__pageGutter)"
  const padRight = m?.paddingRight ?? "var(--RS__pageGutter)"
  return `max-width:${maxWidth};padding:0 ${padRight} 0 ${padLeft};box-sizing:border-box;`
}

// --- node classification --------------------------------------------------

type Kind = "skip" | "inline" | "atomic" | "container" | "leaf"

function getDisplay(el: Element): string {
  return el.ownerDocument.defaultView?.getComputedStyle(el).display ?? "inline"
}

function hasBlockChildren(el: Element): boolean {
  for (const child of Array.from(el.children)) {
    const display = getDisplay(child)
    if (display !== "inline" && display !== "none" && display !== "contents") {
      return true
    }
  }
  return false
}

function classify(node: Node): Kind {
  if (node.nodeType === Node.COMMENT_NODE) {
    return "skip"
  }
  if (node.nodeType === Node.TEXT_NODE) {
    return (node.textContent ?? "").trim() === "" ? "skip" : "inline"
  }
  const el = node as HTMLElement
  const display = getDisplay(el)
  if (display === "none") {
    return "skip"
  }
  if (el.matches(ATOMIC_SELECTOR)) {
    return "atomic"
  }
  // A block that contains replaced content (media/table/pre) is kept whole.
  if (el.querySelector(ATOMIC_SELECTOR)) {
    return "atomic"
  }
  if (display === "contents" || hasBlockChildren(el)) {
    return "container"
  }
  if (display === "inline") {
    return "inline"
  }
  return "leaf"
}

// --- wrapper stack (containers opened on the current page) -----------------

interface Wrapper {
  source: Element
  clone: HTMLElement
}

function cloneWrapper(source: Element): HTMLElement {
  const clone = source.cloneNode(false) as HTMLElement
  // Vertical spacing would otherwise be duplicated on every page a container spans.
  clone.style.marginTop = "0"
  clone.style.marginBottom = "0"
  clone.style.paddingTop = "0"
  clone.style.paddingBottom = "0"
  return clone
}

function targetOf(page: HTMLDivElement, stack: Wrapper[]): HTMLElement {
  return stack.length > 0 ? stack[stack.length - 1].clone : contentOf(page)
}

/** Create a new page and re-open every container wrapper on it. */
function newPageWithStack(
  wnd: Window,
  viewport: Viewport,
  container: HTMLElement,
  stack: Wrapper[]
): HTMLDivElement {
  const page = createPage(wnd.document, viewport)
  container.appendChild(page)
  let target = contentOf(page)
  for (const wrapper of stack) {
    wrapper.clone = cloneWrapper(wrapper.source)
    target.appendChild(wrapper.clone)
    target = wrapper.clone
  }
  return page
}

// --- flow -----------------------------------------------------------------

function flow(
  wnd: Window,
  nodes: Node[],
  page: HTMLDivElement,
  viewport: Viewport,
  container: HTMLElement,
  stack: Wrapper[]
): HTMLDivElement {
  let current = page

  for (const node of nodes) {
    const kind = classify(node)
    if (kind === "skip") {
      continue
    }
    if (kind === "inline") {
      targetOf(current, stack).appendChild(node)
      continue
    }
    if (kind === "atomic") {
      current = placeAtomic(wnd, node as HTMLElement, current, viewport, container, stack)
      continue
    }
    if (kind === "leaf") {
      current = placeLeaf(wnd, node as HTMLElement, current, viewport, container, stack)
      continue
    }

    // container
    const el = node as HTMLElement
    if (getDisplay(el) === "contents") {
      current = flow(wnd, Array.from(el.childNodes), current, viewport, container, stack)
    } else {
      const wrapper: Wrapper = { source: el, clone: cloneWrapper(el) }
      targetOf(current, stack).appendChild(wrapper.clone)
      stack.push(wrapper)
      current = flow(wnd, Array.from(el.childNodes), current, viewport, container, stack)
      stack.pop()
    }
  }

  return current
}

function remainingHeight(page: HTMLDivElement, pageHeight: number): number {
  return Math.max(0, pageHeight - contentOf(page).scrollHeight)
}

/** Place an atomic element (image/table/pre/…) whole; never split it. */
function placeAtomic(
  wnd: Window,
  el: HTMLElement,
  page: HTMLDivElement,
  viewport: Viewport,
  container: HTMLElement,
  stack: Wrapper[]
): HTMLDivElement {
  const target = targetOf(page, stack)
  const available = remainingHeight(page, viewport.height)

  target.appendChild(el)
  const height = el.getBoundingClientRect().height
  if (height <= available + OVERFLOW_TOLERANCE) {
    return page
  }

  target.removeChild(el)
  if (available >= viewport.height - 1) {
    // Alone on a fresh page and still too tall: leave it (clipped).
    target.appendChild(el)
    return page
  }

  const next = newPageWithStack(wnd, viewport, container, stack)
  targetOf(next, stack).appendChild(el)
  return next
}

/** Place a leaf block, splitting its text so it flows across pages. */
function placeLeaf(
  wnd: Window,
  el: HTMLElement,
  page: HTMLDivElement,
  viewport: Viewport,
  container: HTMLElement,
  stack: Wrapper[]
): HTMLDivElement {
  let current = page
  let remaining: HTMLElement | null = el
  let targetHeight = remainingHeight(current, viewport.height)

  while (remaining) {
    if (targetHeight < MIN_FRAGMENT_HEIGHT) {
      current = newPageWithStack(wnd, viewport, container, stack)
      targetHeight = remainingHeight(current, viewport.height)
    }

    const target = targetOf(current, stack)
    const { head, tail } = splitBlockAtHeight(wnd, remaining, targetHeight, target)
    target.appendChild(head)

    if (!tail) {
      break
    }

    current = newPageWithStack(wnd, viewport, container, stack)
    remaining = tail
    targetHeight = remainingHeight(current, viewport.height)
  }

  return current
}

// --- text splitting -------------------------------------------------------

interface SplitResult {
  head: HTMLElement
  tail: HTMLElement | null
}

/**
 * Split a block element into a `head` (rendered height at most `targetHeight`) and a
 * `tail` containing the rest. Returns `tail = null` when everything fits.
 */
function splitBlockAtHeight(
  wnd: Window,
  block: HTMLElement,
  targetHeight: number,
  target: HTMLElement
): SplitResult {
  const total = block.textContent?.length ?? 0
  if (total === 0) {
    return { head: block, tail: null }
  }

  const fullHeight = measureHeight(wnd, block, target)
  if (fullHeight <= targetHeight + OVERFLOW_TOLERANCE) {
    return { head: block, tail: null }
  }

  // Seed a narrow binary-search window around a proportional estimate.
  const ratio = targetHeight / Math.max(1, fullHeight)
  const estimate = Math.floor(total * Math.min(1, ratio))
  let lo = Math.max(0, estimate - SEED_WINDOW)
  let hi = Math.min(total, estimate + SEED_WINDOW)

  if (hi < total && measurePrefix(wnd, block, hi, target) <= targetHeight) {
    lo = hi
    hi = total
  } else if (lo > 0 && measurePrefix(wnd, block, lo, target) > targetHeight) {
    hi = lo
    lo = 0
  }

  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2)
    if (measurePrefix(wnd, block, mid, target) <= targetHeight) {
      lo = mid
    } else {
      hi = mid - 1
    }
  }

  if (lo >= total) {
    return { head: block, tail: null }
  }
  if (lo <= 0) {
    // Even a single character doesn't fit: return the block unsplit (clipped).
    return { head: block, tail: null }
  }

  const tail = cloneSuffixAtCharOffset(block, lo)
  // A continuation has no first-line indent, regardless of the source paragraph.
  tail.style.textIndent = "0"
  return {
    head: clonePrefixAtCharOffset(block, lo),
    tail,
  }
}

/** Height of an element laid out in `target` (correct width and styling), without
 *  permanently mutating the page. */
function measureHeight(wnd: Window, el: HTMLElement, target: HTMLElement): number {
  target.appendChild(el)
  const height = el.getBoundingClientRect().height
  target.removeChild(el)
  return height
}

function measurePrefix(
  wnd: Window,
  block: HTMLElement,
  charOffset: number,
  target: HTMLElement
): number {
  return measureHeight(wnd, clonePrefixAtCharOffset(block, charOffset), target)
}

function createTextNode(doc: Document, text: string): Text {
  return doc.createTextNode(text)
}

function clonePrefixAtCharOffset(container: HTMLElement, charOffset: number): HTMLElement {
  const doc = container.ownerDocument
  const clone = container.cloneNode(false) as HTMLElement
  let remaining = charOffset
  for (const child of Array.from(container.childNodes)) {
    if (remaining <= 0) {
      break
    }
    if (child.nodeType === Node.TEXT_NODE) {
      const text = child.textContent ?? ""
      clone.appendChild(createTextNode(doc, text.substring(0, remaining)))
      remaining -= text.length
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      const childClone = child.cloneNode(false) as HTMLElement
      clone.appendChild(childClone)
      remaining = fillPrefix(child as HTMLElement, childClone, remaining)
    }
  }
  return clone
}

function fillPrefix(source: HTMLElement, target: HTMLElement, charOffset: number): number {
  const doc = source.ownerDocument
  let remaining = charOffset
  for (const child of Array.from(source.childNodes)) {
    if (remaining <= 0) {
      break
    }
    if (child.nodeType === Node.TEXT_NODE) {
      const text = child.textContent ?? ""
      target.appendChild(createTextNode(doc, text.substring(0, remaining)))
      remaining -= text.length
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      const childClone = child.cloneNode(false) as HTMLElement
      target.appendChild(childClone)
      remaining = fillPrefix(child as HTMLElement, childClone, remaining)
    }
  }
  return remaining
}

function cloneSuffixAtCharOffset(container: HTMLElement, charOffset: number): HTMLElement {
  const doc = container.ownerDocument
  const clone = container.cloneNode(false) as HTMLElement
  let skip = charOffset
  for (const child of Array.from(container.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      const text = child.textContent ?? ""
      if (skip >= text.length) {
        skip -= text.length
      } else {
        clone.appendChild(createTextNode(doc, text.substring(skip)))
        skip = 0
      }
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      if (skip > 0) {
        skip = fillSuffix(child as HTMLElement, clone, skip)
      } else {
        clone.appendChild(child.cloneNode(true))
      }
    }
  }
  return clone
}

function fillSuffix(source: HTMLElement, target: HTMLElement, charOffset: number): number {
  const doc = source.ownerDocument
  let skip = charOffset
  for (const child of Array.from(source.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      const text = child.textContent ?? ""
      if (skip >= text.length) {
        skip -= text.length
      } else {
        target.appendChild(createTextNode(doc, text.substring(skip)))
        skip = 0
      }
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      if (skip > 0) {
        skip = fillSuffix(child as HTMLElement, target, skip)
      } else {
        target.appendChild(child.cloneNode(true))
      }
    }
  }
  return skip
}

function removeTrailingEmptyPages(container: HTMLElement): void {
  const pages = Array.from(container.querySelectorAll(`.${PAGE_CLASS}`))
  for (let i = pages.length - 1; i >= 0; i--) {
    const content = pages[i].querySelector(`.${PAGE_CONTENT_CLASS}`)
    if (content && (content.textContent ?? "").trim() === "") {
      pages[i].remove()
    } else {
      break
    }
  }
}
