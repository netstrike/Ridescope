#include "axis_map.h"

namespace
{
    int axisMagnitude(AxisRef axisRef)
    {
        switch (axisRef)
        {
            case AxisRef::PosX:
            case AxisRef::NegX:
                return 1;
            case AxisRef::PosY:
            case AxisRef::NegY:
                return 2;
            case AxisRef::PosZ:
            case AxisRef::NegZ:
                return 3;
            default:
                return 0;
        }
    }

    float selectAxis(AxisRef axisRef, float x, float y, float z)
    {
        const float magnitude = (axisMagnitude(axisRef) == 1)
            ? x
            : (axisMagnitude(axisRef) == 2)
                ? y
                : z;
        return (static_cast<int8_t>(axisRef) < 0) ? -magnitude : magnitude;
    }
}

namespace AxisMap
{
    bool parseAxisRef(const String& value, AxisRef& axisRef)
    {
        String normalized = value;
        normalized.trim();
        normalized.toLowerCase();

        if (normalized == "x" || normalized == "+x")
        {
            axisRef = AxisRef::PosX;
            return true;
        }
        if (normalized == "-x")
        {
            axisRef = AxisRef::NegX;
            return true;
        }
        if (normalized == "y" || normalized == "+y")
        {
            axisRef = AxisRef::PosY;
            return true;
        }
        if (normalized == "-y")
        {
            axisRef = AxisRef::NegY;
            return true;
        }
        if (normalized == "z" || normalized == "+z")
        {
            axisRef = AxisRef::PosZ;
            return true;
        }
        if (normalized == "-z")
        {
            axisRef = AxisRef::NegZ;
            return true;
        }

        return false;
    }

    const char* toString(AxisRef axisRef)
    {
        switch (axisRef)
        {
            case AxisRef::PosX: return "+x";
            case AxisRef::NegX: return "-x";
            case AxisRef::PosY: return "+y";
            case AxisRef::NegY: return "-y";
            case AxisRef::PosZ: return "+z";
            case AxisRef::NegZ: return "-z";
            default: return "?";
        }
    }

    bool isAxisMapValid(const AxisMapConfig& axisMap)
    {
        const int lateral = axisMagnitude(axisMap.bodyLateralAxis);
        const int longitudinal = axisMagnitude(axisMap.bodyLongitudinalAxis);
        const int vertical = axisMagnitude(axisMap.bodyVerticalAxis);

        if (lateral == 0 || longitudinal == 0 || vertical == 0)
        {
            return false;
        }

        return lateral != longitudinal &&
               lateral != vertical &&
               longitudinal != vertical;
    }

    RawImuData mapToBodyFrame(const AxisMapConfig& axisMap, const RawImuData& sensorFrame)
    {
        RawImuData bodyFrame;
        bodyFrame.accelX = selectAxis(axisMap.bodyLateralAxis, sensorFrame.accelX, sensorFrame.accelY, sensorFrame.accelZ);
        bodyFrame.accelY = selectAxis(axisMap.bodyLongitudinalAxis, sensorFrame.accelX, sensorFrame.accelY, sensorFrame.accelZ);
        bodyFrame.accelZ = selectAxis(axisMap.bodyVerticalAxis, sensorFrame.accelX, sensorFrame.accelY, sensorFrame.accelZ);
        bodyFrame.gyroXDegS = selectAxis(axisMap.bodyLateralAxis, sensorFrame.gyroXDegS, sensorFrame.gyroYDegS, sensorFrame.gyroZDegS);
        bodyFrame.gyroYDegS = selectAxis(axisMap.bodyLongitudinalAxis, sensorFrame.gyroXDegS, sensorFrame.gyroYDegS, sensorFrame.gyroZDegS);
        bodyFrame.gyroZDegS = selectAxis(axisMap.bodyVerticalAxis, sensorFrame.gyroXDegS, sensorFrame.gyroYDegS, sensorFrame.gyroZDegS);
        bodyFrame.temperatureC = sensorFrame.temperatureC;
        bodyFrame.sensorTimestampTicks = sensorFrame.sensorTimestampTicks;
        bodyFrame.sensorTimestampTickSeconds = sensorFrame.sensorTimestampTickSeconds;
        bodyFrame.sensorTimestampValid = sensorFrame.sensorTimestampValid;
        return bodyFrame;
    }
}
