#!/usr/bin/env python3
"""Strict API24 raw instrumentation report parser, not a product test.

--self-test exercises synthetic protocol ONLY. A complete report proves only the
registered cases; this partial test lane does NOT cover the entire v2 contract.
"""
from pathlib import Path
import re
import sys

SAFETY_SCENARIOS = (
    "testThreeSavedIpsReplaceSixOnlyAfterValidation",
    "testSubsetAndNonAdoptionReasons",
    "testUnknownChangedTargetAndAdvancedConflictStayOriginal",
    "testNoDownloadRecommendationFalseIsNotFailure",
    "testThirdPartyCommentHiddenAndManualProvenanceRecovered",
    "testReadableLogDeduplicatesAndBounds",
)
V2_SCENARIOS = (
    "testNativeTabsSwipeHeadingAndIpv6",
    "testNativeSelectionValidationAndLastDisable",
    "testForegroundAtomicReadAddFailure",
    "testForegroundAtomicReadRemoveFailure",
    "testStoppedAtomicReadAddFailure",
    "testStoppedAtomicReadRemoveFailure",
    "testSettingsCoverReturnsSameActivity",
    "testPendingNativeIntentUsesDiskUntilSettled",
    "testNetworkEpochAndAbaRejectQueuedNativeAdd",
    "testStoreMonitorRejectsQueuedNativeAdd",
    "testBlockedPollCancelAndWorkerDoneResume",
    "testDownloadSixFieldsAndDurableBudget",
    "testHistoryAtomicFilesWhitelistAndLimits",
    "testHistoryMigrationTransferFailurePreservesRoot",
    "testLedgerUncertainReservationAndExplicitRound",
)
SCENARIOS = SAFETY_SCENARIOS + V2_SCENARIOS
TOTAL = len(SCENARIOS)


def validate(text):
    text = text.replace("\r\n", "\n")
    if "\r" in text or re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|\bshortMsg=|\bFAIL(?:URE)?\b|FATAL EXCEPTION", text):
        raise ValueError("instrumentation reported failure/crash/invalid line endings")
    expected = [f"PASS {i}/{TOTAL} {name}" for i, name in enumerate(SCENARIOS, 1)]
    cases, result, pending, ended = [], {}, None, False
    for line in text.splitlines():
        if ended:
            if line.strip():
                raise ValueError("trailing output after final code")
            continue
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if result or pending is not None:
                raise ValueError("status outside ordered case block")
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if not sep or key != "preferred_ip_case":
                raise ValueError("unexpected status field")
            pending = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            if pending is None or line != "INSTRUMENTATION_STATUS_CODE: 0" or result:
                raise ValueError("missing case/incorrect status code")
            cases.append(pending)
            pending = None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if pending is not None or cases != expected:
                raise ValueError("final bundle before complete ordered cases")
            key, sep, value = line[len("INSTRUMENTATION_RESULT: "):].partition("=")
            if not sep or key in result:
                raise ValueError("malformed/duplicate result field")
            result[key] = value
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if pending is not None or line != "INSTRUMENTATION_CODE: -1":
                raise ValueError("incorrect final code")
            ended = True
        elif line.startswith("INSTRUMENTATION_"):
            raise ValueError("unexpected instrumentation record")
        elif line.strip():
            raise ValueError("unexpected non-protocol output")
    wanted = {"preferred_ip_result": f"PASS {TOTAL}/{TOTAL}", "preferred_ip_passed": str(TOTAL),
              "preferred_ip_total": str(TOTAL), "preferred_ip_api": "24"}
    if not ended or pending is not None or cases != expected or result != wanted:
        raise ValueError("missing/extra/incorrect case, final count, API or result")
    return cases


def synthetic_good():
    text = "".join(f"INSTRUMENTATION_STATUS: preferred_ip_case=PASS {i}/{TOTAL} {name}\n"
                   "INSTRUMENTATION_STATUS_CODE: 0\n" for i, name in enumerate(SCENARIOS, 1))
    return text + (f"INSTRUMENTATION_RESULT: preferred_ip_result=PASS {TOTAL}/{TOTAL}\n"
                   f"INSTRUMENTATION_RESULT: preferred_ip_passed={TOTAL}\n"
                   f"INSTRUMENTATION_RESULT: preferred_ip_total={TOTAL}\n"
                   "INSTRUMENTATION_RESULT: preferred_ip_api=24\nINSTRUMENTATION_CODE: -1\n")


def self_test():
    good = synthetic_good()
    validate(good)
    validate(good.replace("\n", "\r\n"))
    blocks = good.splitlines(keepends=True)
    bad = ["", "INSTRUMENTATION_FAILED: missing runner\n", good.replace(f"PASS {TOTAL}/{TOTAL}", "FAIL"),
           good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
           good.replace("INSTRUMENTATION_CODE: -1\n", ""),
           good.replace("preferred_ip_api=24", "preferred_ip_api=26"),
           good.replace(f"preferred_ip_passed={TOTAL}", "preferred_ip_passed=6"),
           good.replace(f"preferred_ip_total={TOTAL}", "preferred_ip_total=6"),
           good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
           good + "INSTRUMENTATION_CODE: -1\n", good + "FATAL EXCEPTION\n",
           good.replace("INSTRUMENTATION_CODE:", "INSTRUMENTATION_RESULT: shortMsg=crash\nINSTRUMENTATION_CODE:"),
           good.replace("preferred_ip_api=24", "preferred_ip_api=24\nINSTRUMENTATION_RESULT: extra=PASS"),
           "".join(blocks[2:4] + blocks[0:2] + blocks[4:]),
           "".join(blocks[1:2] + blocks[0:1] + blocks[2:]),
           good.replace("INSTRUMENTATION_RESULT: preferred_ip_api=24\n", ""),
           good.replace("INSTRUMENTATION_RESULT: preferred_ip_api=24", "INSTRUMENTATION_RESULT: preferred_ip_api=24\nINSTRUMENTATION_RESULT: preferred_ip_api=24")]
    for i in range(TOTAL):
        bad += ["".join(blocks[:2*i] + blocks[2*i+2:]),
                "".join(blocks[:2*i] + blocks[2*i:2*i+2] + blocks[2*i:])]
    for transcript in bad:
        try:
            validate(transcript)
        except ValueError:
            continue
        raise AssertionError("accepted malformed synthetic protocol")
    print(f"PASS: synthetic protocol only ({len(bad)} rejected reports); NOT Android behavior")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        try:
            cases = validate(Path(sys.argv[1]).read_text(encoding="utf-8"))
        except (OSError, ValueError) as failure:
            sys.exit(f"::error::API24 instrumentation report rejected: {failure}")
        print("\n".join(cases))
        print(f"PASS {TOTAL}/{TOTAL}: registered API24 safety/native-widget cases only; incomplete v2 contract, no public-network/VPN evidence")
    else:
        sys.exit("usage: preferred_ip_instrumentation.py REPORT | --self-test")
