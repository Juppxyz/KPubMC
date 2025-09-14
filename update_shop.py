import json
import jsonschema
from jsonschema import validate
import openai
from openai import OpenAI

client = OpenAI(api_key="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")

CONFIG_PATH = "/mnt/HC_Volume_101895195/KlotzscherPub/plugins/kpub/config.json"

shop_schema = {
    "type": "object",
    "properties": {
        "serverMOTD": {"type": "string"},
        "tradeTax": {"type": "number"},
        "netherTransferTax": {"type": "number"},
        "deathTax": {"type": "number"},
        "shopItems": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "price": {"type": "number"},
                    "sell": {"type": "boolean"},
                    "amount": {"type": "integer"},
                    "description": {"type": "string"},
                    "material": {"type": "string"}
                },
                "required": ["name", "price", "sell", "amount", "description", "material"]
            },
            "minItems": 10,
            "maxItems": 10
        }
    },
    "required": ["serverMOTD", "tradeTax", "netherTransferTax", "deathTax", "shopItems"]
}


def load_config(path):
    with open(path, "r") as f:
        return json.load(f)

def update_config(config_data):
    with open(CONFIG_PATH, "w") as f:
        json.dump(config_data, f, indent=2)

def generate_new_shop(current_json):
    prompt = (
        "Du stellst einen kleinen Minecraft-Ingame Shop da. Bitte aktualisiere einige und/oder alle Items inklusive Preis und Menge, um den Shop dynamisch zu halten. "
        "Wenn ein Item verkauft werden kann, ist es immer die Hälfte (gerundet) des eigentlichen Preises. Bedingungen: Es darf kein Bedrock im Shop sein. "
        "Es darf kein Drachen-Ei im Shop sein. Es darf kein Command-Block im Shop sein. Es darf keine Barrieren im Shop geben. Es muss mindestens 1 wertvolles Item geben. "
        "Es muss mindestens 2 leicht farmbare Items zum verkauf geben. Es muss ein Nahrungs-Item vorhanden sein. Es müssen exakt 10 Items im Shop existieren. "
        "Bitte achte darauf, dass nicht unnötig viel Geld in Umlauf kommt, zb durch simple, einfache Items welche man verkaufen kann. Teuer sollen nur sehr wertvolle Items sein."
        "Bitte brüfe alles doppelt, damit keine Fehler entstehen. Beispiel: Smaragdblöcke sind leicht zu bekommen, eine Elytra ist aber wertvoll."
        "Nur wertvolle Items bekommen einen Namen (gestalterisch, kreativ, im Bezug auf Nerd-Geek-Inside Wissen, aber bitte nicht cringe oder peinlich). "
        "Viel Geld ist in diesem Kontext 10000"
        "Das Rückgabeformat muss gültiges JSON sein, minified, also ohne Enter und unnötige Leerzeichen. "
        "Bitte antworte NUR mit der neu generierten JSON antworten! Du darfst nix anderes ändern, also keine Steuern, keine MOTD, nur die ShopItems. "
        f"Hier eine Vorlage damit du die Struktur kennst an welche du dich halten musst: {json.dumps(current_json)}")

    print("[] Frage ChatGPT:", prompt)
    response = client.chat.completions.create(
        model="gpt-4.1",
        messages=[{"role": "user", "content": prompt}],
    )
    return response.choices[0].message.content.strip()


def is_valid_shop_format(data):
    try:
        validate(instance=data, schema=shop_schema)
        return True
    except jsonschema.exceptions.ValidationError as e:
        print(f"[!] Formatfehler: {e.message}")
        return False

# Hauptlogik
if __name__ == "__main__":
    print("[] Prüfe Config..")
    config = load_config(CONFIG_PATH)
    new_shop_raw = generate_new_shop(config)
    print("debug:", new_shop_raw )
    try:
        parsed_shop = json.loads(new_shop_raw)
    except json.JSONDecodeError as e:
        print(f"[!] Fehler beim Parsen der API-Antwort: {e}")
        exit(1)

    if is_valid_shop_format(parsed_shop):
        update_config(parsed_shop)
        print("[] Shop wurde erfolgreich geupdated!")
    else:
        print("[] Fehler beim updaten des Shops – ungültiges Format.")


