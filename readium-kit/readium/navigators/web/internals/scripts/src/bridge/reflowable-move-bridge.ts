import { TextQuoteAnchor } from "../vendor/hypothesis/annotator/anchoring/types"
import { log } from "../util/log"

export class ReflowableMoveBridge {
  readonly document: HTMLDocument

  constructor(document: HTMLDocument) {
    this.document = document
  }

  getOffsetForLocation(location: string, vertical: boolean): number | null {
    const actualLocation = parseLocation(location)

    if (actualLocation.textAfter || actualLocation.textBefore) {
      return this.getOffsetForTextAnchor(
        actualLocation.textBefore ?? "",
        actualLocation.textAfter ?? "",
        vertical
      )
    }

    if (actualLocation.cssSelector) {
      return this.getOffsetForCssSelector(actualLocation.cssSelector, vertical)
    }

    if (actualLocation.htmlId) {
      return this.getOffsetForHtmlId(actualLocation.htmlId, vertical)
    }

    return null
  }

  private getOffsetForTextAnchor(
    textBefore: string,
    textAfter: string,
    vertical: boolean
  ): number | null {
    const root = this.document.body

    // 锚点直接取「textAfter 开头 N 字」作为 quote，而不是旧的「textBefore 尾字 + textAfter 首字」。
    // 旧实现只有 2 个字符，且跨了「上一句句号 + 本句首字」这条句边界：分页模式下段落末尾常落在
    // 页底、下一句在下一页，「。」与首字被拆进两个 page 元素，body.textContent 里不再相邻，
    // indexOf 匹配不到真位置，于是稳定匹配到正文别处，进度测量偏大 → 提前翻页（约半页）。
    // 取本句（或高亮文本）开头若干字，落在同一元素内部，不跨页边界，且长度足够唯一。
    const quoteLength = Math.min(16, textAfter.length)

    const anchor = new TextQuoteAnchor(
      root,
      textAfter.substring(0, quoteLength),
      {
        prefix: textBefore,
        suffix: textAfter.substring(quoteLength),
      }
    )

    try {
      const range = anchor.toRange()
      return this.getOffsetForRect(range.getBoundingClientRect(), vertical)
    } catch (e) {
      log(e)
      return null
    }
  }

  private getOffsetForCssSelector(
    cssSelector: string,
    vertical: boolean
  ): number | null {
    let element
    try {
      element = this.document.querySelector(cssSelector)
    } catch (e) {
      log(e)
    }

    if (!element) {
      return null
    }

    return this.getOffsetForElement(element, vertical)
  }

  private getOffsetForHtmlId(htmlId: string, vertical: boolean): number | null {
    const element = this.document.getElementById(htmlId)
    if (!element) {
      return null
    }

    return this.getOffsetForElement(element, vertical)
  }

  private getOffsetForElement(element: Element, vertical: boolean): number {
    const rect = element.getBoundingClientRect()
    return this.getOffsetForRect(rect, vertical)
  }

  private getOffsetForRect(rect: DOMRect, vertical: boolean): number {
    if (vertical) {
      return rect.top + window.scrollY
    } else {
      const offset = rect.left + window.scrollX
      return offset
    }
  }
}

interface Location {
  progression: number
  htmlId: string
  cssSelector: string
  textBefore: string
  textAfter: string
}

function parseLocation(location: string): Location {
  const jsonLocation: Location = JSON.parse(location)
  return jsonLocation
}
