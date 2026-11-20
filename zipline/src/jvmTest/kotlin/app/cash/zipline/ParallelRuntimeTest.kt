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
package app.cash.zipline

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several JVM threads each create a runtime. On Windows that used to crash:
 * the stack-bounds check saw only a few dozen kilobytes of a HotSpot stack.
 */
class ParallelRuntimeTest {
  @Test fun manyEnginesOnJvmThreads() {
    val errors = Collections.synchronizedList(mutableListOf<String>())
    val threads = List(8) { index ->
      Thread({
        try {
          JsEngine.create().use { engine ->
            assertEquals("ok", engine.evaluate("'ok'", "?"))
          }
        } catch (t: Throwable) {
          errors += "thread $index: $t"
        }
      }, "zipline-engine-$index")
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertTrue(errors.isEmpty(), errors.joinToString("\n"))
  }
}
