package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"pdfbrowser.local/go-task-service/internal/task"
)

type Server struct {
	manager *task.Manager
	log     *slog.Logger
}

func New(manager *task.Manager, log *slog.Logger) *Server {
	return &Server{manager: manager, log: log}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("GET /metrics", s.metrics)
	mux.HandleFunc("POST /v1/tasks", s.createTask)
	mux.HandleFunc("GET /v1/tasks", s.listTasks)
	mux.HandleFunc("GET /v1/tasks/{id}", s.getTask)
	mux.HandleFunc("DELETE /v1/tasks/{id}", s.cancelTask)
	return s.recoverPanic(s.accessLog(mux))
}

type createRequest struct {
	Path string `json:"path"`
	Kind string `json:"kind"`
}

func (s *Server) createTask(w http.ResponseWriter, r *http.Request) {
	var input createRequest
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_json", "request body must contain exactly one JSON object")
		return
	}
	input.Path = strings.TrimSpace(input.Path)
	input.Kind = strings.ToLower(strings.TrimSpace(input.Kind))
	if input.Path == "" || (input.Kind != "markdown" && input.Kind != "pdf") {
		writeError(w, http.StatusBadRequest, "invalid_task", "path is required and kind must be markdown or pdf")
		return
	}
	created, err := s.manager.Submit(input.Path, input.Kind)
	if errors.Is(err, task.ErrQueueFull) {
		w.Header().Set("Retry-After", "1")
		writeError(w, http.StatusServiceUnavailable, "queue_full", "task queue is full; retry later")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "submit_failed", err.Error())
		return
	}
	w.Header().Set("Location", "/v1/tasks/"+created.ID)
	writeJSON(w, http.StatusAccepted, created)
}

func (s *Server) listTasks(w http.ResponseWriter, r *http.Request) {
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 500 {
			writeError(w, http.StatusBadRequest, "invalid_limit", "limit must be between 1 and 500")
			return
		}
		limit = parsed
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": s.manager.List(limit)})
}

func (s *Server) getTask(w http.ResponseWriter, r *http.Request) {
	item, ok := s.manager.Get(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "not_found", "task not found")
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (s *Server) cancelTask(w http.ResponseWriter, r *http.Request) {
	item, err := s.manager.Cancel(r.PathValue("id"))
	if errors.Is(err, task.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "task not found")
		return
	}
	if errors.Is(err, task.ErrNotCancellable) {
		writeError(w, http.StatusConflict, "already_terminal", "task is already terminal")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cancel_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok", "queueDepth": s.manager.QueueDepth(), "queueCapacity": s.manager.QueueCapacity(),
	})
}

func (s *Server) metrics(w http.ResponseWriter, _ *http.Request) {
	m := s.manager.Metrics()
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	fmt.Fprintf(w, "pdf_tasks_submitted_total %d\n", m.Submitted.Load())
	fmt.Fprintf(w, "pdf_tasks_rejected_total %d\n", m.Rejected.Load())
	fmt.Fprintf(w, "pdf_tasks_completed_total %d\n", m.Completed.Load())
	fmt.Fprintf(w, "pdf_tasks_failed_total %d\n", m.Failed.Load())
	fmt.Fprintf(w, "pdf_tasks_cancelled_total %d\n", m.Cancelled.Load())
	fmt.Fprintf(w, "pdf_tasks_queue_depth %d\n", s.manager.QueueDepth())
	fmt.Fprintf(w, "pdf_tasks_queue_capacity %d\n", s.manager.QueueCapacity())
}

func (s *Server) accessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.log.Info("http request", "method", r.Method, "path", r.URL.Path)
		next.ServeHTTP(w, r)
	})
}

func (s *Server) recoverPanic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				s.log.Error("panic recovered", "panic", recovered, "path", r.URL.Path)
				writeError(w, http.StatusInternalServerError, "internal_error", "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
