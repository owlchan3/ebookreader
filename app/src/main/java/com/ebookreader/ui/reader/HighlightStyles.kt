package com.ebookreader.ui.reader

import android.graphics.Color
import androidx.annotation.ColorInt
import org.readium.navigator.common.Decoration
import org.readium.navigator.web.common.WebDecorationTemplate

/** 波浪线下划线样式：仅用于划线/批注的「波浪线」选项（高光/横线用内置样式）。 */
data class WavyUnderlineStyle(@param:ColorInt override val tint: Int) :
    Decoration.Style, Decoration.Style.Tinted

/**
 * 在选中文字底部绘制一条波浪线的装饰模板。
 *
 * 通过装饰元素自身的 `background-image`（一条 SVG 波浪线，`repeat-x` 贴底）实现。
 * 注意：`background-image` 里的 data-URI SVG 无法继承宿主元素的 `currentColor` / `var()`，
 * 所以颜色必须直接内联进 SVG 的 `stroke`（`%23` 是 `#` 的 URL 编码），否则会固定渲染成
 * 黑色、暗色主题下不可见。
 *
 * 装饰元素会被 Web 装饰管理器以内联 `position: absolute` 精确摆放，背景贴底随文字重排自动跟随。
 */
fun wavyUnderlineTemplate(): WebDecorationTemplate {
    val className = "ebr-wavy-underline"
    return WebDecorationTemplate(
        layout = WebDecorationTemplate.Layout.BOXES,
        element = { style ->
            val tint = (style as? Decoration.Style.Tinted)?.tint ?: Color.YELLOW
            val hex = String.format("%06X", 0xFFFFFF and tint)
            val svg = "data:image/svg+xml,%3Csvg%20xmlns%3D%22http://www.w3.org/2000/svg%22%20viewBox%3D%220%200%2010%204%22%3E%3Cpath%20d%3D%22M0%203%20Q1.25%200%202.5%203%20T5%203%20T7.5%203%20T10%203%22%20fill%3D%22none%22%20stroke%3D%22%23${hex}%22%20stroke-width%3D%221.5%22%20stroke-linecap%3D%22round%22/%3E%3C/svg%3E"
            """<div class="$className" style="background-image:url('$svg');"/>"""
        },
        stylesheet = """
            .$className {
                box-sizing: border-box;
                background-repeat: repeat-x;
                background-position: left bottom;
                background-size: 10px 4px;
            }
        """
    )
}

/**
 * 底部横线（实线）下划线样式，仅用于划线/批注的「横线」选项。
 *
 * 为什么不用 Readium 内置的 `Decoration.Style.Underline` 模板：内置模板用 `border-bottom` +
 * `var(--underline-color)` 画线，但黑夜/护眼主题会开启 `overridePublisherColors`，Readium CSS 会注入
 * `:root[style*="--USER__textColor"] *:not(a) { border-color: currentColor !important; }`，
 * 把装饰元素的 `border-color` 强制改成字色，导致横线颜色始终跟字色一致。
 *
 * 这里改用 `background-image` 画一条 2px 实线贴底，不受 `border-color` / `background-color`
 * 覆盖规则影响，颜色直接内联进元素，暗色主题下也正确。
 */
fun underlineTemplate(): WebDecorationTemplate {
    val className = "ebr-underline"
    return WebDecorationTemplate(
        layout = WebDecorationTemplate.Layout.BOXES,
        element = { style ->
            val tint = (style as? Decoration.Style.Tinted)?.tint ?: Color.YELLOW
            val hex = String.format("%06X", 0xFFFFFF and tint)
            """<div class="$className" style="background-image:linear-gradient(#$hex,#$hex);"/>"""
        },
        stylesheet = """
            .$className {
                box-sizing: border-box;
                background-repeat: no-repeat;
                background-position: left bottom;
                background-size: 100% 2px;
            }
        """
    )
}
