package com.ebookreader.ui.decompose

/**
 * AI 拆书提示词与档位参数（集中存放，方便自行修改）。
 *
 * 文件位置：
 *   app/src/main/java/com/ebookreader/ui/decompose/DecomposePrompts.kt
 *
 * 修改方法：
 *   - 想调整某类书籍的拆解侧重点 → 改 mapPrompt / bookSummaryPrompt 里的 focus 文案。
 *   - 想调整成本与详细程度 → 改 TIERS 里的参数：
 *       · sampleChars > 0 表示「抽样模式」（compact 档只读每章开头，最省钱）。
 *       · chapterCap   表示「读整章」时的单章截断上限（越大输入越贵）。
 *       · summaryLen   逐章摘要目标字数。
 *       · bookSummaryLen 全书总结目标字数。
 *
 * 档位（tier）：
 *   compact  = 精简：目录 + 每章开头抽样 → 粗纲（几毛钱）
 *   standard = 标准（默认）：逐章完整摘要 + 大纲 + 全书总结
 *   deep     = 深度：standard + 人物关系 + 时间线 + 金句
 */
object DecomposePrompts {

    // ── 档位参数 ──────────────────────────────────────────────

    data class TierConfig(
        val sampleChars: Int,     // >0 = 抽样模式（compact）；0 = 读整章
        val chapterCap: Int,      // 读整章时的单章内容截断上限
        val summaryLen: Int,      // 逐章摘要目标字数
        val bookSummaryLen: Int,  // 全书总结目标字数
    )

    val TIERS: Map<String, TierConfig> = mapOf(
        "compact" to TierConfig(sampleChars = 800, chapterCap = 0, summaryLen = 60, bookSummaryLen = 300),
        "standard" to TierConfig(sampleChars = 0, chapterCap = 10_000, summaryLen = 150, bookSummaryLen = 500),
        "deep" to TierConfig(sampleChars = 0, chapterCap = 20_000, summaryLen = 300, bookSummaryLen = 800),
    )

    fun tierConfig(tier: String): TierConfig =
        TIERS[tier] ?: TIERS.getValue("standard")

    /** 某档位是否为「抽样模式」（compact）。 */
    fun isSampling(tier: String): Boolean = tierConfig(tier).sampleChars > 0

    // ── 逐章摘要（Map）提示词 ─────────────────────────────────

    fun mapPrompt(bookType: String, tier: String): String {
        val cfg = tierConfig(tier)

        // compact：抽样，只读开头，输出一句话粗纲
        if (cfg.sampleChars > 0) {
            return "请根据以下这一章的开头内容，用中文写一句话粗纲（大致概括本章内容）。控制在 ${cfg.summaryLen} 字以内，直接输出。"
        }

        val focus = when (bookType) {
            "webnovel" -> "剧情发展、关键事件、出场人物、爽点与伏笔"
            "fanfic" -> "剧情发展、关键事件、出场人物、与原作设定的关联（这是同人作品）"
            "classic" -> "情节推进、人物塑造、主题思想、文学手法"
            "shortstory" -> "故事梗概、人物、主题（这是一篇完整短篇小说，请概括整体情节，不要照抄原文）"
            "collection" -> "本篇故事的梗概、人物、主题（这是短篇小说集，本章是一篇独立小说，请概括情节而非摘抄原文；若与前篇有联系可点明）"
            "collection_fanfic" -> "本篇故事的梗概、人物、与原作设定的关联（这是短篇同人小说集，每篇独立，请概括情节而非摘抄原文）"
            "history_novel" -> "剧情发展、关键事件、人物、历史背景与虚构改编之处（这是历史题材小说）"
            "history_academic" -> "核心论点、史料依据、论证逻辑、学术结论（这是历史学术研究）"
            "novel" -> "剧情发展、关键事件、出场人物、埋下的伏笔"
            "history" -> "历史事件、涉及人物、年代、前因后果"
            "stem" -> "核心概念、公式/原理、关键知识点"
            "humanities" -> "核心论点、论证逻辑、重要概念、作者观点"
            else -> "主要内容、关键要点"
        }

        // deep：额外输出关键事件 + 人物
        if (tier == "deep") {
            return "请用中文概括以下这一章，重点是：$focus。请严格按下面三段输出（每段以【】标题开头），用自己的话概括、不要照抄原文：\n" +
                "【梗概】控制在 ${cfg.summaryLen} 字以内；\n" +
                "【关键事件】列出本章发生的 1-3 个关键事件；\n" +
                "【人物】列出本章出场的主要人物。"
        }

        return "请用中文概括以下这一章的内容，重点是：$focus。控制在 ${cfg.summaryLen} 字以内，用自己的话概括，不要照抄原文，直接输出概括。"
    }

    // ── 全书大纲（Outline）提示词 ─────────────────────────────

    fun outlinePrompt(bookType: String): String {
        return "以下是这本书各章（节）的梗概。请先按情节/主题把它们归纳成 3-8 个部分（卷/部/部分），" +
            "每个部分起一个概括性的标题（如「第一部分：初入江湖」），并写一句该部分的主线概括；" +
            "再把各章归入对应部分作为子节点（只写章节标题 + 一句话要点）。" +
            "注意：不要平铺罗列所有章节，重点是「部分」这一层的归纳概括。\n" +
            "严格按以下 JSON 格式输出（不要输出任何其他文字、解释或代码块标记）：\n" +
            """{"title":"全书大纲","children":[{"title":"第一部分：…","summary":"该部分主线概括","children":[{"title":"第一章标题","summary":"一句话要点"}]}]}"""
    }

    // ── 全书总结（Reduce）提示词 ──────────────────────────────

    fun bookSummaryPrompt(bookType: String, tier: String): String {
        val cfg = tierConfig(tier)
        return when (bookType) {
            "webnovel" -> "以下是这本书各章的梗概。请用中文总结全书：主线剧情、人物关系、时间线、爽点结构。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "fanfic" -> "以下是这本同人作品各章的梗概。请用中文总结全书：主线剧情、人物关系、与原作设定的关联与改编之处。可结合你对原作的知识补充背景。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "classic" -> "以下是这本书各章的梗概。请用中文总结全书：主旨、主线、人物关系、文学价值。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "shortstory" -> "以下是这篇小说的梗概。请用中文总结：故事主旨、人物、主题。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "collection" -> "以下是这本短篇小说集各篇的梗概。请用中文总结：各篇故事概要、共同主题、整体风格。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "collection_fanfic" -> "以下是这本短篇同人小说集各篇的梗概。请用中文总结：各篇故事概要、共同主题、与原作设定的关联。可结合你对原作的知识补充背景。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "history_novel" -> "以下是这本历史题材小说各章的梗概。请用中文总结全书：主线剧情、人物关系、历史背景与虚构改编之处。可结合相关历史背景知识补充。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "history_academic" -> "以下是这本历史学术研究各章的梗概。请用中文总结全书：核心论点体系、史料依据、论证结构、学术贡献。可结合相关史学背景补充。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "novel" -> "以下是这本书各章的梗概。请用中文总结全书：主旨、主线剧情、人物关系、时间线。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "history" -> "以下是这本书各章的梗概。请用中文总结全书：历史脉络、关键事件时间线、核心结论。可结合相关历史背景知识补充。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "stem" -> "以下是这本书各章的梗概。请用中文总结全书：知识框架、核心概念体系、学习方法。可结合相关学科背景知识补充。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            "humanities" -> "以下是这本书各章的梗概。请用中文总结全书：核心论点体系、论证结构、主要结论。可结合相关思想/学术背景补充。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
            else -> "以下是这本书各章的梗概。请用中文总结全书核心内容。控制在 ${cfg.bookSummaryLen} 字以内，直接输出总结。"
        }
    }

    // ── deep 档模块清单（按书类型返回，逐模块生成） ─────────

    data class DeepModule(
        val key: String,         // 存储字段 key
        val title: String,       // 显示标题
        val instruction: String, // 该模块的生成指令
    )

    /**
     * 返回 deep 档要生成的模块清单（按书类型不同）。
     * 每个模块独立生成 + 非空校验重试，避免一次生成漏模块。
     * 所有类型都含「拓展阅读」这个公共模块（每章概述 = 逐章梗概，由 Map 阶段生成）。
     */
    fun deepModules(bookType: String): List<DeepModule> {
        val five = when (bookType) {
            "novel", "webnovel", "fanfic", "classic", "shortstory", "collection", "collection_fanfic", "history_novel" -> listOf(
                DeepModule("characters", "人物关系", "主要人物及其关系（身份、立场、情感/利益纠葛）"),
                DeepModule("timeline", "时间线", "按时间顺序梳理关键事件（注意倒叙、插叙要还原真实时序）"),
                DeepModule("quotes", "金句", "摘录或概括 3-5 句最有代表性的句子/观点（尽量摘录原文原句）"),
                DeepModule("characterBios", "人物小传", "主要人物的小传（身份、性格、动机、关键经历）"),
                DeepModule("worldSetting", "世界观与设定", "世界观与设定（规则、势力、能力体系、背景设定）"),
            )
            "history", "history_academic" -> listOf(
                DeepModule("characters", "历史人物", "重要历史人物及其关系"),
                DeepModule("timeline", "历史脉络", "按年代顺序梳理关键事件（还原真实时序）"),
                DeepModule("quotes", "关键史料", "列出 3-5 条关键史料/论断（注明出处）"),
                DeepModule("characterBios", "历史人物小传", "重要历史人物的小传（身份、主要事迹、历史地位）"),
                DeepModule("worldSetting", "时代背景与制度", "时代背景与制度（政治、经济、文化背景）"),
            )
            "stem" -> listOf(
                DeepModule("characters", "核心概念/流派", "核心概念/流派及其关系"),
                DeepModule("timeline", "概念递进", "核心概念/知识点的递进关系与学习顺序"),
                DeepModule("quotes", "公式/定律", "3-5 个最重要的公式/定律/原理（准确表述，不编造）"),
                DeepModule("characterBios", "学者小传", "关键学者/奠基者的小传（贡献、代表成果）"),
                DeepModule("worldSetting", "知识框架", "知识框架与理论体系（概念层级与理论脉络）"),
            )
            "humanities" -> listOf(
                DeepModule("characters", "思想家/学派", "主要思想家/学派及其关系"),
                DeepModule("timeline", "论证逻辑", "主要论点之间的逻辑递进与论证结构"),
                DeepModule("quotes", "核心观点", "3-5 个核心观点/名言（注明提出者）"),
                DeepModule("characterBios", "思想家", "重要思想家的生平与核心主张"),
                DeepModule("worldSetting", "理论体系", "理论体系与范式（核心理论、方法论、流派共识）"),
            )
            else -> listOf(
                DeepModule("characters", "核心人物/概念", "主要人物/概念及其关系"),
                DeepModule("timeline", "时间线", "按时间顺序梳理关键事件"),
                DeepModule("quotes", "金句/要点", "3-5 句最有代表性的句子/观点"),
                DeepModule("characterBios", "人物/主体简介", "主要人物/主体的简介"),
                DeepModule("worldSetting", "背景/框架", "背景设定与整体框架"),
            )
        }

        return five + listOf(
            DeepModule(
                "extendedReading",
                "拓展阅读",
                "推荐 3-5 本真实存在、与本书主题/类型相关的书籍或作品（只推荐真实存在的作品，注明作者与一句话推荐理由，不要编造书名）。",
            ),
        )
    }
}
