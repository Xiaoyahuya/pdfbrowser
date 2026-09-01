package httpapi

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"pdfbrowser.local/go-task-service/internal/task"
)

type immediateProcessor struct{}

func (immediateProcessor) Process(context.Context, string, string) (task.Result, error) {
	return task.Result{"ok": true}, nil
}

func TestCreateAndGetTask(t *testing.T) {
	manager := task.NewManager(task.NewStore(), immediateProcessor{}, 1, 4, time.Second)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = manager.Close(ctx)
	})
	server := New(manager, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler()
	req := httptest.NewRequest(http.MethodPost, "/v1/tasks", strings.NewReader(`{"path":"a.md","kind":"markdown"}`))
	resp := httptest.NewRecorder()
	server.ServeHTTP(resp, req)
	if resp.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", resp.Code, resp.Body.String())
	}
	if resp.Header().Get("Location") == "" {
		t.Fatal("missing Location header")
	}
}

func TestRejectsUnknownField(t *testing.T) {
	manager := task.NewManager(task.NewStore(), immediateProcessor{}, 1, 4, time.Second)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = manager.Close(ctx)
	})
	server := New(manager, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler()
	req := httptest.NewRequest(http.MethodPost, "/v1/tasks", strings.NewReader(`{"path":"a.md","kind":"markdown","surprise":1}`))
	resp := httptest.NewRecorder()
	server.ServeHTTP(resp, req)
	if resp.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp.Code)
	}
}

func TestRejectsTrailingJSONValue(t *testing.T) {
	manager := task.NewManager(task.NewStore(), immediateProcessor{}, 1, 4, time.Second)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = manager.Close(ctx)
	})
	server := New(manager, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler()
	req := httptest.NewRequest(http.MethodPost, "/v1/tasks", strings.NewReader(
		`{"path":"a.md","kind":"markdown"} {"path":"b.md","kind":"markdown"}`,
	))
	resp := httptest.NewRecorder()
	server.ServeHTTP(resp, req)
	if resp.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp.Code)
	}
}
