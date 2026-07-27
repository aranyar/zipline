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
