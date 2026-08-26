/*
 * Copyright (C) 2022 Block, Inc.
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

public actual fun <T> Json.decodeFromStringFast(deserializer: DeserializationStrategy<T>, string: String): T = decodeFromString(deserializer, string)

public actual fun <T> Json.encodeToStringFast(serializer: KSerializer<T>, value: T): String = encodeToString(serializer, value)

public actual val isFlowJsonAvailable: Boolean
  get() = false

public actual suspend fun <T> Json.decodeFromFlowJson(
  deserializer: DeserializationStrategy<T>,
  chunks: Flow<String>,
): T = decodeFromString(deserializer, buildString { chunks.collect { append(it) } })

public actual fun <T> Json.decodeListFromFlowJson(
  elementSerializer: KSerializer<T>,
  chunks: Flow<String>,
): Flow<T> = flow {
  val items = decodeFromString(
    ListSerializer(elementSerializer),
    buildString { chunks.collect { append(it) } },
  )
  for (item in items) emit(item)
}
