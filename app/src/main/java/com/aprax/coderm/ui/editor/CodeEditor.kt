package com.aprax.coderm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeEditor(
    content: String,
    language: String,
    fontSize: Int,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Row(modifier.fillMaxSize().background(Color(0xFF0A0D12))) {
        val lines = remember(content) { content.count { it == '\n' }.coerceAtLeast(0) + 1 }
        Column(Modifier.padding(top = 14.dp, bottom = 14.dp).width(46.dp).verticalScroll(vertical)) {
            repeat(lines) { i ->
                androidx.compose.material3.Text((i + 1).toString().padStart(4, ' '), color = Color(0xFF5D6877), fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, modifier = Modifier.fillMaxWidth())
            }
        }
        BasicTextField(
            value = content,
            onValueChange = onChange,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp, color = Color(0xFFE8EDF5)),
            modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontal).verticalScroll(vertical).padding(14.dp),
            decorationBox = { inner -> inner() }
        )
        // The annotated preview is intentionally not drawn over the editing surface; BasicTextField preserves native selection and IME behavior.
        // Syntax classification is reused by search/diagnostic layers and can be promoted to a tokenized editor in P1.
    }
}

private fun highlight(text: String, language: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    val keywordColor = Color(0xFF89B4FF)
    val stringColor = Color(0xFFA6E3A1)
    val commentColor = Color(0xFF7F8A9A)
    val keywords = when {
        language.contains("kotlin") -> listOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "in", "return", "private", "public", "package", "import", "suspend")
        language.contains("typescript") || language.contains("javascript") -> listOf("const", "let", "var", "function", "return", "class", "interface", "type", "import", "from", "export", "async", "await")
        else -> listOf("if", "else", "for", "while", "return", "class", "import", "from", "def", "fn", "let", "pub")
    }
    for (kw in keywords) Regex("\\b${Regex.escape(kw)}\\b").findAll(text).forEach { builder.addStyle(SpanStyle(color = keywordColor), it.range.first, it.range.last + 1) }
    Regex("(['\"]).*?\\1").findAll(text).forEach { builder.addStyle(SpanStyle(color = stringColor), it.range.first, it.range.last + 1) }
    Regex("//.*|#.*").findAll(text).forEach { builder.addStyle(SpanStyle(color = commentColor), it.range.first, it.range.last + 1) }
    return builder.toAnnotatedString()
}
