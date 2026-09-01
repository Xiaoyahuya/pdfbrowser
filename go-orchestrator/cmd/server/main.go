package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"pdfbrowser.local/go-task-service/internal/httpapi"
	"pdfbrowser.local/go-task-service/internal/task"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	workers := envInt("TASK_WORKERS", 4)
	capacity := envInt("TASK_QUEUE_CAPACITY", 64)
	timeout := envDuration("TASK_TIMEOUT", 30*time.Second)
	client := &http.Client{Transport: &http.Transport{
		MaxIdleConns: 64, MaxIdleConnsPerHost: workers, IdleConnTimeout: 90 * time.Second,
	}}
	processor := task.NewRustClient(env("RUST_ENGINE_URL", "http://127.0.0.1:8092"), client)
	manager := task.NewManager(task.NewStore(), processor, workers, capacity, timeout)
	mux := http.NewServeMux()
	mux.Handle("/", httpapi.New(manager, log).Handler())
	mux.Handle("/debug/pprof/", http.DefaultServeMux)
	mux.Handle("/debug/pprof/cmdline", http.DefaultServeMux)
	mux.Handle("/debug/pprof/profile", http.DefaultServeMux)
	mux.Handle("/debug/pprof/symbol", http.DefaultServeMux)
	mux.Handle("/debug/pprof/trace", http.DefaultServeMux)
	server := &http.Server{
		Addr: env("GO_TASK_ADDR", ":8091"), Handler: mux,
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 35 * time.Second, IdleTimeout: 60 * time.Second,
	}
	stopCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		log.Info("go task service listening", "addr", server.Addr, "workers", workers, "queueCapacity", capacity)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("http server failed", "error", err)
			stop()
		}
	}()
	<-stopCtx.Done()
	httpShutdownCtx, cancelHTTP := context.WithTimeout(context.Background(), 10*time.Second)
	if err := server.Shutdown(httpShutdownCtx); err != nil {
		log.Error("http shutdown failed", "error", err)
	}
	cancelHTTP()
	workerShutdownCtx, cancelWorkers := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelWorkers()
	if err := manager.Close(workerShutdownCtx); err != nil {
		log.Error("worker shutdown failed", "error", err)
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	value, err := strconv.Atoi(os.Getenv(key))
	if err != nil || value < 1 {
		return fallback
	}
	return value
}

func envDuration(key string, fallback time.Duration) time.Duration {
	value, err := time.ParseDuration(os.Getenv(key))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}
