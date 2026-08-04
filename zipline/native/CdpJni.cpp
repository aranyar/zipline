#include "CdpJni.h"

#include "ContextJni.h"
#include "JniUtf8.h"


#if HERMES_ENABLE_DEBUGGER

#include <hermes/AsyncDebuggerAPI.h>
#include <hermes/cdp/CDPAgent.h>
#include <hermes/cdp/CDPDebugAPI.h>

#include <deque>
#include <mutex>

namespace zipline_cdp {

namespace cdp = facebook::hermes::cdp;
namespace debugger = facebook::hermes::debugger;

struct Session {
  std::unique_ptr<cdp::CDPDebugAPI> debugApi;

  // Guarded by agentMutex: handleCommand may run on any transport thread
  // while resetAgent swaps the agent.
  std::mutex agentMutex;
  std::unique_ptr<cdp::CDPAgent> agent;

  JavaVM* javaVm = nullptr;
  jobject listener = nullptr;  // Global ref to app.cash.zipline.CdpListener.
  jmethodID onMessageId = nullptr;
  jmethodID onTasksEnqueuedId = nullptr;

  ContextJni* ctx = nullptr;

  std::mutex queueMutex;
  std::deque<debugger::RuntimeTask> taskQueue;

  // Whether the debugger is currently paused (tracked from CDP events).
  // While paused the JS thread is blocked in the debugger loop, so integrator
  // tasks must be serviced via AsyncDebuggerAPI interrupts, not the queue.
  std::atomic<bool> paused{false};
};

namespace {

// Returns a JNIEnv for the current thread, attaching it to the JVM if needed.
// If the thread was attached by this call, *attachedOut is set to true and the
// caller must detach when done calling into Java.
JNIEnv* envForCurrentThread(JavaVM* vm, bool* attachedOut) {
  *attachedOut = false;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
    return env;
  }
  // The C++ JNI headers (Android NDK) take JNIEnv** here, the C headers
  // (Oracle/OpenJDK) take void**.
#ifdef __ANDROID__
  if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
#else
  if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
#endif
    return nullptr;
  }
  *attachedOut = true;
  return env;
}

void callListenerVoid(Session* session, jmethodID method, jstring arg) {
  bool attached;
  JNIEnv* env = envForCurrentThread(session->javaVm, &attached);
  if (!env) return;
  env->CallVoidMethod(session->listener, method, arg);
  if (arg) env->DeleteLocalRef(arg);
  // Listener failures must not crash the engine thread.
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) session->javaVm->DetachCurrentThread();
}

void sendMessage(Session* session, const std::string& json) {
  // Track pause state from CDP events so queued runtime tasks can be serviced
  // through the debugger's interrupt path while the JS thread is paused.
  if (json.find("\"Debugger.paused\"") != std::string::npos) {
    session->paused.store(true);
  } else if (json.find("\"Debugger.resumed\"") != std::string::npos) {
    session->paused.store(false);
  }
  bool attached;
  JNIEnv* env = envForCurrentThread(session->javaVm, &attached);
  if (!env) return;
  jstring j = zipline::utf8ToJniString(env, json);
  callListenerVoid(session, session->onMessageId, j);
  if (attached) session->javaVm->DetachCurrentThread();
}

void notifyTasksEnqueued(Session* session) {
  callListenerVoid(session, session->onTasksEnqueuedId, nullptr);
}

// The agent may invoke these callbacks from arbitrary threads (including
// during ~CDPAgent), so they capture the raw session pointer; the session
// outlives the agent because detach() resets the agent first.
std::unique_ptr<cdp::CDPAgent> createAgent(Session* raw, cdp::State state) {
  return cdp::CDPAgent::create(
      /*executionContextID=*/1,
      *raw->debugApi,
      /*enqueueRuntimeTaskCallback=*/
      [raw](debugger::RuntimeTask task) {
        {
          std::lock_guard<std::mutex> lock(raw->queueMutex);
          raw->taskQueue.push_back(std::move(task));
        }
        if (raw->paused.load()) {
          // The JS thread is blocked in the debugger pause loop, so the
          // normal drain (posted to the Zipline dispatcher) can't run. Service
          // the queue through the debugger's interrupt path instead.
          raw->debugApi->asyncDebuggerAPI().triggerInterrupt_TS(
              [raw](facebook::hermes::HermesRuntime&) {
                drainTasks(raw->ctx);
              });
        } else {
          notifyTasksEnqueued(raw);
        }
      },
      /*messageCallback=*/
      [raw](const std::string& json) { sendMessage(raw, json); },
      std::move(state));
}

}  // namespace

bool attach(ContextJni* ctx, JNIEnv* env, jobject listener) {
  if (!ctx || !listener) return false;
  std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
  if (ctx->cdpSession) return true;

  jclass listenerClass = env->GetObjectClass(listener);
  jmethodID onMessageId =
      env->GetMethodID(listenerClass, "onMessage", "(Ljava/lang/String;)V");
  jmethodID onTasksEnqueuedId =
      env->GetMethodID(listenerClass, "onTasksEnqueued", "()V");
  if (!onMessageId || !onTasksEnqueuedId) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
  }

  Session* raw = new Session();
  std::unique_ptr<Session> session(raw);
  session->debugApi = cdp::CDPDebugAPI::create(*ctx->runtime);
  if (!session->debugApi) {
    return false;
  }
  session->javaVm = ctx->javaVm;
  session->ctx = ctx;
  session->listener = env->NewGlobalRef(listener);
  session->onMessageId = onMessageId;
  session->onTasksEnqueuedId = onTasksEnqueuedId;
  session->agent = createAgent(raw, {});

  ctx->cdpSession = session.release();
  return true;
}

void handleCommand(ContextJni* ctx, const std::string& json) {
  if (!ctx) return;
  // Held for the whole call: detach() must not free the session mid-command.
  std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
  if (!ctx->cdpSession) return;
  Session* session = ctx->cdpSession;
  std::lock_guard<std::mutex> lock(session->agentMutex);
  if (session->agent) {
    session->agent->handleCommand(json);
  }
}

void resetAgent(ContextJni* ctx) {
  if (!ctx) return;
  // Held for the whole call: detach() must not free the session mid-swap.
  std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
  if (!ctx->cdpSession) return;
  Session* session = ctx->cdpSession;
  std::lock_guard<std::mutex> lock(session->agentMutex);
  if (!session->agent) return;
  {
    // Drop tasks enqueued by the old agent: they capture the old agent and
    // would run against it (use-after-free) if drained after the swap. The
    // client disconnected, so nothing is waiting on their results. Tasks the
    // old agent's destructor enqueues during the swap below (e.g. domain
    // dispose, which clears breakpoints) are safe — they keep their targets
    // alive — and must survive.
    std::lock_guard<std::mutex> queueLock(session->queueMutex);
    session->taskQueue.clear();
  }
  cdp::State state = session->agent->getState();
  session->agent = createAgent(session, std::move(state));
}

void drainTasks(ContextJni* ctx) {
  if (!ctx) return;
  for (;;) {
    debugger::RuntimeTask task;
    {
      // Only the queue pop is locked: detach() runs on this same (JS) thread,
      // so the session cannot be freed while the task executes.
      std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
      if (!ctx->cdpSession) return;
      Session* session = ctx->cdpSession;
      {
        std::lock_guard<std::mutex> lock(session->queueMutex);
        if (session->taskQueue.empty()) return;
        task = std::move(session->taskQueue.front());
        session->taskQueue.pop_front();
      }
    }
    // RuntimeTaskRunner wraps tasks so a task already executed via the
    // AsyncDebuggerAPI interrupt path is a no-op here (and vice versa).
    task(*ctx->runtime);
  }
}

void detach(ContextJni* ctx) {
  if (!ctx) return;
  Session* session;
  {
    std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
    session = ctx->cdpSession;
    if (!session) return;
    ctx->cdpSession = nullptr;
  }

  // The agent must go first: it uses the debug API and may still invoke our
  // callbacks (which reference the session) while being destroyed.
  {
    std::lock_guard<std::mutex> lock(session->agentMutex);
    session->agent.reset();
  }
  session->debugApi.reset();

  bool attached;
  JNIEnv* env = envForCurrentThread(session->javaVm, &attached);
  if (env) {
    env->DeleteGlobalRef(session->listener);
    if (attached) session->javaVm->DetachCurrentThread();
  }
  delete session;
}

}  // namespace zipline_cdp

#else  // !HERMES_ENABLE_DEBUGGER

namespace zipline_cdp {

bool attach(ContextJni*, JNIEnv*, jobject) { return false; }
void handleCommand(ContextJni*, const std::string&) {}
void resetAgent(ContextJni*) {}
void drainTasks(ContextJni*) {}
void detach(ContextJni*) {}

}  // namespace zipline_cdp

#endif  // HERMES_ENABLE_DEBUGGER
