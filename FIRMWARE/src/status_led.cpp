#include "status_led.h"

#include "config.h"

#include <Arduino.h>
#include <esp32-hal-rgb-led.h>

namespace
{
    enum class LedMode : uint8_t
    {
        Off,
        Healthy,
        Failure,
        OtaBusy
    };

    uint8_t g_lastRed = 0xFF;
    uint8_t g_lastGreen = 0xFF;
    uint8_t g_lastBlue = 0xFF;

    void writeColor(uint8_t red, uint8_t green, uint8_t blue)
    {
        if (red == g_lastRed && green == g_lastGreen && blue == g_lastBlue)
        {
            return;
        }

        g_lastRed = red;
        g_lastGreen = green;
        g_lastBlue = blue;

        rgbLedWriteOrdered(STATUS_LED_PIN, LED_COLOR_ORDER_RGB, red, green, blue);
    }

    bool phaseOn(uint32_t periodMs, uint32_t onMs)
    {
        const uint32_t phaseMs = millis() % periodMs;
        return phaseMs < onMs;
    }

    LedMode selectMode(const RuntimeState& runtimeState, const CommTransportRuntime& runtime)
    {
        if (runtime.updateInProgress || runtime.rebootScheduled)
        {
            return LedMode::OtaBusy;
        }

        if (!runtime.bleReady)
        {
            return LedMode::Failure;
        }

        if (runtimeState.imuReady)
        {
            return LedMode::Healthy;
        }

        return LedMode::Failure;
    }

    void applyMode(LedMode mode)
    {
        switch (mode)
        {
            case LedMode::Healthy:
                if (phaseOn(1200, 160))
                {
                    writeColor(0, STATUS_LED_BRIGHTNESS, 0);
                }
                else
                {
                    writeColor(0, 0, 0);
                }
                break;

            case LedMode::Failure:
                if (phaseOn(500, 120))
                {
                    writeColor(STATUS_LED_BRIGHTNESS, 0, 0);
                }
                else
                {
                    writeColor(0, 0, 0);
                }
                break;

            case LedMode::OtaBusy:
                if (phaseOn(600, 300))
                {
                    writeColor(0, 0, STATUS_LED_BRIGHTNESS);
                }
                else
                {
                    writeColor(0, 0, 0);
                }
                break;

            case LedMode::Off:
            default:
                writeColor(0, 0, 0);
                break;
        }
    }
}

namespace StatusLed
{
    void begin()
    {
        if (!STATUS_LED_ENABLED)
        {
            return;
        }

        applyMode(LedMode::Off);
    }

    void update(const RuntimeState& runtimeState, const CommTransportRuntime& runtime)
    {
        if (!STATUS_LED_ENABLED)
        {
            return;
        }

        applyMode(selectMode(runtimeState, runtime));
    }
}
