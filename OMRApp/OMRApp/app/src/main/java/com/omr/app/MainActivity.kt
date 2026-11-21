package com.omr.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

// ---------- Database ----------
@Entity data class Student(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val rollNo: String)
@Entity data class Exam(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val questions: Int, val options: Int, val rollDigits: Int, val answerKey: String = "", val marks: Double = 1.0, val negative: Double = 0.0)

@Dao interface OmrDao {
    @Query("SELECT * FROM Student ORDER BY rollNo") fun students(): Flow<List<Student>>
    @Insert suspend fun addStudents(s: List<Student>)
    @Query("DELETE FROM Student") suspend fun clearStudents()
    @Insert suspend fun addExam(e: Exam): Long
    @Query("SELECT * FROM Exam ORDER BY id DESC") fun exams(): Flow<List<Exam>>
    @Query("UPDATE Exam SET answerKey=:k, marks=:m, negative=:n WHERE id=:id") suspend fun setKey(id: Long, k: String, m: Double, n: Double)
    @Insert suspend fun addResult(r: ScanResult)
    @Query("SELECT * FROM ScanResult WHERE examId=:id ORDER BY id DESC") fun results(id: Long): Flow<List<ScanResult>>
    @Query("SELECT * FROM Student") suspend fun studentsNow(): List<Student>
}

@Database(entities = [Student::class, Exam::class, ScanResult::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): OmrDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "omr.db").fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}

// ---------- Sheet layout (points on A4 595x842). Reused by the scanner in stage 2. ----------
data class Bubble(val kind: Char, val idx: Int, val opt: Int, val x: Float, val y: Float)

const val PAGE_W = 595; const val PAGE_H = 842; const val MARK = 20f
val MARKERS = listOf(30f to 30f, 545f to 30f, 30f to 792f, 545f to 792f) // top-left x,y of each square

fun layout(q: Int, opts: Int, rollDigits: Int): List<Bubble> {
    val l = mutableListOf<Bubble>()
    for (c in 0 until rollDigits) for (d in 0..9) l += Bubble('R', c, d, 70f + c * 28f, 140f + d * 16f)
    for (i in 0 until q) for (o in 0 until opts) l += Bubble('Q', i, o, 80f + (i / 25) * 130f + o * 20f, 350f + (i % 25) * 17f)
    return l
}

fun writeSheetPdf(e: Exam, out: OutputStream) {
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
    val c = page.canvas
    val fill = Paint().apply { color = android.graphics.Color.BLACK }
    val line = Paint().apply { color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 0.8f }
    val txt = Paint().apply { color = android.graphics.Color.BLACK; textSize = 9f; isAntiAlias = true }
    MARKERS.forEach { (x, y) -> c.drawRect(x, y, x + MARK, y + MARK, fill) }
    txt.textSize = 16f; c.drawText(e.name, 70f, 75f, txt)
    txt.textSize = 10f; c.drawText("Name: ______________________", 300f, 75f, txt)
    c.drawText("Roll No (fill bubbles)", 70f, 105f, txt)
    txt.textSize = 8f
    for (b in layout(e.questions, e.options, e.rollDigits)) {
        c.drawCircle(b.x, b.y, 5f, line)
        if (b.kind == 'R') { if (b.idx == 0) c.drawText("${b.opt}", b.x - 22f, b.y + 3f, txt) }
        else {
            if (b.opt == 0) c.drawText("${b.idx + 1}", b.x - 26f, b.y + 3f, txt)
            c.drawText(('A' + b.opt).toString(), b.x - 2.5f, b.y + 2.5f, txt.apply { textSize = 6f }.also { })
            txt.textSize = 8f
        }
    }
    doc.finishPage(page); doc.writeTo(out); doc.close()
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.opencv.android.OpenCVLoader.initLocal()
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val exams by dao.exams().collectAsState(emptyList())
    var sel by remember { mutableStateOf<Long?>(null) }
    val exam = exams.find { it.id == sel } ?: exams.firstOrNull()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Students", "Sheet Builder", "Answer Key", "Scan").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> StudentsScreen()
            1 -> SheetScreen()
            2 -> AnswerKeyScreen(exams, exam) { sel = it }
            else -> ScanScreen(exams, exam) { sel = it }
        }
    }
}

@Composable
fun StudentsScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val students by dao.students().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val rows = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readLines()
                .map { it.split(",").map { s -> s.trim().trim('"') } }
                .filter { it.size >= 2 && it[0].isNotEmpty() && !it[0].lowercase().contains("roll") }
                .map { Student(rollNo = it[0], name = it[1]) }
            dao.addStudents(rows)
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text("${students.size} students", style = MaterialTheme.typography.titleMedium)
        Text("CSV format: roll,name (one per line)", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ picker.launch(arrayOf("*/*")) }) { Text("Import CSV") }
            OutlinedButton({ scope.launch(Dispatchers.IO) { dao.clearStudents() } }) { Text("Clear all") }
        }
        LazyColumn { items(students) { s -> Text("${s.rollNo}  -  ${s.name}", Modifier.padding(vertical = 4.dp)) } }
    }
}

@Composable
fun SheetScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val exams by dao.exams().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Test 1") }
    var q by remember { mutableStateOf("50") }
    var opts by remember { mutableStateOf("4") }
    var digits by remember { mutableStateOf("4") }
    var pending by remember { mutableStateOf<Exam?>(null) }
    var msg by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val e = pending
        if (uri != null && e != null) scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)!!.use { writeSheetPdf(e, it) }
                dao.addExam(e)
            }
            msg = "Sheet saved. Print at 100% scale (not 'fit to page')."
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Exam name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(q, { q = it.filter(Char::isDigit) }, label = { Text("Questions (1-100)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(opts, { opts = it.filter(Char::isDigit) }, label = { Text("Options per question (2-5)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(digits, { digits = it.filter(Char::isDigit) }, label = { Text("Roll number digits (1-6)") }, modifier = Modifier.fillMaxWidth())
        Button({
            val nq = q.toIntOrNull() ?: 0; val no = opts.toIntOrNull() ?: 0; val nd = digits.toIntOrNull() ?: 0
            if (nq !in 1..100 || no !in 2..5 || nd !in 1..6) msg = "Check the numbers entered."
            else { pending = Exam(name = name.ifBlank { "Exam" }, questions = nq, options = no, rollDigits = nd); saver.launch("${name.ifBlank { "omr" }}.pdf") }
        }) { Text("Create OMR sheet PDF") }
        if (msg.isNotEmpty()) Text(msg)
        Text("Saved exams", style = MaterialTheme.typography.titleMedium)
        LazyColumn { items(exams) { e -> Text("${e.name}: ${e.questions} Q, ${e.options} options", Modifier.padding(vertical = 4.dp)) } }
    }
}
