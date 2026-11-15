#!/usr/bin/env python3
"""Source/resource guards only. Does not compile or execute Android/Go code."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

app = Path(__file__).resolve().parents[2]
java = app / "src/main/java/com/x/client/app"
read = lambda name: (java / (name + ".java")).read_text()
controller, resolver, editor, activity, store, log, presentation, service = map(read, [
    "PreferredIpController", "PreferredIpResolver", "ProfileEditActivity", "PreferredIpActivity",
    "PreferredIpStore", "PreferredIpLog", "PreferredIpPresentation", "TProxyService"])


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


# Original-address safety and final startup precedence (not a runtime integration test).
apply = section(resolver, "public static Decision apply(", "public static String stripComments")
assert apply.index("conflict(p)") < apply.index('"default".equals(mode)') < apply.index('pool.optBoolean("enabled")')
assert 'valid.optString("target_key")' in apply
assert 'address.equals(addresses.getString(j))' in apply
assert 'merged.put(key, String.join(",", relay))' in apply
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

# Every target/IP outcome is retained; recommendation=false must NOT drive compatibility.
prepare = section(controller, "void prepareSave()", "void commitPreview()")
assert 'modes.put(info.id, "independent")' not in prepare
assert 'row.put("results", outcomes)' in prepare
assert 'outcomes.put(diagnostic(address, actual, failure))' in prepare
assert 'Map<String, JSONArray> cache' in prepare and 'fingerprint(target)' in prepare
assert prepare.index('validation.put("state", "completed")') < prepare.index('if (compatibleProfiles == 0)')
assert prepare.index('if (compatibleProfiles == 0)') < prepare.index('preview = new JSONObject()')
assert 'options(target, false, 3, 3, DOWNLOAD)' in prepare
assert 'generation.compareAndSet(epoch, epoch + 1)' in controller
assert '测试来源：' in controller and 'PreferredIpPresentation.source(source)' in controller
compatibility = section(controller, "static boolean compatible(", "private static JSONObject diagnostic(")
assert 'optBoolean("recommended")' not in compatibility
assert 'http_success' in compatibility and '"valid"' in compatibility and 'colo' in compatibility
for guard in ['validationEpoch != generation.get()', '!selectionKey().equals', '!profilesKey().equals', '"pool_revision"', 'publicationEpoch == generation.get()']:
    assert guard in controller, guard
commit = section(controller, "void commitPreview()", "void disable()")
assert commit.index('profilesKey().equals') < commit.index('store.write(next')
assert commit.index('check(preview.getLong("generation")') < commit.index('store.write(next')
assert 'invalidateValidation("保存未完成' in commit
assert 'report.optJSONArray("profiles")' in activity
assert '"completed".equals(report.optString("state"))' in activity
assert '原性能记录，不代表' in activity and '单节点失败不表示 IP 对所有节点失效' in activity

# Display-only provenance, fixed Go/API/storage identifiers and comment preservation.
assert 'MessageDigest.getInstance("SHA-256")' in read("PreferredIpIds")
assert 'row.optJSONArray("source_ids")' in presentation
assert 'fallback(id, row.optString("source_kind"))' in presentation
assert 'hasKind(c, "manual") && !hasKind(c, "third_party")' in presentation
assert 'return row.optString("comment")' in presentation
assert '.put("comment"' not in presentation and '.remove("comment"' not in presentation
assert 'PreferredIpPresentation.sources(r, state.data)' in activity
assert 'PreferredIpPresentation.comment(r, state.data)' in activity
assert 'enabled.setText("参与测试：" + PreferredIpPresentation.source(source))' in activity

# One shared fixed-height history; no raw snapshot JSON, cumulative warnings emitted once.
layout = ET.parse(app / "src/main/res/layout/activity_preferred_ip.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
logs = [n for n in layout.iter() if n.attrib.get(android + "id") == "@+id/preferred_log_scroll"]
assert len(logs) == 1 and logs[0].attrib[android + "layout_height"] == "@dimen/preferred_log_height"
assert logs[0] in list(layout)  # outside all three pages
assert 'snapshot.toString' not in log and 'snap.optJSONArray("warnings")' not in activity
assert 'MAX_LINES = 160, MAX_BYTES = 24576' in log
assert 'lines.size() > MAX_LINES || bytes > MAX_BYTES' in log
assert 'int i = warningsSeen' in log and 'progress.equals(next)' in log
assert '!logScroll.canScrollVertically(1)' in activity
assert 'interaction == logInteraction' in activity and 'logScroll.scrollTo(0, oldY)' in activity
assert 'out.putInt("log_y"' in activity
assert '"5次 HTTPS 请求延迟"' in presentation
assert '"HTTP ' not in activity

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
        kind = "array" if node.tag == "string-array" else node.tag
        if kind in resources and "name" in node.attrib:
            resources[kind].add(node.attrib["name"])
        identifier = node.attrib.get(android + "id", "")
        if identifier.startswith("@+id/"):
            resources["id"].add(identifier[5:])
for text in [activity, editor]:
    for kind, name in re.findall(r"(?<!material\.)R\.(string|dimen|id|array)\.(\w+)", text):
        assert name in resources[kind], (kind, name)

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
