# =============================================================================
# Server-side message text. Plan sections 6.3, 6.15 and 6.16.
#
# The app translates its own screens, but some words are said by the server:
# the reminder push, the "has not checked in" alert a daughter reads at 3am, the
# all-clear. Those must be in the recipient's language, so they live here.
#
# Every safety string is written in all ten launch languages. Plan 6.16 says
# every string is translated by people and reviewed by a native speaker over 60;
# these are first drafts for that review and `NEEDS_NATIVE_REVIEW` says so, so
# nobody mistakes a draft for a signed-off translation. English is always the
# fallback, and a missing key returns the key rather than raising.
# =============================================================================
from __future__ import annotations

NEEDS_NATIVE_REVIEW = ("mr", "bn", "ta", "te", "kn", "ml", "gu", "pa")

# key -> lang -> template. {who}, {last}, {name}, {dose}, {when}, {what}, {days}
_M: dict[str, dict[str, str]] = {
    # --- the user themselves --------------------------------------------------
    "remind_title": {
        "en": "Please check in",
        "hi": "कृपया चेक-इन कीजिए",
        "mr": "कृपया चेक-इन करा",
        "bn": "অনুগ্রহ করে চেক-ইন করুন",
        "ta": "தயவுசெய்து செக்-இன் செய்யுங்கள்",
        "te": "దయచేసి చెక్-ఇన్ చేయండి",
        "kn": "ದಯವಿಟ್ಟು ಚೆಕ್-ಇನ್ ಮಾಡಿ",
        "ml": "ദയവായി ചെക്ക്-ഇൻ ചെയ്യൂ",
        "gu": "કૃપા કરીને ચેક-ઇન કરો",
        "pa": "ਕਿਰਪਾ ਕਰਕੇ ਚੈੱਕ-ਇਨ ਕਰੋ",
    },
    "remind_body": {
        "en": "Namaste {who}, tap “I’m fine” so your family knows you are well.",
        "hi": "नमस्ते {who}, “मैं ठीक हूँ” दबाइए ताकि आपके परिवार को पता रहे कि आप ठीक हैं।",
        "mr": "नमस्कार {who}, “मी ठीक आहे” दाबा म्हणजे कुटुंबाला कळेल की तुम्ही ठीक आहात.",
        "bn": "নমস্কার {who}, “আমি ভালো আছি” চাপুন, যাতে পরিবার জানতে পারে আপনি ভালো আছেন।",
        "ta": "வணக்கம் {who}, “நான் நலம்” என்பதை அழுத்துங்கள்; உங்கள் குடும்பத்திற்கு நீங்கள் நலமாக இருப்பது தெரியும்.",
        "te": "నమస్కారం {who}, “నేను బాగున్నాను” నొక్కండి, మీ కుటుంబానికి మీరు క్షేమంగా ఉన్నారని తెలుస్తుంది.",
        "kn": "ನಮಸ್ಕಾರ {who}, “ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ” ಒತ್ತಿ, ನಿಮ್ಮ ಕುಟುಂಬಕ್ಕೆ ನೀವು ಕ್ಷೇಮವಾಗಿದ್ದೀರಿ ಎಂದು ತಿಳಿಯುತ್ತದೆ.",
        "ml": "നമസ്കാരം {who}, “ഞാൻ സുഖമായിരിക്കുന്നു” അമർത്തൂ, നിങ്ങൾ സുഖമായിരിക്കുന്നുവെന്ന് കുടുംബം അറിയും.",
        "gu": "નમસ્તે {who}, “હું સારો છું” દબાવો, જેથી પરિવારને ખબર પડે કે તમે ઠીક છો.",
        "pa": "ਸਤ ਸ੍ਰੀ ਅਕਾਲ {who}, “ਮੈਂ ਠੀਕ ਹਾਂ” ਦਬਾਓ ਤਾਂ ਜੋ ਪਰਿਵਾਰ ਨੂੰ ਪਤਾ ਲੱਗੇ ਕਿ ਤੁਸੀਂ ਠੀਕ ਹੋ।",
    },
    # --- guardians ------------------------------------------------------------
    "missed_title": {
        "en": "{who} has not checked in",
        "hi": "{who} ने चेक-इन नहीं किया",
        "mr": "{who} यांनी चेक-इन केले नाही",
        "bn": "{who} চেক-ইন করেননি",
        "ta": "{who} செக்-இன் செய்யவில்லை",
        "te": "{who} చెక్-ఇన్ చేయలేదు",
        "kn": "{who} ಚೆಕ್-ಇನ್ ಮಾಡಿಲ್ಲ",
        "ml": "{who} ചെക്ക്-ഇൻ ചെയ്തിട്ടില്ല",
        "gu": "{who} એ ચેક-ઇન કર્યું નથી",
        "pa": "{who} ਨੇ ਚੈੱਕ-ਇਨ ਨਹੀਂ ਕੀਤਾ",
    },
    "missed_body": {
        "en": "{who} has not checked in today and did not answer our call. Please phone them now. Last check-in: {last}.",
        "hi": "{who} ने आज चेक-इन नहीं किया और हमारा फ़ोन भी नहीं उठाया। कृपया अभी उन्हें फ़ोन कीजिए। आख़िरी चेक-इन: {last}।",
        "mr": "{who} यांनी आज चेक-इन केले नाही आणि आमचा फोनही उचलला नाही. कृपया लगेच त्यांना फोन करा. शेवटचे चेक-इन: {last}.",
        "bn": "{who} আজ চেক-ইন করেননি এবং আমাদের ফোনও ধরেননি। অনুগ্রহ করে এখনই ফোন করুন। শেষ চেক-ইন: {last}।",
        "ta": "{who} இன்று செக்-இன் செய்யவில்லை, எங்கள் அழைப்பையும் ஏற்கவில்லை. உடனே அவரை அழையுங்கள். கடைசி செக்-இன்: {last}.",
        "te": "{who} ఈరోజు చెక్-ఇన్ చేయలేదు, మా కాల్‌కు కూడా స్పందించలేదు. దయచేసి వెంటనే ఫోన్ చేయండి. చివరి చెక్-ఇన్: {last}.",
        "kn": "{who} ಇಂದು ಚೆಕ್-ಇನ್ ಮಾಡಿಲ್ಲ ಮತ್ತು ನಮ್ಮ ಕರೆಗೂ ಉತ್ತರಿಸಿಲ್ಲ. ದಯವಿಟ್ಟು ಈಗಲೇ ಕರೆ ಮಾಡಿ. ಕೊನೆಯ ಚೆಕ್-ಇನ್: {last}.",
        "ml": "{who} ഇന്ന് ചെക്ക്-ഇൻ ചെയ്തില്ല, ഞങ്ങളുടെ വിളിയും എടുത്തില്ല. ദയവായി ഉടൻ വിളിക്കൂ. അവസാന ചെക്ക്-ഇൻ: {last}.",
        "gu": "{who} એ આજે ચેક-ઇન કર્યું નથી અને અમારો ફોન પણ ઉપાડ્યો નથી. કૃપા કરીને હમણાં જ ફોન કરો. છેલ્લું ચેક-ઇન: {last}.",
        "pa": "{who} ਨੇ ਅੱਜ ਚੈੱਕ-ਇਨ ਨਹੀਂ ਕੀਤਾ ਅਤੇ ਸਾਡੀ ਕਾਲ ਵੀ ਨਹੀਂ ਚੁੱਕੀ। ਕਿਰਪਾ ਕਰਕੇ ਹੁਣੇ ਫ਼ੋਨ ਕਰੋ। ਆਖ਼ਰੀ ਚੈੱਕ-ਇਨ: {last}।",
    },
    "helpers_title": {
        "en": "{who} may need help",
        "hi": "{who} को मदद की ज़रूरत हो सकती है",
        "mr": "{who} यांना मदतीची गरज असू शकते",
        "bn": "{who}-এর সাহায্য লাগতে পারে",
        "ta": "{who} அவர்களுக்கு உதவி தேவைப்படலாம்",
        "te": "{who}కు సహాయం అవసరం కావచ్చు",
        "kn": "{who} ಅವರಿಗೆ ಸಹಾಯ ಬೇಕಾಗಬಹುದು",
        "ml": "{who}-ന് സഹായം വേണ്ടിവന്നേക്കാം",
        "gu": "{who} ને મદદની જરૂર હોઈ શકે",
        "pa": "{who} ਨੂੰ ਮਦਦ ਦੀ ਲੋੜ ਹੋ ਸਕਦੀ ਹੈ",
    },
    "helpers_body": {
        "en": "{who} has not been reached for over an hour. If you are nearby, please check on them and tell their family.",
        "hi": "{who} से एक घंटे से ज़्यादा से संपर्क नहीं हो पाया है। अगर आप पास में हैं, तो कृपया जाकर देखिए और उनके परिवार को बताइए।",
        "mr": "{who} यांच्याशी तासाभराहून अधिक काळ संपर्क झालेला नाही. तुम्ही जवळ असाल तर कृपया जाऊन पाहा आणि कुटुंबाला कळवा.",
        "bn": "এক ঘণ্টারও বেশি সময় ধরে {who}-এর সঙ্গে যোগাযোগ হয়নি। কাছাকাছি থাকলে একবার দেখে পরিবারকে জানান।",
        "ta": "{who} ஐ ஒரு மணி நேரத்துக்கு மேல் தொடர்பு கொள்ள முடியவில்லை. அருகில் இருந்தால் சென்று பார்த்து குடும்பத்துக்குத் தெரிவியுங்கள்.",
        "te": "{who}ను ఒక గంట కంటే ఎక్కువసేపు సంప్రదించలేకపోయాం. మీరు దగ్గరలో ఉంటే వెళ్లి చూసి కుటుంబానికి చెప్పండి.",
        "kn": "{who} ಅವರನ್ನು ಒಂದು ಗಂಟೆಗೂ ಹೆಚ್ಚು ಕಾಲ ಸಂಪರ್ಕಿಸಲಾಗಿಲ್ಲ. ಹತ್ತಿರವಿದ್ದರೆ ಹೋಗಿ ನೋಡಿ ಕುಟುಂಬಕ್ಕೆ ತಿಳಿಸಿ.",
        "ml": "{who}-നെ ഒരു മണിക്കൂറിലധികമായി ബന്ധപ്പെടാനായില്ല. അടുത്തുണ്ടെങ്കിൽ ചെന്ന് നോക്കി കുടുംബത്തെ അറിയിക്കൂ.",
        "gu": "{who} નો એક કલાકથી વધુ સમયથી સંપર્ક થઈ શક્યો નથી. જો તમે નજીક હો તો જઈને જુઓ અને પરિવારને જણાવો.",
        "pa": "{who} ਨਾਲ ਇੱਕ ਘੰਟੇ ਤੋਂ ਵੱਧ ਸਮੇਂ ਤੋਂ ਸੰਪਰਕ ਨਹੀਂ ਹੋ ਸਕਿਆ। ਜੇ ਤੁਸੀਂ ਨੇੜੇ ਹੋ ਤਾਂ ਜਾ ਕੇ ਵੇਖੋ ਅਤੇ ਪਰਿਵਾਰ ਨੂੰ ਦੱਸੋ।",
    },
    "all_fine_title": {
        "en": "{who} is fine",
        "hi": "{who} ठीक हैं",
        "mr": "{who} ठीक आहेत",
        "bn": "{who} ভালো আছেন",
        "ta": "{who} நலமாக இருக்கிறார்",
        "te": "{who} క్షేమంగా ఉన్నారు",
        "kn": "{who} ಕ್ಷೇಮವಾಗಿದ್ದಾರೆ",
        "ml": "{who} സുഖമായിരിക്കുന്നു",
        "gu": "{who} ઠીક છે",
        "pa": "{who} ਠੀਕ ਹਨ",
    },
    "all_fine_body": {
        "en": "{who} has checked in. Thank you for being there.",
        "hi": "{who} ने चेक-इन कर लिया है। साथ बने रहने के लिए धन्यवाद।",
        "mr": "{who} यांनी चेक-इन केले आहे. सोबत असल्याबद्दल धन्यवाद.",
        "bn": "{who} চেক-ইন করেছেন। পাশে থাকার জন্য ধন্যবাদ।",
        "ta": "{who} செக்-இன் செய்துவிட்டார். உடன் இருப்பதற்கு நன்றி.",
        "te": "{who} చెక్-ఇన్ చేశారు. తోడుగా ఉన్నందుకు ధన్యవాదాలు.",
        "kn": "{who} ಚೆಕ್-ಇನ್ ಮಾಡಿದ್ದಾರೆ. ಜೊತೆಗಿದ್ದಕ್ಕೆ ಧನ್ಯವಾದಗಳು.",
        "ml": "{who} ചെക്ക്-ഇൻ ചെയ്തു. കൂടെയുണ്ടായതിന് നന്ദി.",
        "gu": "{who} એ ચેક-ઇન કરી લીધું છે. સાથે રહેવા બદલ આભાર.",
        "pa": "{who} ਨੇ ਚੈੱਕ-ਇਨ ਕਰ ਲਿਆ ਹੈ। ਨਾਲ ਖੜ੍ਹਨ ਲਈ ਧੰਨਵਾਦ।",
    },
    "paused_title": {
        "en": "{who} has paused check-ins",
        "hi": "{who} ने चेक-इन कुछ दिन रोके हैं",
    },
    "paused_body": {
        "en": "{who} has paused daily check-ins for {days} days (travel or hospital). You will not be alerted until then.",
        "hi": "{who} ने {days} दिन के लिए रोज़ का चेक-इन रोका है (यात्रा या अस्पताल)। तब तक आपको अलर्ट नहीं मिलेगा।",
    },
    # --- medicines ------------------------------------------------------------
    "dose_title": {
        "en": "Time for your medicine",
        "hi": "दवा का समय हो गया",
        "mr": "औषधाची वेळ झाली",
        "bn": "ওষুধের সময় হয়েছে",
        "ta": "மருந்து சாப்பிட வேண்டிய நேரம்",
        "te": "మందు వేసుకునే సమయం",
        "kn": "ಔಷಧಿಯ ಸಮಯ",
        "ml": "മരുന്നിന്റെ സമയം",
        "gu": "દવાનો સમય થયો",
        "pa": "ਦਵਾਈ ਦਾ ਸਮਾਂ",
    },
    "dose_body": {
        "en": "{name} — {dose}",
        "hi": "{name} — {dose}",
    },
    "dose_missed_title": {
        "en": "{who} missed medicines",
        "hi": "{who} ने दवाइयाँ नहीं लीं",
    },
    "dose_missed_body": {
        "en": "{who} has missed {count} doses in a row. You may want to call them.",
        "hi": "{who} की लगातार {count} खुराकें छूट गई हैं। आप उन्हें फ़ोन करना चाहेंगे।",
    },
    # --- pension reminders ----------------------------------------------------
    "life_cert_title": {
        "en": "Life certificate due",
        "hi": "जीवन प्रमाण पत्र देना है",
    },
    "life_cert_body": {
        "en": "Submit your Jeevan Pramaan before 30 November so your pension is not stopped. Doosri Pari can help you step by step.",
        "hi": "पेंशन न रुके, इसके लिए 30 नवंबर से पहले जीवन प्रमाण जमा कीजिए। दूसरी पारी आपको कदम-कदम पर मदद करेगी।",
    },
    "digest_title": {
        "en": "Your circles today",
        "hi": "आज आपके सर्कल में",
    },
    "digest_body": {
        "en": "{count} new posts from your circles.",
        "hi": "आपके सर्कल में {count} नई पोस्ट।",
    },
    "weekly_title": {
        "en": "{who}'s week",
        "hi": "{who} का हफ़्ता",
    },
    "weekly_body": {
        "en": "{who} checked in {days} of 7 days this week.{extra}",
        "hi": "{who} ने इस हफ़्ते 7 में से {days} दिन चेक-इन किया।{extra}",
    },
}


def t(key: str, lang: str | None = "en", **vars) -> str:
    """
    A message in `lang`, English if that language has none, the key if neither.

    A missing variable is left as its own placeholder rather than raising: a
    notification with "{last}" in it is ugly, a notification that never sent
    because a template threw is a missed check-in.
    """
    table = _M.get(key)
    if table is None:
        return key
    text = table.get(lang or "en") or table["en"]
    try:
        return text.format(**vars)
    except (KeyError, IndexError, ValueError):
        return text


def who(user) -> str:  # noqa: ANN001
    """How to refer to a pensioner in a message about them."""
    return (
        getattr(user, "address_as", None)
        or getattr(user, "name", None)
        or "Your family member"
    )
