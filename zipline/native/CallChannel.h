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
#ifndef ZIPLINE_CALL_CHANNEL_H
#define ZIPLINE_CALL_CHANNEL_H

#include <jsi/jsi.h>
#include <memory>
#include <string>

namespace facebook {
namespace jsi {
class Runtime;
}
}  // namespace facebook

class InboundCallChannel {
 public:
  explicit InboundCallChannel(std::string name);
  virtual ~InboundCallChannel() = default;

  virtual std::string call(facebook::jsi::Runtime& runtime, const std::string& callJson) = 0;
  virtual bool disconnect(facebook::jsi::Runtime& runtime, const std::string& instanceName) = 0;

  const std::string& name() const { return name_; }

 protected:
  std::string name_;
};

class OutboundCallChannel {
 public:
  OutboundCallChannel() = default;
  virtual ~OutboundCallChannel() = default;

  virtual void attachToJavascript(facebook::jsi::Runtime& runtime,
                                   facebook::jsi::Object& jsObject) = 0;
};

#endif  // ZIPLINE_CALL_CHANNEL_H
