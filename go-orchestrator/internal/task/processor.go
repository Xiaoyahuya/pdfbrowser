package task

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

type Processor interface {
	Process(ctx context.Context, path, kind string) (Result, error)
}

type RustClient struct {
	baseURL string
	client  *http.Client
}

func NewRustClient(baseURL string, client *http.Client) *RustClient {
	return &RustClient{baseURL: strings.TrimRight(baseURL, "/"), client: client}
}

func (c *RustClient) Process(ctx context.Context, path, kind string) (Result, error) {
	body, err := json.Marshal(map[string]string{"path": path, "kind": kind})
	if err != nil {
		return nil, fmt.Errorf("encode request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/v1/analyze", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("call rust engine: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, fmt.Errorf("read rust response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("rust engine returned %s: %s", resp.Status, strings.TrimSpace(string(raw)))
	}
	var result Result
	if err := json.Unmarshal(raw, &result); err != nil {
		return nil, fmt.Errorf("decode rust response: %w", err)
	}
	return result, nil
}
