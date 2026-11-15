#!/usr/bin/env python3
"""Fail closed on raw `am instrument -w -r` output; its exit code alone is unsafe.

--self-test checks synthetic protocol transcripts ONLY, never Android behavior.
The CI invocation with a report path checks the real emulator's recorded output.
"""
from pathlib import Path
import re
import sys

SCENARIOS = (
    "testThreeSavedIpsReplaceSixOnlyAfterValidation",
    "testSubsetAndNonAdoptionReasons",
    "testUnknownChangedTargetAndAdvancedConflictStayOriginal",
    "testNoDownloadRecommendationFalseIsNotFailure",
    "testThirdPartyCommentHiddenAndManualProvenanceRecovered",
    "testReadableLogDeduplicatesAndBounds",
)
TOTAL = 6


def validate(text):
    text = text.replace("\r", "")
    if re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|\bshortMsg=|\bFAIL(?:URE)?\b", text):
        raise ValueError("instrumentation reported a failure/crash")
    expected = [f"PASS {i}/{TOTAL} {name}" for i, name in enumerate(SCENARIOS, 1)]
    cases = re.findall(r"^INSTRUMENTATION_STATUS: preferred_ip_case=(.*)$", text, re.M)
    if cases != expected:
        raise ValueError(f"expected exactly six ordered named PASS records, got {cases!r}")
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (.*)$", text, re.M)
    if codes != ["0"] * TOTAL:
        raise ValueError(f"unexpected scenario status codes: {codes!r}")
    for key, value in {"preferred_ip_result": "PASS 6/6", "preferred_ip_passed": "6",
                       "preferred_ip_total": "6", "preferred_ip_api": "24"}.items():
        values = re.findall(rf"^INSTRUMENTATION_RESULT: {key}=(.*)$", text, re.M)
        if values != [value]:
            raise ValueError(f"missing/incorrect final {key}: {values!r}")
    if re.findall(r"^INSTRUMENTATION_CODE: (.*)$", text, re.M) != ["-1"]:
        raise ValueError("missing/incorrect final instrumentation success code (-1)")
    if not text.rstrip().endswith("\nINSTRUMENTATION_CODE: -1"):
        raise ValueError("output did not end with instrumentation success")
    return cases


def self_test():
    # Protocol fixtures, NOT a substitute for the six Android scenarios.
    good = "".join(f"INSTRUMENTATION_STATUS: preferred_ip_case=PASS {i}/{TOTAL} {name}\n"
                   "INSTRUMENTATION_STATUS_CODE: 0\n" for i, name in enumerate(SCENARIOS, 1))
    good += ("INSTRUMENTATION_RESULT: preferred_ip_result=PASS 6/6\n"
             "INSTRUMENTATION_RESULT: preferred_ip_passed=6\n"
             "INSTRUMENTATION_RESULT: preferred_ip_total=6\n"
             "INSTRUMENTATION_RESULT: preferred_ip_api=24\n"
             "INSTRUMENTATION_CODE: -1\n")
    validate(good)
    validate(good.replace("\n", "\r\n"))
    bad = ["", "INSTRUMENTATION_FAILED: unable to find instrumentation\n",
           "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n",
           good.replace("PASS 6/6", "FAIL 5/6"),
           good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
           good.replace("INSTRUMENTATION_CODE: -1\n", ""),
           good.replace("preferred_ip_api=24", "preferred_ip_api=26"),
           good.replace("preferred_ip_passed=6", "preferred_ip_passed=5"),
           good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
           good + "INSTRUMENTATION_FAILED: crash after output\n",
           good + "INSTRUMENTATION_CODE: -1\n"]
    for name in SCENARIOS:
        bad.append(re.sub(rf"^INSTRUMENTATION_STATUS: preferred_ip_case=.*{name}\n", "", good, flags=re.M))
    for text in bad:
        try:
            validate(text)
        except ValueError:
            continue
        raise AssertionError("accepted malformed/failed synthetic transcript")
    print(f"PASS: instrumentation parser protocol guards ({len(bad)} rejected synthetic reports); NOT Android tests")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        try:
            cases = validate(Path(sys.argv[1]).read_text(encoding="utf-8"))
        except (OSError, ValueError) as failure:
            sys.exit(f"::error::API 24 offline instrumentation validation failed: {failure}")
        print("\n".join(cases))
        print("PASS 6/6: API 24 emulator offline JSON/Preferences/app-code scenarios; not physical-device, UI or network validation")
    else:
        sys.exit("usage: preferred_ip_instrumentation.py REPORT | --self-test")
