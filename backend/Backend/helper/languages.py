import re

#----- (ISO 639-2 code, display label, match aliases: full names + ISO 639-2/639-1 codes)
#----- Single source of truth — subtitles.py imports this same table.
LANGUAGES = [
    ("eng", "English", ("english", "eng", "en")),
    ("hin", "Hindi", ("hindi", "hin", "hi")),
    ("tam", "Tamil", ("tamil", "tam", "ta")),
    ("tel", "Telugu", ("telugu", "tel", "te")),
    ("kan", "Kannada", ("kannada", "kan", "kn")),
    ("mal", "Malayalam", ("malayalam", "mal", "ml")),
    ("ben", "Bengali", ("bengali", "bangla", "ben", "bn")),
    ("mar", "Marathi", ("marathi", "mar", "mr")),
    ("pan", "Punjabi", ("punjabi", "panjabi", "pan", "pa")),
    ("guj", "Gujarati", ("gujarati", "guj", "gu")),
    ("urd", "Urdu", ("urdu", "urd", "ur")),
    ("ori", "Odia", ("odia", "oriya", "ori", "or")),
    ("asm", "Assamese", ("assamese", "asm", "as")),
    ("bho", "Bhojpuri", ("bhojpuri", "bho")),
    ("kok", "Konkani", ("konkani", "kok")),
    ("nep", "Nepali", ("nepali", "nep", "ne")),
    ("sin", "Sinhala", ("sinhala", "sinhalese", "sin", "si")),
    ("san", "Sanskrit", ("sanskrit", "san", "sa")),
    ("spa", "Spanish", ("spanish", "espanol", "spa", "es")),
    ("fre", "French", ("french", "francais", "fre", "fra", "fr")),
    ("ger", "German", ("german", "deutsch", "ger", "deu", "de")),
    ("ita", "Italian", ("italian", "italiano", "ita", "it")),
    ("por", "Portuguese", ("portuguese", "portugues", "por", "pt")),
    ("rus", "Russian", ("russian", "rus", "ru")),
    ("ara", "Arabic", ("arabic", "ara", "ar")),
    ("jpn", "Japanese", ("japanese", "jpn", "ja")),
    ("kor", "Korean", ("korean", "kor", "ko")),
    ("chi", "Chinese", ("chinese", "mandarin", "cantonese", "chi", "zho", "zh")),
    ("tha", "Thai", ("thai", "tha", "th")),
    ("vie", "Vietnamese", ("vietnamese", "vie", "vi")),
    ("ind", "Indonesian", ("indonesian", "bahasa", "ind", "id")),
    ("may", "Malay", ("malay", "melayu", "msa", "ms")),
    ("fil", "Filipino", ("filipino", "tagalog", "fil", "tgl", "tl")),
    ("tur", "Turkish", ("turkish", "turkce", "tur", "tr")),
    ("dut", "Dutch", ("dutch", "nederlands", "dut", "nld", "nl")),
    ("pol", "Polish", ("polish", "polski", "pol", "pl")),
    ("swe", "Swedish", ("swedish", "svenska", "swe", "sv")),
    ("nor", "Norwegian", ("norwegian", "norsk", "nor", "no")),
    ("dan", "Danish", ("danish", "dansk", "dan", "da")),
    ("fin", "Finnish", ("finnish", "suomi", "fin", "fi")),
    ("gre", "Greek", ("greek", "gre", "ell", "el")),
    ("heb", "Hebrew", ("hebrew", "heb", "he")),
    ("per", "Persian", ("persian", "farsi", "per", "fas", "fa")),
    ("ukr", "Ukrainian", ("ukrainian", "ukr", "uk")),
    ("rum", "Romanian", ("romanian", "rum", "ron", "ro")),
    ("hun", "Hungarian", ("hungarian", "magyar", "hun", "hu")),
    ("cze", "Czech", ("czech", "cesky", "cze", "ces", "cs")),
    ("swa", "Swahili", ("swahili", "swa", "sw")),
]

#----- token (>=3 chars: full names + 3-letter codes) -> display label.
#----- 2-letter ISO codes are intentionally excluded from multi-language
#----- filename scanning below (too noisy as bare filename tokens).
_LABEL_BY_TOKEN = {}
for _code, _label, _aliases in LANGUAGES:
    for _alias in _aliases:
        if len(_alias) >= 3:
            _LABEL_BY_TOKEN[_alias] = _label

#----- ISO 639-1 (2-letter) -> display label, for the original-language fallback
_LABEL_BY_ISO1 = {}
for _code, _label, _aliases in LANGUAGES:
    for _alias in _aliases:
        if len(_alias) == 2:
            _LABEL_BY_ISO1[_alias] = _label

_IGNORE_TOKENS = {"dubbed", "dub", "dual", "multi", "sub", "subs", "subtitle", "subtitles"}


#----- Scan a release name / filename for every language it mentions
#----- (multi-audio releases commonly list several, e.g. "Hindi Tamil Telugu").
#----- Returns display labels in order of first appearance, de-duplicated.
def detect_languages(text: str) -> list:
    if not text:
        return []
    tokens = [t for t in re.split(r"[^a-zA-Z0-9]+", text.lower()) if t and t not in _IGNORE_TOKENS]
    found = []
    for token in tokens:
        label = _LABEL_BY_TOKEN.get(token)
        if label and label not in found:
            found.append(label)
    return found


#----- Best-effort language label for a stored ISO 639-1 code (TMDB original_language)
def language_label_for_code(code: str):
    if not code:
        return None
    return _LABEL_BY_ISO1.get(str(code).lower())
