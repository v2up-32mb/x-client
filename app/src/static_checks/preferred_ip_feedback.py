#!/usr/bin/env python3
"""Source/resource guards only. Does not compile or execute Android/Go code."""
from pathlib import Path
import re
import json
import runpy
import xml.etree.ElementTree as ET

app = Path(__file__).resolve().parents[2]
java = app / "src/main/java/com/x/client/app"
read = lambda name: (java / (name + ".java")).read_text()
controller, resolver, editor, activity, store, log, presentation, service = map(read, [
    "PreferredIpController", "PreferredIpResolver", "ProfileEditActivity", "PreferredIpActivity",
    "PreferredIpStore", "PreferredIpLog", "PreferredIpPresentation", "TProxyService"])


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


# String.join is API 26; minSdk 24 has no coreLibraryDesugaring. Keep delimiters,
# ordered collections and empty output semantics unchanged; TextUtils.join is API 1.
for file in java.glob("PreferredIp*.java"):
    text = file.read_text()
    assert not re.search(r"\bString\s*(?:\.\s*join\s*\(|::\s*join\b)", text), file
    assert not re.search(r"import\s+static\s+java\.lang\.String\.", text), file
for text, calls in [(resolver, ['TextUtils.join(",", relay)']),
                    (log, ['TextUtils.join("", lines)']),
                    (presentation, ['TextUtils.join("\\n", labels)', 'TextUtils.join(" / ", notes)'])]:
    assert "import android.text.TextUtils;" in text
    for call in calls:
        assert call in text, call

# Original-address safety and final startup precedence (not a runtime integration test).
apply = section(resolver, "public static Decision apply(", "public static String stripComments")
assert apply.index("conflict(p)") < apply.index('"default".equals(mode)') < apply.index('pool.optBoolean("enabled")')
assert 'valid.optString("target_key")' in apply
assert 'address.equals(addresses.getString(j))' in apply
assert 'merged.put(key, TextUtils.join(",", relay))' in apply
assert 'java.util.LinkedHashSet' in apply
assert 'setXtRelayNodes' not in resolver and 'setPrefIp' not in resolver
start = section(service, "private void startProxy(", "private JSONObject buildGCMParams(")
assert start.index("buildXtunnelParams(prefs)") < start.index("PreferredIpResolver.apply") < start.index("Xclient.startSocksProxy")
assert "decision.summary" in start and "effectiveCount(prefs, params)" in start
assert '(long) count * connections' in resolver

# No accidental explicit opt-out for first imports; existing explicit snapshots remain independent.
imp = section(editor, "private void importFromProtocol(", "private boolean confirmXtImportOverrides(")
assert imp.index("!confirmXtImportOverrides(protocol)") < imp.index("boolean implicitImport")
assert 'preferredInitialImplicit && !preferredDraftDirty' in imp
assert 'if (!implicitImport)' in imp and 'preferredMode.setSelection(1)' in imp
assert 'preferredImportedAddresses || preferredMode.getSelectedItemPosition() == 1' in editor
assert 'out.putBoolean("preferred_imported_addresses"' in editor
assert 'preferredInitialImplicit = !pool.optJSONObject("profile_modes").has(profileId)' in editor
assert 'setMode(this, id, "independent")' not in read("ProfileListActivity")

# v2: intent never fabricates proof, validation uses actual profiles and selected subset.
compatibility = section(controller, "static boolean compatible(", "void select(")
assert 'optBoolean("recommended")' not in compatibility
assert 'http_success' in compatibility and '"valid"' in compatibility and 'colo' in compatibility
eligible = section(controller, "private boolean eligibleResult(", "/** Legacy low-level")
for guard in ['probe_complete', 'score_ready', 'score_version', '"reference"', 'target_key', 'resultContexts.get(address) == proof']:
    assert guard in eligible, guard
select = section(controller, "void select(", "private String profilesKey(")
assert 'selectionContextCurrent(proof, profileSignature)' in select
assert 'selectionPending++' in select and 'selectionPending--' in select
assert 'selection_version' in select and 'if (next.length() == 0) root.put("enabled", false)' in select
assert '.put("enabled", true)' not in select and '.put("target_key"' not in select
assert 'publishSelectionSettled(address)' in select
validation = section(controller, "private void validateSelections(", "void disable()")
for guard in ['following(data, p)', 'PreferredIpResolver.target(p, merged)', 'storedSelectionVersion != root.optLong("selection_version")',
              'validationCurrent(proofContext, version, profileSignature)', 'isSelected(root, addresses.getString(i))',
              '"network_id", proofContext.networkId', '"session_id", proofContext.sessionId', 'validationSeen.size() + seen.size() > 8192']:
    assert guard in validation, guard
assert 'if (addresses.length() == 0) return' in validation
assert 'modes.put(info.id, "independent")' not in validation
assert 'row.optBoolean("probe_complete")' in controller
for guard in ['proof.epoch != scanEpoch.get()', '!proof.networkId.equals(net.id())', 'proof != scanContext']:
    assert guard in controller, guard
assert controller.index('changedEpoch = scanEpoch.incrementAndGet()') < controller.index('invalidate("网络已变化')
assert 'private final ExecutorService selectionIO' in controller
assert 'private final ExecutorService networkIO' in controller
assert 'private final ExecutorService canceller' in controller
assert 'snapshot.optBoolean("worker_done")' in controller
for guard in ['download_round_id', 'download_round_limit', 'download_round_used', 'download_round_remaining', 'download_reserved_bytes']:
    assert guard in read("PreferredIpLedger"), guard
assert 'new String[]{"download_bytes", "download_seconds", "download_mib_s", "download_peak_mib_s", "download_state", "download_ech_status"}' in controller
assert 'download ? 52428800 : 20971520' in controller and 'download ? 10 : 5' in controller
assert '.put("recommend_count", 0).put("download_top", 0)' in controller
assert 'original.retryUsed = true' in controller and 'retry.retryUsed = true' in controller

# Display-only provenance, fixed Go/API/storage identifiers and comment preservation.
assert 'MessageDigest.getInstance("SHA-256")' in read("PreferredIpIds")
assert 'row.optJSONArray("source_ids")' in presentation
assert 'fallback(id, row.optString("source_kind"))' in presentation
assert 'hasKind(c, "manual") && !hasKind(c, "third_party")' in presentation
assert 'return row.optString("comment")' in presentation
assert '.put("comment"' not in presentation and '.remove("comment"' not in presentation
assert 'PreferredIpPresentation.sources(candidate, data)' in controller
assert 'PreferredIpPresentation.comment(candidate, data)' in controller
assert 'on.setText("参与测试：" + PreferredIpPresentation.source(source))' in activity

# Two native pages, one stable results list; old log is a data-only regression tool.
layout = ET.parse(app / "src/main/res/layout/activity_preferred_ip.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
assert 'return 2;' in activity and 'setupWithViewPager(pager)' in activity
assert 'new RecyclerView(this)' in activity and 'setHasStableIds(true)' in activity
assert 'PreferredIpLog' not in activity and 'preferred_log_scroll' not in ET.tostring(layout).decode()
assert 'prepareSave' not in controller and 'commitPreview' not in controller
assert 'preferred_selected' not in activity and 'preferred_recommend' not in activity
assert 'MAX_LINES = 160, MAX_BYTES = 24576' in log
assert 'lines.size() > MAX_LINES || bytes > MAX_BYTES' in log
assert 'int i = warningsSeen' in log and 'progress.equals(next)' in log
assert '"5次 HTTPS 请求延迟"' in presentation
assert 'colo = "UKN"' in controller
assert 'setChecked(selectedAddresses.contains(address))' in activity
assert 'getBindingAdapterPosition()' in activity and 'RecyclerView.NO_POSITION' in activity
assert activity.index('adapter.reconcileVisibleSelections(); adapter.submitLatest()') < activity.index('private void renderSources()')
assert 'addOnPreDrawListener' in activity
bind = section(activity, 'private void bindChoiceSelection(', 'void rebindSelection(')
assert bind.index('setOnCheckedChangeListener(null)') < bind.index('setChecked(') < bind.index('setOnCheckedChangeListener((button, on)')
assert 'address.equals(holder.address)' in bind
assert 'notifyDataSetChanged' not in activity

# Original persistent OS locking/CAS is still intact.
assert 'new RandomAccessFile(lockFile, "rw")' in store and 'lock.getChannel().lock()' in store
assert 'return withLock(this::readLocked)' in store
assert 'return withLock(() -> writeLocked(data, expectedRevision))' in store
assert 'stream.getFD().sync()' in store and 'lockFile.delete' not in store

# XML well-formedness and resources referenced by the touched UI.
resources = {"string": set(), "dimen": set(), "id": set(), "array": set()}
for file in (app / "src/main/res").rglob("*.xml"):
    root = ET.parse(file).getroot()
    for node in root.iter():
        kind = "array" if node.tag == "string-array" else node.attrib.get("type", "item") if node.tag == "item" else node.tag
        if kind in resources and "name" in node.attrib:
            resources[kind].add(node.attrib["name"])
        identifier = node.attrib.get(android + "id", "")
        if identifier.startswith("@+id/"):
            resources["id"].add(identifier[5:])
for text in [activity, editor]:
    for kind, name in re.findall(r"(?<!material\.)R\.(string|dimen|id|array)\.(\w+)", text):
        assert name in resources[kind], (kind, name)

# Static registration is compared to the parser, never counted as runtime coverage.
tests = app / "src/androidTest/java/com/x/client/app"
suite = (tests / "PreferredIpFeedbackTest.java").read_text()
v2 = (tests / "PreferredIpV2Test.java").read_text()
continuation = (tests / "PreferredIpV2ContinuationTest.java").read_text()
control_tests = (tests / "PreferredIpTestControl.java").read_text()
ui = (tests / "PreferredIpUiRemainingTest.java").read_text()
runner = (tests / "PreferredIpFeedbackInstrumentation.java").read_text()
protocol = runpy.run_path(str(app / "src/static_checks/preferred_ip_instrumentation.py"))
assert tuple(re.findall(r"suite::(test\w+)", suite)) == protocol["SAFETY_SCENARIOS"]
assert tuple(re.findall(r"PreferredIpV2Test::(test\w+)", v2)) == protocol["V2_SCENARIOS"]
names = section(v2, 'static final String[] NAMES = {', '};')
assert tuple(re.findall(r'"(test\w+)"', names)) == protocol['V2_SCENARIOS']
assert len(protocol["SAFETY_SCENARIOS"]) == 6 and protocol["TOTAL"] == 55
assert tuple(re.findall(r"PreferredIpV2ContinuationTest::(test\w+)", continuation)) == protocol['CONTINUATION_SCENARIOS']
assert tuple(re.findall(r'"(test\w+)"', section(continuation, 'static final String[] NAMES = {', '};'))) == protocol['CONTINUATION_SCENARIOS']
assert 'PreferredIpV2ContinuationTest.run(this, reporter)' in runner
assert 'PreferredIpUiRemainingTest.run(this, reporter)' in runner
assert tuple(re.findall(r"PreferredIpUiRemainingTest::(test\w+)", ui)) == protocol['UI_SCENARIOS']
assert tuple(re.findall(r'"(test\w+)"', section(ui, 'static final String[] NAMES = {', '};'))) == protocol['UI_SCENARIOS']
for seam in ['sendPointerSync', 'addRecyclerListener', 'onItemRangeRemoved', 'RecyclerView.NO_POSITION',
             'executeAndWaitForEvent', 'ACTION_ACCESSIBILITY_FOCUS', 'ACTION_CLEAR_ACCESSIBILITY_FOCUS',
             'getBindingAdapterPosition', 'rows(f) == captured', 'captured, address).checked',
             'mainGate.awaitEnter()', 'f.backend.parseInputs']:
    assert seam in ui, seam
assert 'sendAccessibilityEvent(' not in ui and '.requestFocus(' not in ui
assert 'controller.select(' not in ui and 'reconcileVisibleSelections' not in ui
assert 'new PreferredIpController.Row' not in ui and 'new PreferredIpController.State' not in ui
assert 'Thread.sleep' not in ui and 'String.join' not in ui and 'java.nio.file' not in ui
assert 'automation.setServiceInfo(original)' in ui
assert 'resources.add' in ui and 'pointer.end(MotionEvent.ACTION_CANCEL); f.list().suppressLayout(true)' in ui
assert 'read_bytes().decode("utf-8")' in (app / 'src/static_checks/preferred_ip_instrumentation.py').read_text()
assert 'super.dispatchMessage(message)' in control_tests
assert control_tests.count('super.dispatchMessage(message)') == 1
assert 'reference.set(fixture.controller, this)' in control_tests and 'reference.set(fixture.controller, original)' in control_tests
assert 'task.awaitPoll(2)' in v2 and 'repliesConsumed' not in v2
assert 'new TimeoutException("missing scripted poll reply")' in v2
assert 'Task task = null' in v2 and 'return task.id' in v2
assert 'windowsExited && traceRestored' in v2 and 'fail closed: live case resources retained' in v2
assert 'list.getLayoutManager().detachView(view)' in control_tests
assert 'attachMismatch' in continuation and 'notifications.get()' in continuation
assert 'Os.chmod' in continuation and 'f.store.read()' in continuation
inventory = json.loads((app / 'src/static_checks/preferred_ip_v2_inventory.json').read_text())
assert inventory['status'] == 'partial' and inventory['total_registered'] == protocol['TOTAL']
assert tuple(case['name'] for case in inventory['cases']) == protocol['SCENARIOS']
assert [case['number'] for case in inventory['cases']] == list(range(1, protocol['TOTAL'] + 1))
assert inventory['mandatory_not_implemented']
assert all(case['execution'] == 'NOT RUN; Actions only' for case in inventory['cases'])
assert len(inventory['findings_map']) == 7 and len(inventory['groups']) == 12
assert inventory['stage']['completed_stage'] == 'ui_remaining'
assert inventory['stage']['stage_complete'] is True
assert inventory['blocking_production_findings'][0]['id'] == 'P-anchor-margin-coordinate'
assert 'getDecoratedTop' in ui and 'top == v.getTop()' in ui
assert 'height == v.getHeight()' in ui and 'middleByGesture' in ui
assert 'holdProbeAddress' in v2
assert all(group['status'] == 'source-implemented' for group in inventory['groups'] if group['id'] in ('G1', 'G3'))
assert suite.index('finally { suite.tearDown(); }') < suite.index('reporter.passed(i + 1, names[i])')
assert 'assertTrue(edit.commit())' in suite
assert 'extends Instrumentation' in runner
assert 'Build.VERSION.SDK_INT != 24' in runner and '"com.x.client.app.trial".equals' in runner
assert 'catch (Throwable failure)' in runner and 'finish(Activity.RESULT_CANCELED, result)' in runner
assert 'passed != TOTAL' in runner and 'finish(Activity.RESULT_OK, result)' in runner
assert 'PreferredIpV2Test.run(this, reporter)' in runner
assert 'startActivitySync' in v2 and 'performClick()' in v2 and 'dispatchTouchEvent' in v2
assert 'callActivityOnStop(activity)' in v2 and 'callActivityOnRestart(activity)' in v2
assert 'SettingsActivity.class' in v2 and 'same.isDestroyed()' in v2
assert 'controller.select(' not in v2 and 'reconcileVisibleSelections' not in v2
assert 'stopCycle()' in v2 and 'selectionPending' in v2 and 'new ReadFault(f.dir)' in v2
assert 'getApplicationContext() { return this; }' in v2
assert 'Thread.sleep' not in v2 and 'String.join' not in v2 and 'java.nio.file' not in v2
for file in tests.glob("*.java"):
    assert not re.search(r"import (?:org\.junit|android\.test\.|androidx\.test\.)", file.read_text()), file
manifest = ET.parse(app / "src/androidTest/AndroidManifest.xml").getroot()
for library in manifest.iter("uses-library"):
    assert library.attrib["{http://schemas.android.com/tools}node"] == "remove"
build = (app / "build.gradle").read_text()
assert 'if (trialBuild) {\n            testInstrumentationRunner "com.x.client.app.PreferredIpFeedbackInstrumentation"' in build

# Lexical delimiter/string sanity only, explicitly not a Java parser/compiler.
for file in list(java.glob("PreferredIp*.java")) + [java / "ProfileEditActivity.java", java / "ProfileListActivity.java", java / "TProxyService.java"] + list((app / "src/androidTest").rglob("*.java")):
    text = file.read_text()
    stripped = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', "", text, flags=re.S)
    stack = []
    for c in stripped:
        if c in "({[": stack.append(c)
        elif c in ")}]":
            assert stack and "({["[")}]".index(c)] == stack.pop(), file
    assert not stack, file
print("PASS: source guards, XML/resources and lexical sanity ONLY; no Android/Go build or runtime tests")
