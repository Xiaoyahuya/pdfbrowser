package task

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

var ErrQueueFull = errors.New("task queue is full")

type Metrics struct {
	Submitted atomic.Uint64
	Rejected  atomic.Uint64
	Completed atomic.Uint64
	Failed    atomic.Uint64
	Cancelled atomic.Uint64
}

type Manager struct {
	store     *Store
	processor Processor
	queue     chan string
	timeout   time.Duration
	metrics   Metrics

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	activeMu sync.Mutex
	active   map[string]context.CancelFunc
}

func NewManager(store *Store, processor Processor, workers, capacity int, timeout time.Duration) *Manager {
	if workers < 1 {
		workers = 1
	}
	if capacity < 1 {
		capacity = 1
	}
	ctx, cancel := context.WithCancel(context.Background())
	m := &Manager{
		store: store, processor: processor, queue: make(chan string, capacity), timeout: timeout,
		ctx: ctx, cancel: cancel, active: make(map[string]context.CancelFunc),
	}
	for workerID := 0; workerID < workers; workerID++ {
		m.wg.Add(1)
		go m.worker(workerID)
	}
	return m
}

func (m *Manager) Submit(path, kind string) (Task, error) {
	id, err := randomID()
	if err != nil {
		return Task{}, fmt.Errorf("create task id: %w", err)
	}
	t := Task{ID: id, Path: path, Kind: kind, Status: StatusQueued, CreatedAt: time.Now().UTC(), Version: 1}
	if err := m.store.Create(t); err != nil {
		return Task{}, err
	}
	select {
	case m.queue <- id:
		m.metrics.Submitted.Add(1)
		return t, nil
	default:
		_, _ = m.store.Cancel(id)
		m.metrics.Rejected.Add(1)
		return Task{}, ErrQueueFull
	}
}

func (m *Manager) Get(id string) (Task, bool) { return m.store.Get(id) }
func (m *Manager) List(limit int) []Task      { return m.store.List(limit) }
func (m *Manager) QueueDepth() int            { return len(m.queue) }
func (m *Manager) QueueCapacity() int         { return cap(m.queue) }
func (m *Manager) Metrics() *Metrics          { return &m.metrics }

func (m *Manager) Cancel(id string) (Task, error) {
	t, err := m.store.Cancel(id)
	if err != nil {
		return t, err
	}
	m.activeMu.Lock()
	cancel := m.active[id]
	m.activeMu.Unlock()
	if cancel != nil {
		cancel()
	}
	m.metrics.Cancelled.Add(1)
	return t, nil
}

func (m *Manager) Close(ctx context.Context) error {
	m.cancel()
	done := make(chan struct{})
	go func() {
		m.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (m *Manager) worker(_ int) {
	defer m.wg.Done()
	for {
		select {
		case <-m.ctx.Done():
			return
		case id := <-m.queue:
			m.run(id)
		}
	}
}

func (m *Manager) run(id string) {
	t, ok := m.store.Get(id)
	if !ok || t.Status != StatusQueued {
		return
	}
	t, err := m.store.Start(id)
	if err != nil || t.Status != StatusRunning {
		return
	}
	ctx, cancel := context.WithTimeout(m.ctx, m.timeout)
	m.activeMu.Lock()
	m.active[id] = cancel
	m.activeMu.Unlock()

	result, processErr := m.processor.Process(ctx, t.Path, t.Kind)
	cancel()
	m.activeMu.Lock()
	delete(m.active, id)
	m.activeMu.Unlock()

	_ = m.store.Complete(id, result, processErr)
	current, _ := m.store.Get(id)
	if current.Status == StatusCancelled {
		return
	}
	if processErr != nil {
		m.metrics.Failed.Add(1)
	} else {
		m.metrics.Completed.Add(1)
	}
}

func randomID() (string, error) {
	var buf [12]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf[:]), nil
}
