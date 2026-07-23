/*
 * Copyright (C) 2019 Square, Inc.
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
#ifndef ZIPLINE_OUTBOUNDCALLCHANNEL_JNI_H
#define ZIPLINE_OUTBOUNDCALLCHANNEL_JNI_H

#include "OutboundCallChannel.h"

#include <jni.h>

class ContextJni;

class OutboundCallChannelJni : public OutboundCallChannel {
 public:
  OutboundCallChannelJni(ContextBase* context, JNIEnv* env, std::string name,
                         jobject callChannel, const facebook::jsi::Object& jsObject);
  ~OutboundCallChannelJni() override;

  std::string call(const std::string& callJson) override;
  bool disconnect(const std::string& instanceName) override;

 private:
  // JS-facing call implementation. Mirrors the JS `outboundChannel.call(...)`
  // signature so we can register it as a host function on the global outbound
  // channel object.
  static facebook::jsi::Value outboundCallImpl(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisVal,
      const facebook::jsi::Value* args,
      size_t argc,
      OutboundCallChannel* channel);

  // JS-facing disconnect implementation. Mirrors the JS `outboundChannel.disconnect(...)`
  // signature so we can register it as a host function on the global outbound
  // channel object.
  static facebook::jsi::Value outboundDisconnectImpl(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisVal,
      const facebook::jsi::Value* args,
      size_t argc,
      OutboundCallChannel* channel);

  ContextJni* contextJni_;
  jobject javaThis_;
  jclass callChannelClass_;
  jmethodID callMethod_;
  jmethodID disconnectMethod_;
};

#endif  // ZIPLINE_OUTBOUNDCALLCHANNEL_JNI_H
