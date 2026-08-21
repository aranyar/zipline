/*
 * Copyright (C) 2024 Square, Inc.
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
package app.cash.zipline.bytecode

import app.cash.zipline.QuickJs
import org.junit.Test
import java.io.File

class LoadRealBytecodeTest {
  @Test fun loadWbRecommendationsWithStdlib() {
    val ziplineDir = File("/Users/efremov.andrey65/wb/compose-live/wb/live/build/zipline/Production")
    if (!ziplineDir.exists()) return

    val stdlibFile = File(ziplineDir, "kotlin-kotlin-stdlib.zipline")
    val recsFile = File(ziplineDir, "wb-recommendations.zipline")
    if (!stdlibFile.exists() || !recsFile.exists()) {
      println("Skipping - missing stdlib or recommendations files")
      return
    }

    val quickJs = QuickJs.create()
    try {
      val stdlibBytes = stripZiplineHeader(stdlibFile.readBytes())
      val recsBytes = stripZiplineHeader(recsFile.readBytes())
      println("Loading stdlib (${stdlibBytes.size} bytes)...")
      quickJs.execute(stdlibBytes)
      println("Stdlib loaded!")
      println("Loading recommendations (${recsBytes.size} bytes)...")
      quickJs.execute(recsBytes)
      println("Recommendations loaded!")
    } catch (e: Exception) {
      println("Failed: ${e.message}")
      throw e
    } finally {
      quickJs.close()
    }
  }

  private fun stripZiplineHeader(bytes: ByteArray): ByteArray {
    // magic(8) + version(4) + section header(4) + section length(4)
    return bytes.copyOfRange(20, bytes.size)
  }
}
