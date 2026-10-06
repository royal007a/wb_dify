"""Offline transcription of C067 pp7-8 demo expressions, not its full repository.

Synthetic in-memory SQLite ledger only; no Hify, model, payment or external IO.
Assertions lock down observed demo semantics, including undesirable acceptance.
"""
import json
import sqlite3


def gate(output, con):
    try:
        artifact = json.loads(output)
        trusted_total = con.execute('SELECT total FROM trusted_ledger').fetchone()[0]
        return (artifact.get('status') == 'RECONCILED'
                and int(artifact['delta']) == 0
                and int(artifact['total']) == trusted_total)
    except (json.JSONDecodeError, KeyError, TypeError, ValueError):
        return False


def main():
    with sqlite3.connect(':memory:') as con:
        con.execute('CREATE TABLE trusted_ledger(total INTEGER NOT NULL)')
        con.execute('INSERT INTO trusted_ledger VALUES (3100000)')
        cases = [
            ('valid', '{"status":"RECONCILED","delta":0,"total":3100000}', True),
            ('wrong_total', '{"status":"RECONCILED","delta":0,"total":9999999}', False),
            ('fractional_total', '{"status":"RECONCILED","delta":0,"total":3100000.9}', True),
            ('fractional_delta', '{"status":"RECONCILED","delta":0.9,"total":3100000}', True),
            ('boolean_delta', '{"status":"RECONCILED","delta":false,"total":3100000}', True),
            ('numeric_strings', '{"status":"RECONCILED","delta":"0","total":"3100000"}', True),
            ('duplicate_total', '{"status":"RECONCILED","delta":0,"total":9999999,"total":3100000}', True),
        ]
        observations = []
        for name, payload, expected in cases:
            actual = gate(payload, con)
            assert actual is expected, (name, actual, expected)
            observations.append({'case': name, 'acceptedByDemo': actual})
        try:
            gate('[]', con)
        except AttributeError:
            observations.append({'case': 'array_root', 'escapedException': 'AttributeError'})
        else:
            raise AssertionError('Expected uncaught AttributeError for array root')
        fake = 'not JSON, merely mentioning status delta total'
        keys_accepted = all(key in fake for key in ['status', 'delta', 'total'])
        assert keys_accepted
        observations.append({'case': 'keys_are_substrings', 'acceptedByKeysGate': keys_accepted})
    print(json.dumps({'scope': 'course excerpt replay, not Hify or upstream repo acceptance',
                      'observations': observations}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
