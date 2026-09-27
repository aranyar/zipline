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
package app.cash.zipline.loader.internal.receiver

import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import app.cash.zipline.loader.HbcRange
import app.cash.zipline.loader.ZiplineFile.Companion.toZiplineFile
import app.cash.zipline.loader.hbcRange
import app.cash.zipline.loader.internal.multiplatformLoadJsModule
import app.cash.zipline.loader.internal.multiplatformLoadJsModuleMapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Load the [ZiplineFile] into a Zipline runtime instance.
 */
internal class ZiplineLoadReceiver(
  private val zipline: Zipline,
  private val eventListener: EventListener,
) : Receiver {
  override suspend fun receive(byteString: ByteString, id: String, sha256: ByteString) {
    val startValue = eventListener.moduleLoadStart(zipline, id)
    try {
      if (byteString.startsWith(ZIPLINE_MAGIC)) {
        zipline.multiplatformLoadJsModule(byteString.toZiplineFile().jsBytecode.toByteArray(), id)
      } else {
        // Source mode (CDP debugging): the slot carries raw JavaScript to be
        // compiled at runtime instead of Hermes bytecode.
        zipline.loadJsModule(byteString.utf8(), id)
      }
    } finally {
      eventListener.moduleLoadEnd(zipline, id, startValue)
    }
  }

  override suspend fun receiveFile(path: Path, id: String, sha256: ByteString) {
    val range: HbcRange? = withContext(Dispatchers.IO) {
      FileSystem.SYSTEM.hbcRange(path)
    }
    if (range == null) {
      val byteString = withContext(Dispatchers.IO) {
        FileSystem.SYSTEM.read(path) { readByteString() }
      }
      receive(byteString, id, sha256)
      return
    }
    val startValue = eventListener.moduleLoadStart(zipline, id)
    try {
      zipline.multiplatformLoadJsModuleMapped(
        path.toString(),
        range.offset.toInt(),
        range.size.toInt(),
        id,
      )
    } finally {
      eventListener.moduleLoadEnd(zipline, id, startValue)
    }
  }

  private companion object {
    private val ZIPLINE_MAGIC = "ZIPLINE\u0000".encodeUtf8()
  }
}
