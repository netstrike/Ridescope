#include "comm_json.h"

#include "messages.h"

namespace CommJson
{
    // Deserializza il JSON del client e prepara la radice oggetto per il chiamante.
    bool parseObject(const String& json, JsonDocument& doc, JsonObjectConst& root, String& errorMessage, bool allowEmptyBody)
    {
        // Alcuni endpoint accettano body vuoto per usare default firmware.
        if (json.length() == 0)
        {
            if (!allowEmptyBody)
            {
                errorMessage = Messages::Protocol::kJsonNonValido;
                return false;
            }
            doc.clear();
            doc.to<JsonObject>();
            root = doc.as<JsonObjectConst>();
            return true;
        }

        DeserializationError error = deserializeJson(doc, json);
        if (error || !doc.is<JsonObject>())
        {
            errorMessage = Messages::Protocol::kJsonNonValido;
            return false;
        }

        root = doc.as<JsonObjectConst>();
        return true;
    }

    // Estrae un campo testuale senza alterare il documento JSON.
    JsonFieldResult readString(const JsonObjectConst& root, const char* key, String& out)
    {
        JsonVariantConst value = root[key];
        if (value.isNull()) return JsonFieldResult::Missing;
        if (!value.is<const char*>()) return JsonFieldResult::InvalidType;
        out = String(value.as<const char*>());
        return JsonFieldResult::Ok;
    }

    // Estrae un booleano dal payload JSON.
    JsonFieldResult readBool(const JsonObjectConst& root, const char* key, bool& out)
    {
        JsonVariantConst value = root[key];
        if (value.isNull()) return JsonFieldResult::Missing;
        if (!value.is<bool>()) return JsonFieldResult::InvalidType;
        out = value.as<bool>();
        return JsonFieldResult::Ok;
    }

    // Estrae un numero reale o intero dal payload JSON.
    JsonFieldResult readFloat(const JsonObjectConst& root, const char* key, float& out)
    {
        JsonVariantConst value = root[key];
        if (value.isNull()) return JsonFieldResult::Missing;
        if (!value.is<float>() && !value.is<double>() && !value.is<int>() && !value.is<long>() &&
            !value.is<unsigned int>() && !value.is<unsigned long>()) return JsonFieldResult::InvalidType;
        out = value.as<float>();
        return JsonFieldResult::Ok;
    }

    // Estrae un intero senza segno dal payload rifiutando numeri negativi.
    JsonFieldResult readUInt(const JsonObjectConst& root, const char* key, uint32_t& out)
    {
        JsonVariantConst value = root[key];
        if (value.isNull()) return JsonFieldResult::Missing;
        // Il protocollo tratta gli unsigned come valori non negativi
        // compatibili con il range uint32_t.
        if (!value.is<int>() && !value.is<long>() && !value.is<unsigned int>() && !value.is<unsigned long>()) return JsonFieldResult::InvalidType;
        const long numeric = value.as<long>();
        if (numeric < 0) return JsonFieldResult::InvalidType;
        out = static_cast<uint32_t>(numeric);
        return JsonFieldResult::Ok;
    }

    // Traduce un errore di tipo nel messaggio uniforme usato dal protocollo.
    bool fieldInvalid(JsonFieldResult result, String& errorMessage, const char* key, const char* expectedType)
    {
        // I campi mancanti non sono errori qui: il chiamante decide
        // se il campo e opzionale o obbligatorio nel proprio contesto.
        if (result != JsonFieldResult::InvalidType) return false;
        errorMessage = Messages::Protocol::campoDeveEssere(key, expectedType);
        return true;
    }
}
