#pragma once

#include <Arduino.h>
#include <ArduinoJson.h>

// Esito tipizzato della lettura di un campo JSON.
enum class JsonFieldResult
{
    Missing,
    Ok,
    InvalidType
};

namespace CommJson
{
    // Esegue il parse di un body JSON e garantisce che la radice sia un oggetto.
    bool parseObject(const String& json, JsonDocument& doc, JsonObjectConst& root, String& errorMessage, bool allowEmptyBody = false);
    // Legge un campo stringa distinguendo assenza da tipo errato.
    JsonFieldResult readString(const JsonObjectConst& root, const char* key, String& out);
    // Legge un campo booleano distinguendo assenza da tipo errato.
    JsonFieldResult readBool(const JsonObjectConst& root, const char* key, bool& out);
    // Legge un campo numerico convertendolo in float.
    JsonFieldResult readFloat(const JsonObjectConst& root, const char* key, float& out);
    // Legge un intero non negativo convertendolo in uint32_t.
    JsonFieldResult readUInt(const JsonObjectConst& root, const char* key, uint32_t& out);
    // Converte un errore di tipo in messaggio protocollo uniforme.
    bool fieldInvalid(JsonFieldResult result, String& errorMessage, const char* key, const char* expectedType);
}
