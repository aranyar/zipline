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
package app.cash.zipline.loader

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.downloadTaskWithRequest

@OptIn(ExperimentalForeignApi::class)
internal class URLSessionZiplineHttpClient(
  private val urlSession: NSURLSession,
) : ZiplineHttpClient() {
  override suspend fun download(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): ByteString {
    val nsUrl = NSURL(string = url)
    return suspendCancellableCoroutine { continuation: CancellableContinuation<ByteString> ->
      val completionHandler = CompletionHandler(url, continuation)

      val task = urlSession.dataTaskWithRequest(
        request = NSMutableURLRequest(
          uRL = nsUrl,
          cachePolicy = NSURLRequestUseProtocolCachePolicy,
          timeoutInterval = 60.0,
        ).apply {
          for ((name, value) in requestHeaders) {
            addValue(value = value, forHTTPHeaderField = name)
          }
        },
        completionHandler = completionHandler::invoke,
      )

      continuation.invokeOnCancellation {
        task.cancel()
      }

      task.resume()
    }
  }

  override suspend fun downloadToFile(
    url: String,
    requestHeaders: List<Pair<String, String>>,
    dest: Path,
  ): Path {
    val nsUrl = NSURL(string = url)
    return suspendCancellableCoroutine { continuation: CancellableContinuation<Path> ->
      val request = NSMutableURLRequest(
        uRL = nsUrl,
        cachePolicy = NSURLRequestUseProtocolCachePolicy,
        timeoutInterval = 60.0,
      ).apply {
        for ((name, value) in requestHeaders) {
          addValue(value = value, forHTTPHeaderField = name)
        }
      }

      val task = urlSession.downloadTaskWithRequest(request) { location, response, error ->
        when {
          error != null -> {
            continuation.resumeWithException(IOException(error.description))
          }
          response !is NSHTTPURLResponse || location == null -> {
            continuation.resumeWithException(IOException("unexpected response: $response"))
          }
          response.statusCode !in 200 until 300 -> {
            continuation.resumeWithException(
              IOException("failed to fetch $url: ${response.statusCode}"),
            )
          }
          else -> {
            try {
              dest.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
              val destUrl = NSURL.fileURLWithPath(dest.toString())
              val fileManager = NSFileManager.defaultManager
              if (fileManager.fileExistsAtPath(dest.toString())) {
                fileManager.removeItemAtURL(destUrl, null)
              }
              val moved = fileManager.moveItemAtURL(location, destUrl, null)
              if (!moved) {
                throw IOException("failed to move download for $url to $dest")
              }
              continuation.resume(dest)
            } catch (e: Exception) {
              continuation.resumeWithException(
                e as? IOException ?: IOException(e.message),
              )
            }
          }
        }
      }

      continuation.invokeOnCancellation {
        task.cancel()
      }

      task.resume()
    }
  }
}

private class CompletionHandler(
  private val url: String,
  private val continuation: CancellableContinuation<ByteString>,
) {
  fun invoke(data: NSData?, response: NSURLResponse?, error: NSError?) {
    if (error != null) {
      continuation.resumeWithException(IOException(error.description))
      return
    }

    if (response !is NSHTTPURLResponse || data == null) {
      continuation.resumeWithException(IOException("unexpected response: $response"))
      return
    }

    if (response.statusCode !in 200 until 300) {
      continuation.resumeWithException(
        IOException("failed to fetch $url: ${response.statusCode}"),
      )
      return
    }

    continuation.resume(data.toByteString())
  }
}
