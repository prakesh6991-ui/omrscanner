package com.omr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun loadBitmap(ctx: Context, uri: Uri): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
    var s = 1
    while (maxOf(b.outWidth, b.outHeight) / s > 3200) s *= 2
    val o = BitmapFactory.Options().apply { inSampleSize = s }
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
    val ori = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    val deg = when (ori) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    if (deg == 0f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}

fun score(e: Exam, out: ScanOut, name: String): ScanResult {
    var c = 0; var w = 0; var u = 0
    for (i in 0 until e.questions) {
        val k = e.answerKey.getOrNull(i) ?: continue
        val a = out.answers[i]
        when { a == -1 -> u++; a >= 0 && ('A' + a) == k -> c++; else -> w++ }
    }
    val enc = out.answers.joinToString("") { if (it == -1) "-" else if (it == -2) "*" else ('A' + it).toString() }
    return ScanResult(examId = e.id, rollNo = out.roll, name = name, correct = c, incorrect = w, unattempted = u,
        score = c * e.marks - w * e.negative, answers = enc)
}

@Composable
fun ExamPicker(exams: List<Exam>, sel: Exam?, onSel: (Long) -> Unit) {
    if (exams.isEmpty()) { Text("Create an exam in Sheet Builder first."); return }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        exams.forEach { e -> FilterChip(selected = e.id == sel?.id, onClick = { onSel(e.id) }, label = { Text(e.name) }) }
    }
}

@Composable
fun AnswerKeyScreen(exams: List<Exam>, exam: Exam?, onSel: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var key by remember(exam?.id) { mutableStateOf(exam?.answerKey ?: "") }
    var marks by remember(exam?.id) { mutableStateOf((exam?.marks ?: 1.0).toString()) }
    var neg by remember(exam?.id) { mutableStateOf((exam?.negative ?: 0.0).toString()) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExamPicker(exams, exam, onSel)
        if (exam != null) {
            val valid = "ABCDE".take(exam.options)
            Text("Type ${exam.questions} answers in order using $valid  (${key.length}/${exam.questions})")
            OutlinedTextField(key, { key = it.uppercase().filter { c -> c in valid }.take(exam.questions) },
                label = { Text("Answer key e.g. ABCDDABC") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(marks, { marks = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Marks per correct answer") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(neg, { neg = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Negative marks per wrong answer") }, modifier = Modifier.fillMaxWidth())
            Button({
                if (key.length != exam.questions) msg = "Key needs exactly ${exam.questions} letters."
                else scope.launch {
                    withContext(Dispatchers.IO) { dao.setKey(exam.id, key, marks.toDoubleOrNull() ?: 1.0, neg.toDoubleOrNull() ?: 0.0) }
                    msg = "Saved."
                }
            }) { Text("Save key") }
            if (msg.isNotEmpty()) Text(msg)
        }
    }
}

@Composable
fun ScanScreen(exams: List<Exam>, exam: Exam?, onSel: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<ScanResult?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val flow = remember(exam?.id) { if (exam != null) dao.results(exam.id) else flowOf(emptyList<ScanResult>()) }
    val saved by flow.collectAsState(emptyList())

    fun process(uri: Uri) {
        val e = exam ?: return
        scope.launch {
            status = "Scanning..."; res = null
            val out = withContext(Dispatchers.Default) { try { loadBitmap(ctx, uri)?.let { Scanner.scan(it, e) } } catch (t: Throwable) { null } }
            if (out == null) { status = "Could not find the 4 corner squares. Keep the whole sheet in view, flat and well lit."; return@launch }
            val st = withContext(Dispatchers.IO) { dao.studentsNow() }.find { it.rollNo.trimStart('0') == out.roll.trimStart('0') }
            res = score(e, out, st?.name ?: "(not found)")
            status = "Done"
        }
    }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photoUri?.let { process(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) process(u) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExamPicker(exams, exam, onSel)
        val ready = exam != null && exam.answerKey.length == exam.questions
        if (exam != null && !ready) Text("Save the answer key for this exam first (Answer Key tab).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ready, onClick = {
                val f = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                val u = FileProvider.getUriForFile(ctx, "com.omr.app.fileprovider", f)
                photoUri = u; cam.launch(u)
            }) { Text("Take photo") }
            OutlinedButton(enabled = ready, onClick = { pick.launch("image/*") }) { Text("Pick image") }
        }
        if (status.isNotEmpty()) Text(status)
        res?.let { r ->
            Text("Roll: ${r.rollNo}   ${r.name}", style = MaterialTheme.typography.titleMedium)
            Text("Correct ${r.correct}   Incorrect ${r.incorrect}   Unattempted ${r.unattempted}")
            Text("Score: ${r.score}")
            Button({ scope.launch { withContext(Dispatchers.IO) { dao.addResult(r) }; res = null; status = "Saved." } }) { Text("Save result") }
        }
        Text("Saved results: ${saved.size}", style = MaterialTheme.typography.titleMedium)
        LazyColumn { items(saved) { r -> Text("${r.rollNo}  ${r.name}: ${r.score}", Modifier.padding(vertical = 4.dp)) } }
    }
}
