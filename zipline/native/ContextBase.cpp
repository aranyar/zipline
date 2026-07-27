#include "ContextBase.h"
#include "InboundCallChannel.h"
#include "OutboundCallChannel.h"

ContextBase::~ContextBase() {
  for (auto* ch : callChannels) delete ch;
  for (auto* ch : outboundChannels) delete ch;
}

void ContextBase::throwJsError(facebook::jsi::JSError& error) {
  throwJsException(error.getMessage());
}
