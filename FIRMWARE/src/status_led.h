#pragma once

#include "comm_shared.h"

// Indicatore locale su LED onboard:
// - verde lampeggiante quando il sistema e sano
// - rosso lampeggiante sui fail reali
// - blu lampeggiante durante OTA
namespace StatusLed
{
    void begin();
    void update(const RuntimeState& runtimeState, const CommTransportRuntime& runtime);
}
