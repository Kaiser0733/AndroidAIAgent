package com.kaiser.aiagent.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Minimal Markdown renderer for v0.3. Handles the subset of Markdown
 * that LLMs commonly emit:
 *   - # / ## / ### headers
 *   - **bold** and *italic*
 *   - `inline code`
 *   - ```fenced code blocks```  (rendered on a darker surface)
 *   - - bullet lists
 *   - 1. numbered lists
 *   - > blockquotes
 *   - paragraphs separated by blank lines
 *
 * This is NOT a complete Markdown parser — it deliberately trades
 * correctness for simplicity. For richer rendering, swap in
 * `dev.jeziellago:compose-markdown` in v0.4.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier
) {
    val annotated = parseMarkdown(text)
    Text(text = annotated, modifier = modifier)
}

/**
 * Parses a subset of Markdown into an [AnnotatedString]. Code blocks
 * are handled separately by the caller (via [splitCodeBlocks]).
 */
private fun parseMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val blocks = splitCodeBlocks(text)
    for (block in blocks) {
        if (block.isCode) {
            // Inline code block as plain monospace text. The ChatScreen
            // wraps MarkdownText in a Surface that visually separates it.
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = androidx.compose.ui.graphics.Color(0xFF1E1E1E).copy(alpha = 0.08f)
                )
            ) {
                append(block.content)
            }
        } else {
            appendInlineMarkdown(block.content)
        }
    }
}

private data class MdBlock(val content: String, val isCode: Boolean)

private fun splitCodeBlocks(text: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val regex = Regex("""```(\w*)\n?([\s\S]*?)```""")
    var lastEnd = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > lastEnd) {
            result.add(MdBlock(text.substring(lastEnd, match.range.first), isCode = false))
        }
        result.add(MdBlock(match.groupValues[2], isCode = true))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        result.add(MdBlock(text.substring(lastEnd), isCode = false))
    }
    return result
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    val lines = text.split("\n")
    for ((i, line) in lines.withIndex()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp())) {
                    append(trimmed.removePrefix("### "))
                }
            }
            trimmed.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp())) {
                    append(trimmed.removePrefix("## "))
                }
            }
            trimmed.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp())) {
                    append(trimmed.removePrefix("# "))
                }
            }
            trimmed.startsWith("> ") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = androidx.compose.ui.graphics.Color.Gray)) {
                    append(trimmed.removePrefix("> "))
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("• ")
                appendFormatted(trimmed.removePrefix("- ").removePrefix("* "))
            }
            Regex("""^\d+\.\s""").containsMatchIn(trimmed) -> {
                appendFormatted(trimmed)
            }
            trimmed.isEmpty() -> { /* skip blank line */ }
            else -> appendFormatted(trimmed)
        }
        if (i < lines.lastIndex) append("\n")
    }
}

private fun AnnotatedString.Builder.appendFormatted(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end >= 0 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end >= 0) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = androidx.compose.ui.graphics.Color(0xFF1E1E1E).copy(alpha = 0.08f)
                        )
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
        }
        append(text[i])
        i++
    }
}

// Tiny helper to avoid importing sp() everywhere.
private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
private fun Float.sp() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
