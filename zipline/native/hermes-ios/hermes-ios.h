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

#ifndef HERMES_IOS_H
#define HERMES_IOS_H

#include <stdbool.h>
#include <stdint.h>

#include "../common/intset-builtins.h"

#ifdef __cplusplus
extern "C" {
#endif

// Initialize the Hermes framework (must be called before any other function)
// runtimeOut: output pointer to receive the newly created Hermes runtime (opaque void**).
// Returns 1 on success, 0 on failure.
int HermesFramework_init(void** runtimeOut);

// Runtime lifecycle (opaque void* pointers). The returned pointer is an
// ios-layer context that extends the core Hermes context with the
// per-runtime bridge state (channel callbacks, RDMA state).
void* HermesRuntime_create(void);
void HermesRuntime_destroy(void* runtime);

// Get the jsi::Runtime from a runtime returned by HermesRuntime_create
// (used to register JS intrinsics right after creation).
void* HermesRuntime_getJsiRuntime(void* runtime);

// Evaluation - executes the script and discards the result
int HermesContext_evaluate(void* context, const char* code, const char* sourceURL);
void HermesContext_freeValue(void* context, char* value);

// Bytecode compilation and execution
int HermesContext_compile(void* context, const char* code, const char* sourceURL,
                          const char* sourceMap, char** bytecodeOut, int* bytecodeSizeOut);
int HermesContext_execute(void* context, const uint8_t* bytecode, int bytecodeSize,
                         const char* sourceURL);

// Global properties
int HermesContext_getGlobalProperty(void* context, const char* name, char** valueOut);
int HermesContext_setGlobalProperty(void* context, const char* name, const char* value);
int HermesContext_deleteGlobalProperty(void* context, const char* name);

// Call functions
int HermesContext_callGlobalMethod(void* context, const char* objectName, const char* methodName);
int HermesContext_callGlobalFunctionWithStringArg(void* context, const char* functionName,
                                                 const char* arg, char** resultOut);

// Call channel callbacks (for outbound channel - JS calling into native/Kotlin).
// Callbacks are registered per-context so that multiple concurrent runtimes
// (e.g. an old and a new Zipline during a screen transition) each route their
// JS calls to their own Kotlin endpoint. The context pointer is passed back
// as the first argument of every callback invocation.
typedef void* (*OutboundCallChannelCallFn)(void* context, const char* callJson);
typedef int (*OutboundCallChannelDisconnectFn)(void* context, const char* instanceName);
void HermesContext_setOutboundChannelCallbacks(void* context,
                                                OutboundCallChannelCallFn callFn,
                                                OutboundCallChannelDisconnectFn disconnectFn);

// RDMA Changes support. The sink is per-context; the context pointer is
// passed back on invocation.
typedef void (*RdmaChangeSinkFn)(void* context);
void HermesContext_setRdmaChangeSink(void* context, RdmaChangeSinkFn sinkFn);

// Inbound call channel (Kotlin calling into JS)
// channelName is the JS global property name where the channel object is registered
int HermesContext_setInboundCallChannel(void* context, const char* channelName);

// Outbound call channel (JS calling into Kotlin)
// Sets up global "outboundChannel" object with call/disconnect functions
// that delegate to the callbacks set via HermesContext_setOutboundChannelCallbacks
int HermesContext_setupOutboundCallChannel(void* context);

// Call an inbound channel's "call" method from Kotlin
// Returns a newly allocated string (caller must free), or NULL on error
char* HermesContext_callInbound(void* context, const char* channelName, const char* callJson);

// Call an inbound channel's "disconnect" method from Kotlin
// Returns a newly allocated string "true" or "false" (caller must free), or NULL on error
char* HermesContext_callInboundDisconnect(void* context, const char* channelName, const char* instanceName);

// Module loading (not implemented - returns 0)
int HermesContext_installModuleLoader(void* context);
int HermesContext_callRequireMethod(void* context, const char* moduleId, const char* methodName);

// RDMA Changes support
int HermesContext_initRdmaChangesChannel(void* context);

// Memory management
void HermesContext_setMemoryLimit(void* context, int64_t limitBytes);
void HermesContext_setGcThreshold(void* context, int64_t thresholdBytes);
void HermesContext_setMaxStackSize(void* context, int64_t maxStackSizeBytes);
void HermesContext_gc(void* context);

// Memory usage struct
typedef struct HermesMemoryUsage {
    int64_t heapSize;
    int64_t allocBytes;
    int64_t gcCount;
} HermesMemoryUsage;

// Memory usage
int HermesContext_getMemoryUsage(void* context, HermesMemoryUsage* usageOut);

// Version
const char* Hermes_getVersion(void);

// Error handling
const char* HermesContext_getLastError(void* context);

#ifdef __cplusplus
}
#endif

#endif // HERMES_IOS_H
