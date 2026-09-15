package com.aprax.coderm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeEditor(
    content: String,
    language: String,
    fontSize: Int,
    wordWrap: Boolean,
    tabSize: Int,
    onChange: (String, Int, Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var field by remember { mutableStateOf(TextFieldValue(content, TextRange(content.length))) }
    val history = remember { mutableStateListOf(content) }
    var historyIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(content) {
        if (content != field.text) {
            field = TextFieldValue(content, TextRange(content.length))
            history.clear(); history.add(content); historyIndex = 0
        }
    }

    fun setText(next: String, selection: TextRange = TextRange(next.length)) {
        if (next == field.text) return
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history.add(next)
        historyIndex++
        if (history.size > 100) { history.removeAt(0); historyIndex-- }
        field = TextFieldValue(next, selection)
        onChange(next, selection.start, selection.end)
    }

    Column(modifier.fillMaxSize().background(Color(0xFF0A0D12))) {
        Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal)) {
            Column(Modifier.padding(top = 12.dp, bottom = 12.dp).width(44.dp).verticalScroll(vertical)) {
                val lines = field.text.count { it == '\n' } + 1
                repeat(lines) { index ->
                    val selected = index == field.text.substring(0, field.selection.start.coerceAtMost(field.text.length)).count { it == '\n' }
                    androidx.compose.material3.Text((index + 1).toString().padStart(4, ' '), color = if (selected) Color(0xFF8EBBFF) else Color(0xFF5D6877), fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, modifier = Modifier.fillMaxWidth().height((fontSize + 6).dp))
                }
            }
            BasicTextField(
                value = field,
                onValueChange = { new ->
                    val smart = smartEdit(field, new, tabSize)
                    setText(smart.text, smart.selection)
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp, color = Color(0xFFE8EDF5)),
                visualTransformation = SyntaxHighlightTransformation(language),
                softWrap = wordWrap,
                modifier = Modifier.fillMaxHeight().widthIn(min = if (wordWrap) 0.dp else 600.dp).verticalScroll(vertical).padding(12.dp),
                cursorBrush = Brush.linearGradient(listOf(Color(0xFF6EA8FF), Color(0xFF60E7F2))),
            )
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(enabled = historyIndex > 0, onClick = {
                historyIndex--
                val next = history[historyIndex]
                field = TextFieldValue(next, TextRange(next.length))
                onChange(next, next.length, next.length)
            }) { androidx.compose.material3.Text("Undo") }
            androidx.compose.material3.TextButton(enabled = historyIndex + 1 < history.size, onClick = {
                historyIndex++
                val next = history[historyIndex]
                field = TextFieldValue(next, TextRange(next.length))
                onChange(next, next.length, next.length)
            }) { androidx.compose.material3.Text("Redo") }
            androidx.compose.material3.Button(onClick = onSave) { androidx.compose.material3.Text("Save") }
        }
    }
}

private fun smartEdit(previous: TextFieldValue, next: TextFieldValue, tabSize: Int): TextFieldValue {
    val added = next.text.length - previous.text.length
    if (added != 1) return next
    val cursor = next.selection.start
    if (cursor <= 0) return next
    val inserted = next.text[cursor - 1]
    val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    pairs[inserted]?.let { closing ->
        val after = next.text.substring(cursor)
        if (after.firstOrNull() != closing) {
            val value = next.text.substring(0, cursor) + closing + after
            return TextFieldValue(value, TextRange(cursor))
        }
    }
    if (inserted == '\n') {
        val before = next.text.substring(0, cursor - 1)
        val previousLine = before.substringAfterLast('\n')
        val indent = previousLine.takeWhile { it == ' ' || it == '\t' }
        val extra = if (previousLine.trimEnd().endsWith('{') || previousLine.trimEnd().endsWith('[') || previousLine.trimEnd().endsWith('(')) " ".repeat(tabSize) else ""
        val insertion = indent + extra
        if (insertion.isNotEmpty()) {
            val value = next.text.substring(0, cursor) + insertion + next.text.substring(cursor)
            return TextFieldValue(value, TextRange(cursor + insertion.length))
        }
    }
    return next
}

private class SyntaxHighlightTransformation(private val language: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val builder = AnnotatedString.Builder(source)
        val keywordColor = Color(0xFF89B4FF)
        val stringColor = Color(0xFFA6E3A1)
        val commentColor = Color(0xFF7F8A9A)
        val numberColor = Color(0xFFF9CB8A)
        val keywords = when {
            language == "kotlin" -> listOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "in", "return", "private", "public", "package", "import", "suspend", "data", "sealed")
            language == "typescript" || language == "javascript" -> listOf("const", "let", "var", "function", "return", "class", "interface", "type", "import", "from", "export", "async", "await", "new", "extends")
            language == "python" -> listOf("def", "return", "class", "import", "from", "if", "else", "for", "while", "in", "async", "await", "True", "False", "None")
            language == "rust" -> listOf("fn", "let", "mut", "pub", "impl", "trait", "struct", "enum", "use", "mod", "match", "if", "else", "return")
            else -> listOf("if", "else", "for", "while", "return", "class", "import", "from", "function", "const", "let")
        }
        keywords.forEach { kw -> Regex("\\b${Regex.escape(kw)}\\b").findAll(source).forEach { m -> builder.addStyle(SpanStyle(color = keywordColor), m.range.first, m.range.last + 1) } }
        Regex("(?:\"(?:\\.|[^\"])*\"|'(?:\\.|[^'])*'|`(?:\\.|[^`])*`)").findAll(source).forEach { m -> builder.addStyle(SpanStyle(color = stringColor), m.range.first, m.range.last + 1) }
        Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|#[^\\n]*").findAll(source).forEach { m -> builder.addStyle(SpanStyle(color = commentColor), m.range.first, m.range.last + 1) }
        Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(source).forEach { m -> builder.addStyle(SpanStyle(color = numberColor), m.range.first, m.range.last + 1) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
