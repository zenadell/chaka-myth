package com.chakamyth.hands

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading the screen the way a person does — off the pixels.
 *
 * Everything before this searched the accessibility tree, which is a copy of
 * the screen that the system and the app get to curate. Rows can be absent from
 * it while plainly drawn: "Wireless debugging" sits in the middle of Developer
 * options with its switch showing, and no combination of flags, windows,
 * visibility rules or platform search calls would surface it. Two days went
 * into that one row.
 *
 * Nothing can hide a row from the pixels it draws. OCR sees exactly what the
 * owner sees, and it returns real bounding boxes rather than a model's estimate
 * of where something might be — which is also the fix for tapping half a row
 * away and landing in the gap between two controls.
 *
 * On-device, offline, free, and roughly a tenth of a second. No API call, no
 * second app, no shell, nothing for a user to set up.
 */
object ChakaOcr {

  private const val TAG = "ChakaOcr"

  private val recognizer by lazy {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  }

  /** One line of text on screen, with where it actually is. */
  data class Found(
    val text: String,
    val box: Rect,
    /** How much of the query this line accounts for, 0..1. */
    val score: Double
  )

  /**
   * Every line of text on the bitmap, with pixel boxes. Blocking, because every
   * caller is already on a worker thread and the alternative is threading a
   * callback through four layers of guard.
   */
  fun readLines(bmp: Bitmap, timeoutMs: Long = 4000): List<Found> {
    val out = ArrayList<Found>()
    val lock = Object()
    var done = false
    runCatching {
      recognizer.process(InputImage.fromBitmap(bmp, 0))
        .addOnSuccessListener { result ->
          for (block in result.textBlocks) {
            for (line in block.lines) {
              val b = line.boundingBox ?: continue
              val t = line.text?.trim().orEmpty()
              if (t.isNotEmpty()) out.add(Found(t, b, 0.0))
            }
          }
          synchronized(lock) { done = true; lock.notifyAll() }
        }
        .addOnFailureListener { e ->
          Log.e(TAG, "ocr failed: ${e.message}")
          synchronized(lock) { done = true; lock.notifyAll() }
        }
    }.onFailure {
      Log.e(TAG, "ocr threw: ${it.message}")
      return emptyList()
    }
    synchronized(lock) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!done && System.currentTimeMillis() < deadline) runCatching { lock.wait(200) }
    }
    return out
  }

  /**
   * Finds [query] among the lines. Matching is deliberately forgiving about
   * everything except the words themselves: OCR mangles case and punctuation,
   * and Settings rows wrap, so "Wireless debugging" can arrive as its own line
   * or glued to the summary beneath it.
   *
   * All content words must be present. That rule was learned the hard way —
   * scoring on a single long word matched the section heading "Debugging" and
   * sent her to tap a title, and being clever about it wasted a whole evening.
   */
  fun find(lines: List<Found>, query: String): Found? {
    val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
    if (words.isEmpty() || lines.isEmpty()) return null

    var best: Found? = null
    var bestScore = 0.0
    for (l in lines) {
      val hay = l.text.lowercase()
      val hits = words.count { hay.contains(it) }
      if (hits < words.size) continue
      // Prefer the tightest match: a line that is mostly the thing we asked for
      // beats one that merely contains it inside a longer sentence.
      val score = words.sumOf { it.length }.toDouble() / hay.length.coerceAtLeast(1)
      if (score > bestScore) { bestScore = score; best = l.copy(score = score) }
    }
    return best
  }

  /**
   * Every line matching the query, not just the best one.
   *
   * Ambiguity is information. "Debugging" on Developer options matches USB
   * debugging, Wireless debugging and Revoke USB debugging authorisations —
   * three different settings, one of which turns on remote access to the phone.
   * Picking the first and proceeding confidently is how an assistant does the
   * wrong irreversible thing, so the caller is told there were several and can
   * ask instead of guessing.
   */
  fun findAll(lines: List<Found>, query: String): List<Found> {
    val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
    if (words.isEmpty() || lines.isEmpty()) return emptyList()
    return lines
      .filter { l -> val hay = l.text.lowercase(); words.all { hay.contains(it) } }
      .distinctBy { it.text.lowercase().trim() }
  }

  /** For the log and for handing back to her when a search fails. */
  fun asJson(lines: List<Found>, limit: Int = 60): JSONArray {
    val arr = JSONArray()
    for (l in lines.take(limit)) {
      arr.put(
        JSONObject()
          .put("text", l.text.take(60))
          .put("cx", l.box.centerX())
          .put("cy", l.box.centerY())
          .put("x1", l.box.left).put("y1", l.box.top)
          .put("x2", l.box.right).put("y2", l.box.bottom)
      )
    }
    return arr
  }
}
