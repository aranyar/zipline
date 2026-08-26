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
package app.cash.zipline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromDynamic

/** The %FlowJSON global builtin (native incremental JSON parser in Hermes). */
private external val FlowJSON: dynamic

public actual val isFlowJsonAvailable: Boolean
  get() = js("typeof FlowJSON !== 'undefined'") as Boolean

/**
 * Typed wrapper over the %FlowJSON parser object. Create via
 * [createFlowJsonParser].
 */
public class FlowJsonParser internal constructor(
  private val delegate: dynamic,
) {
  /**
   * Feed the next chunk of the document. Pass [isFinal] = true with the last
   * chunk.
   * @return true when the document is complete.
   * @throws Throwable a SyntaxError (JS exception) on malformed input.
   */
  public fun feed(chunk: String, isFinal: Boolean = false): Boolean =
    (delegate.feed(chunk, isFinal) as String) == "done"

  /**
   * The parsed document (whole-document mode only; the parser must be done).
   */
  public fun root(): dynamic = delegate.root()
}

/**
 * Creates an incremental JSON parser.
 *
 *  - without [onElement]: whole-document mode — feed chunks, then read the
 *    result with [FlowJsonParser.root];
 *  - with [onElement]: flow mode — the document must be a JSON array and
 *    [onElement] is invoked synchronously with each completed element of the
 *    root array as it is parsed (no full-document tree is built). A throw
 *    from [onElement] propagates out of [FlowJsonParser.feed].
 *
 * @throws IllegalStateException if the engine has no %FlowJSON builtin.
 */
public fun createFlowJsonParser(onElement: ((dynamic) -> Unit)? = null): FlowJsonParser {
  if (!isFlowJsonAvailable) {
    throw IllegalStateException(
      "The %FlowJSON builtin is not available in this engine " +
        "(requires Hermes with RuntimeConfig.EnableFlowJsonParser)",
    )
  }
  return FlowJsonParser(
    if (onElement != null) FlowJSON.createParser(onElement) else FlowJSON.createParser(),
  )
}

@OptIn(ExperimentalSerializationApi::class) // Zipline must track changes to decodeFromDynamic.
public actual suspend fun <T> Json.decodeFromFlowJson(
  deserializer: DeserializationStrategy<T>,
  chunks: Flow<String>,
): T {
  if (!isFlowJsonAvailable) {
    return decodeFromString(deserializer, chunks.joinToString())
  }
  val parser = createFlowJsonParser()
  chunks.collect { parser.feed(it) }
  parser.feed("", isFinal = true)
  return decodeFromDynamic(deserializer, parser.root())
}

@OptIn(ExperimentalSerializationApi::class) // Zipline must track changes to decodeFromDynamic.
public actual fun <T> Json.decodeListFromFlowJson(
  elementSerializer: KSerializer<T>,
  chunks: Flow<String>,
): Flow<T> {
  if (!isFlowJsonAvailable) {
    return flow {
      // Fallback: no incremental parse — decode the joined body, then emit.
      val items = decodeFromString(
        ListSerializer(elementSerializer),
        chunks.joinToString(),
      )
      for (item in items) emit(item)
    }
  }
  return flow {
    val queue = ArrayDeque<T>()
    val parser = createFlowJsonParser { element ->
      queue.addLast(decodeFromDynamic(elementSerializer, element))
    }
    chunks.collect { chunk ->
      parser.feed(chunk)
      while (queue.isNotEmpty()) emit(queue.removeFirst())
    }
    parser.feed("", isFinal = true)
    while (queue.isNotEmpty()) emit(queue.removeFirst())
  }
}

private suspend fun Flow<String>.joinToString(): String {
  val sb = StringBuilder()
  collect { sb.append(it) }
  return sb.toString()
}
