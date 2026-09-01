package task

import (
	"context"
	"errors"
	"testing"
	"time"
)

type blockingProcessor struct{ started chan struct{} }

func (p *blockingProcessor) Process(ctx context.Context, _, _ string) (Result, error) {
	select {
	case p.started <- struct{}{}:
	default:
	}
	<-ctx.Done()
	return nil, ctx.Err()
}

func TestCancelRunningTask(t *testing.T) {
	processor := &blockingProcessor{started: make(chan struct{}, 1)}
	m := NewManager(NewStore(), processor, 1, 1, time.Minute)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = m.Close(ctx)
	})
	created, err := m.Submit("notes/a.md", "markdown")
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-processor.started:
	case <-time.After(time.Second):
		t.Fatal("processor did not start")
	}
	if _, err := m.Cancel(created.ID); err != nil {
		t.Fatal(err)
	}
	current, _ := m.Get(created.ID)
	if current.Status != StatusCancelled {
		t.Fatalf("expected cancelled, got %s", current.Status)
	}
}

func TestStoreRejectsTerminalCancellation(t *testing.T) {
	store := NewStore()
	item := Task{ID: "one", Status: StatusQueued, CreatedAt: time.Now()}
	if err := store.Create(item); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Start(item.ID); err != nil {
		t.Fatal(err)
	}
	if err := store.Complete(item.ID, Result{"ok": true}, nil); err != nil {
		t.Fatal(err)
	}
	_, err := store.Cancel(item.ID)
	if !errors.Is(err, ErrNotCancellable) {
		t.Fatalf("expected ErrNotCancellable, got %v", err)
	}
}
