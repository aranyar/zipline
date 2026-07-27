#ifndef ZIPLINE_CONTEXT_BASE_H
#define ZIPLINE_CONTEXT_BASE_H

#include <jsi/jsi.h>
#include <memory>
#include <string>
#include <vector>

namespace facebook {
namespace jsi {
class Runtime;
}
}  // namespace facebook

class InboundCallChannel;
class OutboundCallChannel;

class ContextBase {
 public:
  virtual ~ContextBase();

  virtual facebook::jsi::Runtime& getRuntime() = 0;
  virtual facebook::jsi::String toJsString(const std::string& str) = 0;
  virtual std::string toCppString(const facebook::jsi::String& str) = 0;
  virtual void throwJsException(const std::string& message) = 0;

  // Throw for a JS error caught in a host function. The default
  // implementation forwards only the message; platforms that can splice the
  // JS stack into the platform exception (see ContextJni) override this.
  virtual void throwJsError(facebook::jsi::JSError& error);

 protected:
  std::vector<InboundCallChannel*> callChannels;
  std::vector<OutboundCallChannel*> outboundChannels;
};

#endif  // ZIPLINE_CONTEXT_BASE_H
