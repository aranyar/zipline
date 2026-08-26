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
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Incremental (flow) JSON parsing via the %FlowJSON builtin in Hermes
 * internals (FlowJSONParser). The engine parses chunks as they arrive,
 * keeping JSON.parse-level speed while allowing the parse to overlap I/O.
 *
 * Only available when the runtime is Hermes with
 * `RuntimeConfig.EnableFlowJsonParser` (Zipline's HermesCore config enables
 * it); check [isFlowJsonAvailable] before use.
 */
public expect val isFlowJsonAvailable: Boolean

/**
 * Decodes a whole JSON document delivered as string [chunks], parsing each
 * chunk as it is collected.
 *
 * On engines without the %FlowJSON builtin the chunks are joined and decoded
 * with [Json.decodeFromString].
 */
public expect suspend fun <T> Json.decodeFromFlowJson(
  deserializer: DeserializationStrategy<T>,
  chunks: Flow<String>,
): T

/**
 * Decodes a JSON array delivered as string [chunks], emitting each element
 * as soon as it is parsed — before the rest of the document has arrived.
 *
 * On engines without the %FlowJSON builtin the chunks are joined, decoded as
 * a whole and then emitted element by element.
 */
public expect fun <T> Json.decodeListFromFlowJson(
  elementSerializer: KSerializer<T>,
  chunks: Flow<String>,
): Flow<T>
