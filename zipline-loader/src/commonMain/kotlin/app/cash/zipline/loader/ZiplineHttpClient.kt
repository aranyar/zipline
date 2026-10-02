/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.zipline.loader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import app.cash.zipline.loader.internal.writeAtomically
import okio.ByteString
import okio.FileSystem
import okio.Path
import okio.SYSTEM

abstract class ZiplineHttpClient {
  /**
   * When true, [ZiplineLoader] downloads a module with [downloadToFile].
   * When false, it downloads the module into memory with [download].
   */
  open val isFileDownloadEnabled: Boolean = true

  abstract suspend fun download(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): ByteString

  /**
   * Streams [url] onto [dest], replacing it atomically (write `dest.tmp` then
   * rename). Returns [dest]. The default implementation downloads into RAM
   * then writes; platform clients override to sink the HTTP body directly.
   */
  open suspend fun downloadToFile(
    url: String,
    requestHeaders: List<Pair<String, String>>,
    dest: Path,
  ): Path {
    val bytes = download(url, requestHeaders)
    withContext(Dispatchers.IO) {
      writeAtomically(FileSystem.SYSTEM, dest) { write(bytes) }
    }
    return dest
  }

  /**
   * Opens a receive-only web socket to [url], and returns a flow that emits each message pushed by
   * the server.
   *
   * This is not a general-purpose web socket API, and serves only the needs of [ZiplineLoader]'s
   * code update signaling for development. For example, this does not expose HTTP response headers,
   * binary messages, open events, or close events.
   *
   * The flow terminates when the web socket is closed. This will be immediately if the web socket
   * cannot be established, after a graceful shutdown, or after an abrupt disconnection. The close
   * reason is not exposed in this API.
   *
   * The default implementation returns an empty flow.
   */
  open suspend fun openDevelopmentServerWebSocket(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): Flow<String> = flowOf()
}
