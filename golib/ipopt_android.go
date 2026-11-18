package xclient

import "xclient/ipopt"

// Preferred-IP task state belongs to the UI process, not activeBackend. Android
// must refuse VPN mixed-path tests and cancel on background/network changes.
var preferredIPTasks = ipopt.NewManager()

// ParsePreferredIPList parses UTF-8 text without making network requests.
func ParsePreferredIPList(content, sourceID, sourceKind string) (string, error) {
	return ipopt.ParseJSON(content, sourceID, sourceKind)
}

// StartPreferredIPTask returns an opaque ID immediately; poll the JSON snapshot.
func StartPreferredIPTask(requestJSON string) (string, error) {
	return preferredIPTasks.Start(requestJSON)
}

// GetPreferredIPTask returns a bounded task snapshot, with no gomobile callbacks.
func GetPreferredIPTask(taskID string) (string, error) {
	return preferredIPTasks.Get(taskID)
}

// CancelPreferredIPTask is idempotent, including expired and unknown task IDs.
func CancelPreferredIPTask(taskID string) {
	preferredIPTasks.Cancel(taskID)
}
