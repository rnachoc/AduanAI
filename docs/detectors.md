# Detectors

Deterministic detectors find sensitive values in arguments and results (plan §5.7). Each finds
candidates with a pattern and keeps only those that pass a validity check.

| Id | Finds | Validity check |
|---|---|---|
| `email` | E-mail addresses | Local part at most 64 characters, no leading, trailing or double dots; whole address at most 254 |
| `phone_es` | Spanish phone numbers, with or without `+34` / `0034` | Starts with 6, 7, 8 or 9; 9 digits, not part of a longer number |
| `dni_nie` | DNI (8 digits and a letter), NIE (X, Y or Z, 7 digits and a letter) | Control letter (`TRWAGMYFPDXBNJZSQVHLCKE`, number mod 23) |
| `iban` | IBANs, compact or in groups of four | ISO 13616 mod 97 equals 1 |
| `card` | Payment card numbers, with spaces or dashes | 13 to 19 digits and the Luhn check digit |
| `secret` | Tokens in published formats (AWS, GitHub, Slack, Stripe live, Google API, Aduana `adn_`, JWT, private key headers) and `secret/token/password/api_key = value` assignments | Assignments need a value of 20+ characters with at least 3.5 bits of entropy per character |

The secret formats are written from the vendors' documented prefixes, not copied from gitleaks.

## What happens with a match

| Where | What |
|---|---|
| Arguments, in the log and the approval queue | Always replaced by `[REDACTED:<id>:<hint>]`; the hint is the first character and domain of an e-mail, the first and last four characters of an IBAN, the last four digits of a card, the last three characters otherwise; secrets get no hint. `args_hash` is computed over the original arguments |
| Arguments, `deny_args_containing` | The call is denied (`POLICY_DENIED`, reason `ARGS_CONTAIN_<ID>`) |
| Arguments, `flag_args_containing` | The entry gets the flag `SENSITIVE_<ID>` |
| Results, `redact_result` | Replaced by `[REDACTED:<id>]` before the agent sees the result; the entry gets `REDACTED` |

## Measured precision and recall

Measured on 2026-11-14 on the labelled set in
[`gateway/src/test/resources/detectors/labelled.yaml`](../gateway/src/test/resources/detectors/labelled.yaml):
for each detector, 10 texts that contain a value and 10 near misses (a DNI with the wrong letter, an IBAN
one digit off, a card that fails Luhn, a phone with a foreign prefix). A text counts as found when the
detector reports at least one match in it. `DetectorsTest.the_published_precision_and_recall_are_the_measured_ones`
recomputes this table on every build and fails if it differs.

| Detector | Positives | Negatives | Found | False alarms | Precision | Recall |
|---|---|---|---|---|---|---|
| `email` | 10 | 10 | 10 | 0 | 1.00 | 1.00 |
| `phone_es` | 10 | 10 | 10 | 1 | 0.91 | 1.00 |
| `dni_nie` | 10 | 10 | 10 | 0 | 1.00 | 1.00 |
| `iban` | 10 | 10 | 10 | 0 | 1.00 | 1.00 |
| `card` | 10 | 10 | 10 | 0 | 1.00 | 1.00 |
| `secret` | 10 | 10 | 10 | 0 | 1.00 | 1.00 |

A set of 20 texts per detector measures the detectors on these texts only: it is a regression check,
not an estimate of how they do on real traffic. The phone false alarm is `+33 600 000 001`: after a
foreign prefix and a space, the local part still looks like a Spanish mobile number.

## Injection marks

[`injection-patterns.yaml`](../gateway/src/main/resources/injection-patterns.yaml) (version 1) lists
phrases that address an agent: "ignore previous instructions", a system or administrator voice, "AI
agents", a new identity, secrecy towards the user, claims of authorisation, instructions hidden in HTML
comments, requests to send data somewhere. A match in a result adds `INJECTION_PATTERN` and
`INJECTION_PATTERN:<id>:v<version>` to the entry, puts a notice before the result's content and lists
the patterns in the result's `_meta["aduana/flags"]`. It never blocks. A match in a tool description is
stored with the tool and shown in the catalog. Paraphrases and other languages escape these patterns.
