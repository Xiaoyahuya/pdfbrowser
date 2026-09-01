package task

import (
	"errors"
	"sort"
	"sync"
	"time"
)

var (
	ErrNotFound       = errors.New("task not found")
	ErrNotCancellable = errors.New("task is already terminal")
)

type Store struct {
	mu    sync.RWMutex
	tasks map[string]Task
}

func NewStore() *Store {
	return &Store{tasks: make(map[string]Task)}
}

func (s *Store) Create(t Task) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.tasks[t.ID]; exists {
		return errors.New("duplicate task id")
	}
	s.tasks[t.ID] = clone(t)
	return nil
}

func (s *Store) Get(id string) (Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	t, ok := s.tasks[id]
	return clone(t), ok
}

func (s *Store) List(limit int) []Task {
	s.mu.RLock()
	items := make([]Task, 0, len(s.tasks))
	for _, t := range s.tasks {
		items = append(items, clone(t))
	}
	s.mu.RUnlock()

	sort.Slice(items, func(i, j int) bool {
		return items[i].CreatedAt.After(items[j].CreatedAt)
	})
	if limit > 0 && len(items) > limit {
		items = items[:limit]
	}
	return items
}

func (s *Store) Start(id string) (Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.tasks[id]
	if !ok {
		return Task{}, ErrNotFound
	}
	if t.Status != StatusQueued {
		return clone(t), nil
	}
	now := time.Now().UTC()
	t.Status = StatusRunning
	t.StartedAt = &now
	t.Version++
	s.tasks[id] = t
	return clone(t), nil
}

func (s *Store) Complete(id string, result Result, processErr error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.tasks[id]
	if !ok {
		return ErrNotFound
	}
	// Cancellation wins over a late downstream response.
	if t.Status == StatusCancelled {
		return nil
	}
	now := time.Now().UTC()
	t.FinishedAt = &now
	t.Version++
	if processErr != nil {
		t.Status = StatusFailed
		t.Error = processErr.Error()
	} else {
		t.Status = StatusSucceeded
		t.Result = result
	}
	s.tasks[id] = t
	return nil
}

func (s *Store) Cancel(id string) (Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.tasks[id]
	if !ok {
		return Task{}, ErrNotFound
	}
	if isTerminal(t.Status) {
		return clone(t), ErrNotCancellable
	}
	now := time.Now().UTC()
	t.Status = StatusCancelled
	t.FinishedAt = &now
	t.Version++
	s.tasks[id] = t
	return clone(t), nil
}

func isTerminal(status Status) bool {
	return status == StatusSucceeded || status == StatusFailed || status == StatusCancelled
}
