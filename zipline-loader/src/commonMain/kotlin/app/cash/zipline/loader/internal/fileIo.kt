/*
 * Copyright (C) 2026 Cash App
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
package app.cash.zipline.loader.internal

import okio.BufferedSink
import okio.ByteString
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

internal fun writeAtomically(
  fileSystem: FileSystem,
  dest: Path,
  writer: BufferedSink.() -> Unit,
) {
  dest.parent?.let { fileSystem.createDirectories(it) }
  val tmpPath = dest.parent?.let { it / "${dest.name}.tmp" }
  if (tmpPath == null) {
    fileSystem.write(dest) { writer() }
    return
  }
  fileSystem.write(tmpPath) { writer() }
  fileSystem.atomicMove(tmpPath, dest)
}

internal fun FileSystem.fileSha256(path: Path): ByteString {
  source(path).use { raw ->
    val hashing = HashingSource.sha256(raw)
    hashing.buffer().use { buffered ->
      buffered.readAll(blackholeSink())
    }
    return hashing.hash
  }
}
