// Filtri e logica di stima dell'inclinazione.
// Pipeline attuale:
// 1) low-pass opzionale su accelerometro
// 2) low-pass opzionale su giroscopio
// 3) calcolo angoli da accelerometro
// 4) filtro complementare con trust adattivo sull'accelerometro
// 5) applicazione dello zero di riferimento

#include "filters.h"
#include "axis_map.h"
#include "config.h"

#include <math.h>

namespace
{
    static constexpr float kRadToDegFactor = 57.2957795f;
    static constexpr float kDegToRadFactor = 0.01745329252f;
    static constexpr float DEFAULT_DT_S = LOOP_INTERVAL_MS / 1000.0f;
    static constexpr float MAX_DT_S = 0.1f;
    static constexpr float QUASI_STATIC_ACCEL_ERROR_G = 0.08f;
    static constexpr float QUASI_STATIC_GYRO_DPS = 3.0f;
    static constexpr float GYRO_BIAS_ADAPT_ALPHA = 0.0025f;
    // Angolo di pitch massimo (in radianti) per il calcolo di tan(pitch) nelle equazioni cinematiche.
    // Protegge dalla singolarità di gimbal lock a ±90°; per una moto è un limite mai raggiunto.
    static constexpr float MAX_PITCH_RAD_FOR_TAN = 85.0f * kDegToRadFactor;
    // Soglia di velocità GPS sotto cui considerare il veicolo fermo ai fini del bias gyro.
    // 0.5 m/s ≈ 1.8 km/h: copre soste in box con motore acceso e vibrazioni che ingannerebbero l'IMU.
    static constexpr float GPS_STATIONARY_SPEED_MS = 0.5f;
    // Accelerazione di gravità in m/s² usata per normalizzare la correzione centripeta in g.
    static constexpr float kGravityMs2 = 9.80665f;

    // Limita un valore nel range [0,1].
    float clamp01(float value)
    {
        if (value < 0.0f) return 0.0f;
        if (value > 1.0f) return 1.0f;
        return value;
    }

    // Garantisce un valore strettamente positivo oppure usa un fallback noto.
    float clampPositive(float value, float fallbackValue)
    {
        return (value > 0.0f) ? value : fallbackValue;
    }

    // Limita un valore al range chiuso indicato.
    float clampRange(float value, float minValue, float maxValue)
    {
        if (value < minValue) return minValue;
        if (value > maxValue) return maxValue;
        return value;
    }

    // Restituisce un fattore 0..1 che indica quanto fidarsi dell accelerometro.
    // Se il modulo dell accelerazione e vicino a 1 g, il trust resta alto.
    float computeAccelTrust(const AppConfig& config, float magnitudeG)
    {
        if (!config.adaptiveAccelTrustEnabled)
        {
            return 1.0f;
        }

        const float minG = config.accelTrustMinG;
        const float maxG = config.accelTrustMaxG;
        const float fadeSpan = clampPositive(config.accelTrustFadeSpanG, 0.25f);

        if (magnitudeG >= minG && magnitudeG <= maxG)
        {
            return 1.0f;
        }

        if (magnitudeG < 0.01f)
        {
            return 0.0f;
        }

        const float distance = (magnitudeG < minG)
            ? (minG - magnitudeG)
            : (magnitudeG - maxG);

        return clamp01(1.0f - (distance / fadeSpan));
    }

    // Converte il vettore accelerometrico in angoli roll/pitch stimati.
    void computeAccelAngles(float ax, float ay, float az, float& rollDeg, float& pitchDeg)
    {
        rollDeg = atan2f(ax, az) * kRadToDegFactor;
        pitchDeg = atan2f(-ay, sqrtf(ax * ax + az * az)) * kRadToDegFactor;
    }

    // Converte un alpha nominale (riferito a DEFAULT_DT_S) in un alpha consistente con il dt effettivo.
    // Formula: alpha_actual = 1 - (1 - alpha_nom)^(dt / dt_nom)
    // Garantisce che la frequenza di taglio del filtro EMA sia invariante rispetto al sample rate.
    float computeDtConsistentAlpha(float nominalAlpha, float dt, float nominalDtS)
    {
        if (nominalAlpha <= 0.0f) return 0.0f;
        if (nominalAlpha >= 1.0f) return 1.0f;
        const float safeNominalDt = clampPositive(nominalDtS, DEFAULT_DT_S);
        return 1.0f - powf(1.0f - nominalAlpha, dt / safeNominalDt);
    }

    // Ricava il dt dal timestamp hardware del campione IMU quando disponibile.
    float computeImuDeltaTimeSeconds(const RawImuData& imuData, FilterState& state)
    {
        float dtSeconds = 0.0f;

        if (imuData.sensorTimestampValid &&
            state.lastSensorTimestampValid &&
            imuData.sensorTimestampTickSeconds > 0.0f)
        {
            const uint32_t deltaTicks = imuData.sensorTimestampTicks - state.lastSensorTimestampTicks;
            if (deltaTicks > 0U)
            {
                dtSeconds = static_cast<float>(deltaTicks) * imuData.sensorTimestampTickSeconds;
            }
        }

        if (imuData.sensorTimestampValid)
        {
            state.lastSensorTimestampTicks = imuData.sensorTimestampTicks;
            state.lastSensorTimestampValid = true;
        }
        else
        {
            state.lastSensorTimestampTicks = 0;
            state.lastSensorTimestampValid = false;
        }

        return dtSeconds;
    }
}

namespace Filters
{
    // Azzera e prepara lo stato interno della pipeline di filtro.
    void begin(FilterState& state)
    {
        state.rollDeg = 0.0f;
        state.pitchDeg = 0.0f;
        state.accelLPFX = 0.0f;
        state.accelLPFY = 0.0f;
        state.accelLPFZ = 1.0f;
        state.gyroLPFXDegS = 0.0f;
        state.gyroLPFYDegS = 0.0f;
        state.gyroLPFZDegS = 0.0f;
        state.gyroRuntimeBiasXDegS = 0.0f;
        state.gyroRuntimeBiasYDegS = 0.0f;
        state.gyroRuntimeBiasZDegS = 0.0f;
        state.lastSensorTimestampTicks = 0;
        state.lastUpdateMs = 0;
        state.lastSensorTimestampValid = false;
        state.initialized = false;
    }

    // Esegue l'intera pipeline di fusione sensori e aggiorna lo stato runtime.
    void update(const AppConfig& config, const RawImuData& imuData, const ReferenceData& reference, const GpsAidData& gpsAid, FilterState& state, RuntimeState& runtimeState)
    {
        const uint32_t nowMs = millis();
        float dt = computeImuDeltaTimeSeconds(imuData, state);
        if (!(dt > 0.0f))
        {
            dt = DEFAULT_DT_S;
            if (state.lastUpdateMs != 0 && nowMs > state.lastUpdateMs)
            {
                dt = (nowMs - state.lastUpdateMs) / 1000.0f;
            }
        }
        dt = clampRange(dt, 0.001f, MAX_DT_S);
        state.lastUpdateMs = nowMs;
        runtimeState.sampleRateHz = (dt > 0.0f) ? (1.0f / dt) : 0.0f;

        // Il firmware ragiona sempre in un frame veicolo canonico:
        // X laterale, Y longitudinale, Z verticale.
        const RawImuData bodyFrameImu = AxisMap::mapToBodyFrame(config.axisMap, imuData);
        runtimeState.imuTimestampTicks = bodyFrameImu.sensorTimestampTicks;
        runtimeState.imuTemperatureC = bodyFrameImu.temperatureC;
        // Alpha adattato al dt reale: mantiene la stessa frequenza di taglio indipendentemente dal sample rate.
        const float accelAlpha = computeDtConsistentAlpha(config.accelLpfAlpha, dt, DEFAULT_DT_S);
        const float gyroAlpha  = computeDtConsistentAlpha(config.gyroLpfAlpha,  dt, DEFAULT_DT_S);

        if (!state.initialized)
        {
            state.accelLPFX = bodyFrameImu.accelX;
            state.accelLPFY = bodyFrameImu.accelY;
            state.accelLPFZ = bodyFrameImu.accelZ;
            state.gyroLPFXDegS = bodyFrameImu.gyroXDegS;
            state.gyroLPFYDegS = bodyFrameImu.gyroYDegS;
            state.gyroLPFZDegS = bodyFrameImu.gyroZDegS;
        }

        // Prefiltro accelerometro per ridurre rumore e vibrazioni ad alta frequenza.
        if (config.accelLpfEnabled)
        {
            state.accelLPFX += accelAlpha * (bodyFrameImu.accelX - state.accelLPFX);
            state.accelLPFY += accelAlpha * (bodyFrameImu.accelY - state.accelLPFY);
            state.accelLPFZ += accelAlpha * (bodyFrameImu.accelZ - state.accelLPFZ);
        }
        else
        {
            state.accelLPFX = bodyFrameImu.accelX;
            state.accelLPFY = bodyFrameImu.accelY;
            state.accelLPFZ = bodyFrameImu.accelZ;
        }

        // Prefiltro giroscopio leggero per stabilizzare l integrazione senza perdere troppa dinamica.
        if (config.gyroLpfEnabled)
        {
            state.gyroLPFXDegS += gyroAlpha * (bodyFrameImu.gyroXDegS - state.gyroLPFXDegS);
            state.gyroLPFYDegS += gyroAlpha * (bodyFrameImu.gyroYDegS - state.gyroLPFYDegS);
            state.gyroLPFZDegS += gyroAlpha * (bodyFrameImu.gyroZDegS - state.gyroLPFZDegS);
        }
        else
        {
            state.gyroLPFXDegS = bodyFrameImu.gyroXDegS;
            state.gyroLPFYDegS = bodyFrameImu.gyroYDegS;
            state.gyroLPFZDegS = bodyFrameImu.gyroZDegS;
        }

        // Correzione centripeta sull'asse laterale (X) dell'accelerometro.
        // In curva la moto genera a_c = v * ω_yaw che si somma alla gravità sul lato.
        // Senza questa correzione l'accelerometro non è un riferimento assoluto valido in curva
        // e il trust adattivo lo sfiducia, lasciando il gyro integrare liberamente.
        // Con la correzione il trust rimane alto durante tutta la traiettoria.
        //
        // Usa gyroZ già bias-corretto con il residuo runtime (bias grossolano rimosso dalla
        // calibrazione statica, residuo fine dall'adattamento quasi-statico).
        // gyroZ ha una latenza di una iterazione rispetto al bias attuale: trascurabile a 100 Hz.
        //
        // Se gpsAid.valid è false (GPS assente, fix perso o scaduto) la correzione
        // non viene applicata e la pipeline si comporta esattamente come IMU-only.
        float accelXForAngles = state.accelLPFX;
        if (gpsAid.valid)
        {
            const float correctedGyroZForCentripetal = state.gyroLPFZDegS - state.gyroRuntimeBiasZDegS;
            const float yawRateRadS = correctedGyroZForCentripetal * kDegToRadFactor;
            const float centripetalG = (gpsAid.speedMs * yawRateRadS) / kGravityMs2;
            accelXForAngles -= centripetalG;
        }

        float accelRollDeg = 0.0f;
        float accelPitchDeg = 0.0f;
        computeAccelAngles(accelXForAngles, state.accelLPFY, state.accelLPFZ, accelRollDeg, accelPitchDeg);

        const float accelMagnitudeG = sqrtf(
            state.accelLPFX * state.accelLPFX +
            state.accelLPFY * state.accelLPFY +
            state.accelLPFZ * state.accelLPFZ);
        const float gyroMagnitudeDegS = sqrtf(
            state.gyroLPFXDegS * state.gyroLPFXDegS +
            state.gyroLPFYDegS * state.gyroLPFYDegS +
            state.gyroLPFZDegS * state.gyroLPFZDegS);

        // Al primo ciclo si inizializza lo stato del filtro dall accelerometro, evitando il transitorio da 0 gradi.
        if (!state.initialized)
        {
            state.rollDeg = accelRollDeg;
            state.pitchDeg = accelPitchDeg;
            state.initialized = true;
        }

        const float accelTrust = computeAccelTrust(config, accelMagnitudeG);

        // Rilevamento condizione quasi-statica per aggiornamento bias gyro.
        // Il test IMU-only può fallire in sosta con motore acceso (vibrazioni alzano |accel| e gyro).
        // Il test GPS rileva la vera stasi cinematica indipendentemente dalle vibrazioni meccaniche:
        // è attivo solo se gpsAid.valid, quindi degrada silenziosamente senza GPS.
        const bool imuQuasiStatic =
            fabsf(accelMagnitudeG - 1.0f) <= QUASI_STATIC_ACCEL_ERROR_G &&
            gyroMagnitudeDegS <= QUASI_STATIC_GYRO_DPS;
        const bool gpsStationary = gpsAid.valid && gpsAid.speedMs < GPS_STATIONARY_SPEED_MS;
        const bool quasiStatic = imuQuasiStatic || gpsStationary;

        // Aggiorna lentamente il bias residuo del gyro solo in condizioni quasi statiche.
        // In questo modo la deriva cala nelle sessioni lunghe senza inseguire la dinamica reale.
        if (quasiStatic)
        {
            state.gyroRuntimeBiasXDegS += GYRO_BIAS_ADAPT_ALPHA * (state.gyroLPFXDegS - state.gyroRuntimeBiasXDegS);
            state.gyroRuntimeBiasYDegS += GYRO_BIAS_ADAPT_ALPHA * (state.gyroLPFYDegS - state.gyroRuntimeBiasYDegS);
            state.gyroRuntimeBiasZDegS += GYRO_BIAS_ADAPT_ALPHA * (state.gyroLPFZDegS - state.gyroRuntimeBiasZDegS);
        }

        const float correctedGyroXDegS = state.gyroLPFXDegS - state.gyroRuntimeBiasXDegS;
        const float correctedGyroYDegS = state.gyroLPFYDegS - state.gyroRuntimeBiasYDegS;
        const float correctedGyroZDegS = state.gyroLPFZDegS - state.gyroRuntimeBiasZDegS;
        const float baseGyroWeight = clamp01(config.complementaryAlpha);
        const float accelWeight = config.complementaryEnabled ? ((1.0f - baseGyroWeight) * accelTrust) : 1.0f;
        const float gyroWeight = config.complementaryEnabled ? (1.0f - accelWeight) : 0.0f;

        // Fusione principale tra dinamica del gyro e riferimento assoluto dell accelerometro.
        if (config.complementaryEnabled)
        {
            // Equazioni cinematiche di Euler per frame veicolo canonico (X=lat, Y=fwd, Z=up):
            //   roll  (φ) = lean della moto, definito attorno all'asse longitudinale Y
            //   pitch (θ) = assetto naso su/giù, definito attorno all'asse laterale X
            //
            // Le velocità angolari dei singoli assi body si combinano così:
            //   φ̇ = gyroY + (gyroX·sin(φ) − gyroZ·cos(φ))·tan(θ)
            //   θ̇ = gyroX·cos(φ) + gyroZ·sin(φ)
            //
            // A angoli piccoli (φ≈0, θ≈0) le equazioni si riducono all'approssimazione precedente:
            //   φ̇ ≈ gyroY,  θ̇ ≈ gyroX
            // Per lean elevati (>30°, tipici in pista) il contributo di gyroX e gyroZ diventa rilevante.

            const float rollRad  = state.rollDeg  * kDegToRadFactor;
            const float pitchRad = state.pitchDeg * kDegToRadFactor;
            const float pitchClamped = clampRange(pitchRad, -MAX_PITCH_RAD_FOR_TAN, MAX_PITCH_RAD_FOR_TAN);

            const float sinRoll  = sinf(rollRad);
            const float cosRoll  = cosf(rollRad);
            const float tanPitch = tanf(pitchClamped);

            const float rollRateDegS  = correctedGyroYDegS +
                (correctedGyroXDegS * sinRoll - correctedGyroZDegS * cosRoll) * tanPitch;
            const float pitchRateDegS = correctedGyroXDegS * cosRoll + correctedGyroZDegS * sinRoll;

            state.rollDeg  = gyroWeight * (state.rollDeg  + rollRateDegS  * dt) + accelWeight * accelRollDeg;
            state.pitchDeg = gyroWeight * (state.pitchDeg + pitchRateDegS * dt) + accelWeight * accelPitchDeg;
        }
        else
        {
            state.rollDeg = accelRollDeg;
            state.pitchDeg = accelPitchDeg;
        }

        // A questo punto l angolo e gia filtrato nel frame veicolo canonico.
        const float rawRollDeg = state.rollDeg;
        const float rawPitchDeg = state.pitchDeg;

        runtimeState.rawRollDeg = rawRollDeg;
        runtimeState.rawPitchDeg = rawPitchDeg;

        // Stima semplice dell'accelerazione longitudinale lungo Y.
        // Rimuove la componente gravitazionale attesa a partire dal pitch grezzo del sensore.
        const float pitchRad = rawPitchDeg * kDegToRadFactor;
        runtimeState.longitudinalAccelG = state.accelLPFY + sinf(pitchRad);

        // Applicazione dello zero meccanico della moto, distinto dalla calibrazione sensore.
        if (reference.valid)
        {
            runtimeState.rollDeg = rawRollDeg - reference.rollZeroDeg;
            runtimeState.pitchDeg = rawPitchDeg - reference.pitchZeroDeg;
        }
        else
        {
            runtimeState.rollDeg = rawRollDeg;
            runtimeState.pitchDeg = rawPitchDeg;
        }
    }

}
