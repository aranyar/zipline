#include "hermes-ios.h"
#include "hermes-core.h"
#include "../RdmaChange.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <vector>

#include <jsi/jsi.h>

namespace jsi = facebook::jsi;

// Process-global error buffer. NOT thread-safe: two engines reporting errors
// from different threads race on this buffer. The bridge is designed for
// single-threaded-per-runtime use, and errors are consumed immediately after
// each failing call on the same thread, so this is acceptable for now. If
// multi-threaded error reporting is ever needed, move this into
// HermesIosContext (per-runtime) or use thread_local storage.
static char g_lastError[1024];

typedef void* (*OutboundCallChannelCallFn)(void* context, const char* callJson);
typedef int (*OutboundCallChannelDisconnectFn)(void* context, const char* instanceName);

// RdmaChange / RdmaChangeType / RDMA_BATCH_SIZE come from ../RdmaChange.h,
// shared with the JNI layer.

typedef void (*RdmaChangeSinkFn)(void* context);

// The ios-layer context extends the core Hermes context with the
// per-runtime bridge state (channel callbacks, RDMA state), so multiple
// concurrent runtimes (e.g. an old and a new Zipline during a screen
// transition) never route calls into each other. HermesRuntime_create()
// returns this struct; HermesContext_* functions pass it to HermesCore_*
// functions via an implicit upcast.
struct HermesIosContext : HermesCoreContext {
    OutboundCallChannelCallFn outboundCallFn = NULL;
    OutboundCallChannelDisconnectFn outboundDisconnectFn = NULL;
    RdmaChangeSinkFn rdmaSinkFn = NULL;
    std::vector<RdmaChange> pendingChanges;
    int removeCounter = 0;
};

static HermesIosContext* asIosContext(void* context) {
    return static_cast<HermesIosContext*>(context);
}

void HermesContext_setOutboundChannelCallbacks(void* context,
                                               OutboundCallChannelCallFn callFn,
                                               OutboundCallChannelDisconnectFn disconnectFn) {
    if (!context) return;
    HermesIosContext* ctx = asIosContext(context);
    ctx->outboundCallFn = callFn;
    ctx->outboundDisconnectFn = disconnectFn;
}

void HermesContext_setRdmaChangeSink(void* context, RdmaChangeSinkFn sinkFn) {
    if (!context) return;
    asIosContext(context)->rdmaSinkFn = sinkFn;
}

int HermesFramework_init(void** runtimeOut) {
    if (!runtimeOut) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime output pointer");
        return 0;
    }
    HermesIosContext* ctx = new HermesIosContext();
    if (!HermesCore_initContext(ctx)) {
        delete ctx;
        snprintf(g_lastError, sizeof(g_lastError), "Failed to create Hermes runtime");
        return 0;
    }
    *runtimeOut = ctx;
    return 1;
}

void* HermesRuntime_create(void) {
    void* runtime = NULL;
    if (!HermesFramework_init(&runtime)) {
        return NULL;
    }
    return runtime;
}

void HermesRuntime_destroy(void* runtime) {
    if (runtime) {
        HermesIosContext* ctx = asIosContext(runtime);
        HermesCore_releaseContext(ctx);
        delete ctx;
    }
}

void* HermesRuntime_getJsiRuntime(void* runtime) {
    if (!runtime) return NULL;
    return HermesCore_getRuntime(asIosContext(runtime));
}

int HermesContext_evaluate(void* context, const char* code, const char* sourceURL) {
    if (!context || !code) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_evaluate(asIosContext(context), code, strlen(code), sourceURL, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Evaluation failed");
        if (errorOut) free(errorOut);
        return 0;
    }

    return 1;
}

void HermesContext_freeValue(void* context, char* value) {
    if (value) {
        free(value);
    }
}

int HermesContext_compile(void* context, const char* code, const char* sourceURL,
                          const char* sourceMap, char** bytecodeOut, int* bytecodeSizeOut) {
#ifdef HERMESVM_LEAN
    snprintf(g_lastError, sizeof(g_lastError), "compile() is not available in lean Hermes build");
    return 0;
#else
    if (!context || !code) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    uint8_t* bytecode = NULL;
    size_t bytecodeSize = 0;
    char* errorOut = NULL;

    int success = HermesCore_compile(asIosContext(context), code, sourceURL, sourceMap, &bytecode, &bytecodeSize, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Compilation failed");
        if (errorOut) free(errorOut);
        return 0;
    }

    char* bytes = static_cast<char*>(malloc(bytecodeSize));
    if (!bytes) {
        delete[] bytecode; // Allocated with new[] by HermesCore_compile.
        snprintf(g_lastError, sizeof(g_lastError), "Out of memory");
        return 0;
    }
    memcpy(bytes, bytecode, bytecodeSize);
    delete[] bytecode; // Allocated with new[] by HermesCore_compile.

    *bytecodeOut = bytes;
    *bytecodeSizeOut = (int)bytecodeSize;
    return 1;
#endif
}

int HermesContext_execute(void* context, const uint8_t* bytecode, int bytecodeSize,
                         const char* sourceURL) {
    if (!context || !bytecode) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_execute(asIosContext(context), bytecode, bytecodeSize, sourceURL, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Execution failed");
        if (errorOut) free(errorOut);
        return 0;
    }

    return 1;
}

int HermesContext_getGlobalProperty(void* context, const char* name, char** valueOut) {
    if (!context || !name) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_getGlobalProperty(asIosContext(context), name, valueOut, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to get global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_setGlobalProperty(void* context, const char* name, const char* value) {
    if (!context || !name || !value) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_setGlobalProperty(asIosContext(context), name, value, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to set global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_deleteGlobalProperty(void* context, const char* name) {
    if (!context || !name) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_deleteGlobalProperty(asIosContext(context), name, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to delete global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callGlobalMethod(void* context, const char* objectName, const char* methodName) {
    if (!context || !objectName || !methodName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callGlobalMethod(asIosContext(context), objectName, methodName, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call global method");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callGlobalFunctionWithStringArg(void* context, const char* functionName,
                                                const char* arg, char** resultOut) {
    if (!context || !functionName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callGlobalFunctionWithStringArg(asIosContext(context), functionName, arg, resultOut, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call global function");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_installModuleLoader(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_installModuleLoader(asIosContext(context), &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to install module loader");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callRequireMethod(void* context, const char* moduleId, const char* methodName) {
    if (!context || !moduleId || !methodName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callRequireMethod(asIosContext(context), moduleId, methodName, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call require method");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_initRdmaChangesChannel(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    void* runtime = HermesCore_getRuntime(asIosContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return 0;
    }

    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(runtime);

    jsi::Object rdmaObj(rt);

    jsi::Function appendCreateFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendCreate"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isNumber()) {
                throw jsi::JSError(runtime, "appendCreate expects (id, tag)");
            }
            HermesIosContext* ctx = asIosContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Create;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendCreate", appendCreateFn);

    jsi::Function appendPropertyChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendPropertyChange"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendPropertyChange expects (id, widgetTag, propertyTag, value)");
            }
            HermesIosContext* ctx = asIosContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::PropertyChange;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.jsValue = std::make_shared<jsi::Value>(runtime, args[3]);
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendPropertyChange", appendPropertyChangeFn);

    jsi::Function appendModifierChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendModifierChange"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isObject()) {
                throw jsi::JSError(runtime, "appendModifierChange expects (id, elements)");
            }
            HermesIosContext* ctx = asIosContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::ModifierChange;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.jsValue = std::make_shared<jsi::Value>(runtime, args[1]);
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendModifierChange", appendModifierChangeFn);

    jsi::Function appendAddFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendAdd"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber()) {
                throw jsi::JSError(runtime, "appendAdd expects (id, childrenTag, childId, index)");
            }
            HermesIosContext* ctx = asIosContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Add;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.field3 = static_cast<int>(args[3].asNumber());
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendAdd", appendAddFn);

    jsi::Function appendRemoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendRemove"), 3,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 3 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendRemove expects (id, childrenTag, index)");
            }
            HermesIosContext* ctx = asIosContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Remove;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.detach = false;
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            // The counter tracks removes only (matching the original Kotlin
            // protocol): each remove gets an ordinal that the JS side passes
            // back to setRemoveDetach().
            int removeOrdinal = ctx->removeCounter++;
            return jsi::Value(removeOrdinal);
        });
    rdmaObj.setProperty(rt, "appendRemove", appendRemoveFn);

    jsi::Function setRemoveDetachFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "setRemoveDetach"), 1,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) {
                throw jsi::JSError(runtime, "setRemoveDetach expects (idx)");
            }
            HermesIosContext* ctx = asIosContext(context);
            // idx is the remove ordinal returned by appendRemove(), not an
            // index into pendingChanges: find the idx-th Remove change.
            int idx = static_cast<int>(args[0].asNumber());
            int removeOrdinal = 0;
            for (RdmaChange& ch : ctx->pendingChanges) {
                if (ch.type != RdmaChangeType::Remove) continue;
                if (removeOrdinal == idx) {
                    ch.detach = true;
                    break;
                }
                removeOrdinal++;
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "setRemoveDetach", setRemoveDetachFn);

    jsi::Function appendMoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendMove"), 5,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 5 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber() || !args[4].isNumber()) {
                throw jsi::JSError(runtime, "appendMove expects (id, childrenTag, fromIndex, toIndex, count)");
            }
            HermesIosContext* ctx = asIosContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Move;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.field3 = static_cast<int>(args[3].asNumber());
            ch.count = static_cast<int>(args[4].asNumber());
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendMove", appendMoveFn);

    jsi::Function finishChangesFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "finishChanges"), 0,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            // Flush all pending changes to the Kotlin RDMA sink via the
            // rdmaChangeSink function pointer. The C++ side manages the
            // pendingChanges list and removeCounter per context; only the
            // final sendChanges() call delegates to Kotlin.
            RdmaChangeSinkFn sinkFn = NULL;
            HermesIosContext* ctx = asIosContext(context);
            ctx->removeCounter = 0;
            sinkFn = ctx->rdmaSinkFn;
            if (sinkFn) {
                sinkFn(context);
            }
            ctx->pendingChanges.clear();
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "finishChanges", finishChangesFn);

    jsi::Function changesLengthFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "changesLength"), 0,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            return jsi::Value(asIosContext(context)->removeCounter);
        });
    rdmaObj.setProperty(rt, "changesLength", changesLengthFn);

    rt.global().setProperty(rt, "app_cash_redwood_rdmaSendChanges", rdmaObj);
    return 1;
}

void HermesContext_setMemoryLimit(void* context, int64_t limitBytes) {
    if (context) {
        HermesCore_setMemoryLimit(asIosContext(context), limitBytes);
    }
}

void HermesContext_setGcThreshold(void* context, int64_t thresholdBytes) {
    if (context) {
        HermesCore_setGcThreshold(asIosContext(context), thresholdBytes);
    }
}

void HermesContext_setMaxStackSize(void* context, int64_t maxStackSizeBytes) {
    if (context) {
        HermesCore_setMaxStackSize(asIosContext(context), maxStackSizeBytes);
    }
}

void HermesContext_gc(void* context) {
    if (context) {
        HermesCore_gc(asIosContext(context));
    }
}

int HermesContext_getMemoryUsage(void* context, HermesMemoryUsage* usageOut) {
    if (!context || !usageOut) {
        return 0;
    }

    int64_t heapSize = 0, allocBytes = 0, gcCount = 0;
    int success = HermesCore_getMemoryUsage(asIosContext(context), &heapSize, &allocBytes, &gcCount);
    if (success) {
        usageOut->heapSize = heapSize;
        usageOut->allocBytes = allocBytes;
        usageOut->gcCount = gcCount;
    }
    return success;
}

const char* Hermes_getVersion(void) {
    return HermesCore_getVersion();
}

const char* HermesContext_getLastError(void* context) {
    if (context) {
        const char* err = HermesCore_getLastError(asIosContext(context));
        if (err && strlen(err) > 0) {
            return err;
        }
    }
    return g_lastError;
}

int HermesContext_setInboundCallChannel(void* context, const char* channelName) {
    if (!context || !channelName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    void* runtime = HermesCore_getRuntime(asIosContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return 0;
    }

    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(runtime);

    jsi::Object channelObj(rt);
    jsi::Function callFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "call"), 1,
        [](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "call expects a string argument");
            }
            std::string callJson = args[0].asString(runtime).utf8(runtime);
            return jsi::String::createFromUtf8(runtime, callJson);
        });
    channelObj.setProperty(rt, "call", callFn);

    jsi::Function disconnectFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "disconnect"), 1,
        [](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "disconnect expects a string argument");
            }
            return jsi::Value(false);
        });
    channelObj.setProperty(rt, "disconnect", disconnectFn);

    rt.global().setProperty(rt, channelName, channelObj);
    return 1;
}

int HermesContext_setupOutboundCallChannel(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    void* runtime = HermesCore_getRuntime(asIosContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return 0;
    }

    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(runtime);

    jsi::Object outboundChannel(rt);

    jsi::Function callFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "call"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "outboundChannel.call expects (callJson)");
            }
            std::string callJson = args[0].asString(runtime).utf8(runtime);

            HermesIosContext* ctx = asIosContext(context);
            if (ctx->outboundCallFn) {
                char* result = (char*)ctx->outboundCallFn(context, callJson.c_str());
                if (result) {
                    jsi::Value jsResult = jsi::String::createFromUtf8(runtime, std::string(result));
                    free(result);
                    return jsResult;
                }
            }
            return jsi::Value::undefined();
        });
    outboundChannel.setProperty(rt, "call", callFn);

    jsi::Function disconnectFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "disconnect"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "outboundChannel.disconnect expects (instanceName)");
            }
            std::string instanceName = args[0].asString(runtime).utf8(runtime);

            HermesIosContext* ctx = asIosContext(context);
            if (ctx->outboundDisconnectFn) {
                int result = ctx->outboundDisconnectFn(context, instanceName.c_str());
                return jsi::Value(result != 0);
            }
            return jsi::Value(false);
        });
    outboundChannel.setProperty(rt, "disconnect", disconnectFn);

    rt.global().setProperty(rt, "app_cash_zipline_outboundChannel", outboundChannel);
    return 1;
}

char* HermesContext_callInbound(void* context, const char* channelName, const char* callJson) {
    if (!context || !channelName || !callJson) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return NULL;
    }

    void* runtime = HermesCore_getRuntime(asIosContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return NULL;
    }

    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(runtime);

    jsi::Value channel = rt.global().getProperty(rt, channelName);
    if (!channel.isObject()) {
        snprintf(g_lastError, sizeof(g_lastError), "Channel %s not found", channelName);
        return NULL;
    }

    jsi::Object channelObj = channel.asObject(rt);
    jsi::Value callFn = channelObj.getProperty(rt, "call");
    if (!callFn.isObject() || !callFn.asObject(rt).isFunction(rt)) {
        snprintf(g_lastError, sizeof(g_lastError), "Channel %s has no call function", channelName);
        return NULL;
    }

    try {
        jsi::Value result = callFn.asObject(rt).asFunction(rt).callWithThis(
            rt, channelObj, {jsi::String::createFromUtf8(rt, callJson)});
        if (result.isString()) {
            std::string resultStr = result.asString(rt).utf8(rt);
            char* copy = (char*)malloc(resultStr.size() + 1);
            if (copy) {
                memcpy(copy, resultStr.c_str(), resultStr.size() + 1);
            }
            return copy;
        }
    } catch (const jsi::JSError& e) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.what());
    }
    return NULL;
}

char* HermesContext_callInboundDisconnect(void* context, const char* channelName, const char* instanceName) {
    if (!context || !channelName || !instanceName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return NULL;
    }

    void* runtime = HermesCore_getRuntime(asIosContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return NULL;
    }

    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(runtime);

    jsi::Value channel = rt.global().getProperty(rt, channelName);
    if (!channel.isObject()) {
        snprintf(g_lastError, sizeof(g_lastError), "Channel %s not found", channelName);
        return NULL;
    }

    jsi::Object channelObj = channel.asObject(rt);
    jsi::Value disconnectFn = channelObj.getProperty(rt, "disconnect");
    if (!disconnectFn.isObject() || !disconnectFn.asObject(rt).isFunction(rt)) {
        snprintf(g_lastError, sizeof(g_lastError), "Channel %s has no disconnect function", channelName);
        return NULL;
    }

    try {
        jsi::Value result = disconnectFn.asObject(rt).asFunction(rt).callWithThis(
            rt, channelObj, {jsi::String::createFromUtf8(rt, instanceName)});
        std::string resultStr = result.isBool() ? (result.asBool() ? "true" : "false") : "false";
        char* copy = (char*)malloc(resultStr.size() + 1);
        if (copy) {
            memcpy(copy, resultStr.c_str(), resultStr.size() + 1);
        }
        return copy;
    } catch (const jsi::JSError& e) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.what());
    }
    return NULL;
}
