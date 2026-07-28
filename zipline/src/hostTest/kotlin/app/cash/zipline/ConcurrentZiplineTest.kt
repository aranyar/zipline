package app.cash.zipline

import app.cash.zipline.testing.EchoRequest
import app.cash.zipline.testing.EchoResponse
import app.cash.zipline.testing.EchoService
import app.cash.zipline.testing.loadTestingJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Separate Zipline instances are independent native runtimes: creating,
 * using, and destroying them concurrently (on different threads) must not
 * interfere with each other. A single instance is NOT thread-safe, so each
 * test touches an instance from only one thread at a time; only the
 * create/use/close of *separate* instances overlaps.
 */
class ConcurrentZiplineTest {

  private class JvmEchoService : EchoService {
    override fun echo(request: EchoRequest): EchoResponse {
      return EchoResponse("sup from the host, ${request.message}")
    }
  }

  /** Two instances alive at once are fully isolated. */
  @Test
  fun twoInstancesCoexist() = runBlocking {
    val z1 = Zipline.create(Dispatchers.Default)
    val z2 = Zipline.create(Dispatchers.Default)
    try {
      z1.jsEngine.evaluate("globalThis.value = 'one';")
      z2.jsEngine.evaluate("globalThis.value = 'two';")
      z1.jsEngine.evaluate("globalThis.onlyInZ1 = true;")

      assertEquals("one", z1.jsEngine.getGlobalProperty("value"))
      assertEquals("two", z2.jsEngine.getGlobalProperty("value"))
      assertNull(z2.jsEngine.getGlobalProperty("onlyInZ1"))
    } finally {
      z1.close()
      z2.close()
    }
  }

  /**
   * Closing one instance while another is being created, overlapped via
   * latches so closing and creation race.
   */
  @Test
  fun createSecondWhileClosingFirst() = runBlocking {
    repeat(10) {
      val z1 = Zipline.create(Dispatchers.Default)
      z1.jsEngine.evaluate("globalThis.value = 'one';")

      val createStarted = CompletableDeferred<Unit>()
      val closeDone = CompletableDeferred<Unit>()

      val closer = launch(Dispatchers.Default) {
        createStarted.await() // creation begins before the close completes
        z1.close()
        closeDone.complete(Unit)
      }
      createStarted.complete(Unit)
      val z2 = Zipline.create(Dispatchers.Default)
      closeDone.await()
      closer.join()

      try {
        z2.jsEngine.evaluate("globalThis.value = 'two';")
        assertEquals("two", z2.jsEngine.getGlobalProperty("value"))
      } finally {
        z2.close()
      }
    }
  }

  /** Many instances created, used, and destroyed in parallel on different threads. */
  @Test
  fun parallelCreateEvaluateClose() = runBlocking {
    (0 until 10).map { i ->
      async(Dispatchers.Default) {
        val zipline = Zipline.create(Dispatchers.Default)
        try {
          zipline.jsEngine.evaluate("globalThis.value = '' + $i;")
          i to zipline.jsEngine.getGlobalProperty("value")
        } finally {
          zipline.close()
        }
      }
    }.awaitAll().forEach { (i, value) ->
      assertEquals("$i", value)
    }
  }

  /** Two long-lived instances driven in parallel; results stay on their own instance. */
  @Test
  fun concurrentEvaluatesAreIsolated() = runBlocking {
    val z1 = Zipline.create(Dispatchers.Default)
    val z2 = Zipline.create(Dispatchers.Default)
    try {
      val r1 = async(Dispatchers.Default) {
        repeat(50) { i -> z1.jsEngine.evaluate("globalThis.value = 'z1-$i';") }
        z1.jsEngine.getGlobalProperty("value")
      }
      val r2 = async(Dispatchers.Default) {
        repeat(50) { i -> z2.jsEngine.evaluate("globalThis.value = 'z2-$i';") }
        z2.jsEngine.getGlobalProperty("value")
      }
      assertEquals("z1-49", r1.await())
      assertEquals("z2-49", r2.await())
    } finally {
      z1.close()
      z2.close()
    }
  }

  /** Creating and destroying other instances doesn't disturb a live one. */
  @Test
  fun createCloseOthersDoesNotAffectLiveInstance() = runBlocking {
    val live = Zipline.create(Dispatchers.Default)
    try {
      live.jsEngine.evaluate("globalThis.value = 'live';")
      repeat(10) { i ->
        val temp = Zipline.create(Dispatchers.Default)
        temp.jsEngine.evaluate("globalThis.value = 'temp-$i';")
        temp.close()
        assertEquals("live", live.jsEngine.getGlobalProperty("value"))
      }
    } finally {
      live.close()
    }
  }

  /** Services keep working on an instance while others create/destroy in parallel. */
  @Test
  fun servicesWorkWhileOtherInstanceLoops() = runBlocking {
    val zipline = Zipline.create(Dispatchers.Default)
    try {
      // 20 create/destroy cycles running in parallel with the service call.
      val looper = launch(Dispatchers.Default) {
        repeat(40) {
          val temp = Zipline.create(Dispatchers.Default)
          temp.close()
        }
      }
      zipline.loadTestingJs()
      zipline.bind<EchoService>("supService", JvmEchoService())
      zipline.jsEngine.evaluate(
        "globalThis.result = testing.app.cash.zipline.testing.callSupService('homie');",
      )

      assertEquals(
        "JavaScript received 'sup from the host, homie' from the JVM",
        zipline.jsEngine.getGlobalProperty("result"),
      )
      looper.join()
    } finally {
      zipline.close()
    }
  }
}
