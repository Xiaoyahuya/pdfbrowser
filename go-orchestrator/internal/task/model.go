package task

import "time"

type Status string

const (
	StatusQueued    Status = "queued"
	StatusRunning   Status = "running"
	StatusSucceeded Status = "succeeded"
	StatusFailed    Status = "failed"
	StatusCancelled Status = "cancelled"
)

type Result map[string]any

type Task struct {
	ID         string     `json:"id"`
	Path       string     `json:"path"`
	Kind       string     `json:"kind"`
	Status     Status     `json:"status"`
	Result     Result     `json:"result,omitempty"`
	Error      string     `json:"error,omitempty"`
	CreatedAt  time.Time  `json:"createdAt"`
	StartedAt  *time.Time `json:"startedAt,omitempty"`
	FinishedAt *time.Time `json:"finishedAt,omitempty"`
	Version    uint64     `json:"version"`
}

func clone(t Task) Task {
	if t.Result != nil {
		copied := make(Result, len(t.Result))
		for key, value := range t.Result {
			copied[key] = value
		}
		t.Result = copied
	}
	return t
}
