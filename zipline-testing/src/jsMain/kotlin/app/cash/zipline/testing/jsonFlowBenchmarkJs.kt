/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.testing

import app.cash.zipline.decodeFromStringFast
import kotlin.js.Date
import kotlin.math.roundToInt
import kotlin.time.measureTimedValue
import kotlinx.io.writeString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromDynamic
import kotlinx.serialization.json.io.decodeFromSource as decodeFromSourceIo

/** The %FlowJSON global builtin (native incremental JSON parser in Hermes,
 *  enabled via RuntimeConfig.EnableFlowJsonParser). */
private external val FlowJSON: dynamic

@Serializable
data class BenchSeller(
  val id: Int,
  val name: String,
  val rating: Double,
  val verified: Boolean,
)

@Serializable
data class BenchItem(
  val id: Int,
  val title: String,
  val description: String,
  val price: Double,
  val discountPercent: Int,
  val inStock: Boolean,
  val tags: List<String>,
  val seller: BenchSeller,
)

private val benchJson = Json

private val words = listOf(
  "sneakers", "jacket", "backpack", "watch", "phone", "laptop", "tablet",
  "headphones", "camera", "keyboard", "mouse", "monitor", "chair", "lamp",
)

private fun benchItem(i: Int) = BenchItem(
  id = i,
  title = "${words[i % words.size]} ${words[(i / words.size) % words.size]} #$i",
  description = "Description for item $i: ${words[i % words.size]} with discount and delivery",
  price = 199.99 + (i % 500) * 1.37,
  discountPercent = i % 70,
  inStock = i % 3 != 0,
  tags = listOf(words[i % words.size], words[(i + 5) % words.size], "sale", "new"),
  seller = BenchSeller(
    id = 1000 + i % 97,
    name = "Seller ${i % 97}",
    rating = 3.5 + (i % 15) * 0.1,
    verified = i % 2 == 0,
  ),
)

private val listSerializer = ListSerializer(BenchItem.serializer())

/** Generates a JSON array of [itemCount] objects (ASCII-only content). */
@JsExport
fun generateBenchJson(itemCount: Int): String {
  return benchJson.encodeToString(listSerializer, List(itemCount, ::benchItem))
}

/** Simulates network latency on a single thread (the JS engine has no async I/O). */
private fun busyWait(ms: Int) {
  if (ms <= 0) return
  val end = Date.now() + ms
  while (Date.now() < end) {
    // spin
  }
}

/** Simulated slow HTTP GET: downloads the body in [chunkSize] chunks with
 * [chunkDelayMs] of simulated latency per chunk, then returns the full body. */
private fun slowHttpGetBody(text: String, chunkSize: Int, chunkDelayMs: Int): Pair<String, Long> {
  val (body, download) = measureTimedValue {
    val sb = StringBuilder(text.length)
    var offset = 0
    while (offset < text.length) {
      busyWait(chunkDelayMs)
      val end = minOf(text.length, offset + chunkSize)
      sb.append(text, offset, end)
      offset = end
    }
    sb.toString()
  }
  return body to download.inWholeMilliseconds
}

private fun bench(name: String, repeats: Int, block: () -> List<BenchItem>): String {
  // Warmup + sanity.
  val items = block()
  val times = LongArray(repeats) {
    measureTimedValue { block() }.duration.inWholeMilliseconds
  }
  val min = times.min()
  val avg = times.average()
  return "$name: items=${items.size}, repeats=$repeats, min=${min} ms, avg=${(avg * 10).roundToInt() / 10.0} ms"
}

/** Today's fast path: Hermes native JSON.parse + decodeFromDynamic. */
@JsExport
fun benchDecodeFromStringFast(json: String, repeats: Int): String =
  bench("decodeFromStringFast", repeats) {
    benchJson.decodeFromStringFast(listSerializer, json)
  }

/** Pure-Kotlin baseline: kotlinx-serialization string lexer. */
@JsExport
fun benchDecodeFromStringKotlinx(json: String, repeats: Int): String =
  bench("decodeFromStringKotlinx", repeats) {
    benchJson.decodeFromString(listSerializer, json)
  }

/** Streaming baseline: kotlinx-serialization-json-io over a fully-buffered source. */
@OptIn(ExperimentalSerializationApi::class)
@JsExport
fun benchDecodeFromSourceIo(json: String, repeats: Int): String =
  bench("decodeFromSource(json-io, buffered)", repeats) {
    benchJson.decodeFromSourceIo(listSerializer, kotlinx.io.Buffer().apply { writeString(json) })
  }

/** FlowJSON whole-document mode: native incremental parse fed the full
 *  string at once, then decodeFromDynamic. Compare with decodeFromStringFast
 *  (native JSON.parse + decodeFromDynamic). */
@OptIn(ExperimentalSerializationApi::class)
@JsExport
fun benchFlowJsonWholeDoc(json: String, repeats: Int): String =
  bench("flowJsonWholeDoc+decodeFromDynamic", repeats) {
    val p = FlowJSON.createParser()
    val status = p.feed(json, true) as String
    if (status != "done") throw IllegalStateException("FlowJSON status: $status")
    benchJson.decodeFromDynamic(listSerializer, p.root())
  }

/** FlowJSON flow mode over 16 KB chunks: root-array elements stream through
 *  the callback and are decoded one by one — no full-document tree is built. */
@OptIn(ExperimentalSerializationApi::class)
@JsExport
fun benchFlowJsonFlow(json: String, repeats: Int): String =
  bench("flowJsonFlow+decodeFromDynamic", repeats) {
    val out = ArrayList<BenchItem>()
    val p = FlowJSON.createParser { el: dynamic ->
      out.add(benchJson.decodeFromDynamic(BenchItem.serializer(), el))
    }
    var offset = 0
    while (offset < json.length) {
      val end = minOf(json.length, offset + 16384)
      p.feed(json.substring(offset, end), end >= json.length)
      offset = end
    }
    out
  }

/** Download only, no decode — the pure network-latency cost of the body. */
@JsExport
fun benchDownloadOnly(
  json: String,
  chunkSize: Int,
  chunkDelayMs: Int,
  repeats: Int,
): String {
  val times = LongArray(repeats) {
    slowHttpGetBody(json, chunkSize, chunkDelayMs).second
  }
  val chunks = (json.length + chunkSize - 1) / chunkSize
  return "downloadOnly(chunk=$chunkSize,latency=${chunkDelayMs}ms): " +
    "chars=${json.length}, chunks=$chunks, min=${times.min()} ms, " +
    "avg=${(times.average() * 10).roundToInt() / 10.0} ms"
}

/**
 * Today's full flow: slow download to a complete string, then fast decode.
 * Download and parse are strictly sequential.
 */
@JsExport
fun benchDownloadThenDecodeFast(
  json: String,
  chunkSize: Int,
  chunkDelayMs: Int,
  repeats: Int,
): String {
  var downloadMs = 0L
  val result = bench("download+decodeFromStringFast(chunk=$chunkSize,latency=${chunkDelayMs}ms)", repeats) {
    val (body, dl) = slowHttpGetBody(json, chunkSize, chunkDelayMs)
    downloadMs = dl
    benchJson.decodeFromStringFast(listSerializer, body)
  }
  return "$result, downloadMs=$downloadMs"
}

/**
 * The target end state: flow parsing overlaps the download — each chunk is
 * fed to the native incremental parser as it arrives and elements are decoded
 * on the fly, so total time ≈ download time.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsExport
fun benchDownloadThenFlowJson(
  json: String,
  chunkSize: Int,
  chunkDelayMs: Int,
  repeats: Int,
): String {
  var downloadMs = 0L
  val result = bench("download+flowJson(chunk=$chunkSize,latency=${chunkDelayMs}ms)", repeats) {
    val out = ArrayList<BenchItem>()
    val p = FlowJSON.createParser { el: dynamic ->
      out.add(benchJson.decodeFromDynamic(BenchItem.serializer(), el))
    }
    val (_, dl) = measureTimedValue {
      var offset = 0
      while (offset < json.length) {
        busyWait(chunkDelayMs)
        val end = minOf(json.length, offset + chunkSize)
        p.feed(json.substring(offset, end), end >= json.length)
        offset = end
      }
    }
    downloadMs = dl.inWholeMilliseconds
    out
  }
  return "$result, downloadMs=$downloadMs"
}
