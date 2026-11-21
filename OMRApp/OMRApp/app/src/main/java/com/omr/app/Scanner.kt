package com.omr.app

import android.graphics.Bitmap
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

@Entity data class ScanResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val examId: Long, val rollNo: String, val name: String,
    val correct: Int, val incorrect: Int, val unattempted: Int, val score: Double, val answers: String
)

class ScanOut(val roll: String, val answers: IntArray) // answer: option index, -1 blank, -2 multiple marks

object Scanner {
    private const val S = 2.0          // warped pixels per sheet point
    private const val MARKED = 0.5     // fill fraction above which a bubble counts as marked

    fun scan(bmp: Bitmap, e: Exam): ScanOut? {
        val src = Mat(); Utils.bitmapToMat(bmp, src)
        val gray = Mat(); Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val k = 2200.0 / max(gray.cols(), gray.rows())
        val g = Mat()
        if (k < 1) Imgproc.resize(gray, g, Size(), k, k, Imgproc.INTER_AREA) else gray.copyTo(g)

        // 1. find the 4 corner squares
        val blur = Mat(); Imgproc.GaussianBlur(g, blur, Size(5.0, 5.0), 0.0)
        val bw = Mat(); Imgproc.threshold(blur, bw, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bw, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val md = max(g.cols(), g.rows()).toDouble()
        val cands = ArrayList<Point>()
        for (c in contours) {
            val r = Imgproc.boundingRect(c)
            if (r.width < md * 0.013 || r.width > md * 0.07 || r.height < md * 0.013 || r.height > md * 0.07) continue
            val ar = r.width.toDouble() / r.height
            if (ar < 0.75 || ar > 1.33) continue
            if (Imgproc.contourArea(c) / (r.width.toDouble() * r.height) < 0.85) continue
            cands.add(Point(r.x + r.width / 2.0, r.y + r.height / 2.0))
        }
        if (cands.size < 4) return null
        val tl = cands.minBy { it.x + it.y }; val br = cands.maxBy { it.x + it.y }
        val tr = cands.maxBy { it.x - it.y }; val bl = cands.minBy { it.x - it.y }
        if (setOf(tl, tr, bl, br).size < 4) return null

        // 2. flatten the sheet to a known size
        val m = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(tl, tr, bl, br),
            MatOfPoint2f(Point(40 * S, 40 * S), Point(555 * S, 40 * S), Point(40 * S, 802 * S), Point(555 * S, 802 * S))
        )
        val warped = Mat(); Imgproc.warpPerspective(g, warped, m, Size(PAGE_W * S, PAGE_H * S))
        Imgproc.GaussianBlur(warped, warped, Size(3.0, 3.0), 0.0)
        val bin = Mat()
        Imgproc.adaptiveThreshold(warped, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 41, 15.0)
        val w = bin.cols(); val h = bin.rows()
        val data = ByteArray(w * h); bin.get(0, 0, data)

        fun frac(cx: Float, cy: Float): Double {
            val x0 = cx * S; val y0 = cy * S; val rr = 3.5 * S
            var on = 0; var tot = 0
            for (yy in (y0 - rr).toInt()..(y0 + rr).toInt()) for (xx in (x0 - rr).toInt()..(x0 + rr).toInt()) {
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val dx = xx - x0; val dy = yy - y0
                if (dx * dx + dy * dy > rr * rr) continue
                tot++; if (data[yy * w + xx].toInt() != 0) on++
            }
            return if (tot == 0) 0.0 else on.toDouble() / tot
        }

        // 3. read every bubble
        val q = Array(e.questions) { DoubleArray(e.options) }
        val rl = Array(e.rollDigits) { DoubleArray(10) }
        for (b in layout(e.questions, e.options, e.rollDigits)) {
            val f = frac(b.x, b.y)
            if (b.kind == 'R') rl[b.idx][b.opt] = f else q[b.idx][b.opt] = f
        }
        val ans = IntArray(e.questions) { i ->
            val mk = q[i].indices.filter { q[i][it] > MARKED }
            when (mk.size) { 0 -> -1; 1 -> mk[0]; else -> -2 }
        }
        val roll = rl.joinToString("") { d ->
            val mk = d.indices.filter { d[it] > MARKED }
            if (mk.size == 1) mk[0].toString() else "?"
        }
        return ScanOut(roll, ans)
    }
}
