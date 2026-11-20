import copy
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock
from bourse_ai_information.ai import OpenAI, Budget, chunks, validate
from bourse_ai_information.http import FetchError

TEXT="The company denies the bankruptcy rumor and maintains its outlook."
ASSETS=[{"symbol":"DEMO","name":"Demo company"}]


def analysis():
    return {"summary":"L'entreprise dément une rumeur.","uncertainties":["Le démenti doit être recoupé."],"events":[{
        "eventKey":"demo-bankruptcy-denial","title":"Démenti","explanation":"Démenti, sans confirmation indépendante.","quote":TEXT,"speaker":None,"topic":"CORPORATE","kind":"DENIAL",
        "impacts":[{"symbol":"DEMO","direction":"UNKNOWN","horizon":"DAY","importance":50,"relevance":100,"novelty":60,"interpretationConfidence":80}]}]}


class AITests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.budget=Budget(Path(self.tmp.name)/"budget.sqlite",20,1000000)

    def response(self, value=None):
        return {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":json.dumps(value or analysis())}]}]}

    def test_payload_is_structured_and_source_is_untrusted_data(self):
        transport=Mock(return_value=self.response());client=OpenAI("synthetic-key",self.budget,transport)
        self.assertEqual(client.analyze(TEXT,ASSETS,"test-model"),analysis())
        path,raw,mime=transport.call_args.args;request=json.loads(raw)
        self.assertEqual(path,"/v1/responses");self.assertFalse(request["store"]);self.assertTrue(request["text"]["format"]["strict"])
        self.assertEqual(json.loads(request["input"][1]["content"])["untrustedText"],TEXT)

    def test_refusal_incomplete_and_invalid_json_are_not_scores(self):
        responses=[{"status":"incomplete"},{"status":"completed","output":[{"content":[{"type":"refusal"}]}]},{"status":"completed","output":[{"content":[{"type":"output_text","text":"not JSON"}]}]}]
        for response in responses:
            with self.assertRaises(FetchError):OpenAI("synthetic",self.budget,Mock(return_value=response)).analyze(TEXT,ASSETS,"test")

    def test_invented_quotes_and_symbols_are_rejected(self):
        value=analysis();value["events"][0]["quote"]="An invented statement that is not in the source"
        with self.assertRaises(FetchError):validate(value,TEXT,{"DEMO"})
        value=analysis();value["events"][0]["impacts"][0]["symbol"]="INVENTED"
        with self.assertRaises(FetchError):validate(value,TEXT,{"DEMO"})

    def test_invalid_ranges_booleans_and_extra_fields_are_rejected(self):
        for bad in [101,-1,float("nan"),True,"90"]:
            value=analysis();value["events"][0]["impacts"][0]["importance"]=bad
            with self.assertRaises(FetchError):validate(value,TEXT,{"DEMO"})
        value=analysis();value["priceForecast"]=123
        with self.assertRaises(FetchError):validate(value,TEXT,{"DEMO"})

    def test_duplicate_impact_cannot_multiply_event_score(self):
        value=analysis();value["events"][0]["impacts"]*=2
        with self.assertRaises(FetchError):validate(value,TEXT,{"DEMO"})

    def test_budget_is_durable_and_retained_after_error(self):
        path=Path(self.tmp.name)/"one.sqlite";budget=Budget(path,1,10000)
        transport=Mock(side_effect=FetchError("AI_CONNECTION_OR_FORMAT_ERROR"))
        with self.assertRaises(FetchError):OpenAI("synthetic",budget,transport).analyze(TEXT,ASSETS,"test")
        with self.assertRaises(FetchError):Budget(path,1,10000).reserve(1)
        self.assertEqual(transport.call_count,1)

    def test_budget_is_atomic_under_concurrent_workers(self):
        budget=Budget(Path(self.tmp.name)/"atomic.sqlite",3,1000)
        def attempt(_):
            try:budget.reserve(10);return 1
            except FetchError:return 0
        with ThreadPoolExecutor(max_workers=6) as executor:self.assertEqual(sum(executor.map(attempt,range(12))),3)

    def test_no_key_or_budget_prevents_paid_calls(self):
        with self.assertRaises(FetchError):OpenAI("",self.budget)
        with self.assertRaises(FetchError):Budget(Path(self.tmp.name)/"bad",0,10)

    def test_chunks_cover_every_character_without_fabricating_content(self):
        text="".join(str(i%10) for i in range(70000));parts=chunks(text)
        self.assertTrue(all(p in text and len(p)<=16000 for p in parts));self.assertEqual(parts[0],text[:16000]);self.assertEqual(parts[-1][-100:],text[-100:])

    def test_existing_audio_is_sent_only_to_transcription_endpoint(self):
        transport=Mock(return_value={"text":TEXT});client=OpenAI("synthetic",self.budget,transport)
        self.assertEqual(client.transcribe(b"fake-audio","audio/mpeg"),TEXT)
        path,body,mime=transport.call_args.args;self.assertEqual(path,"/v1/audio/transcriptions");self.assertIn(b"gpt-transcribe",body);self.assertIn(b"fake-audio",body)


if __name__=="__main__":unittest.main()
