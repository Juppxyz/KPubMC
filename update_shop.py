#!/usr/bin/env python3
import json
import os
import sys
from typing import Any, Dict

import jsonschema
from jsonschema import validate
from openai import OpenAI, OpenAIError

CONFIG_PATH = "/mnt/HC_Volume_101895195/KlotzscherPub/plugins/kpub/config.json"

# Schema nur für shopItems
SHOP_ITEMS_SCHEMA: Dict[str, Any] = {
    "type": "array",
    "items": {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "price": {"type": "number"},
            "sell": {"type": "boolean"},
            "amount": {"type": "integer"},
            "description": {"type": "string"},
            "material": {"type": "string"},
        },
        "required": [
            "name",
            "price",
            "sell",
            "amount",
            "description",
            "material",
        ],
        "additionalProperties": False,
    },
    "minItems": 10,
    "maxItems": 10,
}

# Schema für die Modell-Antwort: { "shopItems": [...] }
MODEL_RESPONSE_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "properties": {
        "shopItems": SHOP_ITEMS_SCHEMA,
    },
    "required": ["shopItems"],
    "additionalProperties": False,
}


def get_config_path() -> str:
    """Ermittelt den Pfad zur Config (optional per CLI-Argument überschreibbar)."""
    if len(sys.argv) > 1:
        return sys.argv[1]
    return CONFIG_PATH


def load_config(path: str) -> Dict[str, Any]:
    """Lädt die JSON-Config von der Platte."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"[!] Konfigurationsdatei nicht gefunden: {path}")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"[!] Fehler beim Lesen von {path}: Ungültiges JSON ({e})")
        sys.exit(1)


def write_config(path: str, config_data: Dict[str, Any]) -> None:
    """Schreibt die komplette Config zurück auf die Platte (mit Einrückung)."""
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(config_data, f, indent=2, ensure_ascii=False)
        print("[+] Shop-Konfiguration erfolgreich aktualisiert.")
    except OSError as e:
        print(f"[!] Fehler beim Schreiben der Config: {e}")
        sys.exit(1)


def build_prompt(current_config: Dict[str, Any]) -> str:
    """
    Baut den User-Prompt für das Modell.
    current_config ist die aktuelle Config, die als Vorlage dient.
    """
    return (
        "Du wirkst als Konfigurations-Generator für einen kleinen Minecraft-Ingame-Shop. "
        "Du bekommst eine bestehende Shop-Konfiguration als JSON und sollst NUR das Feld 'shopItems' aktualisieren.\n\n"
        "WICHTIG:\n"
        "- Deine Antwort MUSS ein JSON-Objekt der Form {\"shopItems\": [...]} sein.\n"
        "- Du darfst KEINE anderen Felder zurückgeben.\n\n"
        "Regeln für den Shop:\n"
        "- Aktualisiere einige und/oder alle Items inklusive Preis und Menge, um den Shop dynamisch zu halten.\n"
        "- Wenn ein Item verkauft werden kann (sell = true), ist der Verkaufspreis immer die Hälfte des Kaufpreises, "
        "auf ganze Zahlen gerundet.\n"
        "- Verboten im Shop: Bedrock, Drachen-Ei, Command-Blöcke, Barrieren.\n"
        "- Es muss mindestens 1 klar wertvolles Item geben (z.B. Elytra, Nether Star, sehr seltene Items).\n"
        "- Es müssen mindestens 2 leicht farmbare Items verkauft werden (z.B. Holz, Stein, Weizen, etc.).\n"
        "- Es muss mindestens 1 Nahrungs-Item geben.\n"
        "- Es müssen exakt 10 Items im Shop existieren.\n"
        "- Achte darauf, dass nicht zu viel Geld durch einfache, massenhaft farmbare Items in Umlauf kommt. "
        "Teuer sollen nur wirklich wertvolle Items sein.\n"
        "- Beispiel: Smaragdblöcke sind relativ leicht zu bekommen, eine Elytra ist dagegen deutlich wertvoller.\n"
        "- Nur wertvolle Items bekommen einen eigenen, kreativen Namen unter 'name', muss aber nicht sein. "
        "Dieser Name soll stilvoll und nerdig/geekig sein, aber nicht cringy.\n"
        "- 'Viel Geld' ist in diesem Kontext alles bei etwa 10000.\n\n"
        "Technische Regeln:\n"
        "- Gib ausschließlich ein JSON-Objekt mit dem Feld 'shopItems' zurück.\n"
        "- 'shopItems' muss ein Array mit exakt 10 Elementen sein.\n"
        "- Kein Fließtext, keine Erklärungen, keine Kommentare – nur JSON.\n\n"
        f"Hier ist die aktuelle Konfiguration als Kontext (NICHT direkt zurückgeben, nur zur Orientierung):\n"
        f"{json.dumps(current_config, ensure_ascii=False)}"
    )


def create_client() -> OpenAI:
    """Erzeugt einen OpenAI-Client aus der Umgebungsvariable OPENAI_API_KEY."""
    api_key = "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"
    if not api_key:
        print("[!] Umgebungsvariable OPENAI_API_KEY ist nicht gesetzt.")
        print("    Setze sie z.B. mit: export OPENAI_API_KEY='sk-...' (Linux) oder in Windows-Umgebungsvariablen.")
        sys.exit(1)
    return OpenAI(api_key=api_key)


def call_openai_for_shop_items(
    client: OpenAI, prompt: str, max_retries: int = 2
) -> Dict[str, Any]:
    """
    Ruft das Modell auf und versucht bei JSON/Schema-Fehlern bis zu max_retries erneut.
    Rückgabe: ein Dict mit dem Feld 'shopItems'.
    """
    last_error: str | None = None

    for attempt in range(1, max_retries + 1):
        print(f"[*] Frage OpenAI nach neuen Shop-Items (Versuch {attempt}/{max_retries})...")

        try:
            response = client.chat.completions.create(
                model="gpt-5-mini",  # oder was du gerade nutzt
                response_format={"type": "json_object"},  # zwingt das Modell zu JSON
                messages=[
                    {
                        "role": "system",
                        "content": (
                            "Du bist ein striktes JSON-API-Modul. "
                            "Du antwortest ausschließlich mit einem einzigen gültigen JSON-Objekt "
                            "und fügst niemals Fließtext, Kommentare oder Erklärungen hinzu."
                        ),
                    },
                    {"role": "user", "content": prompt},
                ],
                # temperature NICHT setzen, dieses Modell erlaubt nur den Default
            )
        except OpenAIError as e:
            last_error = f"OpenAI API Fehler: {e}"
            print(f"[!] {last_error}")
            continue

        content = response.choices[0].message.content
        if not content:
            last_error = "Leere Antwort vom Modell erhalten."
            print(f"[!] {last_error}")
            continue

        # JSON parsen
        try:
            parsed = json.loads(content)
        except json.JSONDecodeError as e:
            last_error = f"Fehler beim JSON-Parse: {e}"
            print(f"[!] {last_error}")
            continue

        # Schema-Validierung
        try:
            validate(instance=parsed, schema=MODEL_RESPONSE_SCHEMA)
        except jsonschema.exceptions.ValidationError as e:
            last_error = f"Schema-Validierung fehlgeschlagen: {e.message}"
            print(f"[!] {last_error}")
            continue

        print("[+] Erfolgreiche Antwort vom Modell erhalten und validiert.")
        return parsed

    print("[!] Konnte nach mehreren Versuchen keine gültigen Shop-Items erzeugen.")
    if last_error:
        print(f"    Letzter Fehler: {last_error}")
    sys.exit(1)


def main() -> None:
    config_path = get_config_path()
    print(f"[*] Verwende Config-Pfad: {config_path}")

    print("[*] Lade bestehende Config...")
    current_config = load_config(config_path)

    print("[*] Baue Prompt...")
    prompt = build_prompt(current_config)

    client = create_client()

    print("[*] Generiere neue Shop-Items...")
    model_response = call_openai_for_shop_items(client, prompt)

    new_items = model_response["shopItems"]
    print(f"[*] Ersetze alte shopItems durch {len(new_items)} neue Items...")

    current_config["shopItems"] = new_items

    print("[*] Schreibe neue Config auf die Platte...")
    write_config(config_path, current_config)


if __name__ == "__main__":
    main()
